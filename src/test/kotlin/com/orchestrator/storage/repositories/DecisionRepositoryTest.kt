package com.orchestrator.storage.repositories

import com.orchestrator.domain.AgentId
import com.orchestrator.domain.Decision
import com.orchestrator.domain.DecisionId
import com.orchestrator.domain.InputType
import com.orchestrator.domain.ProposalId
import com.orchestrator.domain.ProposalRef
import com.orchestrator.domain.RoutingStrategy
import com.orchestrator.domain.Task
import com.orchestrator.domain.TaskId
import com.orchestrator.domain.TaskStatus
import com.orchestrator.domain.TaskType
import com.orchestrator.domain.TokenUsage
import com.orchestrator.storage.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Assertions.assertDoesNotThrow

class DecisionRepositoryTest {

    @BeforeEach
    fun setUp() {
        Database.shutdown()
        File("data").deleteRecursively()
        Database.getConnection()
    }

    @AfterEach
    fun tearDown() {
        Database.shutdown()
        File("data").deleteRecursively()
    }

    @Test
    fun `upsert updates existing decision without deleting referenced snapshots`() {
        val task = Task(
            id = TaskId("task-${UUID.randomUUID()}"),
            title = "Test Task",
            type = TaskType.DOCUMENTATION,
            status = TaskStatus.IN_PROGRESS,
            routing = RoutingStrategy.CONSENSUS,
            complexity = 3,
            risk = 2,
            createdAt = Instant.now()
        )
        TaskRepository.insert(task)

        val decision = Decision(
            id = DecisionId("dec-${UUID.randomUUID()}"),
            taskId = task.id,
            considered = listOf(
                ProposalRef(
                    id = ProposalId("prop-${UUID.randomUUID()}"),
                    agentId = AgentId("agent-1"),
                    inputType = InputType.RESEARCH_SUMMARY,
                    confidence = 0.8,
                    tokenUsage = TokenUsage(inputTokens = 100, outputTokens = 50)
                )
            ),
            rationale = "initial rationale",
            decidedAt = Instant.now()
        )
        DecisionRepository.insert(decision)

        SnapshotRepository.insert(
            taskId = task.id,
            decisionId = decision.id,
            label = "initial",
            snapshotJson = "{\"state\":\"before\"}"
        )

        val updatedDecision = decision.copy(
            rationale = "updated rationale",
            agreementRate = 0.7,
            metadata = mapOf("source" to "test"),
            decidedAt = decision.decidedAt.plusSeconds(60)
        )

        assertDoesNotThrow {
            DecisionRepository.upsert(updatedDecision)
        }

        val stored = DecisionRepository.findById(decision.id)
        assertNotNull(stored)
        assertEquals("updated rationale", stored!!.rationale)
        assertEquals(0.7, stored.agreementRate)
        assertEquals(updatedDecision.decidedAt, stored.decidedAt)
        assertEquals("test", stored.metadata["source"])
    }
}
