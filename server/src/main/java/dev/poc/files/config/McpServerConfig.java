package dev.poc.files.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.poc.files.resource.TodoResourceProvider;
import dev.poc.files.tool.FileTool;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;

/**
 * Spring configuration class that assembles the Model Context Protocol (MCP) server, integrating
 * the file tooling with the transport implementation selected via application properties.
 */
@Configuration
@EnableConfigurationProperties({ FileServerProperties.class, McpTransportProperties.class })
public class McpServerConfig {

	private static final Logger logger = LoggerFactory.getLogger(McpServerConfig.class);

	/**
	 * Build and initialize the synchronous MCP server, registering the file-related tools and
	 * associating them with the selected transport.
	 * @param transportProvider the configured transport provider
	 * @param transportProperties properties describing the chosen transport
	 * @param fileTool tool definitions for file operations
	 * @return a fully configured {@link McpSyncServer} instance
	 */
	@Bean(destroyMethod = "close")
	public McpSyncServer mcpServer(McpStreamableServerTransportProvider transportProvider,
			McpTransportProperties transportProperties, FileTool fileTool,
			TodoResourceProvider todoResourceProvider) {
		McpSyncServer server = McpServer.sync(transportProvider)
			.serverInfo("mcp-files-server", "0.2.0")
			.instructions("Manage files relative to " + fileTool.baseDirectory())
			.tools(fileTool.listFilesTool(), fileTool.readTextTool(), fileTool.writeTextTool(),
					fileTool.deleteFileTool())
			.resources(todoResourceProvider.todoResource())
			.build();
		logger.info("MCP server initialized using {} transport on endpoint {}", transportProperties.getType(),
				transportProperties.getEndpoint());
		return server;
	}

}

