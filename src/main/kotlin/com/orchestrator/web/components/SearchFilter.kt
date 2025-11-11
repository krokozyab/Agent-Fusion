package com.orchestrator.web.components

import com.orchestrator.web.rendering.Fragment
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.option
import kotlinx.html.select
import kotlinx.html.span

object SearchFilter {

    data class Option(
        val value: String,
        val label: String,
        val selected: Boolean = false
    )

    data class Preset(
        val label: String,
        val query: String? = null,
        val status: String? = null,
        val type: String? = null,
        val agent: String? = null
    )

    data class Config(
        val query: String = "",
        val statuses: List<Option> = emptyList(),
        val types: List<Option> = emptyList(),
        val agents: List<Option> = emptyList(),
        val fromDate: String? = null,
        val toDate: String? = null,
        val includeDateRange: Boolean = false,
        val presets: List<Preset> = emptyList(),
        val hxTarget: String = "#tasks-table-body",
        val hxIndicator: String = "#tasks-table-indicator",
        val hxEndpoint: String = "/tasks/table",
        val formId: String = "task-filter-form",
        val searchPlaceholder: String = "Search tasks",
        val clearLabel: String = "Clear filters"
    )

    fun render(config: Config): String = Fragment.render {
        filters(config)
    }

    fun FlowContent.filters(config: Config) {
        div(classes = "task-filter") {
            form(classes = "task-filter__form") {
                attributes["id"] = config.formId
                attributes["role"] = "search"
                attributes["hx-get"] = config.hxEndpoint
                attributes["hx-trigger"] = "keyup changed delay:500ms"
                attributes["hx-target"] = config.hxTarget
                attributes["hx-indicator"] = config.hxIndicator
                attributes["hx-include"] = "#${config.formId} *"
                method = FormMethod.get

                div(classes = "task-filter__row task-filter__row--primary") {
                    div(classes = "task-filter__control task-filter__control--search") {
                        label(classes = "task-filter__label visually-hidden") {
                            attributes["for"] = "${config.formId}-search"
                            +"Search tasks"
                        }
                        input(InputType.search, classes = "task-filter__search") {
                            attributes["id"] = "${config.formId}-search"
                            attributes["name"] = "query"
                            attributes["placeholder"] = config.searchPlaceholder
                            attributes["autocomplete"] = "off"
                            attributes["data-search-shortcut"] = "/"
                            attributes["aria-label"] = "Search tasks"
                            if (config.query.isNotBlank()) {
                                value = config.query
                            }
                        }
                        span(classes = "task-filter__hint") {
                            attributes["aria-hidden"] = "true"
                            +"/ to focus"
                        }
                    }

                    if (config.presets.isNotEmpty()) {
                        div(classes = "task-filter__control task-filter__control--presets") {
                            label(classes = "task-filter__label") {
                                attributes["aria-hidden"] = "true"
                                +"Presets"
                            }
                            div(classes = "task-filter__preset-group") {
                                config.presets.forEachIndexed { index, preset ->
                                    button(classes = "task-filter__preset") {
                                        type = ButtonType.button
                                        attributes["data-filter-preset"] = preset.label
                                        attributes["data-preset-query"] = preset.query.orEmpty()
                                        attributes["data-preset-status"] = preset.status.orEmpty()
                                        attributes["data-preset-type"] = preset.type.orEmpty()
                                        attributes["data-preset-agent"] = preset.agent.orEmpty()
                                        attributes["data-preset-target"] = "#${config.formId}"
                                        attributes["aria-label"] = "Apply preset ${preset.label}"
                                        attributes["tabindex"] = "${index + 1}"
                                        +preset.label
                                    }
                                }
                            }
                        }
                    }

                    div(classes = "task-filter__control task-filter__control--clear") {
                        button(classes = "task-filter__clear") {
                            type = ButtonType.button
                            attributes["data-filter-clear"] = "true"
                            attributes["data-filter-target"] = "#${config.formId}"
                            attributes["aria-label"] = clearLabel(config)
                            +config.clearLabel
                        }
                    }
                }

                div(classes = "task-filter__row task-filter__row--secondary") {
                    selectBlock(
                        id = "${config.formId}-status",
                        label = "Status",
                        name = "status",
                        options = config.statuses,
                        config = config
                    )
                    selectBlock(
                        id = "${config.formId}-type",
                        label = "Type",
                        name = "type",
                        options = config.types,
                        config = config
                    )
                    selectBlock(
                        id = "${config.formId}-agent",
                        label = "Agent",
                        name = "agent",
                        options = config.agents,
                        config = config
                    )

                    if (config.includeDateRange) {
                        dateRangeBlock(config)
                    }
                }
            }
        }
    }

    private fun clearLabel(config: Config): String = config.clearLabel

    private fun FlowContent.selectBlock(
        id: String,
        label: String,
        name: String,
        options: List<Option>,
        config: Config
    ) {
        div(classes = "task-filter__control task-filter__control--select") {
            label(classes = "task-filter__label") {
                attributes["for"] = id
                +label
            }
            select(classes = "task-filter__select") {
                attributes["id"] = id
                attributes["name"] = name
                attributes["hx-trigger"] = "change"
                attributes["hx-get"] = config.hxEndpoint
                attributes["hx-target"] = config.hxTarget
                attributes["hx-include"] = "#${config.formId} *"
                attributes["hx-indicator"] = config.hxIndicator
                options.forEach {
                    option {
                        value = it.value
                        if (it.selected) {
                            attributes["selected"] = "selected"
                        }
                        +it.label
                    }
                }
            }
        }
    }

    private fun FlowContent.dateRangeBlock(config: Config) {
        div(classes = "task-filter__control task-filter__control--date") {
            label(classes = "task-filter__label") {
                attributes["for"] = "${config.formId}-from"
                +"From"
            }
            input(InputType.date, classes = "task-filter__date") {
                attributes["id"] = "${config.formId}-from"
                attributes["name"] = "from"
                attributes["hx-trigger"] = "change"
                attributes["hx-get"] = config.hxEndpoint
                attributes["hx-target"] = config.hxTarget
                attributes["hx-include"] = "#${config.formId} *"
                attributes["hx-indicator"] = config.hxIndicator
                config.fromDate?.let { value = it }
            }
        }
        div(classes = "task-filter__control task-filter__control--date") {
            label(classes = "task-filter__label") {
                attributes["for"] = "${config.formId}-to"
                +"To"
            }
            input(InputType.date, classes = "task-filter__date") {
                attributes["id"] = "${config.formId}-to"
                attributes["name"] = "to"
                attributes["hx-trigger"] = "change"
                attributes["hx-get"] = config.hxEndpoint
                attributes["hx-target"] = config.hxTarget
                attributes["hx-include"] = "#${config.formId} *"
                attributes["hx-indicator"] = config.hxIndicator
                config.toDate?.let { value = it }
            }
        }
    }
}
