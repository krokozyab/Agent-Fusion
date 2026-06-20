package com.orchestrator.context.chunking

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import com.orchestrator.utils.Logger
import org.treesitter.TSLanguage
import org.treesitter.TSNode
import org.treesitter.TSParser
import org.treesitter.TSPoint
import org.treesitter.TreeSitterCSharp
import org.treesitter.TreeSitterGo
import org.treesitter.TreeSitterJava
import org.treesitter.TreeSitterJavascript
import org.treesitter.TreeSitterKotlin
import org.treesitter.TreeSitterPython
import org.treesitter.TreeSitterTypescript
import java.time.Instant

/**
 * Per-language description that drives the generic [TreeSitterChunker]. Node-type names differ per
 * grammar, so all language knowledge lives here as small lookup tables.
 *
 * @property headerTypes   top-level package/import/using nodes, collapsed into one CODE_HEADER chunk
 * @property functionTypes function/method/constructor nodes — emitted as leaf chunks (never descended)
 * @property atomicTypes   interface/enum/etc — emitted whole as one chunk (never descended)
 * @property classTypes    class-like nodes — descended (emit their methods) when they contain a
 *                         function, otherwise emitted whole
 * @property containerTypes nodes to descend without emitting (e.g. C# namespace)
 * @property unwrapTypes   nodes that wrap a single declaration (e.g. TS `export`, Python decorators)
 * @property fallback      heuristic chunker used when the parse yields nothing usable
 */
data class LanguageSpec(
    val language: () -> TSLanguage,
    val headerTypes: Set<String>,
    val functionTypes: Map<String, ChunkKind>,
    val atomicTypes: Map<String, ChunkKind>,
    val classTypes: Map<String, ChunkKind>,
    val containerTypes: Set<String> = emptySet(),
    val unwrapTypes: Set<String> = emptySet(),
    val fallback: (Int) -> SimpleChunker
)

/**
 * Generic AST chunker over tree-sitter. Splits a source file at declaration nodes using the real
 * parse tree, so chunk boundaries and line ranges come from the grammar instead of heuristics.
 *
 * Behaviour shared across grammars (learned while bringing the languages up):
 *  - Some grammars (Kotlin, Go) report `hasError()` even on valid code while still producing a usable
 *    tree, so we never gate on `hasError()`; we fall back only when parsing throws or yields nothing.
 *  - tree-sitter offsets are UTF-8 byte positions, not Java chars, so content is sliced from the
 *    byte array.
 *  - Parsers are not thread-safe; the registry wraps this in a ThreadLocal adapter
 *    (CachingSimpleChunkerAdapter), giving one parser per thread.
 */
