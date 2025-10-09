package com.orchestrator.modules.metrics

import com.orchestrator.domain.AgentId
import com.orchestrator.domain.TaskId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.concurrent.thread

class PerformanceMonitorTest {
    
    @AfterEach
    fun cleanup() {
        PerformanceMonitor.reset()
    }
    
    @Test
    fun `should track task completion time`() {
        val taskId = TaskId("task-1")
        val duration = Duration.ofSeconds(5)
        
        PerformanceMonitor.trackTaskCompletion(taskId, duration, success = true)
        
        val avgTime = PerformanceMonitor.getAvgTaskCompletionTime()
        assertEquals(duration.toMillis(), avgTime.toMillis())
    }
    
    @Test
    fun `should track agent response time`() {
        val taskId = TaskId("task-1")
        val agentId = AgentId("agent-1")
        val duration = Duration.ofSeconds(2)
        
        PerformanceMonitor.trackAgentResponse(taskId, agentId, duration, success = true)
        
        val avgTime = PerformanceMonitor.getAvgAgentResponseTime()
        assertEquals(duration.toMillis(), avgTime.toMillis())
    }
    
    @Test
    fun `should calculate overall success rate`() {
        val taskId = TaskId("task-1")
        
        PerformanceMonitor.trackTaskCompletion(taskId, Duration.ofSeconds(1), success = true)
        PerformanceMonitor.trackTaskCompletion(taskId, Duration.ofSeconds(1), success = true)
        PerformanceMonitor.trackTaskCompletion(taskId, Duration.ofSeconds(1), success = false)
        
        val successRate = PerformanceMonitor.getSuccessRate()
        assertEquals(0.666, successRate, 0.01)
    }
    
    @Test
    fun `should calculate task-specific success rate`() {
        val task1 = TaskId("task-1")
        val task2 = TaskId("task-2")
        
        PerformanceMonitor.trackTaskCompletion(task1, Duration.ofSeconds(1), success = true)
        PerformanceMonitor.trackTaskCompletion(task1, Duration.ofSeconds(1), success = false)
        PerformanceMonitor.trackTaskCompletion(task2, Duration.ofSeconds(1), success = true)
        PerformanceMonitor.trackTaskCompletion(task2, Duration.ofSeconds(1), success = true)
        
        assertEquals(0.5, PerformanceMonitor.getTaskSuccessRate(task1), 0.01)
        assertEquals(1.0, PerformanceMonitor.getTaskSuccessRate(task2), 0.01)
    }
    
    @Test
    fun `should calculate agent-specific success rate`() {
        val taskId = TaskId("task-1")
        val agent1 = AgentId("agent-1")
        val agent2 = AgentId("agent-2")
        
        PerformanceMonitor.trackAgentResponse(taskId, agent1, Duration.ofSeconds(1), success = true)
        PerformanceMonitor.trackAgentResponse(taskId, agent1, Duration.ofSeconds(1), success = true)
        PerformanceMonitor.trackAgentResponse(taskId, agent1, Duration.ofSeconds(1), success = false)
        PerformanceMonitor.trackAgentResponse(taskId, agent2, Duration.ofSeconds(1), success = true)
        
        assertEquals(0.666, PerformanceMonitor.getAgentSuccessRate(agent1), 0.01)
        assertEquals(1.0, PerformanceMonitor.getAgentSuccessRate(agent2), 0.01)
    }
    
    @Test
    fun `should return zero success rate for unknown task`() {
        val unknownTask = TaskId("unknown")
        assertEquals(0.0, PerformanceMonitor.getTaskSuccessRate(unknownTask))
    }
    
    @Test
    fun `should return zero success rate for unknown agent`() {
        val unknownAgent = AgentId("unknown")
        assertEquals(0.0, PerformanceMonitor.getAgentSuccessRate(unknownAgent))
    }
    
    @Test
    fun `should calculate average durations correctly`() {
        val task1 = TaskId("task-1")
        val task2 = TaskId("task-2")
        val agentId = AgentId("agent-1")
        
        PerformanceMonitor.trackTaskCompletion(task1, Duration.ofSeconds(2), success = true)
        PerformanceMonitor.trackTaskCompletion(task1, Duration.ofSeconds(4), success = true)
        PerformanceMonitor.trackTaskCompletion(task2, Duration.ofSeconds(6), success = true)
        
        PerformanceMonitor.trackAgentResponse(task1, agentId, Duration.ofSeconds(1), success = true)
        PerformanceMonitor.trackAgentResponse(task2, agentId, Duration.ofSeconds(3), success = true)
        
        // Task metrics include all operations: (2+4+1) for task1, (6+3) for task2 = avg 3.2s
        assertEquals(3200, PerformanceMonitor.getAvgTaskCompletionTime().toMillis())
        assertEquals(2000, PerformanceMonitor.getAvgAgentResponseTime().toMillis())
    }
    
