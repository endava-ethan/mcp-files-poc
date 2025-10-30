package dev.poc.files;

import com.modelcontextprotocol.sdk.McpServer;
import com.modelcontextprotocol.sdk.ToolCallContext;
import com.modelcontextprotocol.sdk.ToolHandler;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class McpFilesServer {

    private McpFilesServer() {
    }

    public static void main(String[] args) throws Exception {
        Path baseDir = determineBaseDir();
        Files.createDirectories(baseDir);
        McpServer server = McpServer.stdioServer()
            .tool("list_files", "List one level of files relative to the base directory", listFilesTool(baseDir))
            .tool("read_text", "Read a UTF-8 text file", readTextTool(baseDir))
            .tool("write_text", "Write a UTF-8 text file (prompts before overwrite)", writeTextTool(baseDir))
            .build();
        System.err.println("MCP file server ready at " + baseDir);
        server.run();
    }

    private static Path determineBaseDir() {
        String override = System.getenv("MCP_FILES_BASE_DIR");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), "mcp-play");
    }

    private static ToolHandler listFilesTool(Path baseDir) {
        return (arguments, context) -> {
            String dirArgument = Objects.toString(arguments.getOrDefault("dir", "."));
            Path target = resolvePath(baseDir, dirArgument);
            if (!Files.exists(target)) {
                return Map.of("content", "<missing>");
            }
            if (!Files.isDirectory(target)) {
                return Map.of("content", "<not a directory>");
            }
            List<String> entries = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(target)) {
                for (Path path : stream) {
                    entries.add(baseDir.relativize(path.normalize()).toString());
                }
            }
            String content = entries.stream().sorted().collect(Collectors.joining("\n"));
            return Map.of("content", content);
        };
    }

    private static ToolHandler readTextTool(Path baseDir) {
        return (arguments, context) -> {
            String pathArgument = Objects.toString(arguments.getOrDefault("path", ""));
            if (pathArgument.isBlank()) {
                throw new IOException("path argument is required");
            }
            Path target = resolvePath(baseDir, pathArgument);
            if (!Files.exists(target)) {
                return Map.of("content", "<missing>");
            }
            String content = Files.readString(target);
            return Map.of("content", content);
        };
    }

    private static ToolHandler writeTextTool(Path baseDir) {
        return (arguments, context) -> {
            String pathArgument = Objects.toString(arguments.getOrDefault("path", ""));
            if (pathArgument.isBlank()) {
                throw new IOException("path argument is required");
            }
            Path target = resolvePath(baseDir, pathArgument);
            String content = Objects.toString(arguments.getOrDefault("content", ""));
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (Files.exists(target)) {
                if (!Files.isRegularFile(target)) {
                    throw new IOException("Target exists and is not a regular file: " + pathArgument);
                }
                if (!confirmOverwrite(target, context, baseDir)) {
                    return Map.of("summary", "Declined");
                }
            }
            Files.writeString(target, content);
            return Map.of("summary", "Wrote: " + baseDir.relativize(target));
        };
    }

    private static boolean confirmOverwrite(Path target, ToolCallContext context, Path baseDir) throws Exception {
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> confirm = new LinkedHashMap<>();
        confirm.put("type", "boolean");
        properties.put("confirm", confirm);
        List<String> required = List.of("confirm");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("message", "File exists. Overwrite? " + baseDir.relativize(target));
        params.put("schema", schema);

        Map<String, Object> response = context.request("elicitation/create", params);
        Object valuesObject = response.get("values");
        if (valuesObject instanceof Map<?, ?> values) {
            Object confirmValue = values.get("confirm");
            if (confirmValue instanceof Boolean bool) {
                return bool;
            }
        }
        return false;
    }

    private static Path resolvePath(Path baseDir, String relative) throws IOException {
        Path candidate = baseDir.resolve(relative).normalize();
        if (!candidate.startsWith(baseDir)) {
            throw new IOException("Path escapes base directory: " + relative);
        }
        return candidate;
    }
}
