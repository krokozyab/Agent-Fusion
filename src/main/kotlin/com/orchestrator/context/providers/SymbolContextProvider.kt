package com.orchestrator.context.providers

import com.orchestrator.context.domain.ContextScope
import com.orchestrator.context.domain.ContextSnippet
import com.orchestrator.context.domain.TokenBudget
import com.orchestrator.context.domain.ChunkKind
import com.orchestrator.context.storage.ContextDatabase
import java.util.Locale
import kotlin.math.max

class SymbolContextProvider(
    private val maxResults: Int = 50
) : ContextProvider {

    override val id: String = "symbol"
    override val type: ContextProviderType = ContextProviderType.SYMBOL

    override suspend fun getContext(
        query: String,
        scope: ContextScope,
        budget: TokenBudget
    ): List<ContextSnippet> {
        val tokens = extractSymbols(query)
        if (tokens.isEmpty()) return emptyList()

        val (sql, params) = buildQuery(tokens, scope)
        val snippets = mutableListOf<ContextSnippet>()
        var tokensUsed = 0
        val tokenBudget = budget.availableForSnippets.coerceAtLeast(0)

        ContextDatabase.withConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                params.forEachIndexed { index, param ->
                    ps.setString(index + 1, param)
                }
                ps.executeQuery().use { rs ->
                    while (rs.next() && snippets.size < maxResults) {
                        val chunkId = rs.getLong("chunk_id")
                        val symbolId = rs.getLong("symbol_id")
                        val content = rs.getString("content") ?: continue
                        val summary = rs.getString("signature")
                        val name = rs.getString("name")
                        val kind = rs.getString("symbol_type")
                        val path = rs.getString("rel_path") ?: continue
                        val language = rs.getString("file_language")
                        val chunkKind = rs.getString("kind")?.let { runCatching { ChunkKind.valueOf(it) }.getOrNull() } ?: ChunkKind.CODE_BLOCK
                        val tokenCount = rs.getInt("token_count").takeIf { !rs.wasNull() }
                        val tokenEstimate = tokenCount ?: content.length / 4

                        val weight = typeWeight(kind)
                        val relevance = keywordMatchScore(name, tokens)
                        val score = (weight + relevance) / 2.0

                        val tokensNeeded = max(1, tokenEstimate)
                        if (tokenBudget > 0 && tokensUsed + tokensNeeded > tokenBudget) {
                            continue
                        }
                        tokensUsed += tokensNeeded

                        val snippet = ContextSnippet(
                            chunkId = chunkId,
                            score = score.coerceIn(0.0, 1.0),
                            filePath = path,
                            label = summary ?: name,
                            kind = chunkKind,
                            text = content,
                            language = language,
                            offsets = rs.getInt("start_line").takeIf { !rs.wasNull() }?.let { start ->
                                val end = rs.getInt("end_line").takeIf { !rs.wasNull() } ?: start
                                start..end
                            },
                            metadata = mapOf(
                                "provider" to id,
                                "sources" to id,
                                "symbol_id" to symbolId.toString(),
                                "symbol_type" to kind,
                                "token_estimate" to tokensNeeded.toString()
                            )
                        )
                        snippets += snippet
                    }
                }
            }
        }

        return snippets
    }

    private fun extractSymbols(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val camelCaseRegex = Regex("""\b[A-Z][A-Za-z0-9]{2,}\b""")
        val snakeRegex = Regex("""\b[a-z]+_[a-z0-9_]+\b""")
        val qualifiedRegex = Regex("""\b([A-Za-z_][A-Za-z0-9_]*\.)+[A-Za-z_][A-Za-z0-9_]*\b""")
        val callRegex = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

        val tokens = linkedSetOf<String>()
        qualifiedRegex.findAll(query).forEach { tokens += it.value }
        camelCaseRegex.findAll(query).forEach { tokens += it.value }
        snakeRegex.findAll(query).forEach { tokens += it.value }
        callRegex.findAll(query).forEach { tokens += it.groupValues[1] }

        return tokens.toList()
    }

    private data class SqlBundle(val sql: String, val params: List<String>)

    private fun buildQuery(symbols: List<String>, scope: ContextScope): SqlBundle {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<String>()

        symbols.forEach { symbol ->
            conditions += "(LOWER(s.name) = ? OR LOWER(s.qualified_name) LIKE ?)"
            params += symbol.lowercase(Locale.US)
            params += "%${symbol.lowercase(Locale.US)}%"
        }

        if (scope.paths.isNotEmpty()) {
            val expr = scope.paths.map { "f.rel_path LIKE ?" }.joinToString(" OR ")
            conditions += "($expr)"
            scope.paths.forEach { params += "${it.trim()}%" }
        }

        if (scope.languages.isNotEmpty()) {
            val placeholders = scope.languages.joinToString(",") { "?" }
            conditions += "s.language IN ($placeholders)"
            scope.languages.forEach { params += it }
        }

        val where = if (conditions.isEmpty()) "1=1" else conditions.joinToString(" AND ")
        val sql = """
            SELECT s.symbol_id,
                   s.file_id,
                   s.symbol_type,
                   s.name,
                   s.qualified_name,
                   s.signature,
                   s.start_line,
                   s.end_line,
                   s.language,
                   c.chunk_id,
                   c.kind,
                   c.content,
                   c.token_count,
                   f.rel_path,
                   f.language AS file_language
            FROM symbols s
            JOIN file_state f ON f.file_id = s.file_id
            LEFT JOIN chunks c ON c.file_id = s.file_id AND c.start_line <= COALESCE(s.start_line, c.start_line) AND c.end_line >= COALESCE(s.end_line, c.end_line)
            WHERE $where
        """.trimIndent()

        return SqlBundle(sql, params)
    }

    private fun keywordMatchScore(name: String, symbols: List<String>): Double {
        val lowered = name.lowercase(Locale.US)
        return symbols.count { lowered.contains(it.lowercase(Locale.US)) }
            .coerceAtLeast(1)
            .toDouble()
            .coerceAtMost(5.0) / 5.0
    }

    private fun typeWeight(type: String): Double = when (type.uppercase(Locale.US)) {
        "CLASS", "INTERFACE", "ENUM" -> 1.0
        "METHOD", "FUNCTION" -> 0.85
        "PROPERTY", "VARIABLE", "CONSTANT" -> 0.75
        else -> 0.6
    }
}
