package dev.poc.files.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import dev.poc.files.transport.WebSocketMcpTransportProvider;

/**
 * Registers the WebSocket handler with the servlet container whenever the WebSocket transport is
 * active.
 */
@Configuration
@EnableWebSocket
@ConditionalOnProperty(prefix = "mcp.files.transport", name = "type", havingValue = "websocket")
@ConditionalOnBean(WebSocketMcpTransportProvider.class)
public class WebSocketTransportRegistration implements WebSocketConfigurer {

	private final WebSocketMcpTransportProvider transportProvider;

	private final McpTransportProperties transportProperties;

	public WebSocketTransportRegistration(WebSocketMcpTransportProvider transportProvider,
			McpTransportProperties transportProperties) {
		this.transportProvider = transportProvider;
		this.transportProperties = transportProperties;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(this.transportProvider, this.transportProperties.getEndpoint()).setAllowedOrigins("*");
	}

}

