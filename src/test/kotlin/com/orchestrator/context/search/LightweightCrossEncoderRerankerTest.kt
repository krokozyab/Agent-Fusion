package com.orchestrator.context.search

import com.orchestrator.context.domain.ChunkKind
import com.orchestrator.context.domain.ContextSnippet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LightweightCrossEncoderRerankerTest {

    @Test
    fun `promotes snippets with stronger lexical match`() {
        val reranker = LightweightCrossEncoderReranker()
        val query = "jwt token validation"

        val weak = ContextSnippet(
            chunkId = 1L,
            score = 0.75,
            filePath = "/tmp/other.kt",
            label = "Misc util",
            kind = ChunkKind.CODE_FUNCTION,
            text = "utility for formatting strings",
            language = "kotlin",
            offsets = null
        )
        val strong = ContextSnippet(
            chunkId = 2L,
            score = 0.70,
            filePath = "/tmp/auth.kt",
            label = "validateJwtToken",
            kind = ChunkKind.CODE_FUNCTION,
            text = "fun validateJwtToken(token: String) { /* jwt token validation */ }",
            language = "kotlin",
            offsets = null
        )

        val reranked = reranker.rerank(query, listOf(weak, strong), topN = 2, blendWeight = 0.8)
        assertEquals(2L, reranked.first().chunkId)
        assertTrue(reranked.first().metadata["cross_encoder_applied"] == "true")
    }
}
