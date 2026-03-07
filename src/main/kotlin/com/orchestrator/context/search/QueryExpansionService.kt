package com.orchestrator.context.search

import java.util.Locale

/**
 * Lightweight query expansion service for improving recall when terminology differs
 * between user queries and source code/document language.
 *
 * Supports:
 * - Synonym expansion
 * - HyDE-style pseudo-answer generation (purely local and template-based)
 */
class QueryExpansionService(
    private val synonyms: Map<String, List<String>> = emptyMap(),
    private val maxExpansionTerms: Int = 8
) {

    data class ExpansionResult(
        val originalQuery: String,
        val effectiveQuery: String,
        val synonymTerms: List<String> = emptyList(),
        val hydeDocument: String? = null
    )

    fun expand(
        query: String,
        synonymExpansionEnabled: Boolean,
        hydeEnabled: Boolean
    ): ExpansionResult {
        val original = query.trim()
        if (original.isEmpty()) return ExpansionResult(query, query)

        val baseTokens = tokenize(original)
        val synonymTerms = if (synonymExpansionEnabled) {
            expandSynonyms(baseTokens)
        } else {
            emptyList()
        }

        val hydeDocument = if (hydeEnabled) {
            buildHydeDocument(baseTokens, synonymTerms)
        } else {
            null
        }

        val effective = buildString {
            append(original)
            if (synonymTerms.isNotEmpty()) {
                append(' ')
                append(synonymTerms.joinToString(" "))
            }
            if (!hydeDocument.isNullOrBlank()) {
                append(' ')
                append(hydeDocument)
            }
        }.trim()

        return ExpansionResult(
            originalQuery = original,
            effectiveQuery = effective,
            synonymTerms = synonymTerms,
            hydeDocument = hydeDocument
        )
    }

    private fun expandSynonyms(baseTokens: List<String>): List<String> {
        if (baseTokens.isEmpty() || synonyms.isEmpty() || maxExpansionTerms <= 0) return emptyList()

        val normalizedBase = baseTokens.map { normalizeToken(it) }.toSet()
        if (normalizedBase.isEmpty()) return emptyList()

        val collected = linkedSetOf<String>()
        for (token in normalizedBase) {
            val mapped = synonyms[token].orEmpty()
            for (candidate in mapped) {
                val normalizedCandidate = normalizeToken(candidate)
                if (normalizedCandidate.isEmpty()) continue
                if (normalizedCandidate in normalizedBase) continue
                collected += normalizedCandidate
                if (collected.size >= maxExpansionTerms) {
                    return collected.toList()
                }
            }
        }
        return collected.toList()
    }

    private fun buildHydeDocument(baseTokens: List<String>, synonymTerms: List<String>): String? {
        val core = (baseTokens + synonymTerms)
            .map { normalizeToken(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(14)

        if (core.isEmpty()) return null

        val focus = core.joinToString(", ")
        return "Relevant implementation likely includes $focus with validation, integration points, and error handling."
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase(Locale.US)
            .split(Regex("[^a-z0-9_\\-]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }

    private fun normalizeToken(token: String): String =
        token.lowercase(Locale.US).replace(Regex("[^a-z0-9_\\-]"), "").trim()
}

