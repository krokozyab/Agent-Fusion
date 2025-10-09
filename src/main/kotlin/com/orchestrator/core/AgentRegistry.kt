package com.orchestrator.core

import com.orchestrator.config.ConfigLoader
import com.orchestrator.domain.*
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe registry for managing active Agents.
 * - Loads agents from config (config/agents.toml by default)
 * - Efficient lookup by id and capability via internal indices
 * - Atomic status updates via per-agent AtomicReference
 * - Simple health check integration via pluggable HealthChecker
 */
class AgentRegistry private constructor(
    private val byId: ConcurrentHashMap<AgentId, AgentRecord>,
    private val byCapability: ConcurrentHashMap<Capability, CopyOnWriteArrayList<AgentRecord>>
) {
    /** Lightweight mutable record with atomic status */
    private data class AgentRecord(
        val agentImpl: AgentImpl,
        val statusRef: AtomicReference<AgentStatus>
    ) {
        fun snapshot(): AgentImpl = agentImpl.copy(status = statusRef.get())
    }

    /** Internal Agent implementation used within the registry */
    private data class AgentImpl(
        override val id: AgentId,
        override val type: AgentType,
        override val displayName: String,
        override val status: AgentStatus,
        override val capabilities: Set<Capability>,
        override val strengths: List<Strength>,
        override val config: AgentConfig?
    ) : Agent

    interface HealthChecker {
        fun check(agent: Agent): AgentStatus
    }

    fun getAgent(id: AgentId): Agent? = byId[id]?.snapshot()

    fun getAllAgents(): List<Agent> = byId.values.map { it.snapshot() }

    fun getAgentsByCapability(capability: Capability): List<Agent> =
        byCapability[capability]?.map { it.snapshot() } ?: emptyList()

    /**
     * Atomically update status for a given agent.
     * @return true if updated, false if agent not found.
     */
    fun updateStatus(id: AgentId, newStatus: AgentStatus): Boolean {
        val rec = byId[id] ?: return false
        rec.statusRef.set(newStatus)
        return true
    }

    /**
     * Runs health checks for all agents using provided checker and updates their status atomically.
     */
    fun runHealthChecks(checker: HealthChecker) {
        // Iterate on a stable snapshot of records to avoid concurrent modification issues
        val records = byId.values.toList()
        for (rec in records) {
            val current = rec.snapshot()
            val status = runCatching { checker.check(current) }.getOrDefault(AgentStatus.OFFLINE)
            rec.statusRef.set(status)
        }
    }

    companion object {
        /**
         * Create registry by loading agents from TOML config.
         * @param path optional path to config/agents.toml; defaults to config/agents.toml
         * @param env environment map for variable expansion
         */
        fun fromConfig(path: Path = Path.of("config/agents.toml"), env: Map<String, String> = System.getenv()): AgentRegistry {
            val defs = ConfigLoader.loadAgents(path, env)
            return build(defs)
        }

        /** Create an empty registry (mostly for tests). */
        fun empty(): AgentRegistry = AgentRegistry(ConcurrentHashMap(), ConcurrentHashMap())

        /**
         * Build registry from explicit definitions.
         */
        fun build(defs: List<ConfigLoader.AgentDefinition>): AgentRegistry {
            val byId = ConcurrentHashMap<AgentId, AgentRecord>()
            val byCapability = ConcurrentHashMap<Capability, CopyOnWriteArrayList<AgentRecord>>()

            // Provide conservative default capabilities for known LLM agent types
            val defaultCaps = setOf(
                Capability.CODE_GENERATION,
                Capability.CODE_REVIEW,
                Capability.TEST_WRITING,
                Capability.REFACTORING,
                Capability.ARCHITECTURE,
                Capability.DOCUMENTATION,
                Capability.DEBUGGING,
                Capability.PLANNING,
                Capability.DATA_ANALYSIS
            )

            for (d in defs) {
                val displayName = d.config.name ?: d.id.value
                val initialStatus = AgentStatus.ONLINE // assume online on startup; health checks can adjust
                val capabilities: Set<Capability> = when (d.type) {
                    AgentType.CUSTOM -> emptySet()
                    else -> defaultCaps
                }
                val strengths: List<Strength> = emptyList()

                val agentImpl = AgentImpl(
                    id = d.id,
                    type = d.type,
                    displayName = displayName,
                    status = initialStatus,
                    capabilities = capabilities,
                    strengths = strengths,
                    config = d.config
                )
                val record = AgentRecord(agentImpl, AtomicReference(initialStatus))
                byId[d.id] = record
                // index by capabilities
                for (cap in capabilities) {
                    byCapability.computeIfAbsent(cap) { CopyOnWriteArrayList() }.add(record)
                }
            }
            return AgentRegistry(byId, byCapability)
        }
    }
}
