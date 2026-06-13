package com.orchestrator.web.routes

import com.orchestrator.modules.context.ContextModule
import com.orchestrator.web.dto.toDTO
import com.orchestrator.web.pages.IndexStatusPage
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
        // The dashboard has no authentication, so a destructive rebuild must be protected against
        // CSRF: a page the user happens to visit could otherwise POST here and wipe the index.
        // 1) Reject cross-origin POSTs — browsers attach an Origin header on cross-origin requests;
        //    a mismatch with the request's own Host means the call did not come from the dashboard.
        // 2) Require the HX-Request marker that the real (HTMX) Rebuild button sends. A bare
        //    attacker <form> auto-post cannot set a custom header cross-origin without a CORS
        //    preflight (which the dashboard does not grant), so it is refused here.
        if (isCrossOrigin(call.request.headers["Origin"], call.request.headers["Host"]) ||
            call.request.headers["HX-Request"] != "true"
        ) {
            call.respondText(
                "Rebuild must be triggered from the dashboard.",
                status = HttpStatusCode.Forbidden
            )
            return@post
        }

        val operations = operationsFactory(application)
        val result = operations.triggerRebuild(confirm = true)
        if (!result.accepted) {
            // e.g. a rebuild is already running — surface the reason instead of a silent 204.
            call.respondText(result.message, status = HttpStatusCode.Conflict)
            return@post
        }
        // Return 204 No Content - SSE events will handle all DOM updates
        // Don't return HTML since we removed hx-target/hx-swap from buttons
        call.response.status(HttpStatusCode.NoContent)
        call.respondText("")
    }

}

/**
 * True if [origin] is present and its authority differs from the request [host] — i.e. the request
 * came from a different site. A missing Origin is treated as same-origin (browsers omit it for
 * same-origin GET navigations and some same-origin requests).
 */
private fun isCrossOrigin(origin: String?, host: String?): Boolean {
    if (origin == null || host == null) return false
    val originAuthority = origin.substringAfter("://", missingDelimiterValue = "")
    if (originAuthority.isEmpty()) return true // malformed Origin → treat as cross-origin
    return !originAuthority.equals(host, ignoreCase = true)
}

private fun Route.buildIndexStatusConfig(clock: Clock): IndexStatusPage.Config {
    val operations = IndexOperationsService.forApplication(application)
    val filesystemSnapshot = operations.filesystemSnapshot()
    val snapshotDto = ContextModule.getIndexStatus().toDTO(filesystemSnapshot)
    val actions = defaultAdminActions()

    return IndexStatusPage.Config(
        status = snapshotDto,
        actions = actions,
        generatedAt = Instant.now(clock)
    )
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
