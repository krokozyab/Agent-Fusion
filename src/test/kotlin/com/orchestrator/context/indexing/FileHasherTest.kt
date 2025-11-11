package com.orchestrator.context.indexing

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class FileHasherTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var file: Path

    @BeforeTest
    fun setUp() {
        file = tempDir.resolve("sample.txt")
        Files.writeString(file, "vector search hashing test")
    }

    @Test
    fun `hash equals sha256 fallback when blake3 unavailable`() {
        val expected = sha256(file)
        val actual = FileHasher.computeHash(file)

        assertContentEquals(expected, actual)
        assertEquals(expected.size, actual.size)
    }

    @Test
    fun `hashing is deterministic`() {
        val first = FileHasher.computeHash(file)
        val second = FileHasher.computeHash(file)

        assertContentEquals(first, second)
    }

    @Test
    fun `hex helper formats lowercase`() {
        val hash = FileHasher.computeHash(file)
        val hex = FileHasher.hex(hash)

        assertEquals(hash.size * 2, hex.length)
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `streams large files efficiently`() {
        val largeFile = tempDir.resolve("large.bin")
        generateLargeFile(largeFile, 6 * 1024 * 1024) // 6 MiB

        val hash = FileHasher.computeHash(largeFile)
        val expected = sha256(largeFile)

        assertContentEquals(expected, hash)
    }

    @Test
    fun `throws for missing files`() {
        val missing = tempDir.resolve("missing.txt")
        assertFailsWith<IllegalArgumentException> {
            FileHasher.computeHash(missing)
        }
    }

    private fun sha256(path: Path): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    private fun generateLargeFile(path: Path, sizeBytes: Int) {
        val random = Random(42)
        Files.newOutputStream(path).use { output ->
            val buffer = ByteArray(1024)
            var remaining = sizeBytes
            while (remaining > 0) {
                random.nextBytes(buffer)
                val toWrite = minOf(buffer.size, remaining)
                output.write(buffer, 0, toWrite)
                remaining -= toWrite
            }
        }
    }
}
