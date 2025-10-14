package com.orchestrator.context.providers

import com.orchestrator.context.domain.ContextScope
import com.orchestrator.context.domain.ContextSnippet
import com.orchestrator.context.domain.TokenBudget
import com.orchestrator.context.storage.ContextDatabase
import java.sql.PreparedStatement
import java.util.Locale
import kotlin.math.max

class FullTextContextProvider(
    private val stopwords: Set<String> = DEFAULT_STOPWORDS,
    private val maxResults: Int = 50
) : ContextProvider {

    override val id: String = "full_text"
    override val type: ContextProviderType = ContextProviderType.FULL_TEXT

    override suspend fun getContext(
        query: String,
        scope: ContextScope,
        budget: TokenBudget
    ): List<ContextSnippet> {
        val keywords = extractKeywords(query)
        if (keywords.isEmpty()) return emptyList()

        val (sql, parameters) = buildQuery(keywords, scope)

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
                        val path = rs.getString("rel_path")
                        val language = rs.getString("language")

                        val score = scoreKeywords(content.lowercase(Locale.US), keywords)
                        val tokens = max(1, tokenEstimate)
                        if (tokenBudget > 0 && tokensUsed + tokens > tokenBudget) {
                            continue
                        }

                        val snippet = ContextSnippet(
                            chunkId = chunkId,
                            score = score.coerceAtMost(1.0),
                            filePath = path,
                            label = summary,
                            kind = com.orchestrator.context.domain.ChunkKind.valueOf(kind),
                            text = content,
                            language = language,
                            offsets = null,
                            metadata = mapOf(
                                "provider" to id,
                                "sources" to id,
                                "bm25_score" to "%.3f".format(score),
                                "file_id" to fileId.toString(),
                                "token_estimate" to tokens.toString()
                            )
                        )
                        snippets += snippet
                        tokensUsed += tokens
                    }
                }
            }
        }

        return snippets
    }

    private fun extractKeywords(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        return query.lowercase(Locale.US)
            .split(Regex("\\W+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .filterNot { stopwords.contains(it) }
            .distinct()
    }

    private data class SqlBundle(val sql: String, val params: List<Any>)

    private fun buildQuery(keywords: List<String>, scope: ContextScope): SqlBundle {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        keywords.forEach { keyword ->
            conditions += "(LOWER(c.content) LIKE ? OR LOWER(c.summary) LIKE ?)"
            val token = "%$keyword%"
            params += token
            params += token
        }

        if (scope.paths.isNotEmpty()) {
            val pathClauses = scope.paths.map { "f.rel_path LIKE ?" }
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

        val where = if (conditions.isEmpty()) "1=1" else conditions.joinToString(" AND ")

        val sql = """
            SELECT c.chunk_id, c.file_id, c.kind, c.content, c.summary,
                   c.token_count, f.rel_path, f.language
            FROM chunks c
            JOIN file_state f ON f.file_id = c.file_id
            WHERE $where
        """.trimIndent()

        return SqlBundle(sql, params)
    }

    private fun scoreKeywords(content: String, keywords: List<String>): Double {
        if (keywords.isEmpty() || content.isBlank()) return 0.0
        val termFrequency = keywords.sumOf { keyword ->
            content.windowed(keyword.length) { if (it == keyword) 1 else 0 }.sum()
        }.coerceAtLeast(1)
        val normaliser = content.length.coerceAtLeast(10)
        return (termFrequency.toDouble() / normaliser * 10.0).coerceIn(0.0, 1.0)
    }

    companion object {
        private val DEFAULT_STOPWORDS = setOf(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from",
            "has", "in", "is", "it", "of", "on", "or", "that", "the", "to", "was", "will", "with"
        )
    }
}
