package com.orchestrator.context.chunking

/**
 * Lightweight Python parser that builds a structural AST for top-level and nested class/function
 * definitions using lexical scanning and indentation rules (no regex-only block matching).
 */
class PythonAstParser {

    enum class DefinitionType { FUNCTION, CLASS }

    data class Docstring(
        val startLine: Int,
        val endLine: Int,
        val text: String
    )

    data class Definition(
        val type: DefinitionType,
        val name: String,
        val startLine: Int,
        val endLine: Int,
        val headerStartLine: Int,
        val headerEndLine: Int,
        val docstring: Docstring?
    )

    data class ModuleAst(
        val lines: List<String>,
        val moduleDocstring: Docstring?,
        val definitions: List<Definition>
    )

    fun parse(content: String): ModuleAst {
        val lines = content.lines()
        if (lines.isEmpty()) return ModuleAst(lines, null, emptyList())

        val moduleDocstring = extractModuleDocstring(lines)
        val definitions = mutableListOf<Definition>()
        val pendingDecorators = mutableMapOf<Int, MutableList<Int>>()

        var index = 0
        while (index < lines.size) {
            val raw = lines[index]
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                pendingDecorators.clear()
                index++
                continue
            }

            if (trimmed.startsWith("#")) {
                index++
                continue
            }

            val indent = leadingSpaces(raw)
            if (trimmed.startsWith("@")) {
                val end = consumeContinuedStatement(lines, index)
                val decorators = pendingDecorators.getOrPut(indent) { mutableListOf() }
                for (lineIndex in index..end) {
                    decorators += lineIndex + 1
                }
                index = end + 1
                continue
            }

            val isDefinitionStart = trimmed.startsWith("def ") ||
                trimmed.startsWith("async def ") ||
                trimmed.startsWith("class ")
            if (!isDefinitionStart) {
                pendingDecorators.remove(indent)
                index++
                continue
            }

            val headerEndIndex = consumeHeaderStatement(lines, index)
            val signatureText = lines.subList(index, headerEndIndex + 1).joinToString("\n")
            val signature = parseSignature(signatureText)
            if (signature == null) {
                pendingDecorators.remove(indent)
                index = headerEndIndex + 1
                continue
            }

            val startLine = pendingDecorators.remove(indent)?.firstOrNull() ?: (index + 1)
            val (endLine, bodyStartLine) = locateDefinitionBlock(lines, headerEndIndex, indent)
            val docstring = bodyStartLine?.let { bodyStart ->
                extractDocstring(lines, bodyStart - 1, endLine - 1)
            }

            definitions += Definition(
                type = signature.type,
                name = signature.name,
                startLine = startLine,
                endLine = endLine.coerceAtLeast(startLine),
                headerStartLine = index + 1,
                headerEndLine = headerEndIndex + 1,
                docstring = docstring
            )

            index = headerEndIndex + 1
        }

