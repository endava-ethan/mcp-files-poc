package com.modelcontextprotocol.sdk;

import com.modelcontextprotocol.sdk.internal.Json;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class McpServer implements AutoCloseable {

    private final List<ToolDefinition> tools;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger requestCounter = new AtomicInteger();
    private final Map<String, CompletableFuture<Map<String, Object>>> pendingRequests = new ConcurrentHashMap<>();

    private Thread readerThread;

    private McpServer(List<ToolDefinition> tools) {
        this.tools = List.copyOf(tools);
        this.reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "mcp-server-handler");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static Builder stdioServer() {
        return new Builder();
    }

    public void run() throws IOException {
        start();
        try {
            if (readerThread != null) {
                readerThread.join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        readerThread = new Thread(this::readLoop, "mcp-server-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readLoop() {
        try {
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                Map<String, Object> message = Json.parseObject(line);
                if (message.containsKey("method")) {
                    dispatchRequest(message);
                } else {
                    handleResponse(message);
                }
            }
        } catch (Exception e) {
            System.err.println("MCP server stopping due to error: " + e.getMessage());
        } finally {
            running.set(false);
            pendingRequests.forEach((id, future) -> future.completeExceptionally(new IOException("Server stopped")));
            pendingRequests.clear();
        }
    }

    private void dispatchRequest(Map<String, Object> message) {
        executor.submit(() -> handleRequest(message));
    }

    private void handleRequest(Map<String, Object> message) {
        Object idValue = message.get("id");
        String id = idValue == null ? null : idValue.toString();
        String method = Objects.toString(message.get("method"), "");
        try {
            switch (method) {
                case "initialize" -> handleInitialize(id);
                case "tools/list" -> handleToolsList(id);
                case "tools/call" -> handleToolsCall(id, message);
                default -> sendError(id, -32601, "Unknown method: " + method, null);
            }
        } catch (Exception e) {
            sendError(id, -32603, "Internal error", Map.of("message", e.getMessage()));
        }
    }

    private void handleInitialize(String id) {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        Map<String, Object> toolsCapability = new LinkedHashMap<>();
        toolsCapability.put("list", Boolean.TRUE);
        capabilities.put("tools", toolsCapability);
        Map<String, Object> elicitationCapability = new LinkedHashMap<>();
        elicitationCapability.put("create", Boolean.TRUE);
        capabilities.put("elicitation", elicitationCapability);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", "1.0");
        result.put("capabilities", capabilities);
        sendResult(id, result);
    }

    private void handleToolsList(String id) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ToolDefinition tool : tools) {
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("name", tool.name());
            descriptor.put("description", tool.description());
            list.add(descriptor);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", list);
        sendResult(id, result);
    }

    private void handleToolsCall(String id, Map<String, Object> message) throws Exception {
        Map<String, Object> params = safeObject(message.get("params"));
        String toolName = Objects.toString(params.get("name"), "");
        Map<String, Object> arguments = safeObject(params.getOrDefault("arguments", Map.of()));
        Optional<ToolDefinition> tool = tools.stream().filter(t -> t.name().equals(toolName)).findFirst();
        if (tool.isEmpty()) {
            sendError(id, -32602, "Unknown tool: " + toolName, null);
            return;
        }
        ToolCallContext context = new ToolCallContext(this);
        Map<String, Object> result = tool.get().handler().handle(arguments, context);
        sendResult(id, result);
    }

    public Map<String, Object> sendRequest(String method, Map<String, Object> params) throws Exception {
        String id = "srv-" + requestCounter.incrementAndGet();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null && !params.isEmpty()) {
            request.put("params", params);
        }
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pendingRequests.put(id, future);
        writeMessage(request);
        try {
            return future.get(Duration.ofMinutes(5).toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pendingRequests.remove(id);
            throw new IOException("Timed out waiting for response to " + method, e);
        } catch (InterruptedException e) {
            pendingRequests.remove(id);
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            pendingRequests.remove(id);
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IOException("Client request failed: " + method, cause);
        }
    }

    private void handleResponse(Map<String, Object> message) {
        Object idValue = message.get("id");
        if (idValue == null) {
            return;
        }
        String id = idValue.toString();
        CompletableFuture<Map<String, Object>> future = pendingRequests.remove(id);
        if (future != null) {
            future.complete(message);
        }
    }

    private void sendResult(String id, Map<String, Object> result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        if (id != null) {
            response.put("id", id);
        }
        response.put("result", result);
        writeMessage(response);
    }

    private void sendError(String id, int code, String message, Map<String, Object> data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        if (id != null) {
            response.put("id", id);
        }
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        if (data != null && !data.isEmpty()) {
            error.put("data", data);
        }
        response.put("error", error);
        writeMessage(response);
    }

    private void writeMessage(Map<String, Object> message) {
        synchronized (writer) {
            try {
                writer.write(Json.stringify(message));
                writer.write('\n');
                writer.flush();
            } catch (IOException e) {
                throw new RuntimeException("Failed to write message", e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeObject(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, val) -> result.put(String.valueOf(key), val));
            return result;
        }
        return new LinkedHashMap<>();
    }

    @Override
    public void close() {
        running.set(false);
        if (readerThread != null) {
            readerThread.interrupt();
        }
        executor.shutdownNow();
    }

    public record ToolDefinition(String name, String description, ToolHandler handler) {
    }

    public static final class Builder {

        private final List<ToolDefinition> tools = new ArrayList<>();

        public Builder tool(String name, String description, ToolHandler handler) {
            tools.add(new ToolDefinition(name, description, handler));
            return this;
        }

        public McpServer build() {
            if (tools.isEmpty()) {
                throw new IllegalStateException("At least one tool must be registered");
            }
            return new McpServer(tools);
        }
    }
}
