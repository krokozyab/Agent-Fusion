package com.orchestrator.mcp.tools

import com.orchestrator.domain.*
import com.orchestrator.modules.consensus.ProposalManager
import com.orchestrator.modules.context.ContextModule
import com.orchestrator.storage.repositories.ProposalRepository
import com.orchestrator.storage.repositories.TaskRepository
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class RespondToTaskToolTest {

    private val tool = RespondToTaskTool()
    private val taskId = TaskId("task-123")
    private val agentId = AgentId("claude-code")

    @BeforeEach
    fun setup() {
        mockkObject(TaskRepository)
        mockkObject(ProposalRepository)
        mockkObject(ProposalManager)
        mockkObject(ContextModule)
    }

    @AfterEach
    fun cleanup() {
        unmockkAll()
    }

    @Test
    fun `execute - successfully responds to pending task`() {
        // Given
        val task = Task(
            id = taskId,
            title = "Test task",
            description = "Test description",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.PENDING,
            routing = RoutingStrategy.SOLO,
            assigneeIds = setOf(agentId),
            dependencies = emptySet(),
            complexity = 5,
            risk = 5,
            createdAt = Instant.now(),
            updatedAt = null,
            dueAt = null,
            metadata = emptyMap()
        )

        val proposal = Proposal(
            id = ProposalId("prop-1"),
            taskId = taskId,
            agentId = agentId,
            content = "Test response",
            inputType = InputType.IMPLEMENTATION_PLAN,
            confidence = 0.8,
            tokenUsage = TokenUsage(100, 200),
            createdAt = Instant.now(),
            metadata = emptyMap()
        )

        val context = ContextModule.TaskContext(
            taskId = taskId,
            history = emptyList(),
            fileHistory = emptyList()
        )

        every { TaskRepository.findById(taskId) } returns task
        every { ContextModule.getTaskContext(taskId, any()) } returns context
        every { ProposalManager.submitProposal(any(), any(), any(), any(), any(), any(), any()) } returns proposal
        every { TaskRepository.update(any()) } returns Unit
        every { ProposalRepository.findByTask(taskId) } returns listOf(proposal)

        val params = RespondToTaskTool.Params(
            taskId = taskId.value,
            agentId = null,
            response = RespondToTaskTool.ResponseContent(
                content = "Test response",
                inputType = "IMPLEMENTATION_PLAN",
                confidence = 0.8,
                metadata = null
            ),
            maxTokens = null
        )

        // When
        val result = tool.execute(params, agentId.value)

        // Then
        assertEquals(taskId.value, result.taskId)
        assertEquals(proposal.id.value, result.proposalId)
        assertEquals("IMPLEMENTATION_PLAN", result.inputType)
        // SOLO tasks auto-complete on response
        assertEquals("COMPLETED", result.taskStatus)
        assertNotNull(result.task)
        assertEquals(1, result.proposals.size)
        assertNotNull(result.context)

        verify { ProposalManager.submitProposal(taskId, agentId, "Test response", InputType.IMPLEMENTATION_PLAN, 0.8, any(), emptyMap()) }
        // SOLO tasks should be marked COMPLETED, not IN_PROGRESS
        verify { TaskRepository.update(match { it.status == TaskStatus.COMPLETED && it.metadata["completedBy"] == agentId.value }) }
    }

    @Test
    fun `execute - fails when task does not exist`() {
        // Given
        every { TaskRepository.findById(taskId) } returns null

        val params = RespondToTaskTool.Params(
            taskId = taskId.value,
            agentId = null,
            response = RespondToTaskTool.ResponseContent(
                content = "Test",
                inputType = null,
                confidence = null,
                metadata = null
            ),
            maxTokens = null
        )

        // When/Then
        val exception = assertThrows(IllegalArgumentException::class.java) {
            tool.execute(params, agentId.value)
        }
        assertTrue(exception.message!!.contains("does not exist"))
    }

    @Test
    fun `execute - fails when task is completed`() {
        // Given
        val completedTask = Task(
            id = taskId,
            title = "Test task",
            description = null,
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.COMPLETED,
            routing = RoutingStrategy.SOLO,
            assigneeIds = setOf(agentId),
            dependencies = emptySet(),
            complexity = 5,
            risk = 5,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            dueAt = null,
            metadata = emptyMap()
        )

        every { TaskRepository.findById(taskId) } returns completedTask

        val params = RespondToTaskTool.Params(
            taskId = taskId.value,
            agentId = null,
            response = RespondToTaskTool.ResponseContent(
                content = "Test",
                inputType = null,
                confidence = null,
                metadata = null
            ),
            maxTokens = null
        )

        // When/Then
        val exception = assertThrows(IllegalStateException::class.java) {
            tool.execute(params, agentId.value)
        }
        assertTrue(exception.message!!.contains("cannot respond to completed"))
    }

    @Test
    fun `execute - respects maxTokens parameter`() {
        // Given
        val task = Task(
            id = taskId,
            title = "Test task",
            description = null,
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.PENDING,
            routing = RoutingStrategy.SOLO,
            assigneeIds = setOf(agentId),
            dependencies = emptySet(),
            complexity = 5,
            risk = 5,
            createdAt = Instant.now(),
            updatedAt = null,
            dueAt = null,
            metadata = emptyMap()
        )

        val proposal = Proposal(
            id = ProposalId("prop-1"),
            taskId = taskId,
            agentId = agentId,
            content = "Test",
            inputType = InputType.OTHER,
            confidence = 0.5,
            tokenUsage = TokenUsage(),
            createdAt = Instant.now(),
            metadata = emptyMap()
        )

        val context = ContextModule.TaskContext(taskId, emptyList(), emptyList())

        every { TaskRepository.findById(taskId) } returns task
        every { ContextModule.getTaskContext(taskId, 10000) } returns context
        every { ProposalManager.submitProposal(any(), any(), any(), any(), any(), any(), any()) } returns proposal
        every { TaskRepository.update(any()) } returns Unit
        every { ProposalRepository.findByTask(taskId) } returns listOf(proposal)

        val params = RespondToTaskTool.Params(
            taskId = taskId.value,
            agentId = null,
            response = RespondToTaskTool.ResponseContent(
                content = "Test",
                inputType = null,
                confidence = null,
                metadata = null
            ),
            maxTokens = 10000
        )

        // When
        tool.execute(params, agentId.value)

        // Then
        verify { ContextModule.getTaskContext(taskId, 10000) }
    }

    @Test
    fun `execute - validates maxTokens bounds`() {
        // Given
        val task = Task(
            id = taskId,
            title = "Test",
            description = null,
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.PENDING,
            routing = RoutingStrategy.SOLO,
            assigneeIds = emptySet(),
            dependencies = emptySet(),
            complexity = 5,
            risk = 5,
            createdAt = Instant.now(),
            updatedAt = null,
            dueAt = null,
            metadata = emptyMap()
        )

        every { TaskRepository.findById(taskId) } returns task

        // Too low
        var params = RespondToTaskTool.Params(
            taskId = taskId.value,
            agentId = null,
            response = RespondToTaskTool.ResponseContent(content = "Test", inputType = null, confidence = null, metadata = null),
            maxTokens = 500
        )

        var exception = assertThrows(IllegalArgumentException::class.java) {
            tool.execute(params, agentId.value)
        }
        assertTrue(exception.message!!.contains("at least"))

        // Too high
        params = RespondToTaskTool.Params(
            taskId = taskId.value,
            agentId = null,
            response = RespondToTaskTool.ResponseContent(content = "Test", inputType = null, confidence = null, metadata = null),
            maxTokens = 200000
        )

        exception = assertThrows(IllegalArgumentException::class.java) {
            tool.execute(params, agentId.value)
        }
        assertTrue(exception.message!!.contains("exceeds policy limit"))
    }

    @Test
    fun `execute - consensus task moves to IN_PROGRESS after first response`() {
        // Given
        val consensusTask = Task(
            id = taskId,
            title = "Consensus task",
            description = "Test consensus",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.PENDING,
            routing = RoutingStrategy.CONSENSUS,
            assigneeIds = setOf(AgentId("claude-code"), AgentId("codex-cli")),
            dependencies = emptySet(),
            complexity = 8,
            risk = 8,
            createdAt = Instant.now(),
            updatedAt = null,
            dueAt = null,
            metadata = emptyMap()
        )

        val proposal = Proposal(
            id = ProposalId("prop-1"),
            taskId = taskId,
            agentId = agentId,
            content = "Architectural plan",
            inputType = InputType.ARCHITECTURAL_PLAN,
            confidence = 0.85,
            tokenUsage = TokenUsage(100, 200),
            createdAt = Instant.now(),
            metadata = emptyMap()
        )

        val context = ContextModule.TaskContext(
            taskId = taskId,
            history = emptyList(),
            fileHistory = emptyList()
        )

        every { TaskRepository.findById(taskId) } returns consensusTask
        every { ContextModule.getTaskContext(taskId, any()) } returns context
        every { ProposalManager.submitProposal(any(), any(), any(), any(), any(), any(), any()) } returns proposal
        every { TaskRepository.update(any()) } returns Unit
        every { ProposalRepository.findByTask(taskId) } returns listOf(proposal)

        val params = RespondToTaskTool.Params(
            taskId = taskId.value,
            agentId = null,
            response = RespondToTaskTool.ResponseContent(
                content = "Architectural plan",
                inputType = "ARCHITECTURAL_PLAN",
                confidence = 0.85,
                metadata = null
            ),
            maxTokens = null
        )

        // When
        val result = tool.execute(params, agentId.value)

        // Then
        assertEquals(taskId.value, result.taskId)
        assertEquals(proposal.id.value, result.proposalId)
        // CONSENSUS tasks should move to IN_PROGRESS, NOT auto-complete
        assertEquals("IN_PROGRESS", result.taskStatus)

        verify { ProposalManager.submitProposal(taskId, agentId, "Architectural plan", InputType.ARCHITECTURAL_PLAN, 0.85, any(), emptyMap()) }
        // Verify task status was updated to IN_PROGRESS
        verify { TaskRepository.update(match { it.status == TaskStatus.IN_PROGRESS }) }
    }
}
