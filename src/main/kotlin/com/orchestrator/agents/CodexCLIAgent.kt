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
 * Codex CLI agent implementation using MCP protocol.
 * 
 * Capabilities:
 * - Architecture design
 * - Planning
 * - Code review
 * - Data analysis
 * - Documentation
 */
class CodexCLIAgent(
    connection: McpConnection,
    config: AgentConfig? = null
) : McpAgent(connection, config) {
    
    override val id = AgentId(config?.name ?: "codex-cli")
    override val type = AgentType.CODEX_CLI
    override val displayName = config?.name ?: "Codex CLI"
    
    override val capabilities = setOf(
        Capability.ARCHITECTURE,
        Capability.PLANNING,
        Capability.CODE_REVIEW,
        Capability.DATA_ANALYSIS,
        Capability.DOCUMENTATION,
        Capability.CODE_GENERATION,
        Capability.DEBUGGING
    )
    
    override val strengths = listOf(
        Strength(Capability.ARCHITECTURE, 95),
        Strength(Capability.PLANNING, 93),
        Strength(Capability.CODE_REVIEW, 88),
        Strength(Capability.DATA_ANALYSIS, 90),
        Strength(Capability.DOCUMENTATION, 87),
        Strength(Capability.CODE_GENERATION, 85),
        Strength(Capability.DEBUGGING, 82)
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
         * Create CodexCLIAgent from configuration.
         */
        fun fromConfig(config: AgentConfig): CodexCLIAgent {
            val url = config.extra["url"] 
                ?: throw IllegalArgumentException("Codex CLI agent requires 'url' in config.extra")
            
            val timeout = config.extra["timeout"]?.toLongOrNull()?.let { Duration.ofSeconds(it) }
                ?: Duration.ofSeconds(30)
            
            val retryAttempts = config.extra["retryAttempts"]?.toIntOrNull() ?: 3
            
            val connection = McpConnection(
                url = url,
                timeout = timeout,
                retryAttempts = retryAttempts
            )
            
            return CodexCLIAgent(connection, config)
        }
    }
}
