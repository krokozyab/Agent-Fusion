package com.orchestrator.web.plugins

import com.orchestrator.web.WebServerConfig
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS
import java.net.URI

internal fun Application.configureCors(config: WebServerConfig) {
    if (!config.corsEnabled) return

    install(CORS) {
        allowSameOrigin = true
        allowNonSimpleContentTypes = true
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Patch)

        config.getEffectiveCorsOrigins().forEach { origin ->
            val uri = runCatching { URI(origin) }.getOrNull()
            val scheme = uri?.scheme ?: "http"
            val authority = uri?.authority ?: uri?.host ?: origin
            allowHost(authority, schemes = listOf(scheme))
        }
    }
}
