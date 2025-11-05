package com.orchestrator.web.components

import com.orchestrator.web.rendering.Fragment
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.span

object Pagination {
    fun renderControls(config: Config): String =
        Fragment.render { controls(config) }

    fun FlowContent.controls(config: Config) {
        val totalPages = config.totalPages
        val currentPage = config.page.coerceIn(1, totalPages)
        val targetSelector = "#${config.hxTargetId}"
        val indicatorSelector = "#${config.hxIndicatorId}"

        div(classes = "data-table__footer") {
            nav(classes = "data-table__pagination") {
                attributes["aria-label"] = "Pagination"
                attributes["role"] = "navigation"

                // First button
                paginationButton(
                    label = "First",
                    disabled = currentPage <= 1,
                    destinationPage = 1,
                    config = config,
                    targetSelector = targetSelector,
                    indicatorSelector = indicatorSelector,
                    ariaLabel = "Go to first page"
                )

                // Previous button
                paginationButton(
                    label = "Previous",
                    disabled = currentPage <= 1,
                    destinationPage = currentPage - 1,
                    config = config,
                    targetSelector = targetSelector,
                    indicatorSelector = indicatorSelector,
                    ariaLabel = "Go to previous page"
                )

                // Page number buttons with ellipsis
                pageNumberButtons(
                    currentPage = currentPage,
                    totalPages = totalPages,
                    config = config,
                    targetSelector = targetSelector,
                    indicatorSelector = indicatorSelector
                )

                // Next button
                paginationButton(
                    label = "Next",
                    disabled = currentPage >= totalPages,
                    destinationPage = currentPage + 1,
                    config = config,
                    targetSelector = targetSelector,
                    indicatorSelector = indicatorSelector,
                    ariaLabel = "Go to next page"
                )

                // Last button
                paginationButton(
                    label = "Last",
                    disabled = currentPage >= totalPages,
                    destinationPage = totalPages,
                    config = config,
                    targetSelector = targetSelector,
                    indicatorSelector = indicatorSelector,
                    ariaLabel = "Go to last page"
                )
            }

            perPageSelector(
                config = config,
                targetSelector = targetSelector,
                indicatorSelector = indicatorSelector
            )

            p(classes = "data-table__summary") {
                +"Showing page $currentPage of $totalPages "
                +"(${config.totalCount} items)"
            }
        }
    }

    private fun FlowContent.paginationButton(
        label: String,
        disabled: Boolean,
        destinationPage: Int,
        config: Config,
        targetSelector: String,
        indicatorSelector: String,
        ariaLabel: String = label
    ) {
        button(classes = "data-table__page-button") {
            type = ButtonType.button
            attributes["aria-label"] = ariaLabel
            attributes["aria-disabled"] = disabled.toString()
            attributes["tabindex"] = if (disabled) "-1" else "0"

            if (disabled) {
                attributes["disabled"] = "disabled"
            } else {
                attributes["hx-get"] = config.makePageUrl(destinationPage, config.pageSize)
                attributes["hx-target"] = targetSelector
                attributes["hx-swap"] = config.hxSwap
                attributes["hx-indicator"] = indicatorSelector
            }

            +label
        }
    }

    private fun FlowContent.pageNumberButtons(
        currentPage: Int,
        totalPages: Int,
        config: Config,
        targetSelector: String,
        indicatorSelector: String
    ) {
        val window = config.windowSize.coerceAtLeast(1)
        val halfWindow = ((window - 1) / 2.0).roundToInt()
        val tentativeStart = currentPage - halfWindow
        val tentativeEnd = currentPage + (window - halfWindow - 1)
        var start = max(1, tentativeStart)
        var end = min(totalPages, tentativeEnd)

        if (end - start + 1 < window) {
            if (start == 1) {
                end = min(totalPages, start + window - 1)
            } else if (end == totalPages) {
                start = max(1, end - window + 1)
            }
        }

        // Show ellipsis before first page number if not showing page 1
        if (start > 1) {
            // Always show page 1
            pageNumberButton(1, currentPage, totalPages, config, targetSelector, indicatorSelector)

            // Show ellipsis if there's a gap
            if (start > 2) {
                ellipsis()
            }
        }

        // Show page numbers in the window
        for (page in start..end) {
            pageNumberButton(page, currentPage, totalPages, config, targetSelector, indicatorSelector)
        }

        // Show ellipsis after last page number if not showing last page
        if (end < totalPages) {
            // Show ellipsis if there's a gap
            if (end < totalPages - 1) {
                ellipsis()
            }

            // Always show last page
            pageNumberButton(totalPages, currentPage, totalPages, config, targetSelector, indicatorSelector)
        }
    }

    private fun FlowContent.pageNumberButton(
        page: Int,
        currentPage: Int,
        totalPages: Int,
        config: Config,
        targetSelector: String,
        indicatorSelector: String
    ) {
        button(classes = "data-table__page-number") {
            type = ButtonType.button
            attributes["aria-label"] = "Page $page of $totalPages"
            attributes["aria-pressed"] = (page == currentPage).toString()
            if (page == currentPage) {
                attributes["data-state"] = "active"
                attributes["tabindex"] = "0"
            } else {
                attributes["tabindex"] = "0"
                attributes["hx-get"] = config.makePageUrl(page, config.pageSize)
                attributes["hx-target"] = targetSelector
                attributes["hx-swap"] = config.hxSwap
                attributes["hx-indicator"] = indicatorSelector
            }
            +page.toString()
        }
    }

    private fun FlowContent.ellipsis() {
        span(classes = "data-table__page-ellipsis") {
            attributes["aria-hidden"] = "true"
            +"..."
        }
    }

    private fun FlowContent.perPageSelector(
        config: Config,
        targetSelector: String,
        indicatorSelector: String
    ) {
        div(classes = "data-table__page-size") {
            span { +"Rows per page:" }
            for (option in config.perPageOptions) {
                val isActive = option == config.pageSize
                button(classes = "data-table__page-size-button") {
                    type = ButtonType.button
                    attributes["aria-label"] = "Show $option rows per page"
                    attributes["aria-pressed"] = isActive.toString()
                    if (isActive) {
                        attributes["data-state"] = "active"
                        attributes["tabindex"] = "0"
                    } else {
                        attributes["tabindex"] = "0"
                        attributes["hx-get"] = config.makePageUrl(1, option)
                        attributes["hx-target"] = targetSelector
                        attributes["hx-swap"] = config.hxSwap
                        attributes["hx-indicator"] = indicatorSelector
                    }
                    +option.toString()
                }
            }
        }
    }

    data class Config(
        val page: Int,
        val pageSize: Int,
        val totalCount: Long,
        val perPageOptions: List<Int> = listOf(10, 25, 50),
        val windowSize: Int = 5,
        val makePageUrl: PageUrlBuilder,
        val hxTargetId: String,
        val hxIndicatorId: String,
        val hxSwap: String = "outerHTML"
    ) {
        val totalPages: Int
            get() = max(1, ((totalCount + pageSize - 1) / pageSize).toInt())
    }

    fun interface PageUrlBuilder {
        operator fun invoke(page: Int, pageSize: Int): String
    }
}
