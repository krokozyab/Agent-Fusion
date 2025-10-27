package com.orchestrator.web.components

import com.orchestrator.web.utils.TimeFormatters
import java.time.Instant
import java.time.ZoneId
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.span

object FileRow {

    /**
     * Status tones for different file states
     */
    private fun statusToTone(status: String): StatusBadge.Tone = when (status.lowercase()) {
        "indexed" -> StatusBadge.Tone.SUCCESS
        "outdated" -> StatusBadge.Tone.WARNING
        "error" -> StatusBadge.Tone.DANGER
        "pending" -> StatusBadge.Tone.INFO
        else -> StatusBadge.Tone.DEFAULT
    }

    /**
     * Format file size in human-readable format
     */
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }

    /**
     * Extract file name from path
     */
    private fun getFileName(path: String): String {
        return path.substringAfterLast("/")
    }

    /**
     * Extract directory path from full path
     */
    private fun getDirPath(path: String): String {
        val fileName = getFileName(path)
        return path.substringBeforeLast("/").let {
            if (it.isEmpty()) "/" else it
        }
    }

    fun toRow(
        model: FileBrowser.Model,
        detailsUrl: String
    ): DataTable.Row {
        val timestamp = model.lastModified
        val relative = timestamp?.let {
            TimeFormatters.relativeTime(
                from = it,
                reference = model.referenceInstant,
                zoneId = model.zoneId
            )
        }

        val statusContent = buildString {
            append("<span class=\"file-row__status\">")
            append(StatusBadge.render(StatusBadge.Config(
                label = model.status.uppercase(),
                tone = statusToTone(model.status),
                ariaLabel = "File status ${model.status}"
            )))
            append("</span>")
        }

        val sizeStr = formatFileSize(model.sizeBytes)
        val fileName = getFileName(model.path)
        val dirPath = getDirPath(model.path)

        return DataTable.row(
            id = "file-row-${model.path.hashCode()}",
            ariaLabel = "${model.path}, status ${model.status}, ${model.chunkCount} chunks"
        ) {
            attribute("data-file-path", model.path)
            attribute("data-file-status", model.status)
            attribute("class", "data-table__row file-row")

            // Path cell with directory context
            cell {
                div(classes = "file-row__path-container") {
                    span(classes = "file-row__filename") {
                        +fileName
                    }
                    span(classes = "file-row__dir") {
                        +dirPath
                    }
                }
            }

            // Status cell with badge
            rawCell(content = statusContent)

            // File type/extension cell
            cell {
                span(classes = "file-row__extension") {
                    +model.extension.uppercase()
                }
            }

            // Size cell (numeric)
            cell(numeric = true) {
                span(classes = "file-row__size") {
                    attributes["title"] = "${model.sizeBytes} bytes"
                    +sizeStr
                }
            }

            // Modified date cell
            cell {
                span(classes = "file-row__timestamp") {
                    relative?.let {
                        attributes["title"] = it.absolute
                        +it.humanized
                    } ?: run {
                        +"—"
                    }
                }
            }

            // Chunks count cell (numeric)
            cell(numeric = true) {
                span(classes = "file-row__chunks") {
                    +model.chunkCount.toString()
                }
            }

            // Actions cell
            cell {
                actions(model, detailsUrl)
            }
        }
    }

    private fun FlowContent.actions(
        model: FileBrowser.Model,
        detailsUrl: String
    ) {
        div(classes = "file-row__actions") {
            button(classes = "file-row__action file-row__action--view") {
                type = ButtonType.button
                attributes["hx-get"] = detailsUrl
                attributes["hx-target"] = "#modal-container"
                attributes["hx-swap"] = "innerHTML"
                attributes["hx-trigger"] = "click consume"
                attributes["aria-label"] = "View details for ${model.path}"
                +"View"
            }
        }
    }
}
