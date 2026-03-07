package com.orchestrator.context.chunking

import com.orchestrator.context.domain.ChunkKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GoChunkerTest {

    @Test
    fun `chunks header struct method and function`() {
        val code = """
            package auth

            import (
                "context"
                "errors"
            )

            // Service handles auth operations.
            type Service struct{}

            // Login validates token and returns an error on failure.
            func (s *Service) Login(ctx context.Context, token string) error {
                if token == "" {
                    return errors.New("missing token")
                }
                return nil
            }

            func NormalizeToken(token string) string {
                return token
            }
        """.trimIndent()

        val chunker = GoChunker()
        val chunks = chunker.chunk(code, "auth/service.go")

        assertTrue(chunks.any { it.kind == ChunkKind.CODE_HEADER && it.content.contains("package auth") })
        assertTrue(chunks.any { it.kind == ChunkKind.CODE_CLASS && it.summary == "Service" })
        assertTrue(chunks.any { it.kind == ChunkKind.CODE_METHOD && it.summary == "Service.Login" })
        assertTrue(chunks.any { it.kind == ChunkKind.CODE_FUNCTION && it.summary == "NormalizeToken" })
    }

    @Test
    fun `chunks interface and const blocks`() {
        val code = """
            package store

            type Repository interface {
                Save(id string) error
                Load(id string) (string, error)
            }

            const (
                DefaultTimeout = 30
                MaxAttempts = 3
            )
        """.trimIndent()

        val chunker = GoChunker()
        val chunks = chunker.chunk(code, "store/repository.go")

        assertTrue(chunks.any { it.kind == ChunkKind.CODE_INTERFACE && it.summary == "Repository" })
        assertTrue(chunks.any { it.kind == ChunkKind.CODE_BLOCK && it.summary == "const block" })
    }

    @Test
    fun `splits oversized function by token budget`() {
        val code = buildString {
            appendLine("package compute")
            appendLine()
            appendLine("func Huge() int {")
            appendLine("    total := 0")
            repeat(220) { i ->
                appendLine("    total += $i")
            }
            appendLine("    return total")
            appendLine("}")
        }

        val chunker = GoChunker(maxTokens = 40, overlapPercent = 0)
        val chunks = chunker.chunk(code, "compute/huge.go")
        val hugeParts = chunks.filter {
            it.kind == ChunkKind.CODE_FUNCTION && it.summary?.startsWith("Huge") == true
        }

        assertTrue(hugeParts.size > 1)
        assertTrue(hugeParts.all { (it.tokenEstimate ?: 0) <= 40 })
    }

    @Test
    fun `function literals are not treated as top-level declarations`() {
        val code = """
            package bootstrap

            func Build() func() int {
                counter := 0
                return func() int {
                    counter++
                    return counter
                }
            }
        """.trimIndent()

        val chunker = GoChunker()
        val chunks = chunker.chunk(code, "bootstrap/build.go")
        val functions = chunks.filter { it.kind == ChunkKind.CODE_FUNCTION }

        assertEquals(1, functions.size)
        assertEquals("Build", functions.first().summary)
    }
}
