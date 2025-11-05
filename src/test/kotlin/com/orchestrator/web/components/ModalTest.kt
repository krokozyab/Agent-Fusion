package com.orchestrator.web.components

import kotlin.test.Test
import kotlin.test.assertContains
import kotlinx.html.div
import kotlinx.html.stream.createHTML

class ModalTest {

    @Test
    fun `test modal renders with title, body and footer`() {
        val html = createHTML().div {
            with(Modal) {
                render(Modal.Config(
                    id = "test-modal",
                    title = "Test Modal",
                    body = { div { +"Modal Body" } },
                    footer = { div { +"Modal Footer" } }
                ))
            }
        }

        assertContains(html, "id=\"test-modal\"")
        assertContains(html, "<h3 class=\"modal__title\">Test Modal</h3>")
        assertContains(html, "<div class=\"modal__body\">")
        assertContains(html, "Modal Body")
        assertContains(html, "<div class=\"modal__footer\">")
        assertContains(html, "Modal Footer")
    }

    @Test
    fun `test modal renders without footer`() {
        val html = createHTML().div {
            with(Modal) {
                render(Modal.Config(
                    id = "test-modal",
                    title = "Test Modal",
                    body = { div { +"Modal Body" } }
                ))
            }
        }

        assertContains(html, "id=\"test-modal\"")
        assertContains(html, "<h3 class=\"modal__title\">Test Modal</h3>")
        assertContains(html, "<div class=\"modal__body\">")
        assertContains(html, "Modal Body")
        kotlin.test.assertFalse(html.contains("<div class=\"modal__footer\">"))
    }
}
