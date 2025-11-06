#!/bin/bash
#
# MCP Stdio-to-HTTP Wrapper for Orchestrator
#
# This script bridges Claude Desktop's stdio transport to the HTTP-based
# Orchestrator MCP server on port 3000.
#
# It reads JSON-RPC 2.0 messages from stdin, forwards them to the HTTP
# endpoint, and writes responses back to stdout.
#
# Usage: orchestrator-stdio-wrapper.sh [--host HOST] [--port PORT]
#

set -euo pipefail

# Configuration
HOST="${1:-127.0.0.1}"
PORT="${2:-3000}"
ENDPOINT="http://${HOST}:${PORT}/mcp/json-rpc"

# Logging (to stderr so it doesn't interfere with stdout)
log() {
    echo "[$(date +'%Y-%m-%d %H:%M:%S')] $*" >&2
}

log "Orchestrator MCP Stdio Wrapper starting"
log "Forwarding to: $ENDPOINT"

# Health check - verify server is reachable
check_server() {
    if ! curl -s -f "http://${HOST}:${PORT}/healthz" > /dev/null 2>&1; then
        log "ERROR: Cannot reach orchestrator server at http://${HOST}:${PORT}"
        log "Make sure the server is running: java -jar build/libs/orchestrator-0.1.0-all.jar"
        exit 1
    fi
    log "✓ Server health check passed"
}

check_server

# Main loop - read requests from stdin, forward to HTTP, write responses to stdout
while IFS= read -r line; do
    # Skip empty lines
    if [[ -z "$line" ]]; then
        continue
    fi

    log "Received: $line"

    # Forward to HTTP endpoint
    response=$(curl -s -X POST "$ENDPOINT" \
        -H "Content-Type: application/json" \
        -d "$line" 2>/dev/null || echo '{"jsonrpc":"2.0","error":{"code":-32700,"message":"HTTP request failed"},"id":null}')

    log "Response: $response"

    # Write response to stdout (this goes to Claude Desktop)
    echo "$response"

done

log "Wrapper shutting down"
