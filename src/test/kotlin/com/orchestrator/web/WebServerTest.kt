package com.orchestrator.web

import com.orchestrator.web.routes.HealthCheckResult
import com.orchestrator.web.routes.HealthResponse
import com.orchestrator.web.routes.installHealthChecker
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.net.ServerSocket

class WebServerTest {

    @Test
    fun `health endpoint responds OK`() = testApplication {
        application {
            installHealthChecker {
                HealthResponse(
                    status = "UP",
                    version = "test",
                    uptimeSeconds = 1,
                    durationMillis = 1,
                    checks = mapOf("database" to HealthCheckResult.up())
                )
            }
            configureWebApplication(WebServerConfig())
        }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"UP\""))
    }

    @Test
    fun `cors headers applied for allowed origins`() = testApplication {
        val config = WebServerConfig(corsAllowedOrigins = listOf("http://localhost:3000"))
        application {
            installHealthChecker {
                HealthResponse("UP", "test", 1, 1, mapOf("database" to HealthCheckResult.up()))
            }
            configureWebApplication(config)
        }

        val response = client.options("/health") {
            header(HttpHeaders.Origin, "http://localhost:3000")
            header(HttpHeaders.AccessControlRequestMethod, "GET")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("http://localhost:3000", response.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `compression enables gzip responses`() = testApplication {
        application { configureWebApplication(WebServerConfig()) }

        val response = client.get("/") {
            header(HttpHeaders.AcceptEncoding, "gzip")
        }

        assertEquals("gzip", response.headers[HttpHeaders.ContentEncoding])
    }

    @Test
    fun `status pages handle not found and errors`() = testApplication {
        application { configureWebApplication(WebServerConfig()) }

        val notFound = client.get("/does-not-exist")
        assertEquals(HttpStatusCode.NotFound, notFound.status)
        assertTrue(notFound.bodyAsText().contains("Not Found", ignoreCase = true))

        val internal = client.get("/__internal/error")
        assertEquals(HttpStatusCode.InternalServerError, internal.status)
        assertTrue(internal.bodyAsText().contains("Internal Server Error", ignoreCase = true))
    }

    @Test
    fun `static assets served with cache headers`() = testApplication {
        application { configureWebApplication(WebServerConfig()) }

        val script = client.get("/static/js/htmx.min.js")
        assertEquals(HttpStatusCode.OK, script.status)
        assertEquals("application/javascript", script.headers[HttpHeaders.ContentType])
        assertEquals("public, max-age=31536000", script.headers[HttpHeaders.CacheControl])

        val css = client.get("/static/css/styles.css")
        assertEquals(HttpStatusCode.OK, css.status)
        assertTrue(css.headers[HttpHeaders.ContentType]?.startsWith("text/css") == true)
    }

    @Test
    fun `missing static asset returns 404`() = testApplication {
        application { configureWebApplication(WebServerConfig()) }

        val response = client.get("/static/js/unknown.js")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `web server module starts and stops`() {
        val port = findFreePort()
        val module = WebServer.create(WebServerConfig(port = port))
        module.start()
        module.stop()
    }

    private fun findFreePort(): Int =
        ServerSocket(0).use { it.localPort }
}
