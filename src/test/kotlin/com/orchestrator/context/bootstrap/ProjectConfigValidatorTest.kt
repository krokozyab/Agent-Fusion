package com.orchestrator.context.bootstrap

import org.junit.jupiter.api.BeforeAll
import java.nio.file.Files

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.config.StorageConfig
import com.orchestrator.context.storage.ContextDatabase
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectConfigValidatorTest {



    @TempDir
    lateinit var tempDir: Path

    private lateinit var validator: ProjectConfigValidator

    @BeforeTest
    fun setUp() {
        val dbPath = tempDir.resolve("context.duckdb").toString()
        ContextDatabase.initialize(StorageConfig(dbPath = dbPath))
        validator = ProjectConfigValidator()
    }

    @AfterTest
    fun tearDown() {
        ContextDatabase.shutdown()
    }

    @Test
    fun `saveConfig and loadConfig should work correctly`() {
        val config = ContextConfig()
        validator.saveConfig(config)

        val loadedConfig = validator.loadConfig()
        assertNotNull(loadedConfig)
        assertEquals(config, loadedConfig)
    }

    @Test
    fun `detectChanges should detect changes correctly`() {
        val oldConfig = ContextConfig()
        validator.saveConfig(oldConfig)

        val newConfig = oldConfig.copy(watcher = oldConfig.watcher.copy(debounceMs = 1000))
        val changes = validator.detectChanges(newConfig)

        assertTrue(changes.added.isEmpty())
        assertTrue(changes.removed.isEmpty())
        assertEquals(1, changes.modified.size)
    }

    @Test
    fun `validate should return valid for valid config`() {
        val watchPath = tempDir.resolve("watch").createFile()
        val config = ContextConfig(watcher = com.orchestrator.context.config.WatcherConfig(watchPaths = listOf(watchPath.toString())))

        val result = validator.validate(config)

        if (!result.isValid) {
            println("Validation failed with errors: ${result.errors}")
        }
        assertTrue(result.isValid, "Config should be valid but got errors: ${result.errors}")
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `validate should return invalid for non-existent watch path`() {
        val config = ContextConfig(watcher = com.orchestrator.context.config.WatcherConfig(watchPaths = listOf("/non/existent/path")))

        val result = validator.validate(config)

        assertTrue(!result.isValid)
        assertEquals(1, result.errors.size)
    }

    @Test
    fun `validate should reject dangerous system paths`() {
        val config = ContextConfig(watcher = com.orchestrator.context.config.WatcherConfig(watchPaths = listOf("/etc")))

        val result = validator.validate(config)

        assertTrue(!result.isValid)
        assertTrue(result.errors.any { it.contains("Dangerous path detected") })
    }

    @Test
    fun `validate should reject invalid extensions`() {
        val config = ContextConfig(
            indexing = com.orchestrator.context.config.IndexingConfig(
                allowedExtensions = listOf("kt", ".py", "")  // missing dot, blank
            )
        )

        val result = validator.validate(config)

        assertTrue(!result.isValid)
        assertTrue(result.errors.any { it.contains("must start with dot") })
        assertTrue(result.errors.any { it.contains("blank extension") })
    }

    @Test
    fun `validate should reject unreasonable file size limits`() {
        val configTooSmall = ContextConfig(
            indexing = com.orchestrator.context.config.IndexingConfig(maxFileSizeMb = 0)
        )
        val resultTooSmall = validator.validate(configTooSmall)
        assertTrue(!resultTooSmall.isValid)
        assertTrue(resultTooSmall.errors.any { it.contains("must be positive") })

        val configTooLarge = ContextConfig(
            indexing = com.orchestrator.context.config.IndexingConfig(maxFileSizeMb = 2000)
        )
        val resultTooLarge = validator.validate(configTooLarge)
        assertTrue(!resultTooLarge.isValid)
        assertTrue(resultTooLarge.errors.any { it.contains("too large") })
    }

    @Test
    fun `validate should reject path traversal attempts`() {
        val config = ContextConfig(
            watcher = com.orchestrator.context.config.WatcherConfig(watchPaths = listOf("../../../etc"))
        )

        val result = validator.validate(config)

        assertTrue(!result.isValid)
        assertTrue(result.errors.any { it.contains("Path traversal detected") })
    }

    @Test
    fun `validate should detect ambiguous max_file_size_mb configuration`() {
        // This mirrors the actual fusionagent.toml situation: watcher=5MB, indexing=200MB
        val config = ContextConfig(
            watcher = com.orchestrator.context.config.WatcherConfig(
                watchPaths = listOf(tempDir.toString()),
                maxFileSizeMb = 5
            ),
            indexing = com.orchestrator.context.config.IndexingConfig(
                maxFileSizeMb = 200
            )
        )

        val result = validator.validate(config)

        assertTrue(!result.isValid, "Config with ambiguous max_file_size_mb should be invalid")
        assertTrue(result.errors.any { it.contains("CONFIGURATION ERROR: Ambiguous 'max_file_size_mb'") })
        assertTrue(result.errors.any { it.contains("[context.watcher] max_file_size_mb = 5 MB") })
        assertTrue(result.errors.any { it.contains("[context.indexing] max_file_size_mb = 200 MB") })
        assertTrue(result.errors.any { it.contains("watch_max_file_size_mb") })
        assertTrue(result.errors.any { it.contains("index_max_file_size_mb") })
    }

    @Test
    fun `validate should allow similar max_file_size_mb values`() {
        // When values are similar, it's not necessarily an error (could be intentional)
        val config = ContextConfig(
            watcher = com.orchestrator.context.config.WatcherConfig(
                watchPaths = listOf(tempDir.toString()),
                maxFileSizeMb = 10
            ),
            indexing = com.orchestrator.context.config.IndexingConfig(
                maxFileSizeMb = 15
            )
        )

        val result = validator.validate(config)

        assertTrue(result.isValid, "Config with similar max_file_size_mb values should be valid")
        assertTrue(result.errors.isEmpty())
    }
}
