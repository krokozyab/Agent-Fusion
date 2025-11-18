package com.orchestrator.context.providers

import com.orchestrator.context.domain.ContextSnippet
import com.orchestrator.context.neo4j.Neo4jQueryProvider

class ResultOrganizer(private val neo4jProvider: Neo4jQueryProvider?) {
    
    fun organizeHierarchically(snippets: List<ContextSnippet>): String {
        if (neo4jProvider == null) return formatFlat(snippets)
        
        val grouped = snippets.groupBy { snippet ->
            val filePath = snippet.metadata["file_path"] ?: ""
            val className = snippet.metadata["class_name"]
            val methodName = snippet.metadata["method_name"]
            Triple(filePath, className, methodName)
        }
        
        return buildString {
            grouped.forEach { (key, chunks) ->
                val (filePath, className, methodName) = key
                
                if (filePath.isNotEmpty()) {
                    appendLine("### $filePath")
                    appendLine()
                }
                
                if (className != null) {
                    appendLine("#### $className")
                    appendLine()
                }
                
                if (methodName != null) {
                    appendLine("##### $methodName()")
                    appendLine()
                }
                
                chunks.forEach { snippet ->
                    val finalScore = snippet.metadata["final_score"]?.toDoubleOrNull() ?: snippet.score
                    val rfrScore = snippet.metadata["rfr_score"]?.toDoubleOrNull() ?: snippet.score
                    val structuralScore = snippet.metadata["structural_score"]?.toDoubleOrNull() ?: 0.0
                    
                    appendLine("- **Chunk ${snippet.chunkId}** (score: %.3f)".format(finalScore))
                    appendLine("  - RFR: %.3f | Structural: %.3f".format(rfrScore, structuralScore))
                    
                    val preview = snippet.text.take(100).replace("\n", " ")
                    appendLine("  - $preview${if (snippet.text.length > 100) "..." else ""}")
                    appendLine()
                }
            }
        }
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
