package com.orchestrator.context.storage

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/**
 * JDBC repository for CRUD and lookup operations on the `chunks` table.
 */
object ChunkRepository {

    fun insert(chunk: Chunk): Chunk = ContextDatabase.transaction { conn ->
        val id = if (chunk.id > 0) chunk.id else nextId(conn)
        conn.prepareStatement(
            """
            INSERT INTO chunks (
                chunk_id, file_id, ordinal, kind, start_line, end_line,
                token_count, content, summary, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            bindChunk(ps, id, chunk)
            ps.executeUpdate()
        }
        chunk.copy(id = id)
    }

    fun insertBatch(chunks: List<Chunk>): List<Chunk> {
        if (chunks.isEmpty()) return emptyList()
        return ContextDatabase.transaction { conn ->
            val enriched = chunks.map { chunk ->
                val id = if (chunk.id > 0) chunk.id else nextId(conn)
                chunk.copy(id = id)
            }
            conn.prepareStatement(
                """
                INSERT INTO chunks (
                    chunk_id, file_id, ordinal, kind, start_line, end_line,
                    token_count, content, summary, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                enriched.forEach { chunk ->
                    bindChunk(ps, chunk.id, chunk)
                    ps.executeUpdate()
                }
            }
            enriched
        }
    }

    fun findById(id: Long): Chunk? = ContextDatabase.withConnection { conn ->
        conn.prepareStatement("SELECT * FROM chunks WHERE chunk_id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toChunk() else null }
        }
    }

    fun findByFileId(fileId: Long): List<Chunk> = ContextDatabase.withConnection { conn ->
        conn.prepareStatement("SELECT * FROM chunks WHERE file_id = ? ORDER BY ordinal").use { ps ->
            ps.setLong(1, fileId)
            ps.executeQuery().use { rs -> rs.collectChunks() }
        }
    }

    fun findByKind(kind: ChunkKind): List<Chunk> = ContextDatabase.withConnection { conn ->
        conn.prepareStatement("SELECT * FROM chunks WHERE kind = ? ORDER BY file_id, ordinal").use { ps ->
            ps.setString(1, kind.name)
            ps.executeQuery().use { rs -> rs.collectChunks() }
        }
    }

    fun findByLabel(label: String): List<Chunk> = ContextDatabase.withConnection { conn ->
        conn.prepareStatement("SELECT * FROM chunks WHERE summary = ? ORDER BY file_id, ordinal").use { ps ->
            ps.setString(1, label)
            ps.executeQuery().use { rs -> rs.collectChunks() }
        }
    }

    fun deleteByFileId(fileId: Long) {
        ContextDatabase.transaction { conn ->
            conn.prepareStatement("DELETE FROM chunks WHERE file_id = ?").use { ps ->
                ps.setLong(1, fileId)
                ps.executeUpdate()
            }
        }
    }

    fun count(): Long = ContextDatabase.withConnection { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM chunks").use { ps ->
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

    private fun bindChunk(ps: PreparedStatement, id: Long, chunk: Chunk) {
        var idx = 1
        ps.setLong(idx++, id)
        ps.setLong(idx++, chunk.fileId)
        ps.setInt(idx++, chunk.ordinal)
        ps.setString(idx++, chunk.kind.name)
        setNullableInt(ps, idx++, chunk.startLine)
        setNullableInt(ps, idx++, chunk.endLine)
        setNullableInt(ps, idx++, chunk.tokenEstimate)
        ps.setString(idx++, chunk.content)
        setNullableString(ps, idx++, chunk.summary)
        ps.setTimestamp(idx, Timestamp.from(chunk.createdAt))
    }

    private fun setNullableInt(ps: PreparedStatement, index: Int, value: Int?) {
        if (value != null) ps.setInt(index, value) else ps.setNull(index, java.sql.Types.INTEGER)
    }

    private fun setNullableString(ps: PreparedStatement, index: Int, value: String?) {
        if (value != null) ps.setString(index, value) else ps.setNull(index, java.sql.Types.VARCHAR)
    }

    private fun ResultSet.collectChunks(): List<Chunk> {
        val results = mutableListOf<Chunk>()
        while (next()) {
            results.add(toChunk())
        }
        return results
    }

    private fun ResultSet.toChunk(): Chunk = Chunk(
        id = getLong("chunk_id"),
        fileId = getLong("file_id"),
        ordinal = getInt("ordinal"),
        kind = ChunkKind.valueOf(getString("kind")),
        startLine = getNullableInt("start_line"),
        endLine = getNullableInt("end_line"),
        tokenEstimate = getNullableInt("token_count"),
        content = getString("content"),
        summary = getString("summary"),
        createdAt = getTimestamp("created_at").toInstant()
    )

    private fun ResultSet.getNullableInt(column: String): Int? =
        getObject(column)?.let { (it as Number).toInt() }

    private fun nextId(conn: java.sql.Connection): Long {
        conn.prepareStatement("SELECT nextval('chunks_seq')").use { ps ->
            ps.executeQuery().use { rs ->
                rs.next()
                return rs.getLong(1)
            }
        }
    }
}
