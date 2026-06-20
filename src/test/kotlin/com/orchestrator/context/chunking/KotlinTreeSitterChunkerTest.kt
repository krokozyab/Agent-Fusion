package com.orchestrator.context.chunking

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KotlinTreeSitterChunkerTest {

    private val chunker = KotlinTreeSitterChunker(overlapPercent = 0)

    @Test
    fun `splits top-level declarations with real line ranges`() {
        val src = """
            package com.example

            import kotlin.math.sqrt

            class Foo(val x: Int) {
                fun bar(): Int = x * 2
            }

            fun topLevel(a: Int): Int {
                return a + 1
            }
        """.trimIndent()

        val chunks = chunker.chunk(src, "Foo.kt")

        // Header (package + imports), the class, and the top-level function.
        assertTrue(chunks.any { it.kind == ChunkKind.CODE_HEADER }, "expected a header chunk")

        val cls = chunks.firstOrNull { it.kind == ChunkKind.CODE_CLASS && it.content.contains("class Foo") }
        assertNotNull(cls, "expected a CODE_CLASS chunk for Foo")
        assertEquals(5, cls.startLine, "class should start on line 5")
        assertEquals(7, cls.endLine, "class should end on line 7 (closing brace)")
        assertTrue(cls.content.contains("fun bar()"), "class chunk must contain its body")

        val fn = chunks.firstOrNull { it.kind == ChunkKind.CODE_FUNCTION && it.content.contains("fun topLevel") }
        assertNotNull(fn, "expected a CODE_FUNCTION chunk for topLevel")
        assertEquals(9, fn.startLine, "function should start on line 9")
        assertEquals(11, fn.endLine, "function should end on line 11")

        // Line ranges must be valid (Chunk requires 1 <= startLine <= endLine).
        chunks.forEach { c ->
            assertNotNull(c.startLine); assertNotNull(c.endLine)
            assertTrue(c.startLine!! >= 1 && c.startLine!! <= c.endLine!!, "invalid range in $c")
        }
    }

    @Test
    fun `handles unicode in comments without shifting boundaries (utf-8 byte offsets)`() {
        // A multibyte comment before the function would shift char vs byte offsets if mishandled.
        val src = """
            // Привет 世界 — заголовок
            fun greet(): String = "hi"
        """.trimIndent()

        val chunks = chunker.chunk(src, "Greet.kt")
        val fn = chunks.firstOrNull { it.kind == ChunkKind.CODE_FUNCTION }
        assertNotNull(fn, "expected a function chunk")
        assertTrue(fn.content.contains("fun greet()"), "function chunk must capture the declaration intact")
    }

    @Test
    fun `falls back to heuristic chunker when nothing usable is parsed`() {
        // No declarations at all — chunker must defer to the fallback rather than return nothing.
        val markerChunk = Chunk(
            id = 0, fileId = 0, ordinal = 0, kind = ChunkKind.CODE_BLOCK,
            startLine = 1, endLine = 1, tokenEstimate = 1, content = "FALLBACK", summary = null,
            createdAt = java.time.Instant.now()
        )
        val spyFallback = object : SimpleChunker {
            var called = false
            override fun chunk(content: String, filePath: String): List<Chunk> {
                called = true
                return listOf(markerChunk)
            }
        }
        val c = KotlinTreeSitterChunker(overlapPercent = 0, fallback = spyFallback)

        val result = c.chunk("// just a comment, no declarations\n", "Empty.kt")

        assertTrue(spyFallback.called, "fallback must be invoked when no declarations are found")
        assertEquals(listOf(markerChunk), result)
    }
}
