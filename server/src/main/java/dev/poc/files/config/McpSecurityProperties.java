package dev.poc.files.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Security properties defining the credentials required to access the MCP HTTP transport.
 */
@ConfigurationProperties("mcp.files.security")
public record McpSecurityProperties(String username, String password) {

	public McpSecurityProperties {
		username = username == null || username.isBlank() ? "mcp" : username;
		password = password == null || password.isBlank() ? "change-me" : password;
	}

}
