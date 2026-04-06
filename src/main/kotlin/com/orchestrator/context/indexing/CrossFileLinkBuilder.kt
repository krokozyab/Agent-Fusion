package com.orchestrator.context.indexing

import com.orchestrator.context.storage.ContextDatabase
import com.orchestrator.utils.Logger
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.Locale

/**
 * Builds cross-file semantic edges between chunks using indexed symbol/import data.
 *
 * Generated link types:
 * - CALLS: source chunk invokes a symbol defined in another file
 * - DEPENDS_ON: source chunk imports or references a symbol from another file
 * - COVERS: source chunk lives in a test file and references the target symbol
 *           (mirrors CALLS edges originating from test files; enables impact-radius
 *           queries to surface "which tests cover this code")
 */
class CrossFileLinkBuilder(
    private val maxLinksPerChunk: Int = 10,
    private val maxDependsOnPerFile: Int = 64
) {
    private val log = Logger.logger("com.orchestrator.context.indexing.CrossFileLinkBuilder")

    fun rebuildForFile(fileId: Long) {
        if (fileId <= 0) return

        runCatching {
            ContextDatabase.transaction { conn ->
                val sourceChunks = loadSourceChunks(conn, fileId)
                deleteExistingLinks(conn, fileId)
                if (sourceChunks.isEmpty()) return@transaction

                val sourceRelPath = loadFileRelPath(conn, fileId)
                val isTestFile = sourceRelPath != null && looksLikeTestFile(sourceRelPath)

                val imports = loadImportSymbols(conn, fileId)
                val callTokensByChunk = sourceChunks.associate { chunk ->
                    chunk.chunkId to extractCallTokens(chunk.content)
                }
                val callTokens = callTokensByChunk.values.flatten().toSet()
                val importNames = imports
                    .flatMap { listOfNotNull(it.name, it.qualifiedName?.substringAfterLast('.')) }
                    .map { it.lowercase(Locale.US) }
                    .toSet()
                val importQualified = imports
                    .mapNotNull { it.qualifiedName?.lowercase(Locale.US) }
                    .toSet()

                val lookupNames = (callTokens + importNames).filter { it.isNotBlank() }.toSet()
                if (lookupNames.isEmpty() && importQualified.isEmpty()) return@transaction

                val targetSymbols = loadTargetSymbols(
                    conn = conn,
                    sourceFileId = fileId,
                    names = lookupNames,
                    qualified = importQualified
                )
                if (targetSymbols.isEmpty()) return@transaction

                val targetsByName = targetSymbols.groupBy { it.name.lowercase(Locale.US) }
                val targetsByQualified = targetSymbols
                    .filter { !it.qualifiedName.isNullOrBlank() }
                    .groupBy { it.qualifiedName!!.lowercase(Locale.US) }

                val links = LinkedHashSet<LinkRow>()
                val anchorChunkId = sourceChunks.minByOrNull { it.ordinal }?.chunkId

                // Import -> dependency edges
                var dependsOnCount = 0
                for (importSymbol in imports) {
                    if (dependsOnCount >= maxDependsOnPerFile) break
                    val sourceChunkId = importSymbol.startLine?.let { line -> findChunkForLine(sourceChunks, line) }
                        ?: anchorChunkId
                        ?: continue
                    val target = selectImportTarget(importSymbol, targetsByName, targetsByQualified) ?: continue
                    if (target.chunkId == sourceChunkId) continue

                    val link = LinkRow(
                        sourceChunkId = sourceChunkId,
                        targetFileId = target.fileId,
                        targetChunkId = target.chunkId,
                        type = LINK_TYPE_DEPENDS_ON,
                        label = importSymbol.qualifiedName ?: importSymbol.name,
                        score = 0.92
                    )
                    if (links.add(link)) {
                        dependsOnCount++
                    }
                }

                val importedFileIds = links
                    .filter { it.type == LINK_TYPE_DEPENDS_ON }
                    .map { it.targetFileId }
                    .toSet()

                // Chunk call -> call edges
                for (chunk in sourceChunks) {
                    var linked = 0
                    val tokens = callTokensByChunk[chunk.chunkId].orEmpty()
                    for (token in tokens) {
                        if (linked >= maxLinksPerChunk) break
                        val candidates = targetsByName[token].orEmpty()
                        val target = selectCallTarget(
                            candidates = candidates,
                            sourceChunkId = chunk.chunkId,
                            sourceFileId = fileId,
                            importedFileIds = importedFileIds
                        ) ?: continue

                        val link = LinkRow(
                            sourceChunkId = chunk.chunkId,
                            targetFileId = target.fileId,
                            targetChunkId = target.chunkId,
                            type = LINK_TYPE_CALLS,
                            label = token,
                            score = 0.86
                        )
                        if (links.add(link)) {
                            linked++
                        }
                        // Mirror CALLS into COVERS when the source file is a test.
                        // Lets impact-radius queries surface the tests that exercise a symbol.
                        if (isTestFile) {
                            links.add(
                                LinkRow(
                                    sourceChunkId = chunk.chunkId,
                                    targetFileId = target.fileId,
                                    targetChunkId = target.chunkId,
                                    type = LINK_TYPE_COVERS,
                                    label = token,
                                    score = 0.95
                                )
                            )
                        }
                    }
                }

                if (links.isNotEmpty()) {
                    insertLinks(conn, links.toList())
                    log.debug("Built {} cross-file links for file_id={}", links.size, fileId)
                }
            }
        }.onFailure { e ->
            log.warn("Failed to rebuild cross-file links for file_id={}: {}", fileId, e.message)
        }
    }

    private fun loadSourceChunks(conn: Connection, fileId: Long): List<SourceChunk> {
        val sql = """
            SELECT chunk_id, file_id, ordinal, start_line, end_line, content
            FROM chunks
            WHERE file_id = ?
              AND kind LIKE 'CODE_%'
            ORDER BY ordinal
        """.trimIndent()
        return conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, fileId)
            ps.executeQuery().use { rs ->
                val chunks = mutableListOf<SourceChunk>()
                while (rs.next()) {
                    chunks += SourceChunk(
                        chunkId = rs.getLong("chunk_id"),
                        fileId = rs.getLong("file_id"),
                        ordinal = rs.getInt("ordinal"),
                        startLine = rs.getInt("start_line").takeIf { !rs.wasNull() },
                        endLine = rs.getInt("end_line").takeIf { !rs.wasNull() },
                        content = rs.getString("content").orEmpty()
                    )
                }
                chunks
            }
        }
    }

    private fun loadImportSymbols(conn: Connection, fileId: Long): List<ImportSymbol> {
        val sql = """
            SELECT name, qualified_name, start_line
            FROM symbols
            WHERE file_id = ? AND symbol_type = 'IMPORT'
            ORDER BY start_line NULLS FIRST, name
        """.trimIndent()
        return conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, fileId)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<ImportSymbol>()
                while (rs.next()) {
                    val name = rs.getString("name")?.trim().orEmpty()
                    if (name.isBlank()) continue
                    out += ImportSymbol(
                        name = name,
                        qualifiedName = rs.getString("qualified_name")?.trim()?.takeIf { it.isNotBlank() },
                        startLine = rs.getInt("start_line").takeIf { !rs.wasNull() }
                    )
                }
                out
            }
        }
    }

    private fun loadTargetSymbols(
        conn: Connection,
        sourceFileId: Long,
        names: Set<String>,
        qualified: Set<String>
    ): List<TargetSymbol> {
        if (names.isEmpty() && qualified.isEmpty()) return emptyList()

        val conditions = mutableListOf<String>()
        val params = mutableListOf<String>()

        if (names.isNotEmpty()) {
            conditions += "LOWER(s.name) IN (${names.joinToString(",") { "?" }})"
            params += names.sorted()
        }
        if (qualified.isNotEmpty()) {
            conditions += "LOWER(s.qualified_name) IN (${qualified.joinToString(",") { "?" }})"
            params += qualified.sorted()
        }

        val sql = """
            SELECT s.file_id, s.chunk_id, s.name, s.qualified_name, s.symbol_type
            FROM symbols s
            WHERE s.file_id <> ?
              AND s.chunk_id IS NOT NULL
              AND s.symbol_type IN ('FUNCTION', 'METHOD', 'CLASS', 'INTERFACE', 'ENUM', 'CONSTRUCTOR')
              AND (${conditions.joinToString(" OR ")})
        """.trimIndent()

        return conn.prepareStatement(sql).use { ps ->
            var idx = 1
            ps.setLong(idx++, sourceFileId)
            params.forEach { ps.setString(idx++, it) }
            ps.executeQuery().use { rs ->
                val out = mutableListOf<TargetSymbol>()
                while (rs.next()) {
                    val chunkId = rs.getLong("chunk_id").takeIf { !rs.wasNull() } ?: continue
                    val name = rs.getString("name")?.trim().orEmpty()
                    if (name.isBlank()) continue
                    out += TargetSymbol(
                        fileId = rs.getLong("file_id"),
                        chunkId = chunkId,
                        name = name,
                        qualifiedName = rs.getString("qualified_name")?.trim()?.takeIf { it.isNotBlank() },
                        symbolType = rs.getString("symbol_type")?.trim().orEmpty()
                    )
                }
                out
            }
        }
    }

    private fun deleteExistingLinks(conn: Connection, fileId: Long) {
        val sql = """
            DELETE FROM links
            WHERE source_chunk_id IN (SELECT chunk_id FROM chunks WHERE file_id = ?)
              AND link_type IN (?, ?, ?)
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, fileId)
            ps.setString(2, LINK_TYPE_CALLS)
            ps.setString(3, LINK_TYPE_DEPENDS_ON)
            ps.setString(4, LINK_TYPE_COVERS)
            ps.executeUpdate()
        }
    }

    private fun loadFileRelPath(conn: Connection, fileId: Long): String? =
        conn.prepareStatement("SELECT rel_path FROM file_state WHERE file_id = ?").use { ps ->
            ps.setLong(1, fileId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    private fun insertLinks(conn: Connection, links: List<LinkRow>) {
        if (links.isEmpty()) return
        val sql = """
            INSERT INTO links (
                link_id, source_chunk_id, target_file_id, target_chunk_id,
                link_type, label, score, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        val now = Timestamp.from(Instant.now())
        conn.prepareStatement(sql).use { ps ->
            links.forEach { link ->
                var idx = 1
                ps.setLong(idx++, nextId(conn))
                ps.setLong(idx++, link.sourceChunkId)
                ps.setLong(idx++, link.targetFileId)
                ps.setLong(idx++, link.targetChunkId)
                ps.setString(idx++, link.type)
                ps.setString(idx++, link.label)
                if (link.score != null) ps.setDouble(idx++, link.score) else ps.setNull(idx++, java.sql.Types.DOUBLE)
                ps.setTimestamp(idx, now)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    private fun selectImportTarget(
        symbol: ImportSymbol,
        byName: Map<String, List<TargetSymbol>>,
        byQualified: Map<String, List<TargetSymbol>>
    ): TargetSymbol? {
        val qualified = symbol.qualifiedName?.lowercase(Locale.US)
        if (!qualified.isNullOrBlank()) {
            val exact = byQualified[qualified]
            if (!exact.isNullOrEmpty()) return exact.sortedWith(targetComparator()).first()
        }

        val nameCandidates = linkedSetOf<String>()
        nameCandidates += symbol.name.lowercase(Locale.US)
        symbol.qualifiedName?.substringAfterLast('.')?.lowercase(Locale.US)?.let { nameCandidates += it }

        val merged = nameCandidates
            .flatMap { key -> byName[key].orEmpty() }
            .distinctBy { "${it.fileId}:${it.chunkId}:${it.symbolType}" }
        if (merged.isEmpty()) return null
        return merged.sortedWith(targetComparator()).first()
    }

    private fun selectCallTarget(
        candidates: List<TargetSymbol>,
        sourceChunkId: Long,
        sourceFileId: Long,
        importedFileIds: Set<Long>
    ): TargetSymbol? {
        if (candidates.isEmpty()) return null
        return candidates
            .asSequence()
            .filter { it.fileId != sourceFileId }
            .filter { it.chunkId != sourceChunkId }
            .sortedWith(
                compareBy<TargetSymbol> { if (it.fileId in importedFileIds) 0 else 1 }
                    .thenBy { symbolTypePriority(it.symbolType) }
                    .thenBy { it.qualifiedName?.length ?: Int.MAX_VALUE }
            )
            .firstOrNull()
    }

    private fun targetComparator(): Comparator<TargetSymbol> =
        compareBy<TargetSymbol> { symbolTypePriority(it.symbolType) }
            .thenBy { it.qualifiedName?.length ?: Int.MAX_VALUE }
            .thenBy { it.name.length }

    private fun symbolTypePriority(type: String): Int = when (type.uppercase(Locale.US)) {
        "FUNCTION", "METHOD", "CONSTRUCTOR" -> 0
        "CLASS", "INTERFACE", "ENUM" -> 1
        else -> 2
    }

    private fun findChunkForLine(chunks: List<SourceChunk>, line: Int): Long? =
        chunks
            .filter { chunk ->
                val start = chunk.startLine ?: return@filter false
                val end = chunk.endLine ?: return@filter false
                line in start..end
            }
            .minByOrNull { chunk ->
                (chunk.endLine ?: Int.MAX_VALUE) - (chunk.startLine ?: 0)
            }
            ?.chunkId

    private fun extractCallTokens(content: String): List<String> {
        if (content.isBlank()) return emptyList()
        val tokens = LinkedHashSet<String>()
        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            callRegex.findAll(rawLine).forEach { match ->
                val token = match.groupValues[1].lowercase(Locale.US)
                if (token.length < 2) return@forEach
                if (token in ignoredCallTokens) return@forEach
                if (looksLikeDeclaration(rawLine, token, match.range.first)) return@forEach
                tokens += token
            }
        }
        return tokens.toList()
    }

    private fun looksLikeDeclaration(line: String, token: String, index: Int): Boolean {
        if (index <= 0 || index > line.length) return false
        val prefix = line.substring(0, index)
        return declarationPrefixes.any { kw ->
            Regex("""\b$kw\s+$token\s*$""", RegexOption.IGNORE_CASE).containsMatchIn(prefix)
        }
    }

    private fun nextId(conn: Connection): Long =
        conn.prepareStatement("SELECT nextval('links_seq')").use { ps ->
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    private data class SourceChunk(
        val chunkId: Long,
        val fileId: Long,
        val ordinal: Int,
        val startLine: Int?,
        val endLine: Int?,
        val content: String
    )

    private data class ImportSymbol(
        val name: String,
        val qualifiedName: String?,
        val startLine: Int?
    )

    private data class TargetSymbol(
        val fileId: Long,
        val chunkId: Long,
        val name: String,
        val qualifiedName: String?,
        val symbolType: String
    )

    private data class LinkRow(
        val sourceChunkId: Long,
        val targetFileId: Long,
        val targetChunkId: Long,
        val type: String,
        val label: String?,
        val score: Double?
    )

    companion object {
        private const val LINK_TYPE_CALLS = "CALLS"
        private const val LINK_TYPE_DEPENDS_ON = "DEPENDS_ON"
        private const val LINK_TYPE_COVERS = "COVERS"

        private val testDirSegments = listOf(
            "/test/", "/tests/", "/__tests__/", "/spec/", "/specs/"
        )
        private val testFileNameRegex = Regex(
            """(?i)(^test_.*|.*_test|.*tests?|.*\.test|.*\.spec)\.(kt|kts|java|py|go|rs|ts|tsx|js|jsx|mjs|cjs|rb|cs|swift|scala|php)$"""
        )

        /** True if the given relative path looks like a test source (by directory or filename). */
        fun looksLikeTestFile(relPath: String): Boolean {
            val normalized = "/" + relPath.replace('\\', '/').trimStart('/')
            if (testDirSegments.any { normalized.contains(it, ignoreCase = true) }) return true
            val name = normalized.substringAfterLast('/')
            return testFileNameRegex.matches(name)
        }

        private val callRegex = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        private val declarationPrefixes = setOf("fun", "def", "function", "class", "interface", "enum", "object")
        private val ignoredCallTokens = setOf(
            "if", "for", "while", "when", "switch", "catch", "return", "throw",
            "try", "with", "super", "this", "new", "println", "print", "log",
            "map", "filter", "reduce", "listof", "setof", "arrayof"
        )
    }
}
