package com.orchestrator.agents

import com.orchestrator.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.util.UUID

/**
 * Claude Code agent implementation using MCP protocol.
 * 
 * Capabilities:
 * - Code generation
 * - Code review
 * - Refactoring
 * - Test writing
 * - Documentation
 * - Debugging
 */
class ClaudeCodeAgent(
    connection: McpConnection,
    config: AgentConfig? = null
) : McpAgent(connection, config) {
    
    override val id = AgentId(config?.name ?: "claude-code")
    override val type = AgentType.CLAUDE_CODE
    override val displayName = config?.name ?: "Claude Code"
    
    override val capabilities = setOf(
        Capability.CODE_GENERATION,
        Capability.CODE_REVIEW,
        Capability.REFACTORING,
        Capability.TEST_WRITING,
        Capability.DOCUMENTATION,
        Capability.DEBUGGING,
        Capability.ARCHITECTURE
    )
    
    override val strengths = listOf(
        Strength(Capability.CODE_GENERATION, 95),
        Strength(Capability.CODE_REVIEW, 90),
        Strength(Capability.REFACTORING, 92),
        Strength(Capability.TEST_WRITING, 88),
        Strength(Capability.DOCUMENTATION, 85),
        Strength(Capability.DEBUGGING, 87),
        Strength(Capability.ARCHITECTURE, 90)
    )
    
    override suspend fun sendMcpRequest(
        method: String,
        params: Map<String, Any>
    ): Map<String, Any> = withContext(Dispatchers.IO) {
        val url = URL(connection.url)
        val conn = url.openConnection() as HttpURLConnection
        
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = connection.timeout.toMillis().toInt()
            conn.readTimeout = connection.timeout.toMillis().toInt()
            
            // Build JSON-RPC request
            val request = buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", buildJsonObject {
                    params.forEach { (key, value) ->
                        put(key, JsonPrimitive(value.toString()))
                    }
                })
                put("id", UUID.randomUUID().toString())
            }
            
            // Send request
            conn.outputStream.use { os ->
                os.write(request.toString().toByteArray())
            }
            
            // Read response
            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                throw RuntimeException("HTTP $responseCode")
            }
            
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val json = Json.parseToJsonElement(response).jsonObject
            
            // Extract result
            val result = json["result"]?.jsonObject ?: buildJsonObject {
                put("result", json["result"] ?: JsonPrimitive(""))
            }
            
            result.mapValues { (_, value) ->
                when (value) {
                    is JsonPrimitive -> value.content
                    else -> value.toString()
                }
            }
        } finally {
            conn.disconnect()
        }
    }
    
    companion object {
        /**
         * Create ClaudeCodeAgent from configuration.
         */
        fun fromConfig(config: AgentConfig): ClaudeCodeAgent {
            val url = config.extra["url"] 
                ?: throw IllegalArgumentException("Claude Code agent requires 'url' in config.extra")
            
            val timeout = config.extra["timeout"]?.toLongOrNull()?.let { Duration.ofSeconds(it) }
                ?: Duration.ofSeconds(30)
            
            val retryAttempts = config.extra["retryAttempts"]?.toIntOrNull() ?: 3
            
            val connection = McpConnection(
                url = url,
                timeout = timeout,
                retryAttempts = retryAttempts
            )
            
            return ClaudeCodeAgent(connection, config)
        }
    }
}
