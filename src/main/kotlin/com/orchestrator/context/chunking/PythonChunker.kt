package com.orchestrator.context.chunking

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import java.time.Instant

/**
 * Python chunker backed by structural parsing of class/function definitions plus docstrings.
 */
class PythonChunker(
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    private val overlapRatio: Double = DEFAULT_OVERLAP_RATIO,
    private val overlapPercent: Int = 15,
    private val estimator: TokenEstimator = TokenEstimator,
    private val parser: PythonAstParser = PythonAstParser()
) : Chunker {

    override val strategy: ChunkingStrategy = ChunkingStrategy(
        id = "python",
        displayName = "Python Structure",
        supportedLanguages = setOf("python", "py"),
        defaultMaxTokens = maxTokens,
        description = "Splits Python modules by AST definitions and docstrings with overlap."
    )

    override fun chunk(content: String, filePath: String, language: String): List<Chunk> {
        if (content.isBlank()) return emptyList()

        val ast = parser.parse(content)
        val lines = ast.lines.mapIndexed { index, text -> Line(index + 1, text) }
        val outputs = mutableListOf<ChunkInput>()

        ast.moduleDocstring?.let { doc ->
            outputs += ChunkInput(
                kind = ChunkKind.DOCSTRING,
                lines = linesInRange(lines, doc.startLine, doc.endLine),
                label = "Module docstring"
            )
        }

        for (definition in ast.definitions) {
            definition.docstring?.let { doc ->
                outputs += ChunkInput(
                    kind = ChunkKind.DOCSTRING,
                    lines = linesInRange(lines, doc.startLine, doc.endLine),
                    label = docLabel(definition.type, definition.name)
                )
            }

            val blockLines = linesInRange(lines, definition.startLine, definition.endLine)
            if (blockLines.isEmpty()) continue
            val slices = splitWithOverlap(blockLines, maxTokens)
            slices.forEachIndexed { index, slice ->
                outputs += ChunkInput(
                    kind = kindFor(definition.type),
                    lines = slice,
                    label = codeLabel(definition.type, definition.name, index, slices.size)
                )
            }
        }

        val timestamp = Instant.now()
        val rootPath = ChunkPaths.path(ChunkKind.CODE_BLOCK, listOf(filePath))
        val baseChunks = mutableListOf<Chunk>()

        if (outputs.isEmpty()) {
            // No definitions/docstrings found — store the full file as a single chunk.
            return listOf(
                Chunk(
                    id = 0,
                    fileId = 0,
                    ordinal = 0,
                    kind = ChunkKind.CODE_BLOCK,
                    startLine = 1,
                    endLine = lines.lastOrNull()?.number,
                    tokenEstimate = estimator.estimate(content),
                    content = content,
                    summary = "Module root",
                    createdAt = timestamp,
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
            endLine = lines.lastOrNull()?.number,
            tokenEstimate = estimator.estimate(rootLabel),
            content = rootLabel,
            summary = "Module root",
            createdAt = timestamp,
            chunkPath = rootPath
        )

        outputs.forEach { input ->
            val text = input.lines.joinToString("\n") { it.text }
            if (text.isBlank()) return@forEach
            val label = input.label ?: input.kind.name.lowercase()
            val path = "$rootPath/$label"
            baseChunks += Chunk(
                id = 0,
                fileId = 0,
                ordinal = baseChunks.size,
                kind = input.kind,
                startLine = input.lines.firstOrNull()?.number,
                endLine = input.lines.lastOrNull()?.number,
                tokenEstimate = estimator.estimate(text),
                content = text,
                summary = input.label,
                createdAt = timestamp,
                chunkPath = path
            )
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

    private fun splitWithOverlap(lines: List<Line>, limit: Int): List<List<Line>> {
        if (lines.isEmpty()) return emptyList()
        val result = mutableListOf<List<Line>>()
        val overlapTokens = (limit * overlapRatio).coerceAtLeast(1.0)
        var buffer = mutableListOf<Line>()

        for (line in lines) {
            buffer += line
            val tokens = estimator.estimate(buffer.joinToString("\n") { it.text })
            if (tokens > limit && buffer.size > 1) {
                val splitIndex = findSplitIndex(buffer)
                val segment = buffer.subList(0, splitIndex).toList()
                if (segment.isNotEmpty()) {
                    result += segment
                    val overlap = takeOverlap(segment, overlapTokens)
                    buffer = (overlap + buffer.subList(splitIndex, buffer.size)).toMutableList()
                } else {
                    result += listOf(buffer.removeAt(0))
                }
            }
        }

        while (buffer.isNotEmpty()) {
            val tokens = estimator.estimate(buffer.joinToString("\n") { it.text })
            if (tokens > limit && buffer.size > 1) {
                val splitIndex = findSplitIndex(buffer)
                val segment = buffer.subList(0, splitIndex).toList()
                if (segment.isEmpty()) break
                result += segment
                val overlap = takeOverlap(segment, overlapTokens)
                buffer = (overlap + buffer.subList(splitIndex, buffer.size)).toMutableList()
            } else {
                result += buffer.toList()
                break
            }
        }

        return result
    }

    private fun findSplitIndex(lines: List<Line>): Int {
        for (i in lines.size - 1 downTo 1) {
            if (lines[i].text.trim().isEmpty()) return i
        }
        return lines.size - 1
    }

    private fun takeOverlap(segment: List<Line>, overlapTokens: Double): List<Line> {
        if (segment.isEmpty()) return emptyList()
        val tokensTarget = overlapTokens.toInt().coerceAtLeast(1)
        val selected = mutableListOf<Line>()
        var index = segment.lastIndex
        while (index >= 0) {
            selected.add(0, segment[index])
            val tokens = estimator.estimate(selected.joinToString("\n") { it.text })
            if (tokens >= tokensTarget) break
            index--
        }
        return selected
    }

    private fun docLabel(type: PythonAstParser.DefinitionType, name: String): String = when (type) {
        PythonAstParser.DefinitionType.FUNCTION -> "Function $name docstring"
        PythonAstParser.DefinitionType.CLASS -> "Class $name docstring"
    }

    private fun codeLabel(
        type: PythonAstParser.DefinitionType,
        name: String,
        index: Int,
        total: Int
    ): String {
        val base = when (type) {
            PythonAstParser.DefinitionType.FUNCTION -> "Function $name"
            PythonAstParser.DefinitionType.CLASS -> "Class $name"
        }
        return if (total > 1) "$base (part ${index + 1}/$total)" else base
    }

    private fun kindFor(type: PythonAstParser.DefinitionType): ChunkKind = when (type) {
        PythonAstParser.DefinitionType.FUNCTION -> ChunkKind.CODE_FUNCTION
        PythonAstParser.DefinitionType.CLASS -> ChunkKind.CODE_CLASS
    }

    private data class Line(val number: Int, val text: String)

    private data class ChunkInput(
        val kind: ChunkKind,
        val lines: List<Line>,
        val label: String?
    )

    companion object {
        private const val DEFAULT_MAX_TOKENS = 600
        private const val DEFAULT_OVERLAP_RATIO = 0.15
    }
}
