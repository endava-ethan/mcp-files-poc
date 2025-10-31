package dev.poc.files.transport;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import dev.poc.files.config.McpTransportProperties;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransport;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Custom {@link McpStreamableServerTransportProvider} that exposes the MCP server over WebSockets.
 * Each WebSocket connection hosts a single {@link McpStreamableServerSession}, streaming JSON-RPC
 * messages bidirectionally. The provider logs every inbound and outbound message payload for easy
 * traceability during development.
 */
public class WebSocketMcpTransportProvider extends TextWebSocketHandler implements McpStreamableServerTransportProvider {

	private static final Logger logger = LoggerFactory.getLogger(WebSocketMcpTransportProvider.class);

	private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<>() {
	};

	private final String endpoint;

	private final Duration keepAliveInterval;

	private final McpJsonMapper jsonMapper = McpJsonMapper.getDefault();

	private final ConcurrentHashMap<String, ActiveSession> sessionsByWebSocketId = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<String, ActiveSession> sessionsBySessionId = new ConcurrentHashMap<>();

	private volatile McpStreamableServerSession.Factory sessionFactory;

	/**
	 * Create a transport provider configured via {@link McpTransportProperties}.
	 * @param properties transport configuration sourced from application properties
	 */
	public WebSocketMcpTransportProvider(McpTransportProperties properties) {
		this(properties.getEndpoint(), properties.getKeepAliveInterval());
	}

	/**
	 * Create a transport provider targeting the supplied endpoint.
	 * @param endpoint WebSocket endpoint path
	 * @param keepAliveInterval optional heartbeat interval (currently unused)
	 */
	public WebSocketMcpTransportProvider(String endpoint, Duration keepAliveInterval) {
		this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
		this.keepAliveInterval = keepAliveInterval;
		logger.info("Initialized WebSocket MCP transport on endpoint {}", this.endpoint);
		if (keepAliveInterval != null) {
			logger.info("WebSocket transport keep-alive interval configured at {}", keepAliveInterval);
		}
	}

	/**
	 * Retrieve the configured endpoint path.
	 * @return endpoint path used for the WebSocket mapping
	 */
	public String getEndpoint() {
		return this.endpoint;
	}

	@Override
	public void setSessionFactory(McpStreamableServerSession.Factory sessionFactory) {
		this.sessionFactory = sessionFactory;
		logger.debug("Session factory set for WebSocket transport");
	}

	@Override
	public Mono<Void> notifyClients(String topic, Object payload) {
		logger.info("Broadcasting notification {} with payload {}", topic, payload);
		return Flux.fromIterable(this.sessionsBySessionId.values())
			.flatMap(active -> active.session.sendNotification(topic, payload)
				.doOnSubscribe(sub -> logger.debug("Sending notification {} to session {}", topic, active.session.getId()))
				.doOnError(ex -> logger.error("Failed to notify session {}", active.session.getId(), ex))
				.onErrorResume(ex -> Mono.empty()))
			.then();
	}

	@Override
	public Mono<Void> closeGracefully() {
		logger.info("Closing WebSocket transport");
		return Flux.fromIterable(this.sessionsByWebSocketId.values())
			.flatMap(active -> closeActiveSession(active).onErrorResume(ex -> {
				logger.warn("Failed to close session {}", active.session.getId(), ex);
				return Mono.empty();
			}))
			.then();
	}

