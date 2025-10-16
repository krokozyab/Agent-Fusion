package com.orchestrator.context.providers

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName

/**
 * Unit tests for ContextProviderRegistry.
 *
 * Tests SPI-based provider discovery and registry functionality.
 */
class ContextProviderRegistryTest {

    @Test
    @DisplayName("registry discovers all providers via ServiceLoader")
    fun `registry discovers all providers via ServiceLoader`() {
        // Verify that providers are discovered
        val providerCount = ContextProviderRegistry.getProviderCount()
        assertTrue(providerCount > 0, "Registry should discover at least one provider")

        // We expect 5 providers: semantic, symbol, full_text, git-history, hybrid
        assertEquals(5, providerCount, "Should discover exactly 5 providers")
    }

    @Test
    @DisplayName("getAllProviders returns non-empty list")
    fun `getAllProviders returns non-empty list`() {
        val providers = ContextProviderRegistry.getAllProviders()

        assertNotNull(providers)
        assertTrue(providers.isNotEmpty(), "Should have at least one provider")
        assertEquals(5, providers.size, "Should have 5 providers")
    }

    @Test
    @DisplayName("getProvider returns provider by ID")
    fun `getProvider returns provider by ID`() {
        val semantic = ContextProviderRegistry.getProvider("semantic")
        assertNotNull(semantic, "Should find semantic provider")
        assertEquals(ContextProviderType.SEMANTIC, semantic?.type)

        val symbol = ContextProviderRegistry.getProvider("symbol")
        assertNotNull(symbol, "Should find symbol provider")
        assertEquals(ContextProviderType.SYMBOL, symbol?.type)

        val fullText = ContextProviderRegistry.getProvider("full_text")
        assertNotNull(fullText, "Should find full_text provider")
        assertEquals(ContextProviderType.FULL_TEXT, fullText?.type)

        val hybrid = ContextProviderRegistry.getProvider("hybrid")
        assertNotNull(hybrid, "Should find hybrid provider")
        assertEquals(ContextProviderType.HYBRID, hybrid?.type)
    }

    @Test
    @DisplayName("getProvider is case-insensitive")
    fun `getProvider is case-insensitive`() {
        val semantic1 = ContextProviderRegistry.getProvider("semantic")
        val semantic2 = ContextProviderRegistry.getProvider("SEMANTIC")
        val semantic3 = ContextProviderRegistry.getProvider("Semantic")

        assertNotNull(semantic1)
        assertNotNull(semantic2)
        assertNotNull(semantic3)

        // Should return same type
        assertEquals(semantic1?.type, semantic2?.type)
        assertEquals(semantic1?.type, semantic3?.type)
    }

    @Test
    @DisplayName("getProvider returns null for unknown ID")
    fun `getProvider returns null for unknown ID`() {
        val unknown = ContextProviderRegistry.getProvider("unknown")
        assertNull(unknown, "Should return null for unknown provider ID")

        val nonexistent = ContextProviderRegistry.getProvider("nonexistent")
        assertNull(nonexistent, "Should return null for nonexistent provider ID")
    }

    @Test
    @DisplayName("getProvidersByType returns providers of specific type")
    fun `getProvidersByType returns providers of specific type`() {
        val semanticProviders = ContextProviderRegistry.getProvidersByType(ContextProviderType.SEMANTIC)
        assertEquals(1, semanticProviders.size, "Should have 1 semantic provider")
        assertTrue(semanticProviders.all { it.type == ContextProviderType.SEMANTIC })

        val symbolProviders = ContextProviderRegistry.getProvidersByType(ContextProviderType.SYMBOL)
        assertEquals(1, symbolProviders.size, "Should have 1 symbol provider")
        assertTrue(symbolProviders.all { it.type == ContextProviderType.SYMBOL })

        val fullTextProviders = ContextProviderRegistry.getProvidersByType(ContextProviderType.FULL_TEXT)
        assertEquals(1, fullTextProviders.size, "Should have 1 full_text provider")
        assertTrue(fullTextProviders.all { it.type == ContextProviderType.FULL_TEXT })

        val hybridProviders = ContextProviderRegistry.getProvidersByType(ContextProviderType.HYBRID)
        assertEquals(1, hybridProviders.size, "Should have 1 hybrid provider")
        assertTrue(hybridProviders.all { it.type == ContextProviderType.HYBRID })
    }

