package com.orchestrator.web.components

import kotlin.test.Test
import kotlin.test.assertTrue

class SearchFilterTest {

    @Test
    fun `renders search filter form with htmx wiring`() {
        val config = SearchFilter.Config(
            query = "agent",
            statuses = listOf(
                SearchFilter.Option(value = "", label = "All"),
                SearchFilter.Option(value = "open", label = "Open", selected = true)
            ),
            types = listOf(
                SearchFilter.Option("", "All"),
                SearchFilter.Option("consensus", "Consensus")
            ),
            agents = listOf(
                SearchFilter.Option("", "Any"),
                SearchFilter.Option("codex", "Codex")
            ),
            includeDateRange = true,
            fromDate = "2025-01-01",
            toDate = "2025-02-01",
            presets = listOf(
                SearchFilter.Preset(label = "Open", status = "open"),
                SearchFilter.Preset(label = "Mine", agent = "codex")
            )
        )

        val html = SearchFilter.render(config)

        assertTrue(html.contains("hx-get=\"/tasks/table\""))
        assertTrue(html.contains("hx-trigger=\"keyup changed delay:500ms\""))
        assertTrue(html.contains("hx-target=\"#tasks-table-body\""))
        assertTrue(html.contains("data-search-shortcut=\"/\""))
        assertTrue(html.contains("data-filter-clear=\"true\""))
        assertTrue(html.contains("data-filter-preset=\"Open\""))
        assertTrue(html.contains("name=\"from\""))
        assertTrue(html.contains("value=\"2025-01-01\""))
    }

    @Test
    fun `includes selections and maintains preset metadata`() {
        val config = SearchFilter.Config(
            statuses = listOf(
                SearchFilter.Option("", "All"),
                SearchFilter.Option("closed", "Closed", selected = true)
            ),
            types = listOf(SearchFilter.Option("simple", "Simple")),
            agents = listOf(SearchFilter.Option("claude", "Claude", selected = true)),
            presets = emptyList()
        )

        val html = SearchFilter.render(config)

        assertTrue(html.contains("name=\"status\""))
        assertTrue(html.contains("value=\"closed\""))
        assertTrue(html.contains("selected=\"selected\""))
        assertTrue(html.contains("data-filter-target=\"#task-filter-form\""))
    }
}
