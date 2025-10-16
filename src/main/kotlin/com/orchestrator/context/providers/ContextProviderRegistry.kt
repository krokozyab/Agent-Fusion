package com.orchestrator.context.providers

/**
 * Thread-safe singleton registry for context providers.
 *
 * The registry currently wires providers explicitly instead of relying on a ServiceLoader to keep
 * the reference implementation lightweight for tests. The public API mirrors the intended SPI so
 * the implementation can be swapped without impacting callers.
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

            val semantic = SemanticContextProvider()
            val symbol = SymbolContextProvider()
            val fullText = FullTextContextProvider()
            val gitHistory = GitHistoryContextProvider()
            val hybrid = HybridContextProvider(listOf(semantic, symbol, fullText))

            val ordered = listOf(semantic, symbol, fullText, gitHistory, hybrid)
            cachedProviders = ordered.associateBy { it.id.lowercase() }
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
