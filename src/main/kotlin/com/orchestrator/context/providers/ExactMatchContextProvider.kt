package com.orchestrator.context.providers

import com.orchestrator.context.domain.ChunkKind
import com.orchestrator.context.domain.ContextScope
import com.orchestrator.context.domain.ContextSnippet
import com.orchestrator.context.domain.TokenBudget
import com.orchestrator.context.storage.ContextDatabase
import java.util.Locale
import kotlin.math.max

/**
 * Provider that searches for literal/exact text patterns in chunk content.
 *
 * Unlike [FullTextContextProvider], this does NOT split on `\W+` and therefore
 * preserves special characters (`@`, `-`, `.`, `:`, `/`, UUIDs, ticket IDs, etc.)
 * that are destroyed by keyword tokenisation.
 *
 * Quoted substrings in the query (e.g. `"@Sergey Rudenko"`) are extracted as
 * individual exact phrases. Remaining unquoted text is kept as a single literal
 * pattern.
 */
class ExactMatchContextProvider(
    private val maxResults: Int = 50
) : ContextProvider {

    override val id: String = "exact_match"
    override val type: ContextProviderType = ContextProviderType.EXACT_MATCH

    override suspend fun getContext(
        query: String,
        scope: ContextScope,
        budget: TokenBudget
    ): List<ContextSnippet> {
        val patterns = extractLiteralPatterns(query)
        if (patterns.isEmpty()) return emptyList()

        val (sql, parameters) = buildQuery(patterns, scope)

        val snippets = mutableListOf<ContextSnippet>()
        var tokensUsed = 0
        val tokenBudget = budget.availableForSnippets.coerceAtLeast(0)

        ContextDatabase.withConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                parameters.forEachIndexed { index, value ->
                    when (value) {
                        is String -> ps.setString(index + 1, value)
                        is Int -> ps.setInt(index + 1, value)
                        else -> ps.setObject(index + 1, value)
                    }
                }
                ps.executeQuery().use { rs ->
                    while (rs.next() && snippets.size < maxResults) {
                        val chunkId = rs.getLong("chunk_id")
                        val fileId = rs.getLong("file_id")
                        val content = rs.getString("content")
                        val summary = rs.getString("summary")
                        val kind = rs.getString("kind")
                        val tokenEstimate = rs.getInt("token_count").takeIf { !rs.wasNull() } ?: content.length / 4
                        val chunkPath = rs.getString("chunk_path")
                        val parentChunkId = rs.getLong("parent_chunk_id").takeIf { !rs.wasNull() }
                        val path = rs.getString("abs_path")
                        val language = rs.getString("language")

                        val score = scorePatterns(content, patterns)
                        if (score <= 0.0) continue

                        val tokens = max(1, tokenEstimate)
                        if (tokenBudget > 0 && tokensUsed + tokens > tokenBudget) {
                            continue
                        }

                        val snippet = ContextSnippet(
                            chunkId = chunkId,
                            score = score.coerceAtMost(1.0),
                            filePath = path,
                            label = summary,
                            kind = ChunkKind.valueOf(kind),
                            text = content,
                            language = language,
                            offsets = null,
                            chunkPath = chunkPath,
                            parentChunkId = parentChunkId,
                            metadata = mapOf(
                                "provider" to id,
                                "sources" to id,
                                "exact_match_score" to "%.3f".format(Locale.US, score),
                                "file_id" to fileId.toString(),
                                "token_estimate" to tokens.toString(),
                                "chunk_path" to (chunkPath ?: ""),
                                "parent_chunk_id" to (parentChunkId?.toString() ?: "")
                            )
                        )
                        snippets += snippet
                        tokensUsed += tokens
                    }
                }
            }
        }

        return snippets.sortedByDescending { it.score }
    }

    /**
     * Extract literal patterns from the query.
     *
     * - Quoted substrings (`"@Sergey Rudenko"`) are extracted as individual phrases.
     * - Remaining unquoted text (trimmed, collapsed whitespace) is kept as a single
     *   literal pattern — special characters are preserved.
     */
    internal fun extractLiteralPatterns(query: String): List<String> {
        if (query.isBlank()) return emptyList()

        val patterns = mutableListOf<String>()
        val remaining = StringBuilder()
        var i = 0

        while (i < query.length) {
            if (query[i] == '"') {
                val closeIdx = query.indexOf('"', i + 1)
                if (closeIdx > i + 1) {
                    val quoted = query.substring(i + 1, closeIdx).trim()
                    if (quoted.isNotBlank()) patterns.add(quoted)
                    i = closeIdx + 1
                } else {
                    remaining.append(query[i])
                    i++
                }
            } else {
                remaining.append(query[i])
                i++
            }
        }

        val unquoted = remaining.toString().trim().replace(Regex("\\s+"), " ")
        if (unquoted.isNotBlank()) {
            patterns.add(unquoted)
        }

        return patterns.distinct()
    }

    private data class SqlBundle(val sql: String, val params: List<Any>)

    private fun buildQuery(patterns: List<String>, scope: ContextScope): SqlBundle {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        // Each pattern: LIKE %pattern% on content (case-insensitive via LOWER)
        val patternClauses = patterns.map {
            "LOWER(c.content) LIKE ?"
        }
        conditions += "(${patternClauses.joinToString(" OR ")})"
        patterns.forEach { pattern ->
            params += "%${pattern.lowercase(Locale.US)}%"
        }

        if (scope.paths.isNotEmpty()) {
            val pathClauses = scope.paths.map { "f.abs_path LIKE ?" }
            conditions += "(${pathClauses.joinToString(" OR ")})"
            scope.paths.forEach { params += "${it.trim()}%" }
        }

        if (scope.languages.isNotEmpty()) {
            val placeholders = scope.languages.joinToString(",") { "?" }
            conditions += "f.language IN ($placeholders)"
            scope.languages.forEach { params += it }
        }

        if (scope.kinds.isNotEmpty()) {
            val placeholders = scope.kinds.joinToString(",") { "?" }
            conditions += "c.kind IN ($placeholders)"
            scope.kinds.forEach { params += it.name }
        }

        val where = conditions.joinToString(" AND ")

        val sql = """
            SELECT c.chunk_id, c.file_id, c.kind, c.content, c.summary,
                   c.token_count, c.chunk_path, c.parent_chunk_id,
                   f.abs_path, f.language
            FROM chunks c
            JOIN file_state f ON f.file_id = c.file_id
            WHERE $where
        """.trimIndent()

        return SqlBundle(sql, params)
    }

    private fun scorePatterns(content: String, patterns: List<String>): Double {
        if (content.isBlank() || patterns.isEmpty()) return 0.0

        val contentLower = content.lowercase(Locale.US)
        var matchCount = 0

        for (pattern in patterns) {
            if (contentLower.contains(pattern.lowercase(Locale.US))) {
                matchCount++
            }
        }

        if (matchCount == 0) return 0.0

        // Base score 0.95 for exact match found
        var score = 0.95

        // Length boost: longer patterns are more specific
        val maxPatternLen = patterns.maxOf { it.length }
        score *= when {
            maxPatternLen >= 20 -> 1.05
            maxPatternLen >= 10 -> 1.02
            else -> 1.0
        }

        // Short content penalty
        if (content.length < 50) {
            score *= 0.3
        }

        // Partial match penalty when not all patterns found
        if (matchCount < patterns.size) {
            score *= (matchCount.toDouble() / patterns.size)
        }

        return score.coerceIn(0.0, 1.0)
    }
}
