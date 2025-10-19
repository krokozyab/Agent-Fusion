package com.orchestrator.web.plugins

import com.orchestrator.config.ConfigLoader
import com.orchestrator.context.config.ContextConfig
import com.orchestrator.web.WebServerConfig
import com.orchestrator.web.routes.healthRoutes
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
            context = ContextConfig()
        )

    routing {
        get("/") {
            call.respondHtml {
                head { title { +"Agent Fusion Dashboard" } }
                body {
                    h1 { +"Agent Fusion Dashboard" }
                    p { +"Welcome to the orchestration control panel." }
                }
            }
        }

        healthRoutes(appConfig)

        get("/__internal/error") {
            error("Synthetic failure for monitoring tests.")
        }
    }
}

val ApplicationConfigKey = io.ktor.util.AttributeKey<ConfigLoader.ApplicationConfig>("web-app-config")
