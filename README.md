# MCP Files PoC

This repository demonstrates a minimal Model Context Protocol (MCP) proof of concept built with Java 25 and Maven. The project contains three modules:

- `transport-common` – shared envelope/codec/logging utilities for the custom transport
- `server` – a Spring Boot application that exposes MCP tools over a length-prefixed TCP transport
- `client` – a console client that exercises the server end-to-end using the same transport

Both the client and server emit detailed WIRE logs so you can follow every JSON-RPC envelope that traverses the connection.

## Building

```bash
mvn -DskipTests package
```

This produces runnable JARs:

- `server/target/mcp-files-server.jar`
- `client/target/mcp-files-client.jar`

## Running the demo

1. Start the server:

   ```bash
   java -jar server/target/mcp-files-server.jar
   ```

   The server listens on TCP port `7071` and creates `${user.home}/mcp-play` as its working directory.

2. In a separate terminal, drive the client workflow:

   ```bash
   java -jar client/target/mcp-files-client.jar init
   java -jar client/target/mcp-files-client.jar list .
   java -jar client/target/mcp-files-client.jar write notes/todo.txt "buy milk"
   java -jar client/target/mcp-files-client.jar read notes/todo.txt
   ```

   Add `--decline` before the command if you want the client to refuse overwrite requests.

During the `write` call the server issues an MCP `elicitation/create` request to confirm overwriting existing files; the client auto-accepts by default. Watch the `WIRE` logger output on both sides to see the framed JSON exchange.
