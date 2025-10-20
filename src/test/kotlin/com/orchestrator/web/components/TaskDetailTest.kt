package com.orchestrator.web.components

import com.orchestrator.web.components.StatusBadge.Tone
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue

class TaskDetailTest {

    @Test
    fun `task detail renders all sections`() {
        val model = TaskDetail.Model(
            id = "DASH-020",
            title = "Task Detail Component",
            status = TaskRow.Status(label = "In Progress", tone = Tone.WARNING),
            type = TaskRow.Type(label = "Implementation"),
            description = "Create comprehensive task detail view component",
            metadata = mapOf(
                "Priority" to "P0",
                "Time" to "4h",
                "Dependencies" to "DASH-019",
                "Assigned To" to "Frontend Developer",
                "Phase" to "2-TaskDetail",
                "Component" to "UI"
            ),
            complexity = 8,
            risk = 5,
            proposals = listOf(
                TaskDetail.Proposal(
                    agentId = "agent-1",
                    content = "Proposal 1 content",
                    confidence = 0.9
                )
            ),
            decision = TaskDetail.Decision(
                rationale = "Decision rationale",
                winnerProposalId = "proposal-1"
            ),
            createdAt = Instant.parse("2025-10-19T10:00:00Z"),
            updatedAt = Instant.parse("2025-10-19T11:00:00Z")
        )

        val html = TaskDetail.render(model)

        assertTrue(html.contains("DASH-020"))
        assertTrue(html.contains("Task Detail Component"))
        assertTrue(html.contains("badge--warning"))
        assertTrue(html.contains("Create comprehensive task detail view component"))
        assertTrue(html.contains("Priority"))
        assertTrue(html.contains("P0"))
        assertTrue(html.contains("Complexity"))
        assertTrue(html.contains("8"))
        assertTrue(html.contains("Risk"))
        assertTrue(html.contains("5"))
        assertTrue(html.contains("agent-1"))
        assertTrue(html.contains("Proposal 1 content"))
        assertTrue(html.contains("0.9"))
        assertTrue(html.contains("Decision rationale"))
        assertTrue(html.contains("proposal-1"))
        assertTrue(html.contains("hx-delete=\"/tasks/DASH-020\""))
    }
}
