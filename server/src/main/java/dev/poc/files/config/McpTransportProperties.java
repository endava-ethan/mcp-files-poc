package dev.poc.files.config;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.lang.Nullable;

/**
 * Configuration properties customizing the behaviour of the MCP transport layer. Properties enable
 * selecting between the built-in Spring MVC transport and the custom WebSocket transport while
 * allowing endpoint, deletion policy, and keep-alive interval to be overridden.
 */
@ConfigurationProperties(prefix = "mcp.files.transport")
public class McpTransportProperties {

	/**
	 * Transport technology that should be used to expose the MCP server. Defaults to the MVC
	 * transport provided by the SDK.
	 */
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
	 * Retrieve the selected transport type.
	 * @return currently configured transport technology
	 */
	public TransportType getType() {
		return type;
	}

	/**
	 * Update the selected transport type.
	 * @param type new transport technology to use
	 */
	public void setType(TransportType type) {
		this.type = Objects.requireNonNullElse(type, TransportType.WEBMVC);
	}

	/**
	 * Retrieve the configured endpoint path.
	 * @return the HTTP endpoint that exposes the MCP transport
	 */
	public String getEndpoint() {
		return endpoint;
	}

	/**
	 * Update the endpoint path used by the transport.
	 * @param endpoint the new HTTP endpoint path
	 */
	public void setEndpoint(String endpoint) {
		this.endpoint = endpoint;
	}

	/**
	 * Determine whether DELETE requests against the transport endpoint are rejected.
	 * @return {@code true} when DELETE requests should be rejected
	 */
	public boolean isDisallowDelete() {
		return disallowDelete;
	}

	/**
	 * Enable or disable HTTP DELETE handling.
	 * @param disallowDelete {@code true} to block DELETE requests
	 */
	public void setDisallowDelete(boolean disallowDelete) {
		this.disallowDelete = disallowDelete;
	}

	/**
	 * Obtain the optional keep-alive interval.
	 * @return the configured interval, or {@code null} to use the SDK default
	 */
	@Nullable
	public Duration getKeepAliveInterval() {
		return keepAliveInterval;
	}

	/**
	 * Configure the optional keep-alive interval.
	 * @param keepAliveInterval the heartbeat interval to apply, or {@code null} to disable the
	 * override
	 */
	public void setKeepAliveInterval(@Nullable Duration keepAliveInterval) {
		this.keepAliveInterval = keepAliveInterval;
	}

	/**
	 * Available transport implementations.
	 */
	public enum TransportType {
		WEBMVC, WEBSOCKET
	}

}