class TreeSitterChunker(
    private val spec: LanguageSpec,
    private val maxTokens: Int = 600,
    private val overlapPercent: Int = 15
) : SimpleChunker {

    private val log = Logger.logger("com.orchestrator.context.chunking.TreeSitterChunker")
    private val parser: TSParser = TSParser().apply { setLanguage(spec.language()) }
    private val fallback: SimpleChunker by lazy { spec.fallback(overlapPercent) }

    override fun chunk(content: String, filePath: String): List<Chunk> {
        if (content.isBlank()) return emptyList()

        val root = runCatching { parser.parseString(null, content).rootNode }.getOrNull()
        if (root == null || root.isNull) {
            log.debug("tree-sitter parse unavailable for {} — falling back to heuristic chunker", filePath)
            return fallback.chunk(content, filePath)
        }

        val header = mutableListOf<TSNode>()
        val decls = mutableListOf<Pair<TSNode, ChunkKind>>()
        collect(root, 0, header, decls)

        if (decls.isEmpty() && header.isEmpty()) {
            log.debug("tree-sitter produced no declarations for {} — falling back to heuristic chunker", filePath)
            return fallback.chunk(content, filePath)
        }

        val bytes = content.toByteArray(Charsets.UTF_8)
        val chunks = mutableListOf<Chunk>()
        var ordinal = 0

        if (header.isNotEmpty()) {
            val start = header.first()
            val end = header.last()
            slice(bytes, start.startByte, end.endByte)?.let { text ->
                buildChunk(text, ChunkKind.CODE_HEADER, ordinal, lineOf(start.startPoint), endLineOf(end.endPoint))
                    ?.let { chunks += it; ordinal++ }
            }
        }

        // Document order keeps ordinals stable and chunk_path/overlap meaningful.
        for ((node, kind) in decls.sortedBy { it.first.startByte }) {
            slice(bytes, node.startByte, node.endByte)?.let { text ->
                buildChunk(text, kind, ordinal, lineOf(node.startPoint), endLineOf(node.endPoint))
                    ?.let { chunks += it; ordinal++ }
            }
        }

        if (chunks.isEmpty()) return fallback.chunk(content, filePath)

        return OverlapProcessor.addOverlap(chunks, overlapPercent, ::estimateTokens)
            .map { it.copy(tokenEstimate = (it.tokenEstimate ?: estimateTokens(it.content)).coerceAtMost(maxTokens)) }
    }

    private fun collect(node: TSNode, depth: Int, header: MutableList<TSNode>, decls: MutableList<Pair<TSNode, ChunkKind>>) {
        if (depth > MAX_DEPTH) return
        for (i in 0 until node.namedChildCount) {
            val child = node.getNamedChild(i)
            val type = child.type
            when {
                depth == 0 && type in spec.headerTypes -> header += child
                spec.functionTypes.containsKey(type) -> decls += child to spec.functionTypes.getValue(type)
                spec.atomicTypes.containsKey(type) -> decls += child to spec.atomicTypes.getValue(type)
                spec.classTypes.containsKey(type) ->
                    // A class with methods is split into its methods; a method-less class/data class is
                    // emitted whole.
                    if (containsFunction(child, 0)) collect(child, depth + 1, header, decls)
                    else decls += child to spec.classTypes.getValue(type)
                type in spec.containerTypes -> collect(child, depth + 1, header, decls)
                type in spec.unwrapTypes -> collect(child, depth + 1, header, decls)
                else -> collect(child, depth + 1, header, decls)
            }
        }
    }

    private fun containsFunction(node: TSNode, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return false
        for (i in 0 until node.namedChildCount) {
            val child = node.getNamedChild(i)
            if (spec.functionTypes.containsKey(child.type)) return true
            if (containsFunction(child, depth + 1)) return true
        }
        return false
    }

    private fun lineOf(p: TSPoint): Int = p.row + 1

    // endPoint is just past the node; at column 0 of a later row the last content line is the prior
    // row, so don't add 1 (avoids bleeding into the next declaration or a trailing blank line).
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

    companion object {
        private const val MAX_DEPTH = 60
    }
}

/** The seven supported code-language specifications, derived from each grammar's actual node types. */
object LanguageSpecs {

    private fun adapt(block: (String, String) -> List<Chunk>): SimpleChunker =
        object : SimpleChunker {
            override fun chunk(content: String, filePath: String): List<Chunk> = block(content, filePath)
        }

    val JAVA = LanguageSpec(
        language = { TreeSitterJava() },
        headerTypes = setOf("package_declaration", "import_declaration"),
        functionTypes = mapOf(
            "method_declaration" to ChunkKind.CODE_METHOD,
            "constructor_declaration" to ChunkKind.CODE_CONSTRUCTOR
        ),
        atomicTypes = mapOf(
            "interface_declaration" to ChunkKind.CODE_INTERFACE,
            "enum_declaration" to ChunkKind.CODE_ENUM,
            "annotation_type_declaration" to ChunkKind.CODE_INTERFACE
        ),
        classTypes = mapOf(
            "class_declaration" to ChunkKind.CODE_CLASS,
            "record_declaration" to ChunkKind.CODE_CLASS
        ),
        fallback = { ovl -> JavaChunker(overlapPercent = ovl) }
    )

