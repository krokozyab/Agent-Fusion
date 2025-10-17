package com.orchestrator.context

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import com.orchestrator.context.domain.ContextSnippet
import com.orchestrator.context.domain.ContextScope
import com.orchestrator.context.domain.Embedding
import com.orchestrator.context.domain.FileState
import com.orchestrator.context.domain.Link
import com.orchestrator.context.domain.TokenBudget
import com.orchestrator.context.storage.ContextDatabase
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.util.Locale
import kotlin.math.max

/**
 * Persists and retrieves context indexing artefacts using DuckDB.
 */
object ContextRepository {

    data class ChunkArtifacts(
        val chunk: Chunk,
        val embeddings: List<Embedding>,
        val links: List<Link>
    )

    data class FileArtifacts(
        val file: FileState,
        val chunks: List<ChunkArtifacts>
    )

    data class ChunkWithFile(
        val chunk: Chunk,
        val filePath: String,
        val language: String?
    )

    // region Public API

    fun replaceFileArtifacts(fileState: FileState, chunkArtifacts: List<ChunkArtifacts>): FileArtifacts =
        ContextDatabase.transaction { conn ->
            val persistedFile = upsertFileState(conn, fileState)
            val persistedChunks = replaceChunksInternal(conn, persistedFile.id, chunkArtifacts)
            FileArtifacts(persistedFile, persistedChunks)
        }

    fun fetchFileArtifactsByPath(relativePath: String): FileArtifacts? = ContextDatabase.withConnection { conn ->
        val file = getFileStateByPath(conn, relativePath) ?: return@withConnection null
        val chunks = getChunksByFileId(conn, file.id)
        val chunkIds = chunks.map { it.chunk.id }
        val embeddings = getEmbeddingsByChunkIds(conn, chunkIds)
        val links = getLinksBySourceChunkIds(conn, chunkIds)
        val chunkArtifacts = chunks.map { chunkWithFile ->
            ChunkArtifacts(
                chunk = chunkWithFile.chunk,
                embeddings = embeddings[chunkWithFile.chunk.id] ?: emptyList(),
                links = links[chunkWithFile.chunk.id] ?: emptyList()
            )
        }
        FileArtifacts(file, chunkArtifacts)
    }

    fun searchChunks(scope: ContextScope): List<ChunkWithFile> = ContextDatabase.withConnection { conn ->
        val sql = buildString {
            append(
                """
                SELECT c.chunk_id,
                       c.file_id,
                       c.ordinal,
                       c.kind,
                       c.start_line,
                       c.end_line,
                       c.token_count,
                       c.content,
                       c.summary,
                       c.created_at,
                       f.rel_path,
                       f.language
                FROM chunks c
                JOIN file_state f ON f.file_id = c.file_id
                WHERE 1=1
                """.trimIndent()
            )
            if (scope.paths.isNotEmpty()) {
                append(" AND f.rel_path IN (" + scope.paths.joinToString(",") { "?" } + ")")
            }
            if (scope.languages.isNotEmpty()) {
                append(" AND f.language IN (" + scope.languages.joinToString(",") { "?" } + ")")
            }
            if (scope.kinds.isNotEmpty()) {
                append(" AND c.kind IN (" + scope.kinds.joinToString(",") { "?" } + ")")
            }
            if (scope.excludePatterns.isNotEmpty()) {
                scope.excludePatterns.forEach {
                    append(" AND f.rel_path NOT LIKE ?")
                }
            }
            append(" ORDER BY f.rel_path, c.ordinal")
        }
        conn.prepareStatement(sql).use { ps ->
            var idx = 1
            scope.paths.forEach { ps.setString(idx++, it) }
            scope.languages.forEach { ps.setString(idx++, it) }
            scope.kinds.forEach { ps.setString(idx++, it.name) }
            scope.excludePatterns.forEach { ps.setString(idx++, globToLike(it)) }
            ps.executeQuery().use { rs ->
                val results = ArrayList<ChunkWithFile>()
                while (rs.next()) {
                    results.add(
                        ChunkWithFile(
                            chunk = rs.toChunk(),
                            filePath = rs.getString("rel_path"),
                            language = rs.getString("language")
                        )
                    )
                }
                results
            }
        }
    }

