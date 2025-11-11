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

class PathValidatorTest {

    @Test
    fun `accepts file inside watch root`(@TempDir tempDir: Path) {
        val root = tempDir.resolve("project").also { Files.createDirectories(it) }
        val file = root.resolve("src/Main.kt")
        Files.createDirectories(file.parent)
        Files.writeString(file, "fun main() = println(\"hello\")")

        val validator = newValidator(
            root,
            allowedExtensions = listOf(".kt")
        )

        val result = validator.validate(file)

        assertTrue(result.isValid(), "Expected file to be accepted: ${result.message}")
    }

    @Test
    fun `rejects path traversal attempts`(@TempDir tempDir: Path) {
        val root = tempDir.resolve("root").also { Files.createDirectories(it) }
        val validator = newValidator(root)
        val traversal = root.resolve("..").resolve("..").resolve("etc").resolve("passwd")
        val result = validator.validate(traversal)

        assertFalse(result.isValid())
        assertEquals(PathValidator.Reason.PATH_TRAVERSAL, result.code)
    }

    @Test
    fun `rejects paths outside watch roots`(@TempDir tempDir: Path) {
        val root = tempDir.resolve("root").also { Files.createDirectories(it) }
        val outside = tempDir.resolve("outside/file.txt").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "data")
        }

        val validator = newValidator(root, allowedExtensions = listOf(".txt"))
        val result = validator.validate(outside)

        assertFalse(result.isValid())
        assertEquals(PathValidator.Reason.OUTSIDE_WATCH_PATH, result.code)
    }

    @Test
    fun `rejects ignored patterns`(@TempDir tempDir: Path) {
        val root = tempDir.resolve("root").also { Files.createDirectories(it) }
        val ignored = root.resolve("build/output.log")
        Files.createDirectories(ignored.parent)
        Files.writeString(ignored, "log")

        val validator = newValidator(
            root,
            allowedExtensions = listOf(".log"),
            ignorePatterns = listOf("build/**")
        )

        val result = validator.validate(ignored)

        assertFalse(result.isValid())
        assertEquals(PathValidator.Reason.IGNORED_BY_PATTERN, result.code)
    }

    @Test
    fun `rejects extension not in allowlist`(@TempDir tempDir: Path) {
        val root = tempDir.resolve("root").also { Files.createDirectories(it) }
        val file = root.resolve("README.md")
        Files.writeString(file, "# heading")

        val validator = newValidator(root, allowedExtensions = listOf(".kt"))
        val result = validator.validate(file)

        assertFalse(result.isValid())
        assertEquals(PathValidator.Reason.EXTENSION_NOT_ALLOWED, result.code)
    }

    @Test
    fun `rejects binary content even with text extension`(@TempDir tempDir: Path) {
        val root = tempDir.resolve("root").also { Files.createDirectories(it) }
        val file = root.resolve("data.txt")
        val binary = ByteArray(4096) { if (it % 2 == 0) 0 else 0xFF.toByte() }
        Files.write(file, binary)

        val validator = newValidator(root, allowedExtensions = listOf(".txt"))
        val result = validator.validate(file)

        assertFalse(result.isValid())
        assertEquals(PathValidator.Reason.BINARY_FILE, result.code)
    }

    @Test
    fun `rejects files exceeding size limit`(@TempDir tempDir: Path) {
        val root = tempDir.resolve("root").also { Files.createDirectories(it) }
        val largeFile = root.resolve("large.txt")
        val twoMb = ByteArray(2 * 1024 * 1024) { 'a'.code.toByte() }
        Files.write(largeFile, twoMb)

        val validator = newValidator(root, allowedExtensions = listOf(".txt"), maxFileSizeMb = 1)
        val result = validator.validate(largeFile)

        assertFalse(result.isValid())
        assertEquals(PathValidator.Reason.SIZE_LIMIT_EXCEEDED, result.code)
    }

    @Test
    fun `rejects symlink escapes`(@TempDir tempDir: Path) {
        assumeTrue(canCreateSymlink(tempDir))

        val root = tempDir.resolve("root").also { Files.createDirectories(it) }
        val outside = tempDir.resolve("outside/secret.txt")
        Files.createDirectories(outside.parent)
        Files.writeString(outside, "secrets")
        val link = root.resolve("link.txt")
        Files.createSymbolicLink(link, outside)

        val validator = newValidator(root, followSymlinks = true)
        val result = validator.validate(link)

        assertFalse(result.isValid())
        assertEquals(PathValidator.Reason.SYMLINK_ESCAPE, result.code)
    }

    @Test
    fun `rejects symlinks when disabled`(@TempDir tempDir: Path) {
        assumeTrue(canCreateSymlink(tempDir))

        val root = tempDir.resolve("root").also { Files.createDirectories(it) }
        val target = root.resolve("file.txt")
        Files.writeString(target, "data")
        val link = root.resolve("link.txt")
        Files.createSymbolicLink(link, target.fileName)

        val validator = newValidator(root, allowedExtensions = listOf(".txt"), followSymlinks = false)
        val result = validator.validate(link)

        assertFalse(result.isValid())
        assertEquals(PathValidator.Reason.SYMLINK_NOT_ALLOWED, result.code)
    }

    @Test
    fun `rejects symlink loops`(@TempDir tempDir: Path) {
        assumeTrue(canCreateSymlink(tempDir))

        val root = tempDir.resolve("root").also { Files.createDirectories(it) }
        val a = root.resolve("a")
        val b = root.resolve("b")
        Files.createSymbolicLink(a, b.fileName)
        Files.createSymbolicLink(b, a.fileName)

        val validator = newValidator(root, followSymlinks = true)
        val result = validator.validate(a)

        assertFalse(result.isValid())
        assertEquals(PathValidator.Reason.SYMLINK_LOOP_OR_BROKEN, result.code)
    }

    private fun newValidator(
        root: Path,
        allowedExtensions: List<String> = listOf(".txt"),
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
        val pathFilter = PathFilter.fromSources(root, ignorePatterns)
        val extensionFilter = ExtensionFilter.fromConfig(allowedExtensions, emptyList())
        val symlinkHandler = SymlinkHandler(listOf(root), indexingConfig)
        val includePathsFilter = IncludePathsFilter.disabled()
        return PathValidator(
            listOf(root),
            pathFilter,
            extensionFilter,
            includePathsFilter,
            symlinkHandler,
            indexingConfig
        )
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
