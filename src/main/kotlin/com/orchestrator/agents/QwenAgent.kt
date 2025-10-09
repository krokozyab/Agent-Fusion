package com.orchestrator.agents

import com.orchestrator.domain.*
import java.time.Duration

/**
 * Placeholder for Alibaba Qwen agent implementation.
 * 
 * TODO: Implement when Qwen MCP support is available or via direct API.
 * 
 * Planned capabilities:
 * - Cost-effective alternative
 * - Multilingual support (especially Chinese)
 * - Code generation
 * - Long context
 * - Fast inference
 * 
 * Configuration example:
 * ```toml
 * [agents.qwen]
 * type = "CUSTOM"  # Use CUSTOM until AgentType.QWEN is added
 * name = "Qwen"
 * model = "qwen-max"
 * 
 * [agents.qwen.extra]
 * url = "http://localhost:3003/mcp"  # If using MCP
 * # OR
 * apiKey = "${QWEN_API_KEY}"         # If using direct API
 * ```
 * 
 * @see McpAgent
 */
class QwenAgent(
    connection: McpConnection,
    config: AgentConfig? = null
) : McpAgent(connection, config) {
    
    override val id = AgentId(config?.name ?: "qwen")
    override val type = AgentType.CUSTOM // TODO: Add AgentType.QWEN
    override val displayName = config?.name ?: "Qwen"
    
    override val capabilities = setOf(
        Capability.CODE_GENERATION,
        Capability.CODE_REVIEW,
        Capability.DOCUMENTATION,
        Capability.DATA_ANALYSIS
    )
    
    override val strengths = listOf(
        Strength(Capability.CODE_GENERATION, 85),
        Strength(Capability.CODE_REVIEW, 82),
        Strength(Capability.DOCUMENTATION, 88),
        Strength(Capability.DATA_ANALYSIS, 86)
    )
    
    override suspend fun sendMcpRequest(
        method: String,
        params: Map<String, Any>
    ): Map<String, Any> {
        // TODO: Implement Qwen-specific MCP protocol or direct API calls
        throw NotImplementedError("Qwen agent not yet implemented. Enable when ready.")
    }
    
    companion object {
        fun fromConfig(config: AgentConfig): QwenAgent {
            val url = config.extra["url"] 
                ?: throw IllegalArgumentException("Qwen agent requires 'url' in config.extra")
            
            val timeout = config.extra["timeout"]?.toLongOrNull()?.let { Duration.ofSeconds(it) }
                ?: Duration.ofSeconds(30)
            
            val connection = McpConnection(url = url, timeout = timeout)
            return QwenAgent(connection, config)
        }
    }
}
