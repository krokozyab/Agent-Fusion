package com.orchestrator.context.chunking

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import java.time.Instant

class PlainTextChunker(private val maxTokens: Int = 600) : Chunker {
    
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
        val now = Instant.now()
        
        for (paragraph in paragraphs) {
            val tokens = estimateTokens(paragraph)
            
            if (tokens <= maxTokens) {
                chunks.add(createChunk(paragraph, ordinal++, now))
            } else {
                // Split large paragraphs by sentences or lines
                val subChunks = splitLargeParagraph(paragraph, ordinal)
                chunks.addAll(subChunks)
                ordinal = chunks.size
            }
        }
        
        return chunks
    }
    
    override fun estimateTokens(text: String): Int = text.length / 4
    
    private fun splitLargeParagraph(text: String, startOrdinal: Int): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        val now = Instant.now()
        var ordinal = startOrdinal
        
        // Try splitting by sentences first
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        var buffer = StringBuilder()
        
        for (sentence in sentences) {
            val testBuffer = if (buffer.isEmpty()) sentence else "$buffer $sentence"
            
            if (estimateTokens(testBuffer) <= maxTokens) {
                buffer = StringBuilder(testBuffer)
            } else {
                if (buffer.isNotEmpty()) {
                    chunks.add(createChunk(buffer.toString(), ordinal++, now))
                    buffer = StringBuilder(sentence)
                } else {
                    // Single sentence too large, split by lines
                    val lines = sentence.lines()
                    var lineBuffer = StringBuilder()
                    
                    for (line in lines) {
                        val testLine = if (lineBuffer.isEmpty()) line else "$lineBuffer\n$line"
                        
                        if (estimateTokens(testLine) <= maxTokens) {
                            lineBuffer = StringBuilder(testLine)
                        } else {
                            if (lineBuffer.isNotEmpty()) {
                                chunks.add(createChunk(lineBuffer.toString(), ordinal++, now))
                            }
                            lineBuffer = StringBuilder(line)
                        }
                    }
                    
                    if (lineBuffer.isNotEmpty()) {
                        chunks.add(createChunk(lineBuffer.toString(), ordinal++, now))
                    }
                }
            }
        }
        
        if (buffer.isNotEmpty()) {
            chunks.add(createChunk(buffer.toString(), ordinal++, now))
        }
        
        return chunks
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
