package dev.poc.files.tool;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;

import dev.poc.files.model.DeleteResult;
import dev.poc.files.model.DirectoryListing;
import dev.poc.files.model.FileReadResult;
import dev.poc.files.model.WriteResult;
import dev.poc.files.service.FileService;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Synchronous MCP tool definitions that expose file system operations such as listing directories,
 * reading files, and writing file contents relative to the configured base directory.
 */
@Component
@RequiredArgsConstructor
public class FileTool {

	private static final Logger logger = LoggerFactory.getLogger(FileTool.class);

	private final FileService fileService;

	/**
	 * Obtain the base directory used by the underlying file service.
	 * @return normalized base directory path
	 */
	public Path baseDirectory() {
		return this.fileService.baseDirectory();
	}

	/**
	 * Provide the MCP specification for the {@code list_files} tool, including schema and handler.
	 * @return synchronous tool specification for listing directory contents
	 */
	public McpServerFeatures.SyncToolSpecification listFilesTool() {
		return McpServerFeatures.SyncToolSpecification.builder()
			.tool(McpSchema.Tool.builder()
				.name("list_files")
				.title("List files")
				.description("List one level of files relative to the server base directory.")
				.inputSchema(listFilesSchema())
				.build())
			.callHandler(this::handleListFiles)
			.build();
	}

	/**
	 * Provide the MCP specification for the {@code read_text} tool.
	 * @return synchronous tool specification for reading text files
	 */
	public McpServerFeatures.SyncToolSpecification readTextTool() {
		return McpServerFeatures.SyncToolSpecification.builder()
			.tool(McpSchema.Tool.builder()
				.name("read_text")
				.title("Read text file")
				.description("Read a UTF-8 encoded text file.")
				.inputSchema(readTextSchema())
				.build())
			.callHandler(this::handleReadText)
			.build();
	}

	/**
	 * Provide the MCP specification for the {@code write_text} tool.
	 * @return synchronous tool specification for writing text files
	 */
	public McpServerFeatures.SyncToolSpecification writeTextTool() {
		return McpServerFeatures.SyncToolSpecification.builder()
			.tool(McpSchema.Tool.builder()
				.name("write_new_text")
				.title("Write new text file")
				.description(
						"Write a UTF-8 text file. The client is prompted before overwriting existing files unless allowOverwrite is true.")
				.inputSchema(writeTextSchema())
				.build())
			.callHandler(this::handleWriteText)
			.build();
	}

	/**
	 * Provide the MCP specification for the {@code delete_file} tool.
	 * @return synchronous tool specification for deleting files
	 */
	public McpServerFeatures.SyncToolSpecification deleteFileTool() {
		return McpServerFeatures.SyncToolSpecification.builder()
			.tool(McpSchema.Tool.builder()
				.name("delete_local_file")
				.title("Delete Local file")
				.description("Delete a file relative to the base directory after confirmation.")
				.inputSchema(deleteFileSchema())
				.build())
			.callHandler(this::handleDeleteFile)
			.build();
	}