    val PYTHON = LanguageSpec(
        language = { TreeSitterPython() },
        headerTypes = setOf("import_statement", "import_from_statement", "future_import_statement"),
        functionTypes = mapOf("function_definition" to ChunkKind.CODE_FUNCTION),
        atomicTypes = emptyMap(),
        classTypes = mapOf("class_definition" to ChunkKind.CODE_CLASS),
        unwrapTypes = setOf("decorated_definition"),
        fallback = { ovl -> adapt { c, f -> PythonChunker(overlapPercent = ovl).chunk(c, f, "python") } }
    )

    val GO = LanguageSpec(
        language = { TreeSitterGo() },
        headerTypes = setOf("package_clause", "import_declaration"),
        functionTypes = mapOf(
            "function_declaration" to ChunkKind.CODE_FUNCTION,
            "method_declaration" to ChunkKind.CODE_METHOD
        ),
        atomicTypes = mapOf("type_declaration" to ChunkKind.CODE_CLASS),
        classTypes = emptyMap(),
        fallback = { ovl -> GoChunker(overlapPercent = ovl) }
    )

    val TYPESCRIPT = LanguageSpec(
        language = { TreeSitterTypescript() },
        headerTypes = setOf("import_statement"),
        functionTypes = mapOf(
            "function_declaration" to ChunkKind.CODE_FUNCTION,
            "method_definition" to ChunkKind.CODE_METHOD
        ),
        atomicTypes = mapOf(
            "interface_declaration" to ChunkKind.CODE_INTERFACE,
            "enum_declaration" to ChunkKind.CODE_ENUM,
            "type_alias_declaration" to ChunkKind.CODE_BLOCK
        ),
        classTypes = mapOf(
            "class_declaration" to ChunkKind.CODE_CLASS,
            "abstract_class_declaration" to ChunkKind.CODE_CLASS
        ),
        containerTypes = setOf("internal_module", "module"),
        unwrapTypes = setOf("export_statement"),
        fallback = { ovl -> adapt { c, f -> TypeScriptChunker(overlapPercent = ovl).chunk(c, f, "typescript") } }
    )

    val JAVASCRIPT = LanguageSpec(
        language = { TreeSitterJavascript() },
        headerTypes = setOf("import_statement"),
        functionTypes = mapOf(
            "function_declaration" to ChunkKind.CODE_FUNCTION,
            "method_definition" to ChunkKind.CODE_METHOD,
            "generator_function_declaration" to ChunkKind.CODE_FUNCTION
        ),
        atomicTypes = emptyMap(),
        classTypes = mapOf("class_declaration" to ChunkKind.CODE_CLASS),
        unwrapTypes = setOf("export_statement"),
        fallback = { ovl -> adapt { c, f -> TypeScriptChunker(overlapPercent = ovl).chunk(c, f, "javascript") } }
    )

    val CSHARP = LanguageSpec(
        language = { TreeSitterCSharp() },
        headerTypes = setOf("using_directive"),
        functionTypes = mapOf(
            "method_declaration" to ChunkKind.CODE_METHOD,
            "constructor_declaration" to ChunkKind.CODE_CONSTRUCTOR,
            "property_declaration" to ChunkKind.CODE_BLOCK
        ),
        atomicTypes = mapOf(
            "interface_declaration" to ChunkKind.CODE_INTERFACE,
            "enum_declaration" to ChunkKind.CODE_ENUM
        ),
        classTypes = mapOf(
            "class_declaration" to ChunkKind.CODE_CLASS,
            "struct_declaration" to ChunkKind.CODE_CLASS,
            "record_declaration" to ChunkKind.CODE_CLASS
        ),
        containerTypes = setOf("namespace_declaration", "file_scoped_namespace_declaration"),
        fallback = { ovl -> CSharpChunker(overlapPercent = ovl) }
    )

    val KOTLIN = LanguageSpec(
        language = { TreeSitterKotlin() },
        headerTypes = setOf("package_header", "import_list"),
        functionTypes = mapOf("function_declaration" to ChunkKind.CODE_FUNCTION),
        atomicTypes = emptyMap(),
        classTypes = mapOf(
            "class_declaration" to ChunkKind.CODE_CLASS,
            "object_declaration" to ChunkKind.CODE_CLASS
        ),
        fallback = { ovl -> KotlinChunker(overlapPercent = ovl) }
    )
}
