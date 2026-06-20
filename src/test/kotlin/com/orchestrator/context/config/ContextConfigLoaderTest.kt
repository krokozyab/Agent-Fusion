package com.orchestrator.context.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import com.orchestrator.context.config.DeploymentMode
import java.nio.file.Files
import java.nio.file.Paths

class ContextConfigLoaderTest {

    @Test
    fun `returns defaults when file missing`() {
        val config = ContextConfigLoader.load(path = Paths.get("config/not-present-context.toml"))
        assertEquals(ContextConfig(), config)
    }

    @Test
    fun `loads overrides and providers`() {
        val tempDir = Files.createTempDirectory("context-config")
        val tomlPath = tempDir.resolve("context.toml")

        val srcPath = Paths.get("src").toAbsolutePath()
        val dbPath = tempDir.resolve("context.duckdb").toAbsolutePath()

        Files.writeString(
            tomlPath,
            """
            [context]
            enabled = false
            mode = "standalone"
            fallback_enabled = false
            
            [context.engine]
            host = "127.0.0.2"
            port = 9010
            timeout_ms = 20000
            retry_attempts = 5
            
            [context.storage]
            db_path = "${'$'}{DB_PATH}"
            
            [context.watcher]
            enabled = true
            watch_paths = ["${'$'}{SRC_PATH}"]
            ignore_patterns = [".git", "build"]
            max_file_size_mb = 10
            
            [context.providers.semantic]
            weight = 0.7
            
            [context.metrics]
            enabled = false
            export_interval_minutes = 10
            """.trimIndent()
        )

        val env = mapOf(
            "DB_PATH" to dbPath.toString(),
            "SRC_PATH" to srcPath.toString()
        )

        val config = ContextConfigLoader.load(path = tomlPath, env = env)

        assertFalse(config.enabled)
        assertEquals(DeploymentMode.STANDALONE, config.mode)
        assertFalse(config.fallbackEnabled)
        assertEquals("127.0.0.2", config.engine.host)
        assertEquals(9010, config.engine.port)
        assertEquals(20000, config.engine.timeoutMs)
        assertEquals(dbPath.toString(), config.storage.dbPath)
        assertTrue(config.watcher.watchPaths.contains(srcPath.toString()))
        assertEquals(0.7, config.providers.getValue("semantic").weight)
        assertFalse(config.metrics.enabled)
        assertEquals(10, config.metrics.exportIntervalMinutes)
    }

    @Test
    fun `parses indexing git_intent_links and min_eps_warn with defaults`() {
        val srcPath = Paths.get("src").toAbsolutePath()

        // Defaults when the keys are absent.
        val defaults = ContextConfigLoader.load(path = Paths.get("config/not-present-context.toml"))
        assertTrue(defaults.indexing.gitIntentLinks, "git_intent_links defaults to true")
        assertEquals(0, defaults.indexing.minEpsWarn, "min_eps_warn defaults to 0 (off)")

        // Explicit overrides.
        val tempDir = Files.createTempDirectory("context-config-indexing")
        val tomlPath = tempDir.resolve("context.toml")
        Files.writeString(
            tomlPath,
            """
            [context.watcher]
            enabled = true
            watch_paths = ["${'$'}{SRC_PATH}"]

            [context.indexing]
            git_intent_links = false
            min_eps_warn = 50
            """.trimIndent()
        )

        val config = ContextConfigLoader.load(path = tomlPath, env = mapOf("SRC_PATH" to srcPath.toString()))
        assertFalse(config.indexing.gitIntentLinks)
        assertEquals(50, config.indexing.minEpsWarn)
    }

