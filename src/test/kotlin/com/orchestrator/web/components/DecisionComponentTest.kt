package com.orchestrator.web.components

import com.orchestrator.domain.AgentId
import com.orchestrator.domain.Decision
import com.orchestrator.domain.DecisionId
import com.orchestrator.domain.InputType
import com.orchestrator.domain.ProposalId
import com.orchestrator.domain.ProposalRef
import com.orchestrator.domain.TaskId
import com.orchestrator.domain.TokenUsage
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertTrue

class DecisionComponentTest {

    @Test
    fun `consensus decision highlights winner and savings`() {
        val decision = Decision(
            id = DecisionId("dec-123"),
            taskId = TaskId("task-456"),
            considered = listOf(
                ProposalRef(
                    id = ProposalId("prop-1"),
                    agentId = AgentId("agent-1"),
                    inputType = InputType.ARCHITECTURAL_PLAN,
                    confidence = 0.9,
                    tokenUsage = TokenUsage(10, 20)
                ),
                ProposalRef(
                    id = ProposalId("prop-2"),
                    agentId = AgentId("agent-2"),
                    inputType = InputType.IMPLEMENTATION_PLAN,
                    confidence = 0.8,
                    tokenUsage = TokenUsage(30, 40)
                )
            ),
            selected = setOf(ProposalId("prop-1")),
            winnerProposalId = ProposalId("prop-1"),
            agreementRate = 0.95,
            rationale = "Proposal 1 is the most comprehensive.",
            decidedAt = Instant.parse("2025-10-20T11:00:00Z"),
            metadata = mapOf("strategyTrail" to "VOTING,REASONING_QUALITY")
        )

        val html = DecisionComponent.render(
            DecisionComponent.Model(decision = decision, zoneId = ZoneId.of("UTC"))
        )

        assertTrue(html.contains("Consensus Achieved"), "should show consensus status")
        assertTrue(html.contains("Using Voting"), "should render primary strategy badge")
        assertTrue(html.contains("70 tokens"), "should display token savings total")
        assertTrue(html.contains("70.00%"), "should display token savings percent")
        assertTrue(html.contains("Winning proposal prop-1 selected via consensus."), "should narrate outcome")
        assertTrue(html.contains("Strategy Sequence:") && html.contains("Voting -&gt; Reasoning Quality"), "should show strategy trail")
        assertTrue(html.contains("Proposal 1 is the most comprehensive."), "should render rationale text")
    }

    @Test
    fun `no consensus renders fallback messaging`() {
        val decision = Decision(
            id = DecisionId("dec-999"),
            taskId = TaskId("task-789"),
            considered = listOf(
                ProposalRef(
                    id = ProposalId("prop-a"),
                    agentId = AgentId("agent-a"),
                    inputType = InputType.RESEARCH_SUMMARY,
                    confidence = 0.5,
                    tokenUsage = TokenUsage(0, 0)
                )
            ),
            selected = emptySet(),
            winnerProposalId = null,
            agreementRate = null,
            rationale = null,
            decidedAt = Instant.parse("2025-01-01T00:00:00Z"),
            metadata = emptyMap()
        )

        val html = DecisionComponent.render(
            DecisionComponent.Model(decision = decision, zoneId = ZoneId.of("UTC"))
        )

        assertTrue(html.contains("No Consensus Reached"), "should indicate no consensus state")
        assertTrue(html.contains("Using Consensus"), "should fall back to default indicator label")
        assertTrue(html.contains("0 tokens"), "should report zero token savings")
        assertTrue(html.contains("No consensus reached yet"), "should include guidance message")
        assertTrue(html.contains("No rationale provided."), "should show missing rationale placeholder")
    }
}
