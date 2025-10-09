package com.orchestrator.modules.metrics

import com.orchestrator.core.Event
import com.orchestrator.core.EventBus
import com.orchestrator.domain.AgentId
import com.orchestrator.domain.TaskId
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Alert severity levels.
 */
enum class AlertSeverity {
    INFO,
    WARNING,
    ERROR,
    CRITICAL
}

/**
 * Alert types.
 */
enum class AlertType {
    TOKEN_BUDGET_EXCEEDED,
    PERFORMANCE_DEGRADATION,
    HIGH_FAILURE_RATE,
    AGENT_OFFLINE,
    TASK_TIMEOUT,
    CONFIDENCE_CALIBRATION_ERROR,
    CUSTOM
}

/**
 * Alert threshold configuration.
 */
data class AlertThreshold(
    val type: AlertType,
    val severity: AlertSeverity,
    val threshold: Double,
    val enabled: Boolean = true
)

/**
 * Alert instance.
 */
data class Alert(
    val id: String,
    val type: AlertType,
    val severity: AlertSeverity,
    val message: String,
    val value: Double,
    val threshold: Double,
    val taskId: TaskId? = null,
    val agentId: AgentId? = null,
    val timestamp: Instant = Instant.now(),
    val acknowledged: Boolean = false,
    val acknowledgedAt: Instant? = null,
    val acknowledgedBy: String? = null
)

/**
 * Alert event for EventBus delivery.
 */
data class AlertEvent(
    val alert: Alert,
    override val timestamp: Instant = Instant.now()
) : Event

/**
 * Minimal alert system.
 * 
 * Features:
 * - Define alert types
 * - Configurable thresholds
 * - Alert delivery via events
 * - Alert history
 * - Alert acknowledgment
 */
object AlertSystem {
    
    private val thresholds = ConcurrentHashMap<AlertType, AlertThreshold>()
    private val alerts = ConcurrentHashMap<String, Alert>()
    private val alertCounter = AtomicLong(0)
    private var eventBus: EventBus = EventBus.global
    
    init {
        // Default thresholds
        setThreshold(AlertType.TOKEN_BUDGET_EXCEEDED, AlertSeverity.WARNING, 10000.0)
        setThreshold(AlertType.PERFORMANCE_DEGRADATION, AlertSeverity.WARNING, 0.5)
        setThreshold(AlertType.HIGH_FAILURE_RATE, AlertSeverity.ERROR, 0.3)
        setThreshold(AlertType.CONFIDENCE_CALIBRATION_ERROR, AlertSeverity.WARNING, 0.2)
    }
    
    /**
     * Set event bus for alert delivery.
     */
    fun setEventBus(bus: EventBus) {
        eventBus = bus
    }
    
    /**
     * Configure alert threshold.
     */
    fun setThreshold(type: AlertType, severity: AlertSeverity, threshold: Double, enabled: Boolean = true) {
        thresholds[type] = AlertThreshold(type, severity, threshold, enabled)
    }
    
    /**
     * Get threshold configuration.
     */
    fun getThreshold(type: AlertType): AlertThreshold? = thresholds[type]
    
    /**
     * Check value against threshold and trigger alert if exceeded.
     */
    fun check(
        type: AlertType,
        value: Double,
        message: String,
        taskId: TaskId? = null,
        agentId: AgentId? = null
    ): Alert? {
        val config = thresholds[type] ?: return null
        
        if (!config.enabled) return null
        if (value < config.threshold) return null
        
        return trigger(
            type = type,
            severity = config.severity,
            message = message,
            value = value,
            threshold = config.threshold,
            taskId = taskId,
            agentId = agentId
        )
    }
    
    /**
     * Trigger an alert.
     */
    fun trigger(
        type: AlertType,
        severity: AlertSeverity,
        message: String,
        value: Double = 0.0,
        threshold: Double = 0.0,
        taskId: TaskId? = null,
        agentId: AgentId? = null
    ): Alert {
        val id = "alert-${alertCounter.incrementAndGet()}"
        
        val alert = Alert(
            id = id,
            type = type,
            severity = severity,
            message = message,
            value = value,
            threshold = threshold,
            taskId = taskId,
            agentId = agentId
        )
        
        alerts[id] = alert
        
        // Deliver via event bus
        eventBus.publish(AlertEvent(alert))
        
        return alert
    }
    
    /**
     * Acknowledge an alert.
     */
    fun acknowledge(alertId: String, acknowledgedBy: String): Alert? {
        val alert = alerts[alertId] ?: return null
        
        val acknowledged = alert.copy(
            acknowledged = true,
            acknowledgedAt = Instant.now(),
            acknowledgedBy = acknowledgedBy
        )
        
        alerts[alertId] = acknowledged
        return acknowledged
    }
    
    /**
     * Get alert by ID.
     */
    fun getAlert(alertId: String): Alert? = alerts[alertId]
    
    /**
     * Get all alerts.
     */
    fun getAllAlerts(): List<Alert> = alerts.values.toList().sortedByDescending { it.timestamp }
    
    /**
     * Get unacknowledged alerts.
     */
    fun getUnacknowledgedAlerts(): List<Alert> = 
        alerts.values.filter { !it.acknowledged }.sortedByDescending { it.timestamp }
    
    /**
     * Get alerts by type.
     */
    fun getAlertsByType(type: AlertType): List<Alert> = 
        alerts.values.filter { it.type == type }.sortedByDescending { it.timestamp }
    
    /**
     * Get alerts by severity.
     */
    fun getAlertsBySeverity(severity: AlertSeverity): List<Alert> = 
        alerts.values.filter { it.severity == severity }.sortedByDescending { it.timestamp }
    
    /**
     * Get alerts for task.
     */
    fun getAlertsForTask(taskId: TaskId): List<Alert> = 
        alerts.values.filter { it.taskId == taskId }.sortedByDescending { it.timestamp }
    
    /**
     * Get alerts for agent.
     */
    fun getAlertsForAgent(agentId: AgentId): List<Alert> = 
        alerts.values.filter { it.agentId == agentId }.sortedByDescending { it.timestamp }
    
    /**
     * Get alerts in time range.
     */
    fun getAlertsInRange(from: Instant, to: Instant): List<Alert> = 
        alerts.values.filter { it.timestamp >= from && it.timestamp <= to }.sortedByDescending { it.timestamp }
    
    /**
     * Clear acknowledged alerts older than specified time.
     */
    fun clearOldAlerts(olderThan: Instant) {
        val toRemove = alerts.values.filter { it.acknowledged && it.timestamp < olderThan }
        toRemove.forEach { alerts.remove(it.id) }
    }
    
    /**
     * Reset all alerts and thresholds (for testing).
     */
    fun reset() {
        alerts.clear()
        thresholds.clear()
        alertCounter.set(0)
    }
}
