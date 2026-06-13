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
        val relativePath: String?,
        val language: String?
    )

    private val writeLock = ReentrantLock(true)

    // region Public API

    fun replaceFileArtifacts(fileState: FileState, chunkArtifacts: List<ChunkArtifacts>, batchSize: Int = 128): FileArtifacts =
        writeLock.withLock {
            // Phase 1: Delete old artifacts using separate transactions for each deletion step
            // This approach isolates each deletion operation, preventing a single FK constraint error
            // from aborting all subsequent operations
            var existingFileId: Long? = null

            ContextDatabase.withConnection { conn ->
                val existing = if (fileState.id > 0) {
                    getFileStateById(conn, fileState.id)
                } else {
                    getFileStateByPath(conn, fileState.absolutePath)
                }
                existingFileId = existing?.id

                if (existing != null && existing.id > 0) {
                    val existingChunkIds = getChunkIdsForFile(conn, existing.id)
                    if (existingChunkIds.isNotEmpty()) {
                        log.debug("Deleting {} chunks for file {}", existingChunkIds.size, existing.relativePath)

                        // Step 1: Delete embeddings (in separate transaction)
                        try {
                            ContextDatabase.transaction { innerConn ->
                                log.debug("Step 1: Deleting embeddings for {} chunks", existingChunkIds.size)
                                innerConn.createStatement().use { st ->
                                    st.execute("""
                                        DELETE FROM embeddings
                                        WHERE chunk_id IN (SELECT chunk_id FROM chunks WHERE file_id = ${existing.id})
                                    """)
                                }
                                log.debug("Step 1 complete: Deleted embeddings")
                            }
                        } catch (e: SQLException) {
                            log.warn("Failed to delete embeddings during artifact replace; stale embedding rows may remain: {}", e.message)
                        }

                        // Step 2: Delete links (in separate transaction)
                        try {
                            ContextDatabase.transaction { innerConn ->
                                log.debug("Step 2: Deleting links for {} chunks", existingChunkIds.size)
                                innerConn.createStatement().use { st ->
                                    st.execute("""
                                        DELETE FROM links
                                        WHERE source_chunk_id IN (SELECT chunk_id FROM chunks WHERE file_id = ${existing.id})
                                           OR target_chunk_id IN (SELECT chunk_id FROM chunks WHERE file_id = ${existing.id})
                                    """)
                                }
                                log.debug("Step 2 complete: Deleted links")
                            }
                        } catch (e: SQLException) {
                            log.warn("Failed to delete links during artifact replace; stale link rows may remain: {}", e.message)
                        }

                        // Step 3: Delete symbols (in separate transaction)
                        try {
                            ContextDatabase.transaction { innerConn ->
                                if (hasTable(innerConn, "symbols")) {
                                    log.debug("Step 3: Deleting symbols for file {}", existing.id)
                                    innerConn.createStatement().use { st ->
                                        // Delete symbols that reference this file (either directly by file_id or through chunks)
                                        st.execute("""
                                            DELETE FROM symbols
                                            WHERE file_id = ${existing.id}
                                               OR chunk_id IN (SELECT chunk_id FROM chunks WHERE file_id = ${existing.id})
                                        """)
                                    }
                                    log.debug("Step 3 complete: Deleted symbols")
                                }
                            }
                        } catch (e: SQLException) {
                            log.warn("Failed to delete symbols during artifact replace; stale symbol rows may remain: {}", e.message)
                        }

                        // Step 4: Delete chunks (in separate transaction)
                        try {
                            ContextDatabase.transaction { innerConn ->
                                log.debug("Step 4: Deleting chunks for file {}", existing.id)

                                // First, verify if there are any remaining links that would block chunk deletion
                                val checkSql = """
                                    SELECT COUNT(*) as link_count FROM links
                                    WHERE source_chunk_id IN (SELECT chunk_id FROM chunks WHERE file_id = ${existing.id})
                                       OR target_chunk_id IN (SELECT chunk_id FROM chunks WHERE file_id = ${existing.id})
                                """
                                val remainingLinks = innerConn.prepareStatement(checkSql).use { ps ->
                                    ps.executeQuery().use { rs ->
                                        if (rs.next()) rs.getInt("link_count") else 0
                                    }
                                }

                                if (remainingLinks > 0) {
                                    log.warn("Step 4 warning: {} links still reference chunks before deletion", remainingLinks)
                                }

                                innerConn.createStatement().execute("DELETE FROM chunks WHERE file_id = ${existing.id}")
                                log.debug("Step 4 complete: Successfully deleted chunks")
                            }
                        } catch (fkError: SQLException) {
                            // Unable to delete due to FK constraint - this indicates a serious data integrity issue
                            log.error("Step 4 FAILED: Unable to delete old chunks due to FK constraint: {}. This may leave orphaned chunks referencing file_id {}.", fkError.message, existing.id)
                            log.error("FK constraint details: {}", fkError)
                            // Re-throw to prevent silent failures that lead to corrupt state
                            throw fkError
                        }

                        // Step 5: Delete links by target file (in separate transaction)
                        if (existingFileId != null) {
                            try {
                                ContextDatabase.transaction { innerConn ->
                                    log.debug("Step 5: Deleting links by target file {}", existingFileId)
                                    deleteLinksByTargetFile(innerConn, existingFileId!!)
                                    log.debug("Step 5 complete: Deleted links by target file")
                                }
                            } catch (e: SQLException) {
                                log.warn("Failed to delete links by target file during artifact replace; stale link rows may remain: {}", e.message)
                            }
                        }
                    }
                }
            }

            // Phase 2: Upsert file_state AND insert all chunks atomically in ONE transaction.
            // Critical invariant: file_state.content_hash must never be committed unless the
            // matching chunks are committed too. If they were committed separately and the chunk
            // insert failed, file_state would carry the new hash with zero chunks, ChangeDetector
            // would treat the file as unchanged, and it would silently disappear from search.
            val totalChunks = chunkArtifacts.size
            if (totalChunks > 128) {
                log.info("Persisting {} chunks in a single transaction", totalChunks)
            }

            ContextDatabase.transaction { conn ->
                val persistedFile = upsertFileState(conn, fileState)

                // Assign chunk IDs on the same connection so the whole unit is atomic.
                val assigned = chunkArtifacts.map { artifact ->
                    val chunkId = nextId(conn, "chunks_seq")
                    val chunkWithIds = artifact.chunk.copy(id = chunkId, fileId = persistedFile.id)
                    chunkId to artifact.copy(chunk = chunkWithIds)
                }

                val pathToId = assigned.mapNotNull { (chunkId, artifact) ->
                    artifact.chunk.chunkPath?.let { it to chunkId }
                }.toMap()

                val withParents = assigned.map { (chunkId, artifact) ->
                    val parentPath = artifact.chunk.chunkPath?.let { path ->
                        val idx = path.lastIndexOf('/')
                        if (idx > 0) path.substring(0, idx) else null
                    }
                    val parentId = parentPath?.let { pathToId[it] }
                    val chunkWithParent = artifact.chunk.copy(parentChunkId = parentId)
                    chunkId to artifact.copy(chunk = chunkWithParent)
                }

                val chunks = withParents.map { it.second.chunk }
                val embeddingRows = withParents.flatMap { (chunkId, artifact) ->
                    artifact.embeddings.map { chunkId to it }
                }
                val linkRows = withParents.flatMap { (chunkId, artifact) ->
                    artifact.links.map { Triple(chunkId, persistedFile.id, it) }
                }

                insertChunksBatch(conn, chunks)
                val persistedEmbeddings = insertEmbeddingsBatch(conn, embeddingRows)
                val persistedLinks = insertLinksBatch(conn, linkRows)

                val embeddingsByChunk = persistedEmbeddings.groupBy { it.chunkId }
                val linksByChunk = persistedLinks.groupBy { it.sourceChunkId }
                val persistedChunks = withParents.map { (chunkId, artifact) ->
                    ChunkArtifacts(
                        chunk = artifact.chunk,
                        embeddings = embeddingsByChunk[chunkId] ?: emptyList(),
                        links = linksByChunk[chunkId] ?: emptyList()
                    )
                }

                FileArtifacts(persistedFile, persistedChunks)
            }
        }

    fun fetchFileArtifactsByPath(path: String): FileArtifacts? = ContextDatabase.withConnection { conn ->
        val file = getFileStateByPath(conn, path)
            ?: getFileStateByRelPath(conn, path)
            ?: return@withConnection null
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
                       c.chunk_path,
                       c.parent_chunk_id,
                       c.content,
                       c.summary,
                       c.created_at,
                       f.rel_path,
                       f.abs_path,
                       f.language
                FROM chunks c
                JOIN file_state f ON f.file_id = c.file_id
                WHERE 1=1
                """.trimIndent()
            )
            if (scope.paths.isNotEmpty()) {
                val placeholders = scope.paths.joinToString(",") { "?" }
                append(" AND (f.abs_path IN ($placeholders) OR f.rel_path IN ($placeholders))")
            }
            if (scope.languages.isNotEmpty()) {
                append(" AND f.language IN (" + scope.languages.joinToString(",") { "?" } + ")")
            }
            if (scope.kinds.isNotEmpty()) {
                append(" AND c.kind IN (" + scope.kinds.joinToString(",") { "?" } + ")")
            }
            if (scope.excludePatterns.isNotEmpty()) {
                scope.excludePatterns.forEach {
                    append(" AND f.abs_path NOT LIKE ?")
                }
            }
            append(" ORDER BY f.abs_path, c.ordinal")
        }
        conn.prepareStatement(sql).use { ps ->
            var idx = 1
            scope.paths.forEach { ps.setString(idx++, it) }
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
                            filePath = rs.getString("abs_path"),
                            relativePath = rs.getString("rel_path"),
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
                    chunkPath = chunk.chunkPath,
                    parentChunkId = chunk.parentChunkId,
                    metadata = buildMap {
                        put("fileId", chunk.fileId.toString())
                        put("tokens", estimatedTokens.toString())
                        chunkWithFile.relativePath?.let { put("relativePath", it) }
                        chunk.chunkPath?.let { put("chunk_path", it) }
                        chunk.parentChunkId?.let { put("parent_chunk_id", it.toString()) }
                    }
                )
            )
        }
        return accumulator
    }

    fun listAllFiles(limit: Int? = null): List<FileState> = ContextDatabase.withConnection { conn ->
        val sql = buildString {
            append("SELECT * FROM file_state ORDER BY abs_path")
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

    fun deleteFileArtifacts(absolutePath: String): Boolean {
        val state = ContextDatabase.withConnection { conn -> getFileStateByPath(conn, absolutePath) } ?: return true
        val artifacts = fetchFileArtifactsByPath(absolutePath) ?: FileArtifacts(state, emptyList())
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

    fun deleteFileArtifactsByAbsPath(absolutePath: String): Boolean {
        val state = ContextDatabase.withConnection { conn -> getFileStateByAbsPath(conn, absolutePath) } ?: return true
        val fileId = state.id
        val artifacts = fetchFileArtifactsByPath(state.absolutePath) ?: FileArtifacts(state, emptyList())
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
        val existing = if (state.id > 0) getFileStateById(conn, state.id) else getFileStateByAbsPath(conn, state.absolutePath)
        val payload = state.copy(id = existing?.id ?: state.id)
        return if (existing == null) insertFileState(conn, payload) else updateFileState(conn, payload)
    }

    private fun insertFileState(conn: Connection, state: FileState): FileState {
        val id = if (state.id > 0) state.id else nextId(conn, "file_state_seq")
        val sql = """
            INSERT INTO file_state (
                file_id, rel_path, abs_path, content_hash, size_bytes, mtime_ns,
                language, kind, fingerprint, indexed_at, is_deleted
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            var idx = 1
            ps.setLong(idx++, id)
            ps.setString(idx++, state.relativePath)
            ps.setString(idx++, state.absolutePath)
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
                abs_path = ?,
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
            ps.setString(idx++, state.absolutePath)
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


    private fun insertChunksBatch(conn: Connection, chunks: List<Chunk>) {
        if (chunks.isEmpty()) return
        val sql = """
            INSERT INTO chunks (
                chunk_id, file_id, ordinal, kind, start_line, end_line,
                token_count, chunk_path, parent_chunk_id, content, summary, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            var pending = 0
            chunks.forEach { chunk ->
                val path = chunk.chunkPath ?: chunk.summary ?: "${chunk.kind.name}:${chunk.ordinal}"
                var idx = 1
                ps.setLong(idx++, chunk.id)
                ps.setLong(idx++, chunk.fileId)
                ps.setInt(idx++, chunk.ordinal)
                ps.setString(idx++, chunk.kind.name)
                if (chunk.startLine != null) ps.setInt(idx++, chunk.startLine) else ps.setNull(idx++, java.sql.Types.INTEGER)
                if (chunk.endLine != null) ps.setInt(idx++, chunk.endLine) else ps.setNull(idx++, java.sql.Types.INTEGER)
                if (chunk.tokenEstimate != null) ps.setInt(idx++, chunk.tokenEstimate) else ps.setNull(idx++, java.sql.Types.INTEGER)
                ps.setString(idx++, path)
                if (chunk.parentChunkId != null) ps.setLong(idx++, chunk.parentChunkId) else ps.setNull(idx++, java.sql.Types.BIGINT)
                ps.setString(idx++, chunk.content)
                ps.setString(idx++, chunk.summary)
                ps.setTimestamp(idx, Timestamp.from(chunk.createdAt))
                ps.addBatch()
                pending++
                if (pending >= WRITE_BATCH_SIZE) { ps.executeBatch(); pending = 0 }
            }
            if (pending > 0) ps.executeBatch()
        }
    }

    private fun insertEmbeddingsBatch(conn: Connection, rows: List<Pair<Long, Embedding>>): List<Embedding> {
        if (rows.isEmpty()) return emptyList()
        val persisted = ArrayList<Embedding>(rows.size)
        val sql = """
            INSERT INTO embeddings (
                embedding_id, chunk_id, model, dimensions, vector, created_at
            ) VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent()
        val withIds = rows.map { (chunkId, embedding) ->
            val id = if (embedding.id > 0) embedding.id else nextId(conn, "embeddings_seq")
            Triple(id, chunkId, embedding)
        }
        conn.prepareStatement(sql).use { ps ->
            var pending = 0
            withIds.forEach { (id, chunkId, embedding) ->
                var idx = 1
                ps.setLong(idx++, id)
                ps.setLong(idx++, chunkId)
                ps.setString(idx++, embedding.model)
                ps.setInt(idx++, embedding.dimensions)
                ps.setString(idx++, serializeVector(embedding.vector))
                ps.setTimestamp(idx, Timestamp.from(embedding.createdAt))
                ps.addBatch()
                pending++
                persisted.add(embedding.copy(id = id, chunkId = chunkId))
                if (pending >= WRITE_BATCH_SIZE) { ps.executeBatch(); pending = 0 }
            }
            if (pending > 0) ps.executeBatch()
        }
        return persisted
    }

    private fun insertLinksBatch(conn: Connection, rows: List<Triple<Long, Long, Link>>): List<Link> {
        if (rows.isEmpty()) return emptyList()
        val persisted = ArrayList<Link>(rows.size)
        val sql = """
            INSERT INTO links (
                link_id, source_chunk_id, target_file_id, target_chunk_id,
                link_type, label, score, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        val withIds = rows.map { (sourceChunkId, fileId, link) ->
            val id = if (link.id > 0) link.id else nextId(conn, "links_seq")
            val targetFile = if (link.targetFileId > 0) link.targetFileId else fileId
            Pair(Pair(id, Triple(sourceChunkId, targetFile, link)), link.copy(id = id, sourceChunkId = sourceChunkId, targetFileId = targetFile))
        }
        conn.prepareStatement(sql).use { ps ->
            var pending = 0
            withIds.forEach { (meta, persistedLink) ->
                val (id, linkData) = meta
                val (sourceChunkId, targetFile, link) = linkData
                var idx = 1
                ps.setLong(idx++, id)
                ps.setLong(idx++, sourceChunkId)
                ps.setLong(idx++, targetFile)
                if (link.targetChunkId != null && link.targetChunkId > 0) ps.setLong(idx++, link.targetChunkId)
                else ps.setNull(idx++, java.sql.Types.BIGINT)
                ps.setString(idx++, link.type)
                ps.setString(idx++, link.label)
                if (link.score != null) ps.setDouble(idx++, link.score)
                else ps.setNull(idx++, java.sql.Types.DOUBLE)
                ps.setTimestamp(idx, Timestamp.from(link.createdAt))
                ps.addBatch()
                pending++
                persisted.add(persistedLink)
                if (pending >= WRITE_BATCH_SIZE) { ps.executeBatch(); pending = 0 }
            }
            if (pending > 0) ps.executeBatch()
        }
        return persisted
    }

    private fun insertChunk(conn: Connection, chunk: Chunk) {
        val sql = """
            INSERT INTO chunks (
                chunk_id, file_id, ordinal, kind, start_line, end_line,
                token_count, chunk_path, parent_chunk_id, content, summary, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
        val path = chunk.chunkPath ?: chunk.summary ?: "${chunk.kind.name}:${chunk.ordinal}"
            var idx = 1
            ps.setLong(idx++, chunk.id)
            ps.setLong(idx++, chunk.fileId)
            ps.setInt(idx++, chunk.ordinal)
            ps.setString(idx++, chunk.kind.name)
            if (chunk.startLine != null) ps.setInt(idx++, chunk.startLine) else ps.setNull(idx++, java.sql.Types.INTEGER)
            if (chunk.endLine != null) ps.setInt(idx++, chunk.endLine) else ps.setNull(idx++, java.sql.Types.INTEGER)
            if (chunk.tokenEstimate != null) ps.setInt(idx++, chunk.tokenEstimate) else ps.setNull(idx++, java.sql.Types.INTEGER)
            ps.setString(idx++, path)
            if (chunk.parentChunkId != null) ps.setLong(idx++, chunk.parentChunkId) else ps.setNull(idx++, java.sql.Types.BIGINT)
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

    private fun getFileStateByPath(conn: Connection, absolutePath: String): FileState? {
        // Use absolute path for all lookups
        val sql = "SELECT * FROM file_state WHERE abs_path = ? LIMIT 1"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, absolutePath)
            ps.executeQuery().use { rs ->
                return if (rs.next()) rs.toFileState() else null
            }
        }
    }

    private fun getFileStateByRelPath(conn: Connection, relativePath: String): FileState? {
        val sql = "SELECT * FROM file_state WHERE rel_path = ? LIMIT 1"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, relativePath)
            ps.executeQuery().use { rs ->
                return if (rs.next()) rs.toFileState() else null
            }
        }
    }

    private fun getFileStateByAbsPath(conn: Connection, absolutePath: String): FileState? {
        val sql = "SELECT * FROM file_state WHERE abs_path = ?"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, absolutePath)
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

    fun getChunksByIds(ids: List<Long>): List<ChunkWithFile> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        val sql = """
            SELECT c.*, f.rel_path, f.abs_path, f.language
            FROM chunks c
            JOIN file_state f ON f.file_id = c.file_id
            WHERE c.chunk_id IN ($placeholders)
            ORDER BY c.file_id, c.ordinal
        """.trimIndent()
        return ContextDatabase.withConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                ids.forEachIndexed { idx, id -> ps.setLong(idx + 1, id) }
                ps.executeQuery().use { rs ->
                    val results = ArrayList<ChunkWithFile>()
                    while (rs.next()) {
                        results.add(
                            ChunkWithFile(
                                chunk = rs.toChunk(),
                                filePath = rs.getString("abs_path"),
                                relativePath = rs.getString("rel_path"),
                                language = rs.getString("language")
                            )
                        )
                    }
                    results
                }
            }
        }
    }

    fun getChunksByFilePath(path: String): List<ChunkWithFile> {
        if (path.isBlank()) return emptyList()
        return ContextDatabase.withConnection { conn ->
            val fileState = getFileStateByPath(conn, path)
                ?: getFileStateByRelPath(conn, path)
                ?: getFileStateByPathSuffix(conn, path)
                ?: return@withConnection emptyList()
            getChunksByFileId(conn, fileState.id)
        }
    }

    private fun getFileStateByPathSuffix(conn: Connection, suffix: String): FileState? {
        val normalized = suffix.removePrefix("/")
        val sql = "SELECT * FROM file_state WHERE abs_path LIKE ? OR rel_path LIKE ? LIMIT 1"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, "%/$normalized")
            ps.setString(2, "%/$normalized")
            ps.executeQuery().use { rs ->
                return if (rs.next()) rs.toFileState() else null
            }
        }
    }

    data class LinkedChunk(
        val chunkId: Long,
        val sourceChunkId: Long,
        val linkType: String,
        val linkScore: Double,
        val depth: Int
    )

    /**
     * 1-hop link lookup: finds chunks directly linked to/from the given chunk IDs.
     * Returns both outbound (source→target) and inbound (target→source) links.
     */
    fun getLinkedChunkIds(chunkIds: List<Long>, defaultLinkScore: Double = 0.8): List<LinkedChunk> {
        if (chunkIds.isEmpty()) return emptyList()
        val placeholders = chunkIds.joinToString(",") { "?" }
        val sql = """
            SELECT target_chunk_id AS chunk_id, source_chunk_id, link_type,
                   COALESCE(score, ?) AS link_score, 1 AS depth
            FROM links
            WHERE source_chunk_id IN ($placeholders) AND target_chunk_id IS NOT NULL
            UNION
            SELECT source_chunk_id AS chunk_id, target_chunk_id AS source_chunk_id, link_type,
                   COALESCE(score, ?) AS link_score, 1 AS depth
            FROM links
            WHERE target_chunk_id IN ($placeholders)
        """.trimIndent()
        return ContextDatabase.withConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                ps.setDouble(idx++, defaultLinkScore)
                chunkIds.forEach { ps.setLong(idx++, it) }
                ps.setDouble(idx++, defaultLinkScore)
                chunkIds.forEach { ps.setLong(idx++, it) }
                ps.executeQuery().use { rs ->
                    val results = ArrayList<LinkedChunk>()
                    while (rs.next()) {
                        results.add(
                            LinkedChunk(
                                chunkId = rs.getLong("chunk_id"),
                                sourceChunkId = rs.getLong("source_chunk_id"),
                                linkType = rs.getString("link_type"),
                                linkScore = rs.getDouble("link_score"),
                                depth = rs.getInt("depth")
                            )
                        )
                    }
                    results
                }
            }
        }
    }

    /**
     * Multi-hop graph traversal using a recursive CTE.
     * Walks links up to [maxDepth] hops with cumulative score decay.
     * Seeds are excluded from results.
     */
    fun traverseGraph(
        seedChunkIds: List<Long>,
        maxDepth: Int,
        defaultLinkScore: Double = 0.8,
        maxResults: Int = 50
    ): List<LinkedChunk> {
        if (seedChunkIds.isEmpty() || maxDepth < 1) return emptyList()
        val seedSet = seedChunkIds.joinToString(",") { "?" }
        // DuckDB supports WITH RECURSIVE
        // Track seed_id so downstream can look up the original seed's score
        val sql = """
            WITH RECURSIVE graph_walk AS (
                -- Base case: 1-hop from seeds (outbound)
                SELECT l.target_chunk_id AS chunk_id,
                       l.source_chunk_id AS seed_id,
                       l.source_chunk_id AS prev_id,
                       l.link_type,
                       COALESCE(l.score, ?) AS link_score,
                       1 AS depth
                FROM links l
                WHERE l.source_chunk_id IN ($seedSet)
                  AND l.target_chunk_id IS NOT NULL

                UNION ALL

                -- Recursive case: follow links from discovered chunks
                SELECT l.target_chunk_id AS chunk_id,
                       gw.seed_id,
                       gw.chunk_id AS prev_id,
                       l.link_type,
                       gw.link_score * COALESCE(l.score, ?) AS link_score,
                       gw.depth + 1 AS depth
                FROM graph_walk gw
                JOIN links l ON l.source_chunk_id = gw.chunk_id
                WHERE gw.depth < ?
                  AND l.target_chunk_id IS NOT NULL
                  AND l.target_chunk_id != gw.prev_id
            )
            SELECT chunk_id, seed_id AS source_chunk_id, link_type, link_score, depth
            FROM graph_walk
            WHERE chunk_id NOT IN ($seedSet)
            ORDER BY link_score DESC
            LIMIT ?
        """.trimIndent()
        return ContextDatabase.withConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                // defaultLinkScore for base case COALESCE
                ps.setDouble(idx++, defaultLinkScore)
                // seed IDs for base case WHERE
                seedChunkIds.forEach { ps.setLong(idx++, it) }
                // defaultLinkScore for recursive case COALESCE
                ps.setDouble(idx++, defaultLinkScore)
                // maxDepth for recursive depth guard
                ps.setInt(idx++, maxDepth)
                // seed IDs for exclusion in final WHERE
                seedChunkIds.forEach { ps.setLong(idx++, it) }
                // LIMIT
                ps.setInt(idx++, maxResults)
                ps.executeQuery().use { rs ->
                    val results = ArrayList<LinkedChunk>()
                    while (rs.next()) {
                        results.add(
                            LinkedChunk(
                                chunkId = rs.getLong("chunk_id"),
                                sourceChunkId = rs.getLong("source_chunk_id"),
                                linkType = rs.getString("link_type"),
                                linkScore = rs.getDouble("link_score"),
                                depth = rs.getInt("depth")
                            )
                        )
                    }
                    results
                }
            }
        }
    }

    /**
     * Reverse multi-hop graph traversal: walks `target_chunk_id → source_chunk_id`
     * to answer "who depends on / calls / covers these chunks".
     *
     * Mirrors [traverseGraph] but follows links inbound. Optionally restricts to
     * a specific set of `link_type` values (e.g. {"CALLS","DEPENDS_ON","MODIFIES"}
     * for impact radius, {"COVERS"} for test coverage).
     *
     * Seeds are excluded from results.
     */
    fun traverseGraphReverse(
        seedChunkIds: List<Long>,
        maxDepth: Int,
        defaultLinkScore: Double = 0.8,
        maxResults: Int = 50,
        linkTypes: Set<String>? = null
    ): List<LinkedChunk> {
        if (seedChunkIds.isEmpty() || maxDepth < 1) return emptyList()
        val seedSet = seedChunkIds.joinToString(",") { "?" }
        val typeFilterBase: String
        val typeFilterRec: String
        if (linkTypes.isNullOrEmpty()) {
            typeFilterBase = ""
            typeFilterRec = ""
        } else {
            val typePlaceholders = linkTypes.joinToString(",") { "?" }
            typeFilterBase = " AND l.link_type IN ($typePlaceholders)"
            typeFilterRec = " AND l.link_type IN ($typePlaceholders)"
        }
        val sql = """
            WITH RECURSIVE graph_walk AS (
                -- Base case: 1-hop inbound from seeds
                SELECT l.source_chunk_id AS chunk_id,
                       l.target_chunk_id AS seed_id,
                       l.target_chunk_id AS prev_id,
                       l.link_type,
                       COALESCE(l.score, ?) AS link_score,
                       1 AS depth
                FROM links l
                WHERE l.target_chunk_id IN ($seedSet)
                  AND l.source_chunk_id IS NOT NULL$typeFilterBase

                UNION ALL

                -- Recursive case: keep following inbound links
                SELECT l.source_chunk_id AS chunk_id,
                       gw.seed_id,
                       gw.chunk_id AS prev_id,
                       l.link_type,
                       gw.link_score * COALESCE(l.score, ?) AS link_score,
                       gw.depth + 1 AS depth
                FROM graph_walk gw
                JOIN links l ON l.target_chunk_id = gw.chunk_id
                WHERE gw.depth < ?
                  AND l.source_chunk_id IS NOT NULL
                  AND l.source_chunk_id != gw.prev_id$typeFilterRec
            )
            SELECT chunk_id, seed_id AS source_chunk_id, link_type, link_score, depth
            FROM graph_walk
            WHERE chunk_id NOT IN ($seedSet)
            ORDER BY link_score DESC
            LIMIT ?
        """.trimIndent()
        return ContextDatabase.withConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                ps.setDouble(idx++, defaultLinkScore)
                seedChunkIds.forEach { ps.setLong(idx++, it) }
                if (!linkTypes.isNullOrEmpty()) {
                    linkTypes.forEach { ps.setString(idx++, it) }
                }
                ps.setDouble(idx++, defaultLinkScore)
                ps.setInt(idx++, maxDepth)
                if (!linkTypes.isNullOrEmpty()) {
                    linkTypes.forEach { ps.setString(idx++, it) }
                }
                seedChunkIds.forEach { ps.setLong(idx++, it) }
                ps.setInt(idx++, maxResults)
                ps.executeQuery().use { rs ->
                    val results = ArrayList<LinkedChunk>()
                    while (rs.next()) {
                        results.add(
                            LinkedChunk(
                                chunkId = rs.getLong("chunk_id"),
                                sourceChunkId = rs.getLong("source_chunk_id"),
                                linkType = rs.getString("link_type"),
                                linkScore = rs.getDouble("link_score"),
                                depth = rs.getInt("depth")
                            )
                        )
                    }
                    results
                }
            }
        }
    }

    private fun getChunksByFileId(conn: Connection, fileId: Long): List<ChunkWithFile> {
        val sql = """
            SELECT c.*, f.rel_path, f.abs_path, f.language
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
                            filePath = rs.getString("abs_path"),
                            relativePath = rs.getString("rel_path"),
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
            // Don't throw - best-effort cleanup; the caller proceeds with verification below.
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
            absolutePath = getString("abs_path"),
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
            createdAt = getTimestamp("created_at").toInstant(),
            chunkPath = getString("chunk_path"),
            parentChunkId = getNullableLong("parent_chunk_id")
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

    private const val WRITE_BATCH_SIZE = 256
}
