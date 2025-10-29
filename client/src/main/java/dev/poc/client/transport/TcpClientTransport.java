package dev.poc.client.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.poc.transport.Envelope;
import dev.poc.transport.LengthPrefixedCodec;
import dev.poc.transport.Wire;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TcpClientTransport implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(TcpClientTransport.class);

    private final String host;
    private final int port;
    private final boolean decline;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger requestCounter = new AtomicInteger();
    private final Map<String, CompletableFuture<ObjectNode>> pending = new ConcurrentHashMap<>();
    private final String clientSessionId = "c-" + UUID.randomUUID().toString().substring(0, 8);

    private Socket socket;
    private OutputStream out;
    private Thread readerThread;
    private volatile boolean running;
    private volatile String serverSessionId;

    public TcpClientTransport(String host, int port, boolean decline) {
        this.host = host;
        this.port = port;
        this.decline = decline;
    }

    public void connect() throws IOException {
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        out = socket.getOutputStream();
        running = true;
        readerThread = new Thread(this::readLoop, "tcp-client-reader");
        readerThread.setDaemon(true);
        readerThread.start();
        LOGGER.info("Connected to {}:{}", host, port);
    }

    private void readLoop() {
        try (InputStream in = socket.getInputStream()) {
            while (running) {
                String frame = LengthPrefixedCodec.readFrame(in);
                if (frame == null) {
                    break;
                }
                Envelope envelope = mapper.readValue(frame, Envelope.class);
                if (envelope.sessionId() != null) {
                    serverSessionId = envelope.sessionId();
                }
                Wire.rx(host + ":" + port, envelope);
                switch (envelope.messageType()) {
                    case "response" -> handleResponse(envelope);
                    case "notification" -> LOGGER.info("Notification: {}", envelope.jsonrpc());
                    case "request" -> handleServerRequest(envelope);
                    default -> LOGGER.warn("Unknown message type {}", envelope.messageType());
                }
            }
        } catch (Exception e) {
            if (running) {
                LOGGER.error("Transport error", e);
            }
        } finally {
            running = false;
            for (Map.Entry<String, CompletableFuture<ObjectNode>> entry : pending.entrySet()) {
                entry.getValue().completeExceptionally(new IOException("Connection closed"));
            }
            pending.clear();
        }
    }

    private void handleResponse(Envelope envelope) throws IOException {
        String correlationId = envelope.correlationId();
        if (correlationId == null) {
            LOGGER.warn("Response without correlation id");
            return;
        }
        CompletableFuture<ObjectNode> future = pending.get(correlationId);
        if (future == null) {
            LOGGER.warn("No pending request for {}", correlationId);
            return;
        }
        ObjectNode message = (ObjectNode) mapper.readTree(envelope.jsonrpc());
        if (envelope.fin()) {
            pending.remove(correlationId);
            future.complete(message);
        }
    }

    private void handleServerRequest(Envelope envelope) throws IOException {
        ObjectNode message = (ObjectNode) mapper.readTree(envelope.jsonrpc());
        String method = message.path("method").asText();
        switch (method) {
            case "elicitation/create" -> respondToElicitation(envelope, message);
            default -> LOGGER.warn("Unhandled server method {}", method);
        }
    }

    private void respondToElicitation(Envelope envelope, ObjectNode message) throws IOException {
        JsonNode params = message.path("params");
        String prompt = params.path("message").asText();
        System.out.println("ELICITATION: " + prompt);
        System.out.println("Schema: " + params.path("schema").toPrettyString());

        boolean confirm = !decline;
        System.out.println("Client response: " + (confirm ? "confirm overwrite" : "decline"));

        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", message.path("id").asText());
        ObjectNode result = mapper.createObjectNode();
        result.put("decision", confirm ? "accept" : "decline");
        ObjectNode values = mapper.createObjectNode();
        values.put("confirm", confirm);
        result.set("values", values);
        response.set("result", result);

        Envelope reply = new Envelope(envelope.sessionId() != null ? envelope.sessionId() : clientSessionId,
            "response", null, envelope.requestId(), 1, true, mapper.writeValueAsString(response));
        sendEnvelope(reply);
    }

    public ObjectNode send(String method, JsonNode params) throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        String id = "cli-" + requestCounter.incrementAndGet();
        request.put("id", id);
        request.put("method", method);
        if (params != null && !params.isMissingNode()) {
            request.set("params", params);
        }
        CompletableFuture<ObjectNode> future = new CompletableFuture<>();
        pending.put(id, future);
        Envelope envelope = new Envelope(serverSessionId != null ? serverSessionId : clientSessionId,
            "request", id, null, 1, false, mapper.writeValueAsString(request));
        sendEnvelope(envelope);
        try {
            ObjectNode response = future.get(5, TimeUnit.MINUTES);
            if (response.has("error")) {
                throw new IOException("RPC error: " + response.get("error").toString());
            }
            return response;
        } catch (TimeoutException e) {
            pending.remove(id);
            throw e;
        }
    }

    private void sendEnvelope(Envelope envelope) throws IOException {
        String json = mapper.writeValueAsString(envelope);
        Wire.tx(host + ":" + port, envelope);
        synchronized (this) {
            LengthPrefixedCodec.writeFrame(out, json);
        }
    }

    public ObjectNode initialize() throws Exception {
        ObjectNode capabilities = mapper.createObjectNode();
        ObjectNode tools = mapper.createObjectNode();
        tools.put("call", true);
        capabilities.set("tools", tools);
        ObjectNode elicitation = mapper.createObjectNode();
        elicitation.put("respond", true);
        capabilities.set("elicitation", elicitation);
        ObjectNode params = mapper.createObjectNode();
        params.put("client", "console");
        params.set("capabilities", capabilities);
        return send("initialize", params);
    }

    public ObjectNode listFiles(String dir) throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("dir", dir);
        ObjectNode params = mapper.createObjectNode();
        params.put("name", "list_files");
        params.set("arguments", args);
        return send("tools/call", params);
    }

    public ObjectNode readText(String path) throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("path", path);
        ObjectNode params = mapper.createObjectNode();
        params.put("name", "read_text");
        params.set("arguments", args);
        return send("tools/call", params);
    }

    public ObjectNode writeText(String path, String content) throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("path", path);
        args.put("content", content);
        ObjectNode params = mapper.createObjectNode();
        params.put("name", "write_text");
        params.set("arguments", args);
        return send("tools/call", params);
    }

    @Override
    public void close() throws IOException {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (readerThread != null) {
            try {
                readerThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
