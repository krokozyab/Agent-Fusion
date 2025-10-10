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

class SequentialWorkflowTest {

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
        routing: RoutingStrategy = RoutingStrategy.SEQUENTIAL,
        assignees: Set<AgentId> = emptySet()
    ): Task {
        return Task(
            id = TaskId(id),
            title = "Sequential task $id",
            description = "Test sequential execution",
            type = TaskType.PLANNING,
            status = TaskStatus.PENDING,
            routing = routing,
            assigneeIds = assignees,
            dependencies = emptySet(),
            complexity = 5,
            risk = 4,
            createdAt = Instant.now(),
            updatedAt = null,
            dueAt = null,
            metadata = emptyMap()
        )
    }

    @Test
    fun executes_planner_then_implementer_successfully() = runBlocking {
        val registry = registryWithAgents(2)
        val task = sampleTask("t-seq-1")
        TaskRepository.insert(task)

        var plannerCalled = false
        var implementerCalled = false
        var planPassedToImplementer: String? = null

        val workflow = SequentialWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            messageRepository = MessageRepository,
            plannerRunner = { t, planner, _ ->
                plannerCalled = true
                val plan = "Plan for ${t.id.value} by ${planner.displayName}"
                plan to mapOf("planType" to "detailed")
            },
            planValidator = { plan, _ -> plan.isNotBlank() },
            implementerRunner = { t, implementer, plan, _ ->
                implementerCalled = true
                planPassedToImplementer = plan
                "Implemented ${t.id.value} by ${implementer.displayName} using plan"
            }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        // Verify workflow succeeded
        assertTrue(step is WorkflowStep.Success)
        assertEquals(TaskStatus.COMPLETED, runtime.currentStatus)

        // Verify both phases executed
        assertTrue(plannerCalled, "Planner should be called")
        assertTrue(implementerCalled, "Implementer should be called")

        // Verify plan was passed to implementer
        assertNotNull(planPassedToImplementer)
        assertTrue(planPassedToImplementer?.contains("Plan for ${task.id.value}") == true)

        // Verify output
        val output = (step as WorkflowStep.Success).output
        assertTrue(output?.contains("Implemented") == true)

        // Verify artifacts contain plan
        assertNotNull(step.artifacts["plan"])
        assertTrue((step.artifacts["plan"] as String).contains("Plan for"))
    }

    @Test
    fun records_messages_for_both_planner_and_implementer() = runBlocking {
        val registry = registryWithAgents(2)
        val task = sampleTask("t-seq-2")
        TaskRepository.insert(task)

        val workflow = SequentialWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            messageRepository = MessageRepository,
            plannerRunner = { _, _, _ -> "Test Plan" to emptyMap() },
            planValidator = { plan, _ -> plan.isNotBlank() },
            implementerRunner = { _, _, _, _ -> "Test Implementation" }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        assertTrue(step is WorkflowStep.Success)

        // Verify two messages were persisted (planner + implementer)
        val messages = MessageRepository.listByTask(task.id)
        assertEquals(2, messages.size)
        assertEquals("planner", messages[0].role)
        assertEquals("agent", messages[1].role)
        assertTrue(messages[0].content.contains("Test Plan"))
        assertTrue(messages[1].content.contains("Test Implementation"))
    }

    @Test
    fun uses_same_agent_when_only_one_available() = runBlocking {
        val registry = registryWithAgents(1)
        val task = sampleTask("t-seq-3")
        TaskRepository.insert(task)

        val agentsUsed = mutableListOf<String>()

        val workflow = SequentialWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            plannerRunner = { _, planner, _ ->
                agentsUsed.add("planner:${planner.id.value}")
                "Plan" to emptyMap()
            },
            planValidator = { plan, _ -> plan.isNotBlank() },
            implementerRunner = { _, implementer, _, _ ->
                agentsUsed.add("implementer:${implementer.id.value}")
                "Done"
            }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        assertTrue(step is WorkflowStep.Success)
        assertEquals(2, agentsUsed.size)
        assertTrue(agentsUsed[0].contains("agent-1"))
        assertTrue(agentsUsed[1].contains("agent-1"))
    }

    @Test
    fun uses_different_agents_when_multiple_available() = runBlocking {
        val registry = registryWithAgents(3)
        val task = sampleTask("t-seq-4")
        TaskRepository.insert(task)

        val agentsUsed = mutableListOf<String>()

        val workflow = SequentialWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            plannerRunner = { _, planner, _ ->
                agentsUsed.add(planner.id.value)
                "Plan" to emptyMap()
            },
            planValidator = { plan, _ -> plan.isNotBlank() },
            implementerRunner = { _, implementer, _, _ ->
                agentsUsed.add(implementer.id.value)
                "Done"
            }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        assertTrue(step is WorkflowStep.Success)
        assertEquals(2, agentsUsed.size)
        // Verify two different agents were used (order may vary)
        assertNotEquals(agentsUsed[0], agentsUsed[1], "Should use different agents for planner and implementer")
    }

    @Test
    fun fails_when_no_agents_available() = runBlocking {
        val registry = AgentRegistry.empty()
        val task = sampleTask("t-seq-5")
        TaskRepository.insert(task)

        val workflow = SequentialWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore()
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        assertTrue(step is WorkflowStep.Failure)
        assertEquals(TaskStatus.FAILED, runtime.currentStatus)
        assertTrue((step as WorkflowStep.Failure).error.contains("No suitable agents"))
    }

    @Test
    fun retries_planning_when_validation_fails() = runBlocking {
        val registry = registryWithAgents(2)
        val task = sampleTask("t-seq-6")
        TaskRepository.insert(task)

        var planAttempt = 0
        val maxIterations = 2

        val workflow = SequentialWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            maxIterations = maxIterations,
            plannerRunner = { _, _, _ ->
                planAttempt++
                if (planAttempt < 2) {
                    "Invalid plan" to mapOf("valid" to "false")
                } else {
                    "Valid plan with details" to mapOf("valid" to "true")
                }
            },
            planValidator = { plan, _ ->
                plan.contains("details") // Only second plan passes
            },
            implementerRunner = { _, _, _, _ -> "Implementation complete" }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        assertTrue(step is WorkflowStep.Success)
        assertEquals(2, planAttempt) // Should have retried planning
    }

    @Test
    fun fails_when_plan_never_validates_within_max_iterations() = runBlocking {
        val registry = registryWithAgents(2)
        val task = sampleTask("t-seq-7")
        TaskRepository.insert(task)

        val maxIterations = 3
        var planAttempts = 0

        val workflow = SequentialWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            maxIterations = maxIterations,
            plannerRunner = { _, _, _ ->
                planAttempts++
                "Always invalid plan" to emptyMap()
            },
            planValidator = { _, _ -> false }, // Always fails validation
            implementerRunner = { _, _, _, _ -> "Should not reach here" }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        // Should fail because validation never passes
        assertTrue(step is WorkflowStep.Failure, "Workflow should fail when plan never validates")
        assertEquals(maxIterations, planAttempts, "Should attempt planning maxIterations times")

        // Verify error message indicates validation failure
        val error = (step as WorkflowStep.Failure).error
        assertTrue(
            error.contains("never validated") || error.contains("validation"),
            "Error should mention validation failure, got: $error"
        )
    }

    @Test
    fun fails_when_planner_throws_exception() = runBlocking {
        val registry = registryWithAgents(2)
        val task = sampleTask("t-seq-8")
        TaskRepository.insert(task)

        val workflow = SequentialWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            plannerRunner = { _, _, _ ->
                throw RuntimeException("Planning failed")
            },
            planValidator = { plan, _ -> plan.isNotBlank() },
            implementerRunner = { _, _, _, _ -> "Should not reach" }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        assertTrue(step is WorkflowStep.Failure)
        assertTrue((step as WorkflowStep.Failure).error.contains("Planning failed"))
    }

    @Test
    fun fails_when_implementer_throws_exception() = runBlocking {
        val registry = registryWithAgents(2)
        val task = sampleTask("t-seq-9")
        TaskRepository.insert(task)

        val workflow = SequentialWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            plannerRunner = { _, _, _ -> "Good plan" to emptyMap() },
            planValidator = { plan, _ -> plan.isNotBlank() },
            implementerRunner = { _, _, _, _ ->
                throw RuntimeException("Implementation failed")
            }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        assertTrue(step is WorkflowStep.Failure)
        assertTrue((step as WorkflowStep.Failure).error.contains("Implementation failed"))
    }

    @Test
    fun passes_plan_metadata_to_implementer() = runBlocking {
        val registry = registryWithAgents(2)
        val task = sampleTask("t-seq-10")
        TaskRepository.insert(task)

        var implementerContext: Map<String, String>? = null

        val workflow = SequentialWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            plannerRunner = { _, _, _ ->
                "Plan text" to mapOf("complexity" to "high", "estimated_hours" to "10")
            },
            planValidator = { plan, _ -> plan.isNotBlank() },
            implementerRunner = { _, _, _, context ->
                implementerContext = context
                "Implementation done"
            }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        assertTrue(step is WorkflowStep.Success)
        assertNotNull(implementerContext)
        assertTrue(implementerContext!!.containsKey("complexity"))
        assertEquals("high", implementerContext!!["complexity"])
        assertEquals("10", implementerContext!!["estimated_hours"])
    }

    @Test
    fun resume_re_executes_workflow() = runBlocking {
        val registry = registryWithAgents(2)
        val task = sampleTask("t-seq-11")
        TaskRepository.insert(task)

        var executionCount = 0

        val workflow = SequentialWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            plannerRunner = { _, _, _ ->
                executionCount++
                "Plan $executionCount" to emptyMap()
            },
            planValidator = { plan, _ -> plan.isNotBlank() },
            implementerRunner = { _, _, _, _ -> "Implementation" }
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
    fun supports_only_sequential_routing_strategy() {
        val registry = registryWithAgents(2)
        val workflow = SequentialWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore()
        )

        assertEquals(setOf(RoutingStrategy.SEQUENTIAL), workflow.supportedStrategies)
    }

    @Test
    fun validator_exception_counts_as_validation_failure() = runBlocking {
        val registry = registryWithAgents(2)
        val task = sampleTask("t-seq-12")
        TaskRepository.insert(task)

        var attempts = 0

        val workflow = SequentialWorkflow(
            agentRegistry = registry,
            stateStore = InMemoryWorkflowStateStore(),
            maxIterations = 2,
            plannerRunner = { _, _, _ ->
                attempts++
                if (attempts == 1) {
                    "First plan" to emptyMap()
                } else {
                    "Second plan with validation" to emptyMap()
                }
            },
            planValidator = { plan, _ ->
                if (!plan.contains("validation")) {
                    throw RuntimeException("Validator error")
                }
                true
            },
            implementerRunner = { _, _, _, _ -> "Done" }
        )

        val runtime = WorkflowRuntime(task = task, currentStatus = task.status)
        val step = workflow.execute(runtime)

        // Should succeed on second attempt
        assertTrue(step is WorkflowStep.Success)
        assertEquals(2, attempts)
    }
}
