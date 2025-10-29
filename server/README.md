# MCP Files Server

Spring Boot application that exposes three MCP tools (`list_files`, `read_text`, `write_text`) using a custom TCP transport with length-prefixed frames.

## Build & Run

```bash
mvn -pl server -am -DskipTests package
java -jar server/target/mcp-files-server.jar
```

The server listens on `localhost:7071` and creates `${user.home}/mcp-play` on startup. Each TCP connection gets its own session identifier and a dedicated thread that bridges incoming MCP JSON-RPC requests to the tool handlers.

### Expected log flow

A successful workflow emits log lines similar to:

```
TX conn=/127.0.0.1:53462 type=response req=null corr=cli-1 final=true seq=1 json={"jsonrpc":"2.0","id":"cli-1","result":...}
RX conn=/127.0.0.1:53462 type=request req=cli-2 corr=null final=false seq=1 json={"jsonrpc":"2.0","id":"cli-2","method":"tools/list"...}
```

During `write_text` the server pauses to send an `elicitation/create` request and waits for the client response before returning the final result with `fin=true`.
