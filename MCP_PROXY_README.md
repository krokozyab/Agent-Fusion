# MCP Proxy - Shell & Batch Script Versions

This directory contains lightweight, zero-dependency MCP JSON-RPC proxies for Claude Desktop integration.

## Overview

Instead of using a compiled TypeScript/Node.js proxy, you can use:
- **macOS/Linux**: `orchestrator-mcp-proxy.sh` (pure shell script)
- **Windows**: `orchestrator-mcp-proxy.bat` (PowerShell wrapper)

These scripts forward JSON-RPC 2.0 messages from Claude Desktop to the Orchestrator HTTP endpoint.

## Files

| File | Platform | Description |
|------|----------|-------------|
| `orchestrator-mcp-proxy.sh` | macOS/Linux | Shell script (bash) proxy |
| `orchestrator-mcp-proxy.bat` | Windows | Batch/PowerShell proxy |

## Installation

### macOS/Linux

1. Make the script executable:
```bash
chmod +x orchestrator-mcp-proxy.sh
```

2. Update Claude Desktop config (`~/.claude/claude_desktop_config.json`):
```json
{
  "mcpServers": {
    "orchestrator": {
      "command": "/absolute/path/to/orchestrator-mcp-proxy.sh",
      "env": {
        "MCP_ENDPOINT": "http://127.0.0.1:3000/mcp/json-rpc"
      }
    }
  }
}
```

3. Restart Claude Desktop

### Windows

1. Update Claude Desktop config (`%APPDATA%\Claude\claude_desktop_config.json`):
```json
{
  "mcpServers": {
    "orchestrator": {
      "command": "cmd",
      "args": ["/c", "C:\\path\\to\\orchestrator-mcp-proxy.bat"],
      "env": {
        "MCP_ENDPOINT": "http://127.0.0.1:3000/mcp/json-rpc"
      }
    }
  }
}
```

2. Restart Claude Desktop

## How It Works

1. Claude Desktop sends JSON-RPC 2.0 requests to the proxy via stdin
2. The proxy forwards requests to the Orchestrator HTTP endpoint
3. The proxy returns responses to stdout for Claude Desktop to consume
4. Automatic retry with exponential backoff for connection failures

### Request Flow

```
Claude Desktop
    ↓ (JSON-RPC via stdin)
orchestrator-mcp-proxy.sh/bat
    ↓ (HTTP POST)
Orchestrator Server (http://127.0.0.1:3000/mcp/json-rpc)
    ↓ (HTTP response)
orchestrator-mcp-proxy.sh/bat
    ↓ (JSON-RPC via stdout)
Claude Desktop
```

## Configuration

### Environment Variables

- `MCP_ENDPOINT`: Orchestrator HTTP endpoint (default: `http://127.0.0.1:3000/mcp/json-rpc`)

Example:
```bash
export MCP_ENDPOINT=http://192.168.1.100:3000/mcp/json-rpc
./orchestrator-mcp-proxy.sh
```

### Retry Behavior

- **Max Retries**: 3 attempts
- **Backoff**: Exponential (1s, 2s, 4s)
- **Timeout**: 60 seconds per request
- **Connection Timeout**: 60 seconds

## Features

✅ **Zero Dependencies**: Uses only `curl` (shell) or `PowerShell` (batch)
✅ **Lightweight**: Single script, no compilation needed
✅ **Fast Distribution**: Easy to copy, no npm/Node.js required
✅ **Error Handling**: Proper JSON-RPC error responses
✅ **Retry Logic**: Exponential backoff for connection failures
✅ **Notifications**: Handles 204 No Content (MCP notifications)
✅ **Logging**: Stderr output for debugging

## Troubleshooting

### Check if Orchestrator is Running

```bash
curl http://127.0.0.1:3000/mcp/json-rpc -X POST \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

### Enable Debugging

Check Claude Desktop logs:
- macOS: `~/Library/Logs/Claude`
- Windows: `%APPDATA%\Claude\logs`

### Test Proxy Directly

```bash
# Create a test request
echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | \
  ./orchestrator-mcp-proxy.sh
```

### Common Issues

**Issue**: "Failed to connect to MCP endpoint"
- Check Orchestrator is running: `java -jar build/libs/orchestrator-0.1.0-all.jar`
- Check endpoint URL: `curl http://127.0.0.1:3000/mcp/json-rpc`

**Issue**: Script permissions denied
- Ensure executable: `chmod +x orchestrator-mcp-proxy.sh`

**Issue**: Command not found: curl
- Install curl: `brew install curl` (macOS) or `choco install curl` (Windows)

## Advantages Over TypeScript Proxy

| Aspect | Shell Script | TypeScript Proxy |
|--------|--------------|------------------|
| **Size** | ~3KB | ~300KB compiled |
| **Dependencies** | curl only | Node.js + npm |
| **Installation** | Copy file + chmod | npm install, compile, etc. |
| **Distribution** | Single script | JAR/binary package |
| **Startup** | Instant | ~1-2 seconds |
| **Maintenance** | Minimal | Requires updates |

## Version History

- **v1.0**: Initial shell/batch script versions
  - Full JSON-RPC 2.0 support
  - Automatic retry with backoff
  - Error handling

## License

Same as Orchestrator project

## Support

For issues or questions:
1. Check the Orchestrator logs
2. Test the HTTP endpoint directly with curl
3. Review this README's troubleshooting section
4. Check Claude Desktop's MCP server logs
