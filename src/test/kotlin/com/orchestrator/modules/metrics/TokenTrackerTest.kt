package com.orchestrator.modules.metrics

import com.orchestrator.domain.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TokenTrackerTest {

    @BeforeEach
    fun setup() {
        TokenTracker.reset()
        TokenTracker.clearAlerts()
    }

    @AfterEach
    fun cleanup() {
        TokenTracker.reset()
        TokenTracker.clearAlerts()
    }

    @Test
    fun `track records token usage`() {
        val record = TokenRecord(
            taskId = TaskId("task-1"),
            agentId = AgentId("agent-1"),
            inputTokens = 100,
            outputTokens = 50
        )
        
        TokenTracker.track(record)
        
        assertEquals(150, TokenTracker.getTotalTokens())
        assertEquals(150, TokenTracker.getTaskTokens(TaskId("task-1")))
        assertEquals(150, TokenTracker.getAgentTokens(AgentId("agent-1")))
    }

    @Test
    fun `track accumulates tokens`() {
        val taskId = TaskId("task-1")
        val agentId = AgentId("agent-1")
        
        TokenTracker.track(TokenRecord(taskId, agentId, 100, 50))
        TokenTracker.track(TokenRecord(taskId, agentId, 200, 100))
        
        assertEquals(450, TokenTracker.getTotalTokens())
        assertEquals(450, TokenTracker.getTaskTokens(taskId))
        assertEquals(450, TokenTracker.getAgentTokens(agentId))
    }

    @Test
    fun `track separates by task`() {
        TokenTracker.track(TokenRecord(TaskId("task-1"), AgentId("agent-1"), 100, 50))
        TokenTracker.track(TokenRecord(TaskId("task-2"), AgentId("agent-1"), 200, 100))
        
        assertEquals(150, TokenTracker.getTaskTokens(TaskId("task-1")))
        assertEquals(300, TokenTracker.getTaskTokens(TaskId("task-2")))
        assertEquals(450, TokenTracker.getTotalTokens())
    }

    @Test
    fun `track separates by agent`() {
        TokenTracker.track(TokenRecord(TaskId("task-1"), AgentId("agent-1"), 100, 50))
        TokenTracker.track(TokenRecord(TaskId("task-1"), AgentId("agent-2"), 200, 100))
        
        assertEquals(150, TokenTracker.getAgentTokens(AgentId("agent-1")))
        assertEquals(300, TokenTracker.getAgentTokens(AgentId("agent-2")))
        assertEquals(450, TokenTracker.getTotalTokens())
    }

    @Test
    fun `trackProposal extracts token usage`() {
        val proposal = Proposal(
            id = ProposalId("prop-1"),
            taskId = TaskId("task-1"),
            agentId = AgentId("agent-1"),
            inputType = InputType.IMPLEMENTATION_PLAN,
            content = "test",
            confidence = 0.9,
            tokenUsage = TokenUsage(inputTokens = 100, outputTokens = 50)
        )
        
        TokenTracker.trackProposal(proposal)
        
        assertEquals(150, TokenTracker.getTotalTokens())
    }

    @Test
    fun `recordSavings tracks saved tokens`() {
        TokenTracker.recordSavings(1000)
        TokenTracker.recordSavings(500)
        
        assertEquals(1500, TokenTracker.getSavedTokens())
    }

    @Test
    fun `addAlert triggers on threshold`() {
        val triggered = AtomicInteger(0)
        
        TokenTracker.addAlert(1000) { usage, threshold ->
            triggered.incrementAndGet()
        }
        
        TokenTracker.track(TokenRecord(TaskId("task-1"), AgentId("agent-1"), 500, 500))
        
        assertEquals(1, triggered.get())
    }

    @Test
    fun `addAlert triggers multiple times`() {
        val triggered = AtomicInteger(0)
        
        TokenTracker.addAlert(500) { _, _ ->
            triggered.incrementAndGet()
        }
        
        TokenTracker.track(TokenRecord(TaskId("task-1"), AgentId("agent-1"), 300, 300))
        TokenTracker.track(TokenRecord(TaskId("task-2"), AgentId("agent-1"), 300, 300))
        
        assertEquals(2, triggered.get())
    }

    @Test
    fun `addAlert does not trigger below threshold`() {
        val triggered = AtomicInteger(0)
        
        TokenTracker.addAlert(1000) { _, _ ->
            triggered.incrementAndGet()
        }
        
        TokenTracker.track(TokenRecord(TaskId("task-1"), AgentId("agent-1"), 200, 200))
        
        assertEquals(0, triggered.get())
    }

    @Test
    fun `multiple alerts can be registered`() {
        val alert1 = AtomicInteger(0)
        val alert2 = AtomicInteger(0)
        
        TokenTracker.addAlert(500) { _, _ -> alert1.incrementAndGet() }
        TokenTracker.addAlert(1000) { _, _ -> alert2.incrementAndGet() }
        
        TokenTracker.track(TokenRecord(TaskId("task-1"), AgentId("agent-1"), 600, 600))
        
        assertEquals(1, alert1.get())
        assertEquals(1, alert2.get())
    }

    @Test
    fun `clearAlerts removes all alerts`() {
        val triggered = AtomicInteger(0)
        
        TokenTracker.addAlert(500) { _, _ -> triggered.incrementAndGet() }
        TokenTracker.clearAlerts()
        
        TokenTracker.track(TokenRecord(TaskId("task-1"), AgentId("agent-1"), 300, 300))
        
        assertEquals(0, triggered.get())
    }

    @Test
    fun `generateReport includes all data`() {
        TokenTracker.track(TokenRecord(TaskId("task-1"), AgentId("agent-1"), 100, 50))
        TokenTracker.track(TokenRecord(TaskId("task-2"), AgentId("agent-2"), 200, 100))
        TokenTracker.recordSavings(500)
        
        val report = TokenTracker.generateReport()
        
        assertEquals(450, report.totalTokens)
        assertEquals(150, report.byTask[TaskId("task-1")])
        assertEquals(300, report.byTask[TaskId("task-2")])
        assertEquals(150, report.byAgent[AgentId("agent-1")])
        assertEquals(300, report.byAgent[AgentId("agent-2")])
        assertEquals(500, report.savings)
    }

    @Test
    fun `reset clears all counters`() {
        TokenTracker.track(TokenRecord(TaskId("task-1"), AgentId("agent-1"), 100, 50))
        TokenTracker.recordSavings(500)
        
        TokenTracker.reset()
        
        assertEquals(0, TokenTracker.getTotalTokens())
        assertEquals(0, TokenTracker.getSavedTokens())
        assertEquals(0, TokenTracker.getTaskTokens(TaskId("task-1")))
        assertEquals(0, TokenTracker.getAgentTokens(AgentId("agent-1")))
    }

    @Test
    fun `getTaskTokens returns zero for unknown task`() {
        assertEquals(0, TokenTracker.getTaskTokens(TaskId("unknown")))
    }

    @Test
    fun `getAgentTokens returns zero for unknown agent`() {
        assertEquals(0, TokenTracker.getAgentTokens(AgentId("unknown")))
    }

    @Test
    fun `concurrent tracking is thread-safe`() {
        val numThreads = 10
        val recordsPerThread = 100
        val executor = Executors.newFixedThreadPool(numThreads)
        val latch = CountDownLatch(numThreads)
        
        repeat(numThreads) { threadIndex ->
            executor.submit {
                try {
                    repeat(recordsPerThread) { i ->
                        TokenTracker.track(TokenRecord(
                            taskId = TaskId("task-$threadIndex"),
                            agentId = AgentId("agent-$threadIndex"),
                            inputTokens = 10,
                            outputTokens = 10
                        ))
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        
        // Each thread tracked 100 records of 20 tokens each = 2000 tokens per thread
        assertEquals(numThreads * recordsPerThread * 20, TokenTracker.getTotalTokens())
    }

    @Test
    fun `alert handler errors do not break tracking`() {
        TokenTracker.addAlert(100) { _, _ ->
            throw RuntimeException("Handler error")
        }
        
        // Should not throw
        TokenTracker.track(TokenRecord(TaskId("task-1"), AgentId("agent-1"), 60, 60))
        
        assertEquals(120, TokenTracker.getTotalTokens())
    }

    @Test
    fun `TokenRecord calculates total tokens`() {
        val record = TokenRecord(
            taskId = TaskId("task-1"),
            agentId = AgentId("agent-1"),
            inputTokens = 100,
            outputTokens = 50
        )
        
        assertEquals(150, record.totalTokens)
    }

    @Test
    fun `TokenReport includes period`() {
        val from = Instant.now().minusSeconds(3600)
        val to = Instant.now()
        
        val report = TokenTracker.generateReport(from, to)
        
        assertEquals(from, report.period.first)
        assertEquals(to, report.period.second)
    }
}
