package com.orchestrator.web.components

import com.orchestrator.web.rendering.Fragment
import kotlinx.html.FlowContent
import kotlinx.html.span

object StatusBadge {
    enum class Tone {
        DEFAULT,
        SUCCESS,
        WARNING,
        DANGER,
        INFO
    }

    data class Config(
        val label: String,
        val tone: Tone = Tone.DEFAULT,
        val outline: Boolean = false,
        val ariaLabel: String? = null
    )

    fun render(config: Config): String = Fragment.render {
        badge(config)
    }

    fun FlowContent.badge(config: Config) {
        span(classes = classesFor(config)) {
            attributes["role"] = "status"
            attributes["aria-live"] = "polite"
            config.ariaLabel?.let { attributes["aria-label"] = it }
            +config.label
        }
    }

    private fun classesFor(config: Config): String {
        val base = mutableListOf("badge")
        base += when (config.tone) {
            Tone.DEFAULT -> "badge--default"
            Tone.SUCCESS -> "badge--success"
            Tone.WARNING -> "badge--warning"
            Tone.DANGER -> "badge--danger"
            Tone.INFO -> "badge--info"
        }
        if (config.outline) {
            base += "badge--outline"
        }
        return base.joinToString(" ")
    }
}
