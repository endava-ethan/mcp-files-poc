package dev.poc.files.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Describes a single file system entry returned by a directory listing.
 * @param path relative entry path
 * @param directory {@code true} when the entry is a directory
 * @param size optional size in bytes for regular files
 * @param lastModified optional last modified timestamp
 */
public record FileItem(String path, boolean directory, Long size, Instant lastModified) {

	/**
	 * Render this entry as a structured map suitable for MCP responses.
	 * @return structured representation of the file item
	 */
	public Map<String, Object> toStructured() {
		Map<String, Object> structured = new LinkedHashMap<>();
		structured.put("path", path);
		structured.put("type", directory ? "directory" : "file");
		if (size != null) {
			structured.put("bytes", size);
		}
		if (lastModified != null) {
			structured.put("lastModified", lastModified.toString());
		}
		return structured;
	}

	/**
	 * Produce a short display label describing the entry type.
	 * @return display label combining the path and entry type
	 */
	public String displayLabel() {
		return "%s (%s)".formatted(path, directory ? "dir" : "file");
	}

}

