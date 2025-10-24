package com.orchestrator.web.components

import kotlin.test.Test
import kotlin.test.assertTrue
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

        assertTrue(html.contains("id=\"test-modal\""))
        assertTrue(html.contains("<h3 class=\"modal__title\">Test Modal</h3>"))
        assertTrue(html.contains("<div class=\"modal__body\"><div>Modal Body</div></div>"))
        assertTrue(html.contains("<div class=\"modal__footer\"><div>Modal Footer</div></div>"))
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

        assertTrue(html.contains("id=\"test-modal\""))
        assertTrue(html.contains("<h3 class=\"modal__title\">Test Modal</h3>"))
        assertTrue(html.contains("<div class=\"modal__body\"><div>Modal Body</div></div>"))
        assertTrue(!html.contains("<div class=\"modal__footer\">"))
    }
}