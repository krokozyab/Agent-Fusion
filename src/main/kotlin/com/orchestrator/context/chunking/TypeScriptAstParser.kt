package com.orchestrator.context.chunking

import com.orchestrator.context.domain.ChunkKind

/**
 * Structural parser for TypeScript/JavaScript modules that extracts top-level export declarations
 * with stable line ranges and labels.
 */
class TypeScriptAstParser {

    data class ExportDeclaration(
        val startLine: Int,
        val endLine: Int,
        val kind: ChunkKind,
        val label: String?
    )

    data class ModuleAst(
        val lines: List<String>,
        val importLines: List<Int>,
        val exports: List<ExportDeclaration>
    )

    fun parse(content: String): ModuleAst {
        val lines = content.lines()
        if (lines.isEmpty()) return ModuleAst(lines, emptyList(), emptyList())

        val (imports, _) = collectTopImports(lines)
        val exports = collectExports(lines)

        return ModuleAst(
            lines = lines,
            importLines = imports.flatMap { range -> range.toList() }.distinct().sorted(),
            exports = exports
        )
    }

    private fun collectTopImports(lines: List<String>): Pair<List<IntRange>, Int> {
        val ranges = mutableListOf<IntRange>()
        var index = 0

        while (index < lines.size) {
            val trimmed = lines[index].trim()
            if (trimmed.isEmpty() && ranges.isNotEmpty()) {
                index++
                continue
            }
            if (trimmed.startsWith("//") && ranges.isEmpty()) {
                index++
                continue
            }
            if (!isImportStart(trimmed)) {
                break
            }

            val end = consumeStatement(lines, index)
            ranges += (index + 1)..(end + 1)
            index = end + 1
        }

        return ranges to index
    }

    private fun collectExports(lines: List<String>): List<ExportDeclaration> {
        val exports = mutableListOf<ExportDeclaration>()
        val state = DepthState()
        var index = 0

        while (index < lines.size) {
            val trimmed = lines[index].trimStart()
            val atTopLevel = !state.inBlockComment &&
                !state.inString &&
                state.braceDepth == 0 &&
                state.parenDepth == 0 &&
                state.bracketDepth == 0

            if (atTopLevel && trimmed.startsWith("export")) {
                val exportStart = findLeadingCommentStart(lines, index)
                val exportEnd = consumeExport(lines, index)
                val text = lines.subList(index, exportEnd + 1).joinToString("\n")
                val meta = classifyExport(text)
                exports += ExportDeclaration(
                    startLine = exportStart + 1,
                    endLine = exportEnd + 1,
                    kind = meta.kind,
                    label = meta.label
                )

                state.reset()
                index = exportEnd + 1
                continue
            }

            scanLine(lines[index], state)
            index++
        }

        return exports
    }

    private fun consumeStatement(lines: List<String>, startIndex: Int): Int {
        val state = DepthState()
        var index = startIndex
        while (index < lines.size) {
            val scan = scanLine(lines[index], state)
            val stable = state.braceDepth == 0 && state.parenDepth == 0 && state.bracketDepth == 0 &&
                !state.inString && !state.inBlockComment
            if (stable && (scan.topLevelSemicolon || !scan.continuationLikely)) {
                return index
            }
            index++
        }
        return lines.lastIndex
    }

    private fun consumeExport(lines: List<String>, startIndex: Int): Int {
        val state = DepthState()
        var index = startIndex
        while (index < lines.size) {
            val scan = scanLine(lines[index], state)
            val stable = state.braceDepth == 0 && state.parenDepth == 0 && state.bracketDepth == 0 &&
                !state.inString && !state.inBlockComment
            if (stable) {
                val next = nextSignificantLine(lines, index + 1)
                if (scan.topLevelSemicolon) {
                    return index
                }
                if (scan.endsWithBlock) {
                    if (next == null || next.startsWith("export")) {
                        return index
                    }
                } else if (next == null || next.startsWith("export")) {
                    return index
                }
            }
            index++
        }
        return lines.lastIndex
    }

    private fun nextSignificantLine(lines: List<String>, startIndex: Int): String? {
        var index = startIndex
        while (index < lines.size) {
            val trimmed = lines[index].trim()
            if (trimmed.isNotEmpty()) return trimmed
            index++
        }
        return null
    }

    private fun findLeadingCommentStart(lines: List<String>, exportStart: Int): Int {
        var cursor = exportStart - 1
        while (cursor >= 0 && lines[cursor].trim().isEmpty()) {
            cursor--
        }
        if (cursor < 0 || !lines[cursor].trimEnd().endsWith("*/")) return exportStart

        var start = cursor
        while (start >= 0) {
            val trimmed = lines[start].trim()
            if (trimmed.startsWith("/**") || trimmed.startsWith("/*")) {
                return start
            }
            start--
        }
        return exportStart
    }

