package com.orchestrator.context.indexing

import com.orchestrator.context.chunking.LanguageSpec
import com.orchestrator.context.domain.ChunkKind
import com.orchestrator.context.domain.SymbolRecord
import com.orchestrator.context.domain.SymbolType
import org.treesitter.TSNode
import org.treesitter.TSParser
import java.time.Instant

/**
 * Unified symbol extractor over tree-sitter, driven by the same [LanguageSpec]s that power chunking.
 *
 * Replaces the per-language regex extractors (and the broken catch-all that emitted every identifier
 * as a FUNCTION with a fabricated line number, which is what Go and C# fell back to). Symbols come
 * from the real parse tree, so names, types and line ranges are accurate and consistent with the
 * chunk boundaries — keeping the call graph's symbol→chunk mapping correct.
 *
 * Emits: classes/interfaces/enums, functions/methods/constructors (call-graph targets), properties,
 * and imports (for DEPENDS_ON). Qualified names are built from the file's package/namespace plus the
 * enclosing type nesting.
 */
class TreeSitterSymbolExtractor(private val spec: LanguageSpec) {

    // tree-sitter parsers are not thread-safe; one per extractor instance (callers keep these
    // per-thread, like the chunkers).
    private val parser: TSParser = TSParser().apply { setLanguage(spec.language()) }

    fun extract(content: String, fileId: Long, language: String): List<SymbolRecord> {
        if (content.isBlank()) return emptyList()
        val root = runCatching { parser.parseString(null, content).rootNode }.getOrNull() ?: return emptyList()
        if (root.isNull) return emptyList()

        val bytes = content.toByteArray(Charsets.UTF_8)
        val out = mutableListOf<SymbolRecord>()
        val packagePrefix = detectPackage(root, bytes)
        walk(root, bytes, fileId, language, ArrayDeque<String>().apply { packagePrefix?.let { addLast(it) } }, 0, out)
        return out
    }

    private fun walk(
        node: TSNode,
        bytes: ByteArray,
        fileId: Long,
        language: String,
        scope: ArrayDeque<String>,
        typeDepth: Int,
        out: MutableList<SymbolRecord>
    ) {
        for (i in 0 until node.namedChildCount) {
            val child = node.getNamedChild(i)
            val type = child.type
            val symbolType = symbolTypeFor(type, typeDepth)
            when {
                isImportType(type) -> extractImports(child, bytes, fileId, language, out)
                isPackageType(type) -> { /* used only as a qualifier; not a symbol on its own */ }
                symbolType != null -> {
                    val name = nameOf(child, bytes)
                    if (!name.isNullOrBlank()) {
                        out += symbol(child, symbolType, name, qualify(scope, name), bytes, language, fileId)
                        // Recurse with this name pushed so nested members are qualified by it; bump
                        // typeDepth when entering a type so its functions are classed as METHODs.
                        scope.addLast(name)
                        val nextTypeDepth = if (isTypeSymbol(symbolType)) typeDepth + 1 else typeDepth
                        walk(child, bytes, fileId, language, scope, nextTypeDepth, out)
                        scope.removeLast()
                    } else {
                        walk(child, bytes, fileId, language, scope, typeDepth, out)
                    }
                }
                type in spec.containerTypes -> {
                    val name = nameOf(child, bytes)
                    if (!name.isNullOrBlank()) scope.addLast(name)
                    walk(child, bytes, fileId, language, scope, typeDepth, out)
                    if (!name.isNullOrBlank()) scope.removeLast()
                }
                else -> walk(child, bytes, fileId, language, scope, typeDepth, out)
            }
        }
    }

    private fun isTypeSymbol(t: SymbolType): Boolean =
        t == SymbolType.CLASS || t == SymbolType.INTERFACE || t == SymbolType.ENUM

    private fun symbolTypeFor(nodeType: String, typeDepth: Int): SymbolType? {
        spec.functionTypes[nodeType]?.let {
            val st = toSymbolType(it)
            // A plain function declared inside a type is a method (Kotlin/Python reuse one node type
            // for both); languages with a distinct method node already map to METHOD.
            return if (st == SymbolType.FUNCTION && typeDepth > 0) SymbolType.METHOD else st
        }
        spec.atomicTypes[nodeType]?.let { return toSymbolType(it) }
        spec.classTypes[nodeType]?.let { return toSymbolType(it) }
        return null
    }

