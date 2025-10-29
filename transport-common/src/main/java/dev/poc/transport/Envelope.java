package dev.poc.transport;

/**
 * Transport wrapper. Keeps the MCP JSON-RPC body as a raw JSON string so that we can
 * preserve the original payload without reparsing when routing it through the transport
 * layer.
 */
public record Envelope(
    String sessionId,
    String messageType,
    String requestId,
    String correlationId,
    int seq,
    boolean fin,
    String jsonrpc
) {
}
