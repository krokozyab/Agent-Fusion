package com.orchestrator.web.components

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.web.utils.ConfigRenderer
import kotlinx.html.FlowContent
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import kotlinx.html.summary
import kotlinx.html.unsafe

/**
 * Component for rendering configuration sections with automatic property discovery.
 */
object ConfigSection {

    /**
     * Render all configuration sections from ContextConfig using reflection.
     */
    fun FlowContent.renderAllSections(config: ContextConfig) {
        // Top-level properties
        renderSection("General", "⚙️", config, listOf("enabled", "mode", "fallbackEnabled"))
        
        // Nested config objects
        renderSection("Engine", "🔌", config.engine)
        renderSection("Storage", "💾", config.storage)
        renderSection("Watcher", "👁️", config.watcher)
        renderSection("Indexing", "📑", config.indexing)
        renderSection("Embedding", "🧠", config.embedding)
        renderSection("Chunking", "✂️", config.chunking)
        renderSection("Query", "🔍", config.query)
        renderSection("Budget", "💰", config.budget)
        renderSection("Providers", "🔗", config.providers)
        renderSection("Metrics", "📊", config.metrics)
        renderSection("Bootstrap", "🚀", config.bootstrap)
        renderSection("Security", "🔒", config.security)
    }

    /**
     * Render a single configuration section.
     */
    private fun FlowContent.renderSection(
        title: String,
        icon: String,
        configObject: Any,
        propertyFilter: List<String>? = null
    ) {
        val properties = ConfigRenderer.extractProperties(configObject)
        val filteredProps = if (propertyFilter != null) {
            properties.filter { it.name in propertyFilter }
        } else {
            properties
        }

        details(classes = "config-section") {
            summary(classes = "config-section__header") {
                +"$icon $title"
            }
            div(classes = "config-section__body") {
                filteredProps.forEach { prop ->
                    renderProperty(prop)
                }
            }
        }
    }

    /**
     * Render a single property.
     */
    private fun FlowContent.renderProperty(prop: ConfigRenderer.ConfigProperty) {
        div(classes = "config-property") {
            div(classes = "config-property__name") {
                +prop.name
            }
            div {
                if (prop.isNested && prop.value != null) {
                    // Render nested object
                    div(classes = "config-nested") {
                        val nestedProps = ConfigRenderer.extractProperties(prop.value)
                        nestedProps.forEach { nestedProp ->
                            renderProperty(nestedProp)
                        }
                    }
                } else {
                    // Render value
                    div(classes = "config-property__value") {
                        val formattedValue = if (ConfigRenderer.isSensitive(prop.name) && prop.value is String) {
                            ConfigRenderer.maskSensitive(prop.value)
                        } else {
                            ConfigRenderer.formatValue(prop.value)
                        }
                        unsafe { +formattedValue }
                    }
                }
            }
        }
    }

    /**
     * Render configuration sections as HTML string (for standalone use).
     */
    fun renderAsHtml(config: ContextConfig): String = createHTML().div {
        renderAllSections(config)
    }
}
