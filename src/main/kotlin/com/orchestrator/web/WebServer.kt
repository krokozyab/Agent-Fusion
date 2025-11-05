package com.orchestrator.web

import com.orchestrator.web.plugins.configureCompression
import com.orchestrator.web.plugins.configureCors
import com.orchestrator.web.plugins.configureMonitoring
import com.orchestrator.web.plugins.configureRouting
import com.orchestrator.web.plugins.configureSecurity
import com.orchestrator.web.plugins.configureStatusPages
import com.orchestrator.web.plugins.configureStaticContent
import com.orchestrator.web.sse.installEventBusSubscriber
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.sse.SSE
import io.ktor.serialization.kotlinx.json.json

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
    install(ContentNegotiation) {
        json()
    }
    configureSecurity()
    configureMonitoring()
    configureCompression()
    configureCors(config)
    configureStatusPages()
    configureRouting(config)
    installEventBusSubscriber()
    configureStaticContent()
}
