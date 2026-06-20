package com.orchestrator.context.chunking

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TreeSitterChunkerTest {

    private fun chunk(spec: LanguageSpec, src: String): List<Chunk> =
        TreeSitterChunker(spec, overlapPercent = 0).chunk(src.trimIndent(), "sample")

    private fun assertValidRanges(chunks: List<Chunk>) {
        assertTrue(chunks.isNotEmpty(), "expected at least one chunk")
        chunks.forEach { c ->
            val s = c.startLine; val e = c.endLine
            assertTrue(s != null && e != null && s in 1..e, "invalid line range in $c")
        }
    }

    @Test
    fun java() {
        val chunks = chunk(
            LanguageSpecs.JAVA,
            """
            package com.x;
            import java.util.List;
            class Foo {
                Foo() {}
                void bar() { System.out.println(1); }
            }
            interface I { void m(); }
            """
        )
        val kinds = chunks.map { it.kind }
        assertTrue(ChunkKind.CODE_HEADER in kinds)
        assertTrue(ChunkKind.CODE_METHOD in kinds, "class methods should be their own chunks: $kinds")
        assertTrue(ChunkKind.CODE_CONSTRUCTOR in kinds)
        assertTrue(ChunkKind.CODE_INTERFACE in kinds, "interface should be one atomic chunk")
        assertTrue(chunks.any { it.content.contains("void bar()") })
        assertValidRanges(chunks)
    }

    @Test
    fun python() {
        val chunks = chunk(
            LanguageSpecs.PYTHON,
            """
            import os
            def top():
                return 1
            class C:
                def m(self):
                    return 2
            """
        )
        assertTrue(ChunkKind.CODE_HEADER in chunks.map { it.kind })
        assertTrue(chunks.any { it.kind == ChunkKind.CODE_FUNCTION && it.content.contains("def top") })
        assertTrue(chunks.any { it.kind == ChunkKind.CODE_FUNCTION && it.content.contains("def m") }, "class method should be chunked")
        assertValidRanges(chunks)
    }

    @Test
    fun go() {
        val chunks = chunk(
            LanguageSpecs.GO,
            """
            package main
            import "fmt"
            func Top() int { return 1 }
            func (t T) M() int { return 2 }
            type T struct { X int }
            """
        )
        val kinds = chunks.map { it.kind }
        assertTrue(ChunkKind.CODE_FUNCTION in kinds)
        assertTrue(ChunkKind.CODE_METHOD in kinds, "receiver method should map to CODE_METHOD")
        assertTrue(ChunkKind.CODE_CLASS in kinds, "type declaration should be a chunk")
        assertValidRanges(chunks)
    }

    @Test
    fun typescript() {
        val chunks = chunk(
            LanguageSpecs.TYPESCRIPT,
            """
            import { x } from "y";
            export class Foo {
                bar(): number { return 1; }
            }
            function top(a: number): number { return a; }
            interface I { m(): void; }
            """
        )
        val kinds = chunks.map { it.kind }
        assertTrue(ChunkKind.CODE_METHOD in kinds, "method inside exported class should be chunked: $kinds")
        assertTrue(ChunkKind.CODE_FUNCTION in kinds)
        assertTrue(ChunkKind.CODE_INTERFACE in kinds)
        assertValidRanges(chunks)
    }

    @Test
    fun javascript() {
        val chunks = chunk(
            LanguageSpecs.JAVASCRIPT,
            """
            import { x } from "y";
            export class Foo {
                bar() { return 1; }
            }
            function top(a) { return a; }
            """
        )
        val kinds = chunks.map { it.kind }
        assertTrue(ChunkKind.CODE_METHOD in kinds, "method inside exported class should be chunked: $kinds")
        assertTrue(ChunkKind.CODE_FUNCTION in kinds)
        assertValidRanges(chunks)
    }

    @Test
    fun csharp() {
        val chunks = chunk(
            LanguageSpecs.CSHARP,
            """
            using System;
            namespace N {
                class Foo {
                    public void Bar() { Console.WriteLine(1); }
                }
                interface I { void M(); }
            }
            """
        )
        val kinds = chunks.map { it.kind }
        assertTrue(ChunkKind.CODE_METHOD in kinds, "method inside namespaced class should be chunked: $kinds")
        assertTrue(ChunkKind.CODE_INTERFACE in kinds)
        assertValidRanges(chunks)
    }

    @Test
    fun kotlin() {
        val chunks = chunk(
            LanguageSpecs.KOTLIN,
            """
            package com.x
            import kotlin.math.sqrt
            class Foo(val x: Int) {
                fun bar(): Int = x * 2
            }
            fun topLevel(a: Int): Int = a + 1
            """
        )
        val kinds = chunks.map { it.kind }
        assertTrue(ChunkKind.CODE_HEADER in kinds)
        assertTrue(ChunkKind.CODE_FUNCTION in kinds, "expected function chunks: $kinds")
        assertTrue(chunks.any { it.content.contains("fun topLevel") })
        assertValidRanges(chunks)
    }

    @Test
    fun `falls back when no declarations`() {
        val marker = Chunk(
            id = 0, fileId = 0, ordinal = 0, kind = ChunkKind.CODE_BLOCK,
            startLine = 1, endLine = 1, tokenEstimate = 1, content = "FALLBACK", summary = null,
            createdAt = java.time.Instant.now()
        )
        val spy = object : SimpleChunker {
            var called = false
            override fun chunk(content: String, filePath: String): List<Chunk> { called = true; return listOf(marker) }
        }
        val spec = LanguageSpecs.JAVA.copy(fallback = { spy })
        val result = TreeSitterChunker(spec).chunk("// only a comment\n", "X.java")
        assertTrue(spy.called)
        assertEquals(listOf(marker), result)
    }
}
