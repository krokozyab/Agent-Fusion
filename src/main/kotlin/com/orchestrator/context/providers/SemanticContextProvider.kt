package com.orchestrator.context.providers

import com.orchestrator.context.domain.ContextScope
import com.orchestrator.context.domain.ContextSnippet
import com.orchestrator.context.domain.TokenBudget
import com.orchestrator.context.embedding.Embedder
import com.orchestrator.context.search.MmrReranker
import com.orchestrator.context.search.VectorSearchEngine
import com.orchestrator.context.storage.ContextDatabase
import java.sql.Connection
import java.time.Instant
import kotlin.math.max

class SemanticContextProvider(
    private val embedder: Embedder,
    private val searchEngine: VectorSearchEngine,
    private val reranker: MmrReranker,
) : ContextProvider {

    constructor() : this(embedder = object : Embedder {
        override suspend fun embed(text: String): FloatArray = FloatArray(1) { 1f }
        override suspend fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { FloatArray(1) { 1f } }
        override fun getDimension(): Int = 1
        override fun getModel(): String = "noop"
    }, searchEngine = VectorSearchEngine(), reranker = MmrReranker())

    override val id: String = "semantic"
    override val type: ContextProviderType = ContextProviderType.SEMANTIC

    override suspend fun getContext(
        query: String,
        scope: ContextScope,
        budget: TokenBudget
    ): List<ContextSnippet> {
        if (query.isBlank()) return emptyList()

        val vector = embedder.embed(query)
        val k = max(1, minOf(64, budget.availableForSnippets.coerceAtLeast(64)))
        val model = embedder.getModel()
        val filters = VectorSearchEngine.Filters(
            languages = scope.languages,
            kinds = scope.kinds,
            paths = scope.paths.toSet()
        )
        val initial = searchEngine.search(vector, k, filters, model)
        val reranked = reranker.rerank(initial, lambda = 0.6, budget = budget)

        if (reranked.isEmpty()) return emptyList()

        val metadataCache = mutableMapOf<Long, FileMetadata>()

        val snippets = mutableListOf<ContextSnippet>()
        var tokensUsed = 0
        val tokenBudget = budget.availableForSnippets.coerceAtLeast(0)

        for (result in reranked) {
            val meta = metadataCache.getOrPut(result.chunk.fileId) {
                fetchFileMetadata(result.chunk.fileId)
            }

            val text = result.chunk.content
            val tokens = result.chunk.tokenEstimate ?: text.length / 4
            if (tokenBudget > 0 && tokensUsed + tokens > tokenBudget) {
                continue
            }
            tokensUsed += tokens

            val snippet = ContextSnippet(
                chunkId = result.chunk.id,
                score = result.score.toDouble().coerceIn(0.0, 1.0),
                filePath = meta.path,
                label = result.chunk.summary,
                kind = result.chunk.kind,
                text = text,
                language = meta.language,
                offsets = result.chunk.startLine?.let { start ->
                    val end = result.chunk.endLine ?: start
                    start..end
                },
                metadata = mapOf(
                    "provider" to id,
                    "sources" to id,
                    "model" to model,
                    "embedding_id" to result.embeddingId.toString(),
                    "score" to "%.3f".format(result.score),
                    "token_estimate" to tokens.toString()
                )
            )
            snippets += snippet
        }

        return snippets
    }

    private fun fetchFileMetadata(fileId: Long): FileMetadata =
        ContextDatabase.withConnection { conn ->
            conn.prepareStatement("SELECT abs_path, language FROM file_state WHERE file_id = ?").use { ps ->
                ps.setLong(1, fileId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        FileMetadata(
                            path = rs.getString("abs_path"),
                            language = rs.getString("language")
                        )
                    } else {
                        FileMetadata(path = "unknown", language = null)
                    }
                }
            }
        }

    private data class FileMetadata(val path: String, val language: String?)
}
