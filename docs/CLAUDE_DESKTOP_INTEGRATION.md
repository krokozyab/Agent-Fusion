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

Edit your Claude Desktop configuration file:

**macOS:** `~/Library/Application Support/Claude/claude_desktop_config.json`
**Windows:** `%APPDATA%\Claude\claude_desktop_config.json`
**Linux:** `~/.config/Claude/claude_desktop_config.json`

### Add the following configuration:

```json
{
  "mcpServers": {
    "orchestrator": {
      "url": "http://127.0.0.1:3000/mcp/json-rpc"
    }
  }
}
```

### Full Example Configuration:

```json
{
  "mcpServers": {
    "orchestrator": {
      "url": "http://127.0.0.1:3000/mcp/json-rpc"
    },
    "filesystem": {
      "command": "npx",
      "args": ["@modelcontextprotocol/server-filesystem", "/home/user"]
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

### Connection Refused
- Ensure the Orchestrator server is running on port 3000
- Check firewall settings allow localhost:3000

### "Route not found" Error
- Verify the endpoint is `/mcp/json-rpc` (not `/mcp`)
- Check request method is `POST`

### Invalid JSON Response
- Verify request is valid JSON-RPC 2.0
- Check `Content-Type` header is `application/json`

## Architecture

The integration uses:
- **HttpJsonRpcTransport.kt** - Translates JSON-RPC to MCP methods
- **POST /mcp/json-rpc route** - Ktor HTTP endpoint
- **Stateless request/response** - No session management required
- **Standard JSON-RPC 2.0** - Compatible with all MCP clients

This allows Claude Desktop to communicate with the Orchestrator directly without custom session management or transport protocols.
