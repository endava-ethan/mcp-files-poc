package dev.poc.files.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;

/**
 * Configures the default Spring MVC transport when {@code mcp.files.transport.type=webmvc}.
 */
@Configuration
@ConditionalOnProperty(prefix = "mcp.files.transport", name = "type", havingValue = "webmvc", matchIfMissing = true)
public class WebMvcTransportConfig {

	@Bean
	public WebMvcStreamableServerTransportProvider streamableTransportProvider(
			McpTransportProperties transportProperties) {
		WebMvcStreamableServerTransportProvider.Builder builder = WebMvcStreamableServerTransportProvider.builder()
			.mcpEndpoint(transportProperties.getEndpoint())
			.disallowDelete(transportProperties.isDisallowDelete());
		// TODO Implement authentication/authorization hook for WebMVC transport here (eg, add interceptor/filter).
		if (transportProperties.getKeepAliveInterval() != null) {
			builder.keepAliveInterval(transportProperties.getKeepAliveInterval());
		}
		return builder.build();
	}

	@Bean
	public RouterFunction<ServerResponse> mcpRouter(WebMvcStreamableServerTransportProvider transportProvider) {
		return transportProvider.getRouterFunction();
	}

}
