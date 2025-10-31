package dev.poc.files.resource;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import dev.poc.files.model.FileReadResult;
import dev.poc.files.service.FileService;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Exposes workspace resources that should be available to clients without invoking tools.
 * Currently serves the {@code todo.md} file as a {@code text/markdown} resource.
 */
@Component
@RequiredArgsConstructor
public class TodoResourceProvider {

	private static final Logger logger = LoggerFactory.getLogger(TodoResourceProvider.class);

	private static final String TODO_FILE_NAME = "todo.md";

	private static final String TODO_RESOURCE_URI = "resource://todo.md";

	private final FileService fileService;

	/**
	 * Provide the MCP resource specification for the workspace TODO markdown file.
	 * @return resource specification serving {@code todo.md}
	 */
	public McpServerFeatures.SyncResourceSpecification todoResource() {
		McpSchema.Resource resource = McpSchema.Resource.builder()
			.uri(TODO_RESOURCE_URI)
			.name("workspace_todo")
			.title("Workspace TODO")
			.description("Pinned todo.md notes from the workspace root.")
			.mimeType("text/markdown")
			.build();
		return new McpServerFeatures.SyncResourceSpecification(resource, this::handleTodoResource);
	}

	/**
	 * Handle the {@code todo.md} resource read request synchronously, returning the file
	 * contents as {@code text/markdown}. When the file is missing or unreadable, returns
	 * placeholder content explaining the situation to the client.
	 * @param exchange server exchange (unused but required by the signature)
	 * @param request the incoming resource read request
	 * @return read result containing the TODO markdown text or placeholder content
	 */
	private McpSchema.ReadResourceResult handleTodoResource(McpSyncServerExchange exchange,
			McpSchema.ReadResourceRequest request) {
		logger.debug("Serving todo.md resource request for {}", request != null ? request.uri() : TODO_RESOURCE_URI);
		try {
			FileReadResult readResult = this.fileService.read(TODO_FILE_NAME);
			Map<String, Object> meta = readResult.lastModified() != null
					? Map.of("lastModified", readResult.lastModified().toString())
					: null;
			McpSchema.TextResourceContents contents = (meta != null)
					? new McpSchema.TextResourceContents(TODO_RESOURCE_URI, "text/markdown",
							readResult.content(), meta)
					: new McpSchema.TextResourceContents(TODO_RESOURCE_URI, "text/markdown", readResult.content());
			return new McpSchema.ReadResourceResult(List.of(contents));
		}
		catch (NoSuchFileException e) {
			logger.info("todo.md not found in workspace");
			String placeholder = "# TODO\nNo todo.md file present in the workspace.";
			McpSchema.TextResourceContents contents = new McpSchema.TextResourceContents(TODO_RESOURCE_URI,
					"text/markdown", placeholder);
			return new McpSchema.ReadResourceResult(List.of(contents));
		}
		catch (IOException e) {
			logger.warn("Failed to read todo.md resource", e);
			String placeholder = "# TODO\nUnable to load todo.md (" + e.getMessage() + ")";
			McpSchema.TextResourceContents contents = new McpSchema.TextResourceContents(TODO_RESOURCE_URI,
					"text/markdown", placeholder);
			return new McpSchema.ReadResourceResult(List.of(contents));
		}
	}

}
