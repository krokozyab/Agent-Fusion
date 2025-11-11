package com.orchestrator.web.plugins

import com.orchestrator.utils.Logger
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.statuspages.StatusPages
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.p
import kotlinx.html.title

private val log = Logger.logger("com.orchestrator.web.StatusPages")

internal fun Application.configureStatusPages() {
    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, status ->
            call.respondHtml(status) {
                head { title { +"Page Not Found" } }
                body {
                    h1 { +"404 - Not Found" }
                    p { +"The resource you requested could not be located." }
                }
            }
        }

        exception<Throwable> { call, cause ->
            log.error("Unhandled exception in web server: ${cause.message}", cause)
            call.respondHtml(HttpStatusCode.InternalServerError) {
                head { title { +"Internal Server Error" } }
                body {
                    h1 { +"500 - Internal Server Error" }
                    p { +"An unexpected error occurred while processing your request." }
                }
            }
        }
    }
}
