package com.orchestrator.web.components

import com.orchestrator.web.rendering.Fragment
import com.orchestrator.web.utils.TimeFormatters
import java.time.Instant
import java.time.ZoneId
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.button
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.h3
import kotlinx.html.pre
import kotlinx.html.span
import kotlinx.html.unsafe

/**
 * File detail view component for viewing file information and chunks
 */
object FileDetail {

    data class ChunkInfo(
        val id: Long,
        val ordinal: Int,
        val kind: String,
        val startLine: Int?,
        val endLine: Int?,
        val tokenCount: Int?,
        val content: String,
        val summary: String?
    )

    data class Model(
        val path: String,
        val status: String,
        val sizeBytes: Long,
        val lastModified: Instant?,
        val language: String?,
        val extension: String,
        val contentHash: String,
        val chunks: List<ChunkInfo> = emptyList(),
        val totalChunks: Int = 0,
        val referenceInstant: Instant = Instant.now(),
        val zoneId: ZoneId = ZoneId.systemDefault()
    )

    data class Config(
        val model: Model,
        val closeButtonLabel: String = "Close"
    )

    fun render(config: Config): String = Fragment.render {
        fileDetailModal(config)
    }

    fun FlowContent.fileDetailModal(config: Config) {
        div(classes = "modal-content file-detail") {
            // Modal header
            div(classes = "modal-header") {
                span(classes = "modal-title") {
                    +config.model.path
                }
                button(classes = "modal-close") {
                    type = ButtonType.button
                    attributes["aria-label"] = "Close file detail"
                    attributes["onclick"] = "document.getElementById('modal-container').innerHTML = ''"
                    unsafe { +"&times;" }
                }
            }

            // Modal body
            div(classes = "modal-body") {
                // File info section
                div(classes = "file-detail__section") {
                    h3(classes = "file-detail__heading") { +"File Information" }

                    div(classes = "file-detail__info-grid") {
                        // Status
                        div(classes = "file-detail__info-item") {
                            span(classes = "file-detail__label") { +"Status" }
                            span(classes = "file-detail__value") {
                                val tone = when (config.model.status.lowercase()) {
                                    "indexed" -> StatusBadge.Tone.SUCCESS
                                    "outdated" -> StatusBadge.Tone.WARNING
                                    "error" -> StatusBadge.Tone.DANGER
                                    "pending" -> StatusBadge.Tone.INFO
                                    else -> StatusBadge.Tone.DEFAULT
                                }
                                unsafe {
                                    +StatusBadge.render(StatusBadge.Config(
                                        label = config.model.status.uppercase(),
                                        tone = tone,
                                        ariaLabel = "File status ${config.model.status}"
                                    ))
                                }
                            }
                        }

                        // File type
                        div(classes = "file-detail__info-item") {
                            span(classes = "file-detail__label") { +"Type" }
                            span(classes = "file-detail__value") {
                                +"${config.model.extension.uppercase()} (${config.model.language ?: "unknown"})"
                            }
                        }

                        // File size
                        div(classes = "file-detail__info-item") {
                            span(classes = "file-detail__label") { +"Size" }
                            span(classes = "file-detail__value") {
                                +formatFileSize(config.model.sizeBytes)
                            }
                        }

                        // Last modified
                        div(classes = "file-detail__info-item") {
                            span(classes = "file-detail__label") { +"Modified" }
                            span(classes = "file-detail__value") {
                                config.model.lastModified?.let { lastMod ->
                                    val relative = TimeFormatters.relativeTime(
                                        from = lastMod,
                                        reference = config.model.referenceInstant,
                                        zoneId = config.model.zoneId
                                    )
                                    attributes["title"] = relative.absolute
                                    +relative.humanized
                                } ?: run {
                                    +"—"
                                }
                            }
                        }

                        // Content hash
                        div(classes = "file-detail__info-item") {
                            span(classes = "file-detail__label") { +"Hash" }
                            span(classes = "file-detail__value file-detail__hash") {
                                attributes["title"] = config.model.contentHash
                                +(config.model.contentHash.take(16) + "...")
                            }
                        }

                        // Chunk count
                        div(classes = "file-detail__info-item") {
                            span(classes = "file-detail__label") { +"Chunks" }
                            span(classes = "file-detail__value") {
                                +"${config.model.chunks.size} / ${config.model.totalChunks}"
                            }
                        }
                    }
                }

                // Chunks section
                if (config.model.chunks.isNotEmpty()) {
                    div(classes = "file-detail__section") {
                        h3(classes = "file-detail__heading") {
                            +"Chunks (${config.model.chunks.size})"
                        }

                        div(classes = "file-detail__chunks-list") {
                            config.model.chunks.forEach { chunk ->
                                chunkItem(chunk)
                            }
                        }
                    }
                } else {
                    div(classes = "file-detail__section") {
                        div(classes = "text-center text-muted p-md") {
                            +"No chunks available"
                        }
                    }
                }
            }

            // Modal footer
            div(classes = "modal-footer") {
                button(classes = "btn btn-secondary") {
                    type = ButtonType.button
                    attributes["onclick"] = "document.getElementById('modal-container').innerHTML = ''"
                    +config.closeButtonLabel
                }
            }
        }
    }

    private fun FlowContent.chunkItem(chunk: ChunkInfo) {
        div(classes = "file-detail__chunk-item") {
            // Chunk header
            div(classes = "file-detail__chunk-header") {
                span(classes = "file-detail__chunk-num") {
                    +"Chunk ${chunk.ordinal + 1}"
                }
                span(classes = "file-detail__chunk-kind") {
                    +chunk.kind
                }
                chunk.tokenCount?.let { tokens ->
                    span(classes = "file-detail__chunk-tokens") {
                        +"$tokens tokens"
                    }
                }
                chunk.startLine?.let { start ->
                    span(classes = "file-detail__chunk-lines") {
                        if (chunk.endLine != null) {
                            +"Lines $start - ${chunk.endLine}"
                        } else {
                            +"Line $start"
                        }
                    }
                }
            }

            // Chunk summary
            chunk.summary?.let { summary ->
                div(classes = "file-detail__chunk-summary") {
                    +summary
                }
            }

            // Chunk content preview
            div(classes = "file-detail__chunk-content") {
                pre {
                    code {
                        +(chunk.content.take(500) + if (chunk.content.length > 500) "..." else "")
                    }
                }
            }
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }
}
