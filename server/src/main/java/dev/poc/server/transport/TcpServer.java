package dev.poc.server.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.poc.transport.Envelope;
import dev.poc.transport.LengthPrefixedCodec;
import dev.poc.transport.Wire;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TcpServer implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(TcpServer.class);

    private final Path baseDir;
    private final int port;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService clientExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tcp-server-client");
        t.setDaemon(true);
        return t;
    });
    private final Set<ClientConnection> connections = ConcurrentHashMap.newKeySet();

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;

    public TcpServer(Path baseDir, int port) {
        this.baseDir = baseDir;
        this.port = port;
    }

    public void start() throws IOException {
        if (running) {
            return;
        }
        serverSocket = new ServerSocket(port);
        running = true;
        acceptThread = new Thread(this::acceptLoop, "tcp-server-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        LOGGER.info("TCP server listening on port {}", port);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                ClientConnection connection = new ClientConnection(socket);
                connections.add(connection);
                clientExecutor.submit(connection::start);
            } catch (IOException e) {
                if (running) {
                    LOGGER.error("Error accepting connection", e);
                }
            } catch (RejectedExecutionException ignored) {
                LOGGER.warn("Client executor rejected connection");
            }
        }
    }

    public void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                LOGGER.warn("Error closing server socket", e);
            }
        }
        if (acceptThread != null) {
            try {
                acceptThread.join(Duration.ofSeconds(1).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (ClientConnection connection : new ArrayList<>(connections)) {
            try {
                connection.close();
            } catch (IOException e) {
                LOGGER.warn("Error closing connection", e);
            }
        }
        clientExecutor.shutdown();
        try {
            if (!clientExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                clientExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LOGGER.info("TCP server stopped");
    }

    @Override
    public void close() {
        stop();
    }

    private final class ClientConnection implements Closeable {

        private final Socket socket;
        private final String connectionId;
        private final String sessionId;
        private final ExecutorService requestExecutor;
        private final Map<String, CompletableFuture<ObjectNode>> pendingServerRequests = new ConcurrentHashMap<>();
        private final AtomicInteger serverRequestCounter = new AtomicInteger();

        private volatile boolean open = true;

        ClientConnection(Socket socket) {
            this.socket = socket;
            this.connectionId = socket.getRemoteSocketAddress().toString();
            this.sessionId = "s-" + UUID.randomUUID().toString().substring(0, 8);
            this.requestExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "tcp-server-request-" + connectionId);
                t.setDaemon(true);
                return t;
            });
            LOGGER.info("Accepted connection {}", connectionId);
        }

        void start() {
            try (InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream()) {
                while (open) {
                    String frame = LengthPrefixedCodec.readFrame(in);
                    if (frame == null) {
                        break;
                    }
                    Envelope envelope = mapper.readValue(frame, Envelope.class);
                    Wire.rx(connectionId, envelope);
                    switch (envelope.messageType()) {
                        case "request" -> handleClientRequest(envelope, out);
                        case "response" -> handleClientResponse(envelope);
                        case "notification" -> LOGGER.info("Notification from {}: {}", connectionId, envelope.jsonrpc());
                        default -> LOGGER.warn("Unknown message type {} from {}", envelope.messageType(), connectionId);
                    }
                }
            } catch (IOException e) {
                if (open) {
                    LOGGER.error("Connection error {}", connectionId, e);
                }
            } finally {
                try {
                    close();
                } catch (IOException e) {
                    LOGGER.warn("Error closing connection {}", connectionId, e);
                }
            }
        }

        private void handleClientRequest(Envelope envelope, OutputStream out) {
            requestExecutor.submit(() -> {
                try {
                    ObjectNode request = (ObjectNode) mapper.readTree(envelope.jsonrpc());
                    String method = request.path("method").asText();
                    String id = request.has("id") ? request.get("id").asText() : null;
                    switch (method) {
                        case "initialize" -> handleInitialize(id, out);
                        case "tools/list" -> handleToolsList(id, out);
                        case "tools/call" -> handleToolsCall(id, request.path("params"), out);
                        default -> sendError(id, out, -32601, "Unknown method: " + method, null);
                    }
                } catch (Exception e) {
                    LOGGER.error("Error handling request from {}", connectionId, e);
                    try {
                        sendError(null, out, -32603, "Internal error", mapper.createObjectNode().put("message", e.getMessage()));
                    } catch (IOException ex) {
                        LOGGER.warn("Unable to send error response", ex);
                    }
                }
            });
        }

        private void handleInitialize(String id, OutputStream out) throws IOException {
            ObjectNode capabilities = mapper.createObjectNode();
            ObjectNode tools = mapper.createObjectNode();
            tools.put("list", true);
            capabilities.set("tools", tools);
            ObjectNode elicitation = mapper.createObjectNode();
            elicitation.put("create", true);
            capabilities.set("elicitation", elicitation);

            ObjectNode result = mapper.createObjectNode();
            result.put("protocolVersion", "1.0");
            result.set("capabilities", capabilities);

            sendResult(id, out, result);
        }

        private void handleToolsList(String id, OutputStream out) throws IOException {
            ArrayNode tools = mapper.createArrayNode();

            tools.add(tool("list_files", "List one level of files relative to the base directory"));
            tools.add(tool("read_text", "Read a UTF-8 text file"));
            tools.add(tool("write_text", "Write a UTF-8 text file (prompts before overwrite)"));

            ObjectNode result = mapper.createObjectNode();
            result.set("tools", tools);
            sendResult(id, out, result);
        }

        private ObjectNode tool(String name, String description) {
            ObjectNode node = mapper.createObjectNode();
            node.put("name", name);
            node.put("description", description);
            return node;
        }

        private void handleToolsCall(String id, JsonNode params, OutputStream out) throws Exception {
            String toolName = params.path("name").asText();
            JsonNode arguments = params.path("arguments");
            switch (toolName) {
                case "list_files" -> sendResult(id, out, listFiles(arguments));
                case "read_text" -> sendResult(id, out, readText(arguments));
                case "write_text" -> sendResult(id, out, writeText(arguments));
                default -> sendError(id, out, -32602, "Unknown tool: " + toolName, null);
            }
        }

        private ObjectNode listFiles(JsonNode arguments) throws IOException {
            String dirArgument = arguments.path("dir").asText(".");
            Path target = resolvePath(dirArgument);
            if (!Files.exists(target)) {
                return mapper.createObjectNode().put("content", "<missing>");
            }
            if (!Files.isDirectory(target)) {
                return mapper.createObjectNode().put("content", "<not a directory>");
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(target)) {
                List<String> entries = new ArrayList<>();
                for (Path path : stream) {
                    entries.add(baseDir.relativize(path.normalize()).toString());
                }
                String joined = entries.stream().sorted().collect(Collectors.joining("\n"));
                return mapper.createObjectNode().put("content", joined);
            }
        }

        private ObjectNode readText(JsonNode arguments) throws IOException {
            String pathArgument = arguments.path("path").asText();
            if (pathArgument.isEmpty()) {
                throw new IOException("path argument is required");
            }
            Path target = resolvePath(pathArgument);
            if (!Files.exists(target)) {
                return mapper.createObjectNode().put("content", "<missing>");
            }
            return mapper.createObjectNode().put("content", Files.readString(target));
        }

        private ObjectNode writeText(JsonNode arguments) throws Exception {
            String pathArgument = arguments.path("path").asText();
            if (pathArgument.isEmpty()) {
                throw new IOException("path argument is required");
            }
            Path target = resolvePath(pathArgument);
            String content = arguments.path("content").asText("");
            Files.createDirectories(Objects.requireNonNull(target.getParent(), "parent"));

            if (Files.exists(target)) {
                if (!Files.isRegularFile(target)) {
                    throw new IOException("Target exists and is not a regular file: " + pathArgument);
                }
                if (!requestOverwriteConfirmation(target)) {
                    return mapper.createObjectNode().put("summary", "Declined");
                }
            }

            Files.writeString(target, content);
            return mapper.createObjectNode().put("summary", "Wrote: " + baseDir.relativize(target));
        }

        private boolean requestOverwriteConfirmation(Path target) throws Exception {
            ObjectNode params = mapper.createObjectNode();
            params.put("message", "File exists. Overwrite?");
            ObjectNode schema = mapper.createObjectNode();
            schema.put("type", "object");
            ObjectNode properties = mapper.createObjectNode();
            ObjectNode confirm = mapper.createObjectNode();
            confirm.put("type", "boolean");
            properties.set("confirm", confirm);
            schema.set("properties", properties);
            ArrayNode required = mapper.createArrayNode();
            required.add("confirm");
            schema.set("required", required);
            params.set("schema", schema);

            ObjectNode request = mapper.createObjectNode();
            request.put("jsonrpc", "2.0");
            String requestId = "srv-" + serverRequestCounter.incrementAndGet();
            request.put("id", requestId);
            request.put("method", "elicitation/create");
            request.set("params", params);

            ObjectNode response = sendServerRequest(request);
            JsonNode result = response.path("result");
            if (result.isMissingNode()) {
                return false;
            }
            JsonNode values = result.path("values");
            return values.path("confirm").asBoolean(false);
        }

        private ObjectNode sendServerRequest(ObjectNode request) throws Exception {
            String requestId = request.path("id").asText();
            CompletableFuture<ObjectNode> future = new CompletableFuture<>();
            pendingServerRequests.put(requestId, future);
            Envelope envelope = new Envelope(sessionId, "request", requestId, null, 1, false,
                mapper.writeValueAsString(request));
            sendEnvelope(envelope);
            return future.get(5, TimeUnit.MINUTES);
        }

        private Path resolvePath(String relative) throws IOException {
            Path candidate = baseDir.resolve(relative).normalize();
            if (!candidate.startsWith(baseDir)) {
                throw new IOException("Path escapes base directory: " + relative);
            }
            return candidate;
        }

        private void sendResult(String id, OutputStream out, JsonNode result) throws IOException {
            ObjectNode response = mapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            if (id != null) {
                response.put("id", id);
            }
            response.set("result", result);
            Envelope envelope = new Envelope(sessionId, "response", null, id, 1, true,
                mapper.writeValueAsString(response));
            sendEnvelope(envelope, out);
        }

        private void sendError(String id, OutputStream out, int code, String message, JsonNode data) throws IOException {
            ObjectNode response = mapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            if (id != null) {
                response.put("id", id);
            }
            ObjectNode error = mapper.createObjectNode();
            error.put("code", code);
            error.put("message", message);
            if (data != null) {
                error.set("data", data);
            }
            response.set("error", error);
            Envelope envelope = new Envelope(sessionId, "response", null, id, 1, true,
                mapper.writeValueAsString(response));
            sendEnvelope(envelope, out);
        }

        private void sendEnvelope(Envelope envelope) throws JsonProcessingException {
            try {
                sendEnvelope(envelope, socket.getOutputStream());
            } catch (IOException e) {
                throw new RuntimeException("Failed to send envelope", e);
            }
        }

        private void sendEnvelope(Envelope envelope, OutputStream out) throws IOException {
            String json = mapper.writeValueAsString(envelope);
            Wire.tx(connectionId, envelope);
            LengthPrefixedCodec.writeFrame(out, json);
        }

        private void handleClientResponse(Envelope envelope) throws JsonProcessingException {
            String correlationId = envelope.correlationId();
            if (correlationId == null) {
                LOGGER.warn("Response without correlation id from {}", connectionId);
                return;
            }
            CompletableFuture<ObjectNode> future = pendingServerRequests.get(correlationId);
            if (future == null) {
                LOGGER.warn("No pending request for correlation {}", correlationId);
                return;
            }
            ObjectNode message = (ObjectNode) mapper.readTree(envelope.jsonrpc());
            if (envelope.fin()) {
                pendingServerRequests.remove(correlationId);
                future.complete(message);
            }
        }

        @Override
        public void close() throws IOException {
            open = false;
            connections.remove(this);
            requestExecutor.shutdownNow();
            try {
                if (!requestExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    requestExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!socket.isClosed()) {
                socket.close();
            }
            for (Map.Entry<String, CompletableFuture<ObjectNode>> entry : pendingServerRequests.entrySet()) {
                entry.getValue().completeExceptionally(new IOException("Connection closed"));
            }
            pendingServerRequests.clear();
            LOGGER.info("Connection {} closed", connectionId);
        }
    }
}
