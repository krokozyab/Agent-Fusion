package com.orchestrator.context.discovery

import com.orchestrator.context.config.BinaryDetectionMode
import com.orchestrator.context.config.IndexingConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DirectoryScannerTest {

    @Test
    fun `scans allowed files within watch roots`(@TempDir tempDir: Path) {
        val root = tempDir.resolve("project").also { Files.createDirectories(it) }
        val srcDir = root.resolve("src").also { Files.createDirectories(it) }
        val allowed = srcDir.resolve("Main.kt").also { Files.writeString(it, "fun main() {}") }
        val ignoredExt = srcDir.resolve("notes.tmp").also { Files.writeString(it, "temp") }

        val validator = newValidator(listOf(root), allowedExtensions = listOf(".kt"))
        val scanner = DirectoryScanner(validator)

        val results = scanner.scan(listOf(root))

        assertTrue(results.contains(allowed.toAbsolutePath().normalize()))
        assertFalse(results.contains(ignoredExt.toAbsolutePath().normalize()))
    }

    @Test
    fun `respects ignore patterns and prunes directories`(@TempDir tempDir: Path) {
        val root = tempDir.resolve("project").also { Files.createDirectories(it) }
        val buildDir = root.resolve("build/output").also { Files.createDirectories(it) }
        val buildFile = buildDir.resolve("artifact.kt").also { Files.writeString(it, "fun ignored() {}") }
        val srcFile = root.resolve("src/Main.kt").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "fun main() {}")
        }

        val validator = newValidator(
            listOf(root),
            allowedExtensions = listOf(".kt"),
            ignorePatterns = listOf("build/**")
        )
        val scanner = DirectoryScanner(validator)

        val results = scanner.scan(listOf(root))

        assertTrue(results.contains(srcFile.toAbsolutePath().normalize()))
        assertFalse(results.contains(buildFile.toAbsolutePath().normalize()))
    }

    @Test
    fun `skips binary files detected by validator`(@TempDir tempDir: Path) {
        val root = tempDir.resolve("project").also { Files.createDirectories(it) }
        val srcFile = root.resolve("src/Main.kt").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "fun main() {}")
        }
        val binaryFile = root.resolve("src/image.png").also {
            Files.write(it, ByteArray(4096) { index -> if (index % 2 == 0) 0 else 0xFF.toByte() })
        }

        val validator = newValidator(listOf(root), allowedExtensions = listOf(".kt"))
        val scanner = DirectoryScanner(validator)

        val results = scanner.scan(listOf(root))

        assertTrue(results.contains(srcFile.toAbsolutePath().normalize()))
        assertFalse(results.contains(binaryFile.toAbsolutePath().normalize()))
    }

    @Test
    fun `handles symlink escapes safely`(@TempDir tempDir: Path) {
        assumeTrue(canCreateSymlink(tempDir))

        val root = tempDir.resolve("project").also { Files.createDirectories(it) }
        val outside = tempDir.resolve("outside/secret.txt")
        Files.createDirectories(outside.parent)
        Files.writeString(outside, "secret")
        val link = root.resolve("secret-link")
        Files.createSymbolicLink(link, outside)
        val insideFile = root.resolve("src/Main.kt").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "fun main() {}")
        }

        val validator = newValidator(listOf(root), allowedExtensions = listOf(".kt"))
        val scanner = DirectoryScanner(validator)

        val results = scanner.scan(listOf(root))

        assertTrue(results.contains(insideFile.toAbsolutePath().normalize()))
        assertFalse(results.contains(link.toAbsolutePath().normalize()))
    }

    @Test
    fun `can scan multiple roots optionally in parallel`(@TempDir tempDir: Path) {
        val rootA = tempDir.resolve("projectA").also { Files.createDirectories(it) }
        val rootB = tempDir.resolve("projectB").also { Files.createDirectories(it) }
        val fileA = rootA.resolve("src/A.kt").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "fun a() {}")
        }
        val fileB = rootB.resolve("src/B.kt").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "fun b() {}")
        }

        val validator = newValidator(listOf(rootA, rootB), allowedExtensions = listOf(".kt"))
        val scanner = DirectoryScanner(validator, parallel = true)

        val results = scanner.scan(listOf(rootA, rootB))

        val normalizedResults = results.toSet()
        assertTrue(normalizedResults.contains(fileA.toAbsolutePath().normalize()))
        assertTrue(normalizedResults.contains(fileB.toAbsolutePath().normalize()))
    }

    private fun newValidator(
        watchRoots: List<Path>,
        allowedExtensions: List<String>,
        ignorePatterns: List<String> = emptyList(),
        followSymlinks: Boolean = true,
        maxFileSizeMb: Int = 5
    ): PathValidator {
        val indexingConfig = IndexingConfig(
            allowedExtensions = allowedExtensions,
            blockedExtensions = emptyList(),
            maxFileSizeMb = maxFileSizeMb,
            warnFileSizeMb = maxFileSizeMb,
            sizeExceptions = emptyList(),
            followSymlinks = followSymlinks,
            maxSymlinkDepth = 3,
            binaryDetection = BinaryDetectionMode.ALL,
            binaryThreshold = 30
        )
        val filterRoot = watchRoots.first()
        val pathFilter = PathFilter.fromSources(filterRoot, ignorePatterns)
        val extensionFilter = ExtensionFilter.fromConfig(allowedExtensions, emptyList())
        val symlinkHandler = SymlinkHandler(watchRoots, indexingConfig)
        return PathValidator(watchRoots, pathFilter, extensionFilter, symlinkHandler, indexingConfig)
    }

    private fun canCreateSymlink(tempDir: Path): Boolean {
        val target = tempDir.resolve("_target").also { Files.writeString(it, "x") }
        val link = tempDir.resolve("_link")
        return try {
            Files.deleteIfExists(link)
            Files.createSymbolicLink(link, target.fileName)
            true
        } catch (_: Exception) {
            false
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(target)
        }
    }
}