	/**
	 * Handle the {@code list_files} tool invocation, returning a summary and structured directory
	 * listing.
	 * @param exchange server exchange used for prompting
	 * @param callToolRequest incoming tool request payload
	 * @return tool result describing the directory contents or an error outcome
	 */
	private McpSchema.CallToolResult handleListFiles(McpSyncServerExchange exchange,
			McpSchema.CallToolRequest callToolRequest) {
        /**
         * Log client roots if the capability is present
         */
        var rootsCaps = exchange.getClientCapabilities().roots();
        if (rootsCaps != null) {
            McpSchema.ListRootsResult roots = exchange.listRoots();
            roots.roots().forEach(root ->
                    logger.info("Client root {} -> {}", root.name(), root.uri()));
        } else {
            logger.debug("Client {} has no roots capability", exchange.sessionId());
        }


		Map<String, Object> arguments = ToolRequestUtils.safeArguments(callToolRequest);
		String dir = ToolRequestUtils.stringArgument(arguments, "dir", ".");
		logger.debug("Handling list_files request for directory {}", dir);
		try {
			DirectoryListing listing = this.fileService.list(dir);
			String detailed = listing.entries().isEmpty()
					? listing.summaryLine()
					: listing.summaryLine() + System.lineSeparator()
							+ listing.entries()
								.stream()
								.map(entry -> "- " + entry.displayLabel())
								.collect(Collectors.joining(System.lineSeparator()));
			return successResult(detailed, listing.toStructured());
		}
		catch (NoSuchFileException | NotDirectoryException e) {
			logger.debug("Directory {} not found or not a directory", dir, e);
			return errorResult("Directory not found: " + dir,
					Map.of("path", dir, "reason", e.getClass().getSimpleName()));
		}
		catch (IOException e) {
			logger.warn("Failed to list directory {}", dir, e);
			return errorResult("Failed to list directory: " + dir, Map.of("path", dir));
		}
	}

	/**
	 * Handle the {@code read_text} tool invocation by delegating to the file service and composing
	 * a structured result.
	 * @param exchange server exchange for completeness (unused)
	 * @param callToolRequest incoming tool request payload
	 * @return tool result including the file content or an error outcome
	 */
	private McpSchema.CallToolResult handleReadText(McpSyncServerExchange exchange,
			McpSchema.CallToolRequest callToolRequest) {
		Map<String, Object> arguments = ToolRequestUtils.safeArguments(callToolRequest);
		String path = ToolRequestUtils.stringArgument(arguments, "path", null);
		logger.debug("Handling read_text request for path {}", path);
		if (!StringUtils.hasText(path)) {
			return errorResult("path argument is required", Map.of("missing", "path"));
		}
		try {
			FileReadResult result = this.fileService.read(path);
			return successResult("Read " + result.path(), result.toStructured());
		}
		catch (NoSuchFileException e) {
			logger.debug("Requested file {} not found", path, e);
			return errorResult("File not found: " + path, Map.of("path", path));
		}
		catch (IOException e) {
			logger.warn("Failed to read file {}", path, e);
			return errorResult("Failed to read file: " + path, Map.of("path", path));
		}
	}

	/**
	 * Handle the {@code write_text} tool invocation, optionally prompting for overwrite
	 * confirmation.
	 * @param exchange MCP exchange used when prompting for overwrite confirmation
	 * @param callToolRequest incoming tool request payload
	 * @return tool result summarizing the write or error information when the operation fails
	 */
	private McpSchema.CallToolResult handleWriteText(McpSyncServerExchange exchange,
			McpSchema.CallToolRequest callToolRequest) {
		Map<String, Object> arguments = ToolRequestUtils.safeArguments(callToolRequest);
		String path = ToolRequestUtils.stringArgument(arguments, "path", null);
		String content = ToolRequestUtils.stringArgument(arguments, "content", null);
		boolean allowOverwrite = ToolRequestUtils.booleanArgument(arguments, "allowOverwrite", false);

		logger.debug("Handling write_text request for path {} (overwrite allowed: {})", path, allowOverwrite);

		if (!StringUtils.hasText(path)) {
			return errorResult("path argument is required", Map.of("missing", "path"));
		}
		if (content == null) {
			return errorResult("content argument is required", Map.of("missing", "content"));
		}

		try {
			boolean shouldOverwrite = allowOverwrite;
			if (!allowOverwrite && this.fileService.exists(path)) {
				shouldOverwrite = confirmOverwrite(exchange, path);
				if (!shouldOverwrite) {
					return successResult("Skipped writing to " + path,
							Map.of("path", path, "status", "skipped"));
				}
			}
			WriteResult result = this.fileService.write(path, content, shouldOverwrite);
			return successResult(result.summaryLine(), result.toStructured());
		}
		catch (FileAlreadyExistsException e) {
			logger.debug("Write aborted because file {} already exists without overwrite permission", path, e);
			return errorResult("File exists and overwrite is not permitted: " + path,
					Map.of("path", path, "status", "exists"));
		}
		catch (IOException e) {
			logger.warn("Failed to write file {}", path, e);
			return errorResult("Failed to write file: " + path, Map.of("path", path));
		}
	}

