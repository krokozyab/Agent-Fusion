package com.orchestrator.web.routes

import com.orchestrator.config.ConfigLoader
import com.orchestrator.core.EventBus
import com.orchestrator.storage.Database
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.application
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import java.lang.management.ManagementFactory

fun interface HealthChecker {
    suspend fun check(): HealthResponse
}

private val HealthCheckerKey = io.ktor.util.AttributeKey<HealthChecker>("web-health-checker")

fun Route.healthRoutes(appConfig: ConfigLoader.ApplicationConfig) {
    val checker = if (application.attributes.contains(HealthCheckerKey)) {
        application.attributes[HealthCheckerKey]
    } else {
        HealthChecker { defaultHealthReport(appConfig) }
    }

    get("/health") {
        val response = checker.check()
        val status = if (response.status == "UP") HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
        call.respond(status, response)
    }
}

fun Application.installHealthChecker(provider: suspend () -> HealthResponse) {
    attributes.put(HealthCheckerKey, HealthChecker { provider() })
}

private suspend fun defaultHealthReport(appConfig: ConfigLoader.ApplicationConfig): HealthResponse {
    val started = System.currentTimeMillis()
    val checks = linkedMapOf<String, HealthCheckResult>()

    val dbHealthy = runCatching { Database.isHealthy() }.getOrElse { false }
    checks["database"] = if (dbHealthy) HealthCheckResult.up() else HealthCheckResult.down("Unable to reach DuckDB")

    val busHealthy = EventBus.global.scope.isActive
    checks["eventBus"] = if (busHealthy) HealthCheckResult.up("${EventBus.global.channels.size} channels")
    else HealthCheckResult.down("EventBus scope cancelled")

    checks["mcpServer"] = HealthCheckResult.up()
    checks["webServer"] = HealthCheckResult.up("HTTP ${appConfig.orchestrator.server.transport}")

    val allHealthy = checks.values.all { it.status == "UP" }
    val runtime = ManagementFactory.getRuntimeMXBean()

    return HealthResponse(
        status = if (allHealthy) "UP" else "DOWN",
        version = appConfig.orchestrator.server.transport.name.lowercase(),
        uptimeSeconds = runtime.uptime / 1000,
        durationMillis = System.currentTimeMillis() - started,
        checks = checks
    )
}

@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
    val uptimeSeconds: Long,
    val durationMillis: Long,
    val checks: Map<String, HealthCheckResult>
)

@Serializable
data class HealthCheckResult(
    val status: String,
    val details: String? = null
) {
    companion object {
        fun up(details: String? = null) = HealthCheckResult("UP", details)
        fun down(details: String? = null) = HealthCheckResult("DOWN", details)
    }
}
