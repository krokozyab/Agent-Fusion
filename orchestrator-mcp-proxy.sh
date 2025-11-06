#!/bin/bash
# MCP JSON-RPC Proxy - Shell Script Version
# Forwards JSON-RPC 2.0 requests from Claude Desktop to the Orchestrator HTTP endpoint
# Usage: orchestrator-mcp-proxy.sh

set -o pipefail

# Configuration
MCP_ENDPOINT="${MCP_ENDPOINT:-http://127.0.0.1:3000/mcp/json-rpc}"
MAX_RETRIES=3
RETRY_DELAY=1
TIMEOUT=60

# Helper function: Log to stderr
log_error() {
    echo "[MCP Proxy] $*" >&2
}

# Helper function: Retry with exponential backoff
forward_request() {
    local request="$1"
    local attempt=1
    local temp_file="/tmp/mcp_response_$$.txt"
    local http_code_file="/tmp/mcp_code_$$.txt"

    while [ $attempt -le $MAX_RETRIES ]; do
        # Use separate files for body and HTTP code
        curl -s -o "$temp_file" -w "%{http_code}" \
            -X POST "$MCP_ENDPOINT" \
            -H "Content-Type: application/json" \
            --connect-timeout "$TIMEOUT" \
            --max-time "$TIMEOUT" \
            -d "$request" > "$http_code_file" 2>/dev/null

        local http_code=$(cat "$http_code_file" 2>/dev/null)
        local body=$(cat "$temp_file" 2>/dev/null)

        # Clean up temp files
        rm -f "$temp_file" "$http_code_file"

        # Handle 204 No Content (notifications)
        if [ "$http_code" = "204" ]; then
            return 0  # No response needed
        fi

        # Handle 200 OK
        if [ "$http_code" = "200" ]; then
            echo "$body"
            return 0
        fi

        # Retry on connection errors or 5xx
        if [ -z "$http_code" ] || { [ "$http_code" -ge 500 ] 2>/dev/null; }; then
            if [ $attempt -lt $MAX_RETRIES ]; then
                local delay=$((RETRY_DELAY * (2 ** (attempt - 1))))
                log_error "Attempt $attempt/$MAX_RETRIES failed (HTTP $http_code), retrying in ${delay}s..."
                sleep "$delay"
                ((attempt++))
                continue
            fi
        fi

        # Return error response for other cases
        echo "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32000,\"message\":\"HTTP $http_code: Failed to connect to MCP endpoint\",\"data\":{\"endpoint\":\"$MCP_ENDPOINT\",\"attempts\":$MAX_RETRIES}}}"
        return 0
    done

    # All retries failed
    rm -f "$temp_file" "$http_code_file"
    echo "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"Failed to connect to MCP endpoint after $MAX_RETRIES attempts\",\"data\":{\"endpoint\":\"$MCP_ENDPOINT\"}}}"
}

# Main loop: Read JSON-RPC messages from stdin and forward
log_error "Starting MCP Proxy, forwarding to $MCP_ENDPOINT"
log_error "Waiting for JSON-RPC messages from stdin..."

while IFS= read -r line; do
    # Skip empty lines
    [ -z "$line" ] && continue

    # Validate JSON
    if ! echo "$line" | grep -q '^{'; then
        echo "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700,\"message\":\"Parse error: Invalid JSON\"}}"
        continue
    fi

    # Extract request ID for proper response pairing
    request_id=$(echo "$line" | grep -o '"id":[^,}]*' | head -1 | cut -d: -f2)

    # Check for required fields
    if ! echo "$line" | grep -q '"jsonrpc"\s*:\s*"2.0"'; then
        echo "{\"jsonrpc\":\"2.0\",\"id\":$request_id,\"error\":{\"code\":-32600,\"message\":\"Invalid JSON-RPC version\"}}"
        continue
    fi

    if ! echo "$line" | grep -q '"method"'; then
        echo "{\"jsonrpc\":\"2.0\",\"id\":$request_id,\"error\":{\"code\":-32600,\"message\":\"Missing method field\"}}"
        continue
    fi

    # Forward to MCP endpoint
    response=$(forward_request "$line")

    # Only send response if we have one (notifications may have empty response)
    [ -n "$response" ] && echo "$response"
done

log_error "stdin closed, exiting"
