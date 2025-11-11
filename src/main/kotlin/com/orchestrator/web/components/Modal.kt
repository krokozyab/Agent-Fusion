package com.orchestrator.web.components

import kotlinx.html.*

object Modal {

    data class Config(
        val id: String,
        val title: String,
        val body: DIV.() -> Unit,
        val footer: (DIV.() -> Unit)? = null
    )

    fun FlowContent.render(config: Config) {
        div(classes = "modal") {
            attributes["id"] = config.id
            attributes["aria-modal"] = "true"
            attributes["role"] = "dialog"

            div(classes = "modal__backdrop") {
                attributes["data-modal-close"] = config.id
            }

            div(classes = "modal__content") {
                div(classes = "modal__header") {
                    h3(classes = "modal__title") { +config.title }
                    button(classes = "modal__close") {
                        attributes["data-modal-close"] = config.id
                        attributes["aria-label"] = "Close modal"
                        +"×"
                    }
                }

                div(classes = "modal__body") {
                    config.body(this)
                }

                if (config.footer != null) {
                    div(classes = "modal__footer") {
                        config.footer(this)
                    }
                }
            }
        }
    }
}