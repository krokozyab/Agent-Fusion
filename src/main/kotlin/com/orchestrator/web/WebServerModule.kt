package com.orchestrator.web

import com.orchestrator.utils.Logger
import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * Embedded Ktor server wrapper dedicated to the web dashboard.
 *
 * The module is intentionally small for now; feature routing and dependency wiring
 * will be layered on incrementally in later tasks.
 */
class WebServerModule(
    private val config: WebServerConfig,
    private val module: Application.() -> Unit
) {

    private val log = Logger.logger("com.orchestrator.web.WebServer")
    private var engine: EmbeddedServer<*, *>? = null
    private val browserLauncher = BrowserLauncher(config)

    fun start() {
        if (engine != null) {
            log.warn("Web server already running on ${config.host}:${config.port}")
            return
        }

        log.info("Starting web dashboard server on ${config.host}:${config.port}")
        val embedded = embeddedServer(
            factory = Netty,
            host = config.host,
            port = config.port,
            module = module
        )

        embedded.start(wait = false)
        engine = embedded
        log.info("Web dashboard server started")
        browserLauncher.launchIfEnabled()
    }

    fun stop(gracePeriodMillis: Long = 3_000, timeoutMillis: Long = 10_000) {
        val running = engine ?: return
        log.info("Stopping web dashboard server...")
        runCatching {
            running.stop(gracePeriodMillis, timeoutMillis)
            log.info("Web dashboard server stopped")
        }.onFailure { throwable ->
            log.error("Failed to stop web server cleanly: ${throwable.message}", throwable)
        }
        engine = null
    }
}
