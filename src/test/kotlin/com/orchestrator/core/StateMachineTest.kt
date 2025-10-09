package com.orchestrator.core

import com.orchestrator.domain.TaskId
import com.orchestrator.domain.TaskStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StateMachineTest {
    private val testTaskId = TaskId("test-task")

    @AfterEach
    fun cleanup() {
        StateMachine.clearAllHistory()
    }

    @Test
    fun `valid transitions are accepted`() {
        // PENDING -> IN_PROGRESS
        assertTrue(StateMachine.isValidTransition(TaskStatus.PENDING, TaskStatus.IN_PROGRESS))
        
        // IN_PROGRESS -> COMPLETED
        assertTrue(StateMachine.isValidTransition(TaskStatus.IN_PROGRESS, TaskStatus.COMPLETED))
        
        // IN_PROGRESS -> WAITING_INPUT
        assertTrue(StateMachine.isValidTransition(TaskStatus.IN_PROGRESS, TaskStatus.WAITING_INPUT))
        
        // WAITING_INPUT -> IN_PROGRESS
        assertTrue(StateMachine.isValidTransition(TaskStatus.WAITING_INPUT, TaskStatus.IN_PROGRESS))
        
        // Any non-terminal -> FAILED
        assertTrue(StateMachine.isValidTransition(TaskStatus.PENDING, TaskStatus.FAILED))
        assertTrue(StateMachine.isValidTransition(TaskStatus.IN_PROGRESS, TaskStatus.FAILED))
        assertTrue(StateMachine.isValidTransition(TaskStatus.WAITING_INPUT, TaskStatus.FAILED))
    }

    @Test
    fun `invalid transitions are rejected`() {
        // Terminal states cannot transition
        assertFalse(StateMachine.isValidTransition(TaskStatus.COMPLETED, TaskStatus.IN_PROGRESS))
        assertFalse(StateMachine.isValidTransition(TaskStatus.FAILED, TaskStatus.PENDING))
        
        // Cannot skip states
        assertFalse(StateMachine.isValidTransition(TaskStatus.PENDING, TaskStatus.COMPLETED))
        assertFalse(StateMachine.isValidTransition(TaskStatus.PENDING, TaskStatus.WAITING_INPUT))
        
        // Cannot go backwards
        assertFalse(StateMachine.isValidTransition(TaskStatus.IN_PROGRESS, TaskStatus.PENDING))
        assertFalse(StateMachine.isValidTransition(TaskStatus.COMPLETED, TaskStatus.PENDING))
    }

    @Test
    fun `transition records history`() {
        val success = StateMachine.transition(testTaskId, TaskStatus.PENDING, TaskStatus.IN_PROGRESS)
        
        assertTrue(success)
        
        val history = StateMachine.getHistory(testTaskId)
        assertEquals(1, history.size)
        assertEquals(TaskStatus.PENDING, history[0].from)
        assertEquals(TaskStatus.IN_PROGRESS, history[0].to)
    }

    @Test
    fun `invalid transition does not record history`() {
        val success = StateMachine.transition(testTaskId, TaskStatus.COMPLETED, TaskStatus.PENDING)
        
        assertFalse(success)
        
        val history = StateMachine.getHistory(testTaskId)
        assertTrue(history.isEmpty())
    }

    @Test
    fun `multiple transitions build history`() {
        StateMachine.transition(testTaskId, TaskStatus.PENDING, TaskStatus.IN_PROGRESS)
        StateMachine.transition(testTaskId, TaskStatus.IN_PROGRESS, TaskStatus.WAITING_INPUT)
        StateMachine.transition(testTaskId, TaskStatus.WAITING_INPUT, TaskStatus.IN_PROGRESS)
        StateMachine.transition(testTaskId, TaskStatus.IN_PROGRESS, TaskStatus.COMPLETED)
        
        val history = StateMachine.getHistory(testTaskId)
        assertEquals(4, history.size)
        assertEquals(TaskStatus.PENDING, history[0].from)
        assertEquals(TaskStatus.COMPLETED, history[3].to)
    }

    @Test
    fun `getCurrentState returns latest state`() {
        assertNull(StateMachine.getCurrentState(testTaskId))
        
        StateMachine.transition(testTaskId, TaskStatus.PENDING, TaskStatus.IN_PROGRESS)
        assertEquals(TaskStatus.IN_PROGRESS, StateMachine.getCurrentState(testTaskId))
        
        StateMachine.transition(testTaskId, TaskStatus.IN_PROGRESS, TaskStatus.COMPLETED)
        assertEquals(TaskStatus.COMPLETED, StateMachine.getCurrentState(testTaskId))
    }

    @Test
    fun `metadata is stored with transition`() {
        val metadata = mapOf("reason" to "user request", "agent" to "agent-1")
        StateMachine.transition(testTaskId, TaskStatus.PENDING, TaskStatus.IN_PROGRESS, metadata)
        
        val history = StateMachine.getHistory(testTaskId)
        assertEquals(metadata, history[0].metadata)
    }

    @Test
    fun `clearHistory removes task history`() {
        StateMachine.transition(testTaskId, TaskStatus.PENDING, TaskStatus.IN_PROGRESS)
        assertFalse(StateMachine.getHistory(testTaskId).isEmpty())
        
        StateMachine.clearHistory(testTaskId)
        assertTrue(StateMachine.getHistory(testTaskId).isEmpty())
    }

    @Test
    fun `getValidTransitions returns allowed states`() {
        val fromPending = StateMachine.getValidTransitions(TaskStatus.PENDING)
        assertEquals(setOf(TaskStatus.IN_PROGRESS, TaskStatus.FAILED), fromPending)
        
        val fromInProgress = StateMachine.getValidTransitions(TaskStatus.IN_PROGRESS)
        assertEquals(setOf(TaskStatus.WAITING_INPUT, TaskStatus.COMPLETED, TaskStatus.FAILED), fromInProgress)
        
        val fromCompleted = StateMachine.getValidTransitions(TaskStatus.COMPLETED)
        assertTrue(fromCompleted.isEmpty())
    }

    @Test
    fun `isTerminal identifies terminal states`() {
        assertFalse(StateMachine.isTerminal(TaskStatus.PENDING))
        assertFalse(StateMachine.isTerminal(TaskStatus.IN_PROGRESS))
        assertFalse(StateMachine.isTerminal(TaskStatus.WAITING_INPUT))
        assertTrue(StateMachine.isTerminal(TaskStatus.COMPLETED))
        assertTrue(StateMachine.isTerminal(TaskStatus.FAILED))
    }

    @Test
    fun `concurrent transitions are thread-safe`() {
        val numThreads = 10
        val transitionsPerThread = 100
        val executor = Executors.newFixedThreadPool(numThreads)
        val latch = CountDownLatch(numThreads)

        repeat(numThreads) { threadIndex ->
            executor.submit {
                try {
                    repeat(transitionsPerThread) { i ->
                        val taskId = TaskId("task-$threadIndex-$i")
                        StateMachine.transition(taskId, TaskStatus.PENDING, TaskStatus.IN_PROGRESS)
                        StateMachine.transition(taskId, TaskStatus.IN_PROGRESS, TaskStatus.COMPLETED)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        // Verify all transitions were recorded
        var totalTransitions = 0
        repeat(numThreads) { threadIndex ->
            repeat(transitionsPerThread) { i ->
                val taskId = TaskId("task-$threadIndex-$i")
                totalTransitions += StateMachine.getHistory(taskId).size
            }
        }
        
        assertEquals(numThreads * transitionsPerThread * 2, totalTransitions)
    }

    @Test
    fun `history is isolated per task`() {
        val task1 = TaskId("task-1")
        val task2 = TaskId("task-2")
        
        StateMachine.transition(task1, TaskStatus.PENDING, TaskStatus.IN_PROGRESS)
        StateMachine.transition(task2, TaskStatus.PENDING, TaskStatus.FAILED)
        
        val history1 = StateMachine.getHistory(task1)
        val history2 = StateMachine.getHistory(task2)
        
        assertEquals(1, history1.size)
        assertEquals(1, history2.size)
        assertEquals(TaskStatus.IN_PROGRESS, history1[0].to)
        assertEquals(TaskStatus.FAILED, history2[0].to)
    }

    @Test
    fun `transition timestamps are recorded`() {
        val before = System.currentTimeMillis()
        StateMachine.transition(testTaskId, TaskStatus.PENDING, TaskStatus.IN_PROGRESS)
        val after = System.currentTimeMillis()
        
        val history = StateMachine.getHistory(testTaskId)
        val timestamp = history[0].timestamp.toEpochMilli()
        
        assertTrue(timestamp >= before)
        assertTrue(timestamp <= after)
    }
}