	/**
	 * Handle the {@code delete_file} tool invocation, ensuring the user confirms deletion via
	 * elicitation before removing the target file.
	 * @param exchange server exchange used to prompt for confirmation
	 * @param callToolRequest incoming tool request payload
	 * @return tool result summarizing the deletion or error information
	 */
	private McpSchema.CallToolResult handleDeleteFile(McpSyncServerExchange exchange,
			McpSchema.CallToolRequest callToolRequest) {
		Map<String, Object> arguments = ToolRequestUtils.safeArguments(callToolRequest);
		String path = ToolRequestUtils.stringArgument(arguments, "path", null);
		if (!StringUtils.hasText(path)) {
			return errorResult("path argument is required", Map.of("missing", "path"));
		}
		logger.debug("Handling delete_file request for path {}", path);
		try {
			if (!confirmDeletion(exchange, path)) {
				logger.info("Delete declined for {}", path);
				return successResult("Skipped deleting " + path, Map.of("path", path, "status", "skipped"));
			}
			DeleteResult result = this.fileService.delete(path);
			return successResult(result.summaryLine(), result.toStructured());
		}
		catch (NoSuchFileException e) {
			logger.debug("Requested file {} not found for deletion", path, e);
			return errorResult("File not found: " + path, Map.of("path", path));
		}
		catch (IOException e) {
			logger.warn("Failed to delete file {}", path, e);
			return errorResult("Failed to delete file: " + path, Map.of("path", path));
		}
	}

	/**
	 * Prompt the client to decide whether an existing file should be overwritten.
	 * @param exchange server exchange used to issue the prompt
	 * @param path path of the file under consideration
	 * @return {@code true} if the overwrite is confirmed
	 * @throws IOException if path resolution fails
	 */
	private boolean confirmOverwrite(McpSyncServerExchange exchange, String path) throws IOException {
		Path resolved = this.fileService.resolve(path);
		String normalized = this.fileService.relativeString(resolved);

		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("confirm",
				Map.of("type", "boolean", "description", "Set to true to overwrite the existing file."));
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("properties", properties);
		schema.put("required", List.of("confirm"));
		schema.put("additionalProperties", false);

		logger.info("Prompting for overwrite confirmation of {}", normalized);

		McpSchema.ElicitResult result = exchange.createElicitation(McpSchema.ElicitRequest.builder()
			.message("File exists. Overwrite " + normalized + "?")
			.requestedSchema(schema)
			.build());

		if (result == null || result.action() != McpSchema.ElicitResult.Action.ACCEPT) {
			logger.info("Overwrite declined for {}", normalized);
			return false;
		}
		Object confirm = result.content() != null ? result.content().get("confirm") : null;
		boolean accepted = Boolean.TRUE.equals(confirm);
		logger.info("Overwrite {} for {}", accepted ? "confirmed" : "declined", normalized);
		return accepted;
	}

