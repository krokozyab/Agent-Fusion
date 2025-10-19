package com.orchestrator.web.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip

internal fun Application.configureCompression() {
    install(Compression) {
        gzip()
        deflate()
    }
}
