# MCP Agent Implementation Guide

## Quick Start

### 1. Create Your Agent Class

```kotlin
import com.orchestrator.agents.*
import com.orchestrator.domain.*

class MyAgent(
    connection: McpConnection,
    config: AgentConfig? = null
) : McpAgent(connection, config) {
    
    // Required: Agent identity
    override val id = AgentId("my-agent")
    override val type = AgentType.CUSTOM
    override val displayName = "My Custom Agent"
    
    // Required: Capabilities
    override val capabilities = setOf(
        Capability.CODE_GENERATION,
        Capability.CODE_REVIEW
    )
    
    // Optional: Strengths
    override val strengths = listOf(
        Strength(Capability.CODE_GENERATION, 85),
        Strength(Capability.CODE_REVIEW, 90)
    )
    
    // Required: MCP protocol implementation
    override suspend fun sendMcpRequest(
        method: String,
        params: Map<String, Any>
    ): Map<String, Any> {
        // TODO: Implement your MCP communication
        return mapOf("result" to "success")
    }
}
```

### 2. Configure Connection

```kotlin
val connection = McpConnection(
    url = "http://localhost:3000/mcp",
    timeout = Duration.ofSeconds(30),
    retryAttempts = 3,
    retryDelay = Duration.ofMillis(500)
)
```

### 3. Create and Use Agent

```kotlin
val agent = MyAgent(connection)

// Health check
val health = agent.healthCheck()
println("Healthy: ${health.healthy}, Latency: ${health.latencyMs}ms")

// Execute task
val result = agent.executeTask(
    taskId = TaskId("task-1"),
    description = "Write a function",
    context = mapOf("language" to "kotlin")
)
```

## MCP Protocol Implementation

### Basic HTTP JSON-RPC

```kotlin
override suspend fun sendMcpRequest(
    method: String,
    params: Map<String, Any>
): Map<String, Any> = withContext(Dispatchers.IO) {
    val url = URL(connection.url)
    val conn = url.openConnection() as HttpURLConnection
    
    conn.requestMethod = "POST"
    conn.setRequestProperty("Content-Type", "application/json")
    conn.doOutput = true
    conn.connectTimeout = connection.timeout.toMillis().toInt()
    
    // Send request
    val request = mapOf(
        "jsonrpc" to "2.0",
        "method" to method,
        "params" to params,
        "id" to UUID.randomUUID().toString()
    )
    
    conn.outputStream.use { os ->
        os.write(Json.encodeToString(request).toByteArray())
    }
    
    // Read response
    val response = conn.inputStream.bufferedReader().use { it.readText() }
    conn.disconnect()
    
    Json.decodeFromString<Map<String, Any>>(response)
}
```

### Using Ktor Client

```kotlin
private val httpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json()
    }
    install(HttpTimeout) {
        requestTimeoutMillis = connection.timeout.toMillis()
    }
}

override suspend fun sendMcpRequest(
    method: String,
    params: Map<String, Any>
): Map<String, Any> {
    val response: HttpResponse = httpClient.post(connection.url) {
        contentType(ContentType.Application.Json)
        setBody(mapOf(
            "jsonrpc" to "2.0",
            "method" to method,
            "params" to params,
            "id" to UUID.randomUUID().toString()
        ))
    }
    
    return response.body()
}
```

## Status Management

### Automatic Status Updates

```kotlin
// executeTask automatically manages status:
// 1. Sets BUSY before execution
// 2. Sets ONLINE on success
// 3. Sets OFFLINE on failure

val result = agent.executeTask(taskId, description)
```

### Manual Status Updates

```kotlin
class MyAgent(...) : McpAgent(...) {
    
    suspend fun customOperation() {
        updateStatus(AgentStatus.BUSY)
        try {
            // Do work
            updateStatus(AgentStatus.ONLINE)
        } catch (e: Exception) {
            updateStatus(AgentStatus.OFFLINE)
            throw e
        }
    }
}
```

## Health Checks

### Basic Health Check

```kotlin
val health = agent.healthCheck()

if (health.healthy) {
    println("Agent is online (${health.latencyMs}ms)")
} else {
    println("Agent is offline: ${health.error}")
}
```

### Periodic Health Checks

