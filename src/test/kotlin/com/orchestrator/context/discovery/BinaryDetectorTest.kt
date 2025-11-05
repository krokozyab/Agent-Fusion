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

    @Test
    fun `markdown with UTF-8 box drawing characters is treated as text`(@TempDir tempDir: Path) {
        val markdown = tempDir.resolve("diagram.md")
        Files.writeString(markdown, buildString {
            appendLine("# Architecture Diagram")
            appendLine()
            appendLine("```")
            appendLine("┌─────────────┐")
            appendLine("│   Service   │")
            appendLine("├─────────────┤")
            appendLine("│  Component  │")
            appendLine("└─────────────┘")
            appendLine("```")
            appendLine()
            repeat(50) { appendLine("Regular ASCII text line $it") }
        }, Charsets.UTF_8)

        assertFalse(BinaryDetector.detectByContent(markdown))
        assertFalse(BinaryDetector.isBinary(markdown))
    }

    @Test
    fun `markdown with emoji is treated as text`(@TempDir tempDir: Path) {
        val markdown = tempDir.resolve("status.md")
        Files.writeString(markdown, buildString {
            appendLine("# Project Status")
            appendLine()
            appendLine("✅ Completed tasks:")
            appendLine("- Feature A")
            appendLine("- Feature B")
            appendLine()
            appendLine("❌ Pending tasks:")
            appendLine("- Feature C")
            appendLine()
            appendLine("• Bullet point 1")
            appendLine("• Bullet point 2")
            appendLine()
            repeat(50) { appendLine("Regular content line $it") }
        }, Charsets.UTF_8)

        assertFalse(BinaryDetector.detectByContent(markdown))
        assertFalse(BinaryDetector.isBinary(markdown))
    }

    @Test
    fun `file with null bytes is detected as binary`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("with-nulls.txt")
        val content = "Some text\u0000with null\u0000bytes"
        Files.write(file, content.toByteArray(Charsets.UTF_8))

        assertTrue(BinaryDetector.detectByContent(file))
        assertTrue(BinaryDetector.isBinary(file))
    }

    @Test
    fun `file with high ratio of non-printable chars is binary`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("mostly-binary.dat")
        // Create content with only 10% printable characters
        val bytes = ByteArray(1000) { index ->
            if (index % 10 == 0) 'A'.code.toByte() else 0xFF.toByte()
        }
        Files.write(file, bytes)

        assertTrue(BinaryDetector.detectByContent(file))
        assertTrue(BinaryDetector.isBinary(file))
    }

    @Test
    fun `JSON with Unicode characters is treated as text`(@TempDir tempDir: Path) {
        val json = tempDir.resolve("data.json")
        Files.writeString(json, """
            {
                "name": "Test Project",
                "description": "A project with Unicode: café, naïve, 日本語",
                "status": "✅ active",
                "tags": ["français", "español", "中文"]
            }
        """.trimIndent(), Charsets.UTF_8)

        assertFalse(BinaryDetector.detectByContent(json))
        assertFalse(BinaryDetector.isBinary(json))
    }

    @Test
    fun `Kotlin source file with Unicode is treated as text`(@TempDir tempDir: Path) {
        val kotlin = tempDir.resolve("Example.kt")
        Files.writeString(kotlin, """
            package com.example

            /**
             * Example class with Unicode characters
             * Supports: français, español, 中文, 日本語
             */
            class Example {
                val greeting = "Hello 👋 World 🌍"
                val bullet = "•"

                fun process() {
                    println("✅ Success")
                }
            }
        """.trimIndent(), Charsets.UTF_8)

        assertFalse(BinaryDetector.detectByContent(kotlin))
        assertFalse(BinaryDetector.isBinary(kotlin))
    }

    @Test
    fun `real markdown file with heavy box drawing chars is treated as text`() {
        // Test with the actual WEB_DASHBOARD_ARCHITECTURE.md file if it exists
        val mdPath = Path.of(System.getProperty("user.dir"), "docs", "WEB_DASHBOARD_ARCHITECTURE.md")
        if (Files.exists(mdPath)) {
            assertFalse(BinaryDetector.detectByContent(mdPath),
                "WEB_DASHBOARD_ARCHITECTURE.md should not be detected as binary by content")
            assertFalse(BinaryDetector.isBinary(mdPath),
                "WEB_DASHBOARD_ARCHITECTURE.md should not be detected as binary overall")
        }
    }
}
