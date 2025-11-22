package com.orchestrator.context.providers

import com.orchestrator.context.domain.ContextSnippet

class ResultOrganizer {

    fun organizeHierarchically(snippets: List<ContextSnippet>): String {
        return formatHierarchical(snippets)
    }
    
    private fun formatHierarchical(snippets: List<ContextSnippet>): String = buildString {
        val byFile = snippets.groupBy { it.filePath }
        byFile.forEach { (file, group) ->
            appendLine("File: $file")
            val byParent = group.groupBy { it.metadata["parent_chunk_id"] }
            byParent[null]?.forEach { snippet ->
                appendLine("- [${snippet.kind}] ${snippet.label ?: "(no label)"} (${snippet.chunkPath ?: snippet.metadata["chunk_path"] ?: "no-path"}) score=%.3f".format(snippet.score))
            }
            byParent.filterKeys { it != null }.forEach { (parent, children) ->
                appendLine("  Parent: $parent")
                children.forEach { snippet ->
                    appendLine("  - [${snippet.kind}] ${snippet.label ?: "(no label)"} (${snippet.chunkPath ?: snippet.metadata["chunk_path"] ?: "no-path"}) score=%.3f".format(snippet.score))
                }
            }
            appendLine()
        }
    }
}
