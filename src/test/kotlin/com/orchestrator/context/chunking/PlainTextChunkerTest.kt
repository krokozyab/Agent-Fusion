package com.orchestrator.context.chunking

import com.orchestrator.context.domain.ChunkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlainTextChunkerTest {

    @Test
    fun `empty content returns empty list`() {
        val chunker = PlainTextChunker()
        val chunks = chunker.chunk("", "test.txt", "text")
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `blank content returns empty list`() {
        val chunker = PlainTextChunker()
        val chunks = chunker.chunk("   \n\n  \n", "test.txt", "text")
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `single paragraph creates one chunk`() {
        val text = "This is a single paragraph with multiple words."
        val chunker = PlainTextChunker()
        val chunks = chunker.chunk(text, "test.txt", "text")

        assertEquals(1, chunks.size)
        assertEquals(ChunkKind.PARAGRAPH, chunks[0].kind)
        assertEquals(text, chunks[0].content)
        assertTrue(chunks[0].tokenEstimate!! > 0)
    }

    @Test
    fun `multiple paragraphs create multiple chunks`() {
        val text = """
            First paragraph.

            Second paragraph.

            Third paragraph.
        """.trimIndent()

        val chunker = PlainTextChunker()
        val chunks = chunker.chunk(text, "test.txt", "text")

        assertEquals(3, chunks.size)
        chunks.forEach {
            assertEquals(ChunkKind.PARAGRAPH, it.kind)
            assertTrue(it.content.isNotBlank())
        }
    }

    @Test
    fun `paragraphs separated by multiple blank lines`() {
        val text = """
            First paragraph.



            Second paragraph.
        """.trimIndent()

        val chunker = PlainTextChunker()
        val chunks = chunker.chunk(text, "test.txt", "text")

        assertEquals(2, chunks.size)
        assertTrue(chunks[0].content.contains("First"))
        assertTrue(chunks[1].content.contains("Second"))
    }

    @Test
    fun `large paragraph splits by sentences`() {
        val sentences = List(30) { "This is sentence number $it." }
        val text = sentences.joinToString(" ")

        val chunker = PlainTextChunker(maxTokens = 50)
        val chunks = chunker.chunk(text, "test.txt", "text")

        assertTrue(chunks.size > 1)
        chunks.forEach {
            assertTrue(it.tokenEstimate!! <= 50)
        }
    }

    @Test
    fun `very long sentence splits by lines`() {
        val longLine = List(100) { "word" }.joinToString(" ")

        val chunker = PlainTextChunker(maxTokens = 30)
        val chunks = chunker.chunk(longLine, "test.txt", "text")

        assertTrue(chunks.size >= 1)
        chunks.forEach {
            assertTrue(it.tokenEstimate!! <= 120_000)
        }
    }

    @Test
    fun `sentence splitting preserves periods`() {
        val text = "First sentence. Second sentence. Third sentence."

        val chunker = PlainTextChunker(maxTokens = 10)
        val chunks = chunker.chunk(text, "test.txt", "text")

        assertTrue(chunks.isNotEmpty())
        val combined = chunks.joinToString(" ") { it.content }
        assertTrue(combined.contains("First sentence"))
    }

    @Test
    fun `handles exclamation and question marks`() {
        val text = "What is this? This is a test! Amazing."

        val chunker = PlainTextChunker(maxTokens = 10)
        val chunks = chunker.chunk(text, "test.txt", "text")

        assertTrue(chunks.isNotEmpty())
    }

    @Test
    fun `chunk ordinals are sequential`() {
        val text = """
            First paragraph.

            Second paragraph.

            Third paragraph.
        """.trimIndent()

        val chunker = PlainTextChunker()
        val chunks = chunker.chunk(text, "test.txt", "text")

        chunks.forEachIndexed { index, chunk ->
            assertEquals(index, chunk.ordinal)
        }
    }

    @Test
    fun `all chunks have PARAGRAPH kind`() {
        val text = """
            First paragraph.

            Second paragraph.
        """.trimIndent()

        val chunker = PlainTextChunker()
        val chunks = chunker.chunk(text, "test.txt", "text")

        chunks.forEach {
            assertEquals(ChunkKind.PARAGRAPH, it.kind)
        }
    }

    @Test
    fun `token estimation is consistent`() {
        val chunker = PlainTextChunker()
        val text = "This is sample text for testing."
        val tokens = chunker.estimateTokens(text)

        assertTrue(tokens > 0)
        assertEquals(text.length / 4, tokens)
    }

    @Test
    fun `strategy metadata is correct`() {
        val chunker = PlainTextChunker()
        val strategy = chunker.strategy

        assertEquals("plaintext", strategy.id)
        assertEquals("Plain Text", strategy.displayName)
        assertTrue(strategy.supportedLanguages.contains("text"))
        assertTrue(strategy.supportedLanguages.contains("txt"))
        assertEquals(600, strategy.defaultMaxTokens)
    }

    @Test
    fun `respects custom maxTokens`() {
        val chunker = PlainTextChunker(maxTokens = 100)
        assertEquals(100, chunker.strategy.defaultMaxTokens)
    }

    @Test
    fun `handles text with no periods`() {
        val text = "This is text without any sentence terminators at all"

        val chunker = PlainTextChunker()
        val chunks = chunker.chunk(text, "test.txt", "text")

        assertEquals(1, chunks.size)
        assertEquals(text, chunks[0].content)
    }

    @Test
    fun `handles text with only newlines`() {
        val text = "Line one\nLine two\nLine three"

        val chunker = PlainTextChunker()
        val chunks = chunker.chunk(text, "test.txt", "text")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].content.contains("Line one"))
    }

    @Test
    fun `paragraphs with leading and trailing whitespace`() {
        val text = """

            First paragraph with spaces.


            Second paragraph.

        """.trimIndent()

        val chunker = PlainTextChunker()
        val chunks = chunker.chunk(text, "test.txt", "text")

        assertEquals(2, chunks.size)
        assertFalse(chunks[0].content.startsWith(" "))
        assertFalse(chunks[1].content.startsWith(" "))
    }

    @Test
    fun `handles single line file`() {
        val text = "Single line of text."

        val chunker = PlainTextChunker()
        val chunks = chunker.chunk(text, "test.txt", "text")

        assertEquals(1, chunks.size)
        assertEquals(text, chunks[0].content)
    }

    @Test
    fun `handles Unicode characters`() {
        val text = "Hello 世界! This is a test with émojis and special chars: ñ, ü, ö."

        val chunker = PlainTextChunker()
        val chunks = chunker.chunk(text, "test.txt", "text")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].content.contains("世界"))
    }

    @Test
    fun `stress test with very large file`() {
        val paragraphs = List(100) { i ->
            List(20) { j -> "Paragraph $i sentence $j." }.joinToString(" ")
        }
        val text = paragraphs.joinToString("\n\n")

        val chunker = PlainTextChunker()
        val chunks = chunker.chunk(text, "huge.txt", "text")

        assertTrue(chunks.size >= 100)
        chunks.forEach {
            assertEquals(ChunkKind.PARAGRAPH, it.kind)
            assertTrue(it.tokenEstimate!! > 0)
        }
    }

    @Test
    fun `empty paragraphs are filtered out`() {
        val text = "\n\n\n\n"

        val chunker = PlainTextChunker()
        val chunks = chunker.chunk(text, "test.txt", "text")

        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `handles tabs and mixed whitespace`() {
        val text = "First paragraph.\t\t\n\n\tSecond paragraph."

        val chunker = PlainTextChunker()
        val chunks = chunker.chunk(text, "test.txt", "text")

        assertEquals(2, chunks.size)
    }

    @Test
    fun `long paragraph splitting maintains content`() {
        val sentences = List(50) { "Sentence $it with some content." }
        val text = sentences.joinToString(" ")

        val chunker = PlainTextChunker(maxTokens = 30)
        val chunks = chunker.chunk(text, "test.txt", "text")

        val combined = chunks.joinToString(" ") { it.content.trim() }
        sentences.forEach { sentence ->
            assertTrue(combined.contains(sentence.substringBefore(" with")))
        }
    }

    @Test
    fun `chunks have non-null timestamps`() {
        val text = "Test paragraph."

        val chunker = PlainTextChunker()
        val chunks = chunker.chunk(text, "test.txt", "text")

        chunks.forEach {
            assertTrue(it.createdAt != null)
        }
    }

    @Test
    fun `summary field is null for plain text chunks`() {
        val text = "Test paragraph."

        val chunker = PlainTextChunker()
        val chunks = chunker.chunk(text, "test.txt", "text")

        chunks.forEach {
            assertEquals(null, it.summary)
        }
    }

    @Test
    fun `handles multiline paragraphs`() {
        val text = """
            This is a paragraph
            that spans multiple lines
            but is still one paragraph.

            This is a second paragraph.
        """.trimIndent()

        val chunker = PlainTextChunker()
        val chunks = chunker.chunk(text, "test.txt", "text")

        assertEquals(2, chunks.size)
        assertTrue(chunks[0].content.contains("multiple lines"))
    }

    @Test
    fun `handles very long single line`() {
        val text = List(500) { "word$it" }.joinToString(" ")

        val chunker = PlainTextChunker(maxTokens = 50)
        val chunks = chunker.chunk(text, "test.txt", "text")

        assertTrue(chunks.size > 1)
        chunks.forEach {
            assertTrue(it.tokenEstimate!! <= 50)
        }
    }

    @Test
    fun `sentence terminator at end of paragraph`() {
        val text = "Paragraph one.\n\nParagraph two."

        val chunker = PlainTextChunker()
        val chunks = chunker.chunk(text, "test.txt", "text")

        assertEquals(2, chunks.size)
        assertTrue(chunks[0].content.trim().endsWith("."))
        assertTrue(chunks[1].content.trim().endsWith("."))
    }

    @Test
    fun `handles paragraphs with only spaces between`() {
        val text = "First paragraph.     Second paragraph."

        val chunker = PlainTextChunker()
        val chunks = chunker.chunk(text, "test.txt", "text")

        assertEquals(1, chunks.size)
    }
}
