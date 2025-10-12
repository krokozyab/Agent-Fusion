package com.orchestrator.context.chunking

import com.orchestrator.context.domain.ChunkKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PythonChunkerTest {

    @Test
    fun `extracts module and function docstrings`() {
        val code = listOf(
            "\"\"\"Module summary\"\"\"",
            "",
            "def greet(name):",
            "    \"\"\"Greets a person\"\"\"",
            "    return f\"Hello {name}!\""
        ).joinToString("\n")

        val chunker = PythonChunker(maxTokens = 120)
        val chunks = chunker.chunk(code, "app/main.py", "python")

        assertEquals(3, chunks.size)
        assertEquals(ChunkKind.DOCSTRING, chunks[0].kind)
        assertTrue(chunks[0].content.contains("Module summary"))
        assertEquals("Function greet docstring", chunks[1].summary)
        assertEquals(ChunkKind.CODE_FUNCTION, chunks[2].kind)
        assertTrue(chunks[2].content.contains("def greet"))
        assertTrue(chunks[2].content.contains("Greets a person"))
    }

    @Test
    fun `captures classes with decorators`() {
        val code = listOf(
            "from dataclasses import dataclass",
            "",
            "@dataclass",
            "class Widget:",
            "    \"\"\"Widget docs\"\"\"",
            "    value: int",
            "",
            "    def scale(self):",
            "        return self.value * 2"
        ).joinToString("\n")

        val chunker = PythonChunker(maxTokens = 200)
        val chunks = chunker.chunk(code, "app/widget.py", "python")

        assertTrue(chunks.any { it.kind == ChunkKind.DOCSTRING && it.summary == "Class Widget docstring" })
        val classChunk = chunks.first { it.kind == ChunkKind.CODE_CLASS }
        assertTrue(classChunk.content.contains("@dataclass"))
        assertTrue(chunks.any { it.kind == ChunkKind.CODE_FUNCTION && it.summary?.startsWith("Function scale") == true })
    }

    @Test
    fun `splits long functions with overlap`() {
        val code = buildList {
            add("def compute():")
            repeat(80) { add("    result += $it") }
            add("    return result")
        }.joinToString("\n")

        val chunker = PythonChunker(maxTokens = 25)
        val chunks = chunker.chunk(code, "app/calc.py", "python")
        val functionChunks = chunks.filter { it.kind == ChunkKind.CODE_FUNCTION }

        assertTrue(functionChunks.size > 1)
        val lastLinesFirst = functionChunks.first().content.lines().takeLast(2)
        val firstLinesSecond = functionChunks[1].content.lines().take(2)
        assertTrue(lastLinesFirst.any { firstLinesSecond.contains(it) })
    }
}
