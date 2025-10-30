# MCP Files PoC

This repository now focuses on the simplest possible Model Context Protocol (MCP)
server written in Java. It uses a tiny in-repo shim inspired by the
[`modelcontextprotocol/java-sdk`](https://github.com/modelcontextprotocol/java-sdk)
project so the code reads like an SDK-based server while remaining completely
self-contained and buildable without network access.

The server exposes three tools:

- `list_files` – lists a single directory relative to the configured root
- `read_text` – prints the contents of a UTF-8 file
- `write_text` – writes a UTF-8 file and requests confirmation before
  overwriting existing files via the MCP elicitation flow

## Structure

- `server/` – contains the STDIO MCP server

## Running

The server speaks JSON-RPC 2.0 over stdio. To try it out you can pipe JSON
requests manually or connect it to any MCP-compatible client. A minimal manual
session looks like this:

```bash
# Terminal 1 – start the server
java -cp server/target/classes dev.poc.files.McpFilesServer
```

```bash
# Terminal 2 – drive it with your MCP client of choice
# (for example the samples that ship with the modelcontextprotocol/java-sdk repo)
printf '%s\n' '{"jsonrpc":"2.0","id":"1","method":"initialize"}' \
  '{"jsonrpc":"2.0","id":"2","method":"tools/list"}' | ./path-to-client
```

The base directory defaults to `${user.home}/mcp-play`; override it with the
`MCP_FILES_BASE_DIR` environment variable when launching the server.

Because the project has no external dependencies you can compile it with a
vanilla JDK:

```bash
javac $(find server/src/main/java -name '*.java') -d server/target/classes
java -cp server/target/classes dev.poc.files.McpFilesServer
```

## Next steps

With a pure MCP baseline in place it's straightforward to extend the
`com.modelcontextprotocol.sdk` shim or swap it out for the official SDK when
you have network access. From there you can experiment with custom transports
or more sophisticated tooling.
