package dev.poc.server;

import dev.poc.server.config.AppProperties;
import dev.poc.server.config.TransportProperties;
import dev.poc.server.transport.TcpServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties({AppProperties.class, TransportProperties.class})
public class McpFilesServerApplication {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpFilesServerApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(McpFilesServerApplication.class, args);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    TcpServer tcpServer(AppProperties appProperties, TransportProperties transportProperties) throws IOException {
        Path baseDir = appProperties.getBaseDir();
        Files.createDirectories(baseDir);
        LOGGER.info("MCP file server root: {}", baseDir);
        return new TcpServer(baseDir, transportProperties.getPort());
    }
}
