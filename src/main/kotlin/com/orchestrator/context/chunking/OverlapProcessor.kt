package com.orchestrator.context.chunking

import com.orchestrator.context.domain.Chunk
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Adds sentence-based overlap to existing chunks without altering their ordering or offsets.
 * Does not mutate schema or require idempotency guarantees—call once per chunking run.
 */
object OverlapProcessor {

    /**
     * Apply overlap to a list of chunks. If overlapPercent <= 0 or the list has a single chunk,
     * the original list is returned unchanged.
     */
    fun addOverlap(
        chunks: List<Chunk>,
        overlapPercent: Int,
        estimateTokens: (String) -> Int = TokenEstimator::estimate
    ): List<Chunk> {
        if (chunks.size <= 1 || overlapPercent <= 0) return chunks

        return chunks.mapIndexed { index, chunk ->
            val originalTokens = chunk.tokenEstimate ?: estimateTokens(chunk.content)
            val overlapTarget = ((originalTokens * (overlapPercent / 100.0)).roundToInt()).coerceAtLeast(0)
            val tokenCap = min(originalTokens * 2, originalTokens + overlapTarget)
            val allowedExtra = (tokenCap - originalTokens).coerceAtLeast(0)

            if (allowedExtra == 0) {
                return@mapIndexed chunk.copy(tokenEstimate = originalTokens)
            }

            var remainingExtra = allowedExtra

            val leftNeighbor = chunks.getOrNull(index - 1)?.content
            val leftOverlap = if (leftNeighbor.isNullOrBlank() || remainingExtra == 0) {
                ""
            } else {
                val takeTokens = min(overlapTarget, remainingExtra)
                takeLastSentences(leftNeighbor, takeTokens, estimateTokens)
            }
            remainingExtra -= estimateTokens(leftOverlap).coerceAtMost(remainingExtra)

            val rightNeighbor = chunks.getOrNull(index + 1)?.content
            val rightOverlap = if (rightNeighbor.isNullOrBlank() || remainingExtra == 0) {
                ""
            } else {
                val takeTokens = min(overlapTarget, remainingExtra)
                takeFirstSentences(rightNeighbor, takeTokens, estimateTokens)
            }

            val pieces = mutableListOf<String>()
            if (leftOverlap.isNotBlank()) pieces.add(leftOverlap.trim())
            pieces.add(chunk.content)
            if (rightOverlap.isNotBlank()) pieces.add(rightOverlap.trim())

            val combined = pieces.joinToString("\n\n")
            val updatedTokens = estimateTokens(combined)

            chunk.copy(content = combined, tokenEstimate = updatedTokens)
        }
    }

    private fun takeLastSentences(
        text: String,
        targetTokens: Int,
        estimateTokens: (String) -> Int
    ): String {
        if (targetTokens <= 0) return ""
        val sentences = splitSentences(text)
        if (sentences.isEmpty()) return ""

        val collected = mutableListOf<String>()
        var tokens = 0

        for (i in sentences.indices.reversed()) {
            val candidate = sentences[i].trim()
            if (candidate.isEmpty()) continue

            val candidateTokens = estimateTokens((listOf(candidate) + collected).joinToString(" "))
            if (tokens > 0 && candidateTokens > targetTokens) break

            collected.add(0, candidate)
            tokens = candidateTokens
            if (tokens >= targetTokens) break
        }

        return collected.joinToString(" ").trim()
    }

    private fun takeFirstSentences(
        text: String,
        targetTokens: Int,
        estimateTokens: (String) -> Int
    ): String {
        if (targetTokens <= 0) return ""
        val sentences = splitSentences(text)
        if (sentences.isEmpty()) return ""

        val collected = mutableListOf<String>()
        var tokens = 0

        for (sentence in sentences) {
            val candidate = sentence.trim()
            if (candidate.isEmpty()) continue

            val candidateTokens = estimateTokens((collected + candidate).joinToString(" "))
            if (tokens > 0 && candidateTokens > targetTokens) break

            collected.add(candidate)
            tokens = candidateTokens
            if (tokens >= targetTokens) break
        }

        return collected.joinToString(" ").trim()
    }

    private fun splitSentences(text: String): List<String> {
        val normalized = normalizeWhitespace(text)
        if (normalized.isEmpty()) return emptyList()
        val parts = normalized.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        return if (parts.size <= 1) listOf(normalized) else parts
    }

    private fun normalizeWhitespace(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()
}
