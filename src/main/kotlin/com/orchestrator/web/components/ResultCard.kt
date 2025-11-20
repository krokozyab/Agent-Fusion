package com.orchestrator.web.components

import kotlinx.html.*

/**
 * Result card component for Context Explorer search results
 */
object ResultCard {

    data class Config(
        val chunkId: Long,
        val filePath: String,
        val startLine: Int,
        val score: Double,
        val kind: String,
        val snippet: String,
        val language: String,
        val tokenEstimate: Int,
        val providers: String
    )

    fun render(config: Config): String {
        return buildString {
            append("""
                <div class="card mb-3 result-card" data-chunk-id="${config.chunkId}">
                    <div class="card-body">
                        <div class="d-flex justify-content-between align-items-start mb-2">
                            <a href="#" class="text-decoration-none text-primary result-card__path" 
                               onclick="event.preventDefault()">
                                ${config.filePath}:${config.startLine}
                            </a>
                            <span class="badge bg-info result-card__score">${String.format("%.2f", config.score)}</span>
                        </div>
                        
                        <div class="mb-2">
                            <span class="badge bg-secondary">${config.kind.replace("_", " ")}</span>
                        </div>
                        
                        <pre class="result-card__snippet bg-light p-2 rounded"><code>${escapeHtml(config.snippet)}</code></pre>
                        
                        <div class="text-muted small mb-2">
                            ${config.language} | ${config.tokenEstimate} tokens | ${config.providers}
                        </div>
                        
                        <div class="btn-group btn-group-sm" role="group">
                            <button type="button" class="btn btn-outline-primary"
                                    data-open-file="${config.filePath.replace("\"", "&quot;")}"
                                    data-line-number="${config.startLine}"
                                    onclick="openFile('${config.filePath.replace("'", "\\'")}',${config.startLine})">
                                📂 Open
                            </button>
                        </div>
                    </div>
                </div>
            """.trimIndent())
        }
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
