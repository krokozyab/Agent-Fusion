package com.orchestrator.context.chunking

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import java.time.Instant

class PlainTextChunker(private val maxTokens: Int = 600) : Chunker {

    private val sentenceSplitRegex = Regex("(?<=[.!?])\\s+")

    override val strategy = ChunkingStrategy(
        id = "plaintext",
        displayName = "Plain Text",
        supportedLanguages = setOf("text", "txt"),
        defaultMaxTokens = maxTokens,
        description = "Generic fallback chunker that splits text by paragraphs"
    )
    
    override fun chunk(content: String, filePath: String, language: String): List<Chunk> {
        if (content.isBlank()) return emptyList()
        
        val paragraphs = content.split(Regex("\n\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        
        val chunks = mutableListOf<Chunk>()
        var ordinal = 0
        val createdAt = Instant.now()

        for (paragraph in paragraphs) {
            val tokens = estimateTokens(paragraph)

            if (tokens <= maxTokens) {
                chunks.add(createChunk(paragraph.trim(), ordinal++, createdAt))
            } else {
                // Split large paragraphs by sentences, lines and words while respecting the token budget
                val subChunks = splitLargeParagraph(paragraph, ordinal, createdAt)
                chunks.addAll(subChunks)
                ordinal = chunks.size
            }
        }

        return chunks
    }
    
    override fun estimateTokens(text: String): Int = text.length / 4
    
    private fun splitLargeParagraph(text: String, startOrdinal: Int, timestamp: Instant): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var ordinal = startOrdinal
        val maxLength = (maxTokens * 4).coerceAtLeast(1)

        fun addChunk(content: String) {
            val cleaned = content.trim()
            if (cleaned.isNotEmpty()) {
                chunks += createChunk(cleaned, ordinal++, timestamp)
            }
        }

        val sentences = sentenceSplitRegex.split(text)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (sentences.isEmpty()) {
            splitByWords(text, ordinal, timestamp, maxLength, chunks)
            return chunks
        }

        val buffer = StringBuilder()
        for (sentence in sentences) {
            val candidateLength = if (buffer.isEmpty()) {
                sentence.length
            } else {
                buffer.length + 1 + sentence.length
            }

            if (candidateLength <= maxLength) {
                if (buffer.isNotEmpty()) buffer.append(' ')
                buffer.append(sentence)
            } else {
                if (buffer.isNotEmpty()) {
                    addChunk(buffer.toString())
                    buffer.setLength(0)
                }

                if (sentence.length <= maxLength) {
                    buffer.append(sentence)
                } else {
                    ordinal = splitByWords(sentence, ordinal, timestamp, maxLength, chunks)
                }
            }
        }

        if (buffer.isNotEmpty()) {
            addChunk(buffer.toString())
        }

        if (chunks.isEmpty()) {
            splitByWords(text, startOrdinal, timestamp, maxLength, chunks)
        }

        return chunks
    }

    private fun splitByWords(
        text: String,
        startOrdinal: Int,
        timestamp: Instant,
        maxLength: Int,
        target: MutableList<Chunk>
    ): Int {
        var ordinal = startOrdinal
        val words = text.split(Regex("\\s+"))
            .filter { it.isNotEmpty() }

        if (words.isEmpty()) {
            return ordinal
        }

        val buffer = StringBuilder()

        fun flush() {
            if (buffer.isNotEmpty()) {
                target += createChunk(buffer.toString().trim(), ordinal++, timestamp)
                buffer.setLength(0)
            }
        }

        for (word in words) {
            val candidateLength = if (buffer.isEmpty()) {
                word.length
            } else {
                buffer.length + 1 + word.length
            }

            if (candidateLength <= maxLength) {
                if (buffer.isNotEmpty()) buffer.append(' ')
                buffer.append(word)
            } else {
                flush()
                if (word.length <= maxLength) {
                    buffer.append(word)
                } else {
                    var index = 0
                    while (index < word.length) {
                        val end = (index + maxLength).coerceAtMost(word.length)
                        val part = word.substring(index, end)
                        target += createChunk(part, ordinal++, timestamp)
                        index = end
                    }
                }
            }
        }

        flush()
        return ordinal
    }

    private fun createChunk(text: String, ordinal: Int, timestamp: Instant): Chunk {
        return Chunk(
            id = 0,
            fileId = 0,
            ordinal = ordinal,
            kind = ChunkKind.PARAGRAPH,
            startLine = null,
            endLine = null,
            tokenEstimate = estimateTokens(text),
            content = text,
            summary = null,
            createdAt = timestamp
        )
    }
}
