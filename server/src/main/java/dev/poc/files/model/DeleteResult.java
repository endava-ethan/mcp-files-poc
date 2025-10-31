package dev.poc.files.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents the outcome of deleting a file from the workspace.
 * @param path relative path of the deleted file
 * @param lastModified last modification timestamp recorded before deletion, when available
 */
public record DeleteResult(String path, Instant lastModified) {

	/**
	 * Produce a structured representation suitable for MCP responses.
	 * @return structured map describing the deletion
	 */
	public Map<String, Object> toStructured() {
		Map<String, Object> structured = new LinkedHashMap<>();
		structured.put("path", path);
		structured.put("status", "deleted");
		if (lastModified != null) {
			structured.put("lastModified", lastModified.toString());
		}
		return structured;
	}

	/**
	 * Provide a concise textual summary of the delete operation.
	 * @return summary string describing the deletion
	 */
	public String summaryLine() {
		return "Deleted %s".formatted(path);
	}

}

