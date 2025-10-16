package com.orchestrator.context.watcher

import com.orchestrator.context.config.WatcherConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileWatcherTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `file watcher emits create modify delete events`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val config = WatcherConfig(
            debounceMs = 0,
            watchPaths = listOf(tempDir.toString()),
            ignorePatterns = emptyList()
        )
        val watcher = FileWatcher(scope, listOf(tempDir), config)

        try {
            watcher.start()
            val file = tempDir.resolve("sample.txt")

            val created = async {
                withTimeout(5_000) {
                    watcher.events.first { it.kind == FileWatchEvent.Kind.CREATED && it.path == file }
                }
            }
            Files.writeString(file, "hello world")
            assertEquals(file, created.await().path)

            val modified = async {
                withTimeout(5_000) {
                    watcher.events.first { it.kind == FileWatchEvent.Kind.MODIFIED && it.path == file }
                }
            }
            Files.writeString(file, "hello again")
            assertEquals(file, modified.await().path)

            val deleted = async {
                withTimeout(5_000) {
                    watcher.events.first { it.kind == FileWatchEvent.Kind.DELETED && it.path == file }
                }
            }
            Files.deleteIfExists(file)
            assertEquals(file, deleted.await().path)
        } finally {
            watcher.close()
            scope.cancel()
        }
    }

    @Test
    fun `file watcher registers new directories and emits nested file events`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val config = WatcherConfig(
            debounceMs = 0,
            watchPaths = listOf(tempDir.toString()),
            ignorePatterns = emptyList()
        )
        val watcher = FileWatcher(scope, listOf(tempDir), config)

        try {
            watcher.start()

            val nestedDir = tempDir.resolve("nested")
            val dirCreated = async {
                withTimeout(5_000) {
                    watcher.events.first { it.kind == FileWatchEvent.Kind.CREATED && it.path == nestedDir }
                }
            }
            Files.createDirectory(nestedDir)
            assertEquals(nestedDir, dirCreated.await().path)

            val nestedFile = nestedDir.resolve("child.txt")
            val fileCreated = async {
                withTimeout(5_000) {
                    watcher.events.first { it.kind == FileWatchEvent.Kind.CREATED && it.path == nestedFile }
                }
            }
            Files.writeString(nestedFile, "child")
            assertEquals(nestedFile, fileCreated.await().path)
        } finally {
            watcher.close()
            scope.cancel()
        }
    }
}

