# Claude Code Agent Setup Guide

## Quick Start

### 1. Configure Agent

Create or edit `config/agents.toml`:

```toml
[agents.claude-code]
type = "CLAUDE_CODE"
name = "Claude Code"
model = "claude-3.5-sonnet"

[agents.claude-code.extra]
url = "http://localhost:3000/mcp"
timeout = "30"
retryAttempts = "3"
```

### 2. Start MCP Server

The Claude Code agent expects an MCP server running at the configured URL.

```bash
# Example: Start your MCP server
./start-mcp-server.sh --port 3000
```

### 3. Load Agent

```kotlin
import com.orchestrator.core.AgentRegistry
import java.nio.file.Path

// Load from config
val registry = AgentRegistry.fromConfig(
    path = Path.of("config/agents.toml")
)

// Get Claude Code agent
val agent = registry.getAgent(AgentId("claude-code"))
println("Agent status: ${agent?.status}")
```

### 4. Health Check

```kotlin
import com.orchestrator.agents.ClaudeCodeAgent

val claudeAgent = agent as? ClaudeCodeAgent
val health = claudeAgent?.healthCheck()

if (health?.healthy == true) {
    println("✓ Claude Code is online (${health.latencyMs}ms)")
} else {
    println("✗ Claude Code is offline: ${health?.error}")
}
```

### 5. Execute Task

```kotlin
import kotlinx.coroutines.runBlocking

runBlocking {
    val result = claudeAgent?.executeTask(
        taskId = TaskId("task-1"),
        description = "Implement user authentication in Kotlin",
        context = mapOf(
            "language" to "kotlin",
            "framework" to "ktor",
            "database" to "postgresql"
        )
    )
    
    println("Result: $result")
}
```

## Configuration Options

### Basic Configuration

```toml
[agents.claude-code]
type = "CLAUDE_CODE"
name = "Claude Code"

[agents.claude-code.extra]
url = "http://localhost:3000/mcp"
```

### With Timeouts

```toml
[agents.claude-code]
type = "CLAUDE_CODE"
name = "Claude Code"

[agents.claude-code.extra]
url = "http://localhost:3000/mcp"
timeout = "60"              # seconds
retryAttempts = "5"
```

### With Model Configuration

```toml
[agents.claude-code]
type = "CLAUDE_CODE"
name = "Claude Code"
model = "claude-3.5-sonnet"
temperature = 0.7
maxTokens = 4096

[agents.claude-code.extra]
url = "http://localhost:3000/mcp"
```

### Multiple Instances

```toml
[agents.claude-code-fast]
type = "CLAUDE_CODE"
name = "Claude Code Fast"
model = "claude-3-haiku"

[agents.claude-code-fast.extra]
url = "http://localhost:3001/mcp"
timeout = "15"

[agents.claude-code-quality]
type = "CLAUDE_CODE"
name = "Claude Code Quality"
model = "claude-3.5-sonnet"

[agents.claude-code-quality.extra]
url = "http://localhost:3002/mcp"
timeout = "60"
```

## Integration with Orchestrator

### Automatic Discovery

```kotlin
// Agents are automatically discovered via SPI
val engine = OrchestrationEngine(agentRegistry)

// Create task
val task = Task(
    id = TaskId("task-1"),
    title = "Implement feature",
    type = TaskType.IMPLEMENTATION,
    complexity = 7,
    risk = 5
)

// Execute - routing will select Claude Code if appropriate
val result = engine.executeTask(task)
```

### Manual Assignment

```kotlin
val task = Task(
    id = TaskId("task-1"),
    title = "Implement feature",
    type = TaskType.IMPLEMENTATION,
    assigneeIds = setOf(AgentId("claude-code"))
)

val result = engine.executeTask(task)
```

### With User Directive

```kotlin
val directive = UserDirective(
    originalText = "implement with claude code",
    assignToAgent = AgentId("claude-code")
)

val result = engine.executeTask(task, directive)
```

## Health Monitoring

### Periodic Health Checks

```kotlin
import kotlinx.coroutines.*

fun startHealthMonitoring(agent: ClaudeCodeAgent) {
    GlobalScope.launch {
        while (isActive) {
            val health = agent.healthCheck()
            
            if (!health.healthy) {
                println("⚠ Claude Code unhealthy: ${health.error}")
            } else if (health.latencyMs > 1000) {
                println("⚠ Claude Code slow: ${health.latencyMs}ms")
            }
            
            delay(60_000) // Check every minute
        }
    }
}
```

### With AgentRegistry

```kotlin
agentRegistry.runHealthChecks { agent ->
    if (agent is ClaudeCodeAgent) {
        runBlocking { agent.healthCheck() }
    }
    agent.status
}
```

## Troubleshooting

### Agent Shows OFFLINE

1. Check MCP server is running:
   ```bash
   curl http://localhost:3000/mcp
   ```

2. Verify URL in config:
   ```toml
   url = "http://localhost:3000/mcp"  # Correct
   ```

3. Check firewall/network:
   ```bash
   telnet localhost 3000
   ```

### Connection Timeouts

Increase timeout in config:
```toml
[agents.claude-code.extra]
timeout = "60"  # Increase from default 30
```

### Retry Failures

Increase retry attempts:
```toml
[agents.claude-code.extra]
retryAttempts = "5"  # Increase from default 3
```

### Agent Not Discovered

1. Verify SPI registration:
   ```bash
   jar tf build/libs/*.jar | grep AgentFactory
   ```

2. Check factory is loaded:
   ```kotlin
   val factories = ServiceLoader.load(AgentFactory::class.java).toList()
   println("Factories: ${factories.map { it.supportedType }}")
   ```

## Testing

### Unit Test

```kotlin
@Test
fun `claude code agent executes task`() = runBlocking {
    val connection = McpConnection("http://localhost:3000/mcp")
    val agent = ClaudeCodeAgent(connection)
    
    val result = agent.executeTask(
        TaskId("test-1"),
        "Write a hello world function"
    )
    
    assertNotNull(result)
}
```

### Integration Test

```kotlin
@Test
fun `agent integrates with orchestrator`() {
    val registry = AgentRegistry.fromConfig()
    val engine = OrchestrationEngine(registry)
    
    val task = Task(
        id = TaskId("test-1"),
        title = "Test task",
        type = TaskType.IMPLEMENTATION,
        assigneeIds = setOf(AgentId("claude-code"))
    )
    
    val result = engine.executeTask(task)
    assertTrue(result.status in setOf(TaskStatus.COMPLETED, TaskStatus.FAILED))
}
```

## Best Practices

1. **Always health check before use**
   ```kotlin
   if (agent.healthCheck().healthy) {
       agent.executeTask(...)
   }
   ```

2. **Handle failures gracefully**
   ```kotlin
   try {
       agent.executeTask(...)
   } catch (e: Exception) {
       // Fallback or retry
   }
   ```

3. **Monitor latency**
   ```kotlin
   val health = agent.healthCheck()
   if (health.latencyMs > 2000) {
       logger.warn("High latency: ${health.latencyMs}ms")
   }
   ```

4. **Use appropriate timeouts**
   - Simple tasks: 15-30 seconds
   - Complex tasks: 60-120 seconds
   - Architecture: 120+ seconds

5. **Configure retries based on network**
   - Local: 1-2 retries
   - Remote: 3-5 retries
   - Unreliable: 5+ retries
