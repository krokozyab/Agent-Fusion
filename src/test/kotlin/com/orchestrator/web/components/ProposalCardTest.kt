package com.orchestrator.web.components

import com.orchestrator.domain.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue

class ProposalCardTest {

    @Test
    fun `proposal card renders correctly`() {
        val proposal = Proposal(
            id = ProposalId("prop-123"),
            taskId = TaskId("task-456"),
            agentId = AgentId("agent-789"),
            inputType = InputType.IMPLEMENTATION_PLAN,
            content = "Here is the implementation plan.",
            confidence = 0.85,
            tokenUsage = TokenUsage(100, 200),
            createdAt = Instant.parse("2025-10-20T10:00:00Z")
        )

        val model = ProposalCard.Model(
            proposal = proposal,
            agentName = "Test Agent",
            agentAvatarUrl = "/static/images/agent-avatar.png",
        )

                        assertTrue(html.contains("proposal-card"))

                        assertTrue(html.contains("Test Agent"))

                        assertTrue(html.contains("/static/images/agent-avatar.png"))

                        assertTrue(html.contains("Here is the implementation plan."))

                        assertTrue(html.contains("Confidence: "))

                        assertTrue(html.contains("0.85"))

                        assertTrue(html.contains("Tokens: 300"))

                
    }

    @Test
    fun `winning proposal card has winner class`() {
        val proposal = Proposal(
            id = ProposalId("prop-123"),
            taskId = TaskId("task-456"),
            agentId = AgentId("agent-789"),
            inputType = InputType.IMPLEMENTATION_PLAN,
            content = "Here is the implementation plan.",
            confidence = 0.85,
            tokenUsage = TokenUsage(100, 200),
            createdAt = Instant.parse("2025-10-20T10:00:00Z")
        )

        val model = ProposalCard.Model(
            proposal = proposal,
            agentName = "Test Agent",
            agentAvatarUrl = "/static/images/agent-avatar.png",
            isWinner = true
        )

        val html = ProposalCard.render(model)

        assertTrue(html.contains("proposal-card--winner"))
        assertTrue(html.contains("Winner"))
    }
}
