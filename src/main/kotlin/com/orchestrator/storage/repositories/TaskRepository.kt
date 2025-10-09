package com.orchestrator.storage.repositories

import com.orchestrator.domain.*
import com.orchestrator.storage.Database
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp

/**
 * Raw JDBC repository for Task entities against DuckDB.
 * Uses PreparedStatements for all SQL to avoid injection and provides
 * mapping helpers for arrays and JSON fields.
 */
object TaskRepository {
    // region Public API
    fun insert(task: Task) = Database.withConnection { conn ->
        val sql = """
            INSERT INTO tasks (
                id, title, description, type, status, routing,
                assignee_ids, dependencies, complexity, risk,
                created_at, updated_at, due_at, metadata
            ) VALUES (
                ?, ?, ?, ?, ?, ?,
                CASE WHEN ? = '' THEN [] ELSE string_split(?, ',') END,
                CASE WHEN ? = '' THEN [] ELSE string_split(?, ',') END,
                ?, ?, ?, ?, ?, ?
            )
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            var idx = 1
            ps.setString(idx++, task.id.value)
            ps.setString(idx++, task.title)
            ps.setString(idx++, task.description)
            ps.setString(idx++, task.type.name)
            ps.setString(idx++, task.status.name)
            ps.setString(idx++, task.routing.name)
            val assigneesCsv = task.assigneeIds.joinToString(",") { it.value.replace(",", " ") }
            val depsCsv = task.dependencies.joinToString(",") { it.value.replace(",", " ") }
            ps.setString(idx++, assigneesCsv)
            ps.setString(idx++, assigneesCsv)
            ps.setString(idx++, depsCsv)
            ps.setString(idx++, depsCsv)
            ps.setInt(idx++, task.complexity)
            ps.setInt(idx++, task.risk)
            ps.setTimestamp(idx++, Timestamp.from(task.createdAt))
            ps.setTimestamp(idx++, task.updatedAt?.let { Timestamp.from(it) })
            ps.setTimestamp(idx++, task.dueAt?.let { Timestamp.from(it) })
            ps.setString(idx, mapToJson(task.metadata))
            ps.executeUpdate()
        }
    }

    fun findById(id: TaskId): Task? = Database.withConnection { conn ->
        val sql = "SELECT * FROM tasks WHERE id = ?"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, id.value)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.toTask() else null
            }
        }
    }

    fun findByStatus(status: TaskStatus): List<Task> = Database.withConnection { conn ->
        val sql = "SELECT * FROM tasks WHERE status = ? ORDER BY created_at DESC"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, status.name)
            ps.executeQuery().use { rs -> rs.toTaskList() }
        }
    }

    fun findByAgent(agentId: AgentId): List<Task> = Database.withConnection { conn ->
        // DuckDB supports list_contains for LIST/VARCHAR[] columns
        val sql = "SELECT * FROM tasks WHERE list_contains(assignee_ids, ?) ORDER BY created_at DESC"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, agentId.value)
            ps.executeQuery().use { rs -> rs.toTaskList() }
        }
    }

    fun update(task: Task) = Database.withConnection { conn ->
        val sql = """
            UPDATE tasks
            SET
                title = ?,
                description = ?,
                type = ?,
                status = ?,
                routing = ?,
                assignee_ids = CASE WHEN ? = '' THEN [] ELSE string_split(?, ',') END,
                dependencies = CASE WHEN ? = '' THEN [] ELSE string_split(?, ',') END,
                complexity = ?,
                risk = ?,
                created_at = ?,
                updated_at = ?,
                due_at = ?,
                metadata = ?
            WHERE id = ?
        """.trimIndent()

        conn.prepareStatement(sql).use { ps ->
            var idx = 1
            ps.setString(idx++, task.title)
            ps.setString(idx++, task.description)
            ps.setString(idx++, task.type.name)
            ps.setString(idx++, task.status.name)
            ps.setString(idx++, task.routing.name)

            val assigneesCsv = task.assigneeIds.joinToString(",") { it.value.replace(",", " ") }
            val depsCsv = task.dependencies.joinToString(",") { it.value.replace(",", " ") }
            ps.setString(idx++, assigneesCsv)
            ps.setString(idx++, assigneesCsv)
            ps.setString(idx++, depsCsv)
            ps.setString(idx++, depsCsv)

            ps.setInt(idx++, task.complexity)
            ps.setInt(idx++, task.risk)
            ps.setTimestamp(idx++, Timestamp.from(task.createdAt))
            ps.setTimestamp(idx++, task.updatedAt?.let { Timestamp.from(it) })
            ps.setTimestamp(idx++, task.dueAt?.let { Timestamp.from(it) })
            ps.setString(idx++, mapToJson(task.metadata))
            ps.setString(idx, task.id.value)

            val updatedRows = ps.executeUpdate()
            if (updatedRows == 0) {
                throw IllegalStateException("Task '${task.id.value}' was not found for update")
            }
        }
    }

    /**
     * Narrow update for status transition only, preventing concurrent overwrites of other fields.
     * Uses optimistic locking by checking expected old status.
     */
    fun updateStatus(id: TaskId, newStatus: TaskStatus, expectedOldStatuses: Set<TaskStatus>): Boolean =
        Database.withConnection { conn ->
            val statusConditions = expectedOldStatuses.joinToString(" OR ") { "status = ?" }
            val sql = """
                UPDATE tasks
                SET status = ?, updated_at = ?
                WHERE id = ? AND ($statusConditions)
            """.trimIndent()

            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                ps.setString(idx++, newStatus.name)
                ps.setTimestamp(idx++, Timestamp.from(java.time.Instant.now()))
                ps.setString(idx++, id.value)
                expectedOldStatuses.forEach { ps.setString(idx++, it.name) }

                ps.executeUpdate() > 0
            }
        }

    fun delete(id: TaskId) = Database.withConnection { conn ->
        val sql = "DELETE FROM tasks WHERE id = ?"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, id.value)
            ps.executeUpdate()
        }
    }

    /**
     * Filtered query with pagination. Returns Pair(items, totalCount).
     */
    fun queryFiltered(
        status: TaskStatus?,
        agentId: AgentId?,
        from: java.time.Instant?,
        to: java.time.Instant?,
        limit: Int,
        offset: Int
    ): Pair<List<Task>, Int> = Database.withConnection { conn ->
        val where = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        if (status != null) { where += "status = ?"; params += status.name }
        if (agentId != null) { where += "list_contains(assignee_ids, ?)"; params += agentId.value }
        if (from != null) { where += "created_at >= ?"; params += Timestamp.from(from) }
        if (to != null) { where += "created_at <= ?"; params += Timestamp.from(to) }
        val whereSql = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")

        val total = conn.prepareStatement("SELECT COUNT(*) FROM tasks$whereSql").use { ps ->
            var idx = 1
            for (p in params) {
                when (p) {
                    is String -> ps.setString(idx++, p)
                    is Timestamp -> ps.setTimestamp(idx++, p)
                    else -> ps.setObject(idx++, p)
                }
            }
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

        val sql = "SELECT * FROM tasks$whereSql ORDER BY created_at DESC LIMIT ? OFFSET ?"
        val items = conn.prepareStatement(sql).use { ps ->
            var idx = 1
            for (p in params) {
                when (p) {
                    is String -> ps.setString(idx++, p)
                    is Timestamp -> ps.setTimestamp(idx++, p)
                    else -> ps.setObject(idx++, p)
                }
            }
            ps.setInt(idx++, limit)
            ps.setInt(idx, offset)
            ps.executeQuery().use { rs -> rs.toTaskList() }
        }
        Pair(items, total)
    }
    // endregion

    // region Mapping helpers
    private fun ResultSet.toTaskList(): List<Task> {
        val out = mutableListOf<Task>()
        while (this.next()) out += this.toTask()
        return out
    }

    private fun ResultSet.toTask(): Task {
        val id = TaskId(getString("id"))
        val title = getString("title")
        val description = getString("description")
        val type = TaskType.valueOf(getString("type"))
        val status = TaskStatus.valueOf(getString("status"))
        val routing = RoutingStrategy.valueOf(getString("routing"))
        val assignees = getVarcharArray("assignee_ids").map { AgentId(it) }.toSet()
        val deps = getVarcharArray("dependencies").map { TaskId(it) }.toSet()
        val complexity = getInt("complexity")
        val risk = getInt("risk")
        val createdAt = getTimestamp("created_at").toInstant()
        val updatedAt = getTimestamp("updated_at")?.toInstant()
        val dueAt = getTimestamp("due_at")?.toInstant()
        val metadata = jsonToMap(getString("metadata"))
        return Task(
            id = id,
            title = title,
            description = description,
            type = type,
            status = status,
            routing = routing,
            assigneeIds = assignees,
            dependencies = deps,
            complexity = complexity,
            risk = risk,
            createdAt = createdAt,
            updatedAt = updatedAt,
            dueAt = dueAt,
            metadata = metadata
        )
    }

    private fun ResultSet.getVarcharArray(column: String): List<String> {
        val s = this.getString(column) ?: return emptyList()
        val trimmed = s.trim()
        if (trimmed.length < 2 || trimmed.first() != '[' || trimmed.last() != ']') return emptyList()
        val inner = trimmed.substring(1, trimmed.length - 1).trim()
        if (inner.isBlank()) return emptyList()
        return inner.split(',')
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotEmpty() }
    }

    private fun toVarcharArray(conn: Connection, values: Collection<String>): java.sql.Array? {
        if (values.isEmpty()) return conn.createArrayOf("VARCHAR", emptyArray<String>())
        return conn.createArrayOf("VARCHAR", values.toTypedArray())
    }

    // Minimal JSON serialization for Map<String, String> without external libraries beyond stdlib
    private fun mapToJson(map: Map<String, String>): String? {
        if (map.isEmpty()) return null
        val escaped = map.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "\"${escapeJson(k)}\":\"${escapeJson(v)}\""
        }
        return escaped
    }

    private fun jsonToMap(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        // Very small and safe parser for flat string-to-string JSON objects
        // This assumes metadata values/keys don't contain complex JSON structures.
        val result = java.util.LinkedHashMap<String, String>()
        var i = 0
        fun skipWs() { while (i < json.length && json[i].isWhitespace()) i++ }
        fun parseString(): String? {
            skipWs()
            if (i >= json.length || json[i] != '"') return null
            i++
            val sb = StringBuilder()
            while (i < json.length) {
                val c = json[i++]
                when (c) {
                    '\\' -> if (i < json.length) {
                        val esc = json[i++]
                        when (esc) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 <= json.length) {
                                    val hex = json.substring(i, i + 4)
                                    sb.append(hex.toInt(16).toChar())
                                    i += 4
                                }
                            }
                            else -> sb.append(esc)
                        }
                    }
                    '"' -> return sb.toString()
                    else -> sb.append(c)
                }
            }
            return null
        }
        skipWs(); if (i >= json.length || json[i] != '{') return emptyMap(); i++
        while (true) {
            skipWs(); if (i < json.length && json[i] == '}') { i++; break }
            val key = parseString() ?: return emptyMap()
            skipWs(); if (i >= json.length || json[i] != ':') return emptyMap(); i++
            val value = parseString() ?: return emptyMap()
            result[key] = value
            skipWs()
            if (i < json.length && json[i] == ',') { i++ ; continue }
            skipWs(); if (i < json.length && json[i] == '}') { i++; break }
        }
        return result
    }

    private fun escapeJson(s: String): String = buildString {
        s.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }
    // endregion
}
