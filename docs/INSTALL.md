# Installation Guide

## Requirements

- **Java 21 or higher** - [Download here](https://adoptium.net/)

Check your Java version:
```bash
java -version
```

## Installation Steps

### 1. Download the Distribution

Download the latest release from GitHub:

1. Go to [Releases](https://github.com/krokozyab/claude-codex-orchestrator/releases)
2. Download `release.zip` from the latest release
3. Save it to your preferred location

### 2. Extract the Files

Extract the downloaded ZIP file to any location on your computer. You should see:

```
codex_to_claude/
  ├── codex_to_claude-0.1.0-all.jar
  ├── application.conf
  ├── agents.toml
  ├── start.sh      (Mac/Linux)
  └── start.bat     (Windows)
```

### 3. Configure Agents (Optional)

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

### 4. Start the Orchestrator

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

### 5. Verify It's Running

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

## Configure Amazon Q Developer (IntelliJ)

### 1. Open Amazon Q Chat

In IntelliJ IDEA:
1. Open the **Amazon Q** tool window (usually on the right side)
2. In the chat interface, click the **settings/gear icon** or **menu**

### 2. Add MCP Server

1. Navigate to **MCP Servers** or **Model Context Protocol** settings
2. Click **Add Server** or **+**
3. Enter server details:
   - **Name**: `orchestrator`
   - **Transport**: `HTTP`
   - **URL**: `http://127.0.0.1:3000/mcp`
4. Save the configuration

### 3. Verify Connection

In Amazon Q chat, type:
```
/mcp
```

You should see the orchestrator server listed with available tools:
- assign_task
- complete_task
- continue_task
- create_consensus_task
- create_simple_task
- get_pending_tasks
- get_task_status
- respond_to_task
- submit_input

## Configure Gemini Code Assist

### 1. Add MCP Server

Run the following command:
```bash
gemini mcp add --transport http orchestrator http://127.0.0.1:3000/mcp
```

### 2. Verify Connection

List configured MCP servers:
```bash
gemini mcp list
```

You should see the orchestrator server listed with its available tools.

## Stopping

Press `Ctrl+C` in the terminal window

## Troubleshooting

### "java: command not found"

Install Java 21+ from https://adoptium.net/

### "Port 3000 already in use"

Edit `application.conf` and change the port:
```hocon
orchestrator {
  server {
    port = 3000
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

### Agent Not Recognized ("agentId is required")

**How agent identification works:**

1. When an agent connects via MCP, it sends its name in the `initialize` handshake
2. The orchestrator matches this name to an ID in `agents.toml` (e.g., `[agents.codex-cli]`)
3. The session is linked to that agent ID automatically
4. All tool calls from that session know which agent made them

**If automatic detection fails:**

Check orchestrator logs for: `Session abc-123 associated with agent codex-cli`

No log message? The agent name didn't match your `agents.toml`. Fix it by:
- **Option 1**: Ensure the MCP client sends a name containing your agent ID (e.g., "codex-cli")
- **Option 2**: Add `agentId` parameter to all tool calls: `{"agentId": "codex-cli"}`
- **Option 3**: Add to agent system prompt: "Always include agentId parameter in orchestrator calls"

### Agent Not Following Orchestrator Workflow

**Problem:** Agent completes tasks without providing proper analysis, skips steps, or doesn't synthesize results from multiple agents.

**Common issues:**

1. **Consensus tasks closed too quickly** - Agent marks task complete without analyzing all proposals or combining results
2. **Missing combined analysis** - Agent doesn't summarize what multiple agents said before completing
3. **Skipping own input** - Agent completes consensus task without submitting their own analysis first

**Why this happens:**

AI agents follow their base instructions, which may not include orchestrator-specific workflows. They need explicit guidance about:
- When to submit their own analysis before completing tasks
- How to synthesize multiple agent proposals
- What information to include in task completion

**Solution: Add workflow instructions to agent prompts**

Add these instructions to your agent's system prompt or give them explicitly when needed:

```
ORCHESTRATOR WORKFLOW RULES:

For consensus tasks:
1. First, submit YOUR analysis using respond_to_task() or submit_input()
2. Wait for other agents to respond (check with get_pending_tasks())
3. When all agents responded, use continue_task() to load all proposals
4. SYNTHESIZE and COMPARE all proposals in your completion summary
5. Use complete_task() with combined results showing:
   - What each agent proposed
   - Areas of agreement/disagreement
   - Your recommendation based on all inputs

Never complete a consensus task without:
- Analyzing ALL agent proposals
- Providing a combined summary
- Explaining your reasoning for the final decision
```

**Quick reminder format** (when agent forgets):

"Please provide a combined analysis of all proposals before completing the task. Review what Codex and Q-CLI suggested, compare their approaches, and explain which solution you recommend."

**See also:** `docs/AGENT_ORCHESTRATOR_INSTRUCTIONS.md` for detailed workflow examples

## Configuration Files

### application.conf

Server settings, database path, routing rules. Default values work for most users.

### agents.toml

Which AI agents are enabled. Edit this to add/remove agents.

## Support

For detailed usage and API documentation, see [README.md](../README.md)
