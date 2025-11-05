package com.orchestrator.web.components

import com.orchestrator.web.rendering.Fragment
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.h3
import kotlinx.html.p
import kotlinx.html.span

object ErrorState {
    fun render(config: Config): String =
        Fragment.render { errorState(config) }

    fun FlowContent.errorState(config: Config) {
        div(classes = "data-table__error-state") {
            attributes["role"] = "alert"
            attributes["aria-live"] = "assertive"

            // Error icon
            span(classes = "data-table__error-icon") {
                attributes["aria-hidden"] = "true"
                +config.icon
            }

            // Error heading
            h3(classes = "data-table__error-heading") {
                +config.heading
            }

            // Error message
            p(classes = "data-table__error-message") {
                +config.message
            }

            // Optional error details (for debugging)
            config.details?.let { details ->
                p(classes = "data-table__error-details") {
                    +details
                }
            }

            // Retry button with HTMX
            button(classes = "data-table__error-retry") {
                type = ButtonType.button
                attributes["aria-label"] = "Retry operation"

                attributes["hx-get"] = config.retryUrl
                config.hxTarget?.let { attributes["hx-target"] = it }
                config.hxSwap?.let { attributes["hx-swap"] = it }
                config.hxIndicator?.let { attributes["hx-indicator"] = it }

                +"Retry"
            }
        }
    }

    data class Config(
        val heading: String,
        val message: String,
        val retryUrl: String,
        val icon: String = "⚠️",
        val details: String? = null,
        val hxTarget: String? = null,
        val hxSwap: String? = "outerHTML",
        val hxIndicator: String? = null
    )

    // Common error state configurations
    object Presets {
        fun loadTasksFailed(retryUrl: String = "/tasks") = Config(
            heading = "Failed to load tasks",
            message = "An error occurred while loading tasks. Please try again.",
            retryUrl = retryUrl,
            icon = "❌",
            hxTarget = "#tasks-table",
            hxSwap = "outerHTML"
        )

        fun loadIndexFailed(retryUrl: String = "/index") = Config(
            heading = "Failed to load index status",
            message = "An error occurred while loading the index status. Please try again.",
            retryUrl = retryUrl,
            icon = "❌",
            hxTarget = "#index-status",
            hxSwap = "outerHTML"
        )

        fun loadMetricsFailed(retryUrl: String = "/metrics") = Config(
            heading = "Failed to load metrics",
            message = "An error occurred while loading metrics data. Please try again.",
            retryUrl = retryUrl,
            icon = "❌",
            hxTarget = "#metrics-dashboard",
            hxSwap = "outerHTML"
        )

        fun searchFailed(retryUrl: String, query: String? = null) = Config(
            heading = "Search failed",
            message = "An error occurred while searching. Please try again.",
            retryUrl = retryUrl,
            icon = "⚠️",
            details = query?.let { "Query: \"$it\"" },
            hxTarget = "#search-results",
            hxSwap = "outerHTML"
        )

        fun operationFailed(
            operation: String,
            retryUrl: String,
            errorMessage: String? = null
        ) = Config(
            heading = "$operation failed",
            message = "An error occurred during this operation. Please try again.",
            retryUrl = retryUrl,
            icon = "❌",
            details = errorMessage
        )

        fun networkError(retryUrl: String) = Config(
            heading = "Network error",
            message = "Failed to connect to the server. Please check your connection and try again.",
            retryUrl = retryUrl,
            icon = "🔌",
            hxTarget = "#main-content"
        )

        fun timeout(retryUrl: String) = Config(
            heading = "Request timeout",
            message = "The operation took too long to complete. Please try again.",
            retryUrl = retryUrl,
            icon = "⏱️",
            hxTarget = "#main-content"
        )

        fun forbidden(retryUrl: String) = Config(
            heading = "Access denied",
            message = "You don't have permission to access this resource.",
            retryUrl = retryUrl,
            icon = "🚫",
            details = "Contact your administrator if you believe this is an error."
        )

        fun notFound(resourceType: String, retryUrl: String) = Config(
            heading = "$resourceType not found",
            message = "The requested $resourceType could not be found. It may have been deleted.",
            retryUrl = retryUrl,
            icon = "🔍"
        )

        fun serverError(retryUrl: String, errorCode: Int? = null) = Config(
            heading = "Server error",
            message = "An unexpected server error occurred. Please try again later.",
            retryUrl = retryUrl,
            icon = "💥",
            details = errorCode?.let { "Error code: $it" }
        )
    }
}
