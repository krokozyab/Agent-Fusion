package com.orchestrator.agents.factories

import com.orchestrator.agents.ClaudeCodeAgent
import com.orchestrator.core.AgentFactory
import com.orchestrator.domain.Agent
import com.orchestrator.domain.AgentConfig

/**
 * Factory for creating Claude Code agent instances.
 * 
 * Registered via SPI in META-INF/services/com.orchestrator.core.AgentFactory
 */
class ClaudeCodeAgentFactory : AgentFactory {
    
    override val supportedType: String = "CLAUDE_CODE"
    
    override fun createAgent(config: AgentConfig): Agent {
        return ClaudeCodeAgent.fromConfig(config)
    }
}
