package com.orchestrator.context.discovery

import com.orchestrator.context.config.IndexingConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SymlinkHandlerTest {

    @Test
    fun `follows symlink within allowed root`(@TempDir tempDir: Path) {
        assumeTrue(canCreateSymlink(tempDir))

        val root = tempDir.resolve("project").also { Files.createDirectories(it) }
        val file = root.resolve("target.txt")
        Files.writeString(file, "hello")
        val link = root.resolve("alias.txt")
        Files.createSymbolicLink(link, Path.of("target.txt"))

        val handler = SymlinkHandler(listOf(root), IndexingConfig(followSymlinks = true))

        assertTrue(handler.shouldFollow(link))
        assertEquals(file.toAbsolutePath().normalize(), handler.resolveTarget(link))
    }

    @Test
    fun `disabled follow prevents traversal`(@TempDir tempDir: Path) {
        assumeTrue(canCreateSymlink(tempDir))

        val root = tempDir.resolve("project").also { Files.createDirectories(it) }
        val file = root.resolve("file.txt")
        Files.writeString(file, "content")
        val link = root.resolve("link.txt")
        Files.createSymbolicLink(link, Path.of("file.txt"))

        val handler = SymlinkHandler(listOf(root), IndexingConfig(followSymlinks = false))

        assertFalse(handler.shouldFollow(link))
    }

    @Test
    fun `max depth enforced`(@TempDir tempDir: Path) {
        assumeTrue(canCreateSymlink(tempDir))

        val root = tempDir.resolve("project").also { Files.createDirectories(it) }
        val file = root.resolve("file.txt")
        Files.writeString(file, "content")
        val innerLink = root.resolve("inner.txt")
        Files.createSymbolicLink(innerLink, Path.of("file.txt"))
        val outerLink = root.resolve("outer.txt")
        Files.createSymbolicLink(outerLink, Path.of("inner.txt"))

        val handler = SymlinkHandler(
            listOf(root),
            IndexingConfig(followSymlinks = true, maxSymlinkDepth = 1)
        )

        assertFalse(handler.shouldFollow(outerLink))
    }

    @Test
    fun `detects loops`(@TempDir tempDir: Path) {
        assumeTrue(canCreateSymlink(tempDir))

        val root = tempDir.resolve("project").also { Files.createDirectories(it) }
        val linkA = root.resolve("a")
        val linkB = root.resolve("b")
        Files.createSymbolicLink(linkA, Path.of("b"))
        Files.createSymbolicLink(linkB, Path.of("a"))

        val handler = SymlinkHandler(listOf(root), IndexingConfig(followSymlinks = true))

        assertFalse(handler.shouldFollow(linkA))
    }

    @Test
    fun `blocks escape outside allowed roots`(@TempDir tempDir: Path) {
        assumeTrue(canCreateSymlink(tempDir))

        val root = tempDir.resolve("project").also { Files.createDirectories(it) }
        val outside = tempDir.resolve("outside").also { Files.createDirectories(it) }
        val link = root.resolve("escape")
        Files.createSymbolicLink(link, outside)

        val handler = SymlinkHandler(listOf(root), IndexingConfig(followSymlinks = true))

        assertTrue(handler.isEscape(outside))
        assertFalse(handler.shouldFollow(link))
    }

    private fun canCreateSymlink(tempDir: Path): Boolean {
        val probeTarget = tempDir.resolve("_probe_target").also { Files.writeString(it, "x") }
        val probeLink = tempDir.resolve("_probe_link")
        return try {
            Files.deleteIfExists(probeLink)
            Files.createSymbolicLink(probeLink, probeTarget.fileName)
            Files.deleteIfExists(probeLink)
            true
        } catch (_: Exception) {
            false
        } finally {
            Files.deleteIfExists(probeLink)
            Files.deleteIfExists(probeTarget)
        }
    }
}
