package com.orchestrator.storage.repositories

import com.orchestrator.domain.*
import com.orchestrator.storage.Database
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp

/**
 * JDBC repository for Decision entities.
 * - Persists full decision context (considered ProposalRef snapshots)
 * - Links to tasks and (optional) winning proposal via FKs
 * - Stores selected proposal ids as VARCHAR[]
 */
object DecisionRepository {
    // region Public API
    fun insert(d: Decision) = Database.withConnection { conn ->
        insertInternal(conn, d)
    }

    fun upsert(d: Decision) = Database.withConnection { conn ->
        val updated = updateInternal(conn, d)
        if (updated == 0) {
            insertInternal(conn, d)
        }
    }

    fun update(d: Decision) = upsert(d)

    fun delete(id: DecisionId) = Database.withConnection { conn ->
        conn.prepareStatement("DELETE FROM decisions WHERE id = ?").use { ps ->
            ps.setString(1, id.value)
            ps.executeUpdate()
        }
    }

    fun findById(id: DecisionId): Decision? = Database.withConnection { conn ->
        val sql = "SELECT * FROM decisions WHERE id = ?"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, id.value)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.toDecision() else null
            }
        }
    }

    /**
     * Returns the most recent decision for a task (by decided_at), or null if none exists.
     */
    fun findByTask(taskId: TaskId): Decision? = Database.withConnection { conn ->
        val sql = "SELECT * FROM decisions WHERE task_id = ? ORDER BY decided_at DESC LIMIT 1"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, taskId.value)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toDecision() else null }
        }
    }

    /**
     * Returns all decisions for a task ordered by decided_at desc (audit trail helper).
     */
    fun listByTask(taskId: TaskId): List<Decision> = Database.withConnection { conn ->
        val sql = "SELECT * FROM decisions WHERE task_id = ? ORDER BY decided_at DESC"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, taskId.value)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Decision>()
                while (rs.next()) out += rs.toDecision()
                out
            }
        }
    }
    // endregion

    // region Mapping helpers
    private fun java.sql.ResultSet.toDecision(): Decision {
        val id = DecisionId(getString("id"))
        val taskId = TaskId(getString("task_id"))
        val considered = jsonToConsidered(getString("considered"))
        val selectedIds = getVarcharArray("selected_ids").map { ProposalId(it) }.toSet()
        val winner = getString("winner_proposal_id")?.let { ProposalId(it) }
        val agreement = getObject("agreement_rate")?.let { (it as Number).toDouble() }
        val rationale = getString("rationale")
        val decidedAt = getTimestamp("decided_at").toInstant()
        val metadata = jsonToMap(getString("metadata"))
        return Decision(
            id = id,
            taskId = taskId,
            considered = considered,
            selected = selectedIds,
            winnerProposalId = winner,
            agreementRate = agreement,
            rationale = rationale,
            decidedAt = decidedAt,
            metadata = metadata
        )
    }

    private fun java.sql.ResultSet.getVarcharArray(column: String): List<String> {
        val s = this.getString(column) ?: return emptyList()
        val trimmed = s.trim()
        if (trimmed.length < 2 || trimmed.first() != '[' || trimmed.last() != ']') return emptyList()
        val inner = trimmed.substring(1, trimmed.length - 1).trim()
        if (inner.isBlank()) return emptyList()
        return inner.split(',')
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotEmpty() }
    }
    // endregion

    private fun insertInternal(conn: Connection, d: Decision) {
        val sql = """
            INSERT INTO decisions (
                id, task_id, considered, selected_ids,
                winner_proposal_id, agreement_rate, rationale,
                decided_at, metadata
            ) VALUES (
                ?, ?, ?,
                CASE WHEN ? = '' THEN [] ELSE string_split(?, ',') END,
                ?, ?, ?, ?, ?
            )
        """.trimIndent()
        val selectedCsv = toCsvLiteral(d.selected)
        conn.prepareStatement(sql).use { ps ->
            var i = 1
            ps.setString(i++, d.id.value)
            ps.setString(i++, d.taskId.value)
            ps.setString(i++, consideredToJson(d.considered))
            ps.setString(i++, selectedCsv)
            ps.setString(i++, selectedCsv)
            ps.setStringOrNull(i++, d.winnerProposalId?.value)
            ps.setObject(i++, d.agreementRate)
            ps.setStringOrNull(i++, d.rationale)
            ps.setTimestamp(i++, Timestamp.from(d.decidedAt))
            ps.setStringOrNull(i, mapToJson(d.metadata))
            ps.executeUpdate()
        }
    }

    private fun updateInternal(conn: Connection, d: Decision): Int {
        val sql = """
            UPDATE decisions
            SET
                task_id = ?,
                considered = ?,
                selected_ids = CASE WHEN ? = '' THEN [] ELSE string_split(?, ',') END,
                winner_proposal_id = ?,
                agreement_rate = ?,
                rationale = ?,
                decided_at = ?,
                metadata = ?
            WHERE id = ?
        """.trimIndent()
        val selectedCsv = toCsvLiteral(d.selected)
        conn.prepareStatement(sql).use { ps ->
            var i = 1
            ps.setString(i++, d.taskId.value)
            ps.setString(i++, consideredToJson(d.considered))
            ps.setString(i++, selectedCsv)
            ps.setString(i++, selectedCsv)
            ps.setStringOrNull(i++, d.winnerProposalId?.value)
            ps.setObject(i++, d.agreementRate)
            ps.setStringOrNull(i++, d.rationale)
            ps.setTimestamp(i++, Timestamp.from(d.decidedAt))
            ps.setStringOrNull(i++, mapToJson(d.metadata))
            ps.setString(i, d.id.value)
            return ps.executeUpdate()
        }
    }

    // region JSON helpers for considered ProposalRef and metadata
    private fun consideredToJson(list: List<ProposalRef>): String {
        // Serialize as array of objects with keys: id, agentId, inputType, confidence, tokenUsage{input,output}
        val parts = list.joinToString(separator = ",") { ref ->
            val tokenJson = "{\"inputTokens\":${ref.tokenUsage.inputTokens},\"outputTokens\":${ref.tokenUsage.outputTokens}}"
            "{" +
                "\"id\":\"${escapeJson(ref.id.value)}\"," +
                "\"agentId\":\"${escapeJson(ref.agentId.value)}\"," +
                "\"inputType\":\"${escapeJson(ref.inputType.name)}\"," +
                "\"confidence\":${ref.confidence}," +
                "\"tokenUsage\":$tokenJson" +
            "}"
        }
        return "[$parts]"
    }

    private fun jsonToConsidered(json: String?): List<ProposalRef> {
        if (json.isNullOrBlank()) return emptyList()
        val any = jsonToAny(json)
        if (any !is List<*>) return emptyList()
        return any.mapNotNull { el ->
            if (el !is Map<*, *>) return@mapNotNull null
            val id = (el["id"] as? String)?.let { ProposalId(it) } ?: return@mapNotNull null
            val agent = (el["agentId"] as? String)?.let { AgentId(it) } ?: return@mapNotNull null
            val inputTypeName = (el["inputType"] as? String) ?: return@mapNotNull null
            val inputType = runCatching { InputType.valueOf(inputTypeName) }.getOrNull() ?: return@mapNotNull null
            val confidence = (el["confidence"] as? Number)?.toDouble() ?: return@mapNotNull null
            val tu = (el["tokenUsage"] as? Map<*, *>)
            val inTok = (tu?.get("inputTokens") as? Number)?.toInt() ?: 0
            val outTok = (tu?.get("outputTokens") as? Number)?.toInt() ?: 0
            ProposalRef(
                id = id,
                agentId = agent,
                inputType = inputType,
                confidence = confidence,
                tokenUsage = TokenUsage(inTok, outTok)
            )
        }
    }

    // Reuse minimal JSON support patterns from other repositories
    private fun mapToJson(map: Map<String, String>): String? {
        if (map.isEmpty()) return null
        val escaped = map.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "\"${escapeJson(k)}\":\"${escapeJson(v)}\""
        }
        return escaped
    }

    private fun toCsvLiteral(values: Set<ProposalId>): String {
        if (values.isEmpty()) return ""
        return values
            .map { it.value }
            .sorted()
            .joinToString(",") { it.replace(",", " ") }
    }

    private fun PreparedStatement.setStringOrNull(index: Int, value: String?) {
        if (value == null) {
            setNull(index, java.sql.Types.VARCHAR)
        } else {
            setString(index, value)
        }
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

    // Minimal Any JSON to parse considered field back
    private fun anyToJson(value: Any?): String? {
        return when (value) {
            null -> null
            is String -> "\"${escapeJson(value)}\""
            is Number, is Boolean -> value.toString()
            is Map<*, *> -> mapAnyToJson(value)
            is List<*> -> listAnyToJson(value)
            else -> "\"${escapeJson(value.toString())}\""
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
            } catch (_: Exception) { null }
        }
    }
    // endregion
}
