package com.orchestrator.web.components

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class EmptyStateTest {

    @Test
    fun `renders empty state with all required elements`() {
        val config = EmptyState.Config(
            heading = "Test Heading",
            description = "Test description text",
            icon = "📋"
        )

        val html = EmptyState.render(config)

        // Check for container
        assertContains(html, "data-table__empty-state", ignoreCase = false)
        assertContains(html, "role=\"status\"", ignoreCase = false)
        assertContains(html, "aria-live=\"polite\"", ignoreCase = false)

        // Check for icon
        assertContains(html, "data-table__empty-icon", ignoreCase = false)
        assertContains(html, "📋", ignoreCase = false)
        assertContains(html, "aria-hidden=\"true\"", ignoreCase = false)

        // Check for heading
        assertContains(html, "data-table__empty-heading", ignoreCase = false)
        assertContains(html, "Test Heading", ignoreCase = false)

        // Check for description
        assertContains(html, "data-table__empty-description", ignoreCase = false)
        assertContains(html, "Test description text", ignoreCase = false)
    }

    @Test
    fun `does not render action button when not provided`() {
        val config = EmptyState.Config(
            heading = "No Tasks",
            description = "There are no tasks yet"
        )

        val html = EmptyState.render(config)

        // Should not have action button
        assertFalse(html.contains("data-table__empty-action"))
    }

    @Test
    fun `renders action button when provided`() {
        val config = EmptyState.Config(
            heading = "No Tasks",
            description = "There are no tasks yet",
            action = EmptyState.Action(
                label = "Create Task",
                hxGet = "/tasks/new",
                hxTarget = "#main",
                hxSwap = "innerHTML"
            )
        )

        val html = EmptyState.render(config)

        // Check for action button
        assertContains(html, "data-table__empty-action", ignoreCase = false)
        assertContains(html, "Create Task", ignoreCase = false)
        assertContains(html, "aria-label=\"Create Task\"", ignoreCase = false)
        assertContains(html, "hx-get=\"/tasks/new\"", ignoreCase = false)
        assertContains(html, "hx-target=\"#main\"", ignoreCase = false)
        assertContains(html, "hx-swap=\"innerHTML\"", ignoreCase = false)
    }

    @Test
    fun `renders action button without HTMX attributes when not provided`() {
        val config = EmptyState.Config(
            heading = "No Tasks",
            description = "There are no tasks yet",
            action = EmptyState.Action(
                label = "Refresh"
            )
        )

        val html = EmptyState.render(config)

        // Check for action button
        assertContains(html, "data-table__empty-action", ignoreCase = false)
        assertContains(html, "Refresh", ignoreCase = false)

        // Should not have HTMX attributes
        assertFalse(html.contains("hx-get"))
        assertFalse(html.contains("hx-target"))
    }

    @Test
    fun `preset noTasksFound renders correctly`() {
        val config = EmptyState.Presets.noTasksFound()

        val html = EmptyState.render(config)

        assertContains(html, "No tasks found", ignoreCase = false)
        assertContains(html, "There are no tasks in the system yet", ignoreCase = false)
        assertContains(html, "📋", ignoreCase = false)
        assertFalse(html.contains("data-table__empty-action"))
    }

    @Test
    fun `preset noTasksMatchingFilters renders correctly`() {
        val config = EmptyState.Presets.noTasksMatchingFilters()

        val html = EmptyState.render(config)

        assertContains(html, "No tasks match your filters", ignoreCase = false)
        assertContains(html, "Try adjusting your search", ignoreCase = false)
        assertContains(html, "🔍", ignoreCase = false)
        assertContains(html, "Clear filters", ignoreCase = false)
        assertContains(html, "hx-get=\"/tasks\"", ignoreCase = false)
    }

    @Test
    fun `preset noDataAvailable renders correctly`() {
        val config = EmptyState.Presets.noDataAvailable()

        val html = EmptyState.render(config)

        assertContains(html, "No data available", ignoreCase = false)
        assertContains(html, "not currently available", ignoreCase = false)
        assertContains(html, "📭", ignoreCase = false)
    }

    @Test
    fun `preset noFilesIndexed renders correctly`() {
        val config = EmptyState.Presets.noFilesIndexed()

        val html = EmptyState.render(config)

        assertContains(html, "No files indexed", ignoreCase = false)
        assertContains(html, "Start indexing", ignoreCase = false)
        assertContains(html, "📁", ignoreCase = false)
        assertContains(html, "Refresh index", ignoreCase = false)
        assertContains(html, "hx-get=\"/index/refresh\"", ignoreCase = false)
    }

    @Test
    fun `preset noMetricsAvailable renders correctly`() {
        val config = EmptyState.Presets.noMetricsAvailable()

        val html = EmptyState.render(config)

        assertContains(html, "No metrics available", ignoreCase = false)
        assertContains(html, "not yet available", ignoreCase = false)
        assertContains(html, "📊", ignoreCase = false)
    }

    @Test
    fun `uses default icon when not specified`() {
        val config = EmptyState.Config(
            heading = "Empty",
            description = "Nothing here"
        )

        val html = EmptyState.render(config)

        assertContains(html, "📋", ignoreCase = false)
    }

    @Test
    fun `uses custom icon when specified`() {
        val config = EmptyState.Config(
            heading = "Empty",
            description = "Nothing here",
            icon = "🎯"
        )

        val html = EmptyState.render(config)

        assertContains(html, "🎯", ignoreCase = false)
        assertFalse(html.contains("📋"))
    }

    @Test
    fun `includes all accessibility attributes`() {
        val config = EmptyState.Config(
            heading = "No Results",
            description = "Try a different search"
        )

        val html = EmptyState.render(config)

        // Container accessibility
        assertContains(html, "role=\"status\"", ignoreCase = false)
        assertContains(html, "aria-live=\"polite\"", ignoreCase = false)

        // Icon should be hidden from screen readers
        assertContains(html, "aria-hidden=\"true\"", ignoreCase = false)
    }

    @Test
    fun `button has proper aria-label`() {
        val config = EmptyState.Config(
            heading = "Empty",
            description = "No data",
            action = EmptyState.Action(
                label = "Load Data",
                hxGet = "/data"
            )
        )

        val html = EmptyState.render(config)

        assertContains(html, "aria-label=\"Load Data\"", ignoreCase = false)
    }

    @Test
    fun `handles special characters in text`() {
        val config = EmptyState.Config(
            heading = "No \"Special\" Tasks",
            description = "Nothing with <tags> & symbols"
        )

        val html = EmptyState.render(config)

        // HTML should be properly escaped
        assertContains(html, "No &quot;Special&quot; Tasks", ignoreCase = false)
        assertContains(html, "Nothing with &lt;tags&gt; &amp; symbols", ignoreCase = false)
    }
}
