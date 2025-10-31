package com.orchestrator.web.routes

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.config.ProviderConfig
import com.orchestrator.context.providers.ContextProviderRegistry
import com.orchestrator.modules.context.ContextModule
import com.orchestrator.web.dto.toDTO
import com.orchestrator.web.pages.IndexStatusPage
import com.orchestrator.web.plugins.ApplicationConfigKey
import com.orchestrator.web.services.IndexOperationsService
import com.orchestrator.web.services.OperationTriggerResult
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.application
import java.time.Clock
import java.time.Instant
import java.util.Locale

/**
 * Index status routes for the Orchestrator dashboard.
 */
fun Route.indexRoutes(
    clock: Clock = Clock.systemUTC(),
    operationsFactory: (Application) -> IndexOperationsService = { IndexOperationsService.forApplication(it) }
) {
    get("/index") {
        val pageConfig = buildIndexStatusConfig(clock)
        val html = IndexStatusPage.render(pageConfig)

        call.response.headers.append("Cache-Control", "no-cache, no-store, must-revalidate")
        call.respondText(html, ContentType.Text.Html)
    }

    post("/index/rebuild") {
        val operations = operationsFactory(application)
        val result = operations.triggerRebuild(confirm = true)
        // Return 204 No Content - SSE events will handle all DOM updates
        // Don't return HTML since we removed hx-target/hx-swap from buttons
        call.response.status(HttpStatusCode.NoContent)
        call.respondText("")
    }

}

private fun Route.buildIndexStatusConfig(clock: Clock): IndexStatusPage.Config {
    val operations = IndexOperationsService.forApplication(application)
    val filesystemSnapshot = operations.filesystemSnapshot()
    val snapshotDto = ContextModule.getIndexStatus().toDTO(filesystemSnapshot)
    val providerStatuses = buildProviderStatuses()
    val actions = defaultAdminActions()

    return IndexStatusPage.Config(
        status = snapshotDto,
        providers = providerStatuses,
        actions = actions,
        generatedAt = Instant.now(clock)
    )
}

private fun Route.buildProviderStatuses(): List<IndexStatusPage.ProviderStatus> {
    val appConfig = application.attributes.getOrNull(ApplicationConfigKey)
    val contextConfig = appConfig?.context ?: ContextConfig()

    val providerConfigs = contextConfig.providers.ifEmpty {
        // Fallback to discovered providers if config is empty
        ContextProviderRegistry.getAllProviders().associate { provider ->
            provider.id to ProviderConfig(enabled = true, weight = 1.0)
        }
    }

    if (providerConfigs.isEmpty()) {
        return emptyList()
    }

    return providerConfigs.entries.map { (rawId, cfg) ->
        val providerId = rawId.lowercase(Locale.US)
        val provider = ContextProviderRegistry.getProvider(providerId)
        val providerType = provider?.type?.name?.lowercase(Locale.US)
        val health = when {
            !cfg.enabled -> IndexStatusPage.ProviderHealth.DISABLED
            provider == null -> IndexStatusPage.ProviderHealth.UNAVAILABLE
            else -> IndexStatusPage.ProviderHealth.HEALTHY
        }

        IndexStatusPage.ProviderStatus(
            id = providerId,
            displayName = formatProviderName(rawId),
            type = providerType,
            weight = cfg.weight,
            health = health
        )
    }.sortedBy { it.displayName.lowercase(Locale.US) }
}

private fun defaultAdminActions(): List<IndexStatusPage.AdminAction> = listOf(
    IndexStatusPage.AdminAction(
        id = "rebuild",
        label = "Rebuild Index",
        description = "Recreate the entire context index from scratch.",
        hxPost = "/index/rebuild",
        icon = "\uD83D\uDD28",
        confirm = "Rebuild will clear and re-index all data. Continue?"
    )
)

private fun formatProviderName(id: String): String {
    return id.split('-', '_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            token.lowercase(Locale.US).replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase(Locale.US) else ch.toString()
            }
        }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondWithIndexFragment(
    config: IndexStatusPage.Config,
    result: OperationTriggerResult
) {
    val statusCode = if (result.accepted) HttpStatusCode.OK else HttpStatusCode.Conflict
    val html = IndexStatusPage.renderContainer(config)

    response.headers.append("Cache-Control", "no-cache, no-store, must-revalidate")
    response.headers.append("Vary", "HX-Request")
    respondText(text = html, contentType = ContentType.Text.Html, status = statusCode)
}