    @Test
    fun `should identify slow operations as bottlenecks`() {
        val taskId = TaskId("task-1")
        val agentId = AgentId("slow-agent")
        
        // Create slow agent responses
        repeat(5) {
            PerformanceMonitor.trackAgentResponse(taskId, agentId, Duration.ofSeconds(10), success = true)
        }
        
        val bottlenecks = PerformanceMonitor.identifyBottlenecks(
            minOccurrences = 3,
            slowThreshold = Duration.ofSeconds(5)
        )
        
        assertTrue(bottlenecks.isNotEmpty())
        val agentBottleneck = bottlenecks.find { it.type == "agent" && it.identifier == agentId.value }
        assertNotNull(agentBottleneck)
        assertEquals(5, agentBottleneck!!.occurrences)
    }
    
    @Test
    fun `should identify high failure rate as bottleneck`() {
        val taskId = TaskId("task-1")
        val agentId = AgentId("failing-agent")
        
        // Create failing agent responses
        repeat(3) {
            PerformanceMonitor.trackAgentResponse(taskId, agentId, Duration.ofSeconds(1), success = false)
        }
        PerformanceMonitor.trackAgentResponse(taskId, agentId, Duration.ofSeconds(1), success = true)
        
        val bottlenecks = PerformanceMonitor.identifyBottlenecks(minOccurrences = 3)
        
        assertTrue(bottlenecks.isNotEmpty())
        val agentBottleneck = bottlenecks.find { it.type == "agent" && it.identifier == agentId.value }
        assertNotNull(agentBottleneck)
        assertEquals(0.75, agentBottleneck!!.failureRate, 0.01)
    }
    
    @Test
    fun `should not identify bottlenecks below threshold`() {
        val taskId = TaskId("task-1")
        val agentId = AgentId("good-agent")
        
        // Create fast, successful responses
        repeat(5) {
            PerformanceMonitor.trackAgentResponse(taskId, agentId, Duration.ofSeconds(1), success = true)
        }
        
        val bottlenecks = PerformanceMonitor.identifyBottlenecks(
            minOccurrences = 3,
            slowThreshold = Duration.ofSeconds(5)
        )
        
        val agentBottleneck = bottlenecks.find { it.type == "agent" && it.identifier == agentId.value }
        assertNull(agentBottleneck)
    }
    
    @Test
    fun `should require minimum occurrences for bottleneck detection`() {
        val taskId = TaskId("task-1")
        val agentId = AgentId("agent-1")
        
        // Only 2 occurrences (below threshold of 3)
        PerformanceMonitor.trackAgentResponse(taskId, agentId, Duration.ofSeconds(10), success = false)
        PerformanceMonitor.trackAgentResponse(taskId, agentId, Duration.ofSeconds(10), success = false)
        
        val bottlenecks = PerformanceMonitor.identifyBottlenecks(minOccurrences = 3)
        
        assertTrue(bottlenecks.isEmpty())
    }
    
    @Test
    fun `should generate comprehensive dashboard`() {
        val task1 = TaskId("task-1")
        val task2 = TaskId("task-2")
        val agent1 = AgentId("agent-1")
        
        PerformanceMonitor.trackTaskCompletion(task1, Duration.ofSeconds(3), success = true)
        PerformanceMonitor.trackTaskCompletion(task2, Duration.ofSeconds(5), success = false)
        PerformanceMonitor.trackAgentResponse(task1, agent1, Duration.ofSeconds(2), success = true)
        
        val dashboard = PerformanceMonitor.generateDashboard()
        
        // Task metrics: (3+2) for task1, (5) for task2 = avg 3.33s
        assertEquals(3333, dashboard.avgTaskCompletionTime.toMillis())
        assertEquals(2000, dashboard.avgAgentResponseTime.toMillis())
        // Success rate: task1 has 2 successes, task2 has 1 failure = 2/3 = 0.666
        assertEquals(0.666, dashboard.overallSuccessRate, 0.01)
        assertTrue(dashboard.taskSuccessRate.containsKey(task1))
        assertTrue(dashboard.taskSuccessRate.containsKey(task2))
        assertTrue(dashboard.agentSuccessRate.containsKey(agent1))
        assertNotNull(dashboard.period)
    }
    
