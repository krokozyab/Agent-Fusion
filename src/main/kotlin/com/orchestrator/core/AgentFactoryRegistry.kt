package com.orchestrator.core

import com.orchestrator.domain.Agent
import com.orchestrator.domain.AgentConfig
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for [AgentFactory] implementations.
 *
 * Features:
 * - Manual registration via [register] / [registerAll]
 * - Discovery via Java's [ServiceLoader]
 * - Fast lookup by (case-insensitive) type token
 *
 * The "type" used here is an arbitrary string token defined by each factory's
 * [AgentFactory.supportedType]. The registry normalizes keys using lower-case
 * with US locale semantics to ensure case-insensitive matching.
 */
class AgentFactoryRegistry private constructor(
    private val byType: ConcurrentHashMap<String, AgentFactory>
) {

    /** Register a single factory. If a factory with the same type exists, it will be replaced. */
    fun register(factory: AgentFactory) {
        byType[normalize(factory.supportedType)] = factory
    }

    /** Register multiple factories. Later entries override earlier ones on type conflict. */
    fun registerAll(factories: Iterable<AgentFactory>) {
        for (f in factories) register(f)
    }

    /** Returns the factory for the given type token, or null if none registered. */
    fun getFactory(type: String): AgentFactory? = byType[normalize(type)]

    /** Returns a snapshot of all available type tokens. */
    fun availableTypes(): Set<String> = byType.values.mapTo(LinkedHashSet()) { it.supportedType }

    /** Convenience helper that creates an [Agent] by type using the matching factory, if present. */
    fun create(type: String, config: AgentConfig): Agent? = getFactory(type)?.createAgent(config)

    companion object {
        /** Create an empty registry. */
        fun empty(): AgentFactoryRegistry = AgentFactoryRegistry(ConcurrentHashMap())

        /**
         * Build a registry and eagerly load factories via [ServiceLoader].
         *
         * For this to work, provider resources must exist on the classpath at:
         *   META-INF/services/com.orchestrator.core.AgentFactory
         */
        fun fromServiceLoader(classLoader: ClassLoader = Thread.currentThread().contextClassLoader ?: AgentFactoryRegistry::class.java.classLoader): AgentFactoryRegistry {
            val reg = empty()
            reg.loadFromServiceLoader(classLoader)
            return reg
        }
    }

    /** Load factories via [ServiceLoader] into this registry (can be called multiple times). */
    fun loadFromServiceLoader(classLoader: ClassLoader = Thread.currentThread().contextClassLoader ?: AgentFactoryRegistry::class.java.classLoader): AgentFactoryRegistry {
        val loader: ServiceLoader<AgentFactory> = ServiceLoader.load(AgentFactory::class.java, classLoader)
        loader.forEach { register(it) }
        return this
    }

    private fun normalize(type: String): String = type.trim().lowercase()
}
