package dev.poc.files.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Represents the result of listing a directory, containing the directory path and its entries.
 * @param directory relative directory path
 * @param entries ordered list of file items contained within the directory
 */
public record DirectoryListing(String directory, List<FileItem> entries) {

	/**
	 * Convert the directory listing into a structured map suitable for MCP responses.
	 * @return structured representation of the directory listing
	 */
	public Map<String, Object> toStructured() {
		Map<String, Object> structured = new LinkedHashMap<>();
		structured.put("directory", directory);
		structured.put("entries",
				entries.stream().map(FileItem::toStructured).collect(Collectors.toList()));
		return structured;
	}

	/**
	 * Generate a human-friendly summary string describing the number of entries present.
	 * @return summary sentence describing the directory contents
	 */
	public String summaryLine() {
		if (entries.isEmpty()) {
			return "No entries inside %s".formatted(directory);
		}
		return "Found %d entries inside %s".formatted(entries.size(), directory);
	}

}

