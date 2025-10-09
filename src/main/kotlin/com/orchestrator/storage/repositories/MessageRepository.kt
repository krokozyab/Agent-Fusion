package com.orchestrator.storage.repositories

import com.orchestrator.domain.AgentId
import com.orchestrator.domain.TaskId
import com.orchestrator.storage.Database
import java.sql.Timestamp
import java.time.Instant

/**
 * Repository for conversation_messages table.
 */
object MessageRepository {
    data class MessageRow(
        val id: Long,
        val taskId: TaskId,
        val role: String,
        val agentId: AgentId?,
        val content: String,
        val tokens: Int,
        val ts: Instant,
        val metadataJson: String?
    )

    fun insert(
        taskId: TaskId,
        role: String,
        content: String,
        tokens: Int,
        agentId: AgentId? = null,
        metadataJson: String? = null,
        ts: Instant = Instant.now()
    ): Long = Database.withConnection { conn ->
        val newId = generateId()
        conn.prepareStatement(
            """
            INSERT INTO conversation_messages (id, task_id, role, agent_id, content, tokens, ts, metadata)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, newId)
            ps.setString(2, taskId.value)
            ps.setString(3, role)
            if (agentId != null) ps.setString(4, agentId.value) else ps.setNull(4, java.sql.Types.VARCHAR)
            ps.setString(5, content)
            ps.setInt(6, tokens)
            ps.setTimestamp(7, Timestamp.from(ts))
            if (metadataJson != null) ps.setString(8, metadataJson) else ps.setNull(8, java.sql.Types.VARCHAR)
            ps.executeUpdate()
        }
        newId
    }

    fun listByTask(taskId: TaskId): List<MessageRow> = Database.withConnection { conn ->
        conn.prepareStatement(
            """
            SELECT id, task_id, role, agent_id, content, tokens, ts, metadata
            FROM conversation_messages
            WHERE task_id = ?
            ORDER BY ts ASC, id ASC
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, taskId.value)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<MessageRow>()
                while (rs.next()) {
                    out += MessageRow(
                        id = rs.getLong("id"),
                        taskId = TaskId(rs.getString("task_id")),
                        role = rs.getString("role"),
                        agentId = rs.getString("agent_id")?.let { AgentId(it) },
                        content = rs.getString("content"),
                        tokens = rs.getInt("tokens"),
                        ts = rs.getTimestamp("ts").toInstant(),
                        metadataJson = rs.getString("metadata")
                    )
                }
                out
            }
        }
    }

    fun countTokens(taskId: TaskId): Int = Database.withConnection { conn ->
        conn.prepareStatement(
            """
            SELECT COALESCE(SUM(tokens), 0) AS total_tokens
            FROM conversation_messages WHERE task_id = ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, taskId.value)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getInt("total_tokens") else 0
            }
        }
    }

    /** Delete oldest messages until total tokens <= maxTokens. Returns number of rows deleted. */
    fun deleteOldestBeyondTokens(taskId: TaskId, maxTokens: Int): Int = Database.withConnection { conn ->
        // Fetch ids ordered by oldest first with cumulative sum until under threshold
        val rows = mutableListOf<Pair<Long, Int>>()
        conn.prepareStatement(
            """
            SELECT id, tokens FROM conversation_messages
            WHERE task_id = ?
            ORDER BY ts ASC, id ASC
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, taskId.value)
            ps.executeQuery().use { rs ->
                while (rs.next()) rows += rs.getLong("id") to rs.getInt("tokens")
            }
        }
        var total = rows.sumOf { it.second }
        if (total <= maxTokens) return@withConnection 0
        val toDelete = mutableListOf<Long>()
        for ((id, tok) in rows) {
            if (total <= maxTokens) break
            toDelete += id
            total -= tok
        }
        if (toDelete.isEmpty()) return@withConnection 0
        // Delete in batch
        val placeholders = toDelete.joinToString(",") { "?" }
        conn.prepareStatement("DELETE FROM conversation_messages WHERE id IN ($placeholders)").use { ps ->
            toDelete.forEachIndexed { idx, id -> ps.setLong(idx + 1, id) }
            ps.executeUpdate()
        }
        toDelete.size
    }

    private fun generateId(): Long {
        val base = System.currentTimeMillis() * 1000
        val rand = (Math.random() * 1000).toLong()
        return base + rand
    }
}
