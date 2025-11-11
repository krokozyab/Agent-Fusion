package com.orchestrator.web.dto

import com.orchestrator.domain.AgentId
import com.orchestrator.domain.Decision
import com.orchestrator.domain.DecisionId
import com.orchestrator.domain.ProposalRef
import com.orchestrator.domain.InputType
import com.orchestrator.domain.Proposal
import com.orchestrator.domain.ProposalId
import com.orchestrator.domain.RoutingStrategy
import com.orchestrator.domain.Task
import com.orchestrator.domain.TaskId
import com.orchestrator.domain.TaskStatus
import com.orchestrator.domain.TaskType
import com.orchestrator.domain.TokenUsage
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TaskDTOTest {

    private val nowInstant = Instant.parse("2025-01-01T00:00:00Z")
    private val clock: Clock = Clock.fixed(nowInstant, ZoneOffset.UTC)

    @Test
    fun `task mapping includes computed fields`() {
        val created = nowInstant.minusSeconds(9_000) // 2h 30m
        val updated = nowInstant.minusSeconds(3_600)
        val task = Task(
            id = TaskId("TASK-1"),
            title = "Implement feature",
            description = "Details",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.IN_PROGRESS,
            routing = RoutingStrategy.CONSENSUS,
            assigneeIds = setOf(AgentId("alpha"), AgentId("beta")),
            dependencies = setOf(TaskId("TASK-0")),
            complexity = 7,
            risk = 4,
            createdAt = created,
            updatedAt = updated,
            dueAt = nowInstant.plusSeconds(3_600),
            metadata = mapOf("priority" to "high")
        )

        val dto = task.toTaskDTO(clock)

        assertEquals("TASK-1", dto.id)
        assertEquals("Implement feature", dto.title)
        assertEquals("IMPLEMENTATION", dto.type)
        assertEquals("IN_PROGRESS", dto.status)
        assertEquals(listOf("alpha", "beta"), dto.assigneeIds)
        assertEquals(listOf("TASK-0"), dto.dependencyIds)
        assertEquals("2024-12-31T21:30:00Z", dto.createdAt)
        assertEquals("2024-12-31T23:00:00Z", dto.updatedAt)
        assertEquals("2025-01-01T01:00:00Z", dto.dueAt)
        assertEquals("2h 30m 0s", dto.age)
        assertEquals("1h 30m 0s", dto.duration)
        assertEquals("high", dto.metadata["priority"])
    }

    @Test
    fun `task list mapping wraps pagination metadata`() {
        val task = Task(
            id = TaskId("TASK-2"),
            title = "Review PR",
            type = TaskType.REVIEW,
            createdAt = nowInstant.minusSeconds(600)
        )

        val dto = mapTaskListDTO(
            tasks = listOf(task),
            page = 2,
            pageSize = 20,
            totalCount = 80,
            sortField = "createdAt",
            sortDirection = TaskListDTO.SortInfo.Direction.DESC,
            filters = mapOf("status" to "PENDING"),
            clock = clock
        )

        assertEquals(2, dto.page)
        assertEquals(20, dto.pageSize)
        assertEquals(80, dto.totalCount)
        assertNotNull(dto.sort)
        assertEquals("createdAt", dto.sort?.field)
        assertEquals(TaskListDTO.SortInfo.Direction.DESC, dto.sort?.direction)
        assertEquals("PENDING", dto.filters["status"])
        assertEquals(1, dto.items.size)
    }

    @Test
    fun `task detail mapping includes proposals and decisions`() {
        val task = Task(
            id = TaskId("TASK-3"),
            title = "Plan architecture",
            type = TaskType.ARCHITECTURE,
            createdAt = nowInstant.minusSeconds(1200)
        )

        val proposal = Proposal(
            id = ProposalId("P-1"),
            taskId = task.id,
            agentId = AgentId("codex"),
            inputType = InputType.ARCHITECTURAL_PLAN,
            content = mapOf("summary" to "Plan"),
            confidence = 0.8,
            tokenUsage = TokenUsage(100, 200),
            createdAt = nowInstant.minusSeconds(600)
        )

        val decision = Decision(
            id = DecisionId("D-1"),
            taskId = task.id,
            considered = listOf(
                ProposalRef(
                    id = proposal.id,
                    agentId = proposal.agentId,
                    inputType = proposal.inputType,
                    confidence = proposal.confidence,
                    tokenUsage = proposal.tokenUsage
                )
            ),
            selected = setOf(proposal.id),
            winnerProposalId = proposal.id,
            agreementRate = 0.9,
            rationale = "Strong consensus"
        )

        val detail = mapTaskDetailDTO(
            task = task,
            proposals = listOf(proposal),
            decision = decision,
            mermaid = "graph TD;"
        )

        assertEquals("TASK-3", detail.task.id)
        assertEquals(1, detail.proposals.size)
        assertEquals("P-1", detail.proposals.first().id)
        assertNotNull(detail.decision)
        assertEquals("D-1", detail.decision?.id)
        assertEquals("graph TD;", detail.mermaid)
        assertTrue(detail.decision?.tokenSavingsPercent ?: 0.0 >= 0.0)
    }
}
