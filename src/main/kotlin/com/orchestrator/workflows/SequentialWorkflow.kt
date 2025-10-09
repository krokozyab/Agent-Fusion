package com.orchestrator.workflows

import com.orchestrator.core.AgentRegistry
import com.orchestrator.core.EventBus
import com.orchestrator.domain.*
import com.orchestrator.modules.metrics.TokenTracker
import com.orchestrator.storage.repositories.MessageRepository
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * Sequential workflow: planner -> implementer with validation and iteration.
 * Refactored to use coroutines and BaseWorkflowExecutor.
 *
 * Responsibilities:
 * - Execute planning phase (planner agent)
 * - Validate plan (basic non-empty + optional predicate)
 * - Execute implementation phase (implementer agent)
 * - Pass context (plan + metadata) between phases
 * - Support limited iteration if validation fails
 */
class SequentialWorkflow(
    agentRegistry: AgentRegistry,
    stateStore: WorkflowStateStore = InMemoryWorkflowStateStore(),
    messageRepository: MessageRepository = MessageRepository,
    eventBus: EventBus = EventBus.global,
    private val maxIterations: Int = 2,
    /**
     * Planner runs to produce a plan text and optional metadata.
     */
    private val plannerRunner: suspend (task: Task, planner: Agent, previousContext: Map<String, String>) -> Pair<String, Map<String, String>> = { task, planner, _ ->
        "Plan for task ${task.id.value} by ${planner.displayName}" to emptyMap()
    },
    /**
     * Plan validator; default checks non-empty plan text.
     */
    private val planValidator: (planText: String, metadata: Map<String, String>) -> Boolean = { plan, _ ->
        plan.isNotBlank()
    },
    /**
     * Implementer consumes the plan and returns final output.
     */
    private val implementerRunner: suspend (task: Task, implementer: Agent, planText: String, context: Map<String, String>) -> String = { task, implementer, _, _ ->
        "Task ${task.id.value} implemented by ${implementer.displayName}"
    }
) : BaseWorkflowExecutor(
    agentRegistry = agentRegistry,
    stateStore = stateStore,
    messageRepository = messageRepository,
    eventBus = eventBus
) {

    override val supportedStrategies: Set<RoutingStrategy> = setOf(RoutingStrategy.SEQUENTIAL)

    override suspend fun execute(runtime: WorkflowRuntime): WorkflowStep {
        emitCheckpoint(
            runtime = runtime,
            state = WorkflowState.RUNNING,
            label = "sequential:start"
        )

        val (planner, implementer) = pickAgents(runtime.task)
            ?: return createFailure(runtime, "No suitable agents available for SEQUENTIAL execution")

        eventBus.publish(WorkflowEvent.AgentAssigned(runtime.task.id, planner.id))
        eventBus.publish(WorkflowEvent.AgentAssigned(runtime.task.id, implementer.id))

        var context: Map<String, String> = mapOf(
            "plannerId" to planner.id.value,
            "implementerId" to implementer.id.value
        )
        var lastError: String? = null

        for (iteration in 1..maxIterations) {
            emitCheckpoint(
                runtime = runtime,
                state = WorkflowState.RUNNING,
                label = "sequential:iteration",
                data = mapOf("iteration" to iteration.toString())
            )

            // 1) Planning phase
            val (planText, planMeta) = try {
                plannerRunner(runtime.task, planner, context)
            } catch (e: Exception) {
                lastError = e.message ?: e.toString()
                emitCheckpoint(
                    runtime = runtime,
                    state = WorkflowState.FAILED,
                    label = "sequential:plan_failed",
                    data = mapOf(
                        "iteration" to iteration.toString(),
                        "error" to lastError
                    )
                )
                return createFailure(runtime, lastError)
            }

            // Record planner tokens
            val planTokens = recordTokens(runtime, planner.id, planText, isInput = false)
            persistMessage(
                runtime = runtime,
                agentId = planner.id,
                role = "planner",
                content = planText,
                tokens = planTokens
            )

            emitCheckpoint(
                runtime = runtime,
                state = WorkflowState.RUNNING,
                label = "sequential:plan_ready",
                data = mapOf(
                    "iteration" to iteration.toString(),
                    "planLength" to planText.length.toString()
                ) + planMeta
            )

            // 2) Validate plan
            val validationOk = try {
                planValidator(planText, planMeta)
            } catch (e: Exception) {
                log("Plan validation threw exception: ${e.message}")
                false
            }

            emitCheckpoint(
                runtime = runtime,
                state = if (validationOk) WorkflowState.RUNNING else WorkflowState.PAUSED,
                label = "sequential:plan_validated",
                data = mapOf(
                    "iteration" to iteration.toString(),
                    "valid" to validationOk.toString()
                )
            )

            if (!validationOk) {
                lastError = "Plan validation failed on iteration $iteration"
                context = context + planMeta + mapOf(
                    "lastPlanValid" to "false",
                    "lastPlanLength" to planText.length.toString()
                )
                delay(backoffMillis * iteration) // Backoff before retry
                continue
            }

            // 3) Implementation phase
            val output = try {
                implementerRunner(runtime.task, implementer, planText, planMeta + context)
            } catch (e: Exception) {
                lastError = e.message ?: e.toString()
                emitCheckpoint(
                    runtime = runtime,
                    state = WorkflowState.FAILED,
                    label = "sequential:implementation_failed",
                    data = mapOf(
                        "iteration" to iteration.toString(),
                        "error" to lastError
                    )
                )
                return createFailure(runtime, lastError)
            }

            // Record implementer tokens
            val implTokens = recordTokens(runtime, implementer.id, output, isInput = false)
            persistMessage(
                runtime = runtime,
                agentId = implementer.id,
                role = "agent",
                content = output,
                tokens = implTokens
            )

            // Success!
            emitCheckpoint(
                runtime = runtime,
                state = WorkflowState.COMPLETED,
                label = "sequential:completed",
                data = mapOf(
                    "iterations" to iteration.toString(),
                    "planLength" to planText.length.toString()
                )
            )

            return createSuccess(
                runtime = runtime,
                output = output,
                artifacts = mapOf("plan" to planText)
            )
        }

        // If we exit the loop, validation never passed
        val error = lastError ?: "Plan never validated within $maxIterations iterations"
        return createFailure(runtime, error)
    }

    override suspend fun resume(runtime: WorkflowRuntime, checkpointId: String?): WorkflowStep {
        // For Sequential workflow, resume re-executes from current state
        // TODO: In future, restore planner output from checkpoint payload for true resume
        log("Resuming sequential workflow for task ${runtime.task.id.value}")
        return execute(runtime)
    }

    private fun pickAgents(task: Task): Pair<Agent, Agent>? {
        val online = agentRegistry.getAllAgents().filter { it.status == AgentStatus.ONLINE }
        val assigned = if (task.assigneeIds.isNotEmpty()) {
            task.assigneeIds.mapNotNull { agentRegistry.getAgent(it) }
                .filter { it.status == AgentStatus.ONLINE }
        } else emptyList()

        val pool = (assigned + online).distinctBy { it.id }
        if (pool.isEmpty()) return null

        val planner = pool.first()
        val implementer = if (pool.size >= 2) pool[1] else planner
        return planner to implementer
    }
}
