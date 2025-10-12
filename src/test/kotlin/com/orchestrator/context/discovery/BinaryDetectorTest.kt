package com.orchestrator.context.discovery

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BinaryDetectorTest {

    @Test
    fun `extension detection catches known binaries`(@TempDir tempDir: Path) {
        val exe = tempDir.resolve("run.EXE")
        Files.writeString(exe, "dummy")

        assertTrue(BinaryDetector.detectByExtension(exe))
        assertTrue(BinaryDetector.isBinary(exe))
    }

    @Test
    fun `content detection flags files with binary signatures`(@TempDir tempDir: Path) {
        val binaryData = ByteArray(256) { index ->
            if (index % 4 == 0) 0 else 0x90.toByte()
        }
        val path = tempDir.resolve("blob.bin")
        Files.write(path, binaryData)

        assertTrue(BinaryDetector.detectByContent(path))
        assertTrue(BinaryDetector.isBinary(path))
    }

    @Test
    fun `text files are not treated as binary`(@TempDir tempDir: Path) {
        val text = tempDir.resolve("notes.txt")
        Files.writeString(text, buildString {
            repeat(200) { append("Line $it with ASCII characters only\n") }
        })

        assertFalse(BinaryDetector.detectByContent(text))
        assertFalse(BinaryDetector.isBinary(text))
    }
}
