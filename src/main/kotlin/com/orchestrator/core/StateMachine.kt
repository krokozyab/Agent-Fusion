package com.orchestrator.core

import com.orchestrator.domain.TaskId
import com.orchestrator.domain.TaskStatus
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * State transition record for history tracking.
 */
data class StateTransition(
    val from: TaskStatus,
    val to: TaskStatus,
    val timestamp: Instant = Instant.now(),
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Thread-safe state machine for task status transitions.
 * 
 * Validates transitions according to defined rules and maintains history per task.
 */
object StateMachine {
    private val validTransitions: Map<TaskStatus, Set<TaskStatus>> = mapOf(
        TaskStatus.PENDING to setOf(TaskStatus.IN_PROGRESS, TaskStatus.FAILED),
        TaskStatus.IN_PROGRESS to setOf(TaskStatus.WAITING_INPUT, TaskStatus.COMPLETED, TaskStatus.FAILED),
        TaskStatus.WAITING_INPUT to setOf(TaskStatus.IN_PROGRESS, TaskStatus.FAILED),
        TaskStatus.COMPLETED to emptySet(), // Terminal state
        TaskStatus.FAILED to emptySet() // Terminal state
    )

    private val history = ConcurrentHashMap<TaskId, MutableList<StateTransition>>()

    /**
     * Check if a transition from one state to another is valid.
     */
    fun isValidTransition(from: TaskStatus, to: TaskStatus): Boolean {
        return validTransitions[from]?.contains(to) ?: false
    }

    /**
     * Attempt a state transition. Returns true if valid and recorded, false otherwise.
     */
    fun transition(
        taskId: TaskId,
        from: TaskStatus,
        to: TaskStatus,
        metadata: Map<String, String> = emptyMap()
    ): Boolean {
        if (!isValidTransition(from, to)) {
            return false
        }

        val transition = StateTransition(from, to, Instant.now(), metadata)
        history.computeIfAbsent(taskId) { mutableListOf() }.add(transition)
        return true
    }

    /**
     * Get transition history for a task.
     */
    fun getHistory(taskId: TaskId): List<StateTransition> {
        return history[taskId]?.toList() ?: emptyList()
    }

    /**
     * Get the latest state for a task from history, or null if no history exists.
     */
    fun getCurrentState(taskId: TaskId): TaskStatus? {
        return history[taskId]?.lastOrNull()?.to
    }

    /**
     * Clear history for a task (useful for testing or cleanup).
     */
    fun clearHistory(taskId: TaskId) {
        history.remove(taskId)
    }

    /**
     * Clear all history (useful for testing).
     */
    fun clearAllHistory() {
        history.clear()
    }

    /**
     * Get all valid transitions from a given state.
     */
    fun getValidTransitions(from: TaskStatus): Set<TaskStatus> {
        return validTransitions[from] ?: emptySet()
    }

    /**
     * Check if a state is terminal (no outgoing transitions).
     */
    fun isTerminal(state: TaskStatus): Boolean {
        return validTransitions[state]?.isEmpty() ?: true
    }
}
