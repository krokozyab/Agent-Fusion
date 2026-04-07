package com.orchestrator.context.chunking

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import java.time.Instant

/**
 * Chunker that splits Markdown content by heading sections and preserves fenced code blocks.
 */
class MarkdownChunker(
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    private val overlapPercent: Int = 15,
    private val estimator: TokenEstimator = TokenEstimator
) : Chunker {

    override val strategy: ChunkingStrategy = ChunkingStrategy(
        id = "markdown",
        displayName = "Markdown Headings",
        supportedLanguages = setOf("markdown", "md"),
        defaultMaxTokens = maxTokens,
        description = "Splits markdown documents by headings, paragraphs, and code fences."
    )

    override fun chunk(content: String, filePath: String, language: String): List<Chunk> {
        if (content.isBlank()) return emptyList()

        val chunkInputs = mutableListOf<ChunkInput>()
        var section = SectionBuilder()
        val lines = content.splitToSequence('\n')

        var lineNumber = 1
        var activeFence: FenceBuilder? = null

        fun flushSection() {
            chunkInputs += section.finish(maxTokens, estimator)
            section = SectionBuilder()
        }

        for (line in lines) {
            if (activeFence != null) {
                val fence = activeFence
                fence!!.add(lineNumber, line)
                if (fence.isClosing(line)) {
                    chunkInputs += fence.toChunkInput()
                    activeFence = null
                }
                lineNumber++
                continue
            }

            val heading = parseHeading(line)
            if (heading != null) {
                flushSection()
                section = SectionBuilder(label = heading.text)
                section.add(lineNumber, line)
                lineNumber++
                continue
            }

            if (isFenceOpening(line)) {
                flushSection()
                activeFence = FenceBuilder(lineNumber, line)
                lineNumber++
                continue
            }

            if (section.isEmpty() && line.isNotEmpty()) {
                section.startLine = lineNumber
            }
            section.add(lineNumber, line)
            lineNumber++
        }

        flushSection()

        val prepared = chunkInputs
            .flatMap { it.ensureWithinLimit(maxTokens, estimator) }
            .mapNotNull { input ->
                val text = input.textOverride ?: input.lines.joinToString("\n") { it.text }
                if (text.isBlank()) {
                    null
                } else {
                    val start = input.startLineOverride ?: input.lines.firstOrNull()?.number
                    val end = input.endLineOverride ?: input.lines.lastOrNull()?.number
                    Triple(input, text, Pair(start, end))
                }
            }

        val now = Instant.now()
        val rootPath = ChunkPaths.path(ChunkKind.MARKDOWN_SECTION, listOf(filePath))

        val baseChunks = mutableListOf<Chunk>()

        // Document root chunk to anchor hierarchy. Coerce to a valid range so that
        // degenerate inputs (empty prepared list, upstream chunkers returning 0/negative
        // line numbers) never blow up the Chunk invariants downstream.
        val rootEnd = (prepared.lastOrNull()?.third?.second ?: 1).coerceAtLeast(1)
        baseChunks += Chunk(
            id = 0,
            fileId = 0,
            ordinal = 0,
            kind = ChunkKind.MARKDOWN_SECTION,
            startLine = 1,
            endLine = rootEnd,
            tokenEstimate = estimator.estimate(content),
            content = "Document $filePath",
            summary = "Document root",
            createdAt = now,
            chunkPath = rootPath
        )

        prepared.forEach { (input, text, span) ->
            val rawStart = span.first
            val rawEnd = span.second
            if (rawStart == null || rawEnd == null) {
                return@forEach
            }
            // Defensive sanitisation: Chunk requires 1 <= startLine <= endLine.
            // SemanticProseChunker / override paths have been observed to produce
            // reversed or zero-based ranges on unusual inputs (e.g. Confluence-exported
            // markdown with CRLF front matter). Clamp rather than crash so the file
            // still gets indexed.
            val start = rawStart.coerceAtLeast(1)
            val end = rawEnd.coerceAtLeast(start)
            val label = input.label ?: input.kind.name.lowercase()
            val path = "$rootPath/$label"
            baseChunks += Chunk(
                id = 0,
                fileId = 0,
                ordinal = baseChunks.size,
                kind = input.kind,
                startLine = start,
                endLine = end,
                tokenEstimate = estimator.estimate(text),
                content = text,
                summary = input.label,
                createdAt = now,
                chunkPath = path
            )
        }

        return OverlapProcessor.addOverlap(
            baseChunks,
            overlapPercent,
            estimator::estimate
        ) { it.summary != "Document root" }
    }

    override fun estimateTokens(text: String): Int = estimator.estimate(text)

    private fun parseHeading(line: String): Heading? {
        val trimmed = line.trimStart()
        if (!trimmed.startsWith('#')) return null
        val level = trimmed.takeWhile { it == '#' }.length
        if (level == 0 || level > 6) return null
        val text = trimmed.substring(level).trim()
        if (text.isEmpty()) return null
        return Heading(level, text)
    }

    private fun isFenceOpening(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < 3) return false
        val marker = trimmed.takeWhile { it == '`' || it == '~' }
        return marker.length >= 3
    }

    private data class Heading(val level: Int, val text: String)

    private data class Line(val number: Int, val text: String)

    private data class ChunkInput(
        val kind: ChunkKind,
        val lines: List<Line>,
        val label: String?,
        val textOverride: String? = null,
        val startLineOverride: Int? = null,
        val endLineOverride: Int? = null
    ) {
        fun ensureWithinLimit(maxTokens: Int, estimator: TokenEstimator): List<ChunkInput> {
            if (textOverride != null) return listOf(this)
            if (lines.isEmpty()) return emptyList()
            val content = lines.joinToString("\n") { it.text }
            val tokens = estimator.estimate(content)
            if (tokens <= maxTokens) return listOf(this)
            if (lines.size == 1 && kind != ChunkKind.MARKDOWN_SECTION) return listOf(this)

            if (kind == ChunkKind.MARKDOWN_SECTION) {
                val startLine = lines.first().number
                val endLine = lines.last().number
                val semanticChunks = SemanticProseChunker.split(
                    blocks = listOf(
                        SemanticProseChunker.ProseBlock(
                            text = content,
                            startLine = startLine,
                            endLine = endLine
                        )
                    ),
                    maxTokens = maxTokens,
                    estimateTokens = estimator::estimate,
                    semanticShiftThreshold = SEMANTIC_SHIFT_THRESHOLD,
                    minTokensBeforeSemanticSplit = MIN_TOKENS_BEFORE_SEMANTIC_SPLIT
                )

                if (semanticChunks.isNotEmpty()) {
                    return semanticChunks.map { prose ->
                        ChunkInput(
                            kind = kind,
                            lines = emptyList(),
                            label = label,
                            textOverride = prose.text,
                            startLineOverride = prose.startLine,
                            endLineOverride = prose.endLine
                        )
                    }
                }
            }

            val outputs = mutableListOf<ChunkInput>()
            var buffer = mutableListOf<Line>()
            var lastBlankIndex = -1

            fun flushBufferFrom(toIndexExclusive: Int) {
                if (toIndexExclusive <= 0) return
                val slice = buffer.subList(0, toIndexExclusive).toList()
                if (slice.isNotEmpty()) {
                    outputs += ChunkInput(kind, slice, label)
                }
                buffer = buffer.subList(toIndexExclusive, buffer.size).toMutableList()
                lastBlankIndex = buffer.indexOfLast { it.text.isBlank() }
            }

            for (line in lines) {
                buffer += line
                if (line.text.isBlank()) lastBlankIndex = buffer.size - 1
                val currentText = buffer.joinToString("\n") { it.text }
                val estimate = estimator.estimate(currentText)
                if (estimate > maxTokens && buffer.size > 1) {
                    if (lastBlankIndex > 0) {
                        flushBufferFrom(lastBlankIndex)
                    } else {
                        val splitIndex = buffer.size - 1
                        val slice = buffer.subList(0, splitIndex).toList()
                        if (slice.isNotEmpty()) {
                            outputs += ChunkInput(kind, slice, label)
                        }
                        buffer = mutableListOf(buffer.last())
                    }
                }
            }

            if (buffer.isNotEmpty()) {
                outputs += ChunkInput(kind, buffer.toList(), label)
            }

            return if (outputs.isEmpty()) listOf(this) else outputs
        }
    }

    private class SectionBuilder(var label: String? = null) {
        private val lines = mutableListOf<Line>()
        var startLine: Int? = null

        fun add(lineNumber: Int, text: String) {
            if (startLine == null && text.isNotEmpty()) {
                startLine = lineNumber
            }
            lines += Line(lineNumber, text)
        }

        fun isEmpty(): Boolean = lines.isEmpty()

        fun finish(maxTokens: Int, estimator: TokenEstimator): List<ChunkInput> {
            if (lines.isEmpty()) return emptyList()
            val inputs = listOf(ChunkInput(ChunkKind.MARKDOWN_SECTION, lines.toList(), label))
            return inputs.flatMap { it.ensureWithinLimit(maxTokens, estimator) }
        }
    }

    private class FenceBuilder(startLine: Int, opening: String) {
        private val marker = opening.trim().takeWhile { it == '`' || it == '~' }
        private val lines = mutableListOf(Line(startLine, opening))

        fun add(lineNumber: Int, text: String) {
            lines += Line(lineNumber, text)
        }

        fun isClosing(line: String): Boolean {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return false
            val closingMarker = trimmed.takeWhile { it == '`' || it == '~' }
            return closingMarker.length >= marker.length && trimmed == closingMarker
        }

        fun toChunkInput(): List<ChunkInput> =
            listOf(ChunkInput(ChunkKind.CODE_BLOCK, lines.toList(), label = lines.first().text.trim()))
    }

    companion object {
        private const val DEFAULT_MAX_TOKENS = 400
        private const val SEMANTIC_SHIFT_THRESHOLD = 0.72
        private const val MIN_TOKENS_BEFORE_SEMANTIC_SPLIT = 80
    }
}
