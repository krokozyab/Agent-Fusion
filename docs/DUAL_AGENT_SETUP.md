# Dual-Agent Setup Guide

## Overview

This guide shows how to set up and use both Claude Code and Codex CLI agents together for optimal task execution.

## Configuration

### config/agents.toml

```toml
# Claude Code - Implementation specialist
[agents.claude-code]
type = "CLAUDE_CODE"
name = "Claude Code"
model = "claude-3.5-sonnet"

[agents.claude-code.extra]
url = "http://localhost:3000/mcp"
timeout = "30"
retryAttempts = "3"

# Codex CLI - Architecture specialist
[agents.codex-cli]
type = "CODEX_CLI"
name = "Codex CLI"

[agents.codex-cli.extra]
url = "http://localhost:3001/mcp"
timeout = "30"
retryAttempts = "3"
```

## Starting MCP Servers

```bash
# Terminal 1: Start Claude Code MCP server
claude-code-mcp --port 3000

# Terminal 2: Start Codex CLI MCP server
codex-cli-mcp --port 3001
```

## Basic Usage

### Load Both Agents

```kotlin
import com.orchestrator.core.AgentRegistry
import com.orchestrator.agents.*

val registry = AgentRegistry.fromConfig()

val claude = registry.getAgent(AgentId("claude-code")) as? ClaudeCodeAgent
val codex = registry.getAgent(AgentId("codex-cli")) as? CodexCLIAgent

// Health check both
println("Claude: ${claude?.healthCheck()?.healthy}")
println("Codex: ${codex?.healthCheck()?.healthy}")
```

## Workflow Patterns

### Pattern 1: Sequential (Plan → Implement)

```kotlin
suspend fun planAndImplement(task: Task): String {
    // Step 1: Codex creates plan
    val plan = codex.executeTask(
        taskId = task.id,
        description = "Create detailed implementation plan for: ${task.description}",
        context = mapOf(
            "complexity" to task.complexity.toString(),
            "risk" to task.risk.toString()
        )
    )
    
    // Step 2: Claude implements
    val implementation = claude.executeTask(
        taskId = task.id,
        description = "Implement based on this plan: $plan",
        context = mapOf("plan" to plan)
    )
    
    return implementation
}
```

### Pattern 2: Consensus (Both Review)

```kotlin
suspend fun consensusReview(code: String): Decision {
    val taskId = TaskId("review-${UUID.randomUUID()}")
    
    // Both agents review
    val claudeReview = claude.executeTask(
        taskId = taskId,
        description = "Review this code for implementation quality: $code"
    )
    
    val codexReview = codex.executeTask(
        taskId = taskId,
        description = "Review this code for architectural quality: $code"
    )
    
    // Submit as proposals
    ProposalManager.submitProposal(
        taskId = taskId,
        agentId = claude.id,
        content = mapOf("review" to claudeReview),
        inputType = InputType.REVIEW
    )
    
    ProposalManager.submitProposal(
        taskId = taskId,
        agentId = codex.id,
        content = mapOf("review" to codexReview),
        inputType = InputType.REVIEW
    )
    
    // Run consensus
    return ConsensusModule.decide(taskId)
}
```

### Pattern 3: Parallel (Independent Analysis)

```kotlin
suspend fun parallelAnalysis(task: Task): Pair<String, String> = coroutineScope {
    val claudeDeferred = async {
        claude.executeTask(
            taskId = task.id,
            description = "Analyze implementation approach: ${task.description}"
        )
    }
    
    val codexDeferred = async {
        codex.executeTask(
            taskId = task.id,
            description = "Analyze architectural approach: ${task.description}"
        )
    }
    
    claudeDeferred.await() to codexDeferred.await()
}
```

### Pattern 4: Routing by Task Type

```kotlin
fun routeTask(task: Task): Agent {
    return when (task.type) {
        TaskType.ARCHITECTURE -> codex
        TaskType.PLANNING -> codex
        TaskType.RESEARCH -> codex
        
        TaskType.IMPLEMENTATION -> claude
        TaskType.REFACTORING -> claude
        TaskType.TESTING -> claude
        TaskType.BUGFIX -> claude
        
        TaskType.REVIEW -> {
            // Use both for reviews
            if (task.complexity >= 7) codex else claude
        }
        
        TaskType.DOCUMENTATION -> {
            // Codex for architecture docs, Claude for code docs
            if (task.description?.contains("architecture") == true) codex else claude
        }
    }
}
```

## Orchestration Engine Integration

### Automatic Routing

```kotlin
val engine = OrchestrationEngine(registry)

// Engine automatically routes based on capabilities
val task = Task(
    id = TaskId("task-1"),
    title = "Design and implement authentication",
    type = TaskType.IMPLEMENTATION,
    complexity = 8,
    risk = 7
)

// Will use consensus workflow due to high complexity/risk
val result = engine.executeTask(task)
```

### Manual Assignment

```kotlin
// Assign to Codex for architecture
val archTask = Task(
    id = TaskId("task-2"),
    title = "Design microservices architecture",
    type = TaskType.ARCHITECTURE,
    assigneeIds = setOf(AgentId("codex-cli"))
)

val result = engine.executeTask(archTask)
```

### User Directive

```kotlin
// User explicitly requests both agents
val directive = UserDirective(
    originalText = "design and implement with both agents",
    forceConsensus = true
)

val result = engine.executeTask(task, directive)
```

## Monitoring Both Agents

### Health Check Loop

