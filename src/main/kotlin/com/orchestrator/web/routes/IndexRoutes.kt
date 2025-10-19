package com.orchestrator.web.routes

import com.orchestrator.web.pages.IndexPage
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Index status routes for the Orchestrator dashboard
 */
fun Route.indexRoutes() {

    /**
     * GET /index - Index status page
     *
     * Displays:
     * - File index statistics
     * - File browser with search
     * - Index health status
     * - Admin actions (refresh, rebuild)
     */
    get("/index") {
        val html = IndexPage.render()

        call.response.headers.append("Cache-Control", "no-cache, no-store, must-revalidate")
        call.respondText(html, ContentType.Text.Html)
    }
}
