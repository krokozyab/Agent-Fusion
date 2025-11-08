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
                        val path = rs.getString("abs_path")
                        val language = rs.getString("language")

                        val contentScore = scoreKeywords(content.lowercase(Locale.US), keywords)
                        // Prefer content matches over path-only matches
                        // If content is just whitespace/minimal, penalize the score
                        val score = if (content.length < 50) contentScore * 0.3 else contentScore

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
                                "bm25_score" to "%.3f".format(contentScore),
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

        val where = if (conditions.isEmpty()) "1=1" else conditions.joinToString(" AND ")

        val sql = """
            SELECT c.chunk_id, c.file_id, c.kind, c.content, c.summary,
                   c.token_count, f.abs_path, f.language
            FROM chunks c
            JOIN file_state f ON f.file_id = c.file_id
            WHERE $where
        """.trimIndent()

        return SqlBundle(sql, params)
    }

    private fun scoreKeywords(content: String, keywords: List<String>): Double {
        if (keywords.isEmpty() || content.isBlank()) return 0.0

        val contentLower = content.lowercase(Locale.US)

        // Check for exact phrase match - all keywords in order as separate words/tokens
        if (keywords.size >= 2) {
            var pos = 0
            val positions = mutableListOf<Int>()
            var foundAll = true

            for (keyword in keywords) {
                val nextPos = contentLower.indexOf(keyword, pos)
                if (nextPos == -1) {
                    foundAll = false
                    break
                }

                // Check if this is a word boundary match (not part of a larger word)
                val isWordStart = nextPos == 0 || !contentLower[nextPos - 1].isLetterOrDigit()
                val isWordEnd = nextPos + keyword.length >= contentLower.length ||
                               !contentLower[nextPos + keyword.length].isLetterOrDigit()

                if (isWordStart && isWordEnd) {
                    positions.add(nextPos)
                    pos = nextPos + keyword.length
                } else {
                    // Not a word boundary match, keep searching
                    pos = nextPos + 1
                }
            }

            // If all keywords found as separate words in order within 50 chars, strong phrase match
            if (positions.size == keywords.size) {
                val distance = (positions.last() + keywords.last().length) - positions.first()
                if (distance <= 50) {
                    return 0.95  // Excellent score for ordered phrase match
                }
            }
        }

        // Count keyword occurrences - more generous scoring
        var totalOccurrences = 0
        for (keyword in keywords) {
            var searchPos = 0
            while (true) {
                val pos = contentLower.indexOf(keyword, searchPos)
                if (pos == -1) break

                // Only count if it's a word boundary match
                val isWordStart = pos == 0 || !contentLower[pos - 1].isLetterOrDigit()
                val isWordEnd = pos + keyword.length >= contentLower.length ||
                               !contentLower[pos + keyword.length].isLetterOrDigit()

                if (isWordStart && isWordEnd) {
                    totalOccurrences++
                }
                searchPos = pos + 1
            }
        }

        if (totalOccurrences == 0) return 0.0

        // All keywords found (scattered): good score
        val keywordMatches = keywords.count { keyword ->
            // Check if keyword appears as a separate word
            var searchPos = 0
            var found = false
            while (true) {
                val pos = contentLower.indexOf(keyword, searchPos)
                if (pos == -1) break

                val isWordStart = pos == 0 || !contentLower[pos - 1].isLetterOrDigit()
                val isWordEnd = pos + keyword.length >= contentLower.length ||
                               !contentLower[pos + keyword.length].isLetterOrDigit()

                if (isWordStart && isWordEnd) {
                    found = true
                    break
                }
                searchPos = pos + 1
            }
            found
        }

        if (keywordMatches == keywords.size && keywords.size >= 2) {
            return 0.7  // Good score for all keywords present
        }

        // Partial matches: score based on occurrence count (less aggressive)
        // Avoid penalizing longer documents
        return (totalOccurrences.toDouble() / (keywords.size * 2)).coerceIn(0.0, 0.5)
    }

    companion object {
        private val DEFAULT_STOPWORDS = setOf(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from",
            "has", "in", "is", "it", "of", "on", "or", "that", "the", "to", "was", "will", "with"
        )
    }
}