```kotlin
launch {
    while (isActive) {
        val claudeHealth = claude.healthCheck()
        val codexHealth = codex.healthCheck()
        
        println("Claude: ${if (claudeHealth.healthy) "✓" else "✗"} (${claudeHealth.latencyMs}ms)")
        println("Codex: ${if (codexHealth.healthy) "✓" else "✗"} (${codexHealth.latencyMs}ms)")
        
        if (!claudeHealth.healthy || !codexHealth.healthy) {
            // Alert or fallback logic
        }
        
        delay(60_000) // Every minute
    }
}
```

### Status Dashboard

```kotlin
fun getAgentStatus(): Map<String, Any> {
    return mapOf(
        "claude" to mapOf(
            "status" to claude.status,
            "capabilities" to claude.capabilities.size,
            "health" to runBlocking { claude.healthCheck().healthy }
        ),
        "codex" to mapOf(
            "status" to codex.status,
            "capabilities" to codex.capabilities.size,
            "health" to runBlocking { codex.healthCheck().healthy }
        )
    )
}
```

## Best Practices

### 1. Use Strengths Appropriately

```kotlin
// Good: Use Codex for architecture
codex.executeTask(taskId, "Design system architecture")

// Good: Use Claude for implementation
claude.executeTask(taskId, "Implement the designed system")

// Avoid: Using Claude for pure architecture
// Avoid: Using Codex for detailed implementation
```

### 2. Sequential for Complex Tasks

```kotlin
// Complex task: Break into plan + implement
if (task.complexity >= 7) {
    val plan = codex.executeTask(taskId, "Plan: ${task.description}")
    val impl = claude.executeTask(taskId, "Implement: $plan")
} else {
    // Simple task: Direct implementation
    claude.executeTask(taskId, task.description)
}
```

### 3. Consensus for Critical Decisions

```kotlin
// High risk: Get both opinions
if (task.risk >= 8) {
    val claudeProposal = claude.executeTask(taskId, task.description)
    val codexProposal = codex.executeTask(taskId, task.description)
    val decision = ConsensusModule.decide(taskId)
}
```

### 4. Fallback Strategy

```kotlin
suspend fun executeWithFallback(task: Task): String {
    val primary = routeTask(task)
    
    return try {
        primary.executeTask(task.id, task.description ?: "")
    } catch (e: Exception) {
        // Fallback to other agent
        val fallback = if (primary == claude) codex else claude
        fallback.executeTask(task.id, task.description ?: "")
    }
}
```

## Example: Complete Workflow

```kotlin
suspend fun completeFeatureWorkflow(feature: String): WorkflowResult {
    val taskId = TaskId("feature-${UUID.randomUUID()}")
    
    // 1. Codex: Architecture design
    println("Step 1: Designing architecture...")
    val architecture = codex.executeTask(
        taskId = taskId,
        description = "Design architecture for: $feature",
        context = mapOf("type" to "microservices")
    )
    
    // 2. Claude: Implementation
    println("Step 2: Implementing...")
    val implementation = claude.executeTask(
        taskId = taskId,
        description = "Implement based on architecture: $architecture"
    )
    
    // 3. Claude: Tests
    println("Step 3: Writing tests...")
    val tests = claude.executeTask(
        taskId = taskId,
        description = "Write tests for: $implementation"
    )
    
    // 4. Codex: Architecture review
    println("Step 4: Reviewing architecture...")
    val archReview = codex.executeTask(
        taskId = taskId,
        description = "Review architecture of: $implementation"
    )
    
    // 5. Claude: Code review
    println("Step 5: Reviewing code...")
    val codeReview = claude.executeTask(
        taskId = taskId,
        description = "Review code quality of: $implementation"
    )
    
    return WorkflowResult(
        taskId = taskId,
        status = TaskStatus.COMPLETED,
        output = implementation,
        artifacts = mapOf(
            "architecture" to architecture,
            "tests" to tests,
            "archReview" to archReview,
            "codeReview" to codeReview
        )
    )
}
```

## Troubleshooting

### One Agent Offline

```kotlin
val claudeHealthy = claude.healthCheck().healthy
val codexHealthy = codex.healthCheck().healthy

when {
    claudeHealthy && codexHealthy -> {
        // Both available: use optimal routing
        routeTask(task)
    }
    claudeHealthy && !codexHealthy -> {
        // Only Claude: use for all tasks
        claude.executeTask(taskId, description)
    }
    !claudeHealthy && codexHealthy -> {
        // Only Codex: use for all tasks
        codex.executeTask(taskId, description)
    }
    else -> {
        // Both offline: error
        throw RuntimeException("No agents available")
    }
}
```

### Different Latencies

```kotlin
val claudeLatency = claude.healthCheck().latencyMs
val codexLatency = codex.healthCheck().latencyMs

// Use faster agent for time-sensitive tasks
val fastAgent = if (claudeLatency < codexLatency) claude else codex
```

## Performance Tuning

### Parallel Execution

```kotlin
// Execute both in parallel for faster results
suspend fun fastConsensus(task: Task) = coroutineScope {
    val results = listOf(claude, codex).map { agent ->
        async {
            agent.executeTask(task.id, task.description ?: "")
        }
    }
    
    results.awaitAll()
}
```

### Timeout Configuration

```toml
# Fast agent for simple tasks
[agents.claude-fast]
type = "CLAUDE_CODE"
[agents.claude-fast.extra]
url = "http://localhost:3000/mcp"
timeout = "15"

# Slow agent for complex tasks
[agents.codex-deep]
type = "CODEX_CLI"
[agents.codex-deep.extra]
url = "http://localhost:3001/mcp"
timeout = "120"
```
