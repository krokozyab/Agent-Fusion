package com.orchestrator.modules.consensus.strategies

import com.orchestrator.domain.AgentId
import com.orchestrator.domain.InputType
import com.orchestrator.domain.Proposal
import com.orchestrator.domain.ProposalId
import com.orchestrator.domain.TaskId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.time.Instant

class VotingStrategyTest {

    private fun mkProposal(
        id: String,
        content: Any?,
        confidence: Double,
        createdAt: Instant = Instant.parse("2025-01-01T00:00:00Z")
    ): Proposal {
        return Proposal(
            id = ProposalId(id),
            taskId = TaskId("T-1"),
            agentId = AgentId("A-$id"),
            inputType = InputType.OTHER,
            content = content,
            confidence = confidence,
            createdAt = createdAt
        )
    }

    @Test
    fun `empty proposals produce no consensus`() {
        val strategy = VotingStrategy()
        val result = strategy.evaluate(emptyList())
        assertFalse(result.agreed)
        assertNull(result.winningProposal)
        assertTrue(result.reasoning.contains("No proposals"))
    }

    @Test
    fun `single proposal meets default threshold (75%)`() {
        val strategy = VotingStrategy()
        val p1 = mkProposal("1", content = "optA", confidence = 0.5)
        val result = strategy.evaluate(listOf(p1))
        assertTrue(result.agreed)
        assertEquals(p1, result.winningProposal)
        assertTrue(result.reasoning.contains("Consensus reached"))
    }

    @Test
    fun `majority but below threshold yields no consensus`() {
        val strategy = VotingStrategy(threshold = 0.8)
        val p1 = mkProposal("1", "A", 0.9)
        val p2 = mkProposal("2", "A", 0.4)
        val p3 = mkProposal("3", "B", 0.6)
        // A has 2/3 = 66.7% which is below 80%
        val result = strategy.evaluate(listOf(p1, p2, p3))
        assertFalse(result.agreed)
        assertNull(result.winningProposal)
        assertTrue(result.reasoning.contains("below threshold"))
    }

    @Test
    fun `clear majority above threshold selects highest-confidence winner deterministically`() {
        val strategy = VotingStrategy(threshold = 0.6)
        val base = Instant.parse("2025-01-01T00:00:00Z")
        val p1 = mkProposal("1", "X", 0.6, base.plusSeconds(10))
        val p2 = mkProposal("2", "X", 0.9, base.plusSeconds(20))
        val p3 = mkProposal("3", "X", 0.7, base.plusSeconds(30))
        val p4 = mkProposal("4", "Y", 0.95, base.plusSeconds(40))
        val result = strategy.evaluate(listOf(p1, p2, p3, p4))
        // X has 3/4 = 75% ≥ 60%; winner among X should be p2 (highest confidence)
        assertTrue(result.agreed)
        assertEquals(p2, result.winningProposal)
        assertTrue(result.reasoning.contains("meets threshold"))
        val details = result.details
        assertEquals(4, details["totalProposals"])
        assertEquals(3, details["winningOptionVotes"])
    }

    @Test
    fun `tie on top at or above threshold yields no consensus`() {
        // With threshold = 0.5 and 4 proposals tied 2-2, ratio = 50% which meets threshold
        // but tie must result in no consensus
        val strategy = VotingStrategy(threshold = 0.5)
        val p1 = mkProposal("1", "A", 0.8)
        val p2 = mkProposal("2", "A", 0.7)
        val p3 = mkProposal("3", "B", 0.9)
        val p4 = mkProposal("4", "B", 0.6)
        val result = strategy.evaluate(listOf(p1, p2, p3, p4))
        assertFalse(result.agreed)
        assertNull(result.winningProposal)
        assertTrue(result.reasoning.contains("Tie detected"))
    }

    @Test
    fun `content equality drives approvals even with different agents and confidences`() {
        val strategy = VotingStrategy(threshold = 2.0/3.0)
        val p1 = mkProposal("1", mapOf("answer" to 42), 0.3)
        val p2 = mkProposal("2", mapOf("answer" to 42), 0.9)
        val p3 = mkProposal("3", mapOf("answer" to 43), 0.99)
        val result = strategy.evaluate(listOf(p1, p2, p3))
        // Two proposals with identical content should be grouped; 2/3 meets the custom threshold
        assertTrue(result.agreed)
        assertNotNull(result.winningProposal)
        assertEquals("2", result.winningProposal!!.id.value) // highest confidence within the majority group
    }
}