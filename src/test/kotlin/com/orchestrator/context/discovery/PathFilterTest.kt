package com.orchestrator.context.discovery

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PathFilterTest {

    @Test
    fun `config patterns applied`() {
        val filter = PathFilter.fromSources(Path.of("."), listOf("build/", "*.tmp"))
        assertTrue(filter.shouldIgnore(Path.of("build/output")))
        assertTrue(filter.shouldIgnore(Path.of("notes.tmp")))
        assertFalse(filter.shouldIgnore(Path.of("src/Main.kt")))
    }

    @Test
    fun `ignore files combined`(@TempDir tempDir: Path) {
        Files.writeString(tempDir.resolve(".gitignore"), "node_modules\n")
        Files.writeString(tempDir.resolve(".contextignore"), "docs/**\n")

        val filter = PathFilter.fromSources(tempDir)

        assertTrue(filter.shouldIgnore(tempDir.resolve("node_modules/lib")))
        assertTrue(filter.shouldIgnore(tempDir.resolve("docs/api/index.md")))
        assertFalse(filter.shouldIgnore(tempDir.resolve("src/app.kt")))
    }

    @Test
    fun `case insensitive handling`(@TempDir tempDir: Path) {
        Files.writeString(tempDir.resolve(".gitignore"), "Build/\n")
        val filter = PathFilter.fromSources(tempDir, caseInsensitive = true)
        assertTrue(filter.shouldIgnore(tempDir.resolve("build/output")))
    }
}
