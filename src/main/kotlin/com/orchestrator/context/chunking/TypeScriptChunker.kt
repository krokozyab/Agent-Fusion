package com.orchestrator.context.chunking

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import java.time.Instant

/**
 * Heuristic TypeScript/JavaScript chunker that groups exports with their JSDoc and leading imports.
 */
class TypeScriptChunker(
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    private val estimator: TokenEstimator = TokenEstimator
) : Chunker {

    override val strategy: ChunkingStrategy = ChunkingStrategy(
        id = "typescript",
        displayName = "TypeScript Exports",
        supportedLanguages = setOf("typescript", "javascript", "ts", "tsx", "js", "jsx"),
        defaultMaxTokens = maxTokens,
        description = "Splits modules by exported declarations while preserving JSDoc and imports."
    )

    override fun chunk(content: String, filePath: String, language: String): List<Chunk> {
        if (content.isBlank()) return emptyList()

        val lines = content.lines().mapIndexed { index, text -> Line(index, index + 1, text) }
        val (imports, startIndex) = extractImports(lines)
        val blocks = mutableListOf<ExportBlock>()

        var index = startIndex
        while (index < lines.size) {
            val trimmed = lines[index].text.trim()
            if (trimmed.startsWith("export")) {
                val block = collectExportBlock(lines, index)
                blocks += block
                index = block.endIndex + 1
            } else {
                index++
            }
        }

        if (blocks.isEmpty()) {
            val now = Instant.now()
            return listOf(
                Chunk(
                    id = 0,
                    fileId = 0,
                    ordinal = 0,
                    kind = ChunkKind.CODE_BLOCK,
                    startLine = 1,
                    endLine = lines.last().number,
                    tokenEstimate = estimator.estimate(content),
                    content = content,
                    summary = null,
                    createdAt = now
                )
            )
        }

        val now = Instant.now()
        val importText = imports.joinToString("\n") { it.text }.trim()
        val importTokens = if (importText.isEmpty()) 0 else estimator.estimate(importText)
        val includeImports = importText.isNotEmpty() && importTokens < maxTokens
        val availableTokens = if (includeImports) (maxTokens - importTokens).coerceAtLeast(64) else maxTokens

        val chunks = mutableListOf<Chunk>()
        blocks.forEach { block ->
            val pieces = splitLines(block.lines, availableTokens)
            pieces.forEachIndexed { pieceIndex, pieceLines ->
                val body = pieceLines.joinToString("\n") { it.text }.trimEnd()
                var finalText = body
                if (includeImports) {
                    val candidate = buildString {
                        append(importText)
                        append("\n\n")
                        append(body)
                    }
                    finalText = if (estimator.estimate(candidate) <= maxTokens) candidate else body
                }

                val label = block.label?.let {
                    if (pieces.size > 1) "$it (part ${pieceIndex + 1}/${pieces.size})" else it
                }

                chunks += Chunk(
                    id = 0,
                    fileId = 0,
                    ordinal = chunks.size,
                    kind = block.kind,
                    startLine = pieceLines.firstOrNull()?.number,
                    endLine = pieceLines.lastOrNull()?.number,
                    tokenEstimate = estimator.estimate(finalText),
                    content = finalText,
                    summary = label,
                    createdAt = now
                )
            }
        }

        return chunks
    }

    override fun estimateTokens(text: String): Int = estimator.estimate(text)

    private fun extractImports(lines: List<Line>): Pair<List<Line>, Int> {
        val imports = mutableListOf<Line>()
        var index = 0
        while (index < lines.size) {
            val trimmed = lines[index].text.trim()
            if (trimmed.isEmpty() && imports.isNotEmpty()) {
                imports += lines[index]
                index++
                continue
            }
            if (trimmed.startsWith("import ") || trimmed.startsWith("import{") ||
                trimmed.startsWith("import type") || trimmed.startsWith("import*")
            ) {
                imports += lines[index]
                index++
                continue
            }
            break
        }
        return imports to index
    }

    private fun collectExportBlock(lines: List<Line>, startIndex: Int): ExportBlock {
        val collected = mutableListOf<Line>()
        collected += collectJsDoc(lines, startIndex)

        val state = DepthState()
        var index = startIndex
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.text.trim()
            if (index == startIndex || !trimmed.startsWith("import ")) {
                collected += line
            }
            updateDepth(state, line.text)

            val nextTrimmed = lines.getOrNull(index + 1)?.text?.trim()
            val endOfStatement = trimmed.endsWith(";") || trimmed.endsWith("}") || trimmed == "}" || trimmed.endsWith(")")
            val nextIsExport = nextTrimmed != null && nextTrimmed.startsWith("export") && state.braceDepth <= 0 && state.parenDepth <= 0

            if (state.braceDepth <= 0 && state.parenDepth <= 0 && (endOfStatement || nextIsExport)) {
                return ExportBlock(
                    lines = collected,
                    label = deriveLabel(lines[startIndex].text),
                    kind = kindFor(lines[startIndex].text),
                    endIndex = index
                )
            }
            index++
        }

        return ExportBlock(
            lines = collected,
            label = deriveLabel(lines[startIndex].text),
            kind = kindFor(lines[startIndex].text),
            endIndex = lines.lastIndex
        )
    }

    private fun collectJsDoc(lines: List<Line>, startIndex: Int): List<Line> {
        var index = startIndex - 1
        while (index >= 0 && lines[index].text.trim().isBlank()) {
            index--
        }
        if (index < 0) return emptyList()
        if (!lines[index].text.trim().endsWith("*/")) return emptyList()

        val docLines = mutableListOf<Line>()
        var cursor = index
        while (cursor >= 0) {
            val trimmed = lines[cursor].text.trim()
            docLines.add(0, lines[cursor])
            if (trimmed.startsWith("/**")) break
            cursor--
        }
        return docLines
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

    private fun deriveLabel(line: String): String? {
        val trimmed = line.trim()
        exportFunctionRegex.find(trimmed)?.let { return "Function ${it.groupValues[1]}" }
        exportConstRegex.find(trimmed)?.let { return "Constant ${it.groupValues[1]}" }
        exportClassRegex.find(trimmed)?.let { return "Class ${it.groupValues[1]}" }
        exportInterfaceRegex.find(trimmed)?.let { return "Interface ${it.groupValues[1]}" }
        exportTypeRegex.find(trimmed)?.let { return "Type ${it.groupValues[1]}" }
        if (exportDefaultRegex.find(trimmed) != null) return "Default export"
        return null
    }

    private fun kindFor(line: String): ChunkKind {
        val trimmed = line.trim()
        return when {
            trimmed.contains(" class ") || trimmed.startsWith("export class") || trimmed.startsWith("export default class") ||
                trimmed.startsWith("export interface") || trimmed.startsWith("export enum") -> ChunkKind.CODE_CLASS
            trimmed.contains(" function ") || trimmed.startsWith("export function") ||
                trimmed.startsWith("export default function") || trimmed.contains("=>") -> ChunkKind.CODE_FUNCTION
            else -> ChunkKind.CODE_BLOCK
        }
    }

    private fun updateDepth(state: DepthState, line: String) {
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            val next = line.getOrNull(i + 1)

            if (state.inBlockComment) {
                if (ch == '*' && next == '/') {
                    state.inBlockComment = false
                    i += 2
                    continue
                }
                i++
                continue
            }

            if (state.inString != null) {
                if (ch == '\\' && next != null) {
                    i += 2
                    continue
                }
                if (ch == state.inString && state.inString != '`') {
                    state.inString = null
                    i++
                    continue
                }
                if (state.inString == '`') {
                    if (ch == '`') {
                        state.inString = null
                        i++
                        continue
                    }
                    if (ch == '$' && next == '{') {
                        state.braceDepth++
                        i += 2
                        continue
                    }
                }
                if (state.inString == '`' && ch == '}') {
                    state.braceDepth--
                }
                i++
                continue
            }

            when {
                ch == '/' && next == '/' -> return
                ch == '/' && next == '*' -> {
                    state.inBlockComment = true
                    i += 2
                    continue
                }
                ch == '\'' || ch == '"' -> {
                    state.inString = ch
                    i++
                    continue
                }
                ch == '`' -> {
                    state.inString = '`'
                    i++
                    continue
                }
                ch == '{' -> state.braceDepth++
                ch == '}' -> state.braceDepth--
                ch == '(' -> state.parenDepth++
                ch == ')' -> state.parenDepth--
            }
            i++
        }
        if (state.braceDepth < 0) state.braceDepth = 0
        if (state.parenDepth < 0) state.parenDepth = 0
    }

    private data class Line(val index: Int, val number: Int, val text: String)

    private data class ExportBlock(
        val lines: List<Line>,
        val label: String?,
        val kind: ChunkKind,
        val endIndex: Int
    )

    private data class DepthState(
        var braceDepth: Int = 0,
        var parenDepth: Int = 0,
        var inString: Char? = null,
        var inBlockComment: Boolean = false
    )

    companion object {
        private const val DEFAULT_MAX_TOKENS = 600
        private val exportFunctionRegex = Regex("^export\\s+(?:default\\s+)?(?:async\\s+)?function\\s+([A-Za-z_\\$][A-Za-z0-9_\\$]*)")
        private val exportConstRegex = Regex("^export\\s+(?:default\\s+)?(?:const|let|var)\\s+([A-Za-z_\\$][A-Za-z0-9_\\$]*)")
        private val exportClassRegex = Regex("^export\\s+(?:default\\s+)?class\\s+([A-Za-z_\\$][A-Za-z0-9_\\$]*)")
        private val exportInterfaceRegex = Regex("^export\\s+(?:default\\s+)?interface\\s+([A-Za-z_\\$][A-Za-z0-9_\\$]*)")
        private val exportTypeRegex = Regex("^export\\s+(?:default\\s+)?(?:type|enum)\\s+([A-Za-z_\\$][A-Za-z0-9_\\$]*)")
        private val exportDefaultRegex = Regex("^export\\s+default\\b")
    }
}
