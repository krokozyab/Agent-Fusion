package com.orchestrator.core

import com.orchestrator.domain.Agent
import com.orchestrator.domain.AgentConfig

/**
 * Service Provider Interface (SPI) for creating Agent implementations.
 *
 * Implementations are intended to be discovered via Java's ServiceLoader.
 * To register an implementation, add the following provider file to your
 * classpath (typically under src/main/resources):
 *
 *   META-INF/services/com.orchestrator.core.AgentFactory
 *
 * Each line of that file should contain the fully qualified class name of an
 * AgentFactory implementation with a no-arg public constructor.
 *
 * Type names are compared in a case-insensitive manner by the registry, but
 * it is recommended to stick to upper snake case or kebab case consistently
 * (e.g. "GPT", "CLAUDE_CODE", "custom"). The chosen value must match what
 * the orchestrator uses to look up factories.
 */
interface AgentFactory {
    /**
     * A short type token that identifies the agent implementation this factory
     * can create (e.g., "GPT", "CLAUDE_CODE", "CUSTOM").
     *
     * This value is used as the lookup key in [AgentFactoryRegistry].
     */
    val supportedType: String

    /**
     * Create a new [Agent] instance configured with the provided [config].
     *
     * Implementations should validate the configuration and throw an
     * [IllegalArgumentException] with a descriptive message if something is
     * invalid or missing.
     */
    fun createAgent(config: AgentConfig): Agent
}
