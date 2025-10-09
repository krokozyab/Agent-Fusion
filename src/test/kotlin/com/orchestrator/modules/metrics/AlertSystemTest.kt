package com.orchestrator.modules.metrics

import com.orchestrator.core.EventBus
import com.orchestrator.domain.AgentId
import com.orchestrator.domain.TaskId
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.concurrent.thread

class AlertSystemTest {
    
    @AfterEach
    fun cleanup() {
        AlertSystem.reset()
    }
    
    @Test
    fun `should set and get threshold`() {
        AlertSystem.setThreshold(AlertType.TOKEN_BUDGET_EXCEEDED, AlertSeverity.WARNING, 5000.0)
        
        val threshold = AlertSystem.getThreshold(AlertType.TOKEN_BUDGET_EXCEEDED)
        assertNotNull(threshold)
        assertEquals(AlertType.TOKEN_BUDGET_EXCEEDED, threshold!!.type)
        assertEquals(AlertSeverity.WARNING, threshold.severity)
        assertEquals(5000.0, threshold.threshold)
        assertTrue(threshold.enabled)
    }
    
    @Test
    fun `should trigger alert when threshold exceeded`() {
        AlertSystem.setThreshold(AlertType.TOKEN_BUDGET_EXCEEDED, AlertSeverity.WARNING, 1000.0)
        
        val alert = AlertSystem.check(
            type = AlertType.TOKEN_BUDGET_EXCEEDED,
            value = 1500.0,
            message = "Token budget exceeded"
        )
        
        assertNotNull(alert)
        assertEquals(AlertType.TOKEN_BUDGET_EXCEEDED, alert!!.type)
        assertEquals(AlertSeverity.WARNING, alert.severity)
        assertEquals(1500.0, alert.value)
        assertEquals(1000.0, alert.threshold)
        assertFalse(alert.acknowledged)
    }
    
    @Test
    fun `should not trigger alert when threshold not exceeded`() {
        AlertSystem.setThreshold(AlertType.TOKEN_BUDGET_EXCEEDED, AlertSeverity.WARNING, 1000.0)
        
        val alert = AlertSystem.check(
            type = AlertType.TOKEN_BUDGET_EXCEEDED,
            value = 500.0,
            message = "Token budget OK"
        )
        
        assertNull(alert)
    }
    
    @Test
    fun `should not trigger alert when disabled`() {
        AlertSystem.setThreshold(AlertType.TOKEN_BUDGET_EXCEEDED, AlertSeverity.WARNING, 1000.0, enabled = false)
        
        val alert = AlertSystem.check(
            type = AlertType.TOKEN_BUDGET_EXCEEDED,
            value = 1500.0,
            message = "Token budget exceeded"
        )
        
        assertNull(alert)
    }
    
    @Test
    fun `should trigger alert manually`() {
        val alert = AlertSystem.trigger(
            type = AlertType.AGENT_OFFLINE,
            severity = AlertSeverity.ERROR,
            message = "Agent is offline",
            agentId = AgentId("agent-1")
        )
        
        assertNotNull(alert)
        assertEquals(AlertType.AGENT_OFFLINE, alert.type)
        assertEquals(AlertSeverity.ERROR, alert.severity)
        assertEquals(AgentId("agent-1"), alert.agentId)
    }
    
    @Test
    fun `should acknowledge alert`() {
        val alert = AlertSystem.trigger(
            type = AlertType.HIGH_FAILURE_RATE,
            severity = AlertSeverity.ERROR,
            message = "High failure rate detected"
        )
        
        val acknowledged = AlertSystem.acknowledge(alert.id, "admin")
        
        assertNotNull(acknowledged)
        assertTrue(acknowledged!!.acknowledged)
        assertEquals("admin", acknowledged.acknowledgedBy)
        assertNotNull(acknowledged.acknowledgedAt)
    }
    
    @Test
    fun `should return null when acknowledging non-existent alert`() {
        val acknowledged = AlertSystem.acknowledge("non-existent", "admin")
        assertNull(acknowledged)
    }
    
    @Test
    fun `should get alert by ID`() {
        val alert = AlertSystem.trigger(
            type = AlertType.TASK_TIMEOUT,
            severity = AlertSeverity.WARNING,
            message = "Task timeout"
        )
        
        val retrieved = AlertSystem.getAlert(alert.id)
        assertNotNull(retrieved)
        assertEquals(alert.id, retrieved!!.id)
    }
    
