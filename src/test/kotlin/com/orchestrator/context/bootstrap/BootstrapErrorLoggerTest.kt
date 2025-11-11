package com.orchestrator.context.bootstrap

import com.orchestrator.context.config.StorageConfig
import com.orchestrator.context.storage.ContextDatabase
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BootstrapErrorLoggerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var errorLogger: BootstrapErrorLogger

    @BeforeTest
    fun setUp() {
        val dbPath = tempDir.resolve("context.duckdb").toString()
        ContextDatabase.initialize(StorageConfig(dbPath = dbPath))
        errorLogger = BootstrapErrorLogger()
    }

    @AfterTest
    fun tearDown() {
        ContextDatabase.shutdown()
    }

    @Test
    fun `logError should persist the error to the database`() {
        val file = tempDir.resolve("error.kt").createFile()
        val error = RuntimeException("Test error")

        errorLogger.logError(file, error)

        val errors = errorLogger.getErrors()
        assertEquals(1, errors.size)
        assertEquals(file.toAbsolutePath().normalize(), errors.first().path)
        assertEquals("Test error", errors.first().errorMessage)
        assertTrue(errors.first().stackTrace.contains("RuntimeException"))
    }

    @Test
    fun `clearErrors should remove all errors from the database`() {
        val file = tempDir.resolve("error.kt").createFile()
        val error = RuntimeException("Test error")

        errorLogger.logError(file, error)
        errorLogger.clearErrors()

        val errors = errorLogger.getErrors()
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `retryFailed should return failed paths and clear errors`() {
        val file1 = tempDir.resolve("error1.kt").createFile()
        val file2 = tempDir.resolve("error2.kt").createFile()
        val error = RuntimeException("Test error")

        errorLogger.logError(file1, error)
        errorLogger.logError(file2, error)

        val failedPaths = errorLogger.retryFailed().map { it.toAbsolutePath().normalize() }

        assertEquals(2, failedPaths.size)
        assertTrue(failedPaths.contains(file1.toAbsolutePath().normalize()))
        assertTrue(failedPaths.contains(file2.toAbsolutePath().normalize()))

        val errors = errorLogger.getErrors()
        assertTrue(errors.isEmpty())
    }
}
