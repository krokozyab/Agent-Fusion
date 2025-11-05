package com.orchestrator.web.components

import com.orchestrator.domain.TaskStatus
import com.orchestrator.domain.TaskType

internal fun TaskStatus.toTone(): StatusBadge.Tone = when (this) {
    TaskStatus.COMPLETED -> StatusBadge.Tone.SUCCESS
    TaskStatus.IN_PROGRESS -> StatusBadge.Tone.INFO
    TaskStatus.WAITING_INPUT -> StatusBadge.Tone.INFO
    TaskStatus.FAILED -> StatusBadge.Tone.DANGER
    TaskStatus.PENDING -> StatusBadge.Tone.WARNING
}

internal fun TaskType.toTone(): StatusBadge.Tone = when (this) {
    TaskType.BUGFIX -> StatusBadge.Tone.DANGER
    TaskType.REVIEW -> StatusBadge.Tone.INFO
    TaskType.RESEARCH -> StatusBadge.Tone.INFO
    TaskType.ARCHITECTURE -> StatusBadge.Tone.INFO
    TaskType.TESTING -> StatusBadge.Tone.WARNING
    TaskType.PLANNING -> StatusBadge.Tone.WARNING
    TaskType.DOCUMENTATION -> StatusBadge.Tone.DEFAULT
    TaskType.IMPLEMENTATION -> StatusBadge.Tone.SUCCESS
}

internal val TaskStatus.displayName: String
    get() = when (this) {
        TaskStatus.PENDING -> "Pending"
        TaskStatus.IN_PROGRESS -> "In Progress"
        TaskStatus.WAITING_INPUT -> "Waiting Input"
        TaskStatus.COMPLETED -> "Completed"
        TaskStatus.FAILED -> "Failed"
    }

internal val TaskType.displayName: String
    get() = when (this) {
        TaskType.IMPLEMENTATION -> "Implementation"
        TaskType.ARCHITECTURE -> "Architecture"
        TaskType.REVIEW -> "Review"
        TaskType.RESEARCH -> "Research"
        TaskType.TESTING -> "Testing"
        TaskType.DOCUMENTATION -> "Documentation"
        TaskType.PLANNING -> "Planning"
        TaskType.BUGFIX -> "Bug Fix"
    }
