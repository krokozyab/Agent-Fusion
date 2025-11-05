package com.orchestrator.web.rendering

import kotlinx.html.FlowContent
import kotlinx.html.stream.createHTML
import kotlinx.html.div

object Fragment {
    fun render(block: FlowContent.() -> Unit): String =
        createHTML().div { block() }
}
