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

    @Test
    fun `empty content returns empty list`() {
        val chunker = TypeScriptChunker()
        val chunks = chunker.chunk("", "test.ts", "typescript")
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `blank content returns empty list`() {
        val chunker = TypeScriptChunker()
        val chunks = chunker.chunk("   \n\n  \n", "test.ts", "typescript")
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `non-export code returns single chunk`() {
        val code = """
            const value = 42;
            function helper() {
                return value;
            }
        """.trimIndent()

        val chunker = TypeScriptChunker()
        val chunks = chunker.chunk(code, "test.ts", "typescript")

        assertEquals(1, chunks.size)
        assertEquals(ChunkKind.CODE_BLOCK, chunks[0].kind)
    }

    @Test
    fun `export interface is recognized`() {
        val code = """
            export interface User {
                id: number;
                name: string;
            }
        """.trimIndent()

        val chunker = TypeScriptChunker()
        val chunks = chunker.chunk(code, "test.ts", "typescript")

        assertEquals(1, chunks.size)
        assertEquals("Interface User", chunks[0].summary)
        assertEquals(ChunkKind.CODE_CLASS, chunks[0].kind)
    }

    @Test
    fun `export type is recognized`() {
        val code = """
            export type Status = 'pending' | 'completed' | 'failed';
        """.trimIndent()

        val chunker = TypeScriptChunker()
        val chunks = chunker.chunk(code, "test.ts", "typescript")

        assertEquals(1, chunks.size)
        assertEquals("Type Status", chunks[0].summary)
    }

    @Test
    fun `export enum is recognized`() {
        val code = """
            export enum Color {
                Red,
                Green,
                Blue
            }
        """.trimIndent()

        val chunker = TypeScriptChunker()
        val chunks = chunker.chunk(code, "test.ts", "typescript")

        assertEquals(1, chunks.size)
        assertEquals(ChunkKind.CODE_CLASS, chunks[0].kind)
    }

    @Test
    fun `arrow function exports`() {
        val code = """
            export const greet = (name: string) => {
                return `Hello ${'$'}{name}`;
            };
        """.trimIndent()

        val chunker = TypeScriptChunker()
        val chunks = chunker.chunk(code, "test.ts", "typescript")

        assertEquals(1, chunks.size)
        assertEquals(ChunkKind.CODE_FUNCTION, chunks[0].kind)
    }

    @Test
    fun `template literals with expressions`() {
        val code = """
            export const template = `Value: ${'$'}{getValue()}`;
        """.trimIndent()

        val chunker = TypeScriptChunker()
        val chunks = chunker.chunk(code, "test.ts", "typescript")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].content.contains("getValue()"))
    }

    @Test
    fun `block comments are handled`() {
        val code = """
            /* Block comment */
            export function test() {
                /* Another comment */
                return true;
            }
        """.trimIndent()

        val chunker = TypeScriptChunker()
        val chunks = chunker.chunk(code, "test.ts", "typescript")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].content.contains("Block comment"))
    }

    @Test
    fun `line comments are handled`() {
        val code = """
            // Line comment
            export function test() {
                // Another comment
                return true;
            }
        """.trimIndent()

        val chunker = TypeScriptChunker()
        val chunks = chunker.chunk(code, "test.ts", "typescript")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].content.contains("// Another comment"))
    }

    @Test
    fun `string literals with braces`() {
        val code = """
            export const obj = "{ not: 'a real object' }";
        """.trimIndent()

        val chunker = TypeScriptChunker()
        val chunks = chunker.chunk(code, "test.ts", "typescript")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].content.contains("not:"))
    }

    @Test
    fun `nested braces in object`() {
        val code = """
            export const config = {
                nested: {
                    deeper: {
                        value: 42
                    }
                }
            };
        """.trimIndent()

        val chunker = TypeScriptChunker()
        val chunks = chunker.chunk(code, "test.ts", "typescript")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].content.contains("deeper"))
    }

    @Test
    fun `async functions`() {
        val code = """
            export async function fetchData() {
                const result = await api.get();
                return result;
            }
        """.trimIndent()

        val chunker = TypeScriptChunker()
        val chunks = chunker.chunk(code, "test.ts", "typescript")

        assertEquals(1, chunks.size)
        assertEquals("Function fetchData", chunks[0].summary)
        assertTrue(chunks[0].content.contains("async"))
    }

    @Test
    fun `generic type parameters`() {
        val code = """
            export function identity<T>(value: T): T {
                return value;
            }
        """.trimIndent()

        val chunker = TypeScriptChunker()
        val chunks = chunker.chunk(code, "test.ts", "typescript")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].content.contains("<T>"))
    }

    @Test
    fun `jsx tsx syntax`() {
        val code = """
            export function Component() {
                return <div>Hello</div>;
            }
        """.trimIndent()

        val chunker = TypeScriptChunker()
        val chunks = chunker.chunk(code, "test.tsx", "tsx")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].content.contains("<div>"))
    }

    @Test
    fun `multiple imports are grouped`() {
        val code = """
            import React from 'react';
            import { useState } from 'react';
            import type { Props } from './types';

            export function Component(props: Props) {
                return null;
            }
        """.trimIndent()

        val chunker = TypeScriptChunker()
        val chunks = chunker.chunk(code, "test.tsx", "tsx")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].content.contains("import React"))
        assertTrue(chunks[0].content.contains("import { useState }"))
        assertTrue(chunks[0].content.contains("import type"))
    }

    @Test
    fun `chunk ordinals are sequential`() {
        val code = """
            export const first = 1;
            export const second = 2;
            export const third = 3;
        """.trimIndent()

        val chunker = TypeScriptChunker()
        val chunks = chunker.chunk(code, "test.ts", "typescript")

        chunks.forEachIndexed { index, chunk ->
            assertEquals(index, chunk.ordinal)
        }
    }

    @Test
    fun `line numbers are tracked correctly`() {
        val code = """
            export function first() {}
            export function second() {}
        """.trimIndent()

        val chunker = TypeScriptChunker()
        val chunks = chunker.chunk(code, "test.ts", "typescript")

        chunks.forEach { chunk ->
            assertTrue(chunk.startLine != null)
            assertTrue(chunk.endLine != null)
            assertTrue(chunk.startLine!! <= chunk.endLine!!)
        }
    }

    @Test
    fun `strategy metadata is correct`() {
        val chunker = TypeScriptChunker()
        val strategy = chunker.strategy

        assertEquals("typescript", strategy.id)
        assertEquals("TypeScript Exports", strategy.displayName)
        assertTrue(strategy.supportedLanguages.contains("typescript"))
        assertTrue(strategy.supportedLanguages.contains("javascript"))
        assertTrue(strategy.supportedLanguages.contains("ts"))
        assertTrue(strategy.supportedLanguages.contains("tsx"))
        assertTrue(strategy.supportedLanguages.contains("js"))
        assertTrue(strategy.supportedLanguages.contains("jsx"))
        assertEquals(600, strategy.defaultMaxTokens)
    }

    @Test
    fun `estimateTokens returns positive values`() {
        val chunker = TypeScriptChunker()
        val text = "export function test() { return true; }"
        val tokens = chunker.estimateTokens(text)

        assertTrue(tokens > 0)
        assertTrue(tokens < text.length)
    }

    @Test
    fun `export default class`() {
        val code = """
            export default class Widget {
                constructor() {}
            }
        """.trimIndent()

        val chunker = TypeScriptChunker()
        val chunks = chunker.chunk(code, "test.ts", "typescript")

        assertEquals(1, chunks.size)
        assertEquals("Class Widget", chunks[0].summary)
        assertEquals(ChunkKind.CODE_CLASS, chunks[0].kind)
    }

    @Test
    fun `export with semicolons`() {
        val code = """
            export const a = 1;
            export const b = 2;
        """.trimIndent()

        val chunker = TypeScriptChunker()
        val chunks = chunker.chunk(code, "test.ts", "typescript")

        assertEquals(2, chunks.size)
    }

    @Test
    fun `export without semicolons`() {
        val code = """
            export const a = 1
            export const b = 2
        """.trimIndent()

        val chunker = TypeScriptChunker()
        val chunks = chunker.chunk(code, "test.ts", "typescript")

        assertEquals(2, chunks.size)
    }

    @Test
    fun `very large file stress test`() {
        val exports = List(50) { i ->
            """
            export function func_$i() {
                ${List(10) { j -> "console.log($j);" }.joinToString("\n    ")}
            }
            """.trimIndent()
        }
        val code = exports.joinToString("\n\n")

        val chunker = TypeScriptChunker()
        val chunks = chunker.chunk(code, "huge.ts", "typescript")

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { (it.tokenEstimate ?: 0) > 0 })
        assertTrue(chunks.all { it.startLine != null && it.endLine != null })
    }

    @Test
    fun `escaped characters in strings`() {
        val code = """
            export const message = "He said \"Hello\"";
        """.trimIndent()

        val chunker = TypeScriptChunker()
        val chunks = chunker.chunk(code, "test.ts", "typescript")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].content.contains("\\\"Hello\\\""))
    }

    @Test
    fun `regex literals are handled`() {
        val code = """
            export const pattern = /test\{2\}/g;
        """.trimIndent()

        val chunker = TypeScriptChunker()
        val chunks = chunker.chunk(code, "test.ts", "typescript")

        assertEquals(1, chunks.size)
    }
}
