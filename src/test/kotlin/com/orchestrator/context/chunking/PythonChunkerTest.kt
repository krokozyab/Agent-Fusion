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

    @Test
    fun `empty content returns empty list`() {
        val chunker = PythonChunker()
        val chunks = chunker.chunk("", "test.py", "python")
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `blank content returns empty list`() {
        val chunker = PythonChunker()
        val chunks = chunker.chunk("   \n\n  \n", "test.py", "python")
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `async functions are recognized`() {
        val code = """
            async def fetch_data():
                '''Async function doc'''
                return await api.get()
        """.trimIndent()

        val chunker = PythonChunker()
        val chunks = chunker.chunk(code, "test.py", "python")

        val functionChunk = chunks.find { it.kind == ChunkKind.CODE_FUNCTION }
        assertTrue(functionChunk != null)
        assertTrue(functionChunk!!.content.contains("async def fetch_data"))
    }

    @Test
    fun `nested function inside class`() {
        val code = """
            class Calculator:
                def add(self, a, b):
                    return a + b

                def subtract(self, a, b):
                    return a - b
        """.trimIndent()

        val chunker = PythonChunker()
        val chunks = chunker.chunk(code, "test.py", "python")

        val classChunk = chunks.find { it.kind == ChunkKind.CODE_CLASS }
        val functionChunks = chunks.filter { it.kind == ChunkKind.CODE_FUNCTION }

        assertTrue(classChunk != null)
        assertTrue(functionChunks.size >= 2)
    }

    @Test
    fun `single quote docstrings`() {
        val code = """
            '''Module docstring with single quotes'''

            def test():
                '''Function docstring'''
                pass
        """.trimIndent()

        val chunker = PythonChunker()
        val chunks = chunker.chunk(code, "test.py", "python")

        val docstrings = chunks.filter { it.kind == ChunkKind.DOCSTRING }
        assertTrue(docstrings.size >= 2)
    }

    @Test
    fun `multiline docstrings`() {
        val code = """
            def example():
                '''
                This is a multiline
                docstring with multiple
                lines of text.
                '''
                return True
        """.trimIndent()

        val chunker = PythonChunker()
        val chunks = chunker.chunk(code, "test.py", "python")

        val docstring = chunks.find { it.kind == ChunkKind.DOCSTRING }
        assertTrue(docstring != null)
        assertTrue(docstring!!.content.contains("multiline"))
    }

    @Test
    fun `one-line docstring`() {
        val code = """
            def quick(): '''Quick doc''' ; return 1
        """.trimIndent()

        val chunker = PythonChunker()
        val chunks = chunker.chunk(code, "test.py", "python")

        assertTrue(chunks.isNotEmpty())
    }

    @Test
    fun `function with multiple decorators`() {
        val code = """
            @staticmethod
            @cache
            @validate
            def expensive():
                return compute()
        """.trimIndent()

        val chunker = PythonChunker()
        val chunks = chunker.chunk(code, "test.py", "python")

        val functionChunk = chunks.find { it.kind == ChunkKind.CODE_FUNCTION }
        assertTrue(functionChunk != null)
        assertTrue(functionChunk!!.content.contains("@staticmethod"))
        assertTrue(functionChunk.content.contains("@cache"))
        assertTrue(functionChunk.content.contains("@validate"))
    }

    @Test
    fun `comments are preserved`() {
        val code = """
            # This is a comment
            def test():
                # Another comment
                pass
        """.trimIndent()

        val chunker = PythonChunker()
        val chunks = chunker.chunk(code, "test.py", "python")

        val functionChunk = chunks.find { it.kind == ChunkKind.CODE_FUNCTION }
        assertTrue(functionChunk != null)
        assertTrue(functionChunk!!.content.contains("# Another comment"))
    }

    @Test
    fun `indented class inside function`() {
        val code = """
            def factory():
                class Inner:
                    def method(self):
                        pass
                return Inner
        """.trimIndent()

        val chunker = PythonChunker()
        val chunks = chunker.chunk(code, "test.py", "python")

        val functionChunk = chunks.find { it.kind == ChunkKind.CODE_FUNCTION }
        assertTrue(functionChunk != null)
    }

    @Test
    fun `lambda functions are not chunked separately`() {
        val code = """
            squared = lambda x: x * x
            doubled = lambda y: y * 2
        """.trimIndent()

        val chunker = PythonChunker()
        val chunks = chunker.chunk(code, "test.py", "python")

        // Lambdas should not create function chunks
        val functionChunks = chunks.filter { it.kind == ChunkKind.CODE_FUNCTION }
        assertTrue(functionChunks.isEmpty())
    }

    @Test
    fun `blank lines between functions`() {
        val code = """
            def first():
                pass


            def second():
                pass
        """.trimIndent()

        val chunker = PythonChunker()
        val chunks = chunker.chunk(code, "test.py", "python")

        val functionChunks = chunks.filter { it.kind == ChunkKind.CODE_FUNCTION }
        assertEquals(2, functionChunks.size)
    }

    @Test
    fun `handles indentation with tabs`() {
        val code = "def test():\n\treturn True"

        val chunker = PythonChunker()
        val chunks = chunker.chunk(code, "test.py", "python")

        val functionChunk = chunks.find { it.kind == ChunkKind.CODE_FUNCTION }
        assertTrue(functionChunk != null)
    }

    @Test
    fun `chunk ordinals are sequential`() {
        val code = """
            def first():
                pass

            def second():
                pass

            def third():
                pass
        """.trimIndent()

        val chunker = PythonChunker()
        val chunks = chunker.chunk(code, "test.py", "python")

        chunks.forEachIndexed { index, chunk ->
            assertEquals(index, chunk.ordinal)
        }
    }

    @Test
    fun `line numbers are tracked correctly`() {
        val code = """
            def first():
                pass

            def second():
                pass
        """.trimIndent()

        val chunker = PythonChunker()
        val chunks = chunker.chunk(code, "test.py", "python")

        chunks.forEach { chunk ->
            assertTrue(chunk.startLine != null)
            assertTrue(chunk.endLine != null)
            assertTrue(chunk.startLine!! <= chunk.endLine!!)
        }
    }

    @Test
    fun `strategy metadata is correct`() {
        val chunker = PythonChunker()
        val strategy = chunker.strategy

        assertEquals("python", strategy.id)
        assertEquals("Python Structure", strategy.displayName)
        assertTrue(strategy.supportedLanguages.contains("python"))
        assertTrue(strategy.supportedLanguages.contains("py"))
        assertEquals(600, strategy.defaultMaxTokens)
    }

    @Test
    fun `estimateTokens returns positive values`() {
        val chunker = PythonChunker()
        val text = "def example(): return True"
        val tokens = chunker.estimateTokens(text)

        assertTrue(tokens > 0)
        assertTrue(tokens < text.length)
    }

    @Test
    fun `class with no methods`() {
        val code = """
            class EmptyClass:
                pass
        """.trimIndent()

        val chunker = PythonChunker()
        val chunks = chunker.chunk(code, "test.py", "python")

        val classChunk = chunks.find { it.kind == ChunkKind.CODE_CLASS }
        assertTrue(classChunk != null)
    }

    @Test
    fun `function with type hints`() {
        val code = """
            def add(a: int, b: int) -> int:
                '''Adds two numbers'''
                return a + b
        """.trimIndent()

        val chunker = PythonChunker()
        val chunks = chunker.chunk(code, "test.py", "python")

        val functionChunk = chunks.find { it.kind == ChunkKind.CODE_FUNCTION }
        assertTrue(functionChunk != null)
        assertTrue(functionChunk!!.content.contains("-> int"))
    }

    @Test
    fun `very long class stress test`() {
        val methods = List(50) { i ->
            """
            def method_$i(self):
                '''Method $i docstring'''
                return $i
            """.trimIndent()
        }
        val code = "class BigClass:\n    pass\n\n" + methods.joinToString("\n\n")

        val chunker = PythonChunker()
        val chunks = chunker.chunk(code, "huge.py", "python")

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { (it.tokenEstimate ?: 0) > 0 })
        assertTrue(chunks.all { it.startLine != null && it.endLine != null })
    }

    @Test
    fun `function with default arguments`() {
        val code = """
            def greet(name="World", greeting="Hello"):
                return f"{greeting}, {name}!"
        """.trimIndent()

        val chunker = PythonChunker()
        val chunks = chunker.chunk(code, "test.py", "python")

        val functionChunk = chunks.find { it.kind == ChunkKind.CODE_FUNCTION }
        assertTrue(functionChunk != null)
        assertTrue(functionChunk!!.content.contains("name=\"World\""))
    }

    @Test
    fun `class with inheritance`() {
        val code = """
            class Child(Parent, Mixin):
                def __init__(self):
                    super().__init__()
        """.trimIndent()

        val chunker = PythonChunker()
        val chunks = chunker.chunk(code, "test.py", "python")

        val classChunk = chunks.find { it.kind == ChunkKind.CODE_CLASS }
        assertTrue(classChunk != null)
        assertTrue(classChunk!!.content.contains("(Parent, Mixin)"))
    }

    @Test
    fun `unclosed docstring is handled`() {
        val code = """
            def broken():
                '''This docstring never closes
                return "oops"
        """.trimIndent()

        val chunker = PythonChunker()
        val chunks = chunker.chunk(code, "test.py", "python")

        // Should handle gracefully without crashing
        assertTrue(chunks.isNotEmpty())
    }

    @Test
    fun `mixed indentation levels`() {
        val code = """
            def outer():
                def inner():
                    def innermost():
                        pass
        """.trimIndent()

        val chunker = PythonChunker()
        val chunks = chunker.chunk(code, "test.py", "python")

        val functionChunk = chunks.find { it.kind == ChunkKind.CODE_FUNCTION }
        assertTrue(functionChunk != null)
    }
}
