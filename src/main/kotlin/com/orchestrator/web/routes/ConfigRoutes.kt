package com.orchestrator.web.routes

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.web.pages.ConfigPage
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Configuration display routes.
 */
fun Route.configRoutes(contextConfig: ContextConfig) {
    
    /**
     * GET /config - Configuration display page
     * 
     * Shows all active fusionagent.toml parameters in read-only format.
     */
    get("/config") {
        val html = ConfigPage.render(contextConfig)
        
        call.response.headers.append("Cache-Control", "no-cache, no-store, must-revalidate")
        call.respondText(html, ContentType.Text.Html)
    }
}
