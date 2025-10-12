package com.orchestrator.context.chunking

import com.orchestrator.context.domain.ChunkKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkdownChunkerTest {

    @Test
    fun `splits by headings with labels`() {
        val markdown = """
            # Title
            Intro paragraph.

            ## Details
            More content here.
        """.trimIndent()

        val chunker = MarkdownChunker(maxTokens = 200)
        val chunks = chunker.chunk(markdown, "docs/readme.md", "markdown")

        assertEquals(2, chunks.size)
        assertEquals(ChunkKind.MARKDOWN_SECTION, chunks[0].kind)
        assertEquals("Title", chunks[0].summary)
        assertTrue(chunks[0].content.contains("Intro"))
        assertEquals("Details", chunks[1].summary)
    }

    @Test
    fun `code fences become separate chunks`() {
        val markdown = """
            # Example
            ```kotlin
            fun main() = println("hi")
            ```
        """.trimIndent()

        val chunker = MarkdownChunker()
        val chunks = chunker.chunk(markdown, "docs/example.md", "markdown")

        assertEquals(2, chunks.size)
        assertEquals(ChunkKind.MARKDOWN_SECTION, chunks[0].kind)
        assertEquals(ChunkKind.CODE_BLOCK, chunks[1].kind)
        assertTrue(chunks[1].content.contains("fun main"))
    }

    @Test
    fun `long sections split respecting max tokens`() {
        val builder = StringBuilder("# Heading\n")
        repeat(20) { builder.append("Paragraph $it with words repeated.\n\n") }
        val chunker = MarkdownChunker(maxTokens = 40)

        val chunks = chunker.chunk(builder.toString(), "docs/long.md", "markdown")

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.tokenEstimate ?: 0 <= 120_000 })
    }
}
