# Agent Fusion - Quick Start Guide

This guide explains how to start Agent Fusion using the startup scripts provided.

## Prerequisites

- **Java 21+** installed and available in PATH
- **JAR file**: `build/libs/orchestrator-0.1.0-all.jar` (build with `./gradlew shadowJar`)
- **Configuration file**: `fusionagent.toml` or `fusionagent_win.toml`

## Building the JAR

If you haven't built the JAR yet:

```bash
# macOS/Linux
./gradlew shadowJar

# Windows
gradlew.bat shadowJar
```

The JAR file will be created at: `build/libs/orchestrator-0.1.0-all.jar`

---

## Starting on macOS / Linux

Use the **`start.sh`** script:

```bash
# Make executable (first time only)
chmod +x start.sh

# Start with default config
./start.sh

# Start with custom config
./start.sh --agents fusionagent_win.toml

# Start with custom JAR location
./start.sh --jar /path/to/custom.jar

# Show help
./start.sh --help
```

**Features:**
- ✓ Checks Java installation
- ✓ Verifies config file exists
- ✓ Verifies JAR file exists
- ✓ Shows Java version and JAR size
- ✓ Color-coded output with status indicators
- ✓ Displays access points

---

## Starting on Windows (Batch)

Use the **`start.bat`** script:

```cmd
REM Start with default config
start.bat

REM Start with custom config
start.bat -a fusionagent_win.toml

REM Start with custom JAR
start.bat -j custom\path\orchestrator.jar

REM Show help
start.bat -h
```

**Features:**
- ✓ Checks Java installation
- ✓ Verifies config file exists
- ✓ Verifies JAR file exists
- ✓ Shows Java version and JAR size
- ✓ Error handling with pause for troubleshooting
- ✓ Displays access points

---

## Starting on Windows (PowerShell)

Use the **`start.ps1`** script:

```powershell
# Start with default config
.\start.ps1

# Start with custom config
.\start.ps1 -AgentsFile fusionagent_win.toml

# Start with custom JAR
.\start.ps1 -JarFile custom\path\orchestrator.jar

# Show help
.\start.ps1 -Help
```

**First-time setup (if you get execution policy error):**

```powershell
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
```

**Features:**
- ✓ ANSI color support (Windows 10+)
- ✓ Checks Java installation
- ✓ Verifies config file exists
- ✓ Verifies JAR file exists
- ✓ Shows Java version and JAR size
- ✓ Error handling with helpful messages
- ✓ Displays access points

---

## Access Points After Start

Once the server is running, you'll see:

```
MCP Server (Agent Connection):
  http://127.0.0.1:3000

Web Dashboard:
  http://localhost:8081
```

**Web Dashboard:**
- Open in browser: http://localhost:8081
- View tasks, proposals, decisions
- Monitor context index status
- Trigger refresh/rebuild operations

**MCP Server:**
- Connect Claude Code CLI: `http://127.0.0.1:3000`
- Connect other agents to this endpoint

---

## Stopping the Server

Press **Ctrl+C** in the terminal where the server is running.

---

## Configuration Files

### Default (macOS/Linux preferred)
- **File**: `fusionagent.toml`
- **Features**: Unix paths, absolute paths from `/Users/...`

### Windows Variant
- **File**: `fusionagent_win.toml`
- **Features**: Windows path examples with forward slashes, relative paths

Both files are fully compatible across platforms. Use whichever is easier for you.

---

## Example Scenarios

### Scenario 1: Basic Start (Recommended)

```bash
# macOS/Linux
./start.sh

# Windows (Batch)
start.bat

# Windows (PowerShell)
.\start.ps1
```

### Scenario 2: Custom Configuration

```bash
# macOS/Linux with Windows-style config
./start.sh --agents fusionagent_win.toml

# Windows with custom config
start.bat -a my-custom-config.toml
```

### Scenario 3: Development (Different JAR location)

```bash
# macOS/Linux
./start.sh --jar ./out/orchestrator.jar

# Windows
start.bat -j .\out\orchestrator.jar
```

### Scenario 4: Troubleshooting

If you encounter issues:

```bash
# Check Java is installed and in PATH
java -version

# Check config file exists
ls -la fusionagent*.toml    # macOS/Linux
dir /b fusionagent*.toml    # Windows

# Check JAR file exists
ls -la build/libs/orchestrator-0.1.0-all.jar      # macOS/Linux
dir /b build\libs\orchestrator-0.1.0-all.jar     # Windows

# Run with help to see all options
./start.sh --help           # macOS/Linux
start.bat -h                # Windows
.\start.ps1 -Help          # Windows PowerShell
```

---

## Logs and Output

The startup scripts will display:

1. **System checks** - Java version, config file, JAR file, file sizes
2. **Startup message** - Beginning of server startup
3. **Access points** - URLs for MCP server and web dashboard
4. **Server logs** - Real-time logs from the running server

To save logs to a file:

```bash
# macOS/Linux
./start.sh 2>&1 | tee server.log

# Windows (Batch) - No direct pipe, can redirect to file
# start.bat > server.log 2>&1

# Windows PowerShell
.\start.ps1 | Tee-Object -FilePath server.log
```

---

## Performance Notes

- **First start**: May take 30-60 seconds while initializing the database and indexing files
- **Subsequent starts**: Much faster (5-15 seconds)
- **Memory**: Adjust JVM heap if needed by editing the startup scripts

To customize JVM memory (edit the startup script):

```bash
# Add to java command:
-Xmx4g    # Max heap: 4GB
-Xms2g    # Initial heap: 2GB
```

---

## Troubleshooting

### "Java is not installed"
- Install Java 21+: https://www.java.com/download
- Add Java to PATH (usually done automatically)
- Verify: `java -version`

### "Config file not found"
- Ensure `fusionagent.toml` or `fusionagent_win.toml` exists in the project root
- Specify custom config: `./start.sh --config path/to/config.toml`

### "JAR file not found"
- Build the JAR: `./gradlew shadowJar`
- Default location: `build/libs/orchestrator-0.1.0-all.jar`
- Specify custom JAR: `./start.sh --jar path/to/orchestrator.jar`

### Port already in use
- MCP Server uses port 3000
- Web Dashboard uses port 8081
- Stop other services using these ports
- Or edit config file to use different ports

### PowerShell execution policy error (Windows)
```powershell
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
```

---

## Next Steps

1. Start the server using one of the scripts above
2. Open http://localhost:8081 in your browser
3. Connect AI agents to `http://127.0.0.1:3000` (MCP endpoint)
4. Check docs for agent configuration and workflow examples

See:
- [Main README](README.md)
- [Installation Guide](docs/INSTALL.md)
- [Agent Instructions](docs/AGENT_ORCHESTRATOR_INSTRUCTIONS.md)
