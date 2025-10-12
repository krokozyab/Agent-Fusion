package com.orchestrator.workflows

import com.orchestrator.config.ConfigLoader
import com.orchestrator.core.AgentRegistry
import com.orchestrator.domain.*
import com.orchestrator.storage.Database
import com.orchestrator.storage.repositories.MessageRepository
import com.orchestrator.storage.repositories.TaskRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ParallelWorkflowTest {

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
        routing: RoutingStrategy = RoutingStrategy.PARALLEL,
        assignees: Set<AgentId> = emptySet()
    ): Task {
        return Task(
            id = TaskId(id),
            title = "Parallel task $id",
            description = "Test parallel execution",
            type = TaskType.RESEARCH,
            status = TaskStatus.PENDING,
            routing = routing,
            assigneeIds = assignees,
            dependencies = emptySet(),
            complexity = 6,
            risk = 3,
            createdAt = Instant.now(),
            updatedAt = null,
            dueAt = null,
            metadata = emptyMap()
        )
    }

    @Test
    fun executes_multiple_agents_in_parallel_successfully() = runBlocking {
        val registry = registryWithAgents(3)
        val task = sampleTask("t-parallel-1")
        TaskRepository.insert(task)

        val executedAgents = CopyOnWriteArrayList<String>()

        val workflow = ParallelWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            maxAgents = 3,
            agentTaskRunner = { t, a ->
                executedAgents.add(a.id.value)
                "Result from ${a.displayName} for task ${t.title}"
            }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        // Verify workflow succeeded
        assertTrue(step is WorkflowStep.Success)
        assertEquals(TaskStatus.COMPLETED, runtime.currentStatus)

        // Verify all agents executed
        assertEquals(3, executedAgents.size)
        assertTrue(executedAgents.contains("agent-1"))
        assertTrue(executedAgents.contains("agent-2"))
        assertTrue(executedAgents.contains("agent-3"))

        // Verify aggregated output contains all results
        val output = (step as WorkflowStep.Success).output
        assertNotNull(output)
        assertTrue(output.contains("Agent 1"))
        assertTrue(output.contains("Agent 2"))
        assertTrue(output.contains("Agent 3"))
        assertTrue(output.contains("3/3 successful"))
    }

    @Test
    fun respects_max_agents_limit() = runBlocking {
        val registry = registryWithAgents(5)
        val task = sampleTask("t-parallel-2")
        TaskRepository.insert(task)

        val executedAgents = CopyOnWriteArrayList<String>()

        val workflow = ParallelWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            maxAgents = 2,
            agentTaskRunner = { _, a ->
                executedAgents.add(a.id.value)
                "Result from ${a.displayName}"
            }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        assertTrue(step is WorkflowStep.Success)
        // Should only execute 2 agents despite 5 being available
        assertEquals(2, executedAgents.size)
    }

    @Test
    fun handles_partial_agent_failures() = runBlocking {
        val registry = registryWithAgents(3)
        val task = sampleTask("t-parallel-3")
        TaskRepository.insert(task)

        val workflow = ParallelWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            maxAgents = 3,
            minSuccessfulAgents = 1, // At least 1 success required
            agentTaskRunner = { _, a ->
                if (a.id.value == "agent-2") {
                    throw RuntimeException("Agent 2 failed")
                }
                "Success from ${a.displayName}"
            }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        // Should succeed despite one failure
        assertTrue(step is WorkflowStep.Success)

        // Verify artifacts show failure stats
        val artifacts = (step as WorkflowStep.Success).artifacts
        assertEquals("3", artifacts["totalAgents"])
        assertEquals("2", artifacts["successfulAgents"])
        assertEquals("1", artifacts["failedAgents"])
    }

    @Test
    fun fails_when_minimum_successes_not_met() = runBlocking {
        val registry = registryWithAgents(3)
        val task = sampleTask("t-parallel-4")
        TaskRepository.insert(task)

        val workflow = ParallelWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            maxAgents = 3,
            minSuccessfulAgents = 3, // All must succeed
            agentTaskRunner = { _, a ->
                if (a.id.value == "agent-2") {
                    throw RuntimeException("Agent 2 failed")
                }
                "Success from ${a.displayName}"
            }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        // Should fail because not all agents succeeded
        assertTrue(step is WorkflowStep.Failure)
        assertEquals(TaskStatus.FAILED, runtime.currentStatus)
        assertTrue((step as WorkflowStep.Failure).error.contains("Insufficient successful executions"))
    }

    @Test
    fun handles_agent_timeout() = runBlocking {
        val registry = registryWithAgents(2)
        val task = sampleTask("t-parallel-5")
        TaskRepository.insert(task)

        val workflow = ParallelWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            maxAgents = 2,
            perAgentTimeoutMs = 100, // Very short timeout
            minSuccessfulAgents = 1,
            agentTaskRunner = { _, a ->
                if (a.id.value == "agent-1") {
                    delay(200) // Exceeds timeout
                }
                "Result from ${a.displayName}"
            }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        // Should succeed with agent-2, agent-1 timed out
        assertTrue(step is WorkflowStep.Success)

        val artifacts = (step as WorkflowStep.Success).artifacts
        assertEquals("1", artifacts["successfulAgents"])
        assertEquals("1", artifacts["failedAgents"])
    }

    @Test
    fun records_messages_for_all_successful_agents() = runBlocking {
        val registry = registryWithAgents(3)
        val task = sampleTask("t-parallel-6")
        TaskRepository.insert(task)

        val workflow = ParallelWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            messageRepository = MessageRepository,
            maxAgents = 3,
            agentTaskRunner = { _, a ->
                "Parallel result from ${a.displayName}"
            }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        assertTrue(step is WorkflowStep.Success)

        // Verify messages were persisted for all agents
        val messages = MessageRepository.listByTask(task.id)
        assertEquals(3, messages.size)

        val agentIds = messages.mapNotNull { it.agentId?.value }.toSet()
        assertTrue(agentIds.contains("agent-1"))
        assertTrue(agentIds.contains("agent-2"))
        assertTrue(agentIds.contains("agent-3"))

        // Verify all messages have token counts
        messages.forEach { message ->
            assertTrue(message.tokens > 0)
            assertEquals("agent", message.role)
        }
    }

    @Test
    fun fails_when_no_agents_available() = runBlocking {
        val registry = AgentRegistry.empty()
        val task = sampleTask("t-parallel-7")
        TaskRepository.insert(task)

        val workflow = ParallelWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore()
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        assertTrue(step is WorkflowStep.Failure)
        assertEquals(TaskStatus.FAILED, runtime.currentStatus)
        assertTrue((step as WorkflowStep.Failure).error.contains("No available agents"))
    }

    @Test
    fun selects_assigned_agents_when_specified() = runBlocking {
        val registry = registryWithAgents(5)
        val assignedAgents = setOf(AgentId("agent-2"), AgentId("agent-4"))
        val task = sampleTask("t-parallel-8", assignees = assignedAgents)
        TaskRepository.insert(task)

        val executedAgents = CopyOnWriteArrayList<String>()

        val workflow = ParallelWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            maxAgents = 3,
            agentTaskRunner = { _, a ->
                executedAgents.add(a.id.value)
                "Result from ${a.displayName}"
            }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        assertTrue(step is WorkflowStep.Success)

        // Should only execute assigned agents
        assertEquals(2, executedAgents.size)
        assertTrue(executedAgents.contains("agent-2"))
        assertTrue(executedAgents.contains("agent-4"))
        assertFalse(executedAgents.contains("agent-1"))
        assertFalse(executedAgents.contains("agent-3"))
    }

    @Test
    fun provides_detailed_artifacts_with_metrics() = runBlocking {
        val registry = registryWithAgents(3)
        val task = sampleTask("t-parallel-9")
        TaskRepository.insert(task)

        val workflow = ParallelWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            maxAgents = 3,
            agentTaskRunner = { _, a ->
                delay(50) // Simulate work
                "Result from ${a.displayName}"
            }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        assertTrue(step is WorkflowStep.Success)

        val artifacts = (step as WorkflowStep.Success).artifacts
        assertNotNull(artifacts["totalAgents"])
        assertNotNull(artifacts["successfulAgents"])
        assertNotNull(artifacts["failedAgents"])
        assertNotNull(artifacts["totalTokens"])
        assertNotNull(artifacts["avgExecutionTimeMs"])
        assertNotNull(artifacts["agentIds"])
        assertNotNull(artifacts["successfulAgentIds"])
        assertNotNull(artifacts["executionTimes"])
        assertNotNull(artifacts["tokenBreakdown"])

        // Verify content
        assertEquals("3", artifacts["totalAgents"])
        assertEquals("3", artifacts["successfulAgents"])
        assertEquals("0", artifacts["failedAgents"])
        assertTrue(artifacts["agentIds"]!!.contains("agent-1"))
        assertTrue(artifacts["executionTimes"]!!.contains("ms"))
    }

    @Test
    fun aggregates_output_with_all_agent_results() = runBlocking {
        val registry = registryWithAgents(2)
        val task = sampleTask("t-parallel-10")
        TaskRepository.insert(task)

        val workflow = ParallelWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            maxAgents = 2,
            agentTaskRunner = { _, a ->
                "Unique output from ${a.displayName}: Analysis ${a.id.value}"
            }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        assertTrue(step is WorkflowStep.Success)

        val output = (step as WorkflowStep.Success).output
        assertNotNull(output)

        // Verify output contains separator and both results
        assertTrue(output.contains("Parallel Execution Results"))
        assertTrue(output.contains("Agent 1"))
        assertTrue(output.contains("Agent 2"))
        assertTrue(output.contains("Analysis agent-1"))
        assertTrue(output.contains("Analysis agent-2"))
        assertTrue(output.contains("Execution Time:"))
        assertTrue(output.contains("Tokens:"))
    }

    @Test
    fun includes_failed_agents_in_output() = runBlocking {
        val registry = registryWithAgents(3)
        val task = sampleTask("t-parallel-11")
        TaskRepository.insert(task)

        val workflow = ParallelWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            maxAgents = 3,
            minSuccessfulAgents = 1,
            agentTaskRunner = { _, a ->
                if (a.id.value == "agent-3") {
                    throw RuntimeException("Custom error from agent-3")
                }
                "Success from ${a.displayName}"
            }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        assertTrue(step is WorkflowStep.Success)

        val output = (step as WorkflowStep.Success).output
        assertNotNull(output)

        // Verify failed agents section
        assertTrue(output.contains("Failed Executions"))
        assertTrue(output.contains("agent-3"))
        assertTrue(output.contains("Custom error from agent-3"))
    }

    @Test
    fun supports_only_parallel_routing_strategy() {
        val registry = registryWithAgents(2)
        val workflow = ParallelWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore()
        )

        assertEquals(setOf(RoutingStrategy.PARALLEL), workflow.supportedStrategies)
    }

    @Test
    fun resume_re_executes_workflow() = runBlocking {
        val registry = registryWithAgents(2)
        val task = sampleTask("t-parallel-12")
        TaskRepository.insert(task)

        val executionCount = AtomicInteger(0)

        val workflow = ParallelWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            maxAgents = 2,
            agentTaskRunner = { _, a ->
                val count = executionCount.incrementAndGet()
                "Execution $count from ${a.displayName}"
            }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)

        // First execution
        val step1 = workflow.execute(runtime)
        assertTrue(step1 is WorkflowStep.Success)
        assertEquals(2, executionCount.get()) // 2 agents

        // Resume (re-executes all agents)
        val step2 = workflow.resume(runtime, checkpointId = null)
        assertTrue(step2 is WorkflowStep.Success)
        assertEquals(4, executionCount.get()) // 2 agents again
    }

    @Test
    fun records_token_usage_for_all_agents() = runBlocking {
        val registry = registryWithAgents(3)
        val task = sampleTask("t-parallel-13")
        TaskRepository.insert(task)

        val workflow = ParallelWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            maxAgents = 3,
            agentTaskRunner = { _, a ->
                "This is a longer output from ${a.displayName} with multiple tokens to count properly"
            }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        assertTrue(step is WorkflowStep.Success)

        // Verify runtime recorded tokens for all agents
        assertTrue(runtime.totalTokens() > 0)
        assertEquals(3, runtime.tokenAccounting.size)

        // Verify each agent has token count
        runtime.tokenAccounting.forEach { (agentId, tokens) ->
            assertTrue(tokens > 0)
            assertTrue(agentId.startsWith("agent-"))
        }
    }

    @Test
    fun all_agents_execute_truly_in_parallel() = runBlocking {
        val registry = registryWithAgents(3)
        val task = sampleTask("t-parallel-14")
        TaskRepository.insert(task)

        val startTimes = ConcurrentHashMap<String, Long>()
        val endTimes = ConcurrentHashMap<String, Long>()

        val workflow = ParallelWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            maxAgents = 3,
            agentTaskRunner = { _, a ->
                startTimes[a.id.value] = System.currentTimeMillis()
                delay(100) // Simulate work
                endTimes[a.id.value] = System.currentTimeMillis()
                "Result from ${a.displayName}"
            }
        )

        val overallStart = System.currentTimeMillis()
        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)
        val overallEnd = System.currentTimeMillis()

        assertTrue(step is WorkflowStep.Success)
        assertEquals(3, startTimes.size)
        assertEquals(3, endTimes.size)

        // Verify agents started close together (within 50ms)
        val minStartTime = startTimes.values.min()
        val maxStartTime = startTimes.values.max()
        assertTrue(maxStartTime - minStartTime < 50,
            "Agents should start nearly simultaneously: gap was ${maxStartTime - minStartTime}ms")

        // Verify total time is close to single agent time (not 3x)
        val totalTime = overallEnd - overallStart
        assertTrue(totalTime < 200,
            "Parallel execution should complete in ~100ms, took ${totalTime}ms")
    }
}