    @Test
    fun `should get all alerts`() {
        AlertSystem.trigger(AlertType.TOKEN_BUDGET_EXCEEDED, AlertSeverity.WARNING, "Alert 1")
        AlertSystem.trigger(AlertType.HIGH_FAILURE_RATE, AlertSeverity.ERROR, "Alert 2")
        AlertSystem.trigger(AlertType.AGENT_OFFLINE, AlertSeverity.CRITICAL, "Alert 3")
        
        val allAlerts = AlertSystem.getAllAlerts()
        assertEquals(3, allAlerts.size)
    }
    
    @Test
    fun `should get unacknowledged alerts`() {
        val alert1 = AlertSystem.trigger(AlertType.TOKEN_BUDGET_EXCEEDED, AlertSeverity.WARNING, "Alert 1")
        AlertSystem.trigger(AlertType.HIGH_FAILURE_RATE, AlertSeverity.ERROR, "Alert 2")
        AlertSystem.acknowledge(alert1.id, "admin")
        
        val unacknowledged = AlertSystem.getUnacknowledgedAlerts()
        assertEquals(1, unacknowledged.size)
        assertEquals("Alert 2", unacknowledged[0].message)
    }
    
    @Test
    fun `should get alerts by type`() {
        AlertSystem.trigger(AlertType.TOKEN_BUDGET_EXCEEDED, AlertSeverity.WARNING, "Alert 1")
        AlertSystem.trigger(AlertType.TOKEN_BUDGET_EXCEEDED, AlertSeverity.WARNING, "Alert 2")
        AlertSystem.trigger(AlertType.HIGH_FAILURE_RATE, AlertSeverity.ERROR, "Alert 3")
        
        val tokenAlerts = AlertSystem.getAlertsByType(AlertType.TOKEN_BUDGET_EXCEEDED)
        assertEquals(2, tokenAlerts.size)
    }
    
    @Test
    fun `should get alerts by severity`() {
        AlertSystem.trigger(AlertType.TOKEN_BUDGET_EXCEEDED, AlertSeverity.WARNING, "Alert 1")
        AlertSystem.trigger(AlertType.HIGH_FAILURE_RATE, AlertSeverity.ERROR, "Alert 2")
        AlertSystem.trigger(AlertType.AGENT_OFFLINE, AlertSeverity.ERROR, "Alert 3")
        
        val errorAlerts = AlertSystem.getAlertsBySeverity(AlertSeverity.ERROR)
        assertEquals(2, errorAlerts.size)
    }
    
    @Test
    fun `should get alerts for task`() {
        val taskId = TaskId("task-1")
        AlertSystem.trigger(AlertType.TASK_TIMEOUT, AlertSeverity.WARNING, "Alert 1", taskId = taskId)
        AlertSystem.trigger(AlertType.HIGH_FAILURE_RATE, AlertSeverity.ERROR, "Alert 2", taskId = taskId)
        AlertSystem.trigger(AlertType.AGENT_OFFLINE, AlertSeverity.ERROR, "Alert 3")
        
        val taskAlerts = AlertSystem.getAlertsForTask(taskId)
        assertEquals(2, taskAlerts.size)
    }
    
    @Test
    fun `should get alerts for agent`() {
        val agentId = AgentId("agent-1")
        AlertSystem.trigger(AlertType.AGENT_OFFLINE, AlertSeverity.ERROR, "Alert 1", agentId = agentId)
        AlertSystem.trigger(AlertType.PERFORMANCE_DEGRADATION, AlertSeverity.WARNING, "Alert 2", agentId = agentId)
        AlertSystem.trigger(AlertType.TOKEN_BUDGET_EXCEEDED, AlertSeverity.WARNING, "Alert 3")
        
        val agentAlerts = AlertSystem.getAlertsForAgent(agentId)
        assertEquals(2, agentAlerts.size)
    }
    
    @Test
    fun `should get alerts in time range`() {
        val now = Instant.now()
        val past = now.minus(1, ChronoUnit.HOURS)
        val future = now.plus(1, ChronoUnit.HOURS)
        
        AlertSystem.trigger(AlertType.TOKEN_BUDGET_EXCEEDED, AlertSeverity.WARNING, "Alert 1")
        Thread.sleep(10)
        AlertSystem.trigger(AlertType.HIGH_FAILURE_RATE, AlertSeverity.ERROR, "Alert 2")
        
        val alerts = AlertSystem.getAlertsInRange(past, future)
        assertEquals(2, alerts.size)
    }
    
