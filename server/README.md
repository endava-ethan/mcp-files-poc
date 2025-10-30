# STDIO MCP file server

This module contains a compact Model Context Protocol server that communicates
using newline-delimited JSON-RPC over stdio. The implementation mimics the
structure of the official [`modelcontextprotocol/java-sdk`](https://github.com/modelcontextprotocol/java-sdk)
so you can later swap in the published artifact without changing the rest of
the code.

## Tools

The server advertises three tools:

| Tool        | Description                                              |
|-------------|----------------------------------------------------------|
| list_files  | List one directory level under the configured base path. |
| read_text   | Read a UTF-8 text file.                                  |
| write_text  | Write a UTF-8 text file, prompting before overwriting.   |

All file operations are sandboxed to the base directory (defaults to
`${user.home}/mcp-play`; override with `MCP_FILES_BASE_DIR`).

## Building and running

```bash
javac $(find src/main/java -name '*.java') -d target/classes
java -cp target/classes dev.poc.files.McpFilesServer
```

During startup the server prints the base directory to `stderr` and then waits
for JSON-RPC requests on `stdin`.
