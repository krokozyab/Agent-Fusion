package com.orchestrator.context.providers

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.domain.ContextScope
import com.orchestrator.context.domain.ContextSnippet
import com.orchestrator.context.domain.TokenBudget
import com.orchestrator.context.neo4j.Neo4jQueryProvider

class UnifiedContextProvider(
    private val hybridProvider: HybridContextProvider,
    private val neo4jProvider: Neo4jQueryProvider?,
    private val config: ContextConfig
) : ContextProvider {

    override val id: String = "unified"
    override val type: ContextProviderType = ContextProviderType.HYBRID

    private val neo4jEnabled = config.neo4j?.enabled == true
    private val structuralWeight = config.structuralWeight ?: 0.0

    override suspend fun getContext(
        query: String,
        scope: ContextScope,
        budget: TokenBudget
    ): List<ContextSnippet> {
        val baseResults = hybridProvider.getContext(query, scope, budget)
        
        if (!neo4jEnabled || neo4jProvider == null || structuralWeight == 0.0) {
            return baseResults
        }

        return enhanceWithStructuralScores(baseResults, query)
    }

    private suspend fun enhanceWithStructuralScores(
        snippets: List<ContextSnippet>,
        query: String
    ): List<ContextSnippet> {
        return snippets.map { snippet ->
            val rfrScore = snippet.score
            val structuralScore = calculateStructuralScore(snippet.chunkId, query)
            val finalScore = (1 - structuralWeight) * rfrScore + structuralWeight * structuralScore

            val metadata = snippet.metadata + mapOf(
                "rfr_score" to "%.4f".format(rfrScore),
                "structural_score" to "%.4f".format(structuralScore),
                "final_score" to "%.4f".format(finalScore),
                "structural_weight" to structuralWeight.toString()
            )

            snippet.copy(score = finalScore, metadata = metadata)
        }.sortedByDescending { it.score }
    }

    private suspend fun calculateStructuralScore(chunkId: Long, query: String): Double {
        if (neo4jProvider == null) return 0.0
        
        return try {
            val chunkIds = neo4jProvider.getChunkIdsForStructure(query, limit = 100)
            if (chunkId in chunkIds) 1.0 else 0.0
        } catch (e: Exception) {
            0.0
        }
    }
}
