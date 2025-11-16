package com.orchestrator.web.pages

import com.orchestrator.web.components.Navigation
import kotlinx.html.*
import kotlinx.html.stream.createHTML

/**
 * Context Explorer page for semantic code search.
 */
object ExplorerPage {

    fun render(): String {
        val htmlContent = createHTML().html {
            pageLayout()
        }
        return "<!DOCTYPE html>\n$htmlContent"
    }

    private fun HTML.pageLayout() {
        val navConfig = Navigation.Config(
            title = "Orchestrator",
            titleHref = "/",
            enableHtmxBoost = false,
            links = listOf(
                Navigation.Link("Home", "/", icon = "🏠"),
                Navigation.Link("Tasks", "/tasks", icon = "📋"),
                Navigation.Link("Index Status", "/index", icon = "📊"),
                Navigation.Link("Files", "/files", icon = "📂"),
                Navigation.Link("Context Explorer", "/explorer", active = true, icon = "🔍", disableBoost = true),
                Navigation.Link("Config", "/config", icon = "⚙️"),
                Navigation.Link("Metrics", "/metrics", icon = "📈")
            )
        )

        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            title("Context Explorer - Orchestrator")
            link(rel = "icon", href = "/static/images/favicon.svg", type = "image/svg+xml")
            link(rel = "stylesheet", href = "/static/css/base.css")
            link(rel = "stylesheet", href = "/static/css/bootstrap-litera.min.css")
            link(rel = "stylesheet", href = "/static/css/orchestrator.css?v=20250115")
            link(rel = "stylesheet", href = "/static/css/explorer.css?v=3")
            link(rel = "stylesheet", href = "/static/css/modal.css")
            script(src = "/static/js/htmx.min.js") {}

        }

