package com.orchestrator.context.chunking

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Lightweight heuristic estimator for token counts without model invocation.
 *
 * Uses a blended average of characters per token and tokens per word, calibrated against
 * common GPT-style BPE tokenisers. Results are cached by input length and word count.
 */
object TokenEstimator {

    private data class Key(val length: Int, val wordCount: Int)

    private val cache = ConcurrentHashMap<Key, Int>()

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        val length = text.length
        val words = countWords(text)
        val key = Key(length, words)

        return cache.computeIfAbsent(key) {
            val charEstimate = length / CHARS_PER_TOKEN
            val wordEstimate = words * TOKENS_PER_WORD
            val blended = (charEstimate * CHAR_WEIGHT) + (wordEstimate * WORD_WEIGHT)
            val adjusted = max(charEstimate, blended)
            adjusted.roundToInt().coerceIn(1, MAX_TOKENS_CAP)
        }
    }

    private fun countWords(text: String): Int {
        var inWord = false
        var words = 0
        for (ch in text) {
            if (ch.isWhitespace()) {
                inWord = false
            } else if (!inWord) {
                inWord = true
                words++
            }
        }
        return words
    }

    private const val CHARS_PER_TOKEN = 4.1
    private const val TOKENS_PER_WORD = 0.75
    private const val CHAR_WEIGHT = 0.6
    private const val WORD_WEIGHT = 0.4
    private const val MAX_TOKENS_CAP = 120_000
}
