package com.orchestrator.web.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.defaultheaders.DefaultHeaders

internal fun Application.configureSecurity() {
    install(DefaultHeaders) {
        header(
            "Content-Security-Policy",
            "default-src 'self'; " +
                    "script-src 'self' 'unsafe-inline' 'unsafe-eval'; " +
                    "style-src 'self' 'unsafe-inline'; " +
                    "img-src 'self' data:; " +
                    "font-src 'self' data:; " +
                    "connect-src 'self'"
        )
    }
}
