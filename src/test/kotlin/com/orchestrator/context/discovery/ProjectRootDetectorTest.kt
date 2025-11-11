package com.orchestrator.context.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ProjectRootDetectorTest {

    @Test
    fun `detects git root`(@TempDir tempDir: Path) {
        val child = tempDir.resolve("app/src").also { Files.createDirectories(it) }
        Files.createDirectories(tempDir.resolve(".git"))

        val result = ProjectRootDetector.detect(child)

        assertEquals(tempDir.normalize(), result.root)
        assertEquals(ProjectRootDetector.Confidence.HIGH, result.confidence)
        assertTrue(result.reason.contains(".git"))
    }

    @Test
    fun `detects package manifest`(@TempDir tempDir: Path) {
        val child = tempDir.resolve("module").also { Files.createDirectories(it) }
        Files.createFile(tempDir.resolve("package.json"))

        val result = ProjectRootDetector.detect(child)

        assertEquals(tempDir.normalize(), result.root)
        assertEquals(ProjectRootDetector.Confidence.MEDIUM, result.confidence)
    }

    @Test
    fun `detects structure fallback`(@TempDir tempDir: Path) {
        val candidate = tempDir.resolve("project").also { Files.createDirectories(it) }
        Files.createDirectories(candidate.resolve("src"))
        Files.createDirectories(candidate.resolve("tests"))
        val nested = candidate.resolve("src/main").also { Files.createDirectories(it) }

        val result = ProjectRootDetector.detect(nested)

        assertEquals(candidate.normalize(), result.root)
        assertEquals(ProjectRootDetector.Confidence.LOW, result.confidence)
    }

    @Test
    fun `falls back to current working directory`(@TempDir tempDir: Path) {
        val nested = tempDir.resolve("lonely").also { Files.createDirectories(it) }

        val result = ProjectRootDetector.detect(nested)

        assertEquals(nested.normalize(), result.root)
        assertEquals(ProjectRootDetector.Confidence.LOW, result.confidence)
    }
}