    private fun toSymbolType(kind: ChunkKind): SymbolType = when (kind) {
        ChunkKind.CODE_FUNCTION -> SymbolType.FUNCTION
        ChunkKind.CODE_METHOD -> SymbolType.METHOD
        ChunkKind.CODE_CONSTRUCTOR -> SymbolType.METHOD
        ChunkKind.CODE_CLASS -> SymbolType.CLASS
        ChunkKind.CODE_INTERFACE -> SymbolType.INTERFACE
        ChunkKind.CODE_ENUM -> SymbolType.ENUM
        ChunkKind.CODE_BLOCK -> SymbolType.PROPERTY
        else -> SymbolType.FUNCTION
    }

    /** The declaration's name: the `name` field, else an identifier child, else one inside a `_spec`. */
    private fun nameOf(node: TSNode, bytes: ByteArray): String? {
        runCatching { node.getChildByFieldName("name") }.getOrNull()?.takeIf { !it.isNull }?.let {
            return slice(bytes, it)
        }
        for (i in 0 until node.namedChildCount) {
            val c = node.getNamedChild(i)
            if (c.type.contains("identifier", ignoreCase = true) || c.type == "name") return slice(bytes, c)
        }
        // Go `type_declaration` carries the name inside `type_spec`; same shape for some grammars.
        for (i in 0 until node.namedChildCount) {
            val c = node.getNamedChild(i)
            if (c.type.endsWith("_spec") || c.type.endsWith("_declarator")) {
                nameOf(c, bytes)?.let { return it }
            }
        }
        return null
    }

    private fun detectPackage(root: TSNode, bytes: ByteArray): String? {
        for (i in 0 until root.namedChildCount) {
            val c = root.getNamedChild(i)
            if (isPackageType(c.type)) return nameOf(c, bytes)?.takeIf { it.isNotBlank() }
        }
        return null
    }

    /** Imports: emit one IMPORT symbol per leaf import entry (best effort across grammars). */
    private fun extractImports(node: TSNode, bytes: ByteArray, fileId: Long, language: String, out: MutableList<SymbolRecord>) {
        val leaves = importLeaves(node)
        for (leaf in leaves) {
            val path = importPath(slice(bytes, leaf)) ?: continue
            val name = path.substringAfterLast('.').substringAfterLast('/').trim('"', '\'', '`', ' ')
            if (name.isBlank()) continue
            out += SymbolRecord(
                id = 0, fileId = fileId, chunkId = null, symbolType = SymbolType.IMPORT,
                name = name, qualifiedName = path, signature = slice(bytes, leaf).take(200),
                language = language, startLine = leaf.startPoint.row + 1, endLine = leaf.endPoint.row + 1,
                createdAt = Instant.EPOCH
            )
        }
    }

    // Descend into per-entry import nodes (Kotlin import_header, Go import_spec, …); else this node.
    private fun importLeaves(node: TSNode): List<TSNode> {
        val children = (0 until node.namedChildCount).map { node.getNamedChild(it) }
        val entries = children.filter { it.type.contains("import", true) || it.type.endsWith("_spec") }
        return if (entries.isNotEmpty()) entries else listOf(node)
    }

    private fun importPath(text: String): String? {
        val cleaned = text.substringBefore("//").trim().removeSuffix(";").trim()
        // The dotted/slashed path (Java a.b.C, Go "fmt", Kotlin a.b.C); ignore leading keywords.
        val m = Regex("""([\w.]+(?:\.[\w*]+)*)""").findAll(cleaned)
            .map { it.value }
            .filter { it.any(Char::isLetter) && it !in IMPORT_KEYWORDS }
            .maxByOrNull { it.length }
        return m
    }

    private fun isImportType(t: String): Boolean =
        (t.contains("import", true) || t == "using_directive") && t in spec.headerTypes

    private fun isPackageType(t: String): Boolean =
        t.contains("package", true) && t in spec.headerTypes

    private fun qualify(scope: ArrayDeque<String>, name: String): String =
        if (scope.isEmpty()) name else scope.joinToString(".") + "." + name

    private fun symbol(node: TSNode, type: SymbolType, name: String, qualified: String, bytes: ByteArray, language: String, fileId: Long): SymbolRecord =
        SymbolRecord(
            id = 0, fileId = fileId, chunkId = null, symbolType = type,
            name = name, qualifiedName = qualified,
            // Signature = the declaration's first line (header), useful for ranking/display.
            signature = slice(bytes, node).lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(200),
            language = language, startLine = node.startPoint.row + 1, endLine = node.endPoint.row + 1,
            createdAt = Instant.EPOCH
        )

    private fun slice(bytes: ByteArray, node: TSNode): String {
        val s = node.startByte
        val e = node.endByte
        if (s < 0 || e > bytes.size || s >= e) return ""
        return String(bytes, s, e - s, Charsets.UTF_8)
    }

    companion object {
        private val IMPORT_KEYWORDS = setOf("import", "from", "using", "package", "as")
    }
}
