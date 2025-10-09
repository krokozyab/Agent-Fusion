package com.orchestrator.modules.context

import com.orchestrator.domain.*
import com.orchestrator.modules.context.FileRegistry.Operation
import com.orchestrator.modules.context.FileRegistry.OperationType
import com.orchestrator.modules.context.MemoryManager.Role
import com.orchestrator.storage.repositories.TaskRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.time.Instant

class ContextModuleTest {

    private fun newTask(id: String = "T-CTX-1"): Task {
        val task = Task(
            id = TaskId(id),
            title = "Context test",
            description = "Testing ContextModule",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.IN_PROGRESS,
            routing = RoutingStrategy.SOLO,
            assigneeIds = setOf(AgentId("agent-1")),
            dependencies = emptySet(),
            complexity = 3,
            risk = 2,
            createdAt = Instant.now()
        )
        // Ensure present in DB for snapshot creation
        TaskRepository.insert(task)
        return task
    }

    @Test
    fun getTaskContext_empty() {
        val task = newTask("T-CTX-empty")
        val ctx = ContextModule.getTaskContext(task.id)
        assertTrue(ctx.history.isEmpty())
        assertTrue(ctx.fileHistory.isEmpty())
    }

    @Test
    fun updateContext_and_retrieve() {
        val task = newTask("T-CTX-upd")
        val agent = AgentId("agent-ctx")

        val updates = ContextModule.ContextUpdates(
            messages = listOf(
                ContextModule.MessageUpdate(role = Role.USER, content = "Hello"),
                ContextModule.MessageUpdate(role = Role.ASSISTANT, content = "Hi there", agentId = agent)
            ),
            fileOps = listOf(
                ContextModule.FileOpUpdate(agent, Operation(path = "README.md", type = OperationType.CREATE, content = "first")),
                ContextModule.FileOpUpdate(agent, Operation(path = "README.md", type = OperationType.UPDATE, content = "first\nsecond"))
            ),
            snapshotLabel = "after-initial"
        )

        val result = ContextModule.updateContext(task.id, updates)
        assertEquals(2, result.appendedMessageIds.size)
        assertNotNull(result.snapshotId)
        assertEquals(2, result.fileOperations.size)
        assertTrue(result.fileOperations.all { !it.conflict })

        val ctx = ContextModule.getTaskContext(task.id)
        assertEquals(2, ctx.history.size)
        assertEquals(2, ctx.fileHistory.size)
    }

    @Test
    fun file_conflict_is_recorded_not_thrown() {
        val task = newTask("T-CTX-conflict")
        val agent = AgentId("agent-ctx2")

        // First create
        val ok = ContextModule.updateContext(
            task.id,
            ContextModule.ContextUpdates(
                fileOps = listOf(ContextModule.FileOpUpdate(agent, Operation(path = "a.txt", type = OperationType.CREATE, content = "one")))
            )
        )
        assertEquals(1, ok.fileOperations.size)
        assertFalse(ok.fileOperations.first().conflict)

        // Second create same file => conflict recorded
        val conflict = ContextModule.updateContext(
            task.id,
            ContextModule.ContextUpdates(
                fileOps = listOf(ContextModule.FileOpUpdate(agent, Operation(path = "a.txt", type = OperationType.CREATE, content = "two")))
            )
        )
        assertEquals(1, conflict.fileOperations.size)
        assertTrue(conflict.fileOperations.first().conflict)
    }
}
