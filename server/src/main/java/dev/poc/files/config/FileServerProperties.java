package dev.poc.files.config;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import lombok.Getter;
import lombok.Setter;

/**
 * Configuration options for the file MCP server. Allows overriding the base directory
 * via Spring configuration while preserving the legacy environment variable behaviour.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "mcp.files")
public class FileServerProperties {

	/**
	 * An optional explicit base directory configured via application properties.
	 */
	private String baseDir;

	/**
	 * Resolve the directory the server should operate on, preferring the configured
	 * property, then the {@code MCP_FILES_BASE_DIR} environment variable, and finally a
	 * stable directory under the user home.
	 * @return the normalized path to use as the root for file operations
	 */
	public Path determineBaseDir() {
		if (StringUtils.hasText(this.baseDir)) {
			return normalize(Paths.get(this.baseDir));
		}
		String environmentOverride = System.getenv("MCP_FILES_BASE_DIR");
		if (StringUtils.hasText(environmentOverride)) {
			return normalize(Paths.get(environmentOverride));
		}
		return normalize(Paths.get(System.getProperty("user.home"), "mcp-play"));
	}

	/**
	 * Normalize the supplied path into an absolute, canonical form.
	 * @param candidate path to normalize
	 * @return normalized path
	 */
	private static Path normalize(Path candidate) {
		return candidate.toAbsolutePath().normalize();
	}

}
