package dev.poc.files.config;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.lang.Nullable;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * Configuration properties customizing the behaviour of the MCP transport layer. Properties enable
 * selecting between the built-in Spring MVC transport and the custom WebSocket transport while
 * allowing endpoint, deletion policy, and keep-alive interval to be overridden.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "mcp.files.transport")
public class McpTransportProperties {

	/**
	 * Transport technology that should be used to expose the MCP server. Defaults to the MVC
	 * transport provided by the SDK.
	 */
	@Setter(AccessLevel.NONE)
	private TransportType type = TransportType.WEBMVC;

	/**
	 * HTTP endpoint path that the MCP transport binds to. Defaults to {@code /mcp}.
	 */
	private String endpoint = "/mcp";

	/**
	 * Flag indicating whether HTTP DELETE requests should be disallowed on the transport
	 * endpoint.
	 */
	private boolean disallowDelete = false;

	/**
	 * Optional keep-alive interval used to send heartbeat messages to connected clients. When
	 * {@code null}, the SDK default interval is used.
	 */
	@Nullable
	private Duration keepAliveInterval;

	/**
	 * Update the selected transport type.
	 * @param type new transport technology to use
	 */
	public void setType(TransportType type) {
		this.type = Objects.requireNonNullElse(type, TransportType.WEBMVC);
	}

	/**
	 * Available transport implementations.
	 */
	public enum TransportType {
		WEBMVC, WEBSOCKET
	}

}
