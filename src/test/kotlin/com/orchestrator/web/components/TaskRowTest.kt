package com.orchestrator.web.components

import com.orchestrator.web.components.StatusBadge.Tone
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskRowTest {

    @Test
    fun `row includes hx attributes and renders status badges`() {
        val model = TaskRow.Model(
            id = "123",
            title = "Investigate outage",
            status = TaskRow.Status(label = "In Progress", tone = Tone.WARNING),
            type = TaskRow.Type(label = "Consensus", tone = Tone.INFO),
            routing = "Consensus",
            assignees = listOf("Codex", "Claude"),
            complexity = 7,
            risk = 4,
            detailUrl = "/tasks/123",
            editUrl = "/tasks/123/edit",
            updatedAt = Instant.parse("2025-01-01T12:00:00Z"),
            referenceInstant = Instant.parse("2025-01-01T14:00:00Z"),
            zoneId = ZoneId.of("UTC"),
            hxTarget = "#modal",
            hxSwap = "innerHTML",
            hxIndicator = "#modal-indicator"
        )

        val row = TaskRow.toRow(model)

        assertEquals("task-row-123", row.id)
        assertEquals("data-table__row task-row", row.attributes["class"])
        assertEquals("/tasks/123", row.attributes["hx-get"])
        assertEquals("#modal", row.attributes["hx-target"])
        assertEquals("click", row.attributes["hx-trigger"])
        assertEquals("#modal-indicator", row.attributes["hx-indicator"])

        val idCell = row.cells[0]
        assertTrue(idCell.content.contains("#123"))

        val titleCell = row.cells[1]
        assertTrue(titleCell.content.contains("Investigate outage"))
        assertTrue(titleCell.content.contains("Complexity 7"))
        assertTrue(titleCell.content.contains("Risk 4"))

        val statusCell = row.cells[2]
        assertTrue(statusCell.content.contains("badge--warning"))

        val typeCell = row.cells[3]
        assertTrue(typeCell.content.contains("badge--info"))

        val agentsCell = row.cells[4]
        assertTrue(agentsCell.content.contains("task-row__agent"))
        assertTrue(agentsCell.content.contains("Codex"))
        assertTrue(agentsCell.content.contains("Claude"))

        val timestampCell = row.cells[5]
        assertTrue(timestampCell.content.contains("2 hours ago"))
        assertTrue(timestampCell.content.contains("title=\"2025-01-01 12:00 UTC\""))

        val actionCell = row.cells[6]
        assertTrue(actionCell.content.contains("task-row__action--view"))
        assertTrue(actionCell.content.contains("hx-trigger=\"click consume\""))
    }

    @Test
    fun `unassigned tasks show placeholder`() {
        val model = TaskRow.Model(
            id = "456",
            title = "Schedule retro",
            status = TaskRow.Status(label = "Ready", tone = Tone.SUCCESS),
            type = TaskRow.Type(label = "Simple"),
            routing = "Solo",
            assignees = emptyList(),
            complexity = 3,
            risk = 2,
            detailUrl = "/tasks/456",
            editUrl = "/tasks/456/edit",
            createdAt = Instant.parse("2025-01-01T10:00:00Z"),
            referenceInstant = Instant.parse("2025-01-02T10:00:00Z"),
            zoneId = ZoneId.of("UTC")
        )

        val row = TaskRow.toRow(model)

        val assigneeCell = row.cells[4]
        assertTrue(assigneeCell.content.contains("Unassigned"))
        val timestampCell = row.cells[5]
        assertTrue(timestampCell.content.contains("1 day ago"))
    }
}