    @Test
    fun `should clear old acknowledged alerts`() {
        val alert1 = AlertSystem.trigger(AlertType.TOKEN_BUDGET_EXCEEDED, AlertSeverity.WARNING, "Alert 1")
        val alert2 = AlertSystem.trigger(AlertType.HIGH_FAILURE_RATE, AlertSeverity.ERROR, "Alert 2")
        
        AlertSystem.acknowledge(alert1.id, "admin")
        
        val cutoff = Instant.now().plus(1, ChronoUnit.SECONDS)
        Thread.sleep(100)
        
        AlertSystem.clearOldAlerts(cutoff)
        
        val remaining = AlertSystem.getAllAlerts()
        assertEquals(1, remaining.size)
        assertEquals(alert2.id, remaining[0].id)
    }
    
    @Test
    fun `should deliver alerts via event bus`() = runBlocking {
        val eventBus = EventBus()
        AlertSystem.setEventBus(eventBus)
        
        val receivedAlerts = mutableListOf<Alert>()
        val job = eventBus.on<AlertEvent> { event ->
            receivedAlerts.add(event.alert)
        }
        
        // Give subscription time to register
        delay(50)
        
        AlertSystem.trigger(AlertType.TOKEN_BUDGET_EXCEEDED, AlertSeverity.WARNING, "Test alert")
        
        // Wait for event delivery
        delay(200)
        
        assertTrue(receivedAlerts.isNotEmpty())
        assertEquals(AlertType.TOKEN_BUDGET_EXCEEDED, receivedAlerts[0].type)
        
        job.cancel()
        eventBus.shutdown()
    }
    
    @Test
    fun `should sort alerts by timestamp descending`() {
        AlertSystem.trigger(AlertType.TOKEN_BUDGET_EXCEEDED, AlertSeverity.WARNING, "Alert 1")
        Thread.sleep(10)
        AlertSystem.trigger(AlertType.HIGH_FAILURE_RATE, AlertSeverity.ERROR, "Alert 2")
        Thread.sleep(10)
        AlertSystem.trigger(AlertType.AGENT_OFFLINE, AlertSeverity.CRITICAL, "Alert 3")
        
        val alerts = AlertSystem.getAllAlerts()
        assertEquals("Alert 3", alerts[0].message)
        assertEquals("Alert 2", alerts[1].message)
        assertEquals("Alert 1", alerts[2].message)
    }
    
    @Test
    fun `should be thread-safe`() {
        val threads = 10
        val alertsPerThread = 100
        
        val threadList = (1..threads).map {
            thread {
                repeat(alertsPerThread) {
                    AlertSystem.trigger(
                        type = AlertType.TOKEN_BUDGET_EXCEEDED,
                        severity = AlertSeverity.WARNING,
                        message = "Thread alert"
                    )
                }
            }
        }
        
        threadList.forEach { it.join() }
        
        val allAlerts = AlertSystem.getAllAlerts()
        assertEquals(1000, allAlerts.size)
    }
    
    @Test
    fun `should handle custom alert type`() {
        val alert = AlertSystem.trigger(
            type = AlertType.CUSTOM,
            severity = AlertSeverity.INFO,
            message = "Custom alert message"
        )
        
        assertEquals(AlertType.CUSTOM, alert.type)
        assertEquals(AlertSeverity.INFO, alert.severity)
    }
    
    @Test
    fun `should include task and agent context in alert`() {
        val taskId = TaskId("task-1")
        val agentId = AgentId("agent-1")
        
        val alert = AlertSystem.trigger(
            type = AlertType.PERFORMANCE_DEGRADATION,
            severity = AlertSeverity.WARNING,
            message = "Performance issue",
            taskId = taskId,
            agentId = agentId
        )
        
        assertEquals(taskId, alert.taskId)
        assertEquals(agentId, alert.agentId)
    }
    
    @Test
    fun `should reset all alerts and thresholds`() {
        AlertSystem.setThreshold(AlertType.TOKEN_BUDGET_EXCEEDED, AlertSeverity.WARNING, 5000.0)
        AlertSystem.trigger(AlertType.HIGH_FAILURE_RATE, AlertSeverity.ERROR, "Alert")
        
        AlertSystem.reset()
        
        assertNull(AlertSystem.getThreshold(AlertType.TOKEN_BUDGET_EXCEEDED))
        assertTrue(AlertSystem.getAllAlerts().isEmpty())
    }
}
