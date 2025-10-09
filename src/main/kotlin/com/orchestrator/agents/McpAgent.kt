package com.orchestrator.agents

import com.orchestrator.domain.*
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant

/**
 * MCP connection configuration.
 */
data class McpConnection(
    val url: String,
    val timeout: Duration = Duration.ofSeconds(30),
    val retryAttempts: Int = 3,
    val retryDelay: Duration = Duration.ofMillis(500)
) {
    init {
        require(url.isNotBlank()) { "MCP URL cannot be blank" }
        require(timeout.toMillis() > 0) { "Timeout must be positive" }
        require(retryAttempts >= 0) { "Retry attempts must be non-negative" }
    }
}

/**
 * Health check result for MCP agents.
 */
data class HealthCheckResult(
    val healthy: Boolean,
    val latencyMs: Long,
    val timestamp: Instant = Instant.now(),
    val error: String? = null
)

/**
 * Abstract base class for MCP-based agents.
 * 
 * Provides:
 * - MCP connection management
 * - Health check implementation
 * - Common agent logic
 * - Retry handling
 * 
 * Subclasses must implement:
 * - Agent interface properties (id, type, displayName, etc.)
 * - sendMcpRequest() for actual MCP communication
 */
abstract class McpAgent(
    protected val connection: McpConnection,
    override val config: AgentConfig? = null
) : Agent {
    
    @Volatile
    private var _status: AgentStatus = AgentStatus.OFFLINE
    
    override val status: AgentStatus
        get() = _status
    
    /**
     * Update agent status.
     */
    protected fun updateStatus(newStatus: AgentStatus) {
        _status = newStatus
    }
    
    /**
     * Perform health check by pinging the MCP endpoint.
     * Returns true if agent is reachable and responsive.
     */
    suspend fun healthCheck(): HealthCheckResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        
        try {
            val url = URL(connection.url)
            val conn = url.openConnection() as HttpURLConnection
            
            conn.requestMethod = "GET"
            conn.connectTimeout = connection.timeout.toMillis().toInt()
            conn.readTimeout = connection.timeout.toMillis().toInt()
            
            val responseCode = conn.responseCode
            val latency = System.currentTimeMillis() - start
            
            conn.disconnect()
            
            val healthy = responseCode in 200..299
            updateStatus(if (healthy) AgentStatus.ONLINE else AgentStatus.OFFLINE)
            
            HealthCheckResult(
                healthy = healthy,
                latencyMs = latency,
                error = if (!healthy) "HTTP $responseCode" else null
            )
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - start
            updateStatus(AgentStatus.OFFLINE)
            
            HealthCheckResult(
                healthy = false,
                latencyMs = latency,
                error = e.message ?: e.toString()
            )
        }
    }
    
    /**
     * Send an MCP request with retry logic.
     * Subclasses implement the actual MCP protocol communication.
     */
    protected suspend fun <T> sendWithRetry(block: suspend () -> T): T {
        var lastError: Throwable? = null
        
        repeat(connection.retryAttempts + 1) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastError = e
                if (attempt < connection.retryAttempts) {
                    delay(connection.retryDelay.toMillis() * (attempt + 1))
                }
            }
        }
        
        throw lastError ?: RuntimeException("Request failed after ${connection.retryAttempts} retries")
    }
    
    /**
     * Send an MCP request. Subclasses implement protocol-specific logic.
     */
    protected abstract suspend fun sendMcpRequest(
        method: String,
        params: Map<String, Any>
    ): Map<String, Any>
    
    /**
     * Execute a task using this agent via MCP.
     * Default implementation sends a generic request.
     */
    open suspend fun executeTask(
        taskId: TaskId,
        description: String,
        context: Map<String, Any> = emptyMap()
    ): String {
        updateStatus(AgentStatus.BUSY)
        
        return try {
            val params = mapOf(
                "taskId" to taskId.value,
                "description" to description,
                "context" to context
            )
            
            val response = sendWithRetry {
                sendMcpRequest("execute_task", params)
            }
            
            updateStatus(AgentStatus.ONLINE)
            response["result"]?.toString() ?: ""
        } catch (e: Exception) {
            updateStatus(AgentStatus.OFFLINE)
            throw e
        }
    }
    
    /**
     * Get agent information via MCP.
     */
    open suspend fun getInfo(): Map<String, Any> {
        return sendWithRetry {
            sendMcpRequest("get_info", emptyMap())
        }
    }
    
    override fun toString(): String {
        return "McpAgent(id=$id, type=$type, status=$status, url=${connection.url})"
    }
}
