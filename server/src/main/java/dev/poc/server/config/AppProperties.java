package dev.poc.server.config;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /**
     * Base directory the server operates on.
     */
    private Path baseDir = Paths.get(System.getProperty("user.home"), "mcp-play");

    public Path getBaseDir() {
        return baseDir;
    }

    public void setBaseDir(String baseDir) {
        this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize();
    }
}
