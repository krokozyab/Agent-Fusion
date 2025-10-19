package com.orchestrator.web

import com.orchestrator.web.plugins.configureCompression
import com.orchestrator.web.plugins.configureCors
import com.orchestrator.web.plugins.configureMonitoring
import com.orchestrator.web.plugins.configureRouting
import com.orchestrator.web.plugins.configureStatusPages
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.sse.SSE

/**
 * Entry point for building the web dashboard Ktor application.
 */
object WebServer {
    fun create(config: WebServerConfig = WebServerConfig()): WebServerModule =
        WebServerModule(config) {
            configureWebApplication(config)
        }
}

fun Application.configureWebApplication(config: WebServerConfig) {
    install(SSE)
    configureMonitoring()
    configureCompression()
    configureCors(config)
    configureStatusPages()
    configureRouting(config)
}