    fun fetchSnippets(scope: ContextScope, budget: TokenBudget): List<ContextSnippet> {
        val availableTokens = budget.availableForSnippets
        if (availableTokens <= 0) return emptyList()
        val chunks = searchChunks(scope)
        val accumulator = ArrayList<ContextSnippet>()
        var consumed = 0
        for (chunkWithFile in chunks) {
            val chunk = chunkWithFile.chunk
            val estimatedTokens = chunk.tokenEstimate ?: max(1, chunk.content.length / 4)
            if (consumed + estimatedTokens > availableTokens) break
            consumed += estimatedTokens
            accumulator.add(
                ContextSnippet(
                    chunkId = chunk.id,
                    score = 1.0,
                    filePath = chunkWithFile.filePath,
                    label = chunk.summary,
                    kind = chunk.kind,
                    text = chunk.content,
                    language = chunkWithFile.language,
                    offsets = chunk.lineSpan,
                    metadata = mapOf(
                        "fileId" to chunk.fileId.toString(),
                        "tokens" to estimatedTokens.toString()
                    )
                )
            )
        }
        return accumulator
    }

    fun listAllFiles(limit: Int? = null): List<FileState> = ContextDatabase.withConnection { conn ->
        val sql = buildString {
            append("SELECT * FROM file_state ORDER BY rel_path")
            if (limit != null) append(" LIMIT $limit")
        }
        conn.prepareStatement(sql).use { ps ->
            ps.executeQuery().use { rs ->
                val files = ArrayList<FileState>()
                while (rs.next()) {
                    files.add(rs.toFileState())
                }
                files
            }
        }
    }

    fun deleteFileArtifacts(relativePath: String): Boolean {
        val state = ContextDatabase.withConnection { conn -> getFileStateByPath(conn, relativePath) } ?: return true
        val artifacts = fetchFileArtifactsByPath(relativePath) ?: FileArtifacts(state, emptyList())
        val fileId = artifacts.file.id
        val chunkIds = artifacts.chunks.map { it.chunk.id }

        return try {
            ContextDatabase.transaction { conn ->
                if (chunkIds.isNotEmpty()) {
                    deleteEmbeddings(conn, chunkIds)
                    deleteLinks(conn, chunkIds)
                    deleteUsageMetrics(conn, fileId, chunkIds)
                    deleteSymbolsByChunkIds(conn, chunkIds)
                }
                deleteUsageMetricsForFile(conn, fileId)
                deleteSymbolsByFile(conn, fileId)
                deleteLinksByTargetFile(conn, fileId)
            }

            ContextDatabase.transaction { conn ->
                deleteChunks(conn, fileId)
            }

            ContextDatabase.transaction { conn ->
                deleteFileStateRow(conn, fileId)
            }

            true
        } catch (t: Throwable) {
            restoreArtifacts(artifacts)
            throw t
        }
    }

    // endregion

    // region Internal helpers

    private fun upsertFileState(conn: Connection, state: FileState): FileState {
        val existing = if (state.id > 0) getFileStateById(conn, state.id) else getFileStateByPath(conn, state.relativePath)
        val payload = state.copy(id = existing?.id ?: state.id)
        return if (existing == null) insertFileState(conn, payload) else updateFileState(conn, payload)
    }

