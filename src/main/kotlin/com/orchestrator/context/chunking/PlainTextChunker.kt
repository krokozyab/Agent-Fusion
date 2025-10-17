package com.orchestrator.context.chunking

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import java.time.Instant

private data class ParagraphInfo(
    val text: String,
    val startLine: Int,
    val endLine: Int
)

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

        val lines = content.lines()
        val paragraphs = mutableListOf<ParagraphInfo>()
        var currentParagraph = StringBuilder()
        var paragraphStartLine = 1
        var currentLine = 1

        // Track paragraph boundaries with line numbers
        for (line in lines) {
            if (line.isBlank()) {
                if (currentParagraph.isNotEmpty()) {
                    paragraphs.add(ParagraphInfo(
                        currentParagraph.toString().trim(),
                        paragraphStartLine,
                        currentLine - 1
                    ))
                    currentParagraph.clear()
                }
                paragraphStartLine = currentLine + 1
            } else {
                if (currentParagraph.isNotEmpty()) {
                    currentParagraph.append('\n')
                }
                currentParagraph.append(line)
            }
            currentLine++
        }

        // Add final paragraph if exists
        if (currentParagraph.isNotEmpty()) {
            paragraphs.add(ParagraphInfo(
                currentParagraph.toString().trim(),
                paragraphStartLine,
                lines.size
            ))
        }

        val chunks = mutableListOf<Chunk>()
        var ordinal = 0
        val createdAt = Instant.now()

        for (paragraphInfo in paragraphs) {
            val tokens = estimateTokens(paragraphInfo.text)

            if (tokens <= maxTokens) {
                chunks.add(createChunk(
                    paragraphInfo.text,
                    ordinal++,
                    paragraphInfo.startLine,
                    paragraphInfo.endLine,
                    createdAt
                ))
            } else {
                // Split large paragraphs by sentences, lines and words while respecting the token budget
                val subChunks = splitLargeParagraph(
                    paragraphInfo.text,
                    ordinal,
                    paragraphInfo.startLine,
                    paragraphInfo.endLine,
                    createdAt
                )
                chunks.addAll(subChunks)
                ordinal = chunks.size
            }
        }

        return chunks
    }
    
    override fun estimateTokens(text: String): Int = text.length / 4
    
    private fun splitLargeParagraph(
        text: String,
        startOrdinal: Int,
        startLine: Int,
        endLine: Int,
        timestamp: Instant
    ): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var ordinal = startOrdinal
        val maxLength = (maxTokens * 4).coerceAtLeast(1)

        val totalLines = text.lines().size
        val lineRatio = (endLine - startLine + 1).toDouble() / totalLines.coerceAtLeast(1)

        fun addChunk(content: String, approximateLineOffset: Int) {
            val cleaned = content.trim()
            if (cleaned.isNotEmpty()) {
                val chunkLines = cleaned.lines().size
                val chunkStartLine = (startLine + approximateLineOffset * lineRatio).toInt().coerceAtLeast(1)
                val chunkEndLine = (chunkStartLine + chunkLines - 1).coerceAtLeast(chunkStartLine)
                chunks += createChunk(cleaned, ordinal++, chunkStartLine, chunkEndLine, timestamp)
            }
        }

        val sentences = sentenceSplitRegex.split(text)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (sentences.isEmpty()) {
            splitByWords(text, ordinal, startLine, endLine, timestamp, maxLength, chunks)
            return chunks
        }

        val buffer = StringBuilder()
        var lineOffset = 0
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
                    addChunk(buffer.toString(), lineOffset)
                    lineOffset += buffer.toString().lines().size
                    buffer.setLength(0)
                }

                if (sentence.length <= maxLength) {
                    buffer.append(sentence)
                } else {
                    ordinal = splitByWords(sentence, ordinal, startLine + lineOffset, endLine, timestamp, maxLength, chunks)
                    lineOffset += sentence.lines().size
                }
            }
        }

        if (buffer.isNotEmpty()) {
            addChunk(buffer.toString(), lineOffset)
        }

        if (chunks.isEmpty()) {
            splitByWords(text, startOrdinal, startLine, endLine, timestamp, maxLength, chunks)
        }

        return chunks
    }

    private fun splitByWords(
        text: String,
        startOrdinal: Int,
        startLine: Int,
        endLine: Int,
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
        var currentLine = startLine

        fun flush() {
            if (buffer.isNotEmpty()) {
                val content = buffer.toString().trim()
                val lines = content.lines().size
                val chunkEndLine = (currentLine + lines - 1).coerceAtLeast(currentLine).coerceAtMost(endLine)
                target += createChunk(content, ordinal++, currentLine, chunkEndLine, timestamp)
                currentLine = chunkEndLine + 1
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
                        target += createChunk(part, ordinal++, currentLine, currentLine, timestamp)
                        currentLine++
                        index = end
                    }
                }
            }
        }

        flush()
        return ordinal
    }

    private fun createChunk(
        text: String,
        ordinal: Int,
        startLine: Int,
        endLine: Int,
        timestamp: Instant
    ): Chunk {
        return Chunk(
            id = 0,
            fileId = 0,
            ordinal = ordinal,
            kind = ChunkKind.PARAGRAPH,
            startLine = startLine.coerceAtLeast(1),
            endLine = endLine.coerceAtLeast(1),
            tokenEstimate = estimateTokens(text),
            content = text,
            summary = null,
            createdAt = timestamp
        )
    }
}
