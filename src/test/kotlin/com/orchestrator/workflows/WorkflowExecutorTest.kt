package com.orchestrator.workflows

import com.orchestrator.domain.RoutingStrategy
import com.orchestrator.domain.Task
import com.orchestrator.domain.TaskId
import com.orchestrator.domain.TaskStatus
import com.orchestrator.domain.TaskType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class WorkflowExecutorTest {

    @Test
    fun `WorkflowResult success is true only when status is COMPLETED`() {
        val taskId = TaskId("t-1")
        val completed = WorkflowResult(
            taskId = taskId,
            status = TaskStatus.COMPLETED,
            output = "ok"
        )
        assertTrue(completed.success)

        val failed = WorkflowResult(
            taskId = taskId,
            status = TaskStatus.FAILED,
            error = "boom"
        )
        assertFalse(failed.success)

        val pending = WorkflowResult(
            taskId = taskId,
            status = TaskStatus.PENDING
        )
        assertFalse(pending.success)
    }

    @Test
    fun `InMemoryWorkflowStateStore returns NOT_STARTED by default and persists changes`() {
        val store = InMemoryWorkflowStateStore()
        val taskId = TaskId("t-2")

        assertEquals(WorkflowState.NOT_STARTED, store.getState(taskId))

        store.setState(taskId, WorkflowState.RUNNING)
        assertEquals(WorkflowState.RUNNING, store.getState(taskId))

        store.setState(taskId, WorkflowState.WAITING_INPUT)
        assertEquals(WorkflowState.WAITING_INPUT, store.getState(taskId))

        store.setState(taskId, WorkflowState.COMPLETED)
        assertEquals(WorkflowState.COMPLETED, store.getState(taskId))
    }

    @Test
    fun `InMemoryWorkflowStateStore stores and retrieves checkpoints`() {
        val store = InMemoryWorkflowStateStore()
        val taskId = TaskId("t-3")
        val ts1 = Instant.now().minusSeconds(10)
        val ts2 = Instant.now()

        val cp1 = Checkpoint(
            id = "cp-1",
            taskId = taskId,
            state = WorkflowState.RUNNING,
            timestamp = ts1,
            label = "start",
            data = mapOf("step" to "1")
        )
        val cp2 = Checkpoint(
            id = "cp-2",
            taskId = taskId,
            state = WorkflowState.WAITING_INPUT,
            timestamp = ts2,
            label = "await",
            data = mapOf("prompt" to "enter value")
        )

        store.addCheckpoint(cp1)
        store.addCheckpoint(cp2)

        val checkpoints = store.getCheckpoints(taskId)
        assertEquals(2, checkpoints.size)
        // preserve insertion order
        assertEquals("cp-1", checkpoints[0].id)
        assertEquals("cp-2", checkpoints[1].id)

        val fetched = store.getCheckpoint(taskId, "cp-2")
        assertNotNull(fetched)
        assertEquals(cp2, fetched)

        val missing = store.getCheckpoint(taskId, "nope")
        assertNull(missing)
    }

    @Test
    fun `Fake executor demonstrates supportedStrategies, state querying, and resume`() = runBlocking {
        val store = InMemoryWorkflowStateStore()
        val exec = FakeWorkflowExecutor(store)
        val task = Task(
            id = TaskId("t-4"),
            title = "Demo",
            type = TaskType.IMPLEMENTATION,
            routing = RoutingStrategy.SOLO
        )

        assertTrue(exec.supportedStrategies.contains(RoutingStrategy.SOLO))

        // Before execution state is NOT_STARTED
        assertEquals(WorkflowState.NOT_STARTED, exec.currentState(task.id))

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = exec.execute(runtime)

        assertTrue(step is WorkflowStep.Success)
        assertEquals(TaskStatus.COMPLETED, runtime.currentStatus)
        assertEquals(WorkflowState.COMPLETED, exec.currentState(task.id))

        val cps = exec.checkpoints(task.id)
        assertTrue(cps.isNotEmpty())
        assertEquals(task.id, cps.first().taskId)

        // resume should just re-run and remain successful
        val resumed = exec.resume(runtime)
        assertTrue(resumed is WorkflowStep.Success)
    }

    /**
     * Minimal fake executor to exercise the interface contract in tests.
     */
    private class FakeWorkflowExecutor(private val store: WorkflowStateStore) : WorkflowExecutor {
        override val supportedStrategies: Set<RoutingStrategy> = setOf(RoutingStrategy.SOLO)

        override suspend fun execute(runtime: WorkflowRuntime): WorkflowStep {
            store.setState(runtime.task.id, WorkflowState.RUNNING)
            val cp = Checkpoint(id = "start", taskId = runtime.task.id, state = WorkflowState.RUNNING)
            store.addCheckpoint(cp)

            // pretend work is done successfully
            store.setState(runtime.task.id, WorkflowState.COMPLETED)
            runtime.currentStatus = TaskStatus.COMPLETED
            val end = Checkpoint(id = "end", taskId = runtime.task.id, state = WorkflowState.COMPLETED)
            store.addCheckpoint(end)

            return WorkflowStep.Success(
                runtime = runtime,
                output = "done"
            )
        }

        override fun currentState(taskId: TaskId): WorkflowState = store.getState(taskId)

        override fun checkpoints(taskId: TaskId): List<Checkpoint> = store.getCheckpoints(taskId)

        override suspend fun resume(runtime: WorkflowRuntime, checkpointId: String?): WorkflowStep {
            // For this fake, ignore checkpointId and just execute again
            return execute(runtime)
        }
    }
}