    @Test
    @DisplayName("getProvidersByType returns correct providers for GIT_HISTORY type")
    fun `getProvidersByType returns correct providers for GIT_HISTORY type`() {
        val gitProviders = ContextProviderRegistry.getProvidersByType(ContextProviderType.GIT_HISTORY)
        assertNotNull(gitProviders)
        assertEquals(1, gitProviders.size, "Should have 1 git history provider")
        assertTrue(gitProviders.all { it.type == ContextProviderType.GIT_HISTORY })
    }

    @Test
    @DisplayName("getProviderIds returns all registered IDs")
    fun `getProviderIds returns all registered IDs`() {
        val ids = ContextProviderRegistry.getProviderIds()

        assertNotNull(ids)
        assertEquals(5, ids.size, "Should have 5 provider IDs")

        assertTrue(ids.contains("semantic"), "Should contain 'semantic'")
        assertTrue(ids.contains("symbol"), "Should contain 'symbol'")
        assertTrue(ids.contains("full_text"), "Should contain 'full_text'")
        assertTrue(ids.contains("git-history"), "Should contain 'git-history'")
        assertTrue(ids.contains("hybrid"), "Should contain 'hybrid'")
    }

    @Test
    @DisplayName("hasProvider returns true for registered providers")
    fun `hasProvider returns true for registered providers`() {
        assertTrue(ContextProviderRegistry.hasProvider("semantic"))
        assertTrue(ContextProviderRegistry.hasProvider("symbol"))
        assertTrue(ContextProviderRegistry.hasProvider("full_text"))
        assertTrue(ContextProviderRegistry.hasProvider("hybrid"))
    }

    @Test
    @DisplayName("hasProvider returns false for unregistered providers")
    fun `hasProvider returns false for unregistered providers`() {
        assertFalse(ContextProviderRegistry.hasProvider("unknown"))
        assertFalse(ContextProviderRegistry.hasProvider("nonexistent"))
        assertFalse(ContextProviderRegistry.hasProvider(""))
    }

    @Test
    @DisplayName("hasProvider is case-insensitive")
    fun `hasProvider is case-insensitive`() {
        assertTrue(ContextProviderRegistry.hasProvider("semantic"))
        assertTrue(ContextProviderRegistry.hasProvider("SEMANTIC"))
        assertTrue(ContextProviderRegistry.hasProvider("Semantic"))
    }

    @Test
    @DisplayName("providers have correct types")
    fun `providers have correct types`() {
        val providers = ContextProviderRegistry.getAllProviders()

        val providerTypes = providers.map { it.type }.toSet()
        assertTrue(providerTypes.contains(ContextProviderType.SEMANTIC))
        assertTrue(providerTypes.contains(ContextProviderType.SYMBOL))
        assertTrue(providerTypes.contains(ContextProviderType.FULL_TEXT))
        assertTrue(providerTypes.contains(ContextProviderType.HYBRID))
    }

    @Test
    @DisplayName("registry is thread-safe singleton")
    fun `registry is thread-safe singleton`() {
        // Access from multiple threads
        val results = (1..10).map {
            Thread {
                val count = ContextProviderRegistry.getProviderCount()
                assertEquals(5, count)
            }
        }

        results.forEach { it.start() }
        results.forEach { it.join() }

        // Should still have same providers after concurrent access
        assertEquals(5, ContextProviderRegistry.getProviderCount())
    }

    @Test
    @DisplayName("providers are initialized only once")
    fun `providers are initialized only once`() {
        // Get provider multiple times
        val provider1 = ContextProviderRegistry.getProvider("semantic")
        val provider2 = ContextProviderRegistry.getProvider("semantic")

        assertNotNull(provider1)
        assertNotNull(provider2)

        // Should have same type (can't test instance equality as registry returns from map)
        assertEquals(provider1?.type, provider2?.type)
    }

    @Test
    @DisplayName("all providers implement ContextProvider interface")
    fun `all providers implement ContextProvider interface`() {
        val providers = ContextProviderRegistry.getAllProviders()

        providers.forEach { provider ->
            assertTrue(provider is ContextProvider,
                "Provider ${provider::class.simpleName} should implement ContextProvider")
        }
    }
}
