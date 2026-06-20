package com.orchestrator.context.providers

import com.orchestrator.context.domain.ContextScope
import com.orchestrator.context.domain.ContextSnippet
import com.orchestrator.context.domain.TokenBudget
import com.orchestrator.context.storage.ContextDatabase
import com.orchestrator.utils.Logger
import java.util.Locale
import kotlin.math.max

class FullTextContextProvider(
    private val maxResults: Int = 50
) : ContextProvider {

    private val log = Logger.logger<FullTextContextProvider>()

    override val id: String = "full_text"
    override val type: ContextProviderType = ContextProviderType.FULL_TEXT

    override suspend fun getContext(
        query: String,
        scope: ContextScope,
        budget: TokenBudget
    ): List<ContextSnippet> {
        if (query.isBlank()) return emptyList()
        // Quick check: if no meaningful keywords survive filtering, skip the DB entirely.
        val keywords = extractKeywords(query)
        if (keywords.isEmpty()) return emptyList()

        // Try native BM25 first; fall back to LIKE if the FTS index is unavailable.
        val candidates = try {
            queryBm25(query, scope)
        } catch (e: Exception) {
            log.debug("BM25 query failed, falling back to LIKE: {}", e.message)
            queryLikeFallback(keywords, scope)
        }

        // Apply token budget + maxResults limit
        val tokenBudget = budget.availableForSnippets.coerceAtLeast(0)
        val result = mutableListOf<ContextSnippet>()
        var tokensUsed = 0

        for (snippet in candidates) {
            if (result.size >= maxResults) break
            val tokens = max(1, snippet.metadata["token_estimate"]?.toIntOrNull() ?: snippet.text.length / 4)
            val remaining = if (tokenBudget > 0) tokenBudget - tokensUsed else Int.MAX_VALUE
            if (tokenBudget > 0 && tokens > remaining) {
                // Don't drop a literal match just because the chunk is large (e.g. a whole PL/SQL
                // procedure body); include the top one truncated to fit so the term still surfaces.
                if (result.isEmpty() && remaining > 0) {
                    val maxChars = remaining * 4
                    result += snippet.copy(
                        text = snippet.text.take(maxChars) + "\n… [truncated]",
                        metadata = snippet.metadata + ("truncated" to "true")
                    )
                }
                continue
            }
            tokensUsed += tokens
            result += snippet
        }
        return result
    }

    // ── BM25 path (DuckDB FTS extension) ───────────────────────────────

    private fun queryBm25(query: String, scope: ContextScope): List<ContextSnippet> {
        // Rebuild the FTS index if incremental indexing left it stale, so watcher-added chunks
        // are searchable without waiting for a manual rebuild (DuckDB FTS has no incremental update).
        ContextDatabase.ensureFtsFresh()

        val scopeConditions = mutableListOf<String>()
        val scopeParams = mutableListOf<Any>()
        buildScopeConditions(scope, scopeConditions, scopeParams)

        val scopeWhere = if (scopeConditions.isEmpty()) "" else " AND ${scopeConditions.joinToString(" AND ")}"

        val sql = """
            SELECT c.chunk_id, c.file_id, c.kind, c.content, c.summary,
                   c.token_count, c.chunk_path, c.parent_chunk_id,
                   f.abs_path, f.language,
                   fts_main_chunks.match_bm25(c.chunk_id, ?) AS bm25_score
            FROM chunks c
            JOIN file_state f ON f.file_id = c.file_id
            WHERE fts_main_chunks.match_bm25(c.chunk_id, ?) IS NOT NULL
            $scopeWhere
            ORDER BY bm25_score DESC
            LIMIT ?
        """.trimIndent()

        val candidates = mutableListOf<ContextSnippet>()

        ContextDatabase.withConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                ps.setString(idx++, query)
                ps.setString(idx++, query)
                scopeParams.forEach { v ->
                    when (v) {
                        is String -> ps.setString(idx++, v)
                        is Int -> ps.setInt(idx++, v)
                        else -> ps.setObject(idx++, v)
                    }
                }
                ps.setInt(idx, maxResults)

                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        candidates += rowToSnippet(rs, "bm25_score")
                    }
                }
            }
        }

        // Normalize raw BM25 scores to [0, 1] range
        return normalizeBm25(candidates)
    }

    /**
     * Normalize raw BM25 scores (which are unbounded positive values) into (0, 1].
     *
     * Divide by the max rather than min-max: min-max always maps the lowest-scoring result to
     * exactly 0, which the downstream score threshold (~0.3) then discards — so the worst hit on
     * every page was silently dropped regardless of how good it actually was. Scaling by the max
     * preserves relative magnitude and keeps the lowest result positive.
     */
    private fun normalizeBm25(snippets: List<ContextSnippet>): List<ContextSnippet> {
        if (snippets.isEmpty()) return emptyList()
        val maxScore = snippets.maxOf { it.score }
        if (maxScore <= 0.0) return snippets.map { it.copy(score = 1.0) }
        return snippets.map { s ->
            s.copy(
                score = (s.score / maxScore).coerceIn(0.0, 1.0),
                metadata = s.metadata + ("raw_bm25" to "%.4f".format(Locale.US, s.score))
            )
        }
    }

    // ── LIKE fallback path ─────────────────────────────────────────────

    private fun queryLikeFallback(keywords: List<String>, scope: ContextScope): List<ContextSnippet> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        keywords.forEach { keyword ->
            conditions += "(LOWER(c.content) LIKE ? OR LOWER(c.summary) LIKE ?)"
            val token = "%$keyword%"
            params += token
            params += token
        }

        buildScopeConditions(scope, conditions, params)
        val where = conditions.joinToString(" AND ")

        // Bound the result set: without a LIMIT a common keyword (e.g. "test") would pull every
        // matching chunk's full content into memory. Fetch a headroom multiple of maxResults so the
        // downstream token-budget filtering still has candidates to choose from. The limit is an
        // internal Int, safe to inline.
        val fetchLimit = (maxResults * FETCH_HEADROOM).coerceAtMost(MAX_FETCH_LIMIT)
        val sql = """
            SELECT c.chunk_id, c.file_id, c.kind, c.content, c.summary,
                   c.token_count, c.chunk_path, c.parent_chunk_id,
                   f.abs_path, f.language
            FROM chunks c
            JOIN file_state f ON f.file_id = c.file_id
            WHERE $where
            LIMIT $fetchLimit
        """.trimIndent()

        val candidates = mutableListOf<ContextSnippet>()

        ContextDatabase.withConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                params.forEachIndexed { index, value ->
                    when (value) {
                        is String -> ps.setString(index + 1, value)
                        is Int -> ps.setInt(index + 1, value)
                        else -> ps.setObject(index + 1, value)
                    }
                }
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
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

                        // Simple keyword overlap scoring for fallback
                        val contentLower = content.lowercase(Locale.US)
                        val matched = keywords.count { contentLower.contains(it) }
                        val score = (matched.toDouble() / keywords.size).coerceIn(0.0, 1.0)
                        if (score <= 0.0) continue

                        val tokens = max(1, tokenEstimate)
                        candidates += ContextSnippet(
                            chunkId = chunkId,
                            score = score,
                            filePath = path,
                            label = summary,
                            kind = com.orchestrator.context.domain.ChunkKind.valueOf(kind),
                            text = content,
                            language = language,
                            offsets = null,
                            chunkPath = chunkPath,
                            parentChunkId = parentChunkId,
                            metadata = mapOf(
                                "provider" to id,
                                "sources" to id,
                                "bm25_score" to "%.3f".format(Locale.US, score),
                                "file_id" to fileId.toString(),
                                "token_estimate" to tokens.toString(),
                                "chunk_path" to (chunkPath ?: ""),
                                "parent_chunk_id" to (parentChunkId?.toString() ?: "")
                            )
                        )
                    }
                }
            }
        }

        return candidates.sortedByDescending { it.score }
    }

    // ── Shared helpers ─────────────────────────────────────────────────

    private fun rowToSnippet(rs: java.sql.ResultSet, scoreColumn: String): ContextSnippet {
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
        val rawScore = rs.getDouble(scoreColumn)
        val tokens = max(1, tokenEstimate)

        return ContextSnippet(
            chunkId = chunkId,
            score = rawScore,
            filePath = path,
            label = summary,
            kind = com.orchestrator.context.domain.ChunkKind.valueOf(kind),
            text = content,
            language = language,
            offsets = null,
            chunkPath = chunkPath,
            parentChunkId = parentChunkId,
            metadata = mapOf(
                "provider" to id,
                "sources" to id,
                "bm25_score" to "%.3f".format(Locale.US, rawScore),
                "file_id" to fileId.toString(),
                "token_estimate" to tokens.toString(),
                "chunk_path" to (chunkPath ?: ""),
                "parent_chunk_id" to (parentChunkId?.toString() ?: "")
            )
        )
    }

    private fun buildScopeConditions(scope: ContextScope, conditions: MutableList<String>, params: MutableList<Any>) {
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
    }

    private fun extractKeywords(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        return query.lowercase(Locale.US)
            .split(Regex("\\W+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .filterNot { STOPWORDS.contains(it) }
            .distinct()
    }

    companion object {
        // Fetch this many times maxResults so the downstream token-budget filter has headroom,
        // while still bounding how much is materialized for a broad keyword.
        private const val FETCH_HEADROOM = 10
        private const val MAX_FETCH_LIMIT = 2000

        private val STOPWORDS = setOf(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from",
            "has", "in", "is", "it", "of", "on", "or", "that", "the", "to", "was", "will", "with"
        )
    }
}
