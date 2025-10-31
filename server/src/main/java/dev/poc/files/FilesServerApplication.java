package dev.poc.files;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the MCP files Spring Boot application.
 */
@SpringBootApplication
public class FilesServerApplication {

	/**
	 * Bootstrap the Spring Boot application.
	 * @param args application arguments passed from the command line
	 */
	public static void main(String[] args) {
		SpringApplication.run(FilesServerApplication.class, args);
	}

}
