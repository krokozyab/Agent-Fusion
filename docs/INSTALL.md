# Installation Guide

## Requirements

- **Java 21 or higher** - [Download here](https://adoptium.net/)

Check your Java version:
```bash
java -version
```

## Installation Steps

### 1. Extract the Files

Extract the distribution folder to any location on your computer. You should see:

```
codex_to_claude/
  ├── codex_to_claude-0.1.0-all.jar
  ├── application.conf
  ├── agents.toml
  ├── start.sh      (Mac/Linux)
  └── start.bat     (Windows)
```

### 2. Configure Agents (Optional)

Edit `agents.toml` to enable/disable agents:

```toml
[agents.claude-code]
type = "CLAUDE_CODE"
name = "Claude"
enabled = true

[agents.codex-cli]
type = "CODEX_CLI"
name = "Codex"
enabled = true
```

### 3. Start the Orchestrator

**Mac/Linux:**
```bash
./start.sh
```

Or double-click `start.sh` in Finder

**Windows:**
```bash
start.bat
```

Or double-click `start.bat` in Explorer

### 4. Verify It's Running

Open your browser and go to:
```
http://localhost:3000/healthz
```

You should see: `{"status":"ok"}`

## Usage

Once running, you can:

- **Create tasks** via HTTP API (see README.md for examples)
- **Check health**: `http://localhost:3000/healthz`
- **View tools**: `http://localhost:3000/mcp/tools`

## Configure Codex CLI

### 1. Edit Codex Configuration

In your home directory, find the `.codex` folder and edit the `config.toml` file:

**Add at the top:**
```toml
experimental_use_rmcp_client = true
```

**Add at the bottom:**
```toml
[mcp_servers.orchestrator]
url = "http://127.0.0.1:3000/mcp"
```

### 2. Verify Connection

Start Codex as usual, then enter the command:
```
/mcp
```

You should see:
```
🔌  MCP Tools

• Server: orchestrator
• URL: http://127.0.0.1:3000/mcp
• Tools: assign_task, complete_task, continue_task, create_consensus_task,
         create_simple_task, get_pending_tasks, get_task_status,
         respond_to_task, submit_input
```

## Configure Claude Code

### 1. Add MCP Server

Run the following command:
```bash
claude mcp add --transport http orchestrator http://127.0.0.1:3000/mcp
```

### 2. Verify Connection

List configured MCP servers:
```bash
claude mcp list
```

You should see the orchestrator server listed with connection status - Connected.

## Stopping

Press `Ctrl+C` in the terminal window

## Troubleshooting

### "java: command not found"

Install Java 21+ from https://adoptium.net/

### "Port 8080 already in use"

Edit `application.conf` and change the port:
```hocon
orchestrator {
  server {
    port = 8081
  }
}
```

### "Permission denied" (Mac/Linux)

Make the script executable:
```bash
chmod +x start.sh
```

### Database Issues

Delete the database and restart:
```bash
rm -rf data/
./start.sh
```

## Configuration Files

### application.conf

Server settings, database path, routing rules. Default values work for most users.

### agents.toml

Which AI agents are enabled. Edit this to add/remove agents.

## Support

For detailed usage and API documentation, see [README.md](../README.md)
