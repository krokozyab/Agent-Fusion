package com.orchestrator.context.chunking

import kotlin.math.sqrt

/**
 * Lightweight semantic prose splitter that finds chunk boundaries using sentence-level
 * embedding shifts, while still enforcing a token budget.
 */
internal object SemanticProseChunker {

    data class ProseBlock(
        val text: String,
        val startLine: Int,
        val endLine: Int
    )

    data class ProseChunk(
        val text: String,
        val startLine: Int,
        val endLine: Int
    )

    fun split(
        blocks: List<ProseBlock>,
        maxTokens: Int,
        estimateTokens: (String) -> Int,
        semanticShiftThreshold: Double = DEFAULT_SEMANTIC_SHIFT_THRESHOLD,
        minTokensBeforeSemanticSplit: Int = DEFAULT_MIN_TOKENS_FOR_SHIFT
    ): List<ProseChunk> {
        if (blocks.isEmpty()) return emptyList()

        val cleanedBlocks = blocks
            .filter { it.text.isNotBlank() }
            .map {
                ProseBlock(
                    text = it.text.trim(),
                    startLine = it.startLine.coerceAtLeast(1),
                    endLine = it.endLine.coerceAtLeast(it.startLine.coerceAtLeast(1))
                )
            }
        if (cleanedBlocks.isEmpty()) return emptyList()

        val sentences = cleanedBlocks.flatMap { splitSentences(it, estimateTokens) }
        if (sentences.isEmpty()) {
            return cleanedBlocks.map { ProseChunk(it.text, it.startLine, it.endLine) }
        }

        val budget = maxTokens.coerceAtLeast(1)
        val chunks = mutableListOf<ProseChunk>()
        var buffer = mutableListOf<SentenceUnit>()

        fun flushBuffer() {
            if (buffer.isEmpty()) return
            val text = joinSentences(buffer)
            if (text.isBlank()) {
                buffer.clear()
                return
            }
            val start = buffer.first().startLine
            val end = buffer.last().endLine.coerceAtLeast(start)
            val tokenCount = estimateTokens(text)
            if (tokenCount <= budget) {
                chunks += ProseChunk(text, start, end)
            } else {
                chunks += splitByWords(text, start, end, budget, estimateTokens)
            }
            buffer.clear()
        }

        for (sentence in sentences) {
            if (buffer.isNotEmpty()) {
                val existing = joinSentences(buffer)
                val previous = buffer.last()
                val similarity = cosine(previous.embedding, sentence.embedding)
                val hasParagraphGap = sentence.startLine - previous.endLine > 1
                val shouldSplitForParagraphGap = hasParagraphGap && similarity < PARAGRAPH_MERGE_SIMILARITY
                val shift = 1.0 - similarity

                val shouldSplitForShift = !hasParagraphGap &&
                    shift >= semanticShiftThreshold &&
                    estimateTokens(existing) >= minTokensBeforeSemanticSplit
                if (shouldSplitForParagraphGap || shouldSplitForShift) {
                    flushBuffer()
                }

                if (buffer.isNotEmpty()) {
                    val candidate = joinSentences(buffer + sentence)
                    if (estimateTokens(candidate) > budget) {
                        flushBuffer()
                    }
                }
            }

            buffer += sentence
        }

        flushBuffer()
        return chunks
    }

