package com.orchestrator.context.storage

import com.orchestrator.context.domain.Embedding
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/**
 * JDBC repository for embedding vectors stored in DuckDB.
 */
object EmbeddingRepository {

    fun insert(embedding: Embedding): Embedding = ContextDatabase.transaction { conn ->
        val id = if (embedding.id > 0) embedding.id else nextId(conn)
        conn.prepareStatement(
            """
            INSERT INTO embeddings (
                embedding_id, chunk_id, model, dimensions, vector, created_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            bindEmbedding(ps, id, embedding)
            ps.executeUpdate()
        }
        embedding.copy(id = id)
    }

    fun insertBatch(embeddings: List<Embedding>): List<Embedding> {
        if (embeddings.isEmpty()) return emptyList()
        return ContextDatabase.transaction { conn ->
            val enriched = embeddings.map { embedding ->
                val id = if (embedding.id > 0) embedding.id else nextId(conn)
                embedding.copy(id = id)
            }
            conn.prepareStatement(
                """
                INSERT INTO embeddings (
                    embedding_id, chunk_id, model, dimensions, vector, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                enriched.forEach { embedding ->
                    bindEmbedding(ps, embedding.id, embedding)
                    ps.executeUpdate()
                }
            }
            enriched
        }
    }

    fun findByChunkId(chunkId: Long, model: String): Embedding? = ContextDatabase.withConnection { conn ->
        conn.prepareStatement("SELECT * FROM embeddings WHERE chunk_id = ? AND model = ?").use { ps ->
            ps.setLong(1, chunkId)
            ps.setString(2, model)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toEmbedding() else null }
        }
    }

    fun findByChunkIds(chunkIds: List<Long>, model: String): List<Embedding> {
        if (chunkIds.isEmpty()) return emptyList()
        val placeholders = chunkIds.joinToString(",") { "?" }
        val sql = "SELECT * FROM embeddings WHERE model = ? AND chunk_id IN ($placeholders) ORDER BY chunk_id"
        return ContextDatabase.withConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, model)
                chunkIds.forEachIndexed { index, chunkId -> ps.setLong(index + 2, chunkId) }
                ps.executeQuery().use { rs -> rs.collectEmbeddings() }
            }
        }
    }

    fun deleteByChunkId(chunkId: Long) {
        ContextDatabase.transaction { conn ->
            conn.prepareStatement("DELETE FROM embeddings WHERE chunk_id = ?").use { ps ->
                ps.setLong(1, chunkId)
                ps.executeUpdate()
            }
        }
    }

    private fun bindEmbedding(ps: PreparedStatement, id: Long, embedding: Embedding) {
        var idx = 1
        ps.setLong(idx++, id)
        ps.setLong(idx++, embedding.chunkId)
        ps.setString(idx++, embedding.model)
        ps.setInt(idx++, embedding.dimensions)
        ps.setString(idx++, serializeVector(embedding.vector))
        ps.setTimestamp(idx, Timestamp.from(embedding.createdAt))
    }

    private fun ResultSet.collectEmbeddings(): List<Embedding> {
        val results = mutableListOf<Embedding>()
        while (next()) {
            results.add(toEmbedding())
        }
        return results
    }

    private fun ResultSet.toEmbedding(): Embedding = Embedding(
        id = getLong("embedding_id"),
        chunkId = getLong("chunk_id"),
        model = getString("model"),
        dimensions = getInt("dimensions"),
        vector = deserializeVector(getString("vector")),
        createdAt = getTimestamp("created_at").toInstant()
    )

    private fun nextId(conn: java.sql.Connection): Long {
        conn.prepareStatement("SELECT nextval('embeddings_seq')").use { ps ->
            ps.executeQuery().use { rs ->
                rs.next()
                return rs.getLong(1)
            }
        }
    }

    private fun serializeVector(vector: List<Float>): String =
        vector.joinToString(prefix = "[", postfix = "]") { it.toString() }

    private fun deserializeVector(text: String): List<Float> {
        val trimmed = text.trim()
        if (trimmed.length <= 2) return emptyList()
        return trimmed.removePrefix("[").removeSuffix("]")
            .split(',')
            .mapNotNull { token -> token.trim().takeIf { it.isNotEmpty() }?.toFloat() }
    }
}
