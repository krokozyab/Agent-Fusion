package com.orchestrator.context.providers

/**
 * Thread-safe singleton registry for context providers.
 *
 * Uses Java ServiceLoader SPI to discover all ContextProvider implementations
 * from META-INF/services/com.orchestrator.context.providers.ContextProvider.
 * This provides zero-hardcoding plugin-style extensibility.
 */
object ContextProviderRegistry {

    private val lock = Any()
    @Volatile
    private var cachedProviders: Map<String, ContextProvider>? = null

    private fun ensureProviders(): Map<String, ContextProvider> {
        val current = cachedProviders
        if (current != null) return current

        synchronized(lock) {
            val refreshed = cachedProviders
            if (refreshed != null) return refreshed

            // Use ServiceLoader to discover all ContextProvider implementations
            val loader = java.util.ServiceLoader.load(ContextProvider::class.java)
            val discovered = loader.toList()

            cachedProviders = discovered.associateBy { it.id.lowercase() }
            return cachedProviders!!
        }
    }

    fun getProviderCount(): Int = ensureProviders().size

    fun getAllProviders(): List<ContextProvider> = ensureProviders().values.toList()

    fun getProvider(id: String?): ContextProvider? {
        if (id.isNullOrBlank()) return null
        return ensureProviders()[id.lowercase()]
    }

    fun getProvidersByType(type: ContextProviderType): List<ContextProvider> =
        ensureProviders().values.filter { it.type == type }

    fun getProviderIds(): Set<String> = ensureProviders().keys

    fun hasProvider(id: String?): Boolean = getProvider(id) != null
}
