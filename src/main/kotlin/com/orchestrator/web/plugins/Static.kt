package com.orchestrator.web.plugins

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

private const val ONE_YEAR_SECONDS = 365 * 24 * 60 * 60

internal fun Application.configureStaticContent() {
    routing {
        route("/static") {
            // JavaScript
            serveVersionedAsset(
                slug = "js/htmx.min.js",
                resourcePath = "static/js/htmx.min.js",
                contentType = ContentType.Application.JavaScript
            )
            serveVersionedAsset(
                slug = "js/mermaid.min.js",
                resourcePath = "static/js/mermaid.min.js",
                contentType = ContentType.Application.JavaScript
            )
            serveVersionedAsset(
                slug = "js/theme-toggle.js",
                resourcePath = "static/js/theme-toggle.js",
                contentType = ContentType.Application.JavaScript
            )
            serveVersionedAsset(
                slug = "js/navigation.js",
                resourcePath = "static/js/navigation.js",
                contentType = ContentType.Application.JavaScript
            )
            serveVersionedAsset(
                slug = "js/pagination.js",
                resourcePath = "static/js/pagination.js",
                contentType = ContentType.Application.JavaScript
            )
            serveVersionedAsset(
                slug = "js/mermaid.min.js",
                resourcePath = "static/js/mermaid.min.js",
                contentType = ContentType.Application.JavaScript
            )

            // CSS
            serveVersionedAsset(
                slug = "css/base.css",
                resourcePath = "static/css/base.css",
                contentType = ContentType.Text.CSS
            )
            serveVersionedAsset(
                slug = "css/orchestrator.css",
                resourcePath = "static/css/orchestrator.css",
                contentType = ContentType.Text.CSS
            )
            serveVersionedAsset(
                slug = "css/dark-mode.css",
                resourcePath = "static/css/dark-mode.css",
                contentType = ContentType.Text.CSS
            )
            serveVersionedAsset(
                slug = "css/styles.css",
                resourcePath = "static/css/styles.css",
                contentType = ContentType.Text.CSS
            )

            // Fallback for any other static resources
            staticResources("", "static")
        }
    }
}

private fun io.ktor.server.routing.Route.serveVersionedAsset(
    slug: String,
    resourcePath: String,
    contentType: ContentType
) {
    get(slug) {
        val resource = call.application::class.java.classLoader.getResource(resourcePath)
            ?: return@get call.respondBytes(byteArrayOf(), status = HttpStatusCode.NotFound)

        val bytes = resource.readBytes()
        call.response.header(HttpHeaders.CacheControl, "public, max-age=$ONE_YEAR_SECONDS")
        call.respondBytes(bytes, contentType)
    }
}
