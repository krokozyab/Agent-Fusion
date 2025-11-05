package com.orchestrator.web.components

import com.orchestrator.domain.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue

class ProposalListTest {

    @Test
    fun `proposal list renders multiple proposals`() {
        val proposal1 = Proposal(
            id = ProposalId("prop-1"),
            taskId = TaskId("task-1"),
            agentId = AgentId("agent-1"),
            inputType = InputType.ARCHITECTURAL_PLAN,
            content = "Proposal 1",
            confidence = 0.9,
            tokenUsage = TokenUsage(10, 20)
        )
        val proposal2 = Proposal(
            id = ProposalId("prop-2"),
            taskId = TaskId("task-1"),
            agentId = AgentId("agent-2"),
            inputType = InputType.IMPLEMENTATION_PLAN,
            content = "Proposal 2",
            confidence = 0.8,
            tokenUsage = TokenUsage(30, 40)
        )

        val model = ProposalList.Model(
            proposals = listOf(
                ProposalCard.Model(proposal = proposal1, agentName = "Agent 1", agentAvatarUrl = "avatar1.png"),
                ProposalCard.Model(proposal = proposal2, agentName = "Agent 2", agentAvatarUrl = "avatar2.png", isWinner = true)
            )
        )

        val html = ProposalList.render(model)

        assertTrue(html.contains("proposal-list-container"))
        assertTrue(html.contains("Proposals"))
        assertTrue(html.contains("Agent 1"))
        assertTrue(html.contains("Proposal 1"))
        assertTrue(html.contains("Agent 2"))
        assertTrue(html.contains("Proposal 2"))
        assertTrue(html.contains("proposal-card--winner"))
    }

    @Test
    fun `proposal list shows empty state`() {
        val model = ProposalList.Model(proposals = emptyList())

        val html = ProposalList.render(model)

        assertTrue(html.contains("proposal-list--empty"))
        assertTrue(html.contains("No proposals submitted yet."))
    }
}
