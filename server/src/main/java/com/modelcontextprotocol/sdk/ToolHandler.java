package com.modelcontextprotocol.sdk;

import java.util.Map;

@FunctionalInterface
public interface ToolHandler {
    Map<String, Object> handle(Map<String, Object> arguments, ToolCallContext context) throws Exception;
}
