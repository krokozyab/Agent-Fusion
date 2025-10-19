package com.orchestrator.web.dto

import com.orchestrator.domain.Decision
import com.orchestrator.domain.Proposal
import com.orchestrator.domain.Task
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val isoFormatter: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)

fun Task.toTaskDTO(clock: Clock = Clock.systemUTC()): TaskDTO {
    val now = Instant.now(clock)
    val ageDuration = Duration.between(createdAt, now)
    val updatedDuration = updatedAt?.let { Duration.between(createdAt, it) }

    return TaskDTO(
        id = id.value,
        title = title,
        description = description,
        type = type.name,
        status = status.name,
        routing = routing.name,
        assigneeIds = assigneeIds.map { it.value }.sorted(),
        dependencyIds = dependencies.map { it.value }.sorted(),
        complexity = complexity,
        risk = risk,
        createdAt = isoFormatter.format(createdAt),
        updatedAt = updatedAt?.let(isoFormatter::format),
        dueAt = dueAt?.let(isoFormatter::format),
        age = ageDuration.humanize(),
        duration = updatedDuration?.humanize(),
        metadata = metadata
    )
}

fun List<Task>.toTaskDTOs(clock: Clock = Clock.systemUTC()): List<TaskDTO> =
    map { it.toTaskDTO(clock) }

fun mapTaskListDTO(
    tasks: List<Task>,
    page: Int,
    pageSize: Int,
    totalCount: Int,
    sortField: String? = null,
    sortDirection: TaskListDTO.SortInfo.Direction? = null,
    filters: Map<String, String> = emptyMap(),
    clock: Clock = Clock.systemUTC()
): TaskListDTO {
    val sort = if (sortField != null && sortDirection != null) {
        TaskListDTO.SortInfo(sortField, sortDirection)
    } else null

    return TaskListDTO(
        items = tasks.toTaskDTOs(clock),
        page = page,
        pageSize = pageSize,
        totalCount = totalCount,
        sort = sort,
        filters = filters
    )
}

fun mapTaskDetailDTO(
    task: Task,
    proposals: List<Proposal>,
    decision: Decision?,
    mermaid: String?,
    clock: Clock = Clock.systemUTC()
): TaskDetailDTO {
    val proposalDtos = proposals.map { it.toDTO() }
    val decisionDto = decision?.toDTO()

    return TaskDetailDTO(
        task = task.toTaskDTO(clock),
        proposals = proposalDtos,
        decision = decisionDto,
        mermaid = mermaid
    )
}

private fun Proposal.toDTO(): TaskDetailDTO.ProposalDTO = TaskDetailDTO.ProposalDTO(
    id = id.value,
    agentId = agentId.value,
    inputType = inputType.name,
    confidence = confidence,
    tokenUsage = TaskDetailDTO.TokenUsageDTO(
        inputTokens = tokenUsage.inputTokens,
        outputTokens = tokenUsage.outputTokens,
        totalTokens = tokenUsage.totalTokens
    ),
    createdAt = isoFormatter.format(createdAt),
    content = content,
    metadata = metadata
)

private fun Decision.toDTO(): TaskDetailDTO.DecisionDTO = TaskDetailDTO.DecisionDTO(
    id = id.value,
    decidedAt = isoFormatter.format(decidedAt),
    agreementRate = agreementRate,
    rationale = rationale,
    selected = selected.map { it.value }.toSet(),
    winnerProposalId = winnerProposalId?.value,
    considered = considered.map { ref ->
        TaskDetailDTO.DecisionDTO.ConsideredProposalDTO(
            id = ref.id.value,
            agentId = ref.agentId.value,
            inputType = ref.inputType.name,
            confidence = ref.confidence,
            tokenUsage = TaskDetailDTO.TokenUsageDTO(
                inputTokens = ref.tokenUsage.inputTokens,
                outputTokens = ref.tokenUsage.outputTokens,
                totalTokens = ref.tokenUsage.totalTokens
            )
        )
    },
    tokenSavingsAbsolute = tokenSavingsAbsolute,
    tokenSavingsPercent = tokenSavingsPercent
)

private fun Duration.humanize(): String {
    var seconds = this.seconds
    if (seconds < 0) seconds = 0
    val days = seconds / 86_400
    seconds -= days * 86_400
    val hours = seconds / 3_600
    seconds -= hours * 3_600
    val minutes = seconds / 60
    seconds -= minutes * 60

    val parts = mutableListOf<String>()
    if (days > 0) parts += "${days}d"
    if (hours > 0 || parts.isNotEmpty()) parts += "${hours}h"
    if (minutes > 0 || parts.isNotEmpty()) parts += "${minutes}m"
    parts += "${seconds}s"
    return parts.joinToString(" ")
}
