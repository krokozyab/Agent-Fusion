package com.orchestrator.web.components

/**
 * Results container component with status bar and result cards
 */
object ResultsContainer {

    data class Config(
        val results: List<ResultCard.Config>,
        val totalHits: Int,
        val durationMs: Long,
        val providerStats: Map<String, Int>
    )

    fun render(config: Config): String {
        if (config.results.isEmpty()) {
            return renderEmpty()
        }

        val resultsHtml = config.results.joinToString("\n") { ResultCard.render(it) }
        val statusBar = renderStatusBar(config)

        return """
            <div class="results-list">
                $resultsHtml
            </div>
            $statusBar
        """.trimIndent()
    }

    fun renderEmpty(): String {
        return """
            <div class="card">
                <div class="card-body text-center py-5">
                    <div class="empty-icon mb-3">🔍</div>
                    <h5 class="text-muted">No results found</h5>
                    <p class="text-muted mb-4">Try adjusting your search query by following these tips:</p>
                    <ul class="list-unstyled text-start d-inline-block text-muted">
                        <li class="mb-2"><strong>Shorter queries:</strong> Use 2-5 keywords instead of full sentences</li>
                        <li class="mb-2"><strong>Specific terms:</strong> "JWT validation" is better than "authentication stuff"</li>
                        <li class="mb-2"><strong>Remove filters:</strong> Untick language/kind filters to broaden search</li>
                        <li class="mb-2"><strong>Check index:</strong> Verify files are indexed in Index Status</li>
                    </ul>
                    <details class="mt-4">
                        <summary class="text-primary" style="cursor: pointer;">📝 Query Tips & Examples</summary>
                        <div class="mt-3 text-start" style="max-width: 600px; margin: 0 auto;">
                            <h6 class="text-success">✓ Good Queries (Short & Specific)</h6>
                            <ul class="text-muted small">
                                <li>"authentication JWT token"</li>
                                <li>"database connection pool"</li>
                                <li>"error handling exception"</li>
                                <li>"HTTP request handler"</li>
                            </ul>
                            <h6 class="text-danger mt-3">✗ Bad Queries (Questions or Long)</h6>
                            <ul class="text-muted small">
                                <li>❌ "how does authentication work?"</li>
                                <li>❌ "show me all the authentication code"</li>
                                <li>❌ "where are errors from the client handled?"</li>
                                <li>❌ "what is the purpose of ignore patterns"</li>
                            </ul>
                            <p class="text-muted small mt-3">
                                <strong>Why It Matters:</strong> The search engine is optimized for short, keyword-based queries similar to grep.
                                It searches code, not answers questions naturally.
                            </p>
                        </div>
                    </details>
                </div>
            </div>
        """.trimIndent()
    }

    fun renderError(message: String, hint: String? = null): String {
        val hintHtml = if (hint != null) {
            "<p class=\"mb-0 mt-2\"><small>💡 <em>$hint</em></small></p>"
        } else {
            ""
        }
        
        return """
            <div class="alert alert-danger" role="alert">
                <div class="d-flex align-items-center mb-2">
                    <span class="me-2" style="font-size: 1.5rem;">⚠️</span>
                    <h5 class="alert-heading mb-0">Search Error</h5>
                </div>
                <p class="mb-0">$message</p>
                $hintHtml
            </div>
        """.trimIndent()
    }
    
    fun renderWarning(title: String, message: String): String {
        return """
            <div class="alert alert-warning alert-dismissible fade show" role="alert">
                <div class="d-flex align-items-center mb-2">
                    <span class="me-2" style="font-size: 1.5rem;">⚠️</span>
                    <strong>$title</strong>
                </div>
                <p class="mb-0">$message</p>
                <button type="button" class="btn-close" data-bs-dismiss="alert" aria-label="Close"></button>
            </div>
        """.trimIndent()
    }

    private fun renderStatusBar(config: Config): String {
        val providerBreakdown = config.providerStats.entries
            .joinToString(" | ") { "${it.key}: ${it.value}" }
        val displayedCount = config.results.size
        val totalInfo = if (displayedCount < config.totalHits) {
            "$displayedCount of ${config.totalHits} results"
        } else {
            "$displayedCount results"
        }

        return """
            <div class="card mt-3">
                <div class="card-body py-2">
                    <small class="text-muted">
                        ⏱️ ${config.durationMs}ms | 
                        $totalInfo | 
                        $providerBreakdown
                    </small>
                </div>
            </div>
        """.trimIndent()
    }
}
