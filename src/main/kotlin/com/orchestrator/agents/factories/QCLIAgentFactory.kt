package com.orchestrator.agents.factories

import com.orchestrator.agents.QCLIAgent
import com.orchestrator.core.AgentFactory
import com.orchestrator.domain.Agent
import com.orchestrator.domain.AgentConfig

/**
 * Factory for creating AWS Q CLI agent instances.
 *
 * Registered via SPI in META-INF/services/com.orchestrator.core.AgentFactory
 */
class QCLIAgentFactory : AgentFactory {

    override val supportedType: String = "Q_CLI"

    override fun createAgent(config: AgentConfig): Agent {
        return QCLIAgent.fromConfig(config)
    }
}
