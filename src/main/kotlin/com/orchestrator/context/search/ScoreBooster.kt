package com.orchestrator.context.search

import com.orchestrator.context.config.BoostConfig
import com.orchestrator.context.domain.ContextSnippet

/**
 * Applies score boosts based on file paths and programming languages.
 */
class ScoreBooster(private val config: BoostConfig) {

    /**
     * Applies path and language boosts to snippets.
     * 
     * @param snippets Original snippets with base scores
     * @return Snippets with boosted scores
     */
    fun applyBoosts(snippets: List<ContextSnippet>): List<ContextSnippet> {
        if (snippets.isEmpty()) return emptyList()
        if (config.pathPrefixes.isEmpty() && config.languages.isEmpty()) return snippets

        return snippets.map { snippet ->
            val pathBoost = calculatePathBoost(snippet.filePath)
            val langBoost = calculateLanguageBoost(snippet.language)
            val totalBoost = pathBoost * langBoost
            
            if (totalBoost != 1.0) {
                snippet.copy(score = (snippet.score * totalBoost).coerceIn(0.0, 1.0))
            } else {
                snippet
            }
        }
    }

    private fun calculatePathBoost(filePath: String): Double {
        if (config.pathPrefixes.isEmpty()) return 1.0
        
        // Find longest matching prefix
        return config.pathPrefixes
            .filter { (prefix, _) -> filePath.contains(prefix) }
            .maxByOrNull { (prefix, _) -> prefix.length }
            ?.value
            ?: 1.0
    }

    private fun calculateLanguageBoost(language: String?): Double {
        if (language == null || config.languages.isEmpty()) return 1.0
        return config.languages[language] ?: 1.0
    }
}
