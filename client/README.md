# MCP Files Client

Console client that connects to the MCP Files server using the shared custom TCP transport. It implements the standard MCP JSON-RPC flow with support for handling `elicitation/create` requests from the server.

## Build & Run

```bash
mvn -pl client -am -DskipTests package
java -jar client/target/mcp-files-client.jar init
```

### Commands

- `init` – perform the MCP `initialize` handshake and print the negotiated capabilities
- `list <dir>` – call the `list_files` tool relative to the server base directory
- `read <path>` – retrieve file contents using `read_text`
- `write <path> <content>` – invoke `write_text`; the client auto-confirms overwrites unless you pass `--decline`

Example sequence:

```bash
java -jar client/target/mcp-files-client.jar init
java -jar client/target/mcp-files-client.jar list .
java -jar client/target/mcp-files-client.jar write notes/todo.txt "buy milk"
java -jar client/target/mcp-files-client.jar read notes/todo.txt
```

You will see `WIRE` log lines alongside human-readable summaries such as `OK: Wrote: notes/todo.txt` and `READ (12 chars): buy milk`. During an overwrite the client prints the elicitation prompt and its auto-generated response.
