package com.orchestrator.modules.consensus

import com.orchestrator.domain.*
import com.orchestrator.storage.repositories.ProposalRepository
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manages the lifecycle of proposals for tasks, supporting safe concurrent submissions
 * and timed waiting for new proposals to arrive.
 *
 * Responsibilities:
 * - Validate and store proposals
 * - Retrieve proposals for a task
 * - Allow callers to wait for proposals with timeout (returns whatever is available)
 * - Thread-safe notifications to wake waiting threads when new proposals arrive
 */
object ProposalManager {
    // Per-task wait/notify primitives and cached counts
    private data class TaskSignal(
        val lock: ReentrantLock = ReentrantLock(),
        val condition: java.util.concurrent.locks.Condition = lock.newCondition(),
        // Track number of proposals known at last signal to implement "new proposals" waiting
        var count: Int = 0
    )

    private val signals = ConcurrentHashMap<String, TaskSignal>() // key by TaskId.value

    private fun signalFor(taskId: TaskId): TaskSignal =
        signals.computeIfAbsent(taskId.value) { TaskSignal() }

    /**
     * Submit a proposal into storage and notify any waiters for the task.
     * Minimal input API as specified by TASK-028.
     *
     * Validation rules:
     * - taskId/agentId must be non-blank (enforced by value classes)
     * - content must be JSON-compatible (Proposal ctor enforces)
     */
    fun submitProposal(
        taskId: TaskId,
        agentId: AgentId,
        content: Any?,
        inputType: InputType = InputType.OTHER,
        confidence: Double = 0.5,
        tokenUsage: TokenUsage = TokenUsage(),
        metadata: Map<String, String> = emptyMap()
    ): Proposal {
        // Build proposal (constructor performs validation including content JSON-compatibility and confidence bounds)
        val proposal = Proposal(
            id = ProposalId(UUID.randomUUID().toString()),
            taskId = taskId,
            agentId = agentId,
            inputType = inputType,
            content = content,
            confidence = confidence,
            tokenUsage = tokenUsage,
            createdAt = Instant.now(),
            metadata = metadata
        )

        // Persist
        ProposalRepository.insert(proposal)

        // Update count and signal waiters
        val sig = signalFor(taskId)
        sig.lock.withLock {
            sig.count += 1
            sig.condition.signalAll()
        }

        return proposal
    }

    /** Returns proposals currently stored for the task, newest first (as per repository). */
    fun getProposals(taskId: TaskId): List<Proposal> = ProposalRepository.findByTask(taskId)

    /**
     * Wait up to [timeout] for proposals for the given task.
     * - If new proposals arrive during the wait, returns the full current set at wake time.
     * - If timeout elapses without new arrivals, returns whatever is currently stored.
     * - Always thread-safe. Multiple callers can wait simultaneously.
     */
    fun waitForProposals(taskId: TaskId, timeout: Duration): List<Proposal> {
        val sig = signalFor(taskId)

        // Snapshot current count before waiting
        val startKnownCount = sig.lock.withLock { sig.count }

        // Fast path: if proposals already exist, we still honor waiting to allow callers to
        // optionally wait for additional ones within the timeout. Only wait if no new arrivals
        // since snapshot; otherwise skip waiting.
        if (!timeout.isZero && !timeout.isNegative) {
            val nanos = timeout.toNanos().coerceAtLeast(0)
            var remaining = nanos
            sig.lock.withLock {
                while (remaining > 0) {
                    if (sig.count > startKnownCount) break // new proposals arrived
                    val before = System.nanoTime()
                    try {
                        sig.condition.await(remaining, TimeUnit.NANOSECONDS)
                    } catch (_: InterruptedException) {
                        // On interruption, break and return current proposals
                        break
                    }
                    val after = System.nanoTime()
                    remaining -= (after - before)
                }
            }
        }

        return getProposals(taskId)
    }

    // Utility primarily for tests: reset internal signals without touching DB
    internal fun clearSignals() {
        signals.clear()
    }
}
