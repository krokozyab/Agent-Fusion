# Claude Desktop Integration Guide

## Overview

The Orchestrator MCP Server now provides a **standard JSON-RPC 2.0 HTTP endpoint** that Claude Desktop can connect to directly.

## Endpoint Details

**URL:** `http://127.0.0.1:3000/mcp/json-rpc`
**Protocol:** JSON-RPC 2.0 over HTTP POST
**Content-Type:** `application/json`

## Supported Methods

### `initialize`
Initialize the MCP server connection.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {}
}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": "1",
  "result": {
    "protocolVersion": "2024-11-05",
    "serverInfo": {
      "name": "Orchestrator MCP Server",
      "version": "1.0.0"
    },
    "capabilities": {
      "tools": { "listChanged": false },
      "resources": { "subscribe": false }
    }
  }
}
```

### `tools/list`
List all available tools.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/list",
  "params": {}
}
```

**Response:** Returns array of tools with name, description, and inputSchema.

### `tools/call`
Execute a specific tool.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "create_simple_task",
    "arguments": {
      "title": "Fix authentication bug",
      "description": "User login fails with 401",
      "type": "BUGFIX"
    }
  }
}
```

**Response:** Returns the tool's result wrapped in JSON-RPC format.

### `resources/list`
List all available resources.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "id": 4,
  "method": "resources/list",
  "params": {}
}
```

**Response:** Returns array of resources with URI, name, and description.

### `resources/read`
Read a specific resource by URI.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "id": 5,
  "method": "resources/read",
  "params": {
    "uri": "tasks://"
  }
}
```

**Response:** Returns resource contents.

## Claude Desktop Configuration

Claude Desktop requires a `command` to launch MCP servers with stdio transport. We provide a wrapper script that bridges stdio to the HTTP endpoint.

Edit your Claude Desktop configuration file:

**macOS:** `~/Library/Application Support/Claude/claude_desktop_config.json`
**Windows:** `%APPDATA%\Claude\claude_desktop_config.json`
**Linux:** `~/.config/Claude/claude_desktop_config.json`

### Using the Stdio Wrapper (Recommended)

The wrapper script (`orchestrator-stdio-wrapper.sh`) bridges Claude Desktop's stdio transport to the HTTP endpoint.

**Add the following configuration:**

```json
{
  "mcpServers": {
    "orchestrator": {
      "command": "/Users/sergeyrudenko/projects/codex_to_claude/orchestrator-stdio-wrapper.sh",
      "args": []
    }
  }
}
```

**Full Example Configuration:**

```json
{
  "mcpServers": {
    "fusion-metadata": {
      "command": "/Users/sergeyrudenko/projects/of_mcp/ofmcp.sh",
      "args": [
        "--db",
        "/Users/sergeyrudenko/projects/of_mcp/metadata.db",
        "--mode",
        "stdio"
      ]
    },
    "orchestrator": {
      "command": "/Users/sergeyrudenko/projects/codex_to_claude/orchestrator-stdio-wrapper.sh",
      "args": []
    }
  }
}
```

### Custom Host/Port (Optional)

If your server is running on a different host or port:

```json
{
  "mcpServers": {
    "orchestrator": {
      "command": "/Users/sergeyrudenko/projects/codex_to_claude/orchestrator-stdio-wrapper.sh",
      "args": ["192.168.1.100", "3000"]
    }
  }
}
```

## Starting the Server

Make sure the Orchestrator MCP Server is running on port 3000:

```bash
java -jar build/libs/orchestrator-0.1.0-all.jar
```

The server will start on:
- **MCP JSON-RPC endpoint:** `http://127.0.0.1:3000/mcp/json-rpc`
- **Web dashboard:** `http://0.0.0.0:8081`

## Testing the Connection

You can test the endpoint using curl:

```bash
# Test initialize
curl -X POST http://127.0.0.1:3000/mcp/json-rpc \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'

# List tools
curl -X POST http://127.0.0.1:3000/mcp/json-rpc \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
```