	@Override
	protected void handleTextMessage(WebSocketSession socketSession, TextMessage message) {
		String payload = message.getPayload();
		logger.info("Received message on WebSocket {}: {}", socketSession.getId(), payload);

		Map<String, Object> envelope;
		try {
			envelope = this.jsonMapper.readValue(payload, MAP_TYPE);
		}
		catch (IOException ex) {
			logger.warn("Failed to parse JSON payload on session {}", socketSession.getId(), ex);
			sendErrorResponse(socketSession, null, -32700, "Failed to parse request", Map.of("message", ex.getMessage()), null)
				.subscribe();
			return;
		}

		Object method = envelope.get("method");
		Object id = envelope.get("id");
		ActiveSession active = this.sessionsByWebSocketId.get(socketSession.getId());

		if (method != null && id != null) {
			McpSchema.JSONRPCRequest request = this.jsonMapper.convertValue(envelope, McpSchema.JSONRPCRequest.class);
			if (active == null) {
				handleInitialize(socketSession, request);
			}
			else {
				handleRequest(active, request);
			}
			return;
		}

		if (method != null) {
			McpSchema.JSONRPCNotification notification = this.jsonMapper.convertValue(envelope,
					McpSchema.JSONRPCNotification.class);
			if (active == null) {
				logger.warn("Notification received before initialization on WebSocket {}", socketSession.getId());
				return;
			}
			active.session.accept(notification)
				.doOnError(ex -> logger.error("Failed processing notification {} for session {}", notification.method(),
						active.session.getId(), ex))
				.subscribe();
			return;
		}

		if (envelope.containsKey("result") || envelope.containsKey("error")) {
			if (active == null) {
				logger.warn("Response received before initialization on WebSocket {}", socketSession.getId());
				return;
			}
			McpSchema.JSONRPCResponse response = this.jsonMapper.convertValue(envelope, McpSchema.JSONRPCResponse.class);
			active.session.accept(response)
				.doOnError(ex -> logger.error("Failed processing response {} for session {}", response.id(),
						active.session.getId(), ex))
				.subscribe();
			return;
		}

		logger.warn("Unrecognised JSON-RPC message on WebSocket {}: {}", socketSession.getId(), envelope);
		sendErrorResponse(socketSession, id, -32600, "Invalid JSON-RPC message",
				Map.of("payload", envelope), active).subscribe();
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		logger.info("WebSocket connection established: {}", session.getId());
		// TODO Validate session credentials and enforce access control for WebSocket connection here.
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) {
		logger.warn("Transport error detected on WebSocket {}", session.getId(), exception);
		ActiveSession active = this.sessionsByWebSocketId.remove(session.getId());
		if (active != null) {
			this.sessionsBySessionId.remove(active.session.getId());
			closeActiveSession(active).subscribe();
		}
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		logger.info("WebSocket connection {} closed with status {}", session.getId(), status);
		ActiveSession active = this.sessionsByWebSocketId.remove(session.getId());
		if (active != null) {
			this.sessionsBySessionId.remove(active.session.getId());
			closeActiveSession(active).subscribe();
		}
	}

	private void handleInitialize(WebSocketSession socketSession, McpSchema.JSONRPCRequest request) {
		if (this.sessionFactory == null) {
			logger.error("Session factory not yet configured. Rejecting initialize request.");
			sendErrorResponse(socketSession, request.id(), -32000, "Server not ready",
					Map.of("reason", "sessionFactory unavailable"), null).subscribe();
			return;
		}

		McpSchema.InitializeRequest initializeRequest;
		try {
			initializeRequest = this.jsonMapper.convertValue(request.params(), McpSchema.InitializeRequest.class);
		}
		catch (IllegalArgumentException ex) {
			logger.warn("Invalid initialize payload on WebSocket {}", socketSession.getId(), ex);
			sendErrorResponse(socketSession, request.id(), -32602, "Invalid params",
					Map.of("message", ex.getMessage()), null).subscribe();
			return;
		}

		McpStreamableServerSession.McpStreamableServerSessionInit sessionInit = this.sessionFactory
			.startSession(initializeRequest);
		McpStreamableServerSession session = sessionInit.session();
		WebSocketSessionTransport transport = new WebSocketSessionTransport(socketSession, session.getId());
		session.listeningStream(transport);

		ActiveSession active = new ActiveSession(socketSession, session, transport);
		this.sessionsByWebSocketId.put(socketSession.getId(), active);
		this.sessionsBySessionId.put(session.getId(), active);

		sessionInit.initResult()
			.flatMap(result -> transport
				.sendMessage(new McpSchema.JSONRPCResponse("2.0", request.id(), result, null)))
			.doOnSubscribe(sub -> logger.debug("Processing initialize request for session {}", session.getId()))
			.doOnError(ex -> {
				logger.error("Initialization failed for session {}", session.getId(), ex);
				sendErrorResponse(socketSession, request.id(), -32000, "Initialization failed",
						Map.of("message", ex.getMessage()), active).subscribe();
				closeActiveSession(active).subscribe();
			})
			.subscribe(v -> logger.info("Initialization completed for session {}", session.getId()));
	}

