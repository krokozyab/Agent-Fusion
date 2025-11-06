@echo off
REM Cross-platform MCP Proxy for Orchestrator
REM Works on Windows

setlocal enabledelayedexpansion

REM Get the directory where this batch file is located
set SCRIPT_DIR=%~dp0

REM Set the MCP endpoint (configurable via environment variable)
if not defined MCP_ENDPOINT (
    set MCP_ENDPOINT=http://127.0.0.1:3000/mcp/json-rpc
)

REM Run the Node.js proxy
node "%SCRIPT_DIR%dist\index.js"

endlocal
