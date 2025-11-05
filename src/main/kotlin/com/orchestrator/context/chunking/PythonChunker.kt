package com.orchestrator.context.chunking

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import java.time.Instant

/**
 * Heuristic Python chunker splitting files by module docstring, classes, and functions.
 */
class PythonChunker(
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    private val overlapRatio: Double = DEFAULT_OVERLAP_RATIO,
    private val estimator: TokenEstimator = TokenEstimator
) : Chunker {

    override val strategy: ChunkingStrategy = ChunkingStrategy(
        id = "python",
        displayName = "Python Structure",
        supportedLanguages = setOf("python", "py"),
        defaultMaxTokens = maxTokens,
        description = "Splits Python modules by docstrings, classes, and functions with overlap."
    )

    override fun chunk(content: String, filePath: String, language: String): List<Chunk> {
        if (content.isBlank()) return emptyList()
        val rawLines = content.lines()
        val lines = rawLines.mapIndexed { index, text -> Line(index + 1, text) }
        val outputs = mutableListOf<ChunkInput>()

        var cursor = 0
        val moduleDoc = extractDocstring(lines, 0)
        if (moduleDoc != null) {
            outputs += ChunkInput(ChunkKind.DOCSTRING, moduleDoc.lines, "Module docstring")
            cursor = moduleDoc.endIndex + 1
        }

        while (cursor < lines.size) {
            val line = lines[cursor]
            val trimmed = line.text.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                cursor++
                continue
            }

            val functionMatch = functionRegex.find(trimmed) ?: asyncFunctionRegex.find(trimmed)
            val classMatch = classRegex.find(trimmed)
            val definition = when {
                functionMatch != null -> DefinitionType.FUNCTION to functionMatch.groupValues[1]
                classMatch != null -> DefinitionType.CLASS to classMatch.groupValues[1]
                else -> null
            }

            if (definition == null) {
                cursor++
                continue
            }

            val (type, name) = definition
            val startIndex = findDefinitionStart(lines, cursor)
            val indent = leadingSpaces(lines[startIndex].text)
            val endIndex = findBlockEnd(lines, cursor + 1, indent)
            val blockLines = lines.subList(startIndex, endIndex + 1)

            val docstring = extractInnerDocstring(lines, cursor + 1, endIndex)
            docstring?.let {
                outputs += ChunkInput(ChunkKind.DOCSTRING, it.lines, docLabel(type, name))
            }

            val slices = splitWithOverlap(blockLines, maxTokens)
            slices.forEachIndexed { part, slice ->
                val label = codeLabel(type, name, part, slices.size)
                outputs += ChunkInput(kindFor(type), slice, label)
            }

            cursor = when (type) {
                DefinitionType.CLASS -> cursor + 1
                DefinitionType.FUNCTION -> endIndex + 1
            }
        }

        val timestamp = Instant.now()
        return outputs.mapIndexed { ordinal, input ->
            val text = input.lines.joinToString("\n") { it.text }
            val startLine = input.lines.firstOrNull()?.number
            val endLine = input.lines.lastOrNull()?.number
            Chunk(
                id = 0,
                fileId = 0,
                ordinal = ordinal,
                kind = input.kind,
                startLine = startLine,
                endLine = endLine,
                tokenEstimate = estimator.estimate(text),
                content = text,
                summary = input.label,
                createdAt = timestamp
            )
        }
    }

    override fun estimateTokens(text: String): Int = estimator.estimate(text)

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
        var idx = segment.size - 1
        while (idx >= 0) {
            selected.add(0, segment[idx])
            val tokens = estimator.estimate(selected.joinToString("\n") { it.text })
            if (tokens >= tokensTarget) break
            idx--
        }
        return selected
    }

    private fun extractDocstring(lines: List<Line>, startIndex: Int): Docstring? {
        var idx = startIndex
        while (idx < lines.size) {
            val trimmed = lines[idx].text.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                idx++
                continue
            }
            return parseDocstring(lines, idx)
        }
        return null
    }

    private fun extractInnerDocstring(lines: List<Line>, startIndex: Int, endIndex: Int): Docstring? {
        var idx = startIndex
        while (idx <= endIndex) {
            val trimmed = lines[idx].text.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                idx++
                continue
            }
            return parseDocstring(lines, idx)
        }
        return null
    }

    private fun parseDocstring(lines: List<Line>, index: Int): Docstring? {
        val trimmed = lines[index].text.trim()
        if (!trimmed.startsWith("\"\"\"") && !trimmed.startsWith("'''") ) return null
        val delimiter = trimmed.take(3)
        val docLines = mutableListOf<Line>()
        docLines += lines[index]

        if (trimmed.length >= 6 && trimmed.indexOf(delimiter, 3) != -1) {
            return Docstring(docLines, index, index)
        }

        var i = index + 1
        while (i < lines.size) {
            docLines += lines[i]
            val current = lines[i].text.trim()
            if (current.contains(delimiter)) {
                return Docstring(docLines, index, i)
            }
            i++
        }
        return Docstring(docLines, index, lines.size - 1)
    }

    private fun findDefinitionStart(lines: List<Line>, index: Int): Int {
        var start = index
        var i = index - 1
        while (i >= 0) {
            val trimmed = lines[i].text.trim()
            if (trimmed.startsWith("@")) {
                start = i
                i--
                continue
            }
            break
        }
        return start
    }

    private fun findBlockEnd(lines: List<Line>, start: Int, indent: Int): Int {
        var last = start - 1
        var i = start
        while (i < lines.size) {
            val text = lines[i].text
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                last = i
                i++
                continue
            }
            val currentIndent = leadingSpaces(text)
            if (currentIndent <= indent && !trimmed.startsWith("#")) {
                break
            }
            last = i
            i++
        }
        return last.coerceAtLeast(start - 1)
    }

    private fun leadingSpaces(line: String): Int {
        var count = 0
        for (ch in line) {
            if (ch == ' ') count++ else if (ch == '\t') count += 4 else break
        }
        return count
    }

    private fun docLabel(type: DefinitionType, name: String): String = when (type) {
        DefinitionType.FUNCTION -> "Function $name docstring"
        DefinitionType.CLASS -> "Class $name docstring"
    }

    private fun codeLabel(type: DefinitionType, name: String, index: Int, total: Int): String {
        val base = when (type) {
            DefinitionType.FUNCTION -> "Function $name"
            DefinitionType.CLASS -> "Class $name"
        }
        return if (total > 1) "$base (part ${index + 1}/$total)" else base
    }

    private fun kindFor(type: DefinitionType): ChunkKind = when (type) {
        DefinitionType.FUNCTION -> ChunkKind.CODE_FUNCTION
        DefinitionType.CLASS -> ChunkKind.CODE_CLASS
    }

    private enum class DefinitionType { FUNCTION, CLASS }

    private data class Line(val number: Int, val text: String)

    private data class Docstring(val lines: List<Line>, val startIndex: Int, val endIndex: Int)

    private data class ChunkInput(val kind: ChunkKind, val lines: List<Line>, val label: String?)

    companion object {
        private const val DEFAULT_MAX_TOKENS = 600
        private const val DEFAULT_OVERLAP_RATIO = 0.15
        private val functionRegex = Regex("^def\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
        private val asyncFunctionRegex = Regex("^async\\s+def\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
        private val classRegex = Regex("^class\\s+([A-Za-z_][A-Za-z0-9_]*)")
    }
}