    @Test
    fun `expands environment variables in nested tables`() {
        val tempDir = Files.createTempDirectory("context-config-env")
        val tomlPath = tempDir.resolve("context.toml")
        val watchPath = Paths.get("src/main").toAbsolutePath()

        Files.writeString(
            tomlPath,
            """
            [context]
            
            [context.storage]
            db_path = "${'$'}{DB_PATH}"
            
            [context.bootstrap]
            priority_extensions = ["${'$'}{BOOT_EXT}"]
            
            [context.providers.hybrid]
            fusion_strategy = "${'$'}{FUSION}"
            combines = ["semantic", "${'$'}{EXTRA}" ]
            """.trimIndent()
        )

        val env = mapOf(
            "DB_PATH" to tempDir.resolve("ctx.duckdb").toString(),
            "BOOT_EXT" to ".rs",
            "FUSION" to "rrf",
            "EXTRA" to "symbol"
        )

        val config = ContextConfigLoader.load(path = tomlPath, env = env)

        assertEquals(tempDir.resolve("ctx.duckdb").toString(), config.storage.dbPath)
        assertTrue(config.bootstrap.priorityExtensions.contains(".rs"))
        assertEquals("rrf", config.providers.getValue("hybrid").fusionStrategy)
        assertTrue(config.providers.getValue("hybrid").combines.contains("symbol"))
    }

    @Test
    fun `rejects dangerous or missing watch paths`() {
        val tempDir = Files.createTempDirectory("context-config-invalid")
        val tomlPath = tempDir.resolve("context.toml")

        Files.writeString(
            tomlPath,
            """
            [context.watcher]
            watch_paths = ["/"]
            """.trimIndent()
        )

        val error = assertFailsWith<IllegalArgumentException> {
            ContextConfigLoader.load(path = tomlPath)
        }

        assertTrue(error.message!!.contains("restricted"))
    }

    @Test
    fun `rejects invalid extensions`() {
        val tempDir = Files.createTempDirectory("context-config-ext")
        val tomlPath = tempDir.resolve("context.toml")
        val watchPath = Paths.get("src").toAbsolutePath()

        Files.writeString(
            tomlPath,
            """
            [context.watcher]
            watch_paths = ["${'$'}{WATCH}"]
            
            [context.indexing]
            allowed_extensions = ["kotlin"]
            """.trimIndent()
        )

        val env = mapOf("WATCH" to watchPath.toString())

        val error = assertFailsWith<IllegalArgumentException> {
            ContextConfigLoader.load(path = tomlPath, env = env)
        }

        assertTrue(error.message!!.contains("allowed_extensions"))
    }

    @Test
    fun `loads query expansion and second stage rerank settings`() {
        val tempDir = Files.createTempDirectory("context-config-query-enhancements")
        val tomlPath = tempDir.resolve("context.toml")
        val watchPath = Paths.get("src").toAbsolutePath()

        Files.writeString(
            tomlPath,
            """
            [context.watcher]
            watch_paths = ["${'$'}{WATCH}"]

            [context.query]
            second_stage_rerank_enabled = true
            second_stage_top_n = 42
            second_stage_blend_weight = 0.4
            query_expansion_enabled = true
            hyde_enabled = true
            max_expansion_terms = 12

            [context.query.synonyms]
            login = ["authentication", "auth", "token"]
            """.trimIndent()
        )

        val env = mapOf("WATCH" to watchPath.toString())
        val config = ContextConfigLoader.load(path = tomlPath, env = env)

        assertTrue(config.query.secondStageRerankEnabled)
        assertEquals(42, config.query.secondStageTopN)
        assertEquals(0.4, config.query.secondStageBlendWeight)
        assertTrue(config.query.queryExpansionEnabled)
        assertTrue(config.query.hydeEnabled)
        assertEquals(12, config.query.maxExpansionTerms)
        assertEquals(listOf("authentication", "auth", "token"), config.query.synonyms["login"])
    }

    @Test
    fun `loads go chunking overrides`() {
        val tempDir = Files.createTempDirectory("context-config-go-chunking")
        val tomlPath = tempDir.resolve("context.toml")
        val watchPath = Paths.get("src").toAbsolutePath()

        Files.writeString(
            tomlPath,
            """
            [context.watcher]
            watch_paths = ["${'$'}{WATCH}"]

            [context.chunking.go]
            max_tokens = 720
            """.trimIndent()
        )

        val env = mapOf("WATCH" to watchPath.toString())
        val config = ContextConfigLoader.load(path = tomlPath, env = env)

        assertEquals(720, config.chunking.go.maxTokens)
    }
}
