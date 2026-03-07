package com.orchestrator.context.indexing

import com.orchestrator.context.domain.BlameInfo
import com.orchestrator.context.domain.ChunkKind
import com.orchestrator.context.domain.CommitInfo
import com.orchestrator.context.providers.GitHistoryAnalyzer
import com.orchestrator.context.storage.ContextDatabase
import com.orchestrator.utils.Logger
import java.nio.file.Path
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import kotlin.math.max

/**
 * Creates intent edges from commit-message chunks to code chunks.
 *
 * The resulting graph allows "why was this changed?" exploration where commit
 * intent is connected to the affected implementation chunks.
 */
class GitIntentLinkBuilder(
    private val analyzer: GitHistoryAnalyzer = GitHistoryAnalyzer(),
    private val maxCommitsPerFile: Int = 6,
    private val maxTargetsPerCommit: Int = 8
) {
    private val log = Logger.logger("com.orchestrator.context.indexing.GitIntentLinkBuilder")

    fun rebuildForFile(fileId: Long) {
        if (fileId <= 0) return

        runCatching {
            val fileInfo = loadFileInfo(fileId) ?: return
            if (fileInfo.absolutePath.startsWith(VIRTUAL_GIT_PREFIX)) return

            val filePath = Path.of(fileInfo.absolutePath)
            val commits = analyzer.getRecentCommits(filePath, maxCommitsPerFile)
            if (commits.isEmpty()) {
                ContextDatabase.transaction { conn -> deleteExistingGitLinksForTargetFile(conn, fileId) }
                return
            }

            val blame = analyzer.getBlame(filePath)
            val chunks = loadSourceChunks(fileId)
            if (chunks.isEmpty()) return

            val targetsByCommit = commits.associate { commit ->
                commit.hash to resolveTargetChunks(commit, chunks, blame)
            }

            ContextDatabase.transaction { conn ->
                deleteExistingGitLinksForTargetFile(conn, fileId)

                commits.forEachIndexed { index, commit ->
                    val targets = targetsByCommit[commit.hash].orEmpty().take(maxTargetsPerCommit)
                    if (targets.isEmpty()) return@forEachIndexed

                    val commitChunkId = upsertCommitChunk(conn, commit, fileInfo.relativePath ?: fileInfo.absolutePath)
                    val score = recencyScore(index, commits.size)
                    insertCommitLinks(
                        conn = conn,
                        commitChunkId = commitChunkId,
                        targetFileId = fileId,
                        targetChunkIds = targets,
                        label = commit.shortMessage,
                        score = score
                    )
                }
            }
        }.onFailure { e ->
            log.warn("Failed to rebuild git intent links for file_id={}: {}", fileId, e.message)
        }
    }

    private fun loadFileInfo(fileId: Long): FileInfo? = ContextDatabase.withConnection { conn ->
        conn.prepareStatement(
            """
            SELECT file_id, rel_path, abs_path
            FROM file_state
            WHERE file_id = ? AND is_deleted = FALSE
            LIMIT 1
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, fileId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return@use null
                FileInfo(
                    fileId = rs.getLong("file_id"),
                    relativePath = rs.getString("rel_path"),
                    absolutePath = rs.getString("abs_path")
                )
            }
        }
    }

    private fun loadSourceChunks(fileId: Long): List<SourceChunk> = ContextDatabase.withConnection { conn ->
        conn.prepareStatement(
            """
            SELECT chunk_id, ordinal, kind, start_line, end_line
            FROM chunks
            WHERE file_id = ? AND kind LIKE 'CODE_%'
            ORDER BY ordinal
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, fileId)
            ps.executeQuery().use { rs ->
                val chunks = mutableListOf<SourceChunk>()
                while (rs.next()) {
                    chunks += SourceChunk(
                        chunkId = rs.getLong("chunk_id"),
                        ordinal = rs.getInt("ordinal"),
                        kind = rs.getString("kind")?.trim().orEmpty(),
                        startLine = rs.getInt("start_line").takeIf { !rs.wasNull() },
                        endLine = rs.getInt("end_line").takeIf { !rs.wasNull() }
                    )
                }
                chunks
            }
        }
    }

    private fun resolveTargetChunks(
        commit: CommitInfo,
        chunks: List<SourceChunk>,
        blame: Map<Int, BlameInfo>
    ): List<Long> {
        if (chunks.isEmpty()) return emptyList()

        val linesForCommit = blame
            .filterValues { info -> info.commit.hash == commit.hash }
            .keys
            .toSet()

        if (linesForCommit.isNotEmpty()) {
            val blamedChunks = chunks.filter { chunk ->
                val start = chunk.startLine ?: return@filter false
                val end = chunk.endLine ?: return@filter false
                linesForCommit.any { it in start..end }
            }
            if (blamedChunks.isNotEmpty()) {
                return blamedChunks
                    .sortedBy { it.ordinal }
                    .map { it.chunkId }
                    .distinct()
            }
        }

        // Fallback: no direct blame intersection (e.g., merge commits), use core structural chunks.
        return chunks
            .sortedWith(
                compareBy<SourceChunk> { fallbackKindPriority(it.kind) }
                    .thenBy { it.ordinal }
            )
            .take(maxTargetsPerCommit)
            .map { it.chunkId }
            .distinct()
    }

    private fun fallbackKindPriority(kind: String): Int = when (kind) {
        "CODE_FUNCTION", "CODE_METHOD", "CODE_CONSTRUCTOR" -> 0
        "CODE_CLASS", "CODE_INTERFACE", "CODE_ENUM" -> 1
        else -> 2
    }

    private fun deleteExistingGitLinksForTargetFile(conn: Connection, fileId: Long) {
        conn.prepareStatement(
            """
            DELETE FROM links
            WHERE target_file_id = ?
              AND link_type = ?
              AND source_chunk_id IN (
                SELECT chunk_id
                FROM chunks
                WHERE kind = ?
              )
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, fileId)
            ps.setString(2, LINK_TYPE_MODIFIES)
            ps.setString(3, ChunkKind.COMMIT_MESSAGE.name)
            ps.executeUpdate()
        }
    }

    private fun upsertCommitChunk(conn: Connection, commit: CommitInfo, targetPathHint: String): Long {
        val content = buildCommitChunkContent(commit, targetPathHint)
        val tokenEstimate = max(1, content.length / 4)
        val now = Instant.now()
        val virtualPath = "$VIRTUAL_GIT_PREFIX${commit.hash}"
        val commitFileId = upsertVirtualCommitFile(conn, virtualPath, commit, tokenEstimate, now)

        val existingChunkId = conn.prepareStatement(
            """
            SELECT chunk_id
            FROM chunks
            WHERE file_id = ? AND kind = ?
            ORDER BY ordinal
            LIMIT 1
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, commitFileId)
            ps.setString(2, ChunkKind.COMMIT_MESSAGE.name)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getLong("chunk_id") else null
            }
        }

        val chunkPath = "git/commit/${commit.hash}"

        return if (existingChunkId != null) {
            conn.prepareStatement(
                """
                UPDATE chunks
                SET token_count = ?, chunk_path = ?, parent_chunk_id = NULL,
                    content = ?, summary = ?, created_at = ?
                WHERE chunk_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, tokenEstimate)
                ps.setString(2, chunkPath)
                ps.setString(3, content)
                ps.setString(4, commit.shortMessage)
                ps.setTimestamp(5, Timestamp.from(now))
                ps.setLong(6, existingChunkId)
                ps.executeUpdate()
            }
            existingChunkId
        } else {
            val chunkId = nextId(conn, "chunks_seq")
            conn.prepareStatement(
                """
                INSERT INTO chunks (
                    chunk_id, file_id, ordinal, kind, start_line, end_line, token_count,
                    chunk_path, parent_chunk_id, content, summary, created_at
                ) VALUES (?, ?, ?, ?, NULL, NULL, ?, ?, NULL, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, chunkId)
                ps.setLong(2, commitFileId)
                ps.setInt(3, 0)
                ps.setString(4, ChunkKind.COMMIT_MESSAGE.name)
                ps.setInt(5, tokenEstimate)
                ps.setString(6, chunkPath)
                ps.setString(7, content)
                ps.setString(8, commit.shortMessage)
                ps.setTimestamp(9, Timestamp.from(now))
                ps.executeUpdate()
            }
            chunkId
        }
    }

    private fun upsertVirtualCommitFile(
        conn: Connection,
        virtualPath: String,
        commit: CommitInfo,
        sizeBytes: Int,
        now: Instant
    ): Long {
        val existingId = conn.prepareStatement(
            "SELECT file_id FROM file_state WHERE abs_path = ? LIMIT 1"
        ).use { ps ->
            ps.setString(1, virtualPath)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getLong("file_id") else null
            }
        }

        val modifiedNs = commit.timestamp.epochSecond * 1_000_000_000L
        return if (existingId != null) {
            conn.prepareStatement(
                """
                UPDATE file_state
                SET rel_path = ?, content_hash = ?, size_bytes = ?, mtime_ns = ?,
                    language = ?, kind = ?, fingerprint = NULL, indexed_at = ?, is_deleted = FALSE
                WHERE file_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, virtualPath)
                ps.setString(2, commit.hash)
                ps.setLong(3, sizeBytes.toLong())
                ps.setLong(4, modifiedNs)
                ps.setString(5, "git")
                ps.setString(6, "commit")
                ps.setTimestamp(7, Timestamp.from(now))
                ps.setLong(8, existingId)
                ps.executeUpdate()
            }
            existingId
        } else {
            val fileId = nextId(conn, "file_state_seq")
            conn.prepareStatement(
                """
                INSERT INTO file_state (
                    file_id, rel_path, abs_path, content_hash, size_bytes, mtime_ns,
                    language, kind, fingerprint, indexed_at, is_deleted
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, FALSE)
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, fileId)
                ps.setString(2, virtualPath)
                ps.setString(3, virtualPath)
                ps.setString(4, commit.hash)
                ps.setLong(5, sizeBytes.toLong())
                ps.setLong(6, modifiedNs)
                ps.setString(7, "git")
                ps.setString(8, "commit")
                ps.setTimestamp(9, Timestamp.from(now))
                ps.executeUpdate()
            }
            fileId
        }
    }

    private fun insertCommitLinks(
        conn: Connection,
        commitChunkId: Long,
        targetFileId: Long,
        targetChunkIds: List<Long>,
        label: String?,
        score: Double
    ) {
        if (targetChunkIds.isEmpty()) return
        val sql = """
            INSERT INTO links (
                link_id, source_chunk_id, target_file_id, target_chunk_id,
                link_type, label, score, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        val now = Timestamp.from(Instant.now())
        conn.prepareStatement(sql).use { ps ->
            targetChunkIds.distinct().forEach { targetChunkId ->
                var idx = 1
                ps.setLong(idx++, nextId(conn, "links_seq"))
                ps.setLong(idx++, commitChunkId)
                ps.setLong(idx++, targetFileId)
                ps.setLong(idx++, targetChunkId)
                ps.setString(idx++, LINK_TYPE_MODIFIES)
                ps.setString(idx++, label)
                ps.setDouble(idx++, score)
                ps.setTimestamp(idx, now)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    private fun buildCommitChunkContent(commit: CommitInfo, targetPathHint: String): String = buildString {
        appendLine("Commit ${commit.shortHash}")
        appendLine("Hash: ${commit.hash}")
        appendLine("Author: ${commit.author.name} <${commit.author.email}>")
        appendLine("Date: ${commit.timestamp}")
        appendLine("Target: $targetPathHint")
        appendLine()
        appendLine("Summary:")
        appendLine(commit.shortMessage)
        appendLine()
        appendLine("Message:")
        appendLine(commit.message)
        if (commit.filesChanged.isNotEmpty()) {
            appendLine()
            appendLine("Files changed:")
            commit.filesChanged.take(20).forEach { appendLine("- $it") }
        }
        if (commit.additions > 0 || commit.deletions > 0) {
            appendLine()
            appendLine("Diff stats: +${commit.additions} / -${commit.deletions}")
        }
    }.trim()

    private fun recencyScore(index: Int, total: Int): Double {
        if (total <= 1) return 1.0
        val ratio = index.toDouble() / total.toDouble()
        return (1.0 - ratio * 0.45).coerceIn(0.55, 1.0)
    }

    private fun nextId(conn: Connection, sequence: String): Long =
        conn.prepareStatement("SELECT nextval('$sequence')").use { ps ->
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    private data class FileInfo(
        val fileId: Long,
        val relativePath: String?,
        val absolutePath: String
    )

    private data class SourceChunk(
        val chunkId: Long,
        val ordinal: Int,
        val kind: String,
        val startLine: Int?,
        val endLine: Int?
    )

    companion object {
        private const val LINK_TYPE_MODIFIES = "MODIFIES"
        private const val VIRTUAL_GIT_PREFIX = "git://commit/"
    }
}
