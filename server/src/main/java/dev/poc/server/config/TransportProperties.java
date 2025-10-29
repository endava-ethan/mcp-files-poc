package dev.poc.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "transport.tcp")
public class TransportProperties {

    private int port = 7071;

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }
}
