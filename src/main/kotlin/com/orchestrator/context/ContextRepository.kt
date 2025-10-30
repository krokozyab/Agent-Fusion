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
import com.orchestrator.utils.Logger
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max

/**
 * Persists and retrieves context indexing artefacts using DuckDB.
 */
object ContextRepository {

    private val log = Logger.logger("com.orchestrator.context.ContextRepository")

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

    private val writeLock = ReentrantLock(true)

    // region Public API

    fun replaceFileArtifacts(fileState: FileState, chunkArtifacts: List<ChunkArtifacts>): FileArtifacts =
        writeLock.withLock {
            // Phase 1: Delete old artifacts in isolated transaction (can fail and recover independently)
            ContextDatabase.transaction { conn ->
                val existing = if (fileState.id > 0) {
                    getFileStateById(conn, fileState.id)
                } else {
                    getFileStateByPath(conn, fileState.relativePath)
                }

                if (existing != null) {
                    val existingChunkIds = getChunkIdsForFile(conn, existing.id)
                    if (existingChunkIds.isNotEmpty()) {
                        log.debug("Deleting {} chunks for file {}", existingChunkIds.size, existing.relativePath)

                        // DuckDB has undiscoverable FK constraints that prevent deletion
                        // Delete dependent records first, then try to delete chunks
                        try {
                            log.debug("Deleting dependent records for file {}", existing.id)

                            // Delete embeddings
                            conn.createStatement().use { st ->
                                st.execute("""
                                    DELETE FROM embeddings
                                    WHERE chunk_id IN (SELECT chunk_id FROM chunks WHERE file_id = ${existing.id})
                                """)
                            }
                            log.debug("Deleted embeddings")

                            // Delete links
                            conn.createStatement().use { st ->
                                st.execute("""
                                    DELETE FROM links
                                    WHERE source_chunk_id IN (SELECT chunk_id FROM chunks WHERE file_id = ${existing.id})
                                       OR target_chunk_id IN (SELECT chunk_id FROM chunks WHERE file_id = ${existing.id})
                                """)
                            }
                            log.debug("Deleted links")

                            // Delete symbols
                            if (hasTable(conn, "symbols")) {
                                conn.createStatement().use { st ->
                                    st.execute("""
                                        DELETE FROM symbols
                                        WHERE chunk_id IN (SELECT chunk_id FROM chunks WHERE file_id = ${existing.id})
                                    """)
                                }
                                log.debug("Deleted symbols")
                            }

                            // Try to delete chunks
                            conn.createStatement().execute("DELETE FROM chunks WHERE file_id = ${existing.id}")
                            log.debug("Successfully deleted chunks")

                        } catch (fkError: SQLException) {
                            // Unable to delete due to FK constraint - skip and continue with re-indexing
                            // The chunks will be marked as stale but the new chunks will be inserted
                            log.warn("Unable to delete old chunks due to FK constraint: {}. New chunks will be inserted alongside old ones.", fkError.message)
                        }
                    }

                    deleteLinksByTargetFile(conn, existing.id)
                }
            }

            // Phase 2: Insert new artifacts in separate transaction
            // This ensures that even if Phase 1 partially succeeds, Phase 2 can still proceed
            ContextDatabase.transaction { conn ->
                val persistedFile = upsertFileState(conn, fileState)

                val persistedChunks = ArrayList<ChunkArtifacts>(chunkArtifacts.size)
                chunkArtifacts.forEach { artifact ->
                    val chunkId = nextId(conn, "chunks_seq")
                    insertChunk(conn, artifact.chunk.copy(id = chunkId, fileId = persistedFile.id))
                    val embeddings = insertEmbeddings(conn, chunkId, artifact.embeddings)
                    val links = insertLinks(conn, chunkId, persistedFile.id, artifact.links)
                    persistedChunks.add(
                        ChunkArtifacts(
                            chunk = artifact.chunk.copy(id = chunkId, fileId = persistedFile.id),
                            embeddings = embeddings,
                            links = links
                        )
                    )
                }

                FileArtifacts(persistedFile, persistedChunks)
            }
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

    fun countActiveFiles(): Long = ContextDatabase.withConnection { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM file_state WHERE is_deleted = FALSE").use { ps ->
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getLong(1) else 0L
            }
        }
    }

    fun countChunks(): Long = ContextDatabase.withConnection { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM chunks").use { ps ->
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getLong(1) else 0L
            }
        }
    }

    fun countEmbeddings(): Long = ContextDatabase.withConnection { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM embeddings").use { ps ->
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getLong(1) else 0L
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
        if (chunkIds.isEmpty()) return
        val placeholders = chunkIds.joinToString(",") { "?" }
        // Delete links where source_chunk_id matches (these chunks are sources)
        val sql = "DELETE FROM links WHERE source_chunk_id IN ($placeholders)"
        conn.prepareStatement(sql).use { ps ->
            chunkIds.forEachIndexed { index, id ->
                ps.setLong(index + 1, id)
            }
            ps.executeUpdate()
        }
    }

    private fun deleteAllLinksReferencingChunks(conn: Connection, chunkIds: List<Long>) {
        if (chunkIds.isEmpty()) return

        log.debug("deleteAllLinksReferencingChunks: Attempting to delete links for chunks: {}", chunkIds.take(3))

        // CRITICAL: Delete links with target_chunk_id = NULL check too
        // Some rows might have target_chunk_id IS NOT NULL AND target_chunk_id IN (...)
        val placeholders = chunkIds.joinToString(",") { "?" }

        // First pass: Delete by target_chunk_id (nullable column)
        val targetSql = "DELETE FROM links WHERE target_chunk_id IS NOT NULL AND target_chunk_id IN ($placeholders)"
        try {
            conn.prepareStatement(targetSql).use { ps ->
                chunkIds.forEachIndexed { index, id ->
                    ps.setLong(index + 1, id)
                }
                val deleted = ps.executeUpdate()
                log.debug("Deleted {} links where target_chunk_id IN chunkIds", deleted)
            }
        } catch (e: SQLException) {
            log.error("Failed to delete links by target_chunk_id: {}", e.message, e)
            // Don't throw - continue with source deletion
        }

        // Second pass: Delete by source_chunk_id
        val sourceSql = "DELETE FROM links WHERE source_chunk_id IN ($placeholders)"
        try {
            conn.prepareStatement(sourceSql).use { ps ->
                chunkIds.forEachIndexed { index, id ->
                    ps.setLong(index + 1, id)
                }
                val deleted = ps.executeUpdate()
                log.debug("Deleted {} links where source_chunk_id IN chunkIds", deleted)
            }
        } catch (e: SQLException) {
            log.error("Failed to delete links by source_chunk_id: {}", e.message, e)
            // Don't throw - let purgeChunkForeignReferences try
        }

        // Verify deletion worked
        val verifySql = "SELECT COUNT(*) as cnt FROM links WHERE source_chunk_id IN ($placeholders) OR target_chunk_id IN ($placeholders)"
        try {
            conn.prepareStatement(verifySql).use { ps ->
                chunkIds.forEachIndexed { index, id -> ps.setLong(index + 1, id) }
                chunkIds.forEachIndexed { index, id -> ps.setLong(chunkIds.size + index + 1, id) }
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        val remaining = rs.getInt("cnt")
                        if (remaining > 0) {
                            log.warn("WARNING: {} links still exist after deletion attempt!", remaining)
                        }
                    }
                }
            }
        } catch (e: SQLException) {
            log.debug("Verification query failed: {}", e.message)
        }
    }

    private fun verifyNoLinksReferencingChunks(conn: Connection, chunkIds: List<Long>) {
        if (chunkIds.isEmpty()) return

        val placeholders = chunkIds.joinToString(",") { "?" }
        val sql = "SELECT COUNT(*) as cnt FROM links WHERE source_chunk_id IN ($placeholders) OR target_chunk_id IN ($placeholders)"
        conn.prepareStatement(sql).use { ps ->
            chunkIds.forEachIndexed { index, id -> ps.setLong(index + 1, id) }
            chunkIds.forEachIndexed { index, id -> ps.setLong(chunkIds.size + index + 1, id) }
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    val count = rs.getInt("cnt")
                    if (count > 0) {
                        log.warn("WARNING: {} links still reference chunks after deletion!", count)
                        // Log which links are still there
                        val debugSql = "SELECT link_id, source_chunk_id, target_chunk_id FROM links WHERE source_chunk_id IN ($placeholders) OR target_chunk_id IN ($placeholders)"
                        conn.prepareStatement(debugSql).use { debugPs ->
                            chunkIds.forEachIndexed { index, id -> debugPs.setLong(index + 1, id) }
                            chunkIds.forEachIndexed { index, id -> debugPs.setLong(chunkIds.size + index + 1, id) }
                            debugPs.executeQuery().use { debugRs ->
                                while (debugRs.next()) {
                                    log.warn("  Link {} still references chunks (source: {}, target: {})",
                                        debugRs.getLong("link_id"),
                                        debugRs.getLong("source_chunk_id"),
                                        debugRs.getLong("target_chunk_id")
                                    )
                                }
                            }
                        }
                    } else {
                        log.debug("Verified: no links reference these chunks")
                    }
                }
            }
        }
    }

    private fun deleteLinksByTargetChunkIds(conn: Connection, chunkIds: List<Long>) {
        val placeholders = chunkIds.joinToString(",") { "?" }
        val sql = "DELETE FROM links WHERE target_chunk_id IN ($placeholders)"
        conn.prepareStatement(sql).use { ps ->
            chunkIds.forEachIndexed { index, id ->
                ps.setLong(index + 1, id)
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

    private fun atomicReplaceChunksTable(conn: Connection, fileId: Long) {
        log.debug("Atomically replacing chunks table to remove file {}", fileId)

        try {
            // Step 1: First, delete dependent records for chunks from the file being modified
            // This must happen BEFORE we try to delete chunks
            log.debug("Deleting dependent records for file {}", fileId)

            // Delete embeddings that reference chunks from this file
            conn.createStatement().use { st ->
                st.execute("""
                    DELETE FROM embeddings
                    WHERE chunk_id IN (SELECT chunk_id FROM chunks WHERE file_id = $fileId)
                """)
            }
            log.debug("Deleted embeddings for file")

            // Delete links that reference chunks from this file
            conn.createStatement().use { st ->
                st.execute("""
                    DELETE FROM links
                    WHERE source_chunk_id IN (SELECT chunk_id FROM chunks WHERE file_id = $fileId)
                       OR target_chunk_id IN (SELECT chunk_id FROM chunks WHERE file_id = $fileId)
                """)
            }
            log.debug("Deleted links for file")

            // Delete symbols that reference chunks from this file
            if (hasTable(conn, "symbols")) {
                conn.createStatement().use { st ->
                    st.execute("""
                        DELETE FROM symbols
                        WHERE chunk_id IN (SELECT chunk_id FROM chunks WHERE file_id = $fileId)
                    """)
                }
                log.debug("Deleted symbols for file")
            }

            // Delete usage_metrics that reference chunks from this file (if column exists)
            // Note: The usage_metrics table schema doesn't match schema.sql, but try anyway
            try {
                conn.createStatement().use { st ->
                    st.execute("""
                        DELETE FROM usage_metrics
                        WHERE chunk_id IN (SELECT chunk_id FROM chunks WHERE file_id = $fileId)
                    """)
                }
                log.debug("Deleted usage_metrics for file")
            } catch (ignore: SQLException) {
                // Table might not have chunk_id column, that's ok
                log.debug("usage_metrics deletion skipped (column may not exist)")
            }

            // Step 2: If we still hit FK constraint errors, it means there's an undiscoverable FK
            // The only reliable way to handle this is to create new tables without FK constraints
            // and swap them atomically
            try {
                // Try direct deletion first
                conn.createStatement().execute("DELETE FROM chunks WHERE file_id = $fileId")
                log.debug("Deleted chunks for file")
            } catch (fkError: SQLException) {
                log.warn("Direct chunk deletion failed with FK constraint, using table recreation approach: {}", fkError.message)

                // Create new chunks table without the problematic chunks
                conn.createStatement().execute("""
                    CREATE TABLE chunks_safe AS
                    SELECT * FROM chunks
                    WHERE file_id != $fileId
                """)

                // Drop all dependent tables that have FK constraints to chunks
                try { conn.createStatement().execute("DROP TABLE IF EXISTS embeddings") } catch (ignore: SQLException) { }
                try { conn.createStatement().execute("DROP TABLE IF EXISTS links") } catch (ignore: SQLException) { }
                try { conn.createStatement().execute("DROP TABLE IF EXISTS symbols") } catch (ignore: SQLException) { }
                try { conn.createStatement().execute("DROP TABLE IF EXISTS usage_metrics") } catch (ignore: SQLException) { }

                // Now drop and recreate chunks
                conn.createStatement().execute("DROP TABLE chunks")
                conn.createStatement().execute("ALTER TABLE chunks_safe RENAME TO chunks")

                log.debug("Completed table recreation approach for chunk deletion")
            }

            log.debug("Successfully removed all artifacts for file {}", fileId)
        } catch (e: SQLException) {
            log.error("Failed to delete artifacts: {}", e.message)
            throw e
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

    private data class ChunkReference(val schema: String?, val table: String, val column: String)

    @Volatile
    private var cachedChunkReferences: List<ChunkReference>? = null

    private fun purgeChunkForeignReferences(
        conn: Connection,
        chunkIds: List<Long>,
        forceRefresh: Boolean = false
    ) {
        if (chunkIds.isEmpty()) return

        // DEBUG: Log all tables in database
        log.debug("=== DISCOVERING ALL TABLES IN DATABASE ===")
        try {
            val sql = "SELECT table_schema, table_name FROM information_schema.tables WHERE table_type = 'BASE TABLE' ORDER BY table_name"
            conn.prepareStatement(sql).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        log.debug("Table: {}.{}", rs.getString("table_schema"), rs.getString("table_name"))
                    }
                }
            }
        } catch (e: Exception) {
            log.debug("Failed to list tables: {}", e.message)
        }

        // First, try dynamic discovery of FK references
        try {
            val references = getChunkReferenceColumns(conn, forceRefresh)
            log.debug("Found {} FK references to chunks table", references.size)
            references.forEach { ref ->
                log.debug("  FK Reference: {}.{}.{}", ref.schema, ref.table, ref.column)
            }
            val placeholders = chunkIds.joinToString(",") { "?" }

            references.forEach { ref ->
                val tableSql = buildString {
                    if (!ref.schema.isNullOrBlank() && !ref.schema.equals("main", ignoreCase = true)) {
                        append(quoteIdentifier(ref.schema!!))
                        append(".")
                    }
                    append(quoteIdentifier(ref.table))
                }
                val columnSql = quoteIdentifier(ref.column)
                val sql = "DELETE FROM $tableSql WHERE $columnSql IN ($placeholders)"
                try {
                    conn.prepareStatement(sql).use { ps ->
                        chunkIds.forEachIndexed { index, id -> ps.setLong(index + 1, id) }
                        val deleted = ps.executeUpdate()
                        if (deleted > 0) {
                            log.debug("Purged {} records from {}.{}.{}", deleted, ref.schema, ref.table, ref.column)
                        }
                    }
                } catch (e: SQLException) {
                    log.debug("Failed to purge foreign references in {}.{}: {}", ref.schema, ref.table, e.message)
                    // Continue with other tables even if one fails
                }
            }
        } catch (e: SQLException) {
            log.debug("Failed to load chunk reference columns, will try explicit tables: {}", e.message)
        }

        // FALLBACK: Explicitly delete from all known tables that might have chunk_id references
        // This ensures we don't miss any FK constraints if information_schema introspection fails
        val placeholders = chunkIds.joinToString(",") { "?" }

        // BRUTE FORCE: Find ALL tables with ANY column that has chunk_id pattern
        log.debug("=== BRUTE FORCE SEARCH: Looking for columns with 'chunk' in name ===")
        try {
            val findColumnSql = """
                SELECT table_name, column_name
                FROM information_schema.columns
                WHERE lower(column_name) LIKE '%chunk%'
                ORDER BY table_name, column_name
            """.trimIndent()
            conn.prepareStatement(findColumnSql).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val tableName = rs.getString("table_name")
                        val columnName = rs.getString("column_name")
                        log.debug("Found column: {}.{}", tableName, columnName)

                        // Try to delete from this table/column
                        try {
                            val deleteSql = "DELETE FROM \"$tableName\" WHERE \"$columnName\" IN ($placeholders)"
                            conn.prepareStatement(deleteSql).use { deletePs ->
                                chunkIds.forEachIndexed { index, id -> deletePs.setLong(index + 1, id) }
                                val deleted = deletePs.executeUpdate()
                                if (deleted > 0) {
                                    log.warn("BRUTE FORCE DELETED {} records from {}.{}", deleted, tableName, columnName)
                                }
                            }
                        } catch (e: SQLException) {
                            log.debug("Failed to delete from {}.{}: {}", tableName, columnName, e.message)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.debug("Brute force search failed: {}", e.message)
        }

        val knownTablesWithChunkId = listOf("embeddings", "links", "usage_metrics", "symbols")

        knownTablesWithChunkId.forEach { tableName ->
            if (!hasTable(conn, tableName)) {
                log.debug("Table {} does not exist, skipping", tableName)
                return@forEach
            }

            // Try chunk_id column (works for embeddings, usage_metrics, symbols)
            if (hasColumn(conn, tableName, "chunk_id")) {
                try {
                    val sql = "DELETE FROM \"$tableName\" WHERE chunk_id IN ($placeholders)"
                    conn.prepareStatement(sql).use { ps ->
                        chunkIds.forEachIndexed { index, id -> ps.setLong(index + 1, id) }
                        val deleted = ps.executeUpdate()
                        if (deleted > 0) {
                            log.debug("Purged {} records from {}.chunk_id", deleted, tableName)
                        }
                    }
                } catch (e: SQLException) {
                    log.debug("Failed to delete from {} by chunk_id: {}", tableName, e.message)
                }
            }

            // For links table, also try source_chunk_id and target_chunk_id if not already handled
            if (tableName == "links") {
                if (hasColumn(conn, tableName, "source_chunk_id")) {
                    try {
                        val sql = "DELETE FROM \"$tableName\" WHERE source_chunk_id IN ($placeholders)"
                        conn.prepareStatement(sql).use { ps ->
                            chunkIds.forEachIndexed { index, id -> ps.setLong(index + 1, id) }
                            val deleted = ps.executeUpdate()
                            if (deleted > 0) {
                                log.debug("Purged {} records from links.source_chunk_id", deleted)
                            }
                        }
                    } catch (e: SQLException) {
                        log.error("Failed to delete from links by source_chunk_id: {}", e.message)
                    }
                }

                if (hasColumn(conn, tableName, "target_chunk_id")) {
                    try {
                        // CRITICAL: target_chunk_id is nullable but has FK constraint
                        val sql = "DELETE FROM \"$tableName\" WHERE target_chunk_id IS NOT NULL AND target_chunk_id IN ($placeholders)"
                        conn.prepareStatement(sql).use { ps ->
                            chunkIds.forEachIndexed { index, id -> ps.setLong(index + 1, id) }
                            val deleted = ps.executeUpdate()
                            if (deleted > 0) {
                                log.debug("Purged {} records from links.target_chunk_id", deleted)
                            }
                        }
                    } catch (e: SQLException) {
                        log.error("Failed to delete from links by target_chunk_id: {}", e.message)
                    }
                }
            }
        }
    }

    private fun getChunkReferenceColumns(conn: Connection, forceRefresh: Boolean): List<ChunkReference> {
        val cached = cachedChunkReferences
        if (!forceRefresh && cached != null) {
            return cached
        }
        val loaded = loadChunkReferenceColumns(conn)
        cachedChunkReferences = loaded
        return loaded
    }

    private fun loadChunkReferenceColumns(conn: Connection): List<ChunkReference> {
        val references = mutableListOf<ChunkReference>()

        // DuckDB PRAGMA foreign_key_list returns actual FK constraints
        // Use PRAGMA instead of information_schema which appears to be unreliable
        val tables = listOf("embeddings", "links", "usage_metrics", "symbols")

        tables.forEach { table ->
            try {
                val pragmaSql = "PRAGMA foreign_key_list($table)"
                conn.prepareStatement(pragmaSql).use { ps ->
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            // PRAGMA returns: id, seq, table, from, to, on_delete, on_update, match
                            val referencedTable = rs.getString("table")
                            val fromColumn = rs.getString("from")

                            // Only care about FKs that reference 'chunks' table
                            if (referencedTable.equals("chunks", ignoreCase = true)) {
                                references += ChunkReference("main", table, fromColumn)
                                log.debug("PRAGMA found FK: {}.{} -> chunks({})", table, fromColumn, rs.getString("to"))
                            }
                        }
                    }
                }
            } catch (e: SQLException) {
                log.debug("PRAGMA foreign_key_list failed for {}: {}", table, e.message)
            }
        }

        return references.distinct()
    }

    private fun quoteIdentifier(name: String): String {
        val escaped = name.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    private fun quoteLiteral(value: String): String {
        val escaped = value.replace("'", "''")
        return "'$escaped'"
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

    private fun diagnoseForeignKeyViolation(conn: Connection, chunkIds: List<Long>) {
        if (chunkIds.isEmpty()) return

        log.error("=== FOREIGN KEY VIOLATION DIAGNOSIS ===")
        log.error("Chunks that failed to delete: {}", chunkIds.take(5).joinToString(","))

        // Check embeddings
        val embedSql = "SELECT COUNT(*) as cnt FROM embeddings WHERE chunk_id IN (${chunkIds.joinToString(",") { "?" }})"
        conn.prepareStatement(embedSql).use { ps ->
            chunkIds.forEachIndexed { index, id -> ps.setLong(index + 1, id) }
            ps.executeQuery().use { rs ->
                if (rs.next() && rs.getInt("cnt") > 0) {
                    log.error("  EMBEDDINGS: {} records still reference these chunks", rs.getInt("cnt"))
                }
            }
        }

        // Check links (source)
        val linkSourceSql = "SELECT COUNT(*) as cnt FROM links WHERE source_chunk_id IN (${chunkIds.joinToString(",") { "?" }})"
        conn.prepareStatement(linkSourceSql).use { ps ->
            chunkIds.forEachIndexed { index, id -> ps.setLong(index + 1, id) }
            ps.executeQuery().use { rs ->
                if (rs.next() && rs.getInt("cnt") > 0) {
                    log.error("  LINKS (source): {} records still reference these chunks", rs.getInt("cnt"))
                }
            }
        }

        // Check links (target)
        val linkTargetSql = "SELECT COUNT(*) as cnt FROM links WHERE target_chunk_id IN (${chunkIds.joinToString(",") { "?" }})"
        conn.prepareStatement(linkTargetSql).use { ps ->
            chunkIds.forEachIndexed { index, id -> ps.setLong(index + 1, id) }
            ps.executeQuery().use { rs ->
                if (rs.next() && rs.getInt("cnt") > 0) {
                    log.error("  LINKS (target): {} records still reference these chunks", rs.getInt("cnt"))
                }
            }
        }

        // Check usage_metrics
        val metricsSql = "SELECT COUNT(*) as cnt FROM usage_metrics WHERE chunk_id IN (${chunkIds.joinToString(",") { "?" }})"
        conn.prepareStatement(metricsSql).use { ps ->
            chunkIds.forEachIndexed { index, id -> ps.setLong(index + 1, id) }
            ps.executeQuery().use { rs ->
                if (rs.next() && rs.getInt("cnt") > 0) {
                    log.error("  USAGE_METRICS: {} records still reference these chunks", rs.getInt("cnt"))
                }
            }
        }

        log.error("=== END DIAGNOSIS ===")
    }

    // endregion
}
