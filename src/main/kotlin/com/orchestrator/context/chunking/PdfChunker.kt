package com.orchestrator.context.chunking

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import java.time.Instant

private data class PdfParagraphInfo(
    val text: String,
    val startLine: Int,
    val endLine: Int
)

class PdfChunker(
    private val maxTokens: Int = 600,
    private val overlapPercent: Int = 0
) : Chunker {

    override val strategy = ChunkingStrategy(
        id = "pdf",
        displayName = "PDF Document Chunker",
        supportedLanguages = setOf("pdf"),
        defaultMaxTokens = maxTokens,
        description = "Extracts text from PDF files and chunks by paragraphs"
    )

    override fun chunk(content: String, filePath: String, language: String): List<Chunk> {
        if (content.isBlank()) return emptyList()

        val lines = content.lines()
        val paragraphs = mutableListOf<PdfParagraphInfo>()
        var currentParagraph = StringBuilder()
        var paragraphStartLine = 1
        var currentLine = 1

        // Track paragraph boundaries with line numbers
        for (line in lines) {
            if (line.isBlank()) {
                if (currentParagraph.isNotEmpty()) {
                    paragraphs.add(PdfParagraphInfo(
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
            paragraphs.add(PdfParagraphInfo(
                currentParagraph.toString().trim(),
                paragraphStartLine,
                lines.size
            ))
        }

        val chunks = mutableListOf<Chunk>()
        var ordinal = 0
        val createdAt = Instant.now()
        val rootPath = ChunkPaths.path(ChunkKind.PARAGRAPH, listOf(filePath))

        // Document root chunk
        chunks += Chunk(
            id = 0,
            fileId = 0,
            ordinal = ordinal,
            kind = ChunkKind.PARAGRAPH,
            startLine = 1,
            endLine = lines.size,
            tokenEstimate = estimateTokens(content),
            content = "Document $filePath",
            summary = "Document root",
            createdAt = createdAt,
            chunkPath = rootPath
        )
        ordinal += 1

        val proseChunks = SemanticProseChunker.split(
            blocks = paragraphs.map { paragraph ->
                SemanticProseChunker.ProseBlock(
                    text = paragraph.text,
                    startLine = paragraph.startLine,
                    endLine = paragraph.endLine
                )
            },
            maxTokens = maxTokens,
            estimateTokens = ::estimateTokens,
            semanticShiftThreshold = SEMANTIC_SHIFT_THRESHOLD,
            minTokensBeforeSemanticSplit = MIN_TOKENS_BEFORE_SEMANTIC_SPLIT
        )

        for (proseChunk in proseChunks) {
            chunks += createChunk(
                text = proseChunk.text,
                ordinal = ordinal++,
                startLine = proseChunk.startLine,
                endLine = proseChunk.endLine,
                timestamp = createdAt,
                parentPath = rootPath
            )
        }

        return OverlapProcessor.addOverlap(
            chunks,
            overlapPercent,
            ::estimateTokens
        ) { it.summary != "Document root" }
    }

    override fun estimateTokens(text: String): Int = text.length / 4

    private fun createChunk(
        text: String,
        ordinal: Int,
        startLine: Int,
        endLine: Int,
        timestamp: Instant,
        parentPath: String
    ): Chunk {
        val normalizedStartLine = startLine.coerceAtLeast(1)
        val normalizedEndLine = endLine.coerceAtLeast(normalizedStartLine)
        val path = "$parentPath/paragraph-$ordinal"
        return Chunk(
            id = 0,
            fileId = 0,
            ordinal = ordinal,
            kind = ChunkKind.PARAGRAPH,
            startLine = normalizedStartLine,
            endLine = normalizedEndLine,
            tokenEstimate = estimateTokens(text),
            content = text,
            summary = null,
            createdAt = timestamp,
            chunkPath = path
        )
    }

    companion object {
        private const val SEMANTIC_SHIFT_THRESHOLD = 0.72
        private const val MIN_TOKENS_BEFORE_SEMANTIC_SPLIT = 80
    }
}
