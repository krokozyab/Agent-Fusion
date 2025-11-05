package com.orchestrator.modules.context

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.domain.ContextSnippet
import com.orchestrator.context.domain.ChunkKind
import com.orchestrator.context.domain.TokenBudget
import com.orchestrator.context.storage.ContextDatabase
import com.orchestrator.domain.AgentId
import com.orchestrator.domain.Task
import com.orchestrator.domain.TaskId
import com.orchestrator.domain.TaskStatus
import com.orchestrator.domain.TaskType
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.time.Duration
import java.time.Instant

class ContextMetricsCollectorTest {

    private val collector = ContextMetricsCollector()

    @BeforeTest
    fun setup() {
        ContextDatabase.initialize(ContextConfig().storage)
        clearMetrics()
    }

    @AfterTest
    fun teardown() {
        clearMetrics()
    }

    @Test
    fun `record persists usage metrics`() = runBlocking {
        val task = sampleTask("metrics-1")
        val context = sampleContext(task.id.value, snippets = 2, tokensUsed = 180)

        collector.record(task, AgentId("agent-1"), context, Duration.ofMillis(120))

        val stats = collector.getStats()
        assertEquals(1, stats.totalRecords)
        assertTrue(stats.totalContextTokens >= 180)
        assertTrue(stats.averageLatencyMs >= 120.0)
    }

    @Test
    fun `compareBeforeAfter generates delta report`() = runBlocking {
        val task = sampleTask("metrics-2")
        val baseContext = sampleContext(task.id.value, snippets = 1, tokensUsed = 100)
        val improvedContext = sampleContext(task.id.value, snippets = 3, tokensUsed = 240)

        repeat(2) {
            collector.record(task, AgentId("agent-1"), baseContext, Duration.ofMillis(200))
        }
        repeat(2) {
            collector.record(task, AgentId("agent-1"), improvedContext, Duration.ofMillis(80))
        }

        val report = collector.compareBeforeAfter()
        assertTrue(report.current.averageSnippets >= report.baseline.averageSnippets)
        assertTrue(report.deltaLatencyMs <= 0.0)
    }

    private fun sampleTask(id: String): Task = Task(
        id = TaskId(id),
        title = "Task $id",
        description = "Collect metrics",
        type = TaskType.IMPLEMENTATION,
        status = TaskStatus.IN_PROGRESS,
        routing = com.orchestrator.domain.RoutingStrategy.SOLO,
        complexity = 5,
        risk = 5,
        createdAt = Instant.now()
    )

    private fun sampleContext(taskId: String, snippets: Int, tokensUsed: Int): ContextRetrievalModule.TaskContext {
        val diagnostics = ContextRetrievalModule.ContextDiagnostics(
            budget = TokenBudget(maxTokens = 1000, reserveForPrompt = 200, diversityWeight = 0.2),
            providerMetrics = emptyMap(),
            duration = Duration.ofMillis(100),
            warnings = emptyList(),
            fallbackUsed = false,
            fallbackProvider = null,
            tokensRequested = 800,
            tokensUsed = tokensUsed
        )
        val snippet = ContextSnippet(
            chunkId = 1L,
            score = 0.9,
            filePath = "src/Sample.kt",
            label = "Sample",
            kind = ChunkKind.CODE_BLOCK,
            text = "fun example() = Unit",
            language = "kotlin",
            offsets = 1..1,
            metadata = emptyMap()
        )
        return ContextRetrievalModule.TaskContext(
            taskId = taskId,
            snippets = List(snippets) { snippet },
            diagnostics = diagnostics
        )
    }

    private fun clearMetrics() {
        ContextDatabase.withConnection { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("DELETE FROM usage_metrics")
            }
        }
    }
}
