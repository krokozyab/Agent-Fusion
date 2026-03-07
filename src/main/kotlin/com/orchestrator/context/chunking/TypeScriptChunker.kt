package com.orchestrator.context.chunking

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import java.time.Instant

/**
 * TypeScript/JavaScript chunker driven by structural export parsing.
 */
class TypeScriptChunker(
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    private val overlapPercent: Int = 15,
    private val estimator: TokenEstimator = TokenEstimator,
    private val parser: TypeScriptAstParser = TypeScriptAstParser()
) : Chunker {

    override val strategy: ChunkingStrategy = ChunkingStrategy(
        id = "typescript",
        displayName = "TypeScript Exports",
        supportedLanguages = setOf("typescript", "javascript", "ts", "tsx", "js", "jsx"),
        defaultMaxTokens = maxTokens,
        description = "Splits modules by structural export declarations while preserving import context."
    )

    override fun chunk(content: String, filePath: String, language: String): List<Chunk> {
        if (content.isBlank()) return emptyList()

        val ast = parser.parse(content)
        val lines = ast.lines.mapIndexed { index, text -> Line(index + 1, text) }
        val now = Instant.now()
        val rootPath = ChunkPaths.path(ChunkKind.CODE_BLOCK, listOf(filePath))
        val baseChunks = mutableListOf<Chunk>()

        if (ast.exports.isEmpty()) {
            // No structural exports found — store the full file as a single chunk.
            return listOf(
                Chunk(
                    id = 0,
                    fileId = 0,
                    ordinal = 0,
                    kind = ChunkKind.CODE_BLOCK,
                    startLine = 1,
                    endLine = lines.lastOrNull()?.number ?: 1,
                    tokenEstimate = estimator.estimate(content),
                    content = content,
                    summary = "Module root",
                    createdAt = now,
                    chunkPath = rootPath
                )
            )
        }

        val rootLabel = "Module $filePath"
        baseChunks += Chunk(
            id = 0,
            fileId = 0,
            ordinal = 0,
            kind = ChunkKind.CODE_BLOCK,
            startLine = 1,
            endLine = lines.lastOrNull()?.number ?: 1,
            tokenEstimate = estimator.estimate(rootLabel),
            content = rootLabel,
            summary = "Module root",
            createdAt = now,
            chunkPath = rootPath
        )

        val importText = ast.importLines
            .mapNotNull { lineNo -> lines.getOrNull(lineNo - 1)?.text }
            .joinToString("\n")
            .trim()
        val importTokens = if (importText.isEmpty()) 0 else estimator.estimate(importText)
        val includeImports = importText.isNotEmpty() && importTokens < maxTokens
        val availableTokens = if (includeImports) (maxTokens - importTokens).coerceAtLeast(64) else maxTokens

        for (decl in ast.exports) {
            val declLines = linesInRange(lines, decl.startLine, decl.endLine)
            if (declLines.isEmpty()) continue
            val pieces = splitLines(declLines, availableTokens)

            pieces.forEachIndexed { pieceIndex, pieceLines ->
                val body = pieceLines.joinToString("\n") { it.text }.trimEnd()
                if (body.isBlank()) return@forEachIndexed

                var finalText = body
                if (includeImports) {
                    val candidate = buildString {
                        append(importText)
                        append("\n\n")
                        append(body)
                    }
                    finalText = if (estimator.estimate(candidate) <= maxTokens) candidate else body
                }

                val baseLabel = decl.label ?: filePath
                val label = decl.label?.let {
                    if (pieces.size > 1) "$it (part ${pieceIndex + 1}/${pieces.size})" else it
                }
                val path = "$rootPath/$baseLabel${if (pieces.size > 1) "/part-${pieceIndex + 1}" else ""}"

                baseChunks += Chunk(
                    id = 0,
                    fileId = 0,
                    ordinal = baseChunks.size,
                    kind = decl.kind,
                    startLine = pieceLines.firstOrNull()?.number,
                    endLine = pieceLines.lastOrNull()?.number,
                    tokenEstimate = estimator.estimate(finalText),
                    content = finalText,
                    summary = label,
                    createdAt = now,
                    chunkPath = path
                )
            }
        }

        return OverlapProcessor.addOverlap(
            baseChunks,
            overlapPercent,
            estimator::estimate
        ) { it.summary != "Module root" }
    }

    override fun estimateTokens(text: String): Int = estimator.estimate(text)

    private fun linesInRange(lines: List<Line>, startLine: Int, endLine: Int): List<Line> {
        if (lines.isEmpty()) return emptyList()
        val startIndex = (startLine - 1).coerceIn(0, lines.lastIndex)
        val endIndex = (endLine - 1).coerceIn(startIndex, lines.lastIndex)
        return lines.subList(startIndex, endIndex + 1)
    }

    private fun splitLines(lines: List<Line>, limitTokens: Int): List<List<Line>> {
        val totalTokens = estimator.estimate(lines.joinToString("\n") { it.text })
        if (limitTokens <= 0 || totalTokens <= limitTokens) return listOf(lines)

        val result = mutableListOf<List<Line>>()
        var buffer = mutableListOf<Line>()
        var lastBlankIndex = -1

        lines.forEach { line ->
            buffer += line
            if (line.text.trim().isEmpty()) {
                lastBlankIndex = buffer.size - 1
            }
            val tokens = estimator.estimate(buffer.joinToString("\n") { it.text })
            if (tokens > limitTokens && buffer.size > 1) {
                val splitExclusive = if (lastBlankIndex >= 0) lastBlankIndex + 1 else buffer.size - 1
                val segment = buffer.subList(0, splitExclusive).toList()
                if (segment.isNotEmpty()) {
                    result += segment
                    buffer = buffer.subList(splitExclusive, buffer.size).toMutableList()
                    lastBlankIndex = buffer.indexOfLast { it.text.trim().isEmpty() }
                } else {
                    result += listOf(buffer.removeAt(0))
                    lastBlankIndex = buffer.indexOfLast { it.text.trim().isEmpty() }
                }
            }
        }

        if (buffer.isNotEmpty()) {
            result += buffer.toList()
        }

        return result
    }

    private data class Line(val number: Int, val text: String)

    companion object {
        private const val DEFAULT_MAX_TOKENS = 600
    }
}
