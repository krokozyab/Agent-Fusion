package com.orchestrator.context.domain

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ContextDomainModelsTest {

    @Test
    fun `file state exposes active flag`() {
        val state = FileState(
            id = 1,
            relativePath = "src/Main.kt",
            contentHash = "hash",
            sizeBytes = 42,
            modifiedTimeNs = 1234,
            language = "kotlin",
            kind = "source",
            fingerprint = "fp",
            indexedAt = Instant.parse("2024-01-01T00:00:00Z"),
            isDeleted = false
        )

        assertTrue(state.isActive)
        assertFalse(state.copy(isDeleted = true).isActive)
    }

    @Test
    fun `chunk validates line span and ordinal`() {
        val chunk = Chunk(
            id = 10,
            fileId = 1,
            ordinal = 0,
            kind = ChunkKind.CODE_FUNCTION,
            startLine = 1,
            endLine = 10,
            tokenEstimate = 50,
            content = "fun greet() = Unit",
            summary = "greet function",
            createdAt = Instant.parse("2024-01-01T00:00:00Z")
        )

        assertEquals(1..10, chunk.lineSpan)

        assertFailsWith<IllegalArgumentException> {
            chunk.copy(ordinal = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            chunk.copy(startLine = 5, endLine = 2)
        }
        assertFailsWith<IllegalArgumentException> {
            chunk.copy(content = "   ")
        }
    }

    @Test
    fun `embedding enforces vector size`() {
        val embedding = Embedding(
            id = 3,
            chunkId = 10,
            model = "test-model",
            dimensions = 3,
            vector = listOf(0.1f, 0.2f, 0.3f),
            createdAt = Instant.parse("2024-01-01T00:00:00Z")
        )

        assertEquals(3, embedding.vector.size)

        assertFailsWith<IllegalArgumentException> {
            embedding.copy(vector = listOf(0.1f, 0.2f))
        }
    }

    @Test
    fun `token budget computes remaining tokens`() {
        val budget = TokenBudget(maxTokens = 8000, reserveForPrompt = 2000, diversityWeight = 0.3)

        assertEquals(6000, budget.availableForSnippets)
        assertFailsWith<IllegalArgumentException> {
            budget.copy(maxTokens = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            budget.copy(reserveForPrompt = 9000)
        }
    }

    @Test
    fun `context scope flags bounded state`() {
        val emptyScope = ContextScope()
        assertTrue(emptyScope.isUnbounded)

        val scoped = ContextScope(paths = listOf("src"), languages = setOf("kotlin"))
        assertFalse(scoped.isUnbounded)
        assertFailsWith<IllegalArgumentException> {
            ContextScope(paths = listOf(""))
        }
    }

    @Test
    fun `context snippet validates score and metadata`() {
        val snippet = ContextSnippet(
            chunkId = 10,
            score = 0.8,
            filePath = "src/Main.kt",
            label = "greet",
            kind = ChunkKind.CODE_FUNCTION,
            text = "fun greet() = Unit",
            language = "kotlin",
            offsets = 0..5,
            metadata = mapOf("provider" to "semantic")
        )

        assertEquals("semantic", snippet.metadata["provider"])

        assertFailsWith<IllegalArgumentException> {
            snippet.copy(score = 1.5)
        }
        assertFailsWith<IllegalArgumentException> {
            snippet.copy(filePath = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            snippet.copy(offsets = -1..2)
        }
        assertFailsWith<IllegalArgumentException> {
            snippet.copy(metadata = mapOf("" to "value"))
        }
    }

    @Test
    fun `link requires type and non negative score`() {
        val link = Link(
            id = 1,
            sourceChunkId = 10,
            targetFileId = 20,
            targetChunkId = null,
            type = "import",
            label = "Foo",
            score = 0.42,
            createdAt = Instant.parse("2024-01-01T00:00:00Z")
        )

        assertEquals("import", link.type)

        assertFailsWith<IllegalArgumentException> {
            link.copy(type = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            link.copy(score = -0.1)
        }
    }
}
