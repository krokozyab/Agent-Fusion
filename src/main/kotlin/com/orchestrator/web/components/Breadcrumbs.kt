package com.orchestrator.web.components

import com.orchestrator.web.rendering.Fragment
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.li
import kotlinx.html.nav
import kotlinx.html.ol
import kotlinx.html.span

/**
 * Breadcrumbs component for hierarchical navigation.
 *
 * Provides accessible breadcrumb navigation with:
 * - Structured data markup
 * - ARIA labels for screen readers
 * - Current page indication
 * - HTMX boost support
 */
object Breadcrumbs {

    /**
     * Single breadcrumb item
     */
    data class Crumb(
        val label: String,
        val href: String? = null,
        val current: Boolean = false,
        val ariaLabel: String? = null
    ) {
        init {
            require(!current || href == null) {
                "Current breadcrumb should not have an href"
            }
        }
    }

    /**
     * Breadcrumbs configuration
     */
    data class Config(
        val crumbs: List<Crumb>,
        val enableHtmxBoost: Boolean = true,
        val ariaLabel: String = "Breadcrumb navigation",
        val separator: String = "/"
    ) {
        init {
            require(crumbs.isNotEmpty()) {
                "Breadcrumbs must have at least one crumb"
            }
            val currentCount = crumbs.count { it.current }
            require(currentCount <= 1) {
                "Only one breadcrumb can be marked as current"
            }
            if (currentCount == 0) {
                // Auto-mark the last one as current if none specified
            }
        }
    }

    /**
     * Render breadcrumbs as HTML string
     */
    fun render(config: Config): String = Fragment.render {
        breadcrumbs(config)
    }

    /**
     * Render breadcrumbs into existing FlowContent context
     */
    fun FlowContent.breadcrumbs(config: Config) {
        nav(classes = "breadcrumbs") {
            attributes["aria-label"] = config.ariaLabel

            ol(classes = "breadcrumbs__list") {
                attributes["itemscope"] = ""
                attributes["itemtype"] = "https://schema.org/BreadcrumbList"

                config.crumbs.forEachIndexed { index, crumb ->
                    val isCurrent = crumb.current || (index == config.crumbs.lastIndex && config.crumbs.none { it.current })
                    val position = index + 1

                    li(classes = "breadcrumbs__item") {
                        attributes["itemprop"] = "itemListElement"
                        attributes["itemscope"] = ""
                        attributes["itemtype"] = "https://schema.org/ListItem"

                        if (isCurrent) {
                            // Current page - no link
                            span(classes = "breadcrumbs__current") {
                                attributes["itemprop"] = "name"
                                attributes["aria-current"] = "page"
                                +crumb.label
                            }
                        } else {
                            // Link to previous page
                            a(href = crumb.href ?: "#", classes = "breadcrumbs__link") {
                                attributes["itemprop"] = "item"
                                if (config.enableHtmxBoost) {
                                    attributes["hx-boost"] = "true"
                                }
                                crumb.ariaLabel?.let {
                                    attributes["aria-label"] = it
                                }

                                span {
                                    attributes["itemprop"] = "name"
                                    +crumb.label
                                }
                            }
                        }

                        // Position metadata for structured data
                        span {
                            attributes["itemprop"] = "position"
                            attributes["content"] = position.toString()
                            attributes["style"] = "display: none;"
                        }

                        // Separator (not for last item)
                        if (index < config.crumbs.lastIndex) {
                            span(classes = "breadcrumbs__separator") {
                                attributes["aria-hidden"] = "true"
                                +config.separator
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Helper to create a simple breadcrumb trail
     */
    fun simple(vararg labels: String, enableHtmxBoost: Boolean = true): Config {
        val crumbs = labels.mapIndexed { index, label ->
            Crumb(
                label = label,
                href = if (index < labels.lastIndex) "#" else null,
                current = index == labels.lastIndex
            )
        }
        return Config(crumbs = crumbs, enableHtmxBoost = enableHtmxBoost)
    }

    /**
     * Helper to create breadcrumbs with custom hrefs
     */
    fun trail(vararg pairs: Pair<String, String?>, enableHtmxBoost: Boolean = true): Config {
        val crumbs = pairs.mapIndexed { index, (label, href) ->
            Crumb(
                label = label,
                href = if (index < pairs.lastIndex) href else null,
                current = index == pairs.lastIndex
            )
        }
        return Config(crumbs = crumbs, enableHtmxBoost = enableHtmxBoost)
    }
}
