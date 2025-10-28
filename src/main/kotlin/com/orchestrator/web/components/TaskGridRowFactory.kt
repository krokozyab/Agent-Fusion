package com.orchestrator.web.components

import com.orchestrator.domain.Task
import com.orchestrator.web.components.StatusBadge.Tone
import com.orchestrator.web.dto.toTaskDTO
import com.orchestrator.web.utils.TimeFormatters
import java.time.Clock
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object TaskGridRowFactory {

    @Serializable
    data class TaskGridRow(
        val taskId: String,
        val idDisplay: String,
        val title: String,
        val description: String? = null,
        val status: String,
        val statusLabel: String,
        val statusTone: String,
        val type: String,
        val typeLabel: String,
        val typeTone: String,
        val routing: String,
        val routingDisplay: String,
        val assignees: List<String>,
        val assigneesDisplay: String,
        val complexity: Int,
        val risk: Int,
        val createdAtEpochMs: Long,
        val createdAtHuman: String,
        val createdAtAbsolute: String,
        val updatedAtEpochMs: Long,
        val updatedAtHuman: String,
        val updatedAtAbsolute: String,
        val detailUrl: String,
        val editUrl: String,
        val searchText: String
    )

    private val json = Json { encodeDefaults = false }

    fun fromTask(task: Task, clock: Clock): TaskGridRow {
        val dto = task.toTaskDTO(clock)
        val reference = Instant.now(clock)
        val createdRelative = task.createdAt?.let {
            TimeFormatters.relativeTime(it, reference, clock.zone)
        }
        val updatedInstant = task.updatedAt ?: task.createdAt
        val updatedRelative = updatedInstant?.let {
            TimeFormatters.relativeTime(it, reference, clock.zone)
        }

        val statusTone = task.status.toTone()
        val typeTone = task.type.toTone()

        val assignees = dto.assigneeIds
        val assigneesDisplay = if (assignees.isEmpty()) {
            "Unassigned"
        } else {
            assignees.joinToString(", ")
        }

        val routingDisplay = formatDisplay(task.routing.name)
        val description = task.description

        val searchText = buildString {
            append(task.id.value)
            append(' ')
            append(task.title)
            description?.let {
                append(' ')
                append(it)
            }
            append(' ')
            append(task.status.displayName)
            append(' ')
            append(task.type.displayName)
            append(' ')
            append(routingDisplay)
            if (assignees.isEmpty()) {
                append(" Unassigned")
            } else {
                assignees.forEach { assignee ->
                    append(' ')
                    append(assignee)
                }
            }
        }

        return TaskGridRow(
            taskId = task.id.value,
            idDisplay = "#${task.id.value}",
            title = task.title,
            description = description,
            status = task.status.name,
            statusLabel = task.status.displayName,
            statusTone = toneToToken(statusTone),
            type = task.type.name,
            typeLabel = task.type.displayName,
            typeTone = toneToToken(typeTone),
            routing = task.routing.name,
            routingDisplay = routingDisplay,
            assignees = assignees,
            assigneesDisplay = assigneesDisplay,
            complexity = task.complexity,
            risk = task.risk,
            createdAtEpochMs = task.createdAt?.toEpochMilli() ?: 0L,
            createdAtHuman = createdRelative?.humanized ?: "–",
            createdAtAbsolute = createdRelative?.absolute ?: "–",
            updatedAtEpochMs = updatedInstant?.toEpochMilli() ?: 0L,
            updatedAtHuman = updatedRelative?.humanized ?: "–",
            updatedAtAbsolute = updatedRelative?.absolute ?: "–",
            detailUrl = "/tasks/${task.id.value}/modal",
            editUrl = "/tasks/${task.id.value}/edit",
            searchText = searchText
        )
    }

    fun TaskGridRow.toRowMap(): Map<String, Any> = mapOf(
        "taskId" to taskId,
        "idDisplay" to idDisplay,
        "title" to title,
        "description" to (description ?: ""),
        "status" to status,
        "statusLabel" to statusLabel,
        "statusTone" to statusTone,
        "type" to type,
        "typeLabel" to typeLabel,
        "typeTone" to typeTone,
        "routing" to routing,
        "routingDisplay" to routingDisplay,
        "assignees" to assignees,
        "assigneesDisplay" to assigneesDisplay,
        "assigneeCount" to assignees.size,
        "complexity" to complexity,
        "risk" to risk,
        "createdAtEpochMs" to createdAtEpochMs,
        "createdAtHuman" to createdAtHuman,
        "createdAtAbsolute" to createdAtAbsolute,
        "updatedAtEpochMs" to updatedAtEpochMs,
        "updatedAtHuman" to updatedAtHuman,
        "updatedAtAbsolute" to updatedAtAbsolute,
        "detailUrl" to detailUrl,
        "editUrl" to editUrl,
        "searchText" to searchText
    )

    fun TaskGridRow.toJson(): String = json.encodeToString(this)

    private fun toneToToken(tone: Tone): String = when (tone) {
        Tone.DEFAULT -> "default"
        Tone.SUCCESS -> "success"
        Tone.WARNING -> "warning"
        Tone.DANGER -> "danger"
        Tone.INFO -> "info"
    }

    private fun formatDisplay(value: String): String =
        value.lowercase()
            .split('_')
            .joinToString(" ") { part ->
                part.replaceFirstChar { ch -> ch.titlecase() }
            }
}

