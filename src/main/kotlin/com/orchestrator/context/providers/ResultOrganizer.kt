package com.orchestrator.context.providers

import com.orchestrator.context.domain.ContextSnippet

class ResultOrganizer {

    fun organizeHierarchically(snippets: List<ContextSnippet>): String {
        return formatFlat(snippets)
    }
    
    private fun formatFlat(snippets: List<ContextSnippet>): String {
        return buildString {
            snippets.forEach { snippet ->
                val filePath = snippet.metadata["file_path"] ?: "unknown"
                val score = snippet.score
                
                appendLine("- **$filePath** (score: %.3f)".format(score))
                val preview = snippet.text.take(100).replace("\n", " ")
                appendLine("  $preview${if (snippet.text.length > 100) "..." else ""}")
                appendLine()
            }
        }
    }
}
