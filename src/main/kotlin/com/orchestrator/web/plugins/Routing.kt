package com.orchestrator.web.plugins

import com.orchestrator.config.ConfigLoader
import com.orchestrator.context.config.ContextConfig
import com.orchestrator.modules.context.ContextModule
import com.orchestrator.web.WebServerConfig
import com.orchestrator.web.routes.fileRoutes
import com.orchestrator.web.routes.healthRoutes
import com.orchestrator.web.routes.homeRoutes
import com.orchestrator.web.routes.indexRoutes
import com.orchestrator.web.routes.metricsRoutes
import com.orchestrator.web.routes.sseRoutes
import com.orchestrator.web.routes.taskRoutes
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.p
import kotlinx.html.title

@kotlin.Suppress("UnusedParameter")
internal fun Application.configureRouting(config: WebServerConfig) {
    val appConfig = attributes.getOrNull(ApplicationConfigKey)
        ?: ConfigLoader.ApplicationConfig(
            orchestrator = ConfigLoader.loadHocon(),
            web = config,
            agents = emptyList(),
            context = runCatching { ContextModule.configuration() }.getOrElse { ContextConfig() }
        )

    routing {
        // Home page routes
        homeRoutes()

        // Health check routes
        healthRoutes(appConfig)

        // Task management routes
        taskRoutes()

        // File browser routes
        fileRoutes()

        // Index status routes
        indexRoutes()

        // Metrics dashboard routes
        metricsRoutes()

        // Server-Sent Events routes
        sseRoutes()

        // Internal error endpoint for testing
        get("/__internal/error") {
            error("Synthetic failure for monitoring tests.")
        }
    }
}

val ApplicationConfigKey = io.ktor.util.AttributeKey<ConfigLoader.ApplicationConfig>("web-app-config")
