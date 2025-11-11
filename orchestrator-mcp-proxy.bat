@echo off
REM MCP JSON-RPC Proxy - Batch Script Version (Windows)
REM Forwards JSON-RPC 2.0 requests from Claude Desktop to the Orchestrator HTTP endpoint

setlocal enabledelayedexpansion

REM Configuration
set "MCP_ENDPOINT=http://127.0.0.1:3000/mcp/json-rpc"
if not "!MCP_ENDPOINT!"=="" (
    if not "!MCP_ENDPOINT:~0,1!"=="!" (
        set "MCP_ENDPOINT=%MCP_ENDPOINT%"
    )
)
set MAX_RETRIES=3
set RETRY_DELAY=1
set TIMEOUT=60

REM Use PowerShell for better JSON-RPC handling
REM This batch script delegates to a PowerShell implementation

powershell -NoProfile -ExecutionPolicy Bypass -Command "
# MCP JSON-RPC Proxy - PowerShell Implementation

param()

$MCP_ENDPOINT = $env:MCP_ENDPOINT -ne '' ? $env:MCP_ENDPOINT : 'http://127.0.0.1:3000/mcp/json-rpc'
$MAX_RETRIES = 3
$RETRY_DELAY = 1
$TIMEOUT = 60

function Write-Error-Log {
    param([string]$Message)
    [Console]::Error.WriteLine('[MCP Proxy] ' + $Message)
}

function Forward-Request {
    param([string]$Request)

    $attempt = 1
    while ($attempt -le $MAX_RETRIES) {
        try {
            $response = Invoke-WebRequest `
                -Uri $MCP_ENDPOINT `
                -Method POST `
                -Headers @{'Content-Type' = 'application/json'} `
                -Body $Request `
                -TimeoutSec $TIMEOUT `
                -SkipHttpErrorCheck:$true

            # Handle 204 No Content (notifications)
            if ($response.StatusCode -eq 204) {
                return @{Empty = $true}
            }

            # Handle 200 OK
            if ($response.StatusCode -eq 200) {
                return @{Content = $response.Content; Success = $true}
            }

            # Retry on 5xx errors
            if ($response.StatusCode -ge 500) {
                if ($attempt -lt $MAX_RETRIES) {
                    $delay = $RETRY_DELAY * [Math]::Pow(2, $attempt - 1)
                    Write-Error-Log \"Attempt $attempt/$MAX_RETRIES failed (HTTP $($response.StatusCode)), retrying in ${delay}s...\"
                    Start-Sleep -Seconds $delay
                    $attempt++
                    continue
                }
            }

            # Return error response
            \$id = ((\$Request | ConvertFrom-Json).id)
            \$errorResp = @{
                jsonrpc = '2.0'
                id = \$id
                error = @{
                    code = -32000
                    message = \"HTTP $($response.StatusCode): Failed to connect to MCP endpoint\"
                    data = @{endpoint = \$MCP_ENDPOINT; attempts = \$MAX_RETRIES}
                }
            }
            return @{Content = (\$errorResp | ConvertTo-Json -Compress); Success = $false}
        }
        catch {
            if ($attempt -lt $MAX_RETRIES) {
                $delay = $RETRY_DELAY * [Math]::Pow(2, $attempt - 1)
                Write-Error-Log \"Attempt $attempt/$MAX_RETRIES failed (\$($_.Exception.Message)), retrying in ${delay}s...\"
                Start-Sleep -Seconds $delay
                $attempt++
                continue
            }

            # All retries failed
            \$id = try { (\$Request | ConvertFrom-Json).id } catch { \$null }
            \$errorResp = @{
                jsonrpc = '2.0'
                id = \$id
                error = @{
                    code = -32603
                    message = \"Failed to connect to MCP endpoint after \$MAX_RETRIES attempts\"
                    data = @{endpoint = \$MCP_ENDPOINT}
                }
            }
            return @{Content = (\$errorResp | ConvertTo-Json -Compress); Success = $false}
        }
    }
}

# Main loop: Read JSON-RPC messages from stdin
Write-Error-Log \"Starting MCP Proxy, forwarding to \$MCP_ENDPOINT\"
Write-Error-Log \"Waiting for JSON-RPC messages from stdin...\"

while (\$true) {
    \$line = [Console]::In.ReadLine()

    if (\$line -eq \$null) {
        break
    }

    # Skip empty lines
    if ([string]::IsNullOrWhiteSpace(\$line)) {
        continue
    }

    # Validate JSON
    try {
        \$request = \$line | ConvertFrom-Json
    }
    catch {
        [Console]::Out.WriteLine('{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700,\"message\":\"Parse error: Invalid JSON\"}}')
        continue
    }

    # Check for required fields
    if (\$request.jsonrpc -ne '2.0') {
        \$response = @{
            jsonrpc = '2.0'
            id = \$request.id
            error = @{
                code = -32600
                message = 'Invalid JSON-RPC version'
            }
        }
        [Console]::Out.WriteLine((\$response | ConvertTo-Json -Compress))
        continue
    }

    if ([string]::IsNullOrEmpty(\$request.method)) {
        \$response = @{
            jsonrpc = '2.0'
            id = \$request.id
            error = @{
                code = -32600
                message = 'Missing method field'
            }
        }
        [Console]::Out.WriteLine((\$response | ConvertTo-Json -Compress))
        continue
    }

    # Forward to MCP endpoint
    \$result = Forward-Request -Request \$line

    # Only send response if we have one
    if (-not \$result.Empty -and \$result.Content) {
        [Console]::Out.WriteLine(\$result.Content)
    }
}

Write-Error-Log \"stdin closed, exiting\"
"