    @Test
    fun `should handle empty metrics gracefully`() {
        val dashboard = PerformanceMonitor.generateDashboard()
        
        assertEquals(Duration.ZERO, dashboard.avgTaskCompletionTime)
        assertEquals(Duration.ZERO, dashboard.avgAgentResponseTime)
        assertEquals(0.0, dashboard.overallSuccessRate)
        assertTrue(dashboard.taskSuccessRate.isEmpty())
        assertTrue(dashboard.agentSuccessRate.isEmpty())
        assertTrue(dashboard.bottlenecks.isEmpty())
    }
    
    @Test
    fun `should record custom performance metrics`() {
        val taskId = TaskId("task-1")
        val agentId = AgentId("agent-1")
        
        // Record multiple times to meet minOccurrences and slowThreshold
        repeat(3) {
            val record = PerformanceRecord(
                taskId = taskId,
                agentId = agentId,
                operation = "custom_operation",
                duration = Duration.ofSeconds(10),
                success = true,
                timestamp = Instant.now()
            )
            PerformanceMonitor.record(record)
        }
        
        val bottlenecks = PerformanceMonitor.identifyBottlenecks(minOccurrences = 3, slowThreshold = Duration.ofSeconds(5))
        assertTrue(bottlenecks.any { it.identifier == "custom_operation" })
    }
    
    @Test
    fun `should be thread-safe`() {
        val taskId = TaskId("task-1")
        val agentId = AgentId("agent-1")
        val threads = 10
        val recordsPerThread = 100
        
        val threadList = (1..threads).map {
            thread {
                repeat(recordsPerThread) {
                    PerformanceMonitor.trackTaskCompletion(taskId, Duration.ofSeconds(1), success = true)
                    PerformanceMonitor.trackAgentResponse(taskId, agentId, Duration.ofMillis(500), success = true)
                }
            }
        }
        
        threadList.forEach { it.join() }
        
        val dashboard = PerformanceMonitor.generateDashboard()
        assertEquals(1.0, dashboard.overallSuccessRate)
        assertTrue(dashboard.taskSuccessRate[taskId] == 1.0)
        assertTrue(dashboard.agentSuccessRate[agentId] == 1.0)
    }
    
    @Test
    fun `should reset all metrics`() {
        val taskId = TaskId("task-1")
        val agentId = AgentId("agent-1")
        
        PerformanceMonitor.trackTaskCompletion(taskId, Duration.ofSeconds(5), success = true)
        PerformanceMonitor.trackAgentResponse(taskId, agentId, Duration.ofSeconds(2), success = true)
        
        PerformanceMonitor.reset()
        
        val dashboard = PerformanceMonitor.generateDashboard()
        assertEquals(Duration.ZERO, dashboard.avgTaskCompletionTime)
        assertEquals(Duration.ZERO, dashboard.avgAgentResponseTime)
        assertEquals(0.0, dashboard.overallSuccessRate)
    }
    
    @Test
    fun `should sort bottlenecks by failure rate`() {
        val taskId = TaskId("task-1")
        val agent1 = AgentId("agent-1")
        val agent2 = AgentId("agent-2")
        
        // Agent 1: 50% failure rate
        repeat(2) { PerformanceMonitor.trackAgentResponse(taskId, agent1, Duration.ofSeconds(10), success = true) }
        repeat(2) { PerformanceMonitor.trackAgentResponse(taskId, agent1, Duration.ofSeconds(10), success = false) }
        
        // Agent 2: 75% failure rate
        repeat(1) { PerformanceMonitor.trackAgentResponse(taskId, agent2, Duration.ofSeconds(10), success = true) }
        repeat(3) { PerformanceMonitor.trackAgentResponse(taskId, agent2, Duration.ofSeconds(10), success = false) }
        
        val bottlenecks = PerformanceMonitor.identifyBottlenecks(minOccurrences = 3)
        
        assertTrue(bottlenecks.size >= 2)
        assertTrue(bottlenecks[0].failureRate >= bottlenecks[1].failureRate)
    }
}
