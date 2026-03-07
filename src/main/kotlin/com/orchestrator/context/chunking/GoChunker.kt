package com.orchestrator.context.chunking

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight structural splitter for Go source files.
 *
 * It extracts top-level declarations (`type`, `func`, `const`, `var`) with leading comments,
 * emits a header chunk for package/import blocks, and keeps line ranges stable for navigation.
 */
class GoChunker(
    private val maxTokens: Int = 600,
    private val overlapPercent: Int = 15
) : SimpleChunker {

    override fun chunk(content: String, filePath: String): List<Chunk> {
        if (content.isBlank()) return emptyList()

        val lines = content.lines()
        val packageName = extractPackageName(lines)
        val declarations = collectTopLevelDeclarations(lines)
        val chunks = mutableListOf<Chunk>()
        var ordinal = 0

        val headerEndExclusive = declarations.firstOrNull()?.chunkStart ?: lines.size
        if (headerEndExclusive > 0) {
            val headerText = lines.subList(0, headerEndExclusive).joinToString("\n").trimEnd()
            if (headerText.isNotBlank() && estimateTokens(headerText) <= 240) {
                chunks += createChunk(
                    text = headerText,
                    kind = ChunkKind.CODE_HEADER,
                    label = "header",
                    ordinal = ordinal++,
                    startLine = 1,
                    endLine = headerEndExclusive,
                    packageName = packageName
                )
            }
        }

        declarations.forEachIndexed { index, decl ->
            val nextStart = declarations.getOrNull(index + 1)?.chunkStart ?: lines.size
            var declEnd = (nextStart - 1).coerceAtLeast(decl.declStart)
            while (declEnd >= decl.declStart && lines[declEnd].trim().isEmpty()) {
                declEnd--
            }
            if (declEnd < decl.declStart) {
                declEnd = decl.declStart
            }

            val fullText = joinLines(lines, decl.chunkStart, declEnd)
            if (fullText.isBlank()) return@forEachIndexed

            val meta = classifyDeclaration(lines, decl, declEnd)
            val declarationChunks = splitIfNeeded(
                text = fullText,
                kind = meta.kind,
                label = meta.label,
                ordinal = ordinal,
                startLine = decl.chunkStart + 1,
                endLine = declEnd + 1,
                packageName = packageName
            )
            chunks += declarationChunks
            ordinal = chunks.size
        }

        if (chunks.isEmpty()) {
            return listOf(
                createChunk(
                    text = content.trimEnd(),
                    kind = ChunkKind.CODE_BLOCK,
                    label = "module",
                    ordinal = 0,
                    startLine = 1,
                    endLine = lines.size.coerceAtLeast(1),
                    packageName = packageName
                )
            )
        }

        return OverlapProcessor.addOverlap(chunks, overlapPercent, ::estimateTokens)
            .map { it.copy(tokenEstimate = (it.tokenEstimate ?: estimateTokens(it.content)).coerceAtMost(maxTokens)) }
    }

    private fun collectTopLevelDeclarations(lines: List<String>): List<Declaration> {
        val declarations = mutableListOf<Declaration>()
        val state = ScanState()

        for ((index, line) in lines.withIndex()) {
            val atTopLevel =
                !state.inBlockComment &&
                    !state.inString &&
                    state.braceDepth == 0 &&
                    state.parenDepth == 0 &&
                    state.bracketDepth == 0

            if (atTopLevel) {
                val trimmed = line.trimStart()
                val keyword = declarationRegex.find(trimmed)?.groupValues?.get(1)
                if (keyword != null) {
                    val chunkStart = findLeadingCommentStart(lines, index)
                    declarations += Declaration(
                        keyword = keyword,
                        declStart = index,
                        chunkStart = chunkStart
                    )
                }
            }

            scanLine(line, state)
        }

        return declarations.distinctBy { it.declStart }
    }

    private fun findLeadingCommentStart(lines: List<String>, declStart: Int): Int {
        var cursor = declStart - 1
        while (cursor >= 0) {
            val trimmed = lines[cursor].trim()
            if (trimmed.isEmpty()) break

            if (trimmed.startsWith("//")) {
                cursor--
                continue
            }

            if (trimmed.endsWith("*/")) {
                var blockStart = cursor
                while (blockStart >= 0) {
                    val blockLine = lines[blockStart]
                    val blockTrimmed = blockLine.trim()
                    if (blockTrimmed.isEmpty()) return cursor + 1
                    if (blockLine.contains("/*")) {
                        cursor = blockStart - 1
                        break
                    }
                    blockStart--
                }
                if (blockStart < 0) return cursor + 1
                continue
            }

            break
        }
        return cursor + 1
    }

    private fun classifyDeclaration(lines: List<String>, declaration: Declaration, declEnd: Int): DeclarationMeta {
        val declarationText = joinLines(lines, declaration.declStart, declEnd)
        val normalized = declarationText.trimStart()

        return when (declaration.keyword) {
            "func" -> classifyFunction(normalized)
            "type" -> classifyType(normalized)
            "const" -> classifyVarOrConst(normalized, "const")
            "var" -> classifyVarOrConst(normalized, "var")
            else -> DeclarationMeta(ChunkKind.CODE_BLOCK, declaration.keyword)
        }
    }

    private fun classifyFunction(normalized: String): DeclarationMeta {
        methodRegex.find(normalized)?.let { match ->
            val receiver = match.groupValues[1]
            val methodName = match.groupValues[2]
            val receiverType = extractReceiverType(receiver)
            return DeclarationMeta(ChunkKind.CODE_METHOD, "$receiverType.$methodName")
        }

        functionRegex.find(normalized)?.let { match ->
            return DeclarationMeta(ChunkKind.CODE_FUNCTION, match.groupValues[1])
        }

        return DeclarationMeta(ChunkKind.CODE_FUNCTION, "function")
    }

    private fun classifyType(normalized: String): DeclarationMeta {
        typeStructRegex.find(normalized)?.let { match ->
            return DeclarationMeta(ChunkKind.CODE_CLASS, match.groupValues[1])
        }

        typeInterfaceRegex.find(normalized)?.let { match ->
            return DeclarationMeta(ChunkKind.CODE_INTERFACE, match.groupValues[1])
        }

        typeNameRegex.find(normalized)?.let { match ->
            return DeclarationMeta(ChunkKind.CODE_BLOCK, match.groupValues[1])
        }

        return DeclarationMeta(ChunkKind.CODE_BLOCK, "type")
    }

    private fun classifyVarOrConst(normalized: String, keyword: String): DeclarationMeta {
        varOrConstNameRegex.find(normalized)?.let { match ->
            val name = match.groupValues[2]
            return DeclarationMeta(ChunkKind.CODE_BLOCK, "$keyword $name")
        }

        return DeclarationMeta(ChunkKind.CODE_BLOCK, "$keyword block")
    }

    private fun extractReceiverType(receiver: String): String {
        val trimmed = receiver.trim()
        if (trimmed.isEmpty()) return "receiver"

        var typeToken = trimmed.substringAfterLast(' ', trimmed)
        typeToken = typeToken.removePrefix("*")
        typeToken = typeToken.substringBefore("[")
        typeToken = typeToken.substringAfterLast('.')

        if (identifierRegex.matches(typeToken)) {
            return typeToken
        }

        return identifierRegex.find(typeToken)?.value ?: "receiver"
    }

    private fun splitIfNeeded(
        text: String,
        kind: ChunkKind,
        label: String,
        ordinal: Int,
        startLine: Int,
        endLine: Int,
        packageName: String?
    ): List<Chunk> {
        val tokens = estimateTokens(text)
        if (tokens <= maxTokens) {
            return listOf(createChunk(text, kind, label, ordinal, startLine, endLine, packageName))
        }

        val lines = text.lines()
        val splitChunks = mutableListOf<Chunk>()
        val tokensPerLine = (tokens.toDouble() / lines.size).coerceAtLeast(1.0)
        val linesPerChunk = maxOf(1, (maxTokens / tokensPerLine).toInt())
        val overlapLines = maxOf(1, (linesPerChunk * (overlapPercent / 100.0)).toInt())

        var start = 0
        var chunkOrdinal = ordinal
        while (start < lines.size) {
            val endExclusive = (start + linesPerChunk).coerceAtMost(lines.size)
            val chunkText = lines.subList(start, endExclusive).joinToString("\n")
            val chunkStartLine = startLine + start
            val chunkEndLine = startLine + (endExclusive - 1)
            splitChunks += createChunk(
                text = chunkText,
                kind = kind,
                label = "$label[${chunkOrdinal - ordinal}]",
                ordinal = chunkOrdinal++,
                startLine = chunkStartLine,
                endLine = chunkEndLine,
                packageName = packageName
            )

            start = (endExclusive - overlapLines).coerceAtLeast(start + 1)
            if (start >= lines.size) break
        }

        return splitChunks
    }

    private fun extractPackageName(lines: List<String>): String? {
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*")) continue
            val match = packageRegex.find(trimmed) ?: return null
            return match.groupValues[1]
        }
        return null
    }

    private fun scanLine(line: String, state: ScanState) {
        var index = 0
        while (index < line.length) {
            val char = line[index]
            val next = line.getOrNull(index + 1)

            if (state.inBlockComment) {
                if (char == '*' && next == '/') {
                    state.inBlockComment = false
                    index += 2
                    continue
                }
                index++
                continue
            }

            if (state.inString) {
                if (state.stringDelimiter == '`') {
                    if (char == '`') {
                        state.inString = false
                        state.stringDelimiter = null
                    }
                    index++
                    continue
                }

                if (char == '\\' && next != null) {
                    index += 2
                    continue
                }

                if (char == state.stringDelimiter) {
                    state.inString = false
                    state.stringDelimiter = null
                }
                index++
                continue
            }

            when {
                char == '/' && next == '/' -> break
                char == '/' && next == '*' -> {
                    state.inBlockComment = true
                    index += 2
                    continue
                }
                char == '"' || char == '\'' || char == '`' -> {
                    state.inString = true
                    state.stringDelimiter = char
                    index++
                    continue
                }
                char == '{' -> state.braceDepth++
                char == '}' -> state.braceDepth = (state.braceDepth - 1).coerceAtLeast(0)
                char == '(' -> state.parenDepth++
                char == ')' -> state.parenDepth = (state.parenDepth - 1).coerceAtLeast(0)
                char == '[' -> state.bracketDepth++
                char == ']' -> state.bracketDepth = (state.bracketDepth - 1).coerceAtLeast(0)
            }
            index++
        }
    }

    private fun joinLines(lines: List<String>, start: Int, end: Int): String {
        val clampedStart = max(0, start)
        val clampedEnd = min(lines.lastIndex, end)
        if (clampedStart > clampedEnd) return ""
        return lines.subList(clampedStart, clampedEnd + 1).joinToString("\n").trimEnd()
    }

    private fun createChunk(
        text: String,
        kind: ChunkKind,
        label: String,
        ordinal: Int,
        startLine: Int?,
        endLine: Int?,
        packageName: String?
    ): Chunk {
        val validStartLine = startLine?.coerceAtLeast(1) ?: 1
        val validEndLine = endLine?.coerceAtLeast(validStartLine) ?: validStartLine
        val path = buildPath(kind, packageName, label)
        return Chunk(
            id = 0L,
            fileId = 0L,
            ordinal = ordinal,
            kind = kind,
            startLine = validStartLine,
            endLine = validEndLine,
            tokenEstimate = estimateTokens(text).coerceAtMost(maxTokens),
            content = text,
            summary = label,
            createdAt = Instant.now(),
            chunkPath = path
        )
    }

    private fun buildPath(kind: ChunkKind, packageName: String?, label: String): String {
        val pkgSegment = packageName?.replace('.', '/')?.takeIf { it.isNotBlank() }
        return ChunkPaths.path(kind, listOf(pkgSegment, label))
    }

    private fun estimateTokens(text: String): Int = text.length / 4

    private data class ScanState(
        var inBlockComment: Boolean = false,
        var inString: Boolean = false,
        var stringDelimiter: Char? = null,
        var braceDepth: Int = 0,
        var parenDepth: Int = 0,
        var bracketDepth: Int = 0
    )

    private data class Declaration(
        val keyword: String,
        val declStart: Int,
        val chunkStart: Int
    )

    private data class DeclarationMeta(
        val kind: ChunkKind,
        val label: String
    )

    companion object {
        private val declarationRegex = Regex("""^(func|type|const|var)\b""")
        private val packageRegex = Regex("""^package\s+([A-Za-z_][A-Za-z0-9_]*)\b""")
        private val functionRegex = Regex("""^func\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:\[[^\]]+])?\s*\(""")
        private val methodRegex = Regex("""^func\s*\(([^)]*)\)\s*([A-Za-z_][A-Za-z0-9_]*)\s*(?:\[[^\]]+])?\s*\(""")
        private val typeStructRegex = Regex("""^type\s+([A-Za-z_][A-Za-z0-9_]*)\s+struct\b""")
        private val typeInterfaceRegex = Regex("""^type\s+([A-Za-z_][A-Za-z0-9_]*)\s+interface\b""")
        private val typeNameRegex = Regex("""^type\s+([A-Za-z_][A-Za-z0-9_]*)\b""")
        private val varOrConstNameRegex = Regex("""^(const|var)\s+([A-Za-z_][A-Za-z0-9_]*)\b""")
        private val identifierRegex = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
    }
}
