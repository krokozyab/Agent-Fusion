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
 * AWS Q CLI agent implementation using MCP protocol.
 *
 * Capabilities:
 * - Code generation
 * - Code review
 * - Debugging
 * - Documentation
 * - Testing
 */
class QCLIAgent(
    connection: McpConnection,
    config: AgentConfig? = null
) : McpAgent(connection, config) {

    override val id = AgentId(config?.name ?: "q-cli")
    override val type = AgentType.Q_CLI
    override val displayName = config?.name ?: "AWS Q CLI"

    override val capabilities = setOf(
        Capability.CODE_GENERATION,
        Capability.CODE_REVIEW,
        Capability.DEBUGGING,
        Capability.DOCUMENTATION,
        Capability.TEST_WRITING,
        Capability.REFACTORING
    )

    override val strengths = listOf(
        Strength(Capability.CODE_GENERATION, 88),
        Strength(Capability.CODE_REVIEW, 85),
        Strength(Capability.DEBUGGING, 87),
        Strength(Capability.DOCUMENTATION, 82),
        Strength(Capability.TEST_WRITING, 83),
        Strength(Capability.REFACTORING, 84)
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
         * Create QCLIAgent from configuration.
         */
        fun fromConfig(config: AgentConfig): QCLIAgent {
            val url = config.extra["url"]
                ?: throw IllegalArgumentException("Q CLI agent requires 'url' in config.extra")

            val timeout = config.extra["timeout"]?.toLongOrNull()?.let { Duration.ofSeconds(it) }
                ?: Duration.ofSeconds(30)

            val retryAttempts = config.extra["retryAttempts"]?.toIntOrNull() ?: 3

            val connection = McpConnection(
                url = url,
                timeout = timeout,
                retryAttempts = retryAttempts
            )

            return QCLIAgent(connection, config)
        }
    }
}
