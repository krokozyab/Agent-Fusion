package com.orchestrator.context.chunking

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight structural splitter for C# source files.
 * Extracts headers, type declarations, and common members (constructors, methods, properties)
 * while preserving XML documentation comments ahead of each declaration. The implementation is
 * intentionally heuristic – it avoids the cost of a full parser but still surfaces the constructs
 * our summariser cares about.
 */
class CSharpChunker(
    private val maxTokens: Int = 600,
    private val overlapPercent: Int = 15
) : SimpleChunker {

    override fun chunk(content: String, filePath: String): List<Chunk> {
        if (content.isBlank()) return emptyList()

        val lines = content.lines()
        val chunks = mutableListOf<Chunk>()
        var ordinal = 0

        val headerEnd = headerLineCount(lines)
        if (headerEnd > 0) {
            val headerText = joinLines(lines, 0, headerEnd - 1)
            chunks += createChunk(
                text = headerText,
                kind = ChunkKind.CODE_HEADER,
                label = "header",
                ordinal = ordinal++,
                startLine = 1,
                endLine = headerEnd
            )
        }

        var index = headerEnd
        while (index < lines.size) {
            val line = lines[index].trim()
            if (line.isBlank()) {
                index++
                continue
            }

            val typeMatch = TYPE_DECLARATION.find(line)
            if (typeMatch != null) {
                val (docStart, declarationIndex) = withDocCommentStart(lines, index)
                val block = extractTypeBlock(lines, declarationIndex) ?: break
                val chunkStart = docStart ?: declarationIndex
                val chunkText = joinLines(lines, chunkStart, block.endIndex)
                val typeKeyword = typeMatch.groupValues[1]
                val typeName = cleanIdentifier(typeMatch.groupValues[2])
                val kind = when (typeKeyword) {
                    "interface" -> ChunkKind.CODE_INTERFACE
                    "enum" -> ChunkKind.CODE_ENUM
                    else -> ChunkKind.CODE_CLASS
                }

                chunks += createChunk(
                    text = chunkText,
                    kind = kind,
                    label = typeName,
                    ordinal = ordinal++,
                    startLine = chunkStart + 1,
                    endLine = block.endIndex + 1
                )

                val emittedChunks = mutableSetOf<String>()

                parseMembers(
                    lines = lines,
                    bodyStart = declarationIndex,
                    bodyEnd = block.endIndex,
                    typeName = typeName,
                    ordinalCounter = {
                        val current = ordinal
                        ordinal += 1
                        current
                    }
                ) { chunk ->
                    emittedChunks += chunk.summary.orEmpty()
                    chunks += chunk
                }

                ensureConstructors(
                    lines = lines,
                    bodyStart = declarationIndex,
                    bodyEnd = block.endIndex,
                    typeName = typeName,
                    ordinalCounter = {
                        val current = ordinal
                        ordinal += 1
                        current
                    },
                    alreadyEmitted = emittedChunks
                ) { summary, chunk ->
                    emittedChunks += summary
                    chunks += chunk
                }

                index = block.endIndex + 1
                continue
            }

            index++
        }

        return chunks
    }

    private fun headerLineCount(lines: List<String>): Int {
        var count = 0
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("using ") || trimmed.startsWith("namespace ") || trimmed.isEmpty()) {
                count++
            } else {
                break
            }
        }
        return count
    }

    private fun parseMembers(
        lines: List<String>,
        bodyStart: Int,
        bodyEnd: Int,
        typeName: String,
        ordinalCounter: () -> Int,
        onChunk: (Chunk) -> Unit
    ) {
        var index = bodyStart + 1
        while (index < bodyEnd) {
            val (docStart, signatureIndex) = withDocCommentStart(lines, index)
            var currentIndex = signatureIndex
            if (currentIndex >= bodyEnd) break

            val trimmed = lines[currentIndex].trim()
            if (trimmed.isBlank()) {
                index = currentIndex + 1
                continue
            }

            // Ignore nested type declarations – they will be handled by outer loop.
            if (TYPE_DECLARATION.find(trimmed) != null) {
                val nestedBlock = extractBraceBlock(lines, currentIndex) ?: return
                index = nestedBlock.endIndex + 1
                continue
            }

            val hasParentheses = trimmed.contains('(') && trimmed.contains(')')
            val startsWithAccess = ACCESS_MODIFIERS.any { trimmed.startsWith(it) }

            if (hasParentheses && startsWithAccess) {
                val block = extractBraceBlock(lines, currentIndex) ?: break
                val chunkStart = docStart ?: currentIndex
                val chunkText = joinLines(lines, chunkStart, block.endIndex)
                val signature = trimmed.substring(0, trimmed.indexOf('(')).trim()
                val methodName = cleanIdentifier(signature.substringAfterLast(' '))
                val isConstructor = isConstructorSignature(signature, typeName)
                val summary = if (isConstructor) "$typeName.<init>" else "$typeName.$methodName"
                val kind = if (isConstructor) ChunkKind.CODE_CONSTRUCTOR else ChunkKind.CODE_METHOD

                onChunk(
                    createChunk(
                        text = chunkText,
                        kind = kind,
                        label = summary,
                        ordinal = ordinalCounter(),
                        startLine = chunkStart + 1,
                        endLine = block.endIndex + 1
                    )
                )

                index = block.endIndex + 1
                continue
            }

            if (startsWithAccess && !trimmed.contains('(')) {
                val block = extractBraceBlock(lines, currentIndex)
                if (block != null) {
                    val tokenText = block.text
                    val looksLikeProperty = PROPERTY_MARKERS.any { tokenText.contains(it) }
                    if (looksLikeProperty) {
                        val chunkStart = docStart ?: currentIndex
                        val chunkText = joinLines(lines, chunkStart, block.endIndex)
                        val signature = trimmed.substringBefore('{').trim()
                        val propertyName = cleanIdentifier(signature.substringAfterLast(' '))

                        onChunk(
                            createChunk(
                                text = chunkText,
                                kind = ChunkKind.CODE_BLOCK,
                                label = "$typeName.$propertyName",
                                ordinal = ordinalCounter(),
                                startLine = chunkStart + 1,
                                endLine = block.endIndex + 1
                            )
                        )

                        index = block.endIndex + 1
                        continue
                    }
                }

                index = currentIndex + 1
                continue
            }

            index = currentIndex + 1
        }
    }

    private fun ensureConstructors(
        lines: List<String>,
        bodyStart: Int,
        bodyEnd: Int,
        typeName: String,
        ordinalCounter: () -> Int,
        alreadyEmitted: MutableSet<String>,
        onChunk: (String, Chunk) -> Unit
    ) {
        for (index in bodyStart + 1..bodyEnd) {
            val line = lines.getOrNull(index)?.trim() ?: continue
            if (line.isEmpty()) continue
            val match = CONSTRUCTOR_PATTERN.matchEntire(line) ?: continue
            val name = match.groupValues[2]
            if (!name.equals(typeName, ignoreCase = true)) continue
            val summary = "$typeName.<init>"
            if (alreadyEmitted.contains(summary)) continue

            val (docStart, _) = withDocCommentStart(lines, index)
            val chunkStart = docStart ?: index
            val block = extractBraceBlock(lines, index) ?: continue
            val chunkText = joinLines(lines, chunkStart, block.endIndex)

            onChunk(
                summary,
                createChunk(
                    text = chunkText,
                    kind = ChunkKind.CODE_CONSTRUCTOR,
                    label = summary,
                    ordinal = ordinalCounter(),
                    startLine = chunkStart + 1,
                    endLine = block.endIndex + 1
                )
            )
        }
    }

    private fun withDocCommentStart(lines: List<String>, index: Int): Pair<Int?, Int> {
        var startIdx = index
        while (startIdx > 0 && lines[startIdx - 1].trim().startsWith("///")) {
            startIdx--
        }
        val isDocLine = lines.getOrNull(startIdx)?.trim()?.startsWith("///") == true
        return if (isDocLine) startIdx to index else null to index
    }

    private fun extractTypeBlock(lines: List<String>, start: Int): Block? {
        val firstLine = lines[start].trim()
        val braceBlock = extractBraceBlock(lines, start)
        if (braceBlock != null) return braceBlock

        if (firstLine.contains("record") && firstLine.trimEnd().endsWith(";")) {
            return Block(firstLine, start, start)
        }

        return null
    }

    private fun extractBraceBlock(lines: List<String>, start: Int): Block? {
        var braceDepth = 0
        var seenOpening = false
        val builder = StringBuilder()
        var index = start

        while (index < lines.size) {
            val line = lines[index]
            builder.append(line).append('\n')
            val opens = line.count { it == '{' }
            val closes = line.count { it == '}' }
            if (opens > 0) seenOpening = true
            braceDepth += opens
            braceDepth -= closes

            if (seenOpening && braceDepth <= 0) {
                return Block(builder.toString().trimEnd(), start, index)
            }

            index++
        }

        return if (seenOpening) Block(builder.toString().trimEnd(), start, lines.size - 1) else null
    }

    private fun joinLines(lines: List<String>, start: Int, end: Int): String {
        val clampedStart = max(0, start)
        val clampedEnd = min(lines.lastIndex, end)
        return lines.subList(clampedStart, clampedEnd + 1).joinToString("\n")
    }

    private fun createChunk(
        text: String,
        kind: ChunkKind,
        label: String,
        ordinal: Int,
        startLine: Int?,
        endLine: Int?
    ): Chunk {
        val estimate = estimateTokens(text)
        return Chunk(
            id = 0L,
            fileId = 0L,
            ordinal = ordinal,
            kind = kind,
            startLine = startLine,
            endLine = endLine,
            tokenEstimate = estimate,
            content = text,
            summary = label,
            createdAt = Instant.now()
        )
    }

    private fun estimateTokens(text: String): Int {
        val approx = text.split(WHITESPACE_REGEX).count { it.isNotEmpty() }
        return min(maxTokens, approx)
    }

    private fun cleanIdentifier(raw: String): String = raw.trim().trim('{', '}', ';')

    private fun isConstructorSignature(signature: String, typeName: String): Boolean {
        val name = cleanIdentifier(signature.substringAfterLast(' '))
        if (!name.equals(typeName, ignoreCase = true)) return false

        val tokens = signature.substring(0, signature.lastIndexOf(name)).trim().split(WHITESPACE_REGEX)
        if (tokens.isEmpty()) return true
        val lastToken = tokens.last()
        return lastToken.lowercase() in MODIFIER_KEYWORDS
    }

    private data class Block(val text: String, val startIndex: Int, val endIndex: Int)

    companion object {
        private val TYPE_DECLARATION = Regex("""\b(class|struct|interface|enum|record)\s+([A-Za-z_][\w<>]*)""")
        private val ACCESS_MODIFIERS = listOf("public", "private", "protected", "internal")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val PROPERTY_MARKERS = listOf(" get", " set", "=>", "get;", "set;")
        private val MODIFIER_KEYWORDS = setOf(
            "public", "private", "protected", "internal", "static", "sealed",
            "unsafe", "extern", "abstract", "virtual", "override", "async", "partial"
        )
        private val CONSTRUCTOR_PATTERN = Regex("""(public|private|protected|internal)?\\s*(?:static\\s+)?([A-Za-z_][\\w<>]*)\s*\\(([^)]*)\\)""")
    }
}