	private void handleRequest(ActiveSession active, McpSchema.JSONRPCRequest request) {
		active.session.responseStream(request, active.transport)
			.doOnSubscribe(sub -> logger.debug("Processing request {} for session {}", request.method(),
					active.session.getId()))
			.doOnError(ex -> {
				logger.error("Request {} failed for session {}", request.method(), active.session.getId(), ex);
				sendErrorResponse(active.webSocketSession, request.id(), -32000, "Request failed",
						Map.of("message", ex.getMessage()), active).subscribe();
			})
			.subscribe();
	}

	private Mono<Void> sendErrorResponse(WebSocketSession socketSession, Object id, int errorCode, String message,
			Object data, ActiveSession activeSession) {
		McpSchema.JSONRPCResponse.JSONRPCError error = new McpSchema.JSONRPCResponse.JSONRPCError(errorCode, message,
				data);
		McpSchema.JSONRPCResponse response = new McpSchema.JSONRPCResponse("2.0", id, null, error);
		WebSocketSessionTransport transport = activeSession != null ? activeSession.transport
				: new WebSocketSessionTransport(socketSession, socketSession.getId());
		return transport.sendMessage(response)
			.doOnError(ex -> logger.error("Failed to send error response on WebSocket {}", socketSession.getId(), ex));
	}

	private Mono<Void> closeActiveSession(ActiveSession active) {
		this.sessionsByWebSocketId.remove(active.webSocketSession.getId(), active);
		this.sessionsBySessionId.remove(active.session.getId(), active);
		return Mono.whenDelayError(active.transport.closeSession().onErrorResume(ex -> Mono.empty()),
				active.session.closeGracefully().onErrorResume(ex -> Mono.empty())).then();
	}

	/**
	 * Holder for active session state combining the MCP session and underlying WebSocket.
	 */
	private static final class ActiveSession {

		private final WebSocketSession webSocketSession;

		private final McpStreamableServerSession session;

		private final WebSocketSessionTransport transport;

		private ActiveSession(WebSocketSession webSocketSession, McpStreamableServerSession session,
				WebSocketSessionTransport transport) {
			this.webSocketSession = webSocketSession;
			this.session = session;
			this.transport = transport;
		}

	}

	/**
	 * Transport adapter that writes JSON-RPC messages to the underlying WebSocket session.
	 */
	private final class WebSocketSessionTransport implements McpStreamableServerTransport {

		private final WebSocketSession session;

		private final String sessionId;

		private final ReentrantLock sendLock = new ReentrantLock();

		private WebSocketSessionTransport(WebSocketSession session, String sessionId) {
			this.session = session;
			this.sessionId = sessionId;
		}

		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
			return sendMessage(message, null);
		}

		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message, String topic) {
			return Mono.fromCallable(() -> {
				if (!this.session.isOpen()) {
					throw new IllegalStateException("WebSocket session is closed");
				}
				String payload;
				try {
					payload = jsonMapper.writeValueAsString(message);
				}
				catch (IOException ex) {
					throw new IllegalStateException("Failed to serialise MCP message", ex);
				}
				this.sendLock.lock();
				try {
					this.session.sendMessage(new TextMessage(payload));
				}
				finally {
					this.sendLock.unlock();
				}
				if (topic != null) {
					logger.info("Sent topic {} message on session {}: {}", topic, this.sessionId, payload);
				}
				else {
					logger.info("Sent message on session {}: {}", this.sessionId, payload);
				}
				return payload;
			})
				.subscribeOn(Schedulers.boundedElastic())
				.then();
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeRef<T> type) {
			return jsonMapper.convertValue(data, type);
		}

		@Override
		public Mono<Void> closeGracefully() {
			logger.debug("Graceful close requested for session {}, keeping WebSocket alive until client disconnects",
					this.sessionId);
			return Mono.empty();
		}

		@Override
		public void close() {
			closeSession().subscribe();
		}

		private Mono<Void> closeSession() {
			return Mono.fromRunnable(() -> {
				this.sendLock.lock();
				try {
					if (this.session.isOpen()) {
						this.session.close(CloseStatus.NORMAL);
					}
				}
				catch (IOException ex) {
					logger.warn("Failed to close WebSocket session {}", this.sessionId, ex);
				}
				finally {
					this.sendLock.unlock();
				}
			})
				.subscribeOn(Schedulers.boundedElastic())
				.then();
		}

	}

}
