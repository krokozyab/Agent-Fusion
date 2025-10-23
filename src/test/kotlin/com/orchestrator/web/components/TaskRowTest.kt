package com.orchestrator.web.components

import com.orchestrator.domain.AgentId
import com.orchestrator.domain.RoutingStrategy
import com.orchestrator.domain.Task
import com.orchestrator.domain.TaskId
import com.orchestrator.domain.TaskStatus
import com.orchestrator.domain.TaskType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue

class TaskRowTest {

    @Test
    fun `status and type badges render labels`() {
        val task = Task(
            id = TaskId("task-1"),
            title = "Sample task",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.PENDING,
            routing = RoutingStrategy.SOLO,
            assigneeIds = setOf(AgentId("alpha")),
            createdAt = Instant.parse("2025-10-21T08:00:00Z"),
            updatedAt = Instant.parse("2025-10-21T09:00:00Z")
        )

        val row = TaskRow.toRow(
            TaskRow.Model(
                id = task.id.value,
                title = task.title,
                status = TaskRow.Status(label = task.status.displayName, tone = task.status.toTone()),
                type = TaskRow.Type(label = task.type.displayName, tone = task.type.toTone()),
                routing = task.routing.name,
                assignees = task.assigneeIds.map { it.value },
                complexity = task.complexity,
                risk = task.risk,
                detailUrl = "/tasks/${task.id.value}",
                editUrl = "/tasks/${task.id.value}/edit",
                updatedAt = task.updatedAt,
                createdAt = task.createdAt
            )
        )

        val statusCell = row.cells[2]
        val typeCell = row.cells[3]

        assertTrue(statusCell.content.contains("Pending"), "Status badge should include label")
        assertTrue(typeCell.content.contains("Implementation"), "Type badge should include label")
    }
}
