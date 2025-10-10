package com.orchestrator.workflows

import com.orchestrator.config.ConfigLoader
import com.orchestrator.core.AgentRegistry
import com.orchestrator.domain.*
import com.orchestrator.storage.Database
import com.orchestrator.storage.repositories.MessageRepository
import com.orchestrator.storage.repositories.TaskRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import java.time.Instant

class SoloWorkflowTest {

    @BeforeTest
    fun cleanDb() {
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

    private fun registryWithAgents(n: Int): AgentRegistry {
        val defs = (1..n).map { i ->
            ConfigLoader.AgentDefinition(
                id = AgentId("agent-$i"),
                type = AgentType.GPT,
                config = AgentConfig(name = "Agent $i", model = "test-model-$i")
            )
        }
        return AgentRegistry.build(defs)
    }

    private fun sampleTask(
        id: String,
        routing: RoutingStrategy = RoutingStrategy.SOLO,
        assignees: Set<AgentId> = emptySet()
    ): Task {
        return Task(
            id = TaskId(id),
            title = "Solo task $id",
            description = "Test solo execution",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.PENDING,
            routing = routing,
            assigneeIds = assignees,
            dependencies = emptySet(),
            complexity = 3,
            risk = 2,
            createdAt = Instant.now(),
            updatedAt = null,
            dueAt = null,
            metadata = emptyMap()
        )
    }

    @Test
    fun executes_task_with_single_agent_successfully() = runBlocking {
        val registry = registryWithAgents(1)
        val task = sampleTask("t-solo-1")
        TaskRepository.insert(task)

        var executedTask: Task? = null
        var executedAgent: Agent? = null

        val workflow = SoloWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            agentTaskRunner = { t, a ->
                executedTask = t
                executedAgent = a
                "Task ${t.id.value} completed by ${a.displayName}"
            }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        // Verify workflow succeeded
        assertTrue(step is WorkflowStep.Success)
        assertEquals(TaskStatus.COMPLETED, runtime.currentStatus)

        // Verify runner was called
        assertNotNull(executedTask)
        assertEquals(task.id, executedTask!!.id)
        assertNotNull(executedAgent)
        assertEquals("agent-1", executedAgent!!.id.value)

        // Verify output
        val output = (step as WorkflowStep.Success).output
        assertTrue(output?.contains("Task ${task.id.value}") == true)
        assertTrue(output?.contains("Agent 1") == true)
    }

    @Test
    fun records_message_in_conversation_history() = runBlocking {
        val registry = registryWithAgents(1)
        val task = sampleTask("t-solo-2")
        TaskRepository.insert(task)

        val workflow = SoloWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            messageRepository = MessageRepository,
            agentTaskRunner = { t, a ->
                "Result from ${a.displayName}"
            }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        assertTrue(step is WorkflowStep.Success)

        // Verify message was persisted
        val messages = MessageRepository.listByTask(task.id)
        assertEquals(1, messages.size)
        assertEquals("agent", messages[0].role)
        assertTrue(messages[0].content.contains("Result from Agent 1"))
        assertTrue(messages[0].tokens > 0)
    }

    @Test
    fun selects_assigned_agent_when_multiple_available() = runBlocking {
        val registry = registryWithAgents(3)
        val assignedAgentId = AgentId("agent-2")
        val task = sampleTask("t-solo-3", assignees = setOf(assignedAgentId))
        TaskRepository.insert(task)

        var executedAgent: Agent? = null

        val workflow = SoloWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            agentTaskRunner = { _, a ->
                executedAgent = a
                "Done by ${a.id.value}"
            }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        assertTrue(step is WorkflowStep.Success)
        assertNotNull(executedAgent)
        assertEquals("agent-2", executedAgent!!.id.value)
    }

    @Test
    fun fails_when_no_agents_available() = runBlocking {
        val registry = AgentRegistry.empty()
        val task = sampleTask("t-solo-4")
        TaskRepository.insert(task)

        val workflow = SoloWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore()
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        assertTrue(step is WorkflowStep.Failure)
        assertEquals(TaskStatus.FAILED, runtime.currentStatus)
        assertTrue((step as WorkflowStep.Failure).error.contains("No available agent"))
    }

    @Test
    fun handles_agent_task_runner_exception() = runBlocking {
        val registry = registryWithAgents(1)
        val task = sampleTask("t-solo-5")
        TaskRepository.insert(task)

        val workflow = SoloWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            agentTaskRunner = { _, _ ->
                throw RuntimeException("Agent execution failed")
            }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        assertTrue(step is WorkflowStep.Failure)
        assertEquals(TaskStatus.FAILED, runtime.currentStatus)
        assertTrue((step as WorkflowStep.Failure).error.contains("Agent execution failed"))
    }

    @Test
    fun retries_on_failure_up_to_max_retries() = runBlocking {
        val registry = registryWithAgents(1)
        val task = sampleTask("t-solo-6")
        TaskRepository.insert(task)

        var attemptCount = 0
        val maxRetries = 2

        val workflow = SoloWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            maxRetries = maxRetries,
            backoffMillis = 10, // Short backoff for testing
            agentTaskRunner = { _, _ ->
                attemptCount++
                if (attemptCount < maxRetries + 1) {
                    throw RuntimeException("Temporary failure on attempt $attemptCount")
                }
                "Success on attempt $attemptCount"
            }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        assertTrue(step is WorkflowStep.Success)
        assertEquals(maxRetries + 1, attemptCount) // Initial attempt + 2 retries
        assertTrue((step as WorkflowStep.Success).output?.contains("Success on attempt") == true)
    }

    @Test
    fun fails_after_exhausting_all_retries() = runBlocking {
        val registry = registryWithAgents(1)
        val task = sampleTask("t-solo-7")
        TaskRepository.insert(task)

        var attemptCount = 0
        val maxRetries = 2

        val workflow = SoloWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            maxRetries = maxRetries,
            backoffMillis = 10,
            agentTaskRunner = { _, _ ->
                attemptCount++
                throw RuntimeException("Persistent failure")
            }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        assertTrue(step is WorkflowStep.Failure)
        assertEquals(maxRetries + 1, attemptCount)
        assertTrue((step as WorkflowStep.Failure).error?.contains("Persistent failure") == true)
    }

    @Test
    fun resume_re_executes_workflow() = runBlocking {
        val registry = registryWithAgents(1)
        val task = sampleTask("t-solo-8")
        TaskRepository.insert(task)

        var executionCount = 0

        val workflow = SoloWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            agentTaskRunner = { _, _ ->
                executionCount++
                "Execution $executionCount"
            }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)

        // First execution
        val step1 = workflow.execute(runtime)
        assertTrue(step1 is WorkflowStep.Success)
        assertEquals(1, executionCount)

        // Resume (re-executes)
        val step2 = workflow.resume(runtime, checkpointId = null)
        assertTrue(step2 is WorkflowStep.Success)
        assertEquals(2, executionCount)
    }

    @Test
    fun supports_only_solo_routing_strategy() {
        val registry = registryWithAgents(1)
        val workflow = SoloWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore()
        )

        assertEquals(setOf(RoutingStrategy.SOLO), workflow.supportedStrategies)
    }

    @Test
    fun records_token_usage_for_output() = runBlocking {
        val registry = registryWithAgents(1)
        val task = sampleTask("t-solo-9")
        TaskRepository.insert(task)

        val workflow = SoloWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            messageRepository = MessageRepository,
            agentTaskRunner = { _, _ ->
                "This is a test output with some tokens in it"
            }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        assertTrue(step is WorkflowStep.Success)

        // Verify message has token count
        val messages = MessageRepository.listByTask(task.id)
        assertEquals(1, messages.size)
        assertTrue(messages[0].tokens > 0)
    }
}
