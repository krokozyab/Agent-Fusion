package com.orchestrator.storage.repositories

import com.orchestrator.domain.*
import com.orchestrator.storage.Database
import java.sql.SQLException
import java.sql.Timestamp

/**
 * JDBC repository for Proposal entities.
 * - Stores content and metadata as JSON strings (DuckDB JSON type accepts text input)
 * - Respects FK to tasks(id)
 * - Provides findByTask and findByAgent helpers
 */
object ProposalRepository {
    // region Public API
    fun insert(p: Proposal) = Database.withConnection { conn ->
        conn.prepareStatement("SELECT 1 FROM tasks WHERE id = ?").use { checkPs ->
            checkPs.setString(1, p.taskId.value)
            checkPs.executeQuery().use { rs ->
                if (!rs.next()) {
                    throw SQLException("Cannot insert proposal '${p.id.value}': task '${p.taskId.value}' not found")
                }
            }
        }
        val sql = """
            INSERT INTO proposals (
                id, task_id, agent_id, input_type, content,
                confidence, token_input, token_output, created_at, metadata
            ) VALUES (
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?
            )
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            var i = 1
            ps.setString(i++, p.id.value)
            ps.setString(i++, p.taskId.value)
            ps.setString(i++, p.agentId.value)
            ps.setString(i++, p.inputType.name)
            ps.setString(i++, anyToJson(p.content))
            ps.setDouble(i++, p.confidence)
            ps.setInt(i++, p.tokenUsage.inputTokens)
            ps.setInt(i++, p.tokenUsage.outputTokens)
            ps.setTimestamp(i++, Timestamp.from(p.createdAt))
            ps.setString(i, mapToJson(p.metadata))
            ps.executeUpdate()
        }
    }

    fun findById(id: ProposalId): Proposal? = Database.withConnection { conn ->
        val sql = "SELECT * FROM proposals WHERE id = ?"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, id.value)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.toProposal() else null
            }
        }
    }

    fun findByTask(taskId: TaskId): List<Proposal> = Database.withConnection { conn ->
        val sql = "SELECT * FROM proposals WHERE task_id = ? ORDER BY created_at DESC"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, taskId.value)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Proposal>()
                while (rs.next()) out += rs.toProposal()
                out
            }
        }
    }

    fun findByAgent(agentId: AgentId): List<Proposal> = Database.withConnection { conn ->
        val sql = "SELECT * FROM proposals WHERE agent_id = ? ORDER BY created_at DESC"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, agentId.value)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Proposal>()
                while (rs.next()) out += rs.toProposal()
                out
            }
        }
    }

    fun update(p: Proposal) = Database.withConnection { conn ->
        // Replace strategy: delete then insert
        conn.prepareStatement("DELETE FROM proposals WHERE id = ?").use { ps ->
            ps.setString(1, p.id.value)
            ps.executeUpdate()
        }
        insert(p)
    }

    fun delete(id: ProposalId) = Database.withConnection { conn ->
        val sql = "DELETE FROM proposals WHERE id = ?"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, id.value)
            ps.executeUpdate()
        }
    }
    // endregion

    // region Mapping helpers and JSON
    private fun java.sql.ResultSet.toProposal(): Proposal {
        val id = ProposalId(getString("id"))
        val taskId = TaskId(getString("task_id"))
        val agentId = AgentId(getString("agent_id"))
        val inputType = InputType.valueOf(getString("input_type"))
        val content = jsonToAny(getString("content"))
        val confidence = getDouble("confidence")
        val tokenInput = getInt("token_input")
        val tokenOutput = getInt("token_output")
        val createdAt = getTimestamp("created_at").toInstant()
        val metadata = jsonToMap(getString("metadata"))
        return Proposal(
            id = id,
            taskId = taskId,
            agentId = agentId,
            inputType = inputType,
            content = content,
            confidence = confidence,
            tokenUsage = TokenUsage(tokenInput, tokenOutput),
            createdAt = createdAt,
            metadata = metadata
        )
    }

    // JSON for Map<String, String> (metadata) — copy from TaskRepository
    private fun mapToJson(map: Map<String, String>): String? {
        if (map.isEmpty()) return null
        val escaped = map.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "\"${escapeJson(k)}\":\"${escapeJson(v)}\""
        }
        return escaped
    }

    private fun jsonToMap(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
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
                            'u' -> if (i + 4 <= json.length) {
                                val hex = json.substring(i, i + 4)
                                sb.append(hex.toInt(16).toChar())
                                i += 4
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
        fun expect(ch: Char): Boolean { skipWs(); return if (i < json.length && json[i] == ch) { i++; true } else false }
        skipWs(); if (!expect('{')) return emptyMap()
        skipWs()
        if (i < json.length && json[i] == '}') { i++; return result }
        while (i < json.length) {
            val key = parseString() ?: break
            skipWs(); if (!expect(':')) break
            val value = parseString() ?: break
            result[key] = value
            skipWs()
            if (i < json.length && json[i] == ',') { i++; continue }
            if (i < json.length && json[i] == '}') { i++; break }
            break
        }
        return result
    }

    private fun escapeJson(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> append(c)
        }
    }

    // Generic JSON for Any? content -----------------------------------------
    private fun anyToJson(value: Any?): String? {
        return when (value) {
            null -> null
            is String -> "\"${escapeJson(value)}\""
            is Number, is Boolean -> value.toString()
            is Map<*, *> -> mapAnyToJson(value)
            is List<*> -> listAnyToJson(value)
            else -> "\"${escapeJson(value.toString())}\"" // fallback to string representation
        }
    }

    private fun mapAnyToJson(map: Map<*, *>): String {
        val parts = map.entries.joinToString(separator = ",") { (k, v) ->
            val key = k?.toString() ?: "null"
            val jsonVal = anyToJson(v) ?: "null"
            "\"${escapeJson(key)}\":$jsonVal"
        }
        return "{$parts}"
    }

    private fun listAnyToJson(list: List<*>): String {
        val parts = list.joinToString(separator = ",") { anyToJson(it) ?: "null" }
        return "[$parts]"
    }

    private fun jsonToAny(json: String?): Any? {
        if (json.isNullOrBlank()) return null
        return JsonParser(json).parseValue()
    }

    // Tiny JSON parser for objects/arrays/strings/numbers/booleans/null
    private class JsonParser(private val s: String) {
        private var i = 0
        private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun parseValue(): Any? {
            skipWs()
            if (i >= s.length) return null
            return when (s[i]) {
                '"' -> parseString()
                '{' -> parseObject()
                '[' -> parseArray()
                't' -> if (s.startsWith("true", i)) { i += 4; true } else null
                'f' -> if (s.startsWith("false", i)) { i += 5; false } else null
                'n' -> if (s.startsWith("null", i)) { i += 4; null } else null
                '-', in '0'..'9' -> parseNumber()
                else -> null
            }
        }
        private fun parseString(): String? {
            if (s[i] != '"') return null
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '\\' -> if (i < s.length) {
                        val esc = s[i++]
                        when (esc) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> if (i + 4 <= s.length) {
                                val hex = s.substring(i, i + 4)
                                sb.append(hex.toInt(16).toChar())
                                i += 4
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
        private fun expect(ch: Char): Boolean { skipWs(); return if (i < s.length && s[i] == ch) { i++; true } else false }
        private fun parseObject(): Map<String, Any?>? {
            if (!expect('{')) return null
            val result = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return result }
            while (i < s.length) {
                val key = parseString() ?: return null
                if (!expect(':')) return null
                val value = parseValue()
                result[key] = value
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == '}') { i++; break }
                break
            }
            return result
        }
        private fun parseArray(): List<Any?>? {
            if (!expect('[')) return null
            val result = mutableListOf<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return result }
            while (i < s.length) {
                val v = parseValue()
                result += v
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == ']') { i++; break }
                break
            }
            return result
        }
        private fun parseNumber(): Number? {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            if (i < s.length && s[i] == '.') { i++; while (i < s.length && s[i].isDigit()) i++ }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            val numStr = s.substring(start, i)
            return try {
                if (numStr.contains('.') || numStr.contains('e', true) || numStr.contains('E')) numStr.toDouble() else numStr.toLong()
            } catch (_: Exception) {
                null
            }
        }
    }
    // endregion
}
