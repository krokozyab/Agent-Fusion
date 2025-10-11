package com.orchestrator.domain

/** Unique identifier for an Agent */
data class AgentId(val value: String) {
    init {
        require(value.isNotBlank()) { "AgentId cannot be blank" }
    }
    override fun toString(): String = value
}

/** Type/category of the Agent implementation */
enum class AgentType {
    CLAUDE_CODE,
    CODEX_CLI,
    Q_CLI,
    GEMINI,
    GPT,
    MISTRAL,
    LLAMA,
    CUSTOM
}

/** Current availability status of the Agent */
enum class AgentStatus {
    ONLINE,
    OFFLINE,
    BUSY
}

/**
 * Contract for agent implementations.
 */
interface Agent {
    val id: AgentId
    val type: AgentType
    val displayName: String
    val status: AgentStatus
    val capabilities: Set<Capability>
    val strengths: List<Strength>
    val config: AgentConfig?
}
