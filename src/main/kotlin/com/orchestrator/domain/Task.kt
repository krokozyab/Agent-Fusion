package com.orchestrator.domain

import java.time.Instant

/** Unique identifier for a Task */
data class TaskId(val value: String) {
    init {
        require(value.isNotBlank()) { "TaskId cannot be blank" }
    }
    override fun toString(): String = value
}

/**
 * High-level classification of the task being executed.
 */
enum class TaskType {
    IMPLEMENTATION,
    ARCHITECTURE,
    REVIEW,
    RESEARCH,
    TESTING,
    DOCUMENTATION,
    PLANNING,
    BUGFIX
}

/**
 * Current lifecycle status of a task.
 */
enum class TaskStatus {
    PENDING,
    IN_PROGRESS,
    WAITING_INPUT,
    COMPLETED,
    FAILED
}

/**
 * Strategy that defines how agents are routed/organized to execute a task.
 */
enum class RoutingStrategy {
    SOLO,
    CONSENSUS,
    SEQUENTIAL,
    PARALLEL
}

/**
 * Immutable Task domain model.
 */
data class Task(
    val id: TaskId,
    val title: String,
    val description: String? = null,
    val type: TaskType,
    val status: TaskStatus = TaskStatus.PENDING,
    val routing: RoutingStrategy = RoutingStrategy.SOLO,

    /** Optional agents suggested or assigned to this task */
    val assigneeIds: Set<AgentId> = emptySet(),

    /** Optional dependency tasks that should be completed first */
    val dependencies: Set<TaskId> = emptySet(),

    /** Relative difficulty (1..10 inclusive) */
    val complexity: Int = 5,

    /** Relative risk (1..10 inclusive) */
    val risk: Int = 5,

    /** Creation time */
    val createdAt: Instant = Instant.now(),

    /** Last update time (should not precede createdAt) */
    val updatedAt: Instant? = null,

    /** Optional due date */
    val dueAt: Instant? = null,

    /** Arbitrary metadata for extensibility */
    val metadata: Map<String, String> = emptyMap()
) {
    init {
        require(title.isNotBlank()) { "Task title cannot be blank" }
        require(complexity in 1..10) { "Task complexity must be between 1 and 10 inclusive, was $complexity" }
        require(risk in 1..10) { "Task risk must be between 1 and 10 inclusive, was $risk" }
        require(!dependencies.contains(id)) { "Task cannot depend on itself" }
        if (updatedAt != null) require(!updatedAt.isBefore(createdAt)) { "updatedAt cannot be before createdAt" }
        if (dueAt != null) require(!dueAt.isBefore(createdAt)) { "dueAt cannot be before createdAt" }
    }
}
