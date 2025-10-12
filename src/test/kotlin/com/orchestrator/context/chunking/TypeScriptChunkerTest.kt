package com.orchestrator.context.chunking

import com.orchestrator.context.domain.ChunkKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TypeScriptChunkerTest {

    @Test
    fun `extracts exports with imports and jsdoc`() {
        val code = """
            import foo from './foo'
            import { bar } from './bar'

            /** Greets a user */
            export function greet(name: string): string {
                return `Hello ${'$'}{name}`
            }

            /** Widget utilities */
            export class Widget {
                constructor(private readonly value: number) {}

                public scale() {
                    return this.value * 2
                }
            }

            export const ANSWER = 42;
        """.trimIndent()

        val chunker = TypeScriptChunker(maxTokens = 400)
        val chunks = chunker.chunk(code, "src/app.ts", "typescript")

        assertEquals(3, chunks.size)
        val functionChunk = chunks[0]
        assertEquals(ChunkKind.CODE_FUNCTION, functionChunk.kind)
        assertEquals("Function greet", functionChunk.summary)
        assertTrue(functionChunk.content.startsWith("import foo"))
        assertTrue(functionChunk.content.contains("/** Greets a user */"))

        val classChunk = chunks[1]
        assertEquals("Class Widget", classChunk.summary)
        assertTrue(classChunk.content.contains("class Widget"))
        assertTrue(classChunk.content.contains("Widget utilities"))

        val constChunk = chunks[2]
        assertEquals("Constant ANSWER", constChunk.summary)
        assertTrue(constChunk.content.contains("export const ANSWER"))
    }

    @Test
    fun `handles default export arrow functions`() {
        val code = """
            export default () => {
                return Date.now();
            };
        """.trimIndent()

        val chunker = TypeScriptChunker()
        val chunks = chunker.chunk(code, "src/default.ts", "typescript")

        assertEquals(1, chunks.size)
        val chunk = chunks.first()
        assertEquals(ChunkKind.CODE_FUNCTION, chunk.kind)
        assertEquals("Default export", chunk.summary)
        assertTrue(chunk.content.contains("export default"))
    }

    @Test
    fun `splits oversized export respecting token limit`() {
        val code = buildString {
            appendLine("export function big() {")
            repeat(160) { appendLine("  console.log('line $it');") }
            appendLine("}")
        }

        val chunker = TypeScriptChunker(maxTokens = 80)
        val chunks = chunker.chunk(code, "src/big.ts", "typescript")
        val functionChunks = chunks.filter { it.summary?.startsWith("Function big") == true }

        assertTrue(functionChunks.size > 1)
        assertTrue(functionChunks[0].summary!!.contains("part 1/"))
        assertTrue(functionChunks.all { (it.tokenEstimate ?: 0) <= 120_000 })
    }
}
