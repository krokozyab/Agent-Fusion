package com.orchestrator.web.plugins
import com.orchestrator.web.WebServerConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.p
import kotlinx.html.title

@Suppress("UnusedParameter")
internal fun Application.configureRouting(config: WebServerConfig) {
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

        get("/health") {
            call.respondText("OK", ContentType.Text.Plain, HttpStatusCode.OK)
        }

        get("/__internal/error") {
            error("Synthetic failure for monitoring tests.")
        }
    }
}
