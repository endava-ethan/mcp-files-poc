package dev.poc.files.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents the outcome of reading a file, including the textual content.
 * @param path relative file path
 * @param content file contents as UTF-8 text
 * @param lastModified timestamp of the last modification, when available
 */
public record FileReadResult(String path, String content, Instant lastModified) {

	/**
	 * Convert the read result to a structured map suitable for MCP responses.
	 * @return structured representation of the file read result
	 */
	public Map<String, Object> toStructured() {
		Map<String, Object> structured = new LinkedHashMap<>();
		structured.put("path", path);
		structured.put("content", content);
		if (lastModified != null) {
			structured.put("lastModified", lastModified.toString());
		}
		return structured;
	}

}

