package com.orchestrator.web.rendering

import java.time.Year
import kotlinx.html.BODY
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.footer
import kotlinx.html.header
import kotlinx.html.li
import kotlinx.html.main
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.ul
import kotlinx.html.unsafe

object PageLayout {
    fun BODY.dashboardShell(pageTitle: String, content: FlowContent.() -> Unit) {
        header {
            nav {
                ul {
                    li { a(href = "/tasks") { +"Tasks" } }
                    li { a(href = "/metrics") { +"Metrics" } }
                    li { a(href = "/index") { +"Index" } }
                }
            }
        }
        main {
            content()
        }
        footer {
            p { +"© ${Year.now()} Agent Fusion" }
        }
        script(src = "/static/js/filter-presets.js") {}
        script {
            unsafe {
                +"""
                    document.addEventListener('DOMContentLoaded', function () {
                        if (window.htmx) {
                            window.htmx.config.defaultSwapStyle = 'outerHTML';
                        }
                        if (window.mermaid) {
                            window.mermaid.initialize({ startOnLoad: true });
                        }
                    });
                """.trimIndent()
            }
        }
    }
}
