package com.orchestrator.modules.context

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.domain.ContextSnippet
import com.orchestrator.context.domain.ChunkKind
import com.orchestrator.context.domain.TokenBudget
import com.orchestrator.context.providers.ContextProvider
import com.orchestrator.context.providers.ContextProviderRegistry
import com.orchestrator.context.providers.ContextProviderType
import com.orchestrator.context.search.SearchResult
import com.orchestrator.domain.AgentId
import com.orchestrator.domain.Task
import com.orchestrator.domain.TaskId
import com.orchestrator.domain.TaskType
import com.orchestrator.modules.context.ContextRetrievalModule.ProviderStats
import com.orchestrator.modules.context.ContextRetrievalModule.ProviderStatsEntry
import com.orchestrator.modules.context.ContextRetrievalModule.ProviderStatsFailure
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextRetrievalModuleTest {

    private val agentDirectory = AgentDirectory { null }
    private val budgetManager = mockk<BudgetManager>()
    private val queryOptimizer = mockk<QueryOptimizer>()
    private val metricsRecorder = object : ContextMetricsRecorder {
        override fun record(
            task: Task,
            agentId: AgentId,
            context: ContextRetrievalModule.TaskContext,
            duration: java.time.Duration
        ) {
            // no-op for tests
        }
    }
    private lateinit var contextModule: ContextRetrievalModule

    @BeforeTest
    fun setup() {
        mockkObject(ContextProviderRegistry)
        contextModule = ContextRetrievalModule(ContextConfig(), agentDirectory, budgetManager, queryOptimizer, metricsRecorder)
    }

    @AfterTest
    fun tearDown() {
        unmockkObject(ContextProviderRegistry)
    }

    @Test
    fun `returns snippets from providers`() = runBlocking {
        val provider = mockProvider(ContextProviderType.SEMANTIC)
        val snippet = snippet(chunkId = 1L, path = "src/App.kt", score = 0.9)

        every { ContextProviderRegistry.getAllProviders() } returns listOf(provider)
        every { ContextProviderRegistry.getProvider(any()) } returns null
        every { budgetManager.calculateBudget(any(), any()) } returns TokenBudget(maxTokens = 500, reserveForPrompt = 100, diversityWeight = 0.5)
        every { queryOptimizer.optimize(any(), any(), any()) } answers { secondArg<List<SearchResult>>() }
        coEvery { provider.getContext(any(), any(), any()) } returns listOf(snippet)

        val task = task()
        val context = contextModule.getTaskContext(task, AgentId("agent-1"))

        assertEquals(1, context.snippets.size)
        val returned = context.snippets.first()
        assertEquals("src/App.kt", returned.filePath)
        assertTrue(returned.metadata["sources"]?.contains("semantic") == true)

        val stats = context.diagnostics.providerMetrics["semantic"]
        assertTrue(stats is ProviderStats)
        assertEquals(1, stats.snippetCount)
        assertFalse(context.diagnostics.fallbackUsed)
    }

    @Test
    fun `invokes fallback when primary providers return no snippets`() = runBlocking {
        val primary = mockProvider(ContextProviderType.SYMBOL)
        val fallback = mockProvider(ContextProviderType.SEMANTIC)
        val snippet = snippet(chunkId = 2L, path = "src/Main.kt", score = 0.8)

        every { ContextProviderRegistry.getAllProviders() } returns listOf(primary)
        every { ContextProviderRegistry.getProvider("semantic") } returns fallback
        every { budgetManager.calculateBudget(any(), any()) } returns TokenBudget(400, reserveForPrompt = 100, diversityWeight = 0.5)
        every { queryOptimizer.optimize(any(), any(), any()) } answers { secondArg<List<SearchResult>>() }
        coEvery { primary.getContext(any(), any(), any()) } returns emptyList()
        coEvery { fallback.getContext(any(), any(), any()) } returns listOf(snippet)

        val task = task(description = "touch src/Main.kt")
        val context = contextModule.getTaskContext(task, AgentId("agent-1"))

        assertEquals(1, context.snippets.size)
        assertTrue(context.diagnostics.fallbackUsed)
        val stats = context.diagnostics.providerMetrics["semantic"]
        assertTrue(stats is ProviderStats)
        assertEquals(1, stats.snippetCount)
    }

    @Test
    fun `returns empty context when no snippet budget available`() = runBlocking {
        val provider = mockProvider(ContextProviderType.SEMANTIC)
        every { ContextProviderRegistry.getAllProviders() } returns listOf(provider)
        every { ContextProviderRegistry.getProvider(any()) } returns null
        every { budgetManager.calculateBudget(any(), any()) } returns TokenBudget(maxTokens = 200, reserveForPrompt = 200, diversityWeight = 0.5)
        coEvery { provider.getContext(any(), any(), any()) } returns emptyList()

        val task = task()
        val context = contextModule.getTaskContext(task, AgentId("agent-1"))

        assertTrue(context.snippets.isEmpty())
        assertTrue(context.diagnostics.warnings.any { it.contains("no tokens", ignoreCase = true) })
        val stats = context.diagnostics.providerMetrics["semantic"]
        assertTrue(stats is ProviderStatsFailure || stats == null)
    }

    private fun mockProvider(type: ContextProviderType): ContextProvider = mockk<ContextProvider> {
        every { this@mockk.type } returns type
    }

    private fun snippet(chunkId: Long, path: String, score: Double): ContextSnippet = ContextSnippet(
        chunkId = chunkId,
        score = score,
        filePath = path,
        label = null,
        kind = ChunkKind.CODE_BLOCK,
        text = "fun example() = Unit",
        language = "kotlin",
        offsets = null,
        metadata = emptyMap()
    )

    private fun task(description: String? = null): Task = Task(
        id = TaskId("T-1"),
        title = "Build context",
        description = description,
        type = TaskType.IMPLEMENTATION
    )
}
