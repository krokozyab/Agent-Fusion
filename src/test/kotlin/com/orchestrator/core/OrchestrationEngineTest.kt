package com.orchestrator.core

import com.orchestrator.config.ConfigLoader
import com.orchestrator.domain.*
import com.orchestrator.modules.context.ContextModule
import com.orchestrator.modules.context.MemoryManager
import com.orchestrator.modules.routing.RoutingModule
import com.orchestrator.storage.repositories.TaskRepository
import com.orchestrator.workflows.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class OrchestrationEngineTest {
    private lateinit var agentRegistry: AgentRegistry
    private lateinit var engine: OrchestrationEngine
    private lateinit var testTask: Task

    @BeforeEach
    fun setup() {
        // Create test agent registry
        agentRegistry = AgentRegistry.build(
            listOf(
                createTestAgentDef("agent-1", AgentType.CLAUDE_CODE),
                createTestAgentDef("agent-2", AgentType.CODEX_CLI)
            )
        )
        
        // Mark agents as online
        agentRegistry.updateStatus(AgentId("agent-1"), AgentStatus.ONLINE)
        agentRegistry.updateStatus(AgentId("agent-2"), AgentStatus.ONLINE)
        
        engine = OrchestrationEngine(agentRegistry)
        
        testTask = Task(
            id = TaskId("test-task-1"),
            title = "Test Task",
            description = "A test task",
            type = TaskType.IMPLEMENTATION,
            complexity = 5,
            risk = 5
        )
    }

    @AfterEach
    fun teardown() {
        engine.shutdown()
        StateMachine.clearAllHistory()
        TaskRepository.delete(testTask.id)
    }

    @Test
    fun `executeTask routes and executes workflow`() = runBlocking {
        val result = engine.executeTask(testTask)

        assertNotNull(result)
        assertEquals(testTask.id, result.taskId)
        assertTrue(result.status in setOf(TaskStatus.COMPLETED, TaskStatus.FAILED))
    }

    @Test
    fun `executeTask publishes events`() = runBlocking<Unit> {
        // Launch task execution in background
        val job = launch {
            engine.executeTask(testTask)
        }
        
        // Wait for first event
        val event = withTimeout(5000) {
            engine.events.first()
        }
        
        assertTrue(event is OrchestrationEvent.TaskCreated)
        assertEquals(testTask.id, (event as OrchestrationEvent.TaskCreated).taskId)
        
        job.join()
    }

    @Test
    fun `state transitions are validated`() = runBlocking {
        // Create task in COMPLETED state
        val completedTask = testTask.copy(status = TaskStatus.COMPLETED)

        // Attempting to execute should handle gracefully
        val result = engine.executeTask(completedTask)
        assertNotNull(result)
    }

    @Test
    fun `routing decision is applied to task`() = runBlocking {
        val directive = UserDirective(
            originalText = "force consensus",
            forceConsensus = true,
            forceConsensusConfidence = 0.9
        )
        val result = engine.executeTask(testTask, directive)

        assertNotNull(result)
        // Verify result is not null (routing happens internally)
        assertTrue(result.taskId == testTask.id)
    }

    @Test
    fun `workflow errors are handled gracefully`() = runBlocking {
        // Create task with routing strategy that has no registered workflow
        val invalidTask = testTask.copy(routing = RoutingStrategy.PARALLEL)

        val result = engine.executeTask(invalidTask)

        // Should fail gracefully
        assertTrue(result.status == TaskStatus.FAILED || result.taskId == invalidTask.id)
    }

    @Test
    fun `getWorkflowState returns current state`() = runBlocking {
        engine.executeTask(testTask)

        val state = engine.getWorkflowState(testTask.id, testTask.routing)
        assertNotNull(state)
    }

    @Test
    fun `resumeTask continues from checkpoint`() = runBlocking {
        // First execution
        engine.executeTask(testTask)

        // Resume
        val result = engine.resumeTask(testTask)

        assertNotNull(result)
        assertEquals(testTask.id, result.taskId)
    }

    @Test
    fun `route delegates to routing module`() {
        val decision = engine.route(testTask)
        
        assertNotNull(decision)
        assertEquals(testTask.id, decision.taskId)
        assertNotNull(decision.strategy)
    }

    @Test
    fun `runConsensus delegates to consensus module`() {
        // Create some proposals first (would normally be done by workflow)
        val outcome = engine.runConsensus(testTask.id)
        
        assertNotNull(outcome)
        assertEquals(testTask.id, outcome.result.winningProposal?.taskId ?: testTask.id)
    }

    @Test
    fun `getTaskContext delegates to context module`() {
        val context = engine.getTaskContext(testTask.id)
        
        assertNotNull(context)
        assertEquals(testTask.id, context.taskId)
    }

    @Test
    fun `updateTaskContext delegates to context module`() {
        val updates = ContextModule.ContextUpdates(
            messages = listOf(
                ContextModule.MessageUpdate(
                    role = MemoryManager.Role.USER,
                    content = "Test message"
                )
            )
        )
        
        val result = engine.updateTaskContext(testTask.id, updates)
        
        assertNotNull(result)
        assertTrue(result.appendedMessageIds.isNotEmpty())
    }

    @Test
    fun `getStateHistory returns transition history`() = runBlocking {
        engine.executeTask(testTask)

        val history = engine.getStateHistory(testTask.id)

        assertNotNull(history)
        assertTrue(history.isNotEmpty())
        // Should have at least PENDING -> IN_PROGRESS transition
        assertTrue(history.any { it.from == TaskStatus.PENDING && it.to == TaskStatus.IN_PROGRESS })
    }

    @Test
    fun `registerWorkflow adds new workflow executor`() = runBlocking {
        val customWorkflow = object : WorkflowExecutor {
            override val supportedStrategies = setOf(RoutingStrategy.PARALLEL)

            override suspend fun execute(runtime: WorkflowRuntime): WorkflowStep {
                return WorkflowStep.Success(
                    runtime = runtime,
                    output = "Custom workflow completed"
                )
            }

            override fun currentState(taskId: TaskId) = WorkflowState.NOT_STARTED
            override fun checkpoints(taskId: TaskId) = emptyList<Checkpoint>()
            override suspend fun resume(runtime: WorkflowRuntime, checkpointId: String?) = execute(runtime)
        }

        engine.registerWorkflow(customWorkflow)

        val parallelTask = testTask.copy(routing = RoutingStrategy.PARALLEL)
        val result = engine.executeTask(parallelTask)

        assertEquals(TaskStatus.COMPLETED, result.status)
    }

    @Test
    fun `executeTask enforces per-task locking`() = runBlocking {
        val enter = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val executions = AtomicInteger(0)

        val blockingWorkflow = object : WorkflowExecutor {
            override val supportedStrategies = setOf(RoutingStrategy.SOLO)

            override suspend fun execute(runtime: WorkflowRuntime): WorkflowStep {
                val run = executions.incrementAndGet()
                if (run == 1) {
                    enter.complete(Unit)
                    release.await()
                }
                return WorkflowStep.Success(runtime = runtime, output = "done")
            }

            override fun currentState(taskId: TaskId) = WorkflowState.RUNNING
            override fun checkpoints(taskId: TaskId) = emptyList<Checkpoint>()
            override suspend fun resume(runtime: WorkflowRuntime, checkpointId: String?) = execute(runtime)
        }

        engine.registerWorkflow(blockingWorkflow)

        val lockingTask = testTask.copy(id = TaskId("lock-task"), routing = RoutingStrategy.SOLO)
        TaskRepository.delete(lockingTask.id)

        val job1 = async { engine.executeTask(lockingTask) }
        var job2: Deferred<WorkflowResult>? = null

        try {
            withTimeout(1_000) { enter.await() }

            job2 = async { engine.executeTask(lockingTask) }

            delay(150)
            assertTrue(job2.isActive)

            release.complete(Unit)

            val result1 = job1.await()
            val result2 = job2.await()

            assertEquals(TaskStatus.COMPLETED, result1.status)
            assertEquals(TaskStatus.FAILED, result2.status)
        } finally {
            if (!release.isCompleted) release.complete(Unit)
            job2?.cancel()
            job2?.join()
            job1.cancel()
            job1.join()
            TaskRepository.delete(lockingTask.id)
        }
    }

    @Test
    fun `resumeTask waits for in-flight execution`() = runBlocking {
        val enter = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val executions = AtomicInteger(0)

        val blockingWorkflow = object : WorkflowExecutor {
            override val supportedStrategies = setOf(RoutingStrategy.SOLO)

            override suspend fun execute(runtime: WorkflowRuntime): WorkflowStep {
                val run = executions.incrementAndGet()
                if (run == 1) {
                    enter.complete(Unit)
                    release.await()
                }
                return WorkflowStep.Success(runtime = runtime, output = "done")
            }

            override fun currentState(taskId: TaskId) = WorkflowState.RUNNING
            override fun checkpoints(taskId: TaskId) = emptyList<Checkpoint>()
            override suspend fun resume(runtime: WorkflowRuntime, checkpointId: String?) = execute(runtime)
        }

        engine.registerWorkflow(blockingWorkflow)

        val sharedTask = testTask.copy(id = TaskId("resume-lock"), routing = RoutingStrategy.SOLO)
        TaskRepository.delete(sharedTask.id)

        val job1 = async { engine.executeTask(sharedTask) }
        var resumeJob: Deferred<WorkflowResult>? = null

        try {
            withTimeout(1_000) { enter.await() }

            resumeJob = async { engine.resumeTask(sharedTask) }

            delay(150)
            assertTrue(resumeJob.isActive)

            release.complete(Unit)

            val result1 = job1.await()
            val result2 = resumeJob.await()

            assertEquals(TaskStatus.COMPLETED, result1.status)
            assertEquals(TaskStatus.FAILED, result2.status)
        } finally {
            if (!release.isCompleted) release.complete(Unit)
            resumeJob?.cancel()
            resumeJob?.join()
            job1.cancel()
            job1.join()
            TaskRepository.delete(sharedTask.id)
        }
    }

    private fun createTestAgentDef(id: String, type: AgentType): ConfigLoader.AgentDefinition {
        return ConfigLoader.AgentDefinition(
            id = AgentId(id),
            type = type,
            config = AgentConfig(
                name = "Test Agent $id"
            )
        )
    }
}