        return ModuleAst(
            lines = lines,
            moduleDocstring = moduleDocstring,
            definitions = definitions
                .sortedWith(compareBy<Definition> { it.startLine }.thenBy { it.endLine })
                .distinctBy { "${it.type}:${it.name}:${it.startLine}:${it.endLine}" }
        )
    }

    private fun extractModuleDocstring(lines: List<String>): Docstring? {
        var index = 0
        while (index < lines.size) {
            val trimmed = lines[index].trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                index++
                continue
            }
            return extractDocstring(lines, index, lines.lastIndex)
        }
        return null
    }

    private fun consumeContinuedStatement(lines: List<String>, startIndex: Int): Int {
        var index = startIndex
        val state = ScannerState()
        while (index < lines.size) {
            val line = lines[index]
            scanLine(line, state, trackColon = false)
            val hasLineContinuation = line.trimEnd().endsWith("\\")
            val complete = !state.inString && !state.inTripleString &&
                state.parenDepth == 0 && state.bracketDepth == 0 && state.braceDepth == 0 &&
                !hasLineContinuation
            if (complete) return index
            index++
        }
        return lines.lastIndex
    }

    private fun consumeHeaderStatement(lines: List<String>, startIndex: Int): Int {
        var index = startIndex
        val state = ScannerState()
        while (index < lines.size) {
            val line = lines[index]
            scanLine(line, state, trackColon = true)
            val hasLineContinuation = line.trimEnd().endsWith("\\")
            val complete = state.colonSeen &&
                !state.inString && !state.inTripleString &&
                state.parenDepth == 0 && state.bracketDepth == 0 && state.braceDepth == 0 &&
                !hasLineContinuation
            if (complete) return index
            index++
        }
        return lines.lastIndex
    }

    private fun locateDefinitionBlock(
        lines: List<String>,
        headerEndIndex: Int,
        baseIndent: Int
    ): Pair<Int, Int?> {
        val headerLine = lines[headerEndIndex]
        val inlineSuite = headerLine.substringAfter(":", missingDelimiterValue = "")
            .trim()
            .isNotEmpty()
        if (inlineSuite) {
            return (headerEndIndex + 1) to (headerEndIndex + 1)
        }

        var bodyStartIndex: Int? = null
        var lastLineInBlock = headerEndIndex
        var index = headerEndIndex + 1
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (bodyStartIndex != null) {
                    lastLineInBlock = index
                }
                index++
                continue
            }

            val indent = leadingSpaces(line)
            if (bodyStartIndex == null) {
                if (trimmed.startsWith("#")) {
                    index++
                    continue
                }
                if (indent <= baseIndent) {
                    return (headerEndIndex + 1) to null
                }
                bodyStartIndex = index
                lastLineInBlock = index
                index++
                continue
            }

            if (indent <= baseIndent && !trimmed.startsWith("#")) {
                break
            }
            lastLineInBlock = index
            index++
        }

        val endLine = (lastLineInBlock + 1).coerceAtLeast(headerEndIndex + 1)
        val bodyStartLine = bodyStartIndex?.plus(1)
        return endLine to bodyStartLine
    }

    private fun parseSignature(text: String): ParsedSignature? {
        val normalized = text.trimStart()
        val functionMatch = asyncFunctionRegex.find(normalized) ?: functionRegex.find(normalized)
        if (functionMatch != null) {
            return ParsedSignature(DefinitionType.FUNCTION, functionMatch.groupValues[1])
        }
        val classMatch = classRegex.find(normalized)
        if (classMatch != null) {
            return ParsedSignature(DefinitionType.CLASS, classMatch.groupValues[1])
        }
        return null
    }

    private fun extractDocstring(lines: List<String>, startIndex: Int, endIndex: Int): Docstring? {
        var index = startIndex
        while (index <= endIndex && index < lines.size) {
            val trimmed = lines[index].trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                index++
                continue
            }

            val delimiter = detectTripleQuote(trimmed) ?: return null
            val startLine = index + 1
            if (containsClosingTripleQuote(trimmed, delimiter)) {
                return Docstring(startLine, startLine, lines[index].trim())
            }

            var cursor = index + 1
            while (cursor <= endIndex && cursor < lines.size) {
                if (lines[cursor].contains(delimiter)) {
                    return Docstring(
                        startLine = startLine,
                        endLine = cursor + 1,
                        text = lines.subList(index, cursor + 1).joinToString("\n")
                    )
                }
                cursor++
            }

            return Docstring(
                startLine = startLine,
                endLine = endIndex + 1,
                text = lines.subList(index, endIndex + 1).joinToString("\n")
            )
        }
        return null
    }

    private fun containsClosingTripleQuote(line: String, delimiter: String): Boolean {
        val firstIndex = line.indexOf(delimiter)
        if (firstIndex < 0) return false
        val secondIndex = line.indexOf(delimiter, firstIndex + delimiter.length)
        return secondIndex >= 0
    }

    private fun detectTripleQuote(trimmed: String): String? {
        val match = tripleQuotePrefix.find(trimmed) ?: return null
        return match.groupValues[1]
    }

    private fun leadingSpaces(line: String): Int {
        var count = 0
        for (ch in line) {
            if (ch == ' ') count++ else if (ch == '\t') count += 4 else break
        }
        return count
    }

    private fun scanLine(line: String, state: ScannerState, trackColon: Boolean) {
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            val next = line.getOrNull(i + 1)
            val nextTwo = if (i + 2 < line.length) line.substring(i, i + 3) else null

            if (state.inTripleString) {
                if (nextTwo == state.tripleDelimiter) {
                    state.inTripleString = false
                    state.tripleDelimiter = null
                    i += 3
                    continue
                }
                i++
                continue
            }

            if (state.inString) {
                if (ch == '\\') {
                    i += 2
                    continue
                }
                if (ch == state.stringDelimiter) {
                    state.inString = false
                    state.stringDelimiter = null
                }
                i++
                continue
            }

            if (ch == '#') {
                return
            }

            if (nextTwo == "\"\"\"" || nextTwo == "'''") {
                state.inTripleString = true
                state.tripleDelimiter = nextTwo
                i += 3
                continue
            }

            if (ch == '"' || ch == '\'') {
                state.inString = true
                state.stringDelimiter = ch
                i++
                continue
            }

            when (ch) {
                '(' -> state.parenDepth++
                ')' -> state.parenDepth = (state.parenDepth - 1).coerceAtLeast(0)
                '[' -> state.bracketDepth++
                ']' -> state.bracketDepth = (state.bracketDepth - 1).coerceAtLeast(0)
                '{' -> state.braceDepth++
                '}' -> state.braceDepth = (state.braceDepth - 1).coerceAtLeast(0)
                ':' -> if (trackColon &&
                    state.parenDepth == 0 &&
                    state.bracketDepth == 0 &&
                    state.braceDepth == 0
                ) {
                    state.colonSeen = true
                }
            }

            if (ch == '\\' && next != null) {
                i += 2
            } else {
                i++
            }
        }
    }

    private data class ParsedSignature(
        val type: DefinitionType,
        val name: String
    )

    private data class ScannerState(
        var parenDepth: Int = 0,
        var bracketDepth: Int = 0,
        var braceDepth: Int = 0,
        var inString: Boolean = false,
        var stringDelimiter: Char? = null,
        var inTripleString: Boolean = false,
        var tripleDelimiter: String? = null,
        var colonSeen: Boolean = false
    )

    companion object {
        private val tripleQuotePrefix = Regex("^(?:[rRuUbBfF]{0,2})(\"\"\"|''')")
        private val functionRegex = Regex("^def\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
        private val asyncFunctionRegex = Regex("^async\\s+def\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
        private val classRegex = Regex("^class\\s+([A-Za-z_][A-Za-z0-9_]*)")
    }
}
