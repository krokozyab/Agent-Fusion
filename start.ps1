# ==============================================================================
# Agent Fusion Startup Script - Windows PowerShell
# ==============================================================================
# This script starts the Agent Fusion orchestrator server.
#
# Usage:
#   .\start.ps1                              # Use default fusionagent.toml
#   .\start.ps1 -ConfigFile fusionagent_win.toml   # Use custom config
#   .\start.ps1 -JarFile custom\path\app.jar       # Use custom JAR
#
# Note: If you get execution policy errors, run first:
#   Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
# ==============================================================================

param(
    [string]$ConfigFile = "fusionagent.toml",
    [string]$JarFile = "build\libs\orchestrator-0.1.0-all.jar",
    [switch]$Help
)

# Enable ANSI color support (Windows 10.0.14931+)
$PSDefaultParameterValues['Out-Default:OutVariable'] = 'null'
$null = $host.UI.RawUI.ForegroundColor

function Write-ColorOutput([string]$Message, [string]$Color = "White") {
    $colors = @{
        "Green"  = [System.ConsoleColor]::Green
        "Red"    = [System.ConsoleColor]::Red
        "Yellow" = [System.ConsoleColor]::Yellow
        "Blue"   = [System.ConsoleColor]::Cyan
        "White"  = [System.ConsoleColor]::White
    }

    $originalColor = $host.UI.RawUI.ForegroundColor
    if ($colors.ContainsKey($Color)) {
        $host.UI.RawUI.ForegroundColor = $colors[$Color]
    }
    Write-Host $Message -NoNewline
    $host.UI.RawUI.ForegroundColor = $originalColor
}

function Write-Bullet([string]$Message) {
    Write-Host "  " -NoNewline
    Write-ColorOutput "✓ " "Green"
    Write-Host $Message
}

function Write-Error-Custom([string]$Message) {
    Write-Host ""
    Write-ColorOutput "[ERROR] " "Red"
    Write-Host $Message
}

function Show-Help {
    Write-Host "Agent Fusion Startup Script - PowerShell"
    Write-Host ""
    Write-Host "Usage: .\start.ps1 [OPTIONS]"
    Write-Host ""
    Write-Host "Options:"
    Write-Host "  -ConfigFile FILE   Use custom config file (default: fusionagent.toml)"
    Write-Host "  -JarFile FILE      Use custom JAR file (default: build\libs\orchestrator-0.1.0-all.jar)"
    Write-Host "  -Help              Show this help message"
    Write-Host ""
    Write-Host "Examples:"
    Write-Host "  .\start.ps1"
    Write-Host "  .\start.ps1 -ConfigFile fusionagent_win.toml"
    Write-Host "  .\start.ps1 -JarFile custom\path\orchestrator.jar"
    Write-Host ""
    Write-Host "Note: If you get execution policy errors, run:"
    Write-Host "  Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser"
    exit 0
}

if ($Help) {
    Show-Help
}

Clear-Host
Write-Host ""
Write-ColorOutput "╔════════════════════════════════════════════════════════════════╗`n" "Blue"
Write-ColorOutput "║          Agent Fusion - Orchestrator Server                  ║`n" "Blue"
Write-ColorOutput "╚════════════════════════════════════════════════════════════════╝`n" "Blue"
Write-Host ""

# Check if Java is installed
Write-ColorOutput "Checking Java installation..." "Yellow"
$javaCmd = $null
try {
    $javaCmd = Get-Command java -ErrorAction Stop
    Write-Host " " -NoNewline
    Write-ColorOutput "✓ OK`n" "Green"
}
catch {
    Write-Host " " -NoNewline
    Write-ColorOutput "✗ FAILED`n" "Red"
    Write-Error-Custom "Java is not installed or not in PATH"
    Write-Host ""
    Write-Host "Please install Java 21 or higher:"
    Write-Host "  - https://www.java.com/download"
    Write-Host "  - https://adoptopenjdk.net/"
    Write-Host ""
    Read-Host "Press Enter to exit"
    exit 1
}

# Get Java version
try {
    $javaVersion = java -version 2>&1 | Select-String "version" | Select-Object -First 1
    Write-Host "  Java version: $($javaVersion.ToString().Trim())"
}
catch {
    Write-Host "  Java version: (unable to determine)"
}
Write-Host ""

# Check if config file exists
Write-ColorOutput "Checking configuration file..." "Yellow"
if (-not (Test-Path $ConfigFile)) {
    Write-Host " " -NoNewline
    Write-ColorOutput "✗ FAILED`n" "Red"
    Write-Error-Custom "Configuration file not found: $ConfigFile"
    Write-Host ""
    Write-Host "Available config files:"
    $configFiles = Get-ChildItem -Filter "fusionagent*.toml" -ErrorAction SilentlyContinue
    if ($configFiles) {
        $configFiles | ForEach-Object { Write-Host "  - $($_.Name)" }
    } else {
        Write-Host "  (none found)"
    }
    Write-Host ""
    Read-Host "Press Enter to exit"
    exit 1
}
Write-Host " " -NoNewline
Write-ColorOutput "✓ OK`n" "Green"
Write-Bullet "Config: $ConfigFile"
Write-Host ""

# Check if JAR file exists
Write-ColorOutput "Checking JAR file..." "Yellow"
if (-not (Test-Path $JarFile)) {
    Write-Host " " -NoNewline
    Write-ColorOutput "✗ FAILED`n" "Red"
    Write-Error-Custom "JAR file not found: $JarFile"
    Write-Host ""
    Write-Host "To build the JAR file, run:"
    Write-Host "  gradlew.bat shadowJar"
    Write-Host ""
    Read-Host "Press Enter to exit"
    exit 1
}
Write-Host " " -NoNewline
Write-ColorOutput "✓ OK`n" "Green"
Write-Bullet "JAR: $JarFile"

# Get JAR size
$jarSize = (Get-Item $JarFile).Length / 1MB
Write-Bullet "Size: $([Math]::Round($jarSize, 2)) MB"
Write-Host ""

# Display startup information
Write-ColorOutput "Starting Agent Fusion...`n" "Green"
Write-ColorOutput "  Config file: $ConfigFile`n" "White"
Write-ColorOutput "  JAR file: $JarFile`n`n" "White"

Write-ColorOutput "╔════════════════════════════════════════════════════════════════╗`n" "Blue"
Write-ColorOutput "║              Access Points                                   ║`n" "Blue"
Write-ColorOutput "╠════════════════════════════════════════════════════════════════╣`n" "Blue"
Write-ColorOutput "║  MCP Server (Agent Connection):                              ║`n" "Blue"
Write-ColorOutput "║    http://127.0.0.1:3000                                     ║`n" "Blue"
Write-ColorOutput "║                                                              ║`n" "Blue"
Write-ColorOutput "║  Web Dashboard:                                              ║`n" "Blue"
Write-ColorOutput "║    http://localhost:8081                                     ║`n" "Blue"
Write-ColorOutput "║                                                              ║`n" "Blue"
Write-ColorOutput "║  Press Ctrl+C to stop the server                             ║`n" "Blue"
Write-ColorOutput "╚════════════════════════════════════════════════════════════════╝`n" "Blue"
Write-Host ""

# Run the JAR file
& java -jar "$JarFile" --config "$ConfigFile"

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Error-Custom "Agent Fusion failed to start (exit code: $LASTEXITCODE)"
    Write-Host ""
    Read-Host "Press Enter to exit"
    exit $LASTEXITCODE
}

exit 0
