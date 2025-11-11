package com.orchestrator.web.components

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class ErrorStateTest {

    @Test
    fun `renders error state with all required elements`() {
        val config = ErrorState.Config(
            heading = "Error Occurred",
            message = "Something went wrong",
            retryUrl = "/retry"
        )

        val html = ErrorState.render(config)

        // Check for container
        assertContains(html, "data-table__error-state", ignoreCase = false)
        assertContains(html, "role=\"alert\"", ignoreCase = false)
        assertContains(html, "aria-live=\"assertive\"", ignoreCase = false)

        // Check for icon
        assertContains(html, "data-table__error-icon", ignoreCase = false)
        assertContains(html, "⚠️", ignoreCase = false)
        assertContains(html, "aria-hidden=\"true\"", ignoreCase = false)

        // Check for heading
        assertContains(html, "data-table__error-heading", ignoreCase = false)
        assertContains(html, "Error Occurred", ignoreCase = false)

        // Check for message
        assertContains(html, "data-table__error-message", ignoreCase = false)
        assertContains(html, "Something went wrong", ignoreCase = false)

        // Check for retry button
        assertContains(html, "data-table__error-retry", ignoreCase = false)
        assertContains(html, "Retry", ignoreCase = false)
        assertContains(html, "hx-get=\"/retry\"", ignoreCase = false)
    }

    @Test
    fun `does not render details when not provided`() {
        val config = ErrorState.Config(
            heading = "Error",
            message = "Failed",
            retryUrl = "/retry"
        )

        val html = ErrorState.render(config)

        // Should not have details section
        assertFalse(html.contains("data-table__error-details"))
    }

    @Test
    fun `renders details when provided`() {
        val config = ErrorState.Config(
            heading = "Error",
            message = "Failed to load",
            retryUrl = "/retry",
            details = "Error code: 500 - Internal Server Error"
        )

        val html = ErrorState.render(config)

        // Check for details section
        assertContains(html, "data-table__error-details", ignoreCase = false)
        assertContains(html, "Error code: 500 - Internal Server Error", ignoreCase = false)
    }

    @Test
    fun `includes HTMX attributes on retry button`() {
        val config = ErrorState.Config(
            heading = "Error",
            message = "Failed",
            retryUrl = "/tasks",
            hxTarget = "#tasks-table",
            hxSwap = "outerHTML",
            hxIndicator = "#loading"
        )

        val html = ErrorState.render(config)

        assertContains(html, "hx-get=\"/tasks\"", ignoreCase = false)
        assertContains(html, "hx-target=\"#tasks-table\"", ignoreCase = false)
        assertContains(html, "hx-swap=\"outerHTML\"", ignoreCase = false)
        assertContains(html, "hx-indicator=\"#loading\"", ignoreCase = false)
    }

    @Test
    fun `uses default icon when not specified`() {
        val config = ErrorState.Config(
            heading = "Error",
            message = "Failed",
            retryUrl = "/retry"
        )

        val html = ErrorState.render(config)

        assertContains(html, "⚠️", ignoreCase = false)
    }

    @Test
    fun `uses custom icon when specified`() {
        val config = ErrorState.Config(
            heading = "Error",
            message = "Failed",
            retryUrl = "/retry",
            icon = "❌"
        )

        val html = ErrorState.render(config)

        assertContains(html, "❌", ignoreCase = false)
        assertFalse(html.contains("⚠️"))
    }

    @Test
    fun `preset loadTasksFailed renders correctly`() {
        val config = ErrorState.Presets.loadTasksFailed()

        val html = ErrorState.render(config)

        assertContains(html, "Failed to load tasks", ignoreCase = false)
        assertContains(html, "error occurred while loading tasks", ignoreCase = false)
        assertContains(html, "❌", ignoreCase = false)
        assertContains(html, "hx-get=\"/tasks\"", ignoreCase = false)
        assertContains(html, "hx-target=\"#tasks-table\"", ignoreCase = false)
    }

    @Test
    fun `preset loadIndexFailed renders correctly`() {
        val config = ErrorState.Presets.loadIndexFailed()

        val html = ErrorState.render(config)

        assertContains(html, "Failed to load index status", ignoreCase = false)
        assertContains(html, "hx-get=\"/index\"", ignoreCase = false)
        assertContains(html, "hx-target=\"#index-status\"", ignoreCase = false)
    }

    @Test
    fun `preset loadMetricsFailed renders correctly`() {
        val config = ErrorState.Presets.loadMetricsFailed()

        val html = ErrorState.render(config)

        assertContains(html, "Failed to load metrics", ignoreCase = false)
        assertContains(html, "hx-get=\"/metrics\"", ignoreCase = false)
        assertContains(html, "hx-target=\"#metrics-dashboard\"", ignoreCase = false)
    }

    @Test
    fun `preset searchFailed renders correctly`() {
        val config = ErrorState.Presets.searchFailed("/search", "test query")

        val html = ErrorState.render(config)

        assertContains(html, "Search failed", ignoreCase = false)
        assertContains(html, "error occurred while searching", ignoreCase = false)
        assertContains(html, "hx-get=\"/search\"", ignoreCase = false)
        assertContains(html, "Query: &quot;test query&quot;", ignoreCase = false)
    }

    @Test
    fun `preset operationFailed renders correctly`() {
        val config = ErrorState.Presets.operationFailed(
            operation = "Delete",
            retryUrl = "/delete/123",
            errorMessage = "Resource not found"
        )

        val html = ErrorState.render(config)

        assertContains(html, "Delete failed", ignoreCase = false)
        assertContains(html, "error occurred during this operation", ignoreCase = false)
        assertContains(html, "hx-get=\"/delete/123\"", ignoreCase = false)
        assertContains(html, "Resource not found", ignoreCase = false)
    }

    @Test
    fun `preset networkError renders correctly`() {
        val config = ErrorState.Presets.networkError("/retry")

        val html = ErrorState.render(config)

        assertContains(html, "Network error", ignoreCase = false)
        assertContains(html, "Failed to connect to the server", ignoreCase = false)
        assertContains(html, "🔌", ignoreCase = false)
    }

    @Test
    fun `preset timeout renders correctly`() {
        val config = ErrorState.Presets.timeout("/retry")

        val html = ErrorState.render(config)

        assertContains(html, "Request timeout", ignoreCase = false)
        assertContains(html, "took too long", ignoreCase = false)
        assertContains(html, "⏱️", ignoreCase = false)
    }

    @Test
    fun `preset forbidden renders correctly`() {
        val config = ErrorState.Presets.forbidden("/retry")

        val html = ErrorState.render(config)

        assertContains(html, "Access denied", ignoreCase = false)
        assertContains(html, "don't have permission", ignoreCase = false)
        assertContains(html, "🚫", ignoreCase = false)
        assertContains(html, "Contact your administrator", ignoreCase = false)
    }

    @Test
    fun `preset notFound renders correctly`() {
        val config = ErrorState.Presets.notFound("Task", "/tasks")

        val html = ErrorState.render(config)

        assertContains(html, "Task not found", ignoreCase = false)
        assertContains(html, "could not be found", ignoreCase = false)
        assertContains(html, "may have been deleted", ignoreCase = false)
        assertContains(html, "🔍", ignoreCase = false)
    }

    @Test
    fun `preset serverError renders correctly`() {
        val config = ErrorState.Presets.serverError("/retry", 500)

        val html = ErrorState.render(config)

        assertContains(html, "Server error", ignoreCase = false)
        assertContains(html, "unexpected server error", ignoreCase = false)
        assertContains(html, "💥", ignoreCase = false)
        assertContains(html, "Error code: 500", ignoreCase = false)
    }

    @Test
    fun `includes all accessibility attributes`() {
        val config = ErrorState.Config(
            heading = "Error",
            message = "Failed",
            retryUrl = "/retry"
        )

        val html = ErrorState.render(config)

        // Container accessibility
        assertContains(html, "role=\"alert\"", ignoreCase = false)
        assertContains(html, "aria-live=\"assertive\"", ignoreCase = false)

        // Icon should be hidden from screen readers
        assertContains(html, "aria-hidden=\"true\"", ignoreCase = false)

        // Retry button should have aria-label
        assertContains(html, "aria-label=\"Retry operation\"", ignoreCase = false)
    }

    @Test
    fun `handles special characters in text`() {
        val config = ErrorState.Config(
            heading = "Error with \"quotes\"",
            message = "Failed to load <data> & process",
            retryUrl = "/retry",
            details = "Stack trace: <error>"
        )

        val html = ErrorState.render(config)

        // HTML should be properly escaped
        assertContains(html, "Error with &quot;quotes&quot;", ignoreCase = false)
        assertContains(html, "Failed to load &lt;data&gt; &amp; process", ignoreCase = false)
        assertContains(html, "Stack trace: &lt;error&gt;", ignoreCase = false)
    }

    @Test
    fun `uses default hxSwap when not specified`() {
        val config = ErrorState.Config(
            heading = "Error",
            message = "Failed",
            retryUrl = "/retry"
        )

        val html = ErrorState.render(config)

        assertContains(html, "hx-swap=\"outerHTML\"", ignoreCase = false)
    }

    @Test
    fun `does not include hxIndicator when not specified`() {
        val config = ErrorState.Config(
            heading = "Error",
            message = "Failed",
            retryUrl = "/retry"
        )

        val html = ErrorState.render(config)

        assertFalse(html.contains("hx-indicator"))
    }
}
