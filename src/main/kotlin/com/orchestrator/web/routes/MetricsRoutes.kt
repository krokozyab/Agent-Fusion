package com.orchestrator.web.routes

import com.orchestrator.web.pages.MetricsPage
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Metrics routes for the Orchestrator dashboard
 */
fun Route.metricsRoutes() {

    /**
     * GET /metrics - Metrics dashboard page
     *
     * Displays:
     * - Token usage statistics
     * - Task performance metrics
     * - Decision analytics
     * - Interactive charts
     * - Export functionality
     */
    get("/metrics") {
        val html = MetricsPage.render()

        call.response.headers.append("Cache-Control", "no-cache, no-store, must-revalidate")
        call.respondText(html, ContentType.Text.Html)
    }
}
