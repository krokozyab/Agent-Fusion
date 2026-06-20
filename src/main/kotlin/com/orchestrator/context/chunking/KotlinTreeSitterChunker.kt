package com.orchestrator.context.chunking

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import com.orchestrator.utils.Logger
import org.treesitter.TSNode
import org.treesitter.TSParser
import org.treesitter.TSPoint
import org.treesitter.TreeSitterKotlin
import java.time.Instant

/**
 * Prototype: AST-based Kotlin chunker backed by tree-sitter.
 *
 * Splits a Kotlin file at top-level declarations using the real parse tree, so chunk boundaries and
 * line ranges come from the grammar rather than the regex/brace heuristics in [KotlinChunker].
 *
 * Robustness notes (learned from the grammar's actual behaviour):
 *  - The `tree-sitter-kotlin` grammar reports `hasError() == true` even on valid Kotlin, yet its
 *    declaration nodes and line ranges are still correct. So we do NOT bail on `hasError()`; instead
 *    we extract what we can and fall back to [fallback] only when parsing throws or yields nothing.
 *  - tree-sitter byte offsets index the UTF-8 encoding, not Java chars — we slice the `ByteArray`.
 *  - Parsers are not thread-safe; the registry wraps SimpleChunkers in a ThreadLocal adapter
 *    (CachingSimpleChunkerAdapter), giving one parser per thread, which satisfies that requirement.
 */
class KotlinTreeSitterChunker(
    private val maxTokens: Int = 600,
    private val overlapPercent: Int = 15,
    private val fallback: SimpleChunker = KotlinChunker(maxTokens, overlapPercent)
) : SimpleChunker {

    private val log = Logger.logger("com.orchestrator.context.chunking.KotlinTreeSitterChunker")

    // One parser per instance; the registry's ThreadLocal adapter keeps it single-threaded.
    private val parser: TSParser = TSParser().apply { setLanguage(TreeSitterKotlin()) }

    override fun chunk(content: String, filePath: String): List<Chunk> {
        if (content.isBlank()) return emptyList()

        val root = runCatching { parser.parseString(null, content).rootNode }.getOrNull()
        if (root == null || root.isNull) {
            log.debug("tree-sitter parse unavailable for {} — falling back to heuristic chunker", filePath)
            return fallback.chunk(content, filePath)
        }

        val bytes = content.toByteArray(Charsets.UTF_8)
        val children = root.namedChildren()
        val chunks = mutableListOf<Chunk>()
        var ordinal = 0

        // Header: package + imports collapsed into one CODE_HEADER chunk.
        val headerNodes = children.filter { it.type == "package_header" || it.type == "import_list" }
        if (headerNodes.isNotEmpty()) {
            val start = headerNodes.first()
            val end = headerNodes.last()
            slice(bytes, start.startByte, end.endByte)?.let { text ->
                buildChunk(text, ChunkKind.CODE_HEADER, ordinal++, lineOf(start.startPoint), endLineOf(end.endPoint))
                    ?.let(chunks::add)
            }
        }

        // Top-level declarations.
        for (node in children) {
            val kind = node.type.toChunkKind() ?: continue
            slice(bytes, node.startByte, node.endByte)?.let { text ->
                buildChunk(text, kind, ordinal++, lineOf(node.startPoint), endLineOf(node.endPoint))?.let(chunks::add)
            }
        }

        if (chunks.isEmpty()) {
            log.debug("tree-sitter produced no chunks for {} — falling back to heuristic chunker", filePath)
            return fallback.chunk(content, filePath)
        }

        return OverlapProcessor.addOverlap(chunks, overlapPercent, ::estimateTokens)
            .map { it.copy(tokenEstimate = (it.tokenEstimate ?: estimateTokens(it.content)).coerceAtMost(maxTokens)) }
    }

    // Prototype mapping. interface/enum are also `class_declaration` in this grammar; refining them
    // to CODE_INTERFACE/CODE_ENUM requires inspecting the modifier/keyword children — left as TODO.
    private fun String.toChunkKind(): ChunkKind? = when (this) {
        "class_declaration" -> ChunkKind.CODE_CLASS
        "object_declaration" -> ChunkKind.CODE_CLASS
        "function_declaration" -> ChunkKind.CODE_FUNCTION
        "property_declaration" -> ChunkKind.CODE_BLOCK
        else -> null
    }

    // tree-sitter rows are 0-based; +1 → 1-based line numbers.
    private fun lineOf(p: TSPoint): Int = p.row + 1

    // endPoint is the position just past the node. When it lands at column 0 of a later row, the last
    // real content line is the previous row, so use row as-is (1-based) to avoid bleeding into the
    // next declaration or a trailing blank line.
    private fun endLineOf(p: TSPoint): Int = if (p.column == 0 && p.row > 0) p.row else p.row + 1

    private fun slice(bytes: ByteArray, startByte: Int, endByte: Int): String? {
        if (startByte < 0 || endByte > bytes.size || startByte >= endByte) return null
        return String(bytes, startByte, endByte - startByte, Charsets.UTF_8).takeIf { it.isNotBlank() }
    }

    private fun buildChunk(content: String, kind: ChunkKind, ordinal: Int, startLine: Int, endLine: Int): Chunk? {
        if (content.isBlank()) return null
        val safeStart = startLine.coerceAtLeast(1)
        val safeEnd = endLine.coerceAtLeast(safeStart)
        return Chunk(
            id = 0,
            fileId = 0,
            ordinal = ordinal,
            kind = kind,
            startLine = safeStart,
            endLine = safeEnd,
            tokenEstimate = estimateTokens(content),
            content = content,
            summary = null,
            createdAt = Instant.now()
        )
    }

    private fun estimateTokens(text: String): Int = TokenEstimator.estimate(text)

    private fun TSNode.namedChildren(): List<TSNode> = (0 until namedChildCount).map { getNamedChild(it) }
}
