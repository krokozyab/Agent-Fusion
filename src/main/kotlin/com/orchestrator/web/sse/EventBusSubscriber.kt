package com.orchestrator.web.sse

import com.orchestrator.core.Event
import com.orchestrator.core.EventBus
import com.orchestrator.core.SystemEvent
import com.orchestrator.domain.Task
import com.orchestrator.domain.TaskId
import com.orchestrator.domain.TaskStatus
import com.orchestrator.modules.metrics.Alert
import com.orchestrator.modules.metrics.AlertEvent
import com.orchestrator.modules.metrics.MetricsSnapshot
import com.orchestrator.utils.Logger
import com.orchestrator.web.routes.SSEStreamKind
import com.orchestrator.web.routes.ensureSseManager
import com.orchestrator.workflows.WorkflowEvent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.util.AttributeKey
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Event emitted when a task transitions between statuses.
 */
internal data class TaskStatusChangedEvent(
    val taskId: TaskId,
    val previousStatus: TaskStatus,
    val currentStatus: TaskStatus,
    override val timestamp: Instant = Instant.now()
) : Event

/**
 * Event emitted when a new task is persisted.
 */
internal data class TaskCreatedEvent(
    val taskId: TaskId,
    override val timestamp: Instant = Instant.now()
) : Event

/**
 * Event emitted during indexing operations for progress updates.
 */
internal data class IndexProgressEvent(
    val operationId: String,
    val percentage: Int,
    val processed: Int? = null,
    val total: Int? = null,
    val title: String? = null,
    val message: String? = null,
    override val timestamp: Instant = Instant.now()
) : Event

/**
 * Event emitted when metrics are refreshed.
 */
internal data class MetricsUpdatedEvent(
    val snapshot: MetricsSnapshot,
    override val timestamp: Instant = snapshot.timestamp
) : Event

/**
 * Event emitted when an alert is triggered.
 */
internal data class AlertTriggeredEvent(
    val alert: Alert,
    override val timestamp: Instant = alert.timestamp
) : Event

/**
 * Bridges EventBus domain events to SSE subscribers.
 */
