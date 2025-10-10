package com.orchestrator.mcp.tools

import com.orchestrator.domain.*
import com.orchestrator.storage.Database
import com.orchestrator.storage.repositories.*
import kotlin.test.*
import java.time.Instant

class ContinueTaskToolTest {

    @BeforeTest
    fun resetDb() {
        Database.withConnection { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("DELETE FROM context_snapshots")
                st.executeUpdate("DELETE FROM conversation_messages")
                st.executeUpdate("DELETE FROM decisions")
                st.executeUpdate("DELETE FROM proposals")
                st.executeUpdate("DELETE FROM metrics_timeseries")
                st.executeUpdate("DELETE FROM tasks")
            }
        }
    }

    @Test
    fun returns_task_with_proposals_and_context() {
        val now = Instant.now()
        val task = Task(
            id = TaskId("task-continue"),
            title = "Continue this task",
            description = "Test continuation",
            type = TaskType.ARCHITECTURE,
            status = TaskStatus.PENDING,
            routing = RoutingStrategy.CONSENSUS,
            assigneeIds = setOf(AgentId("claude-code"), AgentId("codex-cli")),
            dependencies = emptySet(),
            complexity = 5,
            risk = 5,
            createdAt = now,
            updatedAt = now
        )
        TaskRepository.insert(task)

        // Add proposals
        val proposal1 = Proposal(
            id = ProposalId("prop-1"),
            taskId = task.id,
            agentId = AgentId("claude-code"),
            inputType = InputType.ARCHITECTURAL_PLAN,
            confidence = 0.85,
            tokenUsage = TokenUsage(inputTokens = 200, outputTokens = 300),
            content = "Approach A",
            createdAt = now
        )
        ProposalRepository.insert(proposal1)

        // Add conversation message
        MessageRepository.insert(
            taskId = task.id,
            role = "user",
            content = "Please design this",
            tokens = 4,
            agentId = AgentId("claude-code"),
            metadataJson = null,
            ts = now
        )

        val tool = ContinueTaskTool()
        val result = tool.execute(ContinueTaskTool.Params(taskId = task.id.value))

        // Verify task data
        assertEquals(task.id.value, result.task.id)
        assertEquals(task.title, result.task.title)
        assertEquals("IN_PROGRESS", result.task.status) // Should update from PENDING

        // Verify proposals
        assertEquals(1, result.proposals.size)
        assertEquals(proposal1.id.value, result.proposals[0].id)
        assertEquals(0.85, result.proposals[0].confidence)

        // Verify context has history
        assertEquals(1, result.context.history.size)
        assertEquals("USER", result.context.history[0].role) // Roles are stored uppercase
        assertEquals("Please design this", result.context.history[0].content)
    }

    @Test
    fun updates_pending_task_to_in_progress() {
        val now = Instant.now()
        val task = Task(
            id = TaskId("task-pending"),
            title = "Pending task",
            description = "Should transition",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.PENDING,
            routing = RoutingStrategy.SOLO,
            assigneeIds = setOf(AgentId("claude-code")),
            dependencies = emptySet(),
            complexity = 3,
            risk = 2,
            createdAt = now,
            updatedAt = now
        )
        TaskRepository.insert(task)

        val tool = ContinueTaskTool()
        val result = tool.execute(ContinueTaskTool.Params(taskId = task.id.value))

        assertEquals("IN_PROGRESS", result.task.status)

        // Verify in database
        val updated = TaskRepository.findById(task.id)
        assertNotNull(updated)
        assertEquals(TaskStatus.IN_PROGRESS, updated.status)
    }

    @Test
    fun does_not_update_completed_task_status() {
        val now = Instant.now()
        val task = Task(
            id = TaskId("task-completed"),
            title = "Already done",
            description = "Should not change",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.COMPLETED,
            routing = RoutingStrategy.SOLO,
            assigneeIds = setOf(AgentId("claude-code")),
            dependencies = emptySet(),
            complexity = 3,
            risk = 2,
            createdAt = now,
            updatedAt = now
        )
        TaskRepository.insert(task)

        val tool = ContinueTaskTool()
        val result = tool.execute(ContinueTaskTool.Params(taskId = task.id.value))

        assertEquals("COMPLETED", result.task.status) // Should remain COMPLETED
    }

    @Test
    fun throws_for_missing_task() {
        val tool = ContinueTaskTool()
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(ContinueTaskTool.Params(taskId = "no-such-task"))
        }
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test
    fun throws_for_blank_task_id() {
        val tool = ContinueTaskTool()
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(ContinueTaskTool.Params(taskId = "   "))
        }
        assertTrue(ex.message!!.contains("cannot be blank"))
    }

    @Test
    fun respects_max_tokens_limit() {
        val now = Instant.now()
        val task = Task(
            id = TaskId("task-token-limit"),
            title = "Token limit test",
            description = "Test",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.PENDING,
            routing = RoutingStrategy.SOLO,
            assigneeIds = setOf(AgentId("claude-code")),
            dependencies = emptySet(),
            complexity = 3,
            risk = 2,
            createdAt = now,
            updatedAt = now
        )
        TaskRepository.insert(task)

        val tool = ContinueTaskTool()

        // Should work with valid maxTokens
        val result = tool.execute(ContinueTaskTool.Params(
            taskId = task.id.value,
            maxTokens = 5000
        ))
        assertNotNull(result)
    }

    @Test
    fun throws_when_max_tokens_too_low() {
        val now = Instant.now()
        val task = Task(
            id = TaskId("task-tokens-low"),
            title = "Test",
            description = "Test",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.PENDING,
            routing = RoutingStrategy.SOLO,
            assigneeIds = setOf(AgentId("claude-code")),
            dependencies = emptySet(),
            complexity = 3,
            risk = 2,
            createdAt = now,
            updatedAt = now
        )
        TaskRepository.insert(task)

        val tool = ContinueTaskTool()
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(ContinueTaskTool.Params(
                taskId = task.id.value,
                maxTokens = 500 // Below MIN_TOKENS (1000)
            ))
        }
        assertTrue(ex.message!!.contains("must be at least"))
    }

    @Test
    fun throws_when_max_tokens_too_high() {
        val now = Instant.now()
        val task = Task(
            id = TaskId("task-tokens-high"),
            title = "Test",
            description = "Test",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.PENDING,
            routing = RoutingStrategy.SOLO,
            assigneeIds = setOf(AgentId("claude-code")),
            dependencies = emptySet(),
            complexity = 3,
            risk = 2,
            createdAt = now,
            updatedAt = now
        )
        TaskRepository.insert(task)

        val tool = ContinueTaskTool()
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(ContinueTaskTool.Params(
                taskId = task.id.value,
                maxTokens = 200_000 // Above MAX_ALLOWED_TOKENS (120,000)
            ))
        }
        assertTrue(ex.message!!.contains("exceeds policy limit"))
    }
}
