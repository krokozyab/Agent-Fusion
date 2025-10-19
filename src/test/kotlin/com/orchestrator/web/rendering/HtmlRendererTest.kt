package com.orchestrator.web.rendering

import com.orchestrator.web.rendering.PageLayout.dashboardShell
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.html.p

class HtmlRendererTest {

    @Test
    fun `rendered page includes expected scaffolding`() {
        val html = HtmlRenderer.renderPage("Demo") {
            with(PageLayout) {
                dashboardShell("Demo") {
                    +"Hello"
                }
            }
        }

        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("<html lang=\"en\""))
        assertTrue(html.contains("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\""))
        assertTrue(html.contains("/static/js/htmx.min.js"))
        assertTrue(html.contains("/static/js/mermaid.min.js"))
        assertTrue(html.contains("Hello"))
    }

    @Test
    fun `fragments render with flow content`() {
        val fragment = Fragment.render {
            p { +"Partial" }
        }
        assertTrue(fragment.contains("<p>Partial</p>"))
    }
}
