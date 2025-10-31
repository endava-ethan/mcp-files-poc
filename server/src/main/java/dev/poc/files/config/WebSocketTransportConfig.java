package dev.poc.files.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.poc.files.transport.WebSocketMcpTransportProvider;

/**
 * Declares the WebSocket-based transport provider when {@code mcp.files.transport.type=websocket}.
 */
@Configuration
@ConditionalOnProperty(prefix = "mcp.files.transport", name = "type", havingValue = "websocket")
public class WebSocketTransportConfig {

	@Bean
	public WebSocketMcpTransportProvider streamableTransportProvider(McpTransportProperties transportProperties) {
		// TODO Inject WebSocket authentication or token validation before constructing provider.
		return new WebSocketMcpTransportProvider(transportProperties);
	}

}
