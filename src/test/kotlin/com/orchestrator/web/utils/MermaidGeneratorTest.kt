package com.orchestrator.web.utils

import com.orchestrator.domain.AgentId
import com.orchestrator.domain.Decision
import com.orchestrator.domain.DecisionId
import com.orchestrator.domain.InputType
import com.orchestrator.domain.Proposal
import com.orchestrator.domain.ProposalId
import com.orchestrator.domain.ProposalRef
import com.orchestrator.domain.RoutingStrategy
import com.orchestrator.domain.Task
import com.orchestrator.domain.TaskId
import com.orchestrator.domain.TaskStatus
import com.orchestrator.domain.TaskType
import com.orchestrator.domain.TokenUsage
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue

class MermaidGeneratorTest {

    @Test
    fun `consensus diagram includes consensus and decision notes`() {
        val task = Task(
            id = TaskId("task-1"),
            title = "Consensus Demo",
            type = TaskType.IMPLEMENTATION,
            routing = RoutingStrategy.CONSENSUS,
            status = TaskStatus.COMPLETED,
            assigneeIds = setOf(AgentId("alpha"), AgentId("beta"))
        )

        val proposal1 = Proposal(
            id = ProposalId("prop-1"),
            taskId = task.id,
            agentId = AgentId("alpha"),
            inputType = InputType.ARCHITECTURAL_PLAN,
            content = "Plan A",
            confidence = 0.8,
            tokenUsage = TokenUsage(50, 10),
            createdAt = Instant.parse("2025-10-20T10:00:00Z")
        )
        val proposal2 = Proposal(
            id = ProposalId("prop-2"),
            taskId = task.id,
            agentId = AgentId("beta"),
            inputType = InputType.IMPLEMENTATION_PLAN,
            content = "Plan B",
            confidence = 0.7,
            tokenUsage = TokenUsage(30, 5),
            createdAt = Instant.parse("2025-10-20T10:05:00Z")
        )

        val decision = Decision(
            id = DecisionId("dec-1"),
            taskId = task.id,
            considered = listOf(
                ProposalRef(
                    id = proposal1.id,
                    agentId = proposal1.agentId,
                    inputType = proposal1.inputType,
                    confidence = proposal1.confidence,
                    tokenUsage = proposal1.tokenUsage
                ),
                ProposalRef(
                    id = proposal2.id,
                    agentId = proposal2.agentId,
                    inputType = proposal2.inputType,
                    confidence = proposal2.confidence,
                    tokenUsage = proposal2.tokenUsage
                )
            ),
            selected = setOf(proposal1.id),
            winnerProposalId = proposal1.id,
            agreementRate = 0.9,
            rationale = "Plan A is more detailed."
        )

        val mermaid = MermaidGenerator.buildTaskSequence(task, listOf(proposal1, proposal2), decision)

        assertTrue(mermaid.contains("sequenceDiagram"))
        assertTrue(mermaid.contains("participant Task as Task"))
        assertTrue(mermaid.contains("participant Consensus as Consensus Engine"))
        assertTrue(mermaid.contains("Agent1->>Consensus"))
        assertTrue(mermaid.contains("Consensus->>Decision"))
        assertTrue(mermaid.contains("Note over Decision"))
    }

    @Test
    fun `solo routing diagram highlights single agent`() {
        val task = Task(
            id = TaskId("solo-1"),
            title = "Solo Task",
            type = TaskType.REVIEW,
            routing = RoutingStrategy.SOLO,
            status = TaskStatus.IN_PROGRESS,
            assigneeIds = setOf(AgentId("solo-agent"))
        )

        val proposal = Proposal(
            id = ProposalId("prop-solo"),
            taskId = task.id,
            agentId = AgentId("solo-agent"),
            inputType = InputType.CODE_REVIEW,
            content = "Review content",
            confidence = 0.6,
            tokenUsage = TokenUsage(12, 18),
            createdAt = Instant.parse("2025-10-20T09:00:00Z")
        )

        val mermaid = MermaidGenerator.buildTaskSequence(task, listOf(proposal), null)

        assertTrue(mermaid.contains("participant Router as Routing (Solo)"))
        assertTrue(mermaid.contains("Router->>Agent1: Assign task"))
        assertTrue(mermaid.contains("Agent1->>Router: Acknowledge assignment"))
        assertTrue(mermaid.contains("Agent1->>Completion: Return implementation plan"))
    }
}