    private fun splitSentences(block: ProseBlock, estimateTokens: (String) -> Int): List<SentenceUnit> {
        val text = block.text
        if (text.isBlank()) return emptyList()

        val sentenceRanges = mutableListOf<IntRange>()
        var cursor = 0
        for (match in SENTENCE_BOUNDARY.findAll(text)) {
            val boundaryStart = match.range.first
            if (boundaryStart > cursor) {
                sentenceRanges += IntRange(cursor, boundaryStart - 1)
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            sentenceRanges += IntRange(cursor, text.length - 1)
        }
        if (sentenceRanges.isEmpty()) {
            sentenceRanges += IntRange(0, text.length - 1)
        }

        val newlinePrefix = IntArray(text.length + 1)
        for (i in text.indices) {
            newlinePrefix[i + 1] = newlinePrefix[i] + if (text[i] == '\n') 1 else 0
        }

        val units = mutableListOf<SentenceUnit>()
        for (range in sentenceRanges) {
            var start = range.first
            var endExclusive = range.last + 1
            while (start < endExclusive && text[start].isWhitespace()) start++
            while (endExclusive > start && text[endExclusive - 1].isWhitespace()) endExclusive--
            if (start >= endExclusive) continue

            val sentenceText = text.substring(start, endExclusive)
            val startLine = (block.startLine + newlinePrefix[start]).coerceAtMost(block.endLine)
            val endLine = (block.startLine + newlinePrefix[endExclusive - 1]).coerceAtMost(block.endLine)
            units += SentenceUnit(
                text = sentenceText,
                startLine = startLine,
                endLine = endLine.coerceAtLeast(startLine),
                embedding = embedSentence(sentenceText)
            )
        }

        return if (units.isEmpty()) {
            listOf(
                SentenceUnit(
                    text = text,
                    startLine = block.startLine,
                    endLine = block.endLine,
                    embedding = embedSentence(text)
                )
            )
        } else {
            units
        }
    }

    private fun joinSentences(sentences: List<SentenceUnit>): String {
        if (sentences.isEmpty()) return ""
        val builder = StringBuilder()
        for (i in sentences.indices) {
            val sentence = sentences[i]
            if (builder.isNotEmpty()) {
                val prev = sentences[i - 1]
                val separator = if (sentence.startLine - prev.endLine > 1) "\n\n" else " "
                builder.append(separator)
            }
            builder.append(sentence.text.trim())
        }
        return builder.toString().trim()
    }

    private fun splitByWords(
        text: String,
        startLine: Int,
        endLine: Int,
        maxTokens: Int,
        estimateTokens: (String) -> Int
    ): List<ProseChunk> {
        val words = text.split(WORD_SPLIT).filter { it.isNotBlank() }
        if (words.isEmpty()) return listOf(ProseChunk(text.trim(), startLine, endLine))

        val maxChars = (maxTokens * APPROX_CHARS_PER_TOKEN).coerceAtLeast(1)
        val result = mutableListOf<String>()
        val buffer = StringBuilder()

        fun flush() {
            val cleaned = buffer.toString().trim()
            if (cleaned.isNotEmpty()) result += cleaned
            buffer.setLength(0)
        }

        for (word in words) {
            val candidate = if (buffer.isEmpty()) word else "${buffer} $word"
            if (candidate.length <= maxChars && estimateTokens(candidate) <= maxTokens) {
                if (buffer.isNotEmpty()) buffer.append(' ')
                buffer.append(word)
            } else {
                flush()
                if (word.length <= maxChars) {
                    buffer.append(word)
                } else {
                    var index = 0
                    while (index < word.length) {
                        val next = (index + maxChars).coerceAtMost(word.length)
                        result += word.substring(index, next)
                        index = next
                    }
                }
            }
        }
        flush()

        val totalLines = text.lines().size.coerceAtLeast(1)
        val span = (endLine - startLine + 1).coerceAtLeast(1)
        var lineOffset = 0
        return result.map { part ->
            val start = (startLine + (lineOffset * span) / totalLines).coerceAtLeast(1)
            val end = (start + part.lines().size - 1).coerceAtLeast(start).coerceAtMost(endLine)
            lineOffset += part.lines().size
            ProseChunk(part, start, end)
        }
    }

    private fun embedSentence(text: String): FloatArray {
        val vector = FloatArray(EMBED_DIM)
        val tokens = TOKEN_PATTERN.findAll(text.lowercase())
            .map { it.value }
            .filter { it.length > 2 && it !in STOP_WORDS }
            .toList()

        if (tokens.isEmpty()) {
            val fallback = text.lowercase().replace(WHITESPACE, "")
            if (fallback.isNotEmpty()) {
                for (gram in fallback.windowed(size = 3, step = 1, partialWindows = true)) {
                    val index = (gram.hashCode() and Int.MAX_VALUE) % EMBED_DIM
                    vector[index] += 1f
                }
            } else {
                vector[0] = 1f
            }
        } else {
            for (token in tokens) {
                val hash = token.hashCode()
                val index = (hash and Int.MAX_VALUE) % EMBED_DIM
                val sign = if (((hash ushr 1) and 1) == 0) 1f else -1f
                vector[index] += sign
            }
        }

        var norm = 0.0
        for (value in vector) {
            norm += value * value
        }
        val scale = sqrt(norm).toFloat()
        if (scale > 0f) {
            for (i in vector.indices) {
                vector[i] /= scale
            }
        } else {
            vector[0] = 1f
        }
        return vector
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        val size = minOf(a.size, b.size)
        var dot = 0.0
        for (i in 0 until size) {
            dot += a[i] * b[i]
        }
        return dot.coerceIn(-1.0, 1.0)
    }

    private data class SentenceUnit(
        val text: String,
        val startLine: Int,
        val endLine: Int,
        val embedding: FloatArray
    )

    private val SENTENCE_BOUNDARY = Regex("(?<=[.!?])\\s+")
    private val TOKEN_PATTERN = Regex("[a-z0-9_]+")
    private val WORD_SPLIT = Regex("\\s+")
    private val WHITESPACE = Regex("\\s+")

    private val STOP_WORDS = setOf(
        "the", "and", "for", "with", "that", "this", "from", "into", "onto",
        "was", "were", "are", "is", "be", "been", "being", "have", "has", "had",
        "will", "would", "could", "should", "can", "may", "might", "your", "our",
        "their", "about", "above", "below", "after", "before", "between", "through",
        "over", "under", "during", "while", "when", "where", "what", "which", "who",
        "them", "they", "there", "here", "than", "then", "also", "just", "very", "not"
    )

    private const val EMBED_DIM = 128
    private const val APPROX_CHARS_PER_TOKEN = 4
    private const val DEFAULT_SEMANTIC_SHIFT_THRESHOLD = 0.72
    private const val DEFAULT_MIN_TOKENS_FOR_SHIFT = 80
    private const val PARAGRAPH_MERGE_SIMILARITY = 0.78
}