```kotlin
launch {
    while (isActive) {
        agent.healthCheck()
        delay(Duration.ofMinutes(1).toMillis())
    }
}
```

### Custom Health Check

```kotlin
class MyAgent(...) : McpAgent(...) {
    
    suspend fun detailedHealthCheck(): Map<String, Any> {
        val basic = healthCheck()
        val info = getInfo()
        
        return mapOf(
            "healthy" to basic.healthy,
            "latency" to basic.latencyMs,
            "version" to info["version"],
            "capabilities" to capabilities.map { it.name }
        )
    }
}
```

## Retry Logic

### Using Built-in Retry

```kotlin
class MyAgent(...) : McpAgent(...) {
    
    suspend fun customRequest(): String {
        return sendWithRetry {
            // This will retry automatically on failure
            val response = sendMcpRequest("custom_method", emptyMap())
            response["data"]?.toString() ?: ""
        }
    }
}
```

### Custom Retry Configuration

```kotlin
// Per-agent retry settings
val connection = McpConnection(
    url = "http://localhost:3000/mcp",
    retryAttempts = 5,  // More retries
    retryDelay = Duration.ofSeconds(1)  // Longer delay
)
```

## Error Handling

### Handling Task Failures

```kotlin
try {
    val result = agent.executeTask(taskId, description)
    println("Success: $result")
} catch (e: Exception) {
    println("Task failed: ${e.message}")
    // Agent status is now OFFLINE
}
```

### Graceful Degradation

```kotlin
val result = try {
    agent.executeTask(taskId, description)
} catch (e: Exception) {
    // Fallback to another agent or default behavior
    "Task could not be completed"
}
```

## Testing Your Agent

```kotlin
class MyAgentTest {
    
    @Test
    fun `agent executes task successfully`() = runBlocking {
        val connection = McpConnection("http://localhost:3000")
        val agent = MyAgent(connection)
        
        val result = agent.executeTask(
            TaskId("test-1"),
            "Test task"
        )
        
        assertNotNull(result)
    }
    
    @Test
    fun `agent handles connection failure`() = runBlocking {
        val connection = McpConnection(
            url = "http://invalid:9999",
            retryAttempts = 0
        )
        val agent = MyAgent(connection)
        
        assertThrows<Exception> {
            runBlocking {
                agent.executeTask(TaskId("test-1"), "Test")
            }
        }
        
        assertEquals(AgentStatus.OFFLINE, agent.status)
    }
}
```

## Best Practices

### 1. Connection Configuration
- Use longer timeouts for remote agents
- Increase retries for unreliable networks
- Adjust delays based on expected latency

### 2. Status Management
- Let executeTask manage status automatically
- Only use updateStatus() for custom operations
- Always set OFFLINE on unrecoverable errors

### 3. Error Handling
- Let exceptions propagate for caller to handle
- Use sendWithRetry() for transient failures
- Log errors for debugging

### 4. Health Checks
- Run periodically (every 1-5 minutes)
- Use results to update routing decisions
- Monitor latency trends

### 5. Testing
- Mock MCP responses for unit tests
- Test connection failures
- Verify status transitions
- Test retry logic

## Common Patterns

### Agent with Authentication

```kotlin
class AuthenticatedAgent(
    connection: McpConnection,
    private val apiKey: String
) : McpAgent(connection) {
    
    override suspend fun sendMcpRequest(
        method: String,
        params: Map<String, Any>
    ): Map<String, Any> {
        val authParams = params + mapOf("apiKey" to apiKey)
        // Send with auth
    }
}
```

### Agent with Rate Limiting

```kotlin
class RateLimitedAgent(
    connection: McpConnection
) : McpAgent(connection) {
    
    private val rateLimiter = Semaphore(5) // 5 concurrent requests
    
    override suspend fun sendMcpRequest(
        method: String,
        params: Map<String, Any>
    ): Map<String, Any> {
        rateLimiter.withPermit {
            // Send request
        }
    }
}
```

### Agent with Caching

```kotlin
class CachedAgent(
    connection: McpConnection
) : McpAgent(connection) {
    
    private val cache = ConcurrentHashMap<String, Map<String, Any>>()
    
    override suspend fun getInfo(): Map<String, Any> {
        return cache.getOrPut("info") {
            super.getInfo()
        }
    }
}
```
