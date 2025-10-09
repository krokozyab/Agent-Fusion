package com.orchestrator.agents.factories

import com.orchestrator.agents.CodexCLIAgent
import com.orchestrator.core.AgentFactory
import com.orchestrator.domain.Agent
import com.orchestrator.domain.AgentConfig

/**
 * Factory for creating Codex CLI agent instances.
 * 
 * Registered via SPI in META-INF/services/com.orchestrator.core.AgentFactory
 */
class CodexCLIAgentFactory : AgentFactory {
    
    override val supportedType: String = "CODEX_CLI"
    
    override fun createAgent(config: AgentConfig): Agent {
        return CodexCLIAgent.fromConfig(config)
    }
}
