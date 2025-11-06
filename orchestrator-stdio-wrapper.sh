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

set -u
# Don't use -e because we need to handle errors gracefully

# Configuration
HOST="${1:-127.0.0.1}"
PORT="${2:-3000}"
ENDPOINT="http://${HOST}:${PORT}/mcp/json-rpc"
REQUEST_ID=0

# Logging (to stderr so it doesn't interfere with stdout)
log() {
    echo "[$(date +'%Y-%m-%d %H:%M:%S')] $*" >&2
}

# Send JSON-RPC error response to Claude Desktop
send_error() {
    local id="$1"
    local code="$2"
    local message="$3"

    local response="{\"jsonrpc\":\"2.0\",\"error\":{\"code\":$code,\"message\":\"$message\"},\"id\":$id}"
    echo "$response"
}

log "Orchestrator MCP Stdio Wrapper starting"
log "Forwarding to: $ENDPOINT"

# Health check - log warning but don't exit
check_server() {
    if ! curl -s -f "http://${HOST}:${PORT}/healthz" > /dev/null 2>&1; then
        log "WARNING: Cannot reach orchestrator server at http://${HOST}:${PORT}"
        log "Make sure the server is running: java -jar build/libs/orchestrator-0.1.0-all.jar"
        return 1
    fi
    log "✓ Server health check passed"
    return 0
}

# Try health check, but don't fail if it doesn't work yet
check_server || log "Health check failed, will retry on first request"

# Main loop - read requests from stdin, forward to HTTP, write responses to stdout
while IFS= read -r line; do
    # Skip empty lines
    if [[ -z "$line" ]]; then
        continue
    fi

    log "Received: $line"

    # Extract request ID from JSON (for error responses)
    request_id=$(echo "$line" | grep -o '"id"[^,}]*' | head -1 | cut -d':' -f2 | xargs || echo "null")

    # Verify server is reachable (lazy health check)
    if ! curl -s -f "http://${HOST}:${PORT}/healthz" > /dev/null 2>&1; then
        log "ERROR: Server unreachable at $ENDPOINT"
        send_error "$request_id" -32603 "Internal error: orchestrator server unreachable"
        continue
    fi

    # Forward to HTTP endpoint
    http_status=$(curl -s -o /tmp/mcp_response.json -w "%{http_code}" -X POST "$ENDPOINT" \
        -H "Content-Type: application/json" \
        -d "$line" 2>/dev/null)

    if [[ "$http_status" == "200" ]]; then
        response=$(cat /tmp/mcp_response.json 2>/dev/null || echo '{}')
        log "Response (HTTP $http_status): $response"
        echo "$response"
    else
        log "ERROR: HTTP $http_status from server"
        send_error "$request_id" -32603 "Internal error: HTTP $http_status from server"
    fi

done

log "Wrapper shutting down"
