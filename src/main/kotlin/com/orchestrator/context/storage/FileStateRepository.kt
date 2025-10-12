package com.orchestrator.context.storage

import com.orchestrator.context.domain.FileState
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/**
 * JDBC repository for the `file_state` table.
 */
object FileStateRepository {

    fun insert(fileState: FileState): FileState = ContextDatabase.transaction { conn ->
        val id = if (fileState.id > 0) fileState.id else nextId(conn)
        val sql = """
            INSERT INTO file_state (
                file_id, rel_path, content_hash, size_bytes, mtime_ns,
                language, kind, fingerprint, indexed_at, is_deleted
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            var idx = 1
            ps.setLong(idx++, id)
            ps.setString(idx++, fileState.relativePath)
            ps.setString(idx++, fileState.contentHash)
            ps.setLong(idx++, fileState.sizeBytes)
            ps.setLong(idx++, fileState.modifiedTimeNs)
            setNullableString(ps, idx++, fileState.language)
            setNullableString(ps, idx++, fileState.kind)
            setNullableString(ps, idx++, fileState.fingerprint)
            ps.setTimestamp(idx++, Timestamp.from(fileState.indexedAt))
            ps.setBoolean(idx, fileState.isDeleted)
            ps.executeUpdate()
        }
        fileState.copy(id = id)
    }

    fun findById(id: Long): FileState? = ContextDatabase.withConnection { conn ->
        conn.prepareStatement("SELECT * FROM file_state WHERE file_id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.toFileState() else null
            }
        }
    }

    fun findByPath(relPath: String): FileState? = ContextDatabase.withConnection { conn ->
        conn.prepareStatement("SELECT * FROM file_state WHERE rel_path = ?").use { ps ->
            ps.setString(1, relPath)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.toFileState() else null
            }
        }
    }

    fun findAll(limit: Int = Int.MAX_VALUE): List<FileState> = ContextDatabase.withConnection { conn ->
        val sql = if (limit == Int.MAX_VALUE) "SELECT * FROM file_state ORDER BY rel_path" else "SELECT * FROM file_state ORDER BY rel_path LIMIT ?"
        conn.prepareStatement(sql).use { ps ->
            if (limit != Int.MAX_VALUE) ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                val results = mutableListOf<FileState>()
                while (rs.next()) {
                    results.add(rs.toFileState())
                }
                results
            }
        }
    }

    fun update(fileState: FileState) {
        require(fileState.id > 0) { "Cannot update FileState without an id" }
        ContextDatabase.transaction { conn ->
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
                ps.setString(idx++, fileState.relativePath)
                ps.setString(idx++, fileState.contentHash)
                ps.setLong(idx++, fileState.sizeBytes)
                ps.setLong(idx++, fileState.modifiedTimeNs)
                setNullableString(ps, idx++, fileState.language)
                setNullableString(ps, idx++, fileState.kind)
                setNullableString(ps, idx++, fileState.fingerprint)
                ps.setTimestamp(idx++, Timestamp.from(fileState.indexedAt))
                ps.setBoolean(idx++, fileState.isDeleted)
                ps.setLong(idx, fileState.id)
                ps.executeUpdate()
            }
        }
    }

    fun delete(id: Long) {
        ContextDatabase.transaction { conn ->
            conn.prepareStatement("DELETE FROM file_state WHERE file_id = ?").use { ps ->
                ps.setLong(1, id)
                ps.executeUpdate()
            }
        }
    }

    fun deleteByPath(relPath: String) {
        ContextDatabase.transaction { conn ->
            conn.prepareStatement("DELETE FROM file_state WHERE rel_path = ?").use { ps ->
                ps.setString(1, relPath)
                ps.executeUpdate()
            }
        }
    }

    fun findModifiedSince(timestamp: Instant): List<FileState> = ContextDatabase.withConnection { conn ->
        val sql = "SELECT * FROM file_state WHERE indexed_at >= ? ORDER BY indexed_at"
        conn.prepareStatement(sql).use { ps ->
            ps.setTimestamp(1, Timestamp.from(timestamp))
            ps.executeQuery().use { rs ->
                val results = mutableListOf<FileState>()
                while (rs.next()) {
                    results.add(rs.toFileState())
                }
                results
            }
        }
    }

    fun findByLanguage(lang: String): List<FileState> = ContextDatabase.withConnection { conn ->
        val sql = "SELECT * FROM file_state WHERE language = ? ORDER BY rel_path"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, lang)
            ps.executeQuery().use { rs ->
                val results = mutableListOf<FileState>()
                while (rs.next()) {
                    results.add(rs.toFileState())
                }
                results
            }
        }
    }

    fun count(): Long = ContextDatabase.withConnection { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM file_state").use { ps ->
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

    private fun nextId(conn: Connection): Long {
        conn.prepareStatement("SELECT nextval('file_state_seq')").use { ps ->
            ps.executeQuery().use { rs ->
                rs.next()
                return rs.getLong(1)
            }
        }
    }

    private fun ResultSet.toFileState(): FileState = FileState(
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

    private fun setNullableString(ps: java.sql.PreparedStatement, index: Int, value: String?) {
        if (value != null) ps.setString(index, value) else ps.setNull(index, java.sql.Types.VARCHAR)
    }
}