internal class EventBusSubscriber(
    private val eventBus: EventBus,
    private val fragmentGenerator: FragmentGenerator,
    private val taskLoader: suspend (TaskId) -> Task?,
    private val managerProvider: (SSEStreamKind) -> SSEManager,
    private val logger: Logger = Logger.logger("com.orchestrator.web.sse.EventBusSubscriber")
) {

    private val running = AtomicBoolean(false)
    private val jobs = mutableListOf<Job>()

    fun start() {
        if (!running.compareAndSet(false, true)) return

        jobs += eventBus.on<TaskStatusChangedEvent> { event ->
            handleTaskEvent(event.taskId, event.timestamp, "taskUpdated")
        }

        jobs += eventBus.on<TaskCreatedEvent> { event ->
            handleTaskEvent(event.taskId, event.timestamp, "taskCreated")
        }

        jobs += eventBus.on<SystemEvent.TaskCreated> { event ->
            handleTaskEvent(event.taskId, event.timestamp, "taskCreated")
        }

        jobs += eventBus.on<SystemEvent.TaskUpdated> { event ->
            handleTaskEvent(event.taskId, event.timestamp, "taskUpdated")
        }

        jobs += eventBus.on<WorkflowEvent.Completed> { event ->
            handleTaskEvent(event.taskId, event.timestamp, "taskUpdated")
        }

        jobs += eventBus.on<WorkflowEvent.Failed> { event ->
            handleTaskEvent(event.taskId, event.timestamp, "taskUpdated")
        }

        jobs += eventBus.on<IndexProgressEvent> { event ->
            handleIndexProgress(event)
        }

        jobs += eventBus.on<MetricsUpdatedEvent> { event ->
            handleMetrics(event.snapshot)
        }

        jobs += eventBus.on<AlertTriggeredEvent> { event ->
            handleAlert(event.alert)
        }

        jobs += eventBus.on<AlertEvent> { event ->
            handleAlert(event.alert)
        }

        logger.info("Web SSE EventBus subscriber started")
    }

    suspend fun stop() {
        if (!running.getAndSet(false)) return
        jobs.forEach { job ->
            job.cancel()
            runCatching { job.cancelAndJoin() }
        }
        jobs.clear()
        logger.info("Web SSE EventBus subscriber stopped")
    }

    private suspend fun handleTaskEvent(taskId: TaskId, timestamp: Instant, eventName: String) {
        val task = runCatching { taskLoader(taskId) }
            .onFailure { throwable ->
                logger.warn("Failed to load task ${taskId.value}: ${throwable.message}")
            }
            .getOrNull() ?: return

        val fragment = runCatching { fragmentGenerator.taskRow(task) }
            .onFailure { throwable ->
                logger.warn("Failed to render task ${task.id.value}: ${throwable.message}")
            }
            .getOrNull() ?: return

        val payload = jsonPayload(
            event = eventName,
            attributes = mapOf(
                "taskId" to task.id.value,
                "status" to task.status.name,
                "routing" to task.routing.name,
                "timestamp" to timestamp.toString()
            )
        )

        val event = SSEEvent.message(
            data = payload,
            htmlFragment = fragment,
            timestamp = timestamp
        )

        broadcast(SSEStreamKind.TASKS, event)
    }

    private suspend fun handleIndexProgress(event: IndexProgressEvent) {
        val fragment = runCatching { fragmentGenerator.indexProgress(event) }
            .onFailure { throwable ->
                logger.warn(
                    "Failed to render index progress ${event.operationId}: ${throwable.message}"
                )
            }
            .getOrNull() ?: return

        val payload = jsonPayload(
            event = "indexProgress",
            attributes = mapOf(
                "operationId" to event.operationId,
                "percentage" to event.percentage,
                "processed" to event.processed,
                "total" to event.total,
                "timestamp" to event.timestamp.toString()
            )
        )

        val sseEvent = SSEEvent.message(
            data = payload,
            htmlFragment = fragment,
            timestamp = event.timestamp
        )

        broadcast(SSEStreamKind.INDEX, sseEvent)
    }

    private suspend fun handleMetrics(snapshot: MetricsSnapshot) {
        val fragment = runCatching { fragmentGenerator.metricsSnapshot(snapshot) }
            .onFailure { throwable ->
                logger.warn("Failed to render metrics snapshot: ${throwable.message}")
            }
            .getOrNull() ?: return

        val payload = jsonPayload(
            event = "metricsUpdated",
            attributes = mapOf(
                "timestamp" to snapshot.timestamp.toString(),
                "totalTokens" to snapshot.tokenUsage.totalTokens,
                "savings" to snapshot.tokenUsage.savings,
                "alerts" to snapshot.alerts.unacknowledged
            )
        )

        val sseEvent = SSEEvent.message(
            data = payload,
            htmlFragment = fragment,
            timestamp = snapshot.timestamp
        )

        broadcast(SSEStreamKind.METRICS, sseEvent)
    }

    private suspend fun handleAlert(alert: Alert) {
        val fragment = runCatching { fragmentGenerator.alert(alert) }
            .onFailure { throwable ->
                logger.warn("Failed to render alert ${alert.id}: ${throwable.message}")
            }
            .getOrNull() ?: return

        val payload = jsonPayload(
            event = "alertTriggered",
            attributes = mapOf(
                "alertId" to alert.id,
                "severity" to alert.severity.name,
                "type" to alert.type.name,
                "timestamp" to alert.timestamp.toString()
            )
        )

        val sseEvent = SSEEvent.message(
            data = payload,
            htmlFragment = fragment,
            timestamp = alert.timestamp
        )

        broadcast(SSEStreamKind.METRICS, sseEvent)
    }

    private suspend fun broadcast(kind: SSEStreamKind, event: SSEEvent) {
        runCatching { managerProvider(kind).broadcast(event) }
            .onFailure { throwable ->
                logger.warn("Failed to broadcast ${event.data} to ${kind.name}: ${throwable.message}")
            }

        if (kind != SSEStreamKind.ALL) {
            runCatching { managerProvider(SSEStreamKind.ALL).broadcast(event) }
                .onFailure { throwable ->
                    logger.warn(
                        "Failed to broadcast ${event.data} to aggregate stream: ${throwable.message}"
                    )
                }
        }
    }
}

private fun jsonPayload(event: String, attributes: Map<String, Any?> = emptyMap()): String =
    buildString {
        append("{\"event\":\"")
        append(event.escapeJson())
        append("\"")
        attributes.forEach { (key, value) ->
            append(",\"")
            append(key.escapeJson())
            append("\":")
            appendJsonValue(value)
        }
        append("}")
    }

private fun String.escapeJson(): String = buildString(length) {
    for (ch in this@escapeJson) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (ch.code in 0x00..0x1F) {
                    append("\\u")
                    append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
            }
        }
    }
}

private fun StringBuilder.appendJsonValue(value: Any?) {
    when (value) {
        null -> append("null")
        is Number, is Boolean -> append(value.toString())
        else -> append("\"${value.toString().escapeJson()}\"")
    }
}

private val SubscriberAttributeKey =
    AttributeKey<EventBusSubscriber>("web-sse-eventbus-subscriber")

/**
 * Install and start the EventBusSubscriber within the Ktor application.
 */
internal fun Application.installEventBusSubscriber(
    eventBus: EventBus = EventBus.global,
    fragmentGenerator: FragmentGenerator = FragmentGenerator(),
    taskLoader: suspend (TaskId) -> Task? = { id ->
        withContext(Dispatchers.IO) { com.orchestrator.storage.repositories.TaskRepository.findById(id) }
    }
) {
    if (attributes.getOrNull(SubscriberAttributeKey) != null) return

    val subscriber = EventBusSubscriber(
        eventBus = eventBus,
        fragmentGenerator = fragmentGenerator,
        taskLoader = taskLoader,
        managerProvider = { kind -> ensureSseManager(kind) }
    )

    attributes.put(SubscriberAttributeKey, subscriber)

    monitor.subscribe(ApplicationStarted) {
        subscriber.start()
    }

    monitor.subscribe(ApplicationStopping) {
        runBlocking { subscriber.stop() }
    }
}
