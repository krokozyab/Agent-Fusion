package com.orchestrator.context.search

import com.orchestrator.context.domain.ContextSnippet
import java.util.Locale
import kotlin.math.min

/**
 * Lightweight second-stage reranker inspired by cross-encoder behavior.
 *
 * This implementation is intentionally local and dependency-free: it scores
 * query/snippet pairs jointly using lexical overlap, phrase matches, and path cues,
 * then blends with the first-stage score.
 */
class LightweightCrossEncoderReranker {

    fun rerank(
        query: String,
        snippets: List<ContextSnippet>,
        topN: Int,
        blendWeight: Double
    ): List<ContextSnippet> {
        if (snippets.isEmpty()) return emptyList()
        if (topN <= 0) return snippets

        val safeBlend = blendWeight.coerceIn(0.0, 1.0)
        if (safeBlend == 0.0) return snippets

        val sorted = snippets.sortedByDescending { it.score }
        val headSize = min(topN, sorted.size)
        val head = sorted.take(headSize)
        val tail = sorted.drop(headSize)

        val rescoredHead = head.map { snippet ->
            val crossScore = score(query, snippet)
            val blended = ((1.0 - safeBlend) * snippet.score + safeBlend * crossScore).coerceIn(0.0, 1.0)
            snippet.copy(
                score = blended,
                metadata = snippet.metadata + mapOf(
                    "cross_encoder_score" to "%.4f".format(Locale.US, crossScore),
                    "cross_encoder_blend_weight" to "%.2f".format(Locale.US, safeBlend),
                    "cross_encoder_applied" to "true"
                )
            )
        }

        return (rescoredHead + tail).sortedByDescending { it.score }
    }

    private fun score(query: String, snippet: ContextSnippet): Double {
        val qTokens = tokens(query)
        if (qTokens.isEmpty()) return snippet.score.coerceIn(0.0, 1.0)

        val textSample = snippet.text.take(1800)
        val sTokens = tokens(textSample)
        if (sTokens.isEmpty()) return 0.0

        val qSet = qTokens.toSet()
        val sSet = sTokens.toSet()
        val overlap = qSet.intersect(sSet).size.toDouble() / qSet.size.toDouble()

        val queryPhrase = query.trim().lowercase(Locale.US)
        val snippetLower = textSample.lowercase(Locale.US)
        val exactPhrase = if (queryPhrase.isNotBlank() && snippetLower.contains(queryPhrase)) 1.0 else 0.0

        val path = snippet.filePath.lowercase(Locale.US)
        val pathMatch = (qSet.count { token -> token.length >= 3 && path.contains(token) }.toDouble() / qSet.size.toDouble())
            .coerceIn(0.0, 1.0)

        val label = snippet.label?.lowercase(Locale.US).orEmpty()
        val labelMatch = (qSet.count { token -> token.length >= 3 && label.contains(token) }.toDouble() / qSet.size.toDouble())
            .coerceIn(0.0, 1.0)

        return (0.55 * overlap + 0.20 * labelMatch + 0.15 * pathMatch + 0.10 * exactPhrase).coerceIn(0.0, 1.0)
    }

    private fun tokens(text: String): List<String> =
        text.lowercase(Locale.US)
            .split(Regex("[^a-z0-9_\\-]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
}

