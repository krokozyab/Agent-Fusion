package com.orchestrator.web.sse

import com.orchestrator.core.EventBus
import com.orchestrator.domain.AgentId
import com.orchestrator.domain.RoutingStrategy
import com.orchestrator.domain.Task
import com.orchestrator.domain.TaskId
import com.orchestrator.domain.TaskStatus
import com.orchestrator.domain.TaskType
import com.orchestrator.modules.context.ContextModule
import com.orchestrator.modules.metrics.Alert
import com.orchestrator.modules.metrics.AlertSeverity
import com.orchestrator.modules.metrics.AlertSummary
import com.orchestrator.modules.metrics.AlertType
import com.orchestrator.modules.metrics.Bottleneck
import com.orchestrator.modules.metrics.ConfidenceCalibration
import com.orchestrator.modules.metrics.DecisionReport
import com.orchestrator.modules.metrics.MetricsSnapshot
import com.orchestrator.modules.metrics.PerformanceDashboard
import com.orchestrator.modules.metrics.StrategyMetrics
import com.orchestrator.modules.metrics.TokenReport
import com.orchestrator.web.routes.SSEStreamKind
import com.orchestrator.web.services.FilesystemIndexSnapshot
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class EventBusSubscriberTest {

    @Test
    fun `task events broadcast to tasks and all streams`() = runTest {
        val scopes = mutableListOf<CoroutineScope>()
        val baseInstant = Instant.parse("2025-01-02T10:15:30Z")
        val eventBusScope = newScope(scopes)
        val eventBus = EventBus(scope = eventBusScope)
        val managers = createManagers(scopes)
        val generator = FragmentGenerator(
            clock = Clock.fixed(baseInstant, ZoneOffset.UTC),
            locale = Locale.US
        )

        val task = Task(
            id = TaskId("task-001"),
            title = "Implement feature",
            description = "Ensure SSE task row updates",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.IN_PROGRESS,
            routing = RoutingStrategy.CONSENSUS,
            assigneeIds = setOf(AgentId("agent-1")),
            complexity = 6,
            risk = 4,
            createdAt = baseInstant.minusSeconds(3_600),
            updatedAt = baseInstant
        )

        val subscriber = EventBusSubscriber(
            eventBus = eventBus,
            fragmentGenerator = generator,
            taskLoader = { id -> if (id == task.id) task else null },
            managerProvider = managers::getValue
        )

        val taskEvents = Channel<SSEEvent>(capacity = Channel.UNLIMITED)
        val allEvents = Channel<SSEEvent>(capacity = Channel.UNLIMITED)

        try {
            subscriber.start()

            registerCollector(managers.getValue(SSEStreamKind.TASKS), "tasks-listener", taskEvents)
            registerCollector(managers.getValue(SSEStreamKind.ALL), "all-listener", allEvents)

            eventBus.publish(
                TaskStatusChangedEvent(
                    taskId = task.id,
                    previousStatus = TaskStatus.PENDING,
                    currentStatus = task.status,
                    timestamp = baseInstant
                )
            )

            runCurrent()

            val taskEvent = withTimeout(1_000) { taskEvents.receive() }
            assertEquals(SSEEventType.MESSAGE, taskEvent.type)
            assertTrue(taskEvent.data.contains("\"event\":\"taskUpdated\""))
            assertNotNull(taskEvent.htmlFragment)
            assertTrue(taskEvent.htmlFragment!!.contains("task-row__title"))

            val aggregateEvent = withTimeout(1_000) { allEvents.receive() }
            assertEquals(taskEvent.data, aggregateEvent.data)
        } finally {
            try {
                subscriber.stop()
            } catch (_: Throwable) {}
            eventBus.shutdown()
            taskEvents.close()
            allEvents.close()
            managers.values.forEach { manager ->
                try {
                    manager.shutdown()
                } catch (_: Throwable) {}
            }
            scopes.forEach { scope -> scope.cancel() }
        }
    }

    @Test
    fun `index metrics and alert events reach respective streams`() = runTest {
        val scopes = mutableListOf<CoroutineScope>()
        val baseInstant = Instant.parse("2025-01-03T12:00:00Z")
        val eventBusScope = newScope(scopes)
        val eventBus = EventBus(scope = eventBusScope)
        val managers = createManagers(scopes)
        val generator = FragmentGenerator(
            clock = Clock.fixed(baseInstant, ZoneOffset.UTC),
            locale = Locale.US
        )

        val subscriber = EventBusSubscriber(
            eventBus = eventBus,
            fragmentGenerator = generator,
            taskLoader = { null },
            managerProvider = managers::getValue
        )

        val indexEvents = Channel<SSEEvent>(capacity = Channel.UNLIMITED)
        val metricsEvents = Channel<SSEEvent>(capacity = Channel.UNLIMITED)
        val allEvents = Channel<SSEEvent>(capacity = Channel.UNLIMITED)

        try {
            subscriber.start()

            registerCollector(managers.getValue(SSEStreamKind.INDEX), "index-listener", indexEvents)
            registerCollector(managers.getValue(SSEStreamKind.METRICS), "metrics-listener", metricsEvents)
            registerCollector(managers.getValue(SSEStreamKind.ALL), "aggregate-listener", allEvents)

            val indexEvent = IndexProgressEvent(
                operationId = "op-123",
                percentage = 42,
                processed = 210,
                total = 500,
                title = "Context Refresh",
                message = "Indexing Kotlin files",
                timestamp = baseInstant
            )

            eventBus.publish(indexEvent)
            runCurrent()

            val indexPayload = withTimeout(1_000) { indexEvents.receive() }
            assertTrue(indexPayload.data.contains("\"event\":\"indexProgress\""))
            assertNotNull(indexPayload.htmlFragment)
            assertTrue(indexPayload.htmlFragment!!.contains("index-progress"))
            assertTrue(indexPayload.htmlFragment!!.contains("sse-swap=\"indexProgress\""))

            val aggregateIndex = withTimeout(1_000) { allEvents.receive() }
            assertEquals(indexPayload.data, aggregateIndex.data)

            val summarySnapshot = ContextModule.IndexStatusSnapshot(
                totalFiles = 3,
                indexedFiles = 1,
                pendingFiles = 1,
                failedFiles = 1,
                lastRefresh = baseInstant,
                health = ContextModule.IndexHealthStatus.DEGRADED,
                files = listOf(
                    ContextModule.FileIndexEntry(
                        path = "src/Main.kt",
                        status = ContextModule.FileIndexStatus.INDEXED,
                        sizeBytes = 1_024,
                        lastModified = baseInstant.minusSeconds(60),
                        chunkCount = 4
                    ),
                    ContextModule.FileIndexEntry(
                        path = "docs/readme.md",
                        status = ContextModule.FileIndexStatus.PENDING,
                        sizeBytes = 512,
                        lastModified = null,
                        chunkCount = 0
                    )
                )
            )

            val filesystemSnapshot = FilesystemIndexSnapshot(
                totalFiles = 42,
                roots = listOf(
                    FilesystemIndexSnapshot.RootSummary(
                        path = "/workspace/project",
                        totalFiles = 42
                    )
                ),
                watchRoots = listOf("/workspace/project"),
                scannedAt = baseInstant,
                missingFromCatalog = emptyList(),
                orphanedInCatalog = emptyList(),
                missingTotal = 0,
                orphanedTotal = 0
            )

            eventBus.publish(IndexStatusUpdatedEvent(summarySnapshot, filesystemSnapshot))
            runCurrent()

            val summaryPayload = withTimeout(1_000) { indexEvents.receive() }
            assertTrue(summaryPayload.data.contains("\"event\":\"indexSummary\""))
            assertNotNull(summaryPayload.htmlFragment)
            assertTrue(summaryPayload.htmlFragment!!.contains("index-summary"))
            assertTrue(summaryPayload.data.contains("\"filesystemTotal\":42"))
            assertTrue(
                summaryPayload.htmlFragment!!.lowercase().contains("filesystem"),
                "Missing filesystem info in summary fragment: ${summaryPayload.htmlFragment}"
            )
            assertTrue(summaryPayload.htmlFragment!!.contains("/workspace/project"))

            val aggregateSummary = withTimeout(1_000) { allEvents.receive() }
            assertEquals(summaryPayload.data, aggregateSummary.data)

            val metricsSnapshot = sampleMetricsSnapshot(baseInstant)
            eventBus.publish(MetricsUpdatedEvent(metricsSnapshot))
            runCurrent()

            val metricsPayload = withTimeout(1_000) { metricsEvents.receive() }
            assertTrue(metricsPayload.data.contains("\"event\":\"metricsUpdated\""))
            assertNotNull(metricsPayload.htmlFragment)
            assertTrue(metricsPayload.htmlFragment!!.contains("metrics-summary"))

            val aggregateMetrics = withTimeout(1_000) { allEvents.receive() }
            assertEquals(metricsPayload.data, aggregateMetrics.data)

            val alert = Alert(
                id = "alert-1",
                type = AlertType.PERFORMANCE_DEGRADATION,
                severity = AlertSeverity.WARNING,
                message = "Average completion time exceeded threshold",
                value = 12.5,
                threshold = 10.0,
                taskId = TaskId("task-24"),
                agentId = AgentId("agent-99"),
                timestamp = baseInstant.plusSeconds(30)
            )

            eventBus.publish(AlertTriggeredEvent(alert))
            runCurrent()

            val alertPayload = withTimeout(1_000) { metricsEvents.receive() }
            assertTrue(alertPayload.data.contains("\"event\":\"alertTriggered\""))
            assertNotNull(alertPayload.htmlFragment)
            assertTrue(alertPayload.htmlFragment!!.contains("alert-banner"))

            val aggregateAlert = withTimeout(1_000) { allEvents.receive() }
            assertEquals(alertPayload.data, aggregateAlert.data)
        } finally {
            try {
                subscriber.stop()
            } catch (_: Throwable) {}
            eventBus.shutdown()
            indexEvents.close()
            metricsEvents.close()
            allEvents.close()
            managers.values.forEach { manager ->
                try {
                    manager.shutdown()
                } catch (_: Throwable) {}
            }
            scopes.forEach { scope -> scope.cancel() }
        }
    }

    private fun TestScope.createManagers(scopes: MutableList<CoroutineScope>): Map<SSEStreamKind, SSEManager> {
        val keepAlive = 5.minutes
        return mapOf(
            SSEStreamKind.TASKS to newManager(scopes, keepAlive),
            SSEStreamKind.INDEX to newManager(scopes, keepAlive),
            SSEStreamKind.METRICS to newManager(scopes, keepAlive),
            SSEStreamKind.ALL to newManager(scopes, keepAlive)
        )
    }

    private fun TestScope.newManager(
        scopes: MutableList<CoroutineScope>,
        keepAlive: kotlin.time.Duration
    ): SSEManager {
        val scope = newScope(scopes)
        return SSEManager(scope, keepAliveInterval = keepAlive, clock = { Instant.now() })
    }

    private fun TestScope.newScope(scopes: MutableList<CoroutineScope>): CoroutineScope {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        scopes += scope
        return scope
    }

    private suspend fun TestScope.registerCollector(
        manager: SSEManager,
        connectionId: String,
        sink: Channel<SSEEvent>
    ) {
        manager.subscribe(connectionId, SSEConnection.Sender { event ->
            sink.send(event)
        })

        runCurrent()

        val initial = withTimeout(1_000) { sink.receive() }
        // Validate the initial handshake event and drop it.
        assertEquals(SSEEventType.CONNECTED, initial.type)
    }

    private fun sampleMetricsSnapshot(timestamp: Instant): MetricsSnapshot {
        val period = timestamp.minusSeconds(3_600) to timestamp
        return MetricsSnapshot(
            timestamp = timestamp,
            tokenUsage = TokenReport(
                totalTokens = 12_345,
                byTask = emptyMap(),
                byAgent = emptyMap(),
                savings = 456,
                period = period
            ),
            performance = PerformanceDashboard(
                avgTaskCompletionTime = Duration.ofSeconds(18),
                avgAgentResponseTime = Duration.ofSeconds(7),
                overallSuccessRate = 0.82,
                taskSuccessRate = emptyMap(),
                agentSuccessRate = emptyMap(),
                bottlenecks = emptyList<Bottleneck>(),
                period = period
            ),
            decisions = DecisionReport(
                routingAccuracy = 0.75,
                strategyMetrics = emptyList<StrategyMetrics>(),
                confidenceCalibration = emptyList<ConfidenceCalibration>(),
                optimalStrategies = emptyMap(),
                patterns = emptyList(),
                period = period
            ),
            alerts = AlertSummary(
                total = 3,
                unacknowledged = 1,
                bySeverity = mapOf(AlertSeverity.WARNING to 1, AlertSeverity.ERROR to 1),
                byType = mapOf(AlertType.PERFORMANCE_DEGRADATION to 1)
            )
        )
    }
}