    private fun classifyExport(text: String): DeclMeta {
        val normalized = text
            .replace(Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("//.*"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        exportDefaultClassRegex.find(normalized)?.let {
            return DeclMeta(ChunkKind.CODE_CLASS, "Class ${it.groupValues[1]}")
        }
        exportClassRegex.find(normalized)?.let {
            return DeclMeta(ChunkKind.CODE_CLASS, "Class ${it.groupValues[1]}")
        }
        exportInterfaceRegex.find(normalized)?.let {
            return DeclMeta(ChunkKind.CODE_CLASS, "Interface ${it.groupValues[1]}")
        }
        exportEnumRegex.find(normalized)?.let {
            return DeclMeta(ChunkKind.CODE_CLASS, "Type ${it.groupValues[1]}")
        }
        exportTypeRegex.find(normalized)?.let {
            return DeclMeta(ChunkKind.CODE_BLOCK, "Type ${it.groupValues[1]}")
        }
        exportFunctionRegex.find(normalized)?.let {
            val name = it.groupValues.getOrNull(1).orEmpty().ifBlank { "Default export" }
            return DeclMeta(ChunkKind.CODE_FUNCTION, if (name == "Default export") name else "Function $name")
        }
        exportConstRegex.find(normalized)?.let {
            val name = it.groupValues[1]
            val kind = if (normalized.contains("=>") || normalized.contains(" function")) {
                ChunkKind.CODE_FUNCTION
            } else {
                ChunkKind.CODE_BLOCK
            }
            return DeclMeta(kind, "Constant $name")
        }
        if (exportDefaultRegex.containsMatchIn(normalized)) {
            return DeclMeta(ChunkKind.CODE_FUNCTION, "Default export")
        }
        if (exportNamedRegex.containsMatchIn(normalized)) {
            return DeclMeta(ChunkKind.CODE_BLOCK, "Named export")
        }
        return DeclMeta(ChunkKind.CODE_BLOCK, null)
    }

    private fun isImportStart(trimmed: String): Boolean =
        trimmed.startsWith("import ") ||
            trimmed.startsWith("import{") ||
            trimmed.startsWith("import type") ||
            trimmed.startsWith("import*")

    private fun scanLine(line: String, state: DepthState): ScanResult {
        var topLevelSemicolon = false
        var continuationLikely = false
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

            if (state.inString) {
                if (ch == '\\' && next != null) {
                    i += 2
                    continue
                }
                if (ch == state.stringDelimiter && state.stringDelimiter != '`') {
                    state.inString = false
                    state.stringDelimiter = null
                    i++
                    continue
                }
                if (state.stringDelimiter == '`') {
                    if (ch == '`') {
                        state.inString = false
                        state.stringDelimiter = null
                        i++
                        continue
                    }
                    if (ch == '$' && next == '{') {
                        state.braceDepth++
                        i += 2
                        continue
                    }
                }
                if (state.stringDelimiter == '`' && ch == '}') {
                    state.braceDepth = (state.braceDepth - 1).coerceAtLeast(0)
                }
                i++
                continue
            }

            when {
                ch == '/' && next == '/' -> break
                ch == '/' && next == '*' -> {
                    state.inBlockComment = true
                    i += 2
                    continue
                }
                ch == '\'' || ch == '"' -> {
                    state.inString = true
                    state.stringDelimiter = ch
                    i++
                    continue
                }
                ch == '`' -> {
                    state.inString = true
                    state.stringDelimiter = '`'
                    i++
                    continue
                }
                ch == '{' -> state.braceDepth++
                ch == '}' -> state.braceDepth = (state.braceDepth - 1).coerceAtLeast(0)
                ch == '(' -> state.parenDepth++
                ch == ')' -> state.parenDepth = (state.parenDepth - 1).coerceAtLeast(0)
                ch == '[' -> state.bracketDepth++
                ch == ']' -> state.bracketDepth = (state.bracketDepth - 1).coerceAtLeast(0)
                ch == ';' && state.braceDepth == 0 && state.parenDepth == 0 && state.bracketDepth == 0 -> {
                    topLevelSemicolon = true
                }
            }
            i++
        }

        val trimmed = line.trimEnd()
        continuationLikely = trimmed.endsWith(",") ||
            trimmed.endsWith("(") ||
            trimmed.endsWith("[") ||
            trimmed.endsWith("{") ||
            trimmed.endsWith("\\")

        return ScanResult(
            topLevelSemicolon = topLevelSemicolon,
            continuationLikely = continuationLikely,
            endsWithBlock = trimmed.endsWith("}")
        )
    }

    private data class ScanResult(
        val topLevelSemicolon: Boolean,
        val continuationLikely: Boolean,
        val endsWithBlock: Boolean
    )

    private data class DepthState(
        var braceDepth: Int = 0,
        var parenDepth: Int = 0,
        var bracketDepth: Int = 0,
        var inString: Boolean = false,
        var stringDelimiter: Char? = null,
        var inBlockComment: Boolean = false
    ) {
        fun reset() {
            braceDepth = 0
            parenDepth = 0
            bracketDepth = 0
            inString = false
            stringDelimiter = null
            inBlockComment = false
        }
    }

    private data class DeclMeta(
        val kind: ChunkKind,
        val label: String?
    )

    companion object {
        private val exportFunctionRegex = Regex(
            "^export\\s+(?:default\\s+)?(?:async\\s+)?function\\*?\\s*([A-Za-z_\\$][A-Za-z0-9_\\$]*)?"
        )
        private val exportConstRegex = Regex("^export\\s+(?:default\\s+)?(?:const|let|var)\\s+([A-Za-z_\\$][A-Za-z0-9_\\$]*)")
        private val exportClassRegex = Regex("^export\\s+class\\s+([A-Za-z_\\$][A-Za-z0-9_\\$]*)")
        private val exportDefaultClassRegex = Regex("^export\\s+default\\s+class\\s+([A-Za-z_\\$][A-Za-z0-9_\\$]*)")
        private val exportInterfaceRegex = Regex("^export\\s+interface\\s+([A-Za-z_\\$][A-Za-z0-9_\\$]*)")
        private val exportEnumRegex = Regex("^export\\s+enum\\s+([A-Za-z_\\$][A-Za-z0-9_\\$]*)")
        private val exportTypeRegex = Regex("^export\\s+type\\s+([A-Za-z_\\$][A-Za-z0-9_\\$]*)")
        private val exportDefaultRegex = Regex("^export\\s+default\\b")
        private val exportNamedRegex = Regex("^export\\s*\\{")
    }
}
