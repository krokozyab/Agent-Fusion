package com.orchestrator.web.plugins

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.matchContentType

internal fun Application.configureCompression() {
    install(Compression) {
        gzip {
            matchContentType(ContentType.Text.Html)
            matchContentType(ContentType.Text.Plain)
            matchContentType(ContentType.Application.Json)
            matchContentType(ContentType.Text.CSS)
            matchContentType(ContentType.Application.JavaScript)
        }
        deflate {
            matchContentType(ContentType.Text.Html)
            matchContentType(ContentType.Text.Plain)
            matchContentType(ContentType.Application.Json)
        }
    }
}
