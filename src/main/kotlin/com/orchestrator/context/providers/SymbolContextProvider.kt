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
        val candidates = mutableListOf<SymbolCandidate>()

        ContextDatabase.withConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                params.forEachIndexed { index, param ->
                    ps.setString(index + 1, param)
                }
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val chunkId = rs.getLong("chunk_id")
                        val symbolId = rs.getLong("symbol_id")
                        val content = rs.getString("content") ?: continue
                        val summary = rs.getString("signature")
                        val name = rs.getString("name")
                        val qualified = rs.getString("qualified_name")
                        val symbolType = rs.getString("symbol_type") ?: "UNKNOWN"
                        val path = rs.getString("rel_path") ?: continue
                        val language = rs.getString("language") ?: rs.getString("file_language")
                        val chunkKind = rs.getString("kind")
                            ?.let { runCatching { ChunkKind.valueOf(it) }.getOrNull() }
                            ?: chunkKindForSymbol(symbolType, ChunkKind.CODE_BLOCK)
                        val tokenCount = rs.getInt("token_count").takeIf { !rs.wasNull() }
                        val tokenEstimate = tokenCount ?: content.length / 4

                        val tokensNeeded = max(1, tokenEstimate)
                        val weight = typeWeight(symbolType)
                        val relevance = keywordMatchScore(name ?: "", qualified, tokens)
                        val exactMatchBoost = if (isExactMatch(name, qualified, tokens)) 0.15 else 0.0
                        val combinedScore = (weight * 0.7 + relevance * 0.3 + exactMatchBoost)
                            .coerceIn(0.0, 1.0)
                        val offsets = rs.getInt("start_line").takeIf { !rs.wasNull() }?.let { start ->
                            val end = rs.getInt("end_line").takeIf { !rs.wasNull() } ?: start
                            start..end
                        }

                        val label = (summary ?: name).orEmpty()
                        if (label.isEmpty()) continue

                        candidates += SymbolCandidate(
                            chunkId = chunkId,
                            symbolId = symbolId,
                            label = label,
                            kind = chunkKind,
                            text = content,
                            language = language,
                            filePath = path,
                            offsets = offsets,
                            symbolType = symbolType,
                            score = combinedScore,
                            tokensNeeded = tokensNeeded
                        )
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            return emptyList()
        }

        val ordered = candidates
            .sortedWith(
                compareByDescending<SymbolCandidate> { it.score }
                    .thenBy { it.tokensNeeded }
                    .thenBy { it.label.lowercase(Locale.US) }
                    .thenBy { it.filePath }
            )

        val snippets = mutableListOf<ContextSnippet>()
        var tokensUsed = 0
        val tokenBudget = budget.availableForSnippets.coerceAtLeast(0)

        for (candidate in ordered) {
            if (snippets.size >= maxResults) break
            if (tokenBudget > 0 && tokensUsed + candidate.tokensNeeded > tokenBudget) continue

            tokensUsed += candidate.tokensNeeded
            snippets += candidate.toSnippet(id)
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

    private fun keywordMatchScore(name: String, qualified: String?, symbols: List<String>): Double {
        if (symbols.isEmpty()) return 0.0

        val loweredName = name.lowercase(Locale.US)
        val loweredQualified = qualified?.lowercase(Locale.US)

        var matches = 0
        for (symbol in symbols) {
            val candidate = symbol.lowercase(Locale.US)
            if (loweredName.contains(candidate) || (loweredQualified?.contains(candidate) == true)) {
                matches++
            }
        }

        if (matches == 0) return 0.0
        return matches.coerceAtMost(5).toDouble() / 5.0
    }

    private fun typeWeight(type: String): Double = when (type.uppercase(Locale.US)) {
        "CLASS", "INTERFACE", "ENUM" -> 1.0
        "METHOD", "FUNCTION" -> 0.85
        "PROPERTY", "VARIABLE", "CONSTANT" -> 0.75
        else -> 0.6
    }

    private fun chunkKindForSymbol(symbolType: String, fallback: ChunkKind): ChunkKind = when (symbolType.uppercase(Locale.US)) {
        "CLASS" -> ChunkKind.CODE_CLASS
        "INTERFACE" -> ChunkKind.CODE_INTERFACE
        "ENUM" -> ChunkKind.CODE_ENUM
        "METHOD" -> ChunkKind.CODE_METHOD
        "FUNCTION" -> ChunkKind.CODE_FUNCTION
        "CONSTRUCTOR" -> ChunkKind.CODE_CONSTRUCTOR
        "PROPERTY", "VARIABLE", "FIELD", "CONSTANT" -> ChunkKind.CODE_BLOCK
        else -> fallback
    }

    private fun isExactMatch(name: String?, qualified: String?, symbols: List<String>): Boolean {
        if (name.isNullOrBlank() && qualified.isNullOrBlank()) return false
        val loweredName = name?.lowercase(Locale.US)
        val loweredQualified = qualified?.lowercase(Locale.US)
        return symbols.any { symbol ->
            val candidate = symbol.lowercase(Locale.US)
            candidate == loweredName || candidate == loweredQualified
        }
    }

    private data class SymbolCandidate(
        val chunkId: Long,
        val symbolId: Long,
        val label: String,
        val kind: ChunkKind,
        val text: String,
        val language: String?,
        val filePath: String,
        val offsets: IntRange?,
        val symbolType: String,
        val score: Double,
        val tokensNeeded: Int
    ) {
        fun toSnippet(providerId: String): ContextSnippet {
            return ContextSnippet(
                chunkId = chunkId,
                score = score.coerceIn(0.0, 1.0),
                filePath = filePath,
                label = label,
                kind = kind,
                text = text,
                language = language,
                offsets = offsets,
                metadata = mapOf(
                    "provider" to providerId,
                    "sources" to providerId,
                    "symbol_id" to symbolId.toString(),
                    "symbol_type" to symbolType,
                    "token_estimate" to tokensNeeded.toString(),
                    "score" to "%.3f".format(score)
                )
            )
        }
    }
}
