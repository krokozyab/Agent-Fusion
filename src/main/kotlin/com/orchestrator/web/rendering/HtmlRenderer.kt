package com.orchestrator.web.rendering

import kotlinx.html.BODY
import kotlinx.html.HEAD
import kotlinx.html.body
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.script
import kotlinx.html.stream.createHTML
import kotlinx.html.title

object HtmlRenderer {
    fun renderPage(
        title: String,
        buildBody: BODY.() -> Unit
    ): String {
        val document = createHTML().html {
            attributes["lang"] = "en"
            head { defaultHead(title) }
            body { buildBody() }
        }
        return "<!DOCTYPE html>\n$document"
    }

    private fun HEAD.defaultHead(titleText: String) {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title { +titleText }
        link(rel = "icon", href = "/static/images/favicon.svg", type = "image/svg+xml")
        link(rel = "alternate icon", href = "/static/images/favicon.ico", type = "image/x-icon")
        link(rel = "stylesheet", href = "/static/css/styles.css")
        script(src = "/static/js/htmx.min.js") {}
        script(src = "/static/js/mermaid.min.js") {}
    }
}
