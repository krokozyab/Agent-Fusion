package com.orchestrator.modules.context

import com.orchestrator.domain.Agent
import com.orchestrator.domain.AgentId

/**
 * Minimal abstraction for looking up agent metadata required by the context subsystem.
 */
fun interface AgentDirectory {
    fun getAgent(id: AgentId): Agent?
}
