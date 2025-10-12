package com.orchestrator.context.chunking

import com.orchestrator.context.domain.ChunkKind
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlainTextChunkerTest {
    
    private val chunker = PlainTextChunker()
    
    @Test
    fun `chunks text by paragraphs`() {
        val text = """
            First paragraph with some text.
            It has multiple lines.
            
            Second paragraph here.
            Also with multiple lines.
            
            Third paragraph.
        """.trimIndent()
        
        val chunks = chunker.chunk(text, "file.txt", "text")
        
        assertEquals(3, chunks.size)
        chunks.forEach { chunk ->
            assertEquals(ChunkKind.PARAGRAPH, chunk.kind)
            assertNull(chunk.summary)
        }
        
        assertTrue(chunks[0].content.contains("First paragraph"))
        assertTrue(chunks[1].content.contains("Second paragraph"))
        assertTrue(chunks[2].content.contains("Third paragraph"))
    }
    
    @Test
    fun `handles single paragraph`() {
        val text = "This is a single paragraph with some text."
        
        val chunks = chunker.chunk(text, "file.txt", "text")
        
        assertEquals(1, chunks.size)
        assertEquals(ChunkKind.PARAGRAPH, chunks[0].kind)
        assertEquals(text, chunks[0].content)
    }
    
    @Test
    fun `handles empty text`() {
        val text = ""
        
        val chunks = chunker.chunk(text, "file.txt", "text")
        
        assertEquals(0, chunks.size)
    }
    
    @Test
    fun `handles blank text`() {
        val text = "   \n\n   \n   "
        
        val chunks = chunker.chunk(text, "file.txt", "text")
        
        assertEquals(0, chunks.size)
    }
    
    @Test
    fun `splits large paragraphs`() {
        val largeParagraph = buildString {
            repeat(300) {
                append("This is sentence $it. ")
            }
        }
        
        val chunks = chunker.chunk(largeParagraph, "file.txt", "text")
        
        assertTrue(chunks.size > 1)
        chunks.forEach { chunk ->
            assertEquals(ChunkKind.PARAGRAPH, chunk.kind)
            assertTrue(chunk.tokenEstimate!! <= 600)
        }
    }
    
    @Test
    fun `respects token limit`() {
        val text = buildString {
            repeat(10) { i ->
                repeat(100) { j ->
                    append("Word$i$j ")
                }
                append("\n\n")
            }
        }
        
        val chunks = chunker.chunk(text, "file.txt", "text")
        
        assertTrue(chunks.isNotEmpty())
        chunks.forEach { chunk ->
            assertTrue(chunk.tokenEstimate!! <= 600, 
                "Chunk has ${chunk.tokenEstimate} tokens, expected <= 600")
        }
    }
    
    @Test
    fun `handles multiple blank lines between paragraphs`() {
        val text = """
            First paragraph.
            
            
            
            Second paragraph.
        """.trimIndent()
        
        val chunks = chunker.chunk(text, "file.txt", "text")
        
        assertEquals(2, chunks.size)
        assertTrue(chunks[0].content.contains("First"))
        assertTrue(chunks[1].content.contains("Second"))
    }
    
    @Test
    fun `trims whitespace from paragraphs`() {
        val text = """
            
              First paragraph with leading spaces.  
            
            
              Second paragraph.  
            
        """.trimIndent()
        
        val chunks = chunker.chunk(text, "file.txt", "text")
        
        assertEquals(2, chunks.size)
        assertFalse(chunks[0].content.startsWith(" "))
        assertFalse(chunks[1].content.startsWith(" "))
    }
    
    @Test
    fun `sets ordinal correctly`() {
        val text = """
            First.
            
            Second.
            
            Third.
        """.trimIndent()
        
        val chunks = chunker.chunk(text, "file.txt", "text")
        
        assertEquals(3, chunks.size)
        assertEquals(0, chunks[0].ordinal)
        assertEquals(1, chunks[1].ordinal)
        assertEquals(2, chunks[2].ordinal)
    }
    
    @Test
    fun `handles text without paragraph breaks`() {
        val text = "Line 1\nLine 2\nLine 3\nLine 4"
        
        val chunks = chunker.chunk(text, "file.txt", "text")
        
        assertEquals(1, chunks.size)
        assertEquals(text, chunks[0].content)
    }
    
    @Test
    fun `splits by sentences when paragraph too large`() {
        val largeParagraph = buildString {
            repeat(200) {
                append("Sentence $it with some words. ")
            }
        }
        
        val chunks = chunker.chunk(largeParagraph, "file.txt", "text")
        
        assertTrue(chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue(chunk.tokenEstimate!! <= 600)
            assertEquals(ChunkKind.PARAGRAPH, chunk.kind)
        }
    }
}
