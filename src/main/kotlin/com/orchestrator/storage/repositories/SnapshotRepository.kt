package com.orchestrator.storage.repositories

import com.orchestrator.domain.DecisionId
import com.orchestrator.domain.TaskId
import com.orchestrator.storage.Database
import java.sql.Timestamp
import java.time.Instant

/**
 * Repository for context_snapshots table.
 * Stores a compressed JSON envelope string inside the JSON column.
 */
object SnapshotRepository {
    data class SnapshotRow(
        val id: Long,
        val taskId: TaskId?,
        val decisionId: DecisionId?,
        val label: String?,
        val snapshotJson: String,
        val createdAt: Instant
    )

    fun insert(
        taskId: TaskId?,
        decisionId: DecisionId?,
        label: String?,
        snapshotJson: String,
        createdAt: Instant = Instant.now()
    ): Long = Database.withConnection { conn ->
        val newId = generateId()
        conn.prepareStatement(
            """
            INSERT INTO context_snapshots (id, task_id, decision_id, label, snapshot, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, newId)
            if (taskId != null) ps.setString(2, taskId.value) else ps.setNull(2, java.sql.Types.VARCHAR)
            if (decisionId != null) ps.setString(3, decisionId.value) else ps.setNull(3, java.sql.Types.VARCHAR)
            if (label != null) ps.setString(4, label) else ps.setNull(4, java.sql.Types.VARCHAR)
            ps.setString(5, snapshotJson) // DuckDB JSON accepts text
            ps.setTimestamp(6, Timestamp.from(createdAt))
            ps.executeUpdate()
        }
        newId
    }

    fun findById(id: Long): SnapshotRow? = Database.withConnection { conn ->
        conn.prepareStatement(
            """
            SELECT id, task_id, decision_id, label, snapshot, created_at
            FROM context_snapshots WHERE id = ?
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return@use null
                val taskId = rs.getString("task_id")?.let { TaskId(it) }
                val decisionId = rs.getString("decision_id")?.let { DecisionId(it) }
                val label = rs.getString("label")
                val snapshotJson = rs.getString("snapshot")
                val createdAt = rs.getTimestamp("created_at").toInstant()
                SnapshotRow(
                    id = rs.getLong("id"),
                    taskId = taskId,
                    decisionId = decisionId,
                    label = label,
                    snapshotJson = snapshotJson,
                    createdAt = createdAt
                )
            }
        }
    }

    private fun generateId(): Long {
        // Compose time-based id with a small random suffix to avoid collision
        val base = System.currentTimeMillis() * 1000
        val rand = (Math.random() * 1000).toLong()
        return base + rand
    }
}
