package dev.poc.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.poc.client.transport.TcpClientTransport;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        List<String> arguments = new ArrayList<>(Arrays.asList(args));
        boolean decline = arguments.remove("--decline");
        if (arguments.isEmpty()) {
            printUsage();
            return;
        }
        String command = arguments.remove(0);

        try (TcpClientTransport transport = new TcpClientTransport("localhost", 7071, decline)) {
            transport.connect();
            switch (command) {
                case "init" -> handleInit(transport);
                case "list" -> handleList(transport, arguments);
                case "read" -> handleRead(transport, arguments);
                case "write" -> handleWrite(transport, arguments);
                default -> {
                    System.err.println("Unknown command: " + command);
                    printUsage();
                }
            }
        }
    }

    private static void handleInit(TcpClientTransport transport) throws Exception {
        ObjectNode response = transport.initialize();
        JsonNode result = response.path("result");
        System.out.println("INIT protocol=" + result.path("protocolVersion").asText() + " capabilities=" + result.path("capabilities"));
    }

    private static void handleList(TcpClientTransport transport, List<String> arguments) throws Exception {
        String dir = arguments.isEmpty() ? "." : arguments.get(0);
        ObjectNode response = transport.listFiles(dir);
        String content = response.path("result").path("content").asText();
        System.out.println("LIST:\n" + content);
    }

    private static void handleRead(TcpClientTransport transport, List<String> arguments) throws Exception {
        if (arguments.isEmpty()) {
            throw new IllegalArgumentException("read requires a path argument");
        }
        ObjectNode response = transport.readText(arguments.get(0));
        String content = response.path("result").path("content").asText();
        String preview = content.length() > 100 ? content.substring(0, 100) + "…" : content;
        System.out.println("READ (" + Math.min(100, content.length()) + " chars): " + preview);
    }

    private static void handleWrite(TcpClientTransport transport, List<String> arguments) throws Exception {
        if (arguments.size() < 2) {
            throw new IllegalArgumentException("write requires a path and content");
        }
        String path = arguments.get(0);
        String content = arguments.get(1);
        ObjectNode response = transport.writeText(path, content);
        JsonNode result = response.path("result");
        if (result.has("summary")) {
            System.out.println("OK: " + result.path("summary").asText());
        } else {
            System.out.println("WRITE result: " + result);
        }
    }

    private static void printUsage() {
        System.out.println("Usage: java -jar mcp-files-client.jar [--decline] <command> [args]\n" +
            "Commands:\n" +
            "  init\n" +
            "  list <dir>\n" +
            "  read <path>\n" +
            "  write <path> <content>");
    }
}
