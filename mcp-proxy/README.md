# Orchestrator MCP Proxy

A cross-platform TypeScript proxy that bridges Claude Desktop to the Orchestrator MCP Server via HTTP. Works on Windows, macOS, and Linux.

## Features

- **Cross-platform**: Windows, macOS, and Linux support
- **Proper JSON-RPC 2.0 handling**: Supports both requests (with response) and notifications (no response)
- **Robust error handling**: Connection retry logic with exponential backoff
- **Configurable endpoint**: Set `MCP_ENDPOINT` environment variable to change the server URL
- **Proper stdio communication**: Handles stdin/stdout correctly for Claude Desktop integration

## Prerequisites

- Node.js 16+ (download from https://nodejs.org/)
- Orchestrator server running on `http://127.0.0.1:3000/mcp/json-rpc` (configurable)

## Setup

### 1. Install Dependencies

```bash
cd mcp-proxy
npm install
```

### 2. Build the TypeScript Proxy

```bash
npm run build
```

This creates the `dist/index.js` file that will be executed.

### 3. Verify the Proxy Works

Test the proxy with a simple request:

```bash
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' | node dist/index.js
```

You should see a JSON response with the server capabilities.

### 4. Configure Claude Desktop

Update your Claude Desktop configuration file based on your operating system:

#### macOS/Linux

Edit `~/.config/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "orchestrator": {
      "command": "bash",
      "args": [
        "/path/to/codex_to_claude/mcp-proxy/orchestrator-mcp-proxy.sh"
      ]
    }
  }
}
```

Replace `/path/to/codex_to_claude` with your actual project path.

#### Windows

Edit `%APPDATA%\Claude\claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "orchestrator": {
      "command": "cmd",
      "args": [
        "/c",
        "C:\\path\\to\\codex_to_claude\\mcp-proxy\\orchestrator-mcp-proxy.bat"
      ]
    }
  }
}
```

Replace `C:\path\to\codex_to_claude` with your actual project path.

### 5. Test in Claude Desktop

1. Restart Claude Desktop completely (quit and reopen)
2. Open Claude Desktop and create a new conversation
3. Type a request that uses the orchestrator tools, for example:
   - "Search the codebase for PathFilter shouldIgnore"
   - "Find authentication JWT token"

The `query_context` tool should now return search results.

## Environment Variables

- `MCP_ENDPOINT`: The HTTP endpoint of the Orchestrator MCP Server (default: `http://127.0.0.1:3000/mcp/json-rpc`)

Example:
```bash
export MCP_ENDPOINT=http://192.168.1.100:3000/mcp/json-rpc
```

## Troubleshooting

### Proxy doesn't respond

1. **Check the Orchestrator server is running:**
   ```bash
   curl http://127.0.0.1:3000/mcp
   ```
   You should get an HTTP 404 (endpoint doesn't exist) but the connection should work.

2. **Test the proxy directly:**
   ```bash
   echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' | node dist/index.js
   ```

3. **Check Claude Desktop logs:**
   - macOS: `~/Library/Logs/Claude/logs.txt`
   - Windows: `%APPDATA%\Claude\logs\logs.txt`
   - Linux: `~/.config/Claude/logs/logs.txt`

### Connection refused error

If you see "Connection refused" errors:

1. Verify the Orchestrator server is running:
   ```bash
   ps aux | grep orchestrator
   ```

2. Check the server is listening on port 3000:
   ```bash
   lsof -i :3000  # macOS/Linux
   netstat -ano | findstr :3000  # Windows
   ```

3. Make sure the `MCP_ENDPOINT` environment variable is set correctly (if you changed the default)

### Query returns no results

1. Ensure the context database is indexed:
   ```bash
   curl -X POST http://127.0.0.1:3000/api/context/rebuild -H "Content-Type: application/json" -d '{"confirm":true}'
   ```

2. Check the query syntax - use short keywords like:
   - `"PathFilter shouldIgnore"` ✅
   - `"authentication JWT token"` ✅
   - NOT natural language questions ❌

### Claude Desktop can't find the proxy script

1. Verify the path in `claude_desktop_config.json` is correct
2. Use absolute paths (full path starting from root)
3. On Windows, use forward slashes `/` even in absolute paths
4. Make sure the proxy script file exists and is executable

### Proxy timeouts

The proxy has a 60-second timeout per request. If requests are timing out:

1. Check if the Orchestrator server is responding slowly
2. Monitor server logs: `tail -f /tmp/server.log`
3. Check system resources (CPU, memory, disk I/O)

## Development

### Run the proxy in development mode

```bash
npm run dev
```

This uses `ts-node` to run TypeScript directly without compilation.

### Rebuild after code changes

```bash
npm run build
```

### Debug output

The proxy logs to stderr (which Claude Desktop shows in its logs). To see more detail:

```bash
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' | node dist/index.js 2>&1
```

## Architecture

```
Claude Desktop
    ↓ (stdio with JSON-RPC messages)
Proxy Process (Node.js)
    ↓ (HTTP)
Orchestrator MCP Server (Java/Ktor)
    ↓ (Internal MCP handling)
Tools (query_context, create_task, etc.)
```

## How It Works

1. **Claude Desktop** sends JSON-RPC 2.0 requests/notifications via stdin to the proxy
2. **Proxy** reads each line, parses the JSON-RPC message
3. **Proxy** forwards the request to `http://127.0.0.1:3000/mcp/json-rpc` via HTTP
4. **Orchestrator** processes the request and returns a JSON-RPC response
5. **Proxy** writes the response to stdout for Claude Desktop to receive
6. **Claude Desktop** parses the response and processes the result

## JSON-RPC 2.0 Compliance

The proxy properly handles:
- **Requests** (messages with `id` field) → response required
- **Notifications** (messages without `id` field) → no response sent
- **Errors** → proper JSON-RPC error responses
- **Parameter formats** → both `{"arguments": {...}}` and flat `{...}` formats

## License

MIT

## See Also

- [Orchestrator MCP Server](../src/main/kotlin/com/orchestrator/mcp/McpServerImpl.kt)
- [Claude Desktop Documentation](https://github.com/anthropics/claude-desktop)
- [MCP Protocol Specification](https://modelcontextprotocol.io/)
