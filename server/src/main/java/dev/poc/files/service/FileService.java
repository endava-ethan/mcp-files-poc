package dev.poc.files.service;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.poc.files.config.FileServerProperties;
import dev.poc.files.model.DirectoryListing;
import dev.poc.files.model.FileItem;
import dev.poc.files.model.FileReadResult;
import dev.poc.files.model.WriteResult;
import dev.poc.files.model.DeleteResult;

/**
 * Service layer that performs file system operations relative to a configured base directory.
 * The service provides list, read, write, and path resolution utilities used by MCP tools.
 */
@Service
public class FileService {

	private static final Logger logger = LoggerFactory.getLogger(FileService.class);

	private final Path baseDirectory;

	/**
	 * Create a new service rooted at the directory defined by {@link FileServerProperties}.
	 * @param properties configuration supplying the base directory
	 */
	public FileService(FileServerProperties properties) {
		this.baseDirectory = ensureDirectory(properties.determineBaseDir());
		logger.info("Using base directory for MCP file server: {}", this.baseDirectory);
	}

	/**
	 * Retrieve the normalized base directory that all operations are scoped to.
	 * @return absolute base directory path
	 */
	public Path baseDirectory() {
		return this.baseDirectory;
	}

	/**
	 * Enumerate the contents of the provided directory.
	 * @param relativeDir directory to list, relative to the base directory
	 * @return directory listing with metadata about each entry
	 * @throws IOException when the directory cannot be resolved or traversed
	 */
	public DirectoryListing list(String relativeDir) throws IOException {
		Path directory = resolve(relativeDir == null || relativeDir.isBlank() ? "." : relativeDir);
		if (!Files.exists(directory)) {
			throw new NoSuchFileException(relativeDir);
		}
		if (!Files.isDirectory(directory)) {
			throw new NotDirectoryException(relativeDir);
		}

		try (Stream<Path> entries = Files.list(directory)) {
			List<FileItem> listing = entries.map(this::toFileItem)
				.sorted(Comparator.comparing(FileItem::path, String.CASE_INSENSITIVE_ORDER))
				.collect(Collectors.toList());
			return new DirectoryListing(relativeString(directory), listing);
		}
	}

	/**
	 * Read the contents of a file as UTF-8 text.
	 * @param relativePath file path relative to the base directory
	 * @return structured read result containing content and metadata
	 * @throws IOException when the file cannot be read
	 */
	public FileReadResult read(String relativePath) throws IOException {
		Path file = resolve(relativePath);
		if (!Files.exists(file)) {
			throw new NoSuchFileException(relativePath);
		}
		if (!Files.isRegularFile(file)) {
			throw new IOException("Target exists but is not a regular file: " + relativePath);
		}
		String content = Files.readString(file);
		Instant lastModified = Files.getLastModifiedTime(file).toInstant();
		return new FileReadResult(relativeString(file), content, lastModified);
	}

	/**
	 * Determine whether a path exists under the base directory.
	 * @param relativePath candidate path relative to the base directory
	 * @return {@code true} if the path exists
	 * @throws IOException when the path cannot be resolved
	 */
	public boolean exists(String relativePath) throws IOException {
		return Files.exists(resolve(relativePath));
	}

	/**
	 * Write the supplied content to a file, optionally overwriting existing files.
	 * @param relativePath file path relative to the base directory
	 * @param content UTF-8 content to write
	 * @param overwrite {@code true} to permit overwriting existing files
	 * @return result describing the write outcome
	 * @throws IOException when the write fails
	 */
	public WriteResult write(String relativePath, String content, boolean overwrite) throws IOException {
		Path target = resolve(relativePath);
		Path parent = target.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		boolean existed = Files.exists(target);
		if (existed && !Files.isRegularFile(target)) {
			throw new IOException("Target exists and is not a regular file: " + relativePath);
		}
		if (existed && !overwrite) {
			throw new FileAlreadyExistsException(relativePath);
		}
		Files.writeString(target, content);
		Instant lastModified = Files.getLastModifiedTime(target).toInstant();
		return new WriteResult(relativeString(target), existed ? WriteResult.Status.UPDATED : WriteResult.Status.CREATED,
				lastModified);
	}

	/**
	 * Delete the specified file if it exists and is a regular file.
	 * @param relativePath path to the file relative to the base directory
	 * @return deletion result capturing metadata prior to removal
	 * @throws IOException when the delete operation fails
	 */
	public DeleteResult delete(String relativePath) throws IOException {
		Path target = resolve(relativePath);
		if (!Files.exists(target)) {
			throw new NoSuchFileException(relativePath);
		}
		if (!Files.isRegularFile(target)) {
			throw new IOException("Target exists but is not a regular file: " + relativePath);
		}
		Instant lastModified = Files.getLastModifiedTime(target).toInstant();
		Files.delete(target);
		logger.info("Deleted file {}", target);
		return new DeleteResult(relativeString(target), lastModified);
	}

	/**
	 * Resolve a relative path against the base directory, preventing directory traversal attacks.
	 * @param relativePath candidate relative path (may be {@code null})
	 * @return resolved absolute path within the base directory
	 * @throws IOException when the path would escape the base directory
	 */
	public Path resolve(String relativePath) throws IOException {
		Path candidate = this.baseDirectory.resolve(relativePath == null ? "" : relativePath).normalize();
		if (!candidate.startsWith(this.baseDirectory)) {
			throw new IOException("Path escapes base directory: " + relativePath);
		}
		return candidate;
	}

	/**
	 * Produce a stable, forward-slash formatted relative path string from the base directory to the
	 * supplied path.
	 * @param path absolute path within the base directory
	 * @return human-friendly relative path
	 */
	public String relativeString(Path path) {
		Path relative = this.baseDirectory.relativize(path);
		String relativeString = relative.toString().replace('\\', '/');
		return relativeString.isEmpty() ? "." : relativeString;
	}

	/**
	 * Ensure the target directory exists, creating it if necessary.
	 * @param directory directory to create
	 * @return normalized directory path
	 */
	private Path ensureDirectory(Path directory) {
		try {
			Files.createDirectories(directory);
			return directory;
		}
		catch (IOException e) {
			throw new IllegalStateException("Failed to prepare base directory: " + directory, e);
		}
	}

	/**
	 * Convert a filesystem entry into a {@link FileItem} with relevant metadata.
	 * @param entry path to convert
	 * @return file item representation, falling back to best-effort metadata on failure
	 */
	private FileItem toFileItem(Path entry) {
		try {
			BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class);
			boolean directory = attributes.isDirectory();
			Long size = directory ? null : attributes.size();
			Instant lastModified = attributes.lastModifiedTime().toInstant();
			return new FileItem(relativeString(entry), directory, size, lastModified);
		}
		catch (IOException ex) {
			logger.warn("Unable to read file metadata for {}", entry, ex);
			return new FileItem(relativeString(entry), Files.isDirectory(entry), null, null);
		}
	}

}
