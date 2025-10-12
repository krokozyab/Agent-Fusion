package com.orchestrator.context.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
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
            backup_enabled = true
            backup_interval_hours = 2
            
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
}
