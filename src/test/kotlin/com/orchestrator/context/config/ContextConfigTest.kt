package com.orchestrator.context.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextConfigTest {

    @Test
    fun `defaults match documented schema`() {
        val config = ContextConfig()

        assertTrue(config.enabled)
        assertEquals(DeploymentMode.EMBEDDED, config.mode)
        assertTrue(config.fallbackEnabled)

        assertEquals("localhost", config.engine.host)
        assertEquals(9090, config.engine.port)
        assertEquals(10_000, config.engine.timeoutMs)
        assertEquals(3, config.engine.retryAttempts)

        assertEquals("./context.duckdb", config.storage.dbPath)
        assertFalse(config.storage.backupEnabled)
        assertEquals(24, config.storage.backupIntervalHours)

        assertTrue(config.watcher.enabled)
        assertEquals(500, config.watcher.debounceMs)
        assertEquals(listOf("auto"), config.watcher.watchPaths)
        assertTrue(config.watcher.ignorePatterns.contains("node_modules"))

        assertEquals(10, config.indexing.maxFileSizeMb)
        assertEquals(2, config.indexing.warnFileSizeMb)
        assertEquals(BinaryDetectionMode.ALL, config.indexing.binaryDetection)
        assertTrue(config.indexing.allowedExtensions.contains(".kt"))
        assertTrue(config.indexing.blockedExtensions.isEmpty())

        assertEquals("sentence-transformers/all-MiniLM-L6-v2", config.embedding.model)
        assertEquals(384, config.embedding.dimension)
        assertEquals(128, config.embedding.batchSize)
        assertTrue(config.embedding.normalize)

        assertEquals(400, config.chunking.markdown.maxTokens)
        assertTrue(config.chunking.python.splitByFunction)
        assertTrue(config.chunking.kotlin.preserveKdoc)
        assertTrue(config.chunking.typescript.preserveJsdoc)

        assertEquals(12, config.query.defaultK)
        assertEquals(0.5, config.query.mmrLambda)
        assertEquals(0.3, config.query.minScoreThreshold)
        assertTrue(config.query.rerankEnabled)

        assertEquals(1_500, config.budget.defaultMaxTokens)
        assertEquals(500, config.budget.reserveForPrompt)
        assertEquals(80, config.budget.warnThresholdPercent)

        assertEquals(5, config.metrics.exportIntervalMinutes)
        assertTrue(config.metrics.trackTokenUsage)

        assertTrue(config.bootstrap.enabled)
        assertEquals(7, config.bootstrap.parallelWorkers)
        assertTrue(config.bootstrap.priorityExtensions.contains(".kt"))

        assertTrue(config.security.scrubSecrets)
        assertTrue(config.security.secretPatterns.isNotEmpty())
        assertFalse(config.security.encryptDb)

        val providerKeys = config.providers.keys
        assertTrue(providerKeys.containsAll(listOf("semantic", "symbol", "full_text", "git_history", "hybrid")))
        assertTrue(config.providers.getValue("full_text").enabled)
        assertEquals(listOf("semantic", "symbol", "git_history"), config.providers.getValue("hybrid").combines)
        assertEquals("rrf", config.providers.getValue("hybrid").fusionStrategy)
    }

    @Test
    fun `enabled providers filters disabled entries`() {
        val defaults = ContextConfig()
        val initialEnabled = defaults.enabledProviders
        assertTrue("full_text" in initialEnabled)
        assertTrue("semantic" in initialEnabled)

        val disabledSemantic = defaults.copy(
            providers = defaults.providers + ("semantic" to defaults.providers.getValue("semantic").copy(enabled = false))
        )

        assertFalse("semantic" in disabledSemantic.enabledProviders)
        assertEquals(initialEnabled.size - 1, disabledSemantic.enabledProviders.size)
    }

    @Test
    fun `supports overriding nested configuration`() {
        val custom = ContextConfig(
            indexing = IndexingConfig(binaryDetection = BinaryDetectionMode.MIME, followSymlinks = true),
            providers = mapOf("semantic" to ProviderConfig(weight = 1.0))
        )

        assertEquals(BinaryDetectionMode.MIME, custom.indexing.binaryDetection)
        assertTrue(custom.indexing.followSymlinks)
        assertEquals(mapOf("semantic" to ProviderConfig(weight = 1.0)), custom.providers)
    }
}
