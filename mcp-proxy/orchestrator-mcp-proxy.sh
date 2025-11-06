#!/bin/bash
# Cross-platform MCP Proxy for Orchestrator
# Works on macOS and Linux

set -e

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Set the MCP endpoint (configurable via environment variable)
export MCP_ENDPOINT="${MCP_ENDPOINT:-http://127.0.0.1:3000/mcp/json-rpc}"

# Run the Node.js proxy
exec node "$SCRIPT_DIR/dist/index.js"
