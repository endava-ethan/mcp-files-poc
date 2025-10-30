package com.modelcontextprotocol.sdk;

import com.modelcontextprotocol.sdk.internal.Json;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ToolCallContext {

    private final McpServer server;

    ToolCallContext(McpServer server) {
        this.server = server;
    }

    public Map<String, Object> request(String method, Map<String, Object> params) throws Exception {
        Map<String, Object> response = server.sendRequest(method, params);
        if (response.containsKey("error")) {
            throw new IOException("Client returned error: " + Json.stringify(response.get("error")));
        }
        Object result = response.get("result");
        if (result instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return copy;
        }
        return Map.of();
    }
}
