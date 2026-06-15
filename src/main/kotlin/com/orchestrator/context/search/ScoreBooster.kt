package com.orchestrator.context.search

import com.orchestrator.context.config.BoostConfig
import com.orchestrator.context.domain.ContextSnippet

/**
 * Applies score boosts/penalties based on file paths, languages, file types,
 * file patterns, and chunk kinds.
 */
class ScoreBooster(private val config: BoostConfig) {

    fun applyBoosts(snippets: List<ContextSnippet>): List<ContextSnippet> {
        if (snippets.isEmpty()) return emptyList()

        return snippets.map { snippet ->
            val pathBoost = calculatePathBoost(snippet.filePath)
            val langBoost = calculateLanguageBoost(snippet.language)
            val fileTypePenalty = calculateFileTypePenalty(snippet.filePath)
            val patternPenalty = calculateFilePatternPenalty(snippet.filePath)
            val kindBoost = calculateChunkKindBoost(snippet.kind)
            val totalMultiplier = pathBoost * langBoost * fileTypePenalty * patternPenalty * kindBoost

            if (totalMultiplier != 1.0) {
                snippet.copy(score = (snippet.score * totalMultiplier).coerceIn(0.0, 1.0))
            } else {
                snippet
            }
        }
    }

    private fun calculatePathBoost(filePath: String): Double {
        if (config.pathPrefixes.isEmpty()) return 1.0
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

    private fun calculateFileTypePenalty(filePath: String): Double {
        if (config.fileTypePenalties.isEmpty()) return 1.0
        val ext = filePath.substringAfterLast('.', "")
        if (ext.isBlank()) return 1.0
        return config.fileTypePenalties[ext] ?: 1.0
    }

    private fun calculateFilePatternPenalty(filePath: String): Double {
        if (config.filePatternPenalties.isEmpty()) return 1.0
        val penalties = config.filePatternPenalties.mapNotNull { (pattern, penalty) ->
            if (matchesGlob(filePath, pattern)) penalty else null
        }
        return penalties.minOrNull() ?: 1.0
    }

    private fun calculateChunkKindBoost(kind: com.orchestrator.context.domain.ChunkKind): Double {
        if (config.chunkKindBoosts.isEmpty()) return 1.0
        return config.chunkKindBoosts[kind.name] ?: 1.0
    }

    private fun matchesGlob(path: String, pattern: String): Boolean {
        val normalizedPath = path.replace('\\', '/').removePrefix("/")
        val normalizedPattern = pattern.replace('\\', '/')

        // A leading "**/" means "zero or more path segments", so it becomes an optional prefix
        // ("foo" and "a/b/foo" both match "**/foo"). Decide this on the raw pattern BEFORE escaping:
        // the old code inspected the escaped regex and did substring(3) assuming the prefix was ".*/"
        // (3 chars). For patterns like "**.log" that chopped the backslash off "\." and left a bare
        // "." which matched any character (so "**.log" wrongly matched e.g. "catalog").
        val anchorPrefix: String
        val core: String
        if (normalizedPattern.startsWith("**/")) {
            anchorPrefix = "^(.*/)?"
            core = normalizedPattern.removePrefix("**/")
        } else {
            anchorPrefix = "^"
            core = normalizedPattern
        }

        val regex = anchorPrefix + core
            .replace(".", "\\.")
            .replace("**", "###DOUBLESTAR###")
            .replace("*", "[^/]*")
            .replace("###DOUBLESTAR###", ".*") + "$"

        return try {
            Regex(regex).matches(normalizedPath)
        } catch (_: Exception) {
            normalizedPath.contains(pattern.replace("**", "").replace("*", ""))
        }
    }
}