    private fun insertFileState(conn: Connection, state: FileState): FileState {
        val id = if (state.id > 0) state.id else nextId(conn, "file_state_seq")
        val sql = """
            INSERT INTO file_state (
                file_id, rel_path, content_hash, size_bytes, mtime_ns,
                language, kind, fingerprint, indexed_at, is_deleted
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            var idx = 1
            ps.setLong(idx++, id)
            ps.setString(idx++, state.relativePath)
            ps.setString(idx++, state.contentHash)
            ps.setLong(idx++, state.sizeBytes)
            ps.setLong(idx++, state.modifiedTimeNs)
            ps.setString(idx++, state.language)
            ps.setString(idx++, state.kind)
            ps.setString(idx++, state.fingerprint)
            ps.setTimestamp(idx++, Timestamp.from(state.indexedAt))
            ps.setBoolean(idx++, state.isDeleted)
            ps.executeUpdate()
        }
        return state.copy(id = id)
    }

    private fun updateFileState(conn: Connection, state: FileState): FileState {
        val sql = """
            UPDATE file_state SET
                rel_path = ?,
                content_hash = ?,
                size_bytes = ?,
                mtime_ns = ?,
                language = ?,
                kind = ?,
                fingerprint = ?,
                indexed_at = ?,
                is_deleted = ?
            WHERE file_id = ?
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            var idx = 1
            ps.setString(idx++, state.relativePath)
            ps.setString(idx++, state.contentHash)
            ps.setLong(idx++, state.sizeBytes)
            ps.setLong(idx++, state.modifiedTimeNs)
            ps.setString(idx++, state.language)
            ps.setString(idx++, state.kind)
            ps.setString(idx++, state.fingerprint)
            ps.setTimestamp(idx++, Timestamp.from(state.indexedAt))
            ps.setBoolean(idx++, state.isDeleted)
            ps.setLong(idx, state.id)
            ps.executeUpdate()
        }
        return state
    }

    private fun replaceChunksInternal(conn: Connection, fileId: Long, chunkArtifacts: List<ChunkArtifacts>): List<ChunkArtifacts> {
        val existingChunkIds = getChunkIdsForFile(conn, fileId)
        if (existingChunkIds.isNotEmpty()) {
            deleteEmbeddings(conn, existingChunkIds)
            deleteLinks(conn, existingChunkIds)
            deleteUsageMetrics(conn, fileId, existingChunkIds)
            deleteSymbolsByChunkIds(conn, existingChunkIds)
            deleteLinksByTargetFile(conn, fileId)
            deleteChunks(conn, fileId)
        }

        val persisted = ArrayList<ChunkArtifacts>(chunkArtifacts.size)
        chunkArtifacts.forEach { artifact ->
            val chunkId = if (artifact.chunk.id > 0) artifact.chunk.id else nextId(conn, "chunks_seq")
            insertChunk(conn, artifact.chunk.copy(id = chunkId, fileId = fileId))
            val embeddings = insertEmbeddings(conn, chunkId, artifact.embeddings)
            val links = insertLinks(conn, chunkId, fileId, artifact.links)
            persisted.add(
                ChunkArtifacts(
                    chunk = artifact.chunk.copy(id = chunkId, fileId = fileId),
                    embeddings = embeddings,
                    links = links
                )
            )
        }
        return persisted
    }

    private fun insertChunk(conn: Connection, chunk: Chunk) {
        val sql = """
            INSERT INTO chunks (
                chunk_id, file_id, ordinal, kind, start_line, end_line,
                token_count, content, summary, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            var idx = 1
            ps.setLong(idx++, chunk.id)
            ps.setLong(idx++, chunk.fileId)
            ps.setInt(idx++, chunk.ordinal)
            ps.setString(idx++, chunk.kind.name)
            if (chunk.startLine != null) ps.setInt(idx++, chunk.startLine) else ps.setNull(idx++, java.sql.Types.INTEGER)
            if (chunk.endLine != null) ps.setInt(idx++, chunk.endLine) else ps.setNull(idx++, java.sql.Types.INTEGER)
            if (chunk.tokenEstimate != null) ps.setInt(idx++, chunk.tokenEstimate) else ps.setNull(idx++, java.sql.Types.INTEGER)
            ps.setString(idx++, chunk.content)
            ps.setString(idx++, chunk.summary)
            ps.setTimestamp(idx, Timestamp.from(chunk.createdAt))
            ps.executeUpdate()
        }
    }

    private fun insertEmbeddings(conn: Connection, chunkId: Long, embeddings: List<Embedding>): List<Embedding> {
        if (embeddings.isEmpty()) return emptyList()
        val persisted = ArrayList<Embedding>(embeddings.size)
        val sql = """
            INSERT INTO embeddings (
                embedding_id, chunk_id, model, dimensions, vector, created_at
            ) VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            embeddings.forEach { embedding ->
                val id = if (embedding.id > 0) embedding.id else nextId(conn, "embeddings_seq")
                var idx = 1
                ps.setLong(idx++, id)
                ps.setLong(idx++, chunkId)
                ps.setString(idx++, embedding.model)
                ps.setInt(idx++, embedding.dimensions)
                ps.setString(idx++, serializeVector(embedding.vector))
                ps.setTimestamp(idx, Timestamp.from(embedding.createdAt))
                ps.executeUpdate()
                persisted.add(embedding.copy(id = id, chunkId = chunkId))
            }
        }
        return persisted
    }

    private fun insertLinks(conn: Connection, sourceChunkId: Long, fileId: Long, links: List<Link>): List<Link> {
        if (links.isEmpty()) return emptyList()
        val persisted = ArrayList<Link>(links.size)
        val sql = """
            INSERT INTO links (
                link_id, source_chunk_id, target_file_id, target_chunk_id,
                link_type, label, score, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            links.forEach { link ->
                val id = if (link.id > 0) link.id else nextId(conn, "links_seq")
                var idx = 1
                ps.setLong(idx++, id)
                ps.setLong(idx++, sourceChunkId)
                val targetFile = if (link.targetFileId > 0) link.targetFileId else fileId
                ps.setLong(idx++, targetFile)
                if (link.targetChunkId != null && link.targetChunkId > 0) {
                    ps.setLong(idx++, link.targetChunkId)
                } else {
                    ps.setNull(idx++, java.sql.Types.BIGINT)
                }
                ps.setString(idx++, link.type)
                ps.setString(idx++, link.label)
                if (link.score != null) {
                    ps.setDouble(idx++, link.score)
                } else {
                    ps.setNull(idx++, java.sql.Types.DOUBLE)
                }
                ps.setTimestamp(idx, Timestamp.from(link.createdAt))
                ps.executeUpdate()
                persisted.add(
                    link.copy(
                        id = id,
                        sourceChunkId = sourceChunkId,
                        targetFileId = targetFile
                    )
                )
            }
        }
        return persisted
    }

    private fun getFileStateByPath(conn: Connection, relativePath: String): FileState? {
        val sql = "SELECT * FROM file_state WHERE rel_path = ?"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, relativePath)
            ps.executeQuery().use { rs ->
                return if (rs.next()) rs.toFileState() else null
            }
        }
    }

    private fun getFileStateById(conn: Connection, id: Long): FileState? {
        val sql = "SELECT * FROM file_state WHERE file_id = ?"
        conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs ->
                return if (rs.next()) rs.toFileState() else null
            }
        }
    }

    private fun getChunksByFileId(conn: Connection, fileId: Long): List<ChunkWithFile> {
        val sql = """
            SELECT c.*, f.rel_path, f.language
            FROM chunks c
            JOIN file_state f ON f.file_id = c.file_id
            WHERE c.file_id = ?
            ORDER BY c.ordinal
        """.trimIndent()
        return conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, fileId)
            ps.executeQuery().use { rs ->
                val results = ArrayList<ChunkWithFile>()
                while (rs.next()) {
                    results.add(
                        ChunkWithFile(
                            chunk = rs.toChunk(),
                            filePath = rs.getString("rel_path"),
                            language = rs.getString("language")
                        )
                    )
                }
                results
            }
        }
    }

    private fun getEmbeddingsByChunkIds(conn: Connection, chunkIds: List<Long>): Map<Long, List<Embedding>> {
        if (chunkIds.isEmpty()) return emptyMap()
        val placeholders = chunkIds.joinToString(",") { "?" }
        val sql = "SELECT * FROM embeddings WHERE chunk_id IN ($placeholders)"
        return conn.prepareStatement(sql).use { ps ->
            chunkIds.forEachIndexed { index, id -> ps.setLong(index + 1, id) }
            ps.executeQuery().use { rs ->
                val map = HashMap<Long, MutableList<Embedding>>()
                while (rs.next()) {
                    val embedding = rs.toEmbedding()
                    map.computeIfAbsent(embedding.chunkId) { ArrayList() }.add(embedding)
                }
                map
            }
        }
    }

    private fun getLinksBySourceChunkIds(conn: Connection, chunkIds: List<Long>): Map<Long, List<Link>> {
        if (chunkIds.isEmpty()) return emptyMap()
        val placeholders = chunkIds.joinToString(",") { "?" }
        val sql = "SELECT * FROM links WHERE source_chunk_id IN ($placeholders)"
        return conn.prepareStatement(sql).use { ps ->
            chunkIds.forEachIndexed { index, id -> ps.setLong(index + 1, id) }
            ps.executeQuery().use { rs ->
                val map = HashMap<Long, MutableList<Link>>()
                while (rs.next()) {
                    val link = rs.toLink()
                    map.computeIfAbsent(link.sourceChunkId) { ArrayList() }.add(link)
                }
                map
            }
        }
    }

    private fun getChunkIdsForFile(conn: Connection, fileId: Long): List<Long> {
        val sql = "SELECT chunk_id FROM chunks WHERE file_id = ?"
        conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, fileId)
            ps.executeQuery().use { rs ->
                val ids = ArrayList<Long>()
                while (rs.next()) {
                    ids.add(rs.getLong("chunk_id"))
                }
                return ids
            }
        }
    }

    private fun deleteEmbeddings(conn: Connection, chunkIds: List<Long>) {
        val placeholders = chunkIds.joinToString(",") { "?" }
        val sql = "DELETE FROM embeddings WHERE chunk_id IN ($placeholders)"
        conn.prepareStatement(sql).use { ps ->
            chunkIds.forEachIndexed { index, id -> ps.setLong(index + 1, id) }
            ps.executeUpdate()
        }
    }

    private fun deleteSymbolsByChunkIds(conn: Connection, chunkIds: List<Long>) {
        if (chunkIds.isEmpty() || !hasTable(conn, "symbols") || !hasColumn(conn, "symbols", "chunk_id")) return
        val placeholders = chunkIds.joinToString(",") { "?" }
        val sql = "DELETE FROM symbols WHERE chunk_id IN ($placeholders)"
        conn.prepareStatement(sql).use { ps ->
            chunkIds.forEachIndexed { index, id -> ps.setLong(index + 1, id) }
            ps.executeUpdate()
        }
    }

    private fun deleteLinks(conn: Connection, chunkIds: List<Long>) {
        val placeholders = chunkIds.joinToString(",") { "?" }
        val sql = "DELETE FROM links WHERE source_chunk_id IN ($placeholders) OR target_chunk_id IN ($placeholders)"
        conn.prepareStatement(sql).use { ps ->
            chunkIds.forEachIndexed { index, id ->
                ps.setLong(index + 1, id)
                ps.setLong(index + 1 + chunkIds.size, id)
            }
            ps.executeUpdate()
        }
    }

    private fun deleteUsageMetrics(conn: Connection, fileId: Long, chunkIds: List<Long>) {
        if (!hasTable(conn, "usage_metrics")) return
        val hasFileColumn = hasColumn(conn, "usage_metrics", "file_id")
        val hasChunkColumn = hasColumn(conn, "usage_metrics", "chunk_id")
        if (!hasFileColumn && (!hasChunkColumn || chunkIds.isEmpty())) return

        val clauses = ArrayList<String>()
        val parameters = ArrayList<Long>()

        if (hasFileColumn) {
            clauses += "file_id = ?"
            parameters += fileId
        }
        if (hasChunkColumn && chunkIds.isNotEmpty()) {
            val placeholder = chunkIds.joinToString(",") { "?" }
            clauses += "chunk_id IN ($placeholder)"
            parameters.addAll(chunkIds)
        }
        if (clauses.isEmpty()) return

        val sql = "DELETE FROM usage_metrics WHERE ${clauses.joinToString(" OR ")}"
        conn.prepareStatement(sql).use { ps ->
            parameters.forEachIndexed { index, value -> ps.setLong(index + 1, value) }
            ps.executeUpdate()
        }
    }

    private fun deleteUsageMetricsForFile(conn: Connection, fileId: Long) {
        if (!hasTable(conn, "usage_metrics") || !hasColumn(conn, "usage_metrics", "file_id")) return
        val sql = "DELETE FROM usage_metrics WHERE file_id = ?"
        conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, fileId)
            ps.executeUpdate()
        }
    }

    private fun deleteSymbolsByFile(conn: Connection, fileId: Long) {
        if (!hasTable(conn, "symbols")) return
        val hasFileColumn = hasColumn(conn, "symbols", "file_id")
        val hasChunkColumn = hasColumn(conn, "symbols", "chunk_id")
        if (!hasFileColumn && !hasChunkColumn) return

        val conditions = ArrayList<String>()
        val parameters = ArrayList<Long>()

        if (hasFileColumn) {
            conditions += "file_id = ?"
            parameters += fileId
        }
        if (hasChunkColumn) {
            conditions += "chunk_id IN (SELECT chunk_id FROM chunks WHERE file_id = ?)"
            parameters += fileId
        }
        if (conditions.isEmpty()) return

        val whereClause = conditions.joinToString(" OR ")
        val sql = "DELETE FROM symbols WHERE $whereClause"
        conn.prepareStatement(sql).use { ps ->
            parameters.forEachIndexed { index, value -> ps.setLong(index + 1, value) }
            ps.executeUpdate()
        }
    }

    private fun deleteChunks(conn: Connection, fileId: Long) {
        val sql = "DELETE FROM chunks WHERE file_id = ?"
        conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, fileId)
            ps.executeUpdate()
        }
    }

    private fun deleteSymbols(conn: Connection, fileId: Long, chunkIds: List<Long>) {
        if (!hasTable(conn, "symbols")) return
        val baseSql = if (chunkIds.isEmpty()) {
            "DELETE FROM symbols WHERE file_id = ?"
        } else {
            val placeholders = chunkIds.joinToString(",") { "?" }
            "DELETE FROM symbols WHERE file_id = ? OR chunk_id IN ($placeholders)"
        }
        conn.prepareStatement(baseSql).use { ps ->
            ps.setLong(1, fileId)
            if (chunkIds.isNotEmpty()) {
                chunkIds.forEachIndexed { index, id -> ps.setLong(index + 2, id) }
            }
            ps.executeUpdate()
        }
    }

    private fun deleteLinksByTargetFile(conn: Connection, fileId: Long) {
        val sql = "DELETE FROM links WHERE target_file_id = ?"
        conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, fileId)
            ps.executeUpdate()
        }
    }

    private fun deleteFileStateRow(conn: Connection, fileId: Long) {
        val sql = "DELETE FROM file_state WHERE file_id = ?"
        conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, fileId)
            ps.executeUpdate()
        }
    }

    private fun restoreArtifacts(artifacts: FileArtifacts) {
        ContextDatabase.transaction { conn ->
            val existingFile = getFileStateById(conn, artifacts.file.id)
            val restoredFile = if (existingFile == null) {
                insertFileState(conn, artifacts.file)
            } else {
                updateFileState(conn, artifacts.file)
            }

            val existingChunkIds = getChunkIdsForFile(conn, restoredFile.id).toHashSet()

            artifacts.chunks.forEach { chunkArtifacts ->
                val chunk = chunkArtifacts.chunk.copy(fileId = restoredFile.id)
                if (!existingChunkIds.contains(chunk.id)) {
                    insertChunk(conn, chunk)
                }
                if (chunkArtifacts.embeddings.isNotEmpty()) {
                    insertEmbeddings(conn, chunk.id, chunkArtifacts.embeddings)
                }
                if (chunkArtifacts.links.isNotEmpty()) {
                    insertLinks(conn, chunk.id, restoredFile.id, chunkArtifacts.links)
                }
            }
        }
    }

    private fun hasTable(conn: Connection, tableName: String): Boolean {
        val sql = "SELECT 1 FROM information_schema.tables WHERE lower(table_name) = ? LIMIT 1"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, tableName.lowercase(Locale.US))
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    private fun hasColumn(conn: Connection, tableName: String, columnName: String): Boolean {
        if (!hasTable(conn, tableName)) return false
        val sql = """
            SELECT 1 FROM information_schema.columns
            WHERE lower(table_name) = ? AND lower(column_name) = ?
            LIMIT 1
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, tableName.lowercase(Locale.US))
            ps.setString(2, columnName.lowercase(Locale.US))
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    private fun ResultSet.toFileState(): FileState {
        return FileState(
            id = getLong("file_id"),
            relativePath = getString("rel_path"),
            contentHash = getString("content_hash"),
            sizeBytes = getLong("size_bytes"),
            modifiedTimeNs = getLong("mtime_ns"),
            language = getString("language"),
            kind = getString("kind"),
            fingerprint = getString("fingerprint"),
            indexedAt = getTimestamp("indexed_at").toInstant(),
            isDeleted = getBoolean("is_deleted")
        )
    }

    private fun ResultSet.toChunk(): Chunk {
        val kindName = getString("kind")
        val chunkKind = runCatching { ChunkKind.valueOf(kindName) }.getOrElse { ChunkKind.PARAGRAPH }
        return Chunk(
            id = getLong("chunk_id"),
            fileId = getLong("file_id"),
            ordinal = getInt("ordinal"),
            kind = chunkKind,
            startLine = getNullableInt("start_line"),
            endLine = getNullableInt("end_line"),
            tokenEstimate = getNullableInt("token_count"),
            content = getString("content"),
            summary = getString("summary"),
            createdAt = getTimestamp("created_at").toInstant()
        )
    }

    private fun ResultSet.toEmbedding(): Embedding {
        return Embedding(
            id = getLong("embedding_id"),
            chunkId = getLong("chunk_id"),
            model = getString("model"),
            dimensions = getInt("dimensions"),
        vector = deserializeVector(getString("vector")),
            createdAt = getTimestamp("created_at").toInstant()
        )
    }

    private fun ResultSet.toLink(): Link = Link(
        id = getLong("link_id"),
        sourceChunkId = getLong("source_chunk_id"),
        targetFileId = getLong("target_file_id"),
        targetChunkId = getNullableLong("target_chunk_id"),
        type = getString("link_type"),
        label = getString("label"),
        score = getNullableDouble("score"),
        createdAt = getTimestamp("created_at").toInstant()
    )

    private fun ResultSet.getNullableInt(column: String): Int? = getObject(column)?.let { (it as Number).toInt() }
    private fun ResultSet.getNullableLong(column: String): Long? = getObject(column)?.let { (it as Number).toLong() }
    private fun ResultSet.getNullableDouble(column: String): Double? = getObject(column)?.let { (it as Number).toDouble() }

    private fun serializeVector(vector: List<Float>): String =
        vector.joinToString(prefix = "[", postfix = "]") { it.toString() }

    private fun deserializeVector(text: String): List<Float> {
        val trimmed = text.trim()
        if (trimmed.length <= 2) return emptyList()
        return trimmed.removePrefix("[").removeSuffix("]")
            .split(',')
            .mapNotNull { token -> token.trim().takeIf { it.isNotEmpty() }?.toFloat() }
    }

    private fun nextId(conn: Connection, sequence: String): Long {
        val sql = "SELECT nextval('$sequence')"
        conn.prepareStatement(sql).use { ps ->
            ps.executeQuery().use { rs ->
                rs.next()
                return rs.getLong(1)
            }
        }
    }

    private fun globToLike(pattern: String): String {
        return pattern
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
            .replace("*", "%")
            .replace("?", "_")
    }

    // endregion
}