	/**
	 * Prompt the client to confirm that the specified file should be deleted.
	 * @param exchange server exchange used to issue the prompt
	 * @param path file path under consideration
	 * @return {@code true} when the client confirms deletion
	 * @throws IOException if path resolution fails
	 */
	private boolean confirmDeletion(McpSyncServerExchange exchange, String path) throws IOException {
		Path resolved = this.fileService.resolve(path);
		String normalized = this.fileService.relativeString(resolved);

		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("confirm",
				Map.of("type", "boolean", "description", "Set to true to permanently delete the file."));
		properties.put("reason",
				Map.of("type", "string", "description", "Optional reason describing why the file should be deleted."));

		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("properties", properties);
		schema.put("required", List.of("confirm"));
		schema.put("additionalProperties", false);

		logger.info("Prompting for deletion confirmation of {}", normalized);

		McpSchema.ElicitResult result = exchange.createElicitation(McpSchema.ElicitRequest.builder()
			.message("Delete file " + normalized + "?")
			.requestedSchema(schema)
			.build());

		if (result == null || result.action() != McpSchema.ElicitResult.Action.ACCEPT) {
			return false;
		}
		Object confirm = result.content() != null ? result.content().get("confirm") : null;
		boolean accepted = Boolean.TRUE.equals(confirm);
		logger.info("Deletion {} for {}", accepted ? "confirmed" : "declined", normalized);
		return accepted;
	}

	/**
	 * Build the JSON schema describing the input for the {@code list_files} tool.
	 * @return schema definition
	 */
	private McpSchema.JsonSchema listFilesSchema() {
		Map<String, Object> dirSchema = Map.of("type", "string",
				"description", "Relative directory to list (defaults to current).");
		Map<String, Object> properties = Map.of("dir", dirSchema);
		return new McpSchema.JsonSchema("object", properties, List.of(), false, null, null);
	}

	/**
	 * Build the JSON schema describing the input for the {@code read_text} tool.
	 * @return schema definition
	 */
	private McpSchema.JsonSchema readTextSchema() {
		Map<String, Object> pathSchema = Map.of("type", "string",
				"description", "Relative path to the UTF-8 text file.");
		Map<String, Object> properties = Map.of("path", pathSchema);
		return new McpSchema.JsonSchema("object", properties, List.of("path"), false, null, null);
	}

	/**
	 * Build the JSON schema describing the input for the {@code write_text} tool.
	 * @return schema definition
	 */
	private McpSchema.JsonSchema writeTextSchema() {
		Map<String, Object> pathSchema = Map.of("type", "string",
				"description", "Relative path where the text should be written.");
		Map<String, Object> contentSchema = Map.of("type", "string",
				"description", "UTF-8 text content to write.");
		Map<String, Object> overwriteSchema = Map.of("type", "boolean",
				"description", "Set to true to overwrite an existing file without prompting.");

		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("path", pathSchema);
		properties.put("content", contentSchema);
		properties.put("allowOverwrite", overwriteSchema);

		return new McpSchema.JsonSchema("object", properties, List.of("path", "content"), false, null, null);
	}

	/**
	 * Build the JSON schema describing the input for the {@code delete_file} tool.
	 * @return schema definition
	 */
	private McpSchema.JsonSchema deleteFileSchema() {
		Map<String, Object> pathSchema = Map.of("type", "string",
				"description", "Relative path to the file that should be deleted.");
		Map<String, Object> properties = Map.of("path", pathSchema);
		return new McpSchema.JsonSchema("object", properties, List.of("path"), false, null, null);
	}

	/**
	 * Compose a successful tool result while logging the emitted message content.
	 * @param message textual summary returned to the client
	 * @param structuredContent structured payload accompanying the message
	 * @return successful tool result
	 */
	private McpSchema.CallToolResult successResult(String message, Map<String, Object> structuredContent) {
		logger.info("Success response: {}", message);
		return McpSchema.CallToolResult.builder()
			.addTextContent(message)
			.structuredContent(structuredContent)
			.build();
	}

	/**
	 * Compose an error tool result while logging the emitted message content.
	 * @param message textual summary returned to the client
	 * @param structuredContent structured payload accompanying the message
	 * @return error tool result
	 */
	private McpSchema.CallToolResult errorResult(String message, Map<String, Object> structuredContent) {
		logger.warn("Error response: {}", message);
		return McpSchema.CallToolResult.builder()
			.addTextContent(message)
			.isError(true)
			.structuredContent(structuredContent)
			.build();
	}

}
