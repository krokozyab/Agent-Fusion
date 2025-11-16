package com.orchestrator.web.routes

import com.orchestrator.mcp.tools.QueryContextTool
import com.orchestrator.web.components.ResultCard
import com.orchestrator.web.components.ResultsContainer
import com.orchestrator.web.pages.ExplorerPage
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * Context Explorer routes for semantic code search
 */
fun Route.explorerRoutes() {
    
    /**
     * GET /explorer - Context Explorer page
     * 
     * Displays the semantic code search interface with:
     * - Query input with filters
     * - Result cards with snippets
     * - Related graph visualization
     */
    get("/explorer") {
        val html = ExplorerPage.render()
        
        call.response.headers.append("Cache-Control", "no-cache, no-store, must-revalidate")
        call.respondText(html, ContentType.Text.Html)
    }
    
    /**
     * POST /api/context/query - Execute context search query
     */
    post("/api/context/query") {
        val params = call.receiveParameters()
        val query = params["query"] ?: return@post call.respondText(
            ResultsContainer.renderError(
                "Missing query parameter",
                "Enter a search query with at least 2 characters (e.g., 'auth JWT')"
            ),
            ContentType.Text.Html
        )
        
        if (query.isBlank()) {
            return@post call.respondText(
                ResultsContainer.renderError(
                    "Query cannot be empty",
                    "Enter a search query with at least 2 characters (e.g., 'database connection')"
                ),
                ContentType.Text.Html
            )
        }
        
        if (query.length < 2) {
            return@post call.respondText(
                ResultsContainer.renderError(
                    "Query too short",
                    "Use at least 2 characters. Try short keywords like 'JWT token' or 'error handler'"
                ),
                ContentType.Text.Html
            )
        }
        
        // Parse filter parameters
        val paths = params["paths"]?.split("\n")?.filter { it.isNotBlank() }
        val excludePatterns = params["excludePatterns"]?.split("\n")?.filter { it.isNotBlank() }
        val languages = params.getAll("languages")
        val kinds = params.getAll("kinds")
        val k = params["k"]?.toIntOrNull() ?: 20
        val maxTokens = params["maxTokens"]?.toIntOrNull() ?: 6000
        
        // Execute query with error handling
        val startTime = System.currentTimeMillis()
        val tool = QueryContextTool()
        
        val result = try {
            tool.execute(
                QueryContextTool.Params(
                    query = query,
                    k = k,
                    maxTokens = maxTokens,
                    paths = paths,
                    languages = languages,
                    kinds = kinds,
                    excludePatterns = excludePatterns
                )
            )
        } catch (e: Exception) {
            return@post call.respondText(
                ResultsContainer.renderError(
                    "Query execution failed: ${e.message ?: "Unknown error"}",
                    "Check that the context index is ready and try again"
                ),
                ContentType.Text.Html
            )
        }
        
        val durationMs = System.currentTimeMillis() - startTime
        
        // Map to ResultCard configs and limit to k
        val resultCards = result.hits.take(k).map { hit ->
            ResultCard.Config(
                chunkId = hit.chunkId,
                filePath = hit.filePath,
                startLine = hit.startLine ?: 1,
                score = hit.score,
                kind = hit.kind,
                snippet = hit.text,
                language = hit.language ?: "unknown",
                tokenEstimate = hit.metadata["token_estimate"]?.toIntOrNull() ?: (hit.text.length / 4),
                providers = hit.metadata["sources"] ?: "unknown"
            )
        }
        
        // Extract provider stats
        @Suppress("UNCHECKED_CAST")
        val providers = result.metadata["providers"] as? Map<String, Map<String, Any>> ?: emptyMap()
        val providerStats = providers.mapValues { (_, stats) ->
            stats["snippets"] as? Int ?: 0
        }
        
        // Render results with warnings for slow queries
        val html = if (resultCards.isEmpty()) {
            ResultsContainer.renderEmpty()
        } else {
            val resultsHtml = ResultsContainer.render(
                ResultsContainer.Config(
                    results = resultCards,
                    totalHits = result.metadata["totalHits"] as? Int ?: resultCards.size,
                    durationMs = durationMs,
                    providerStats = providerStats
                )
            )
            
            // Add warning for slow queries
            val warningHtml = if (durationMs > 5000) {
                ResultsContainer.renderWarning(
                    "Slow Query",
                    "Query took ${durationMs}ms. Consider reducing max results (k) or adding more specific filters."
                )
            } else ""
            
            warningHtml + resultsHtml
        }
        
        call.respondText(html, ContentType.Text.Html)
    }
    

    /**
     * GET /api/files/content - Get file content for modal viewer
     */
    get("/api/files/content") {
        val path = call.request.queryParameters["path"] ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "Missing path parameter", "hint" to "Provide a valid file path")
        )
        
        val file = java.io.File(path)
        if (!file.exists() || !file.isFile) {
            return@get call.respond(
                HttpStatusCode.NotFound,
                mapOf("error" to "File not found: $path", "hint" to "The file may have been moved or deleted")
            )
        }
        
        try {
            val content = file.readText()
            call.respond(mapOf("content" to content, "path" to path))
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to read file", "hint" to "The file may be too large or inaccessible")
            )
        }
    }
}
