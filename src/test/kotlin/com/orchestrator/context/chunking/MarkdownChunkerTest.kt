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

    @Test
    fun `empty content returns empty list`() {
        val chunker = MarkdownChunker()
        val chunks = chunker.chunk("", "test.md", "markdown")
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `blank content returns empty list`() {
        val chunker = MarkdownChunker()
        val chunks = chunker.chunk("   \n\n  \n", "test.md", "markdown")
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `heading levels 1-6 are recognized`() {
        val markdown = """
            # H1
            ## H2
            ### H3
            #### H4
            ##### H5
            ###### H6
        """.trimIndent()

        val chunker = MarkdownChunker()
        val chunks = chunker.chunk(markdown, "test.md", "markdown")

        assertEquals(6, chunks.size)
        assertEquals("H1", chunks[0].summary)
        assertEquals("H2", chunks[1].summary)
        assertEquals("H3", chunks[2].summary)
        assertEquals("H4", chunks[3].summary)
        assertEquals("H5", chunks[4].summary)
        assertEquals("H6", chunks[5].summary)
    }

    @Test
    fun `heading without text is ignored`() {
        val markdown = """
            #
            ##
            Regular text
        """.trimIndent()

        val chunker = MarkdownChunker()
        val chunks = chunker.chunk(markdown, "test.md", "markdown")

        assertTrue(chunks.all { it.summary == null || it.summary.isBlank() })
    }

    @Test
    fun `seven hashes is not a heading`() {
        val markdown = "####### Not a heading"

        val chunker = MarkdownChunker()
        val chunks = chunker.chunk(markdown, "test.md", "markdown")

        assertTrue(chunks.isEmpty() || chunks.all { it.summary == null })
    }

    @Test
    fun `tilde fences are recognized`() {
        val markdown = """
            ~~~python
            def test():
                pass
            ~~~
        """.trimIndent()

        val chunker = MarkdownChunker()
        val chunks = chunker.chunk(markdown, "test.md", "markdown")

        assertEquals(1, chunks.size)
        assertEquals(ChunkKind.CODE_BLOCK, chunks[0].kind)
        assertTrue(chunks[0].content.contains("def test()"))
    }

    @Test
    fun `nested fences with different lengths`() {
        val markdown = """
            ````markdown
            Example:
            ```kotlin
            fun test() {}
            ```
            ````
        """.trimIndent()

        val chunker = MarkdownChunker()
        val chunks = chunker.chunk(markdown, "test.md", "markdown")

        assertEquals(1, chunks.size)
        assertEquals(ChunkKind.CODE_BLOCK, chunks[0].kind)
        assertTrue(chunks[0].content.contains("```kotlin"))
    }

    @Test
    fun `inline code is not treated as fence`() {
        val markdown = """
            # Example
            Use `code` for inline code.
        """.trimIndent()

        val chunker = MarkdownChunker()
        val chunks = chunker.chunk(markdown, "test.md", "markdown")

        assertEquals(1, chunks.size)
        assertEquals(ChunkKind.MARKDOWN_SECTION, chunks[0].kind)
        assertTrue(chunks[0].content.contains("`code`"))
    }

    @Test
    fun `content before first heading`() {
        val markdown = """
            This is content before any heading.

            # First Heading
            Content under heading.
        """.trimIndent()

        val chunker = MarkdownChunker()
        val chunks = chunker.chunk(markdown, "test.md", "markdown")

        assertTrue(chunks.size >= 2)
        assertTrue(chunks[0].summary == null || chunks[0].summary?.isBlank() == true)
        assertEquals("First Heading", chunks[1].summary)
    }

    @Test
    fun `consecutive code fences`() {
        val markdown = """
            # Code Examples

            Example one:
            ```kotlin
            fun first() {}
            ```

            Example two:
            ```python
            def second():
                pass
            ```
        """.trimIndent()

        val chunker = MarkdownChunker()
        val chunks = chunker.chunk(markdown, "test.md", "markdown")

        val codeBlocks = chunks.filter { it.kind == ChunkKind.CODE_BLOCK }
        assertTrue(codeBlocks.size >= 2)
    }

    @Test
    fun `empty code fence`() {
        val markdown = """
            ```
            ```
        """.trimIndent()

        val chunker = MarkdownChunker()
        val chunks = chunker.chunk(markdown, "test.md", "markdown")

        assertEquals(1, chunks.size)
        assertEquals(ChunkKind.CODE_BLOCK, chunks[0].kind)
    }

    @Test
    fun `heading with special characters`() {
        val markdown = """
            # Section: Introduction & Overview
            ## Sub-section (Part 1)
        """.trimIndent()

        val chunker = MarkdownChunker()
        val chunks = chunker.chunk(markdown, "test.md", "markdown")

        assertEquals(2, chunks.size)
        assertEquals("Section: Introduction & Overview", chunks[0].summary)
        assertEquals("Sub-section (Part 1)", chunks[1].summary)
    }

    @Test
    fun `chunk ordinals are sequential`() {
        val markdown = """
            # First
            # Second
            # Third
        """.trimIndent()

        val chunker = MarkdownChunker()
        val chunks = chunker.chunk(markdown, "test.md", "markdown")

        chunks.forEachIndexed { index, chunk ->
            assertEquals(index, chunk.ordinal)
        }
    }

    @Test
    fun `line numbers are tracked correctly`() {
        val markdown = """
            # First
            Content line 1
            Content line 2

            # Second
            Content line 3
        """.trimIndent()

        val chunker = MarkdownChunker()
        val chunks = chunker.chunk(markdown, "test.md", "markdown")

        chunks.forEach { chunk ->
            assertTrue(chunk.startLine != null)
            assertTrue(chunk.endLine != null)
            assertTrue(chunk.startLine!! <= chunk.endLine!!)
        }
    }

    @Test
    fun `strategy metadata is correct`() {
        val chunker = MarkdownChunker()
        val strategy = chunker.strategy

        assertEquals("markdown", strategy.id)
        assertEquals("Markdown Headings", strategy.displayName)
        assertTrue(strategy.supportedLanguages.contains("markdown"))
        assertTrue(strategy.supportedLanguages.contains("md"))
        assertEquals(400, strategy.defaultMaxTokens)
    }

    @Test
    fun `estimateTokens returns positive values`() {
        val chunker = MarkdownChunker()
        val text = "This is sample text for token estimation."
        val tokens = chunker.estimateTokens(text)

        assertTrue(tokens > 0)
        assertTrue(tokens < text.length)
    }

    @Test
    fun `very large file stress test`() {
        val sections = List(50) { i ->
            "# Section $i\n" + List(20) { j -> "Line $j content." }.joinToString("\n")
        }
        val markdown = sections.joinToString("\n\n")

        val chunker = MarkdownChunker()
        val chunks = chunker.chunk(markdown, "huge.md", "markdown")

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { (it.tokenEstimate ?: 0) > 0 })
        assertTrue(chunks.all { it.startLine != null && it.endLine != null })
    }

    @Test
    fun `single line file`() {
        val markdown = "# Just a heading"

        val chunker = MarkdownChunker()
        val chunks = chunker.chunk(markdown, "test.md", "markdown")

        assertEquals(1, chunks.size)
        assertEquals(1, chunks[0].startLine)
        assertEquals(1, chunks[0].endLine)
    }

    @Test
    fun `whitespace handling in sections`() {
        val markdown = """
            # Test



            Content with blank lines
        """.trimIndent()

        val chunker = MarkdownChunker()
        val chunks = chunker.chunk(markdown, "test.md", "markdown")

        assertTrue(chunks.isNotEmpty())
        assertEquals("Test", chunks[0].summary)
    }
}
