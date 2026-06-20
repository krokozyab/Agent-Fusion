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
                        val summary = rs.getString("signature")
                        val name = rs.getString("name")
                        val qualified = rs.getString("qualified_name")
                        val symbolType = rs.getString("symbol_type") ?: "UNKNOWN"
                        val path = rs.getString("abs_path") ?: continue
                        // A symbol with no attached chunk (rare) still surfaces, using its signature
                        // or qualified name as text, instead of being silently dropped.
                        val content = rs.getString("content")
                            ?: summary
                            ?: qualified
                            ?: name
                            ?: continue
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
            val remaining = if (tokenBudget > 0) tokenBudget - tokensUsed else Int.MAX_VALUE
            if (tokenBudget > 0 && candidate.tokensNeeded > remaining) {
                // Do NOT silently drop a matching symbol because its chunk is large — for an exact
                // name lookup that oversized body is exactly the result wanted (a 1600-line PL/SQL
                // procedure is one ~20k-token chunk). Include the top match truncated to fit; once
                // the budget is spent there is no room for more.
                if (snippets.isEmpty() && remaining > 0) {
                    snippets += candidate.truncatedTo(remaining).toSnippet(id)
                }
                break
            }
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

        // Fallback: for natural-language queries with no code identifiers,
        // extract meaningful words for LIKE-based symbol search
        if (tokens.isEmpty()) {
            query.split(Regex("\\s+"))
                .map { it.trim().lowercase(Locale.US) }
                .filter { it.length >= 3 && it !in STOP_WORDS }
                .forEach { tokens += it }
        }

        return tokens.toList()
    }

    companion object {
        // Fetch this many times maxResults so downstream scoring/filtering has headroom, while
        // still bounding how much of the symbols table is materialized for a broad match.
        private const val FETCH_HEADROOM = 10
        private const val MAX_FETCH_LIMIT = 2000

        private val STOP_WORDS = setOf(
            "the", "a", "an", "is", "are", "was", "were", "in", "on", "at",
            "to", "for", "of", "with", "by", "from", "how", "what", "where",
            "when", "why", "which", "that", "this", "and", "or", "not", "but",
            "does", "has", "have", "can", "will", "all", "any", "some", "get",
            "set", "use", "using", "used", "about", "into", "than", "then"
        )
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
            val expr = scope.paths.map { "f.abs_path LIKE ?" }.joinToString(" OR ")
            conditions += "($expr)"
            scope.paths.forEach { params += "${it.trim()}%" }
        }

        if (scope.languages.isNotEmpty()) {
            // Match on the FILE's language as well as the symbol's: the file's language is the
            // authoritative one a query scopes by, and a symbol's stored language can lag behind it
            // (e.g. a symbol indexed before the extension→language mapping was added). Filtering on
            // s.language alone silently dropped every symbol in those files.
            val placeholders = scope.languages.joinToString(",") { "?" }
            conditions += "(s.language IN ($placeholders) OR f.language IN ($placeholders))"
            scope.languages.forEach { params += it }
            scope.languages.forEach { params += it }
        }

        val where = if (conditions.isEmpty()) "1=1" else conditions.joinToString(" AND ")
        // Bound the result set so a broad symbol-name LIKE doesn't materialize the whole symbols
        // table; keep headroom over maxResults for downstream scoring/filtering. Internal Int, safe to inline.
        val fetchLimit = (maxResults * FETCH_HEADROOM).coerceAtMost(MAX_FETCH_LIMIT)
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
                   COALESCE(c.chunk_id, cl.chunk_id) AS chunk_id,
                   COALESCE(c.kind, cl.kind) AS kind,
                   COALESCE(c.content, cl.content) AS content,
                   COALESCE(c.token_count, cl.token_count) AS token_count,
                   f.abs_path,
                   f.language AS file_language
            FROM symbols s
            JOIN file_state f ON f.file_id = s.file_id
            -- Prefer the chunk the indexer already attached (s.chunk_id); fall back to line containment
            -- only when it is missing, instead of relying solely on a fragile line join.
            LEFT JOIN chunks c ON c.chunk_id = s.chunk_id
            LEFT JOIN chunks cl ON s.chunk_id IS NULL AND cl.file_id = s.file_id
                AND cl.start_line <= COALESCE(s.start_line, cl.start_line)
                AND cl.end_line >= COALESCE(s.end_line, cl.end_line)
            WHERE $where
            LIMIT $fetchLimit
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
        val tokensNeeded: Int,
        val truncated: Boolean = false
    ) {
        /** A copy whose text is cut to ~maxTokens (head of the chunk — keeps the signature/start). */
        fun truncatedTo(maxTokens: Int): SymbolCandidate {
            val maxChars = (maxTokens.coerceAtLeast(1)) * 4
            if (text.length <= maxChars) return this
            return copy(text = text.substring(0, maxChars) + "\n… [truncated]", tokensNeeded = maxTokens, truncated = true)
        }

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
                chunkPath = null,
                parentChunkId = null,
                metadata = mapOf(
                    "provider" to providerId,
                    "sources" to providerId,
                    "symbol_id" to symbolId.toString(),
                    "symbol_type" to symbolType,
                    "token_estimate" to tokensNeeded.toString(),
                    "score" to "%.3f".format(score),
                    "truncated" to truncated.toString()
                )
            )
        }
    }
}
