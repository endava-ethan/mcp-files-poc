package dev.poc.files.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Captures the outcome of a write operation, including status and metadata.
 * @param path relative file path written
 * @param status status describing whether the file was created or updated
 * @param lastModified timestamp of the write, when available
 */
public record WriteResult(String path, Status status, Instant lastModified) {

	/**
	 * Possible write statuses indicating whether a file was created or updated.
	 */
	public enum Status {
		CREATED, UPDATED
	}

	/**
	 * Convert the write result to a structured map suitable for MCP responses.
	 * @return structured representation of the write result
	 */
	public Map<String, Object> toStructured() {
		Map<String, Object> structured = new LinkedHashMap<>();
		structured.put("path", path);
		structured.put("status", status.name().toLowerCase());
		if (lastModified != null) {
			structured.put("lastModified", lastModified.toString());
		}
		return structured;
	}

	/**
	 * Produce a concise summary describing the write action taken.
	 * @return summary statement for the write result
	 */
	public String summaryLine() {
		return switch (status) {
			case CREATED -> "Created %s".formatted(path);
			case UPDATED -> "Updated %s".formatted(path);
		};
	}

}

