package com.orchestrator.web.routes

import com.orchestrator.web.WebServerConfig
import com.orchestrator.web.configureWebApplication
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class HealthRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `health endpoint returns UP status`() = testApplication {
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
        val payload = json.parseToJsonElement(response.body<String>()).jsonObject
        assertEquals("UP", payload["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `health endpoint returns DOWN status`() = testApplication {
        application {
            installHealthChecker {
                HealthResponse(
                    status = "DOWN",
                    version = "test",
                    uptimeSeconds = 1,
                    durationMillis = 1,
                    checks = mapOf("database" to HealthCheckResult.down("failure"))
                )
            }
            configureWebApplication(WebServerConfig())
        }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val payload = json.parseToJsonElement(response.body<String>()).jsonObject
        assertEquals("DOWN", payload["status"]?.jsonPrimitive?.content)
    }
}
