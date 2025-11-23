package com.orchestrator.context.chunking

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WordChunkerTest {

    private fun List<Chunk>.withoutRoot() = filterNot { it.summary == "Document root" }

    @Test
    fun `empty content returns empty list`() {
        val chunker = WordChunker()
        val chunks = chunker.chunk("", "test.docx", "docx")
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `blank content returns empty list`() {
        val chunker = WordChunker()
        val chunks = chunker.chunk("   \n\n  \n", "test.docx", "docx")
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `single paragraph creates one chunk with correct line numbers`() {
        val text = "This is a single paragraph."
        val chunker = WordChunker()
        val chunks = chunker.chunk(text, "test.docx", "docx").withoutRoot()

        assertEquals(1, chunks.size)
        assertEquals(ChunkKind.PARAGRAPH, chunks[0].kind)
        assertEquals(text, chunks[0].content)
        assertEquals(1, chunks[0].startLine)
        assertEquals(1, chunks[0].endLine)
    }

    @Test
    fun `multi-line paragraph tracks correct line span`() {
        val text = """Line 1
Line 2
Line 3"""
        val chunker = WordChunker()
        val chunks = chunker.chunk(text, "test.docx", "docx").withoutRoot()

        assertEquals(1, chunks.size)
        assertEquals(1, chunks[0].startLine)
        assertEquals(3, chunks[0].endLine)
        assertEquals("Line 1\nLine 2\nLine 3", chunks[0].content)
    }

    @Test
    fun `multiple paragraphs separated by blank lines have correct line numbers`() {
        val text = """First paragraph.

Second paragraph.

Third paragraph."""
        val chunker = WordChunker()
        val chunks = chunker.chunk(text, "test.docx", "docx").withoutRoot()

        assertEquals(3, chunks.size)

        assertEquals("First paragraph.", chunks[0].content)
        assertEquals(1, chunks[0].startLine)
        assertEquals(1, chunks[0].endLine)

        assertEquals("Second paragraph.", chunks[1].content)
        assertEquals(3, chunks[1].startLine)
        assertEquals(3, chunks[1].endLine)

        assertEquals("Third paragraph.", chunks[2].content)
        assertEquals(5, chunks[2].startLine)
        assertEquals(5, chunks[2].endLine)
    }

    @Test
    fun `multiple paragraphs with multi-line content track line spans correctly`() {
        val text = """First line
Second line

Third line
Fourth line

Fifth line"""
        val chunker = WordChunker()
        val chunks = chunker.chunk(text, "test.docx", "docx").withoutRoot()

        assertEquals(3, chunks.size)

        assertEquals("First line\nSecond line", chunks[0].content)
        assertEquals(1, chunks[0].startLine)
        assertEquals(2, chunks[0].endLine)

        assertEquals("Third line\nFourth line", chunks[1].content)
        assertEquals(4, chunks[1].startLine)
        assertEquals(5, chunks[1].endLine)

        assertEquals("Fifth line", chunks[2].content)
        assertEquals(7, chunks[2].startLine)
        assertEquals(7, chunks[2].endLine)
    }

    @Test
    fun `paragraphs separated by multiple blank lines`() {
        val text = """First paragraph.


Second paragraph."""
        val chunker = WordChunker()
        val chunks = chunker.chunk(text, "test.docx", "docx").withoutRoot()

        assertEquals(2, chunks.size)
        assertEquals(1, chunks[0].startLine)
        assertEquals(1, chunks[0].endLine)
        assertEquals(4, chunks[1].startLine)
        assertEquals(4, chunks[1].endLine)
    }

    @Test
    fun `chunk ordinals are sequential`() {
        val text = """First paragraph.

Second paragraph.

Third paragraph."""
        val chunker = WordChunker()
        val chunks = chunker.chunk(text, "test.docx", "docx").withoutRoot()

        chunks.forEachIndexed { index, chunk ->
            assertEquals(index + 1, chunk.ordinal)
        }
    }

    @Test
    fun `all chunks have PARAGRAPH kind`() {
        val text = """First paragraph.

Second paragraph."""
        val chunker = WordChunker()
        val chunks = chunker.chunk(text, "test.docx", "docx")

        chunks.forEach {
            assertEquals(ChunkKind.PARAGRAPH, it.kind)
        }
    }

    @Test
    fun `token estimation is consistent`() {
        val chunker = WordChunker()
        val text = "This is sample text for testing."
        val tokens = chunker.estimateTokens(text)

        assertTrue(tokens > 0)
        assertEquals(text.length / 4, tokens)
    }

    @Test
    fun `strategy metadata is correct`() {
        val chunker = WordChunker()
        val strategy = chunker.strategy

        assertEquals("word", strategy.id)
        assertEquals("Word Document Chunker", strategy.displayName)
    }

    @Test
    fun `summary field is null for word chunks`() {
        val text = "Test paragraph."
        val chunker = WordChunker()
        val chunks = chunker.chunk(text, "test.docx", "docx").withoutRoot()

        chunks.forEach {
            assertEquals(null, it.summary)
        }
    }

    @Test
    fun `handles single line file`() {
        val text = "Single line of text."
        val chunker = WordChunker()
        val chunks = chunker.chunk(text, "test.docx", "docx").withoutRoot()

        assertEquals(1, chunks.size)
        assertEquals(text, chunks[0].content)
        assertEquals(1, chunks[0].startLine)
        assertEquals(1, chunks[0].endLine)
    }

    @Test
    fun `handles Unicode characters`() {
        val text = "Hello 世界! This is a test with émojis and special chars: ñ, ü, ö."
        val chunker = WordChunker()
        val chunks = chunker.chunk(text, "test.docx", "docx").withoutRoot()

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].content.contains("世界"))
    }

    @Test
    fun `chunks have non-null timestamps`() {
        val text = "Test paragraph."
        val chunker = WordChunker()
        val chunks = chunker.chunk(text, "test.docx", "docx").withoutRoot()

        chunks.forEach {
            assertTrue(it.createdAt != null)
        }
    }

    @Test
    fun `complex multiline paragraphs with varying sizes`() {
        val text = """A short line.
A second short line.

A longer paragraph that spans
multiple lines and has more content
to test the line counting.

Final short paragraph."""

        val chunker = WordChunker()
        val chunks = chunker.chunk(text, "test.docx", "docx").withoutRoot()

        assertEquals(3, chunks.size)

        assertEquals(1, chunks[0].startLine)
        assertEquals(2, chunks[0].endLine)

        assertEquals(4, chunks[1].startLine)
        assertEquals(6, chunks[1].endLine)

        assertEquals(8, chunks[2].startLine)
        assertEquals(8, chunks[2].endLine)
    }

    @Test
    fun `handles text with only newlines`() {
        val text = "Line one\nLine two\nLine three"
        val chunker = WordChunker()
        val chunks = chunker.chunk(text, "test.docx", "docx").withoutRoot()

        assertEquals(1, chunks.size)
        assertEquals("Line one\nLine two\nLine three", chunks[0].content)
        assertEquals(1, chunks[0].startLine)
        assertEquals(3, chunks[0].endLine)
    }
}