## Available Tools

The Orchestrator exposes the following MCP tools:

- **Task Management:**
  - `create_simple_task` - Create a solo agent task
  - `create_consensus_task` - Create a multi-agent consensus task
  - `assign_task` - Assign work to a specific agent
  - `get_pending_tasks` - Check task queue
  - `get_task_status` - Get task status
  - `continue_task` - Resume task execution
  - `submit_input` - Submit agent proposals
  - `respond_to_task` - Submit task response
  - `complete_task` - Mark task as complete

- **Context Management:**
  - `query_context` - Search the codebase
  - `get_context_stats` - Context system statistics
  - `refresh_context` - Reindex files
  - `rebuild_context` - Rebuild context database
  - `get_rebuild_status` - Check rebuild progress

## Troubleshooting

### Wrapper Not Found / Command Failed
- Check the wrapper path in config matches your installation
- Verify wrapper is executable: `chmod +x orchestrator-stdio-wrapper.sh`
- Check stderr output for error messages

### "Cannot reach orchestrator server" Error
- Ensure the HTTP server is running on port 3000
- Check: `curl http://127.0.0.1:3000/healthz`
- Start server if needed: `java -jar build/libs/orchestrator-0.1.0-all.jar`

### Connection Refused
- Ensure the Orchestrator server is running on port 3000
- Check firewall settings allow localhost:3000
- Verify server is listening: `lsof -i :3000`

### "Route not found" Error (Direct HTTP Testing)
- Verify the endpoint is `/mcp/json-rpc` (not `/mcp`)
- Check request method is `POST`
- Test with: `curl -X POST http://127.0.0.1:3000/mcp/json-rpc`

### Invalid JSON Response (Direct HTTP Testing)
- Verify request is valid JSON-RPC 2.0
- Check `Content-Type` header is `application/json`

### Debugging the Wrapper

Test the wrapper directly:

```bash
# Test initialization
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' | \
  /Users/sergeyrudenko/projects/codex_to_claude/orchestrator-stdio-wrapper.sh

# Check wrapper logs (stderr)
/Users/sergeyrudenko/projects/codex_to_claude/orchestrator-stdio-wrapper.sh < /dev/null 2>&1 | head -20
```

The wrapper logs all activity to stderr, which Claude Desktop captures. Check Claude's logs if needed.

## Architecture

### Transport Layer

```
Claude Desktop (stdio)
        ↓
orchestrator-stdio-wrapper.sh (stdio-to-HTTP bridge)
        ↓
Ktor HTTP Server (port 3000)
        ↓
POST /mcp/json-rpc endpoint
        ↓
HttpJsonRpcTransport (JSON-RPC routing)
        ↓
McpServerImpl (MCP methods)
```

### Components

- **orchestrator-stdio-wrapper.sh** - Stdio-to-HTTP bridge script
  - Reads JSON-RPC from stdin (from Claude Desktop)
  - Forwards to HTTP endpoint
  - Writes responses to stdout
  - Health checks before forwarding
  - Detailed logging to stderr

- **HttpJsonRpcTransport.kt** - Translates JSON-RPC to MCP methods
  - Routes initialize, tools/list, tools/call, resources/list, resources/read
  - Error handling with JSON-RPC 2.0 format
  - Stateless request/response

- **POST /mcp/json-rpc route** - Ktor HTTP endpoint
  - Receives JSON-RPC requests
  - Routes to HttpJsonRpcTransport
  - Returns JSON-RPC responses

- **Stateless Design** - No session management required
  - Each request is independent
  - No cookies or custom headers
  - Simple HTTP POST semantics

- **Standard JSON-RPC 2.0** - Compatible with all MCP clients
  - Proper error responses
  - Request/response correlation via ID
  - Supports all MCP methods

This architecture allows Claude Desktop to communicate with the Orchestrator using its native stdio transport while the server uses HTTP internally.
