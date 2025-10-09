package com.orchestrator.agents

import com.orchestrator.domain.*
import java.time.Duration

/**
 * Placeholder for DeepSeek Coder agent implementation.
 * 
 * TODO: Implement when DeepSeek Coder MCP support is available or via direct API.
 * 
 * Planned capabilities:
 * - Specialized for code generation
 * - High code quality
 * - Multiple programming languages
 * - Code completion
 * - Refactoring
 * 
 * Configuration example:
 * ```toml
 * [agents.deepseek-coder]
 * type = "CUSTOM"  # Use CUSTOM until AgentType.DEEPSEEK_CODER is added
 * name = "DeepSeek Coder"
 * model = "deepseek-coder-33b"
 * 
 * [agents.deepseek-coder.extra]
 * url = "http://localhost:3004/mcp"  # If using MCP
 * # OR
 * apiKey = "${DEEPSEEK_API_KEY}"     # If using direct API
 * ```
 * 
 * @see McpAgent
 */
class DeepSeekCoderAgent(
    connection: McpConnection,
    config: AgentConfig? = null
) : McpAgent(connection, config) {
    
    override val id = AgentId(config?.name ?: "deepseek-coder")
    override val type = AgentType.CUSTOM // TODO: Add AgentType.DEEPSEEK_CODER
    override val displayName = config?.name ?: "DeepSeek Coder"
    
    override val capabilities = setOf(
        Capability.CODE_GENERATION,
        Capability.REFACTORING,
        Capability.CODE_REVIEW,
        Capability.DEBUGGING,
        Capability.TEST_WRITING
    )
    
    override val strengths = listOf(
        Strength(Capability.CODE_GENERATION, 93),
        Strength(Capability.REFACTORING, 90),
        Strength(Capability.CODE_REVIEW, 88),
        Strength(Capability.DEBUGGING, 86),
        Strength(Capability.TEST_WRITING, 85)
    )
    
    override suspend fun sendMcpRequest(
        method: String,
        params: Map<String, Any>
    ): Map<String, Any> {
        // TODO: Implement DeepSeek Coder-specific MCP protocol or direct API calls
        throw NotImplementedError("DeepSeek Coder agent not yet implemented. Enable when ready.")
    }
    
    companion object {
        fun fromConfig(config: AgentConfig): DeepSeekCoderAgent {
            val url = config.extra["url"] 
                ?: throw IllegalArgumentException("DeepSeek Coder agent requires 'url' in config.extra")
            
            val timeout = config.extra["timeout"]?.toLongOrNull()?.let { Duration.ofSeconds(it) }
                ?: Duration.ofSeconds(30)
            
            val connection = McpConnection(url = url, timeout = timeout)
            return DeepSeekCoderAgent(connection, config)
        }
    }
}