        body(classes = "dashboard-layout") {
            with(Navigation) { navigationBar(navConfig) }

            main(classes = "main-content") {
                attributes["id"] = "main-content"
                attributes["role"] = "main"

                div { 
                    id = "modal-container" 
                    classes = setOf("modal")
                }

                pageHeader()
                searchSection()
                
                div(classes = "mt-4") {
                    id = "results-container"
                    // Results loaded via HTMX
                }

                div(classes = "side-panel") {
                    id = "graph-panel"
                    style = "display: none;"
                    // Graph visualization loaded via HTMX
                }
            }

            footer(classes = "main-footer") {
                small { +"Orchestrator Dashboard © 2025" }
            }

            script(src = "/static/js/modal.js?v=1763256400") {}
            script(src = "/static/js/explorer.js?v=1763257200") {}
            script(src = "/static/js/theme-toggle.js") {}
            script(src = "/static/js/navigation.js") {}
            script {
                unsafe {
                    +"""document.addEventListener('keydown',function(e){if((e.ctrlKey||e.metaKey)&&e.key==='k'){e.preventDefault();document.getElementById('query-input')?.focus()}if((e.ctrlKey||e.metaKey)&&e.key==='Enter'&&document.activeElement?.id==='query-input'){e.preventDefault();document.getElementById('query-form')?.requestSubmit()}if(e.key==='Escape'){const m=document.getElementById('modal-container');if(m&&m.innerHTML)m.innerHTML=''}});""".trimIndent()
                }
            }
        }
    }

    private fun FlowContent.pageHeader() {
        div(classes = "flex justify-between items-center mb-lg") {
            div {
                h1(classes = "mt-0 mb-2") { +"Context Explorer" }
                p(classes = "text-muted mb-0") {
                    +"Search your codebase with semantic understanding"
                }
            }
        }
    }

    private fun FlowContent.filterPanel() {
        div(classes = "row") {
            // Left column - Paths and Exclude patterns
            div(classes = "col-md-6") {
                div(classes = "mb-3") {
                    label(classes = "form-label") {
                        attributes["for"] = "filter-paths"
                        +"Paths (one per line)"
                    }
                    textArea(classes = "form-control") {
                        id = "filter-paths"
                        name = "paths"
                        rows = "3"
                        placeholder = "src/main/kotlin\nsrc/test/kotlin"
                    }
                }
                
                div(classes = "mb-3") {
                    label(classes = "form-label") {
                        attributes["for"] = "filter-exclude"
                        +"Exclude Patterns (one per line)"
                    }
                    textArea(classes = "form-control") {
                        id = "filter-exclude"
                        name = "excludePatterns"
                        rows = "3"
                        placeholder = "*Test.kt\nbuild/\n*.md"
                    }
                }
            }
            
            // Right column - Languages and Kinds
            div(classes = "col-md-6") {
                div(classes = "mb-3") {
                    label(classes = "form-label") { +"Languages" }
                    div(classes = "d-flex flex-wrap gap-2") {
                        listOf("kotlin", "java", "python", "javascript", "typescript", "markdown", "document").forEach { lang ->
                            div(classes = "form-check form-check-inline") {
                                input(type = InputType.checkBox, classes = "form-check-input") {
                                    id = "lang-$lang"
                                    name = "languages"
                                    value = lang
                                    checked = true
                                }
                                label(classes = "form-check-label") {
                                    attributes["for"] = "lang-$lang"
                                    +lang.replaceFirstChar { it.uppercase() }
                                }
                            }
                        }
                    }
                }
                
                div(classes = "mb-3") {
                    label(classes = "form-label") { +"Chunk Kinds" }
                    div(classes = "d-flex flex-wrap gap-2") {
                        listOf(
                            "CODE_CLASS",
                            "CODE_FUNCTION",
                            "CODE_METHOD",
                            "MARKDOWN_SECTION",
                            "PARAGRAPH"
                        ).forEach { kind ->
                            div(classes = "form-check form-check-inline") {
                                input(type = InputType.checkBox, classes = "form-check-input") {
                                    id = "kind-$kind"
                                    name = "kinds"
                                    value = kind
                                    checked = true
                                }
                                label(classes = "form-check-label") {
                                    attributes["for"] = "kind-$kind"
                                    +kind.replace("_", " ")
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Sliders row
        div(classes = "row mt-3") {
            div(classes = "col-md-6") {
                div(classes = "mb-3") {
                    label(classes = "form-label") {
                        +"Max Results: "
                        span(classes = "badge bg-secondary") {
                            id = "max-results-value"
                            +"20"
                        }
                    }
                    input(type = InputType.range, classes = "form-range") {
                        id = "filter-max-results"
                        name = "k"
                        attributes["min"] = "1"
                        attributes["max"] = "100"
                        attributes["value"] = "20"
                        attributes["oninput"] = "document.getElementById('max-results-value').textContent = this.value"
                    }
                }
            }
            
            div(classes = "col-md-6") {
                div(classes = "mb-3") {
                    label(classes = "form-label") {
                        +"Max Tokens: "
                        span(classes = "badge bg-secondary") {
                            id = "max-tokens-value"
                            +"6K"
                        }
                    }
                    input(type = InputType.range, classes = "form-range") {
                        id = "filter-max-tokens"
                        name = "maxTokens"
                        attributes["min"] = "500"
                        attributes["max"] = "20000"
                        attributes["step"] = "500"
                        attributes["value"] = "6000"
                        attributes["oninput"] = "updateTokensDisplay(this.value)"
                    }
                }
            }
        }
        
        // Action buttons
        div(classes = "d-flex gap-2 mt-3") {
            button(type = ButtonType.button, classes = "btn btn-outline-secondary btn-sm") {
                attributes["onclick"] = "resetFilters()"
                +"Reset Filters"
            }
            button(type = ButtonType.button, classes = "btn btn-outline-primary btn-sm") {
                attributes["onclick"] = "saveFilters()"
                +"💾 Save Filters"
            }
        }
    }

    private fun FlowContent.searchSection() {
        div(classes = "card mb-4") {
            id = "search-section"

            div(classes = "card-body") {
                form {
                    id = "query-form"
                    attributes["hx-post"] = "/api/context/query"
                    attributes["hx-target"] = "#results-container"
                    attributes["hx-swap"] = "innerHTML"
                    attributes["hx-disabled-elt"] = "#run-query-btn"
                    attributes["onsubmit"] = "return submitSearch(event)"

                    div(classes = "mb-3") {
                        input(type = InputType.text, classes = "form-control form-control-lg") {
                            id = "query-input"
                            name = "query"
                            placeholder = "Search code... (e.g., 'authentication JWT token')"
                            required = true
                            attributes["minlength"] = "2"
                            attributes["autocomplete"] = "off"
                        }
                    }

                    div(classes = "d-flex gap-2 align-items-center") {
                        button(type = ButtonType.submit, classes = "btn btn-primary d-inline-flex align-items-center gap-2") {
                            id = "run-query-btn"
                            span {
                                id = "run-query-label"
                                +"▶ Run Query"
                            }
                            span(classes = "spinner-border spinner-border-sm ms-1 d-none") {
                                id = "run-query-spinner"
                                attributes["role"] = "status"
                                attributes["aria-hidden"] = "true"
                            }
                        }
                        button(type = ButtonType.button, classes = "btn btn-outline-secondary") {
                            attributes["onclick"] = "document.getElementById('query-form').reset()"
                            +"✕ Clear"
                        }
                        button(type = ButtonType.button, classes = "btn btn-outline-secondary") {
                            attributes["onclick"] = "const p=document.getElementById('filter-panel');if(p)p.style.display=p.style.display==='none'?'block':'none'"
                            +"≡ Filters"
                        }
                    }

                    // Filter panel (collapsed by default)
                    div(classes = "mt-3 border-top pt-3") {
                        id = "filter-panel"
                        style = "display: none;"
                        
                        filterPanel()
                    }
                }
            }
        }
    }
}
