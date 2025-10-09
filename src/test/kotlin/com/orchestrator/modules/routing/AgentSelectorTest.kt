package com.orchestrator.modules.routing

import com.orchestrator.config.ConfigLoader
import com.orchestrator.core.AgentRegistry
import com.orchestrator.domain.*
import com.orchestrator.storage.repositories.MetricsRepository
import com.orchestrator.storage.repositories.MetricsRepository.MetricInsert
import com.orchestrator.storage.repositories.MetricsRepository.TimeRange
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AgentSelectorTest {

    private fun buildRegistry(ids: List<String>): AgentRegistry {
        val defs = ids.map { id ->
            ConfigLoader.AgentDefinition(AgentId(id), AgentType.GPT, AgentConfig(name = id.uppercase(), model = "gpt-4o"))
        }
        return AgentRegistry.build(defs)
    }

    private fun task(type: TaskType = TaskType.IMPLEMENTATION): Task = Task(
        id = TaskId("t1"),
        title = "Test",
        type = type
    )

    @Test
    fun respectsUserDirectiveWhenAgentOnline() {
        val registry = buildRegistry(listOf("a1", "a2"))
        val selector = AgentSelector(registry)
        val directive = UserDirective(
            originalText = "use a1",
            assignToAgent = AgentId("a1"),
            assignmentConfidence = 0.9
        )

        val selected = selector.selectAgentForTask(task(), directive)
        assertNotNull(selected)
        assertEquals(AgentId("a1"), selected.id)
    }

    @Test
    fun choosesOnlineOverBusyAndOffline() {
        val registry = buildRegistry(listOf("a1", "a2"))
        // Make a1 BUSY and a2 ONLINE -> prefer a2
        registry.updateStatus(AgentId("a1"), AgentStatus.BUSY)
        registry.updateStatus(AgentId("a2"), AgentStatus.ONLINE)

        val selector = AgentSelector(registry)
        val selected = selector.selectAgentForTask(task())
        assertNotNull(selected)
        assertEquals(AgentId("a2"), selected.id)
    }

    @Test
    fun returnsNullWhenNoAvailableAgentMatchesCapability() {
        // CUSTOM agents have no default capabilities in registry; thus none match implementation task
        val defs = listOf(
            ConfigLoader.AgentDefinition(AgentId("c1"), AgentType.CUSTOM, AgentConfig(name = "C1", model = "x"))
        )
        val registry = AgentRegistry.build(defs)
        val selector = AgentSelector(registry)
        val selected = selector.selectAgentForTask(task(TaskType.IMPLEMENTATION))
        assertNull(selected)
    }

    @Test
    fun consensusReturnsTopAgents() {
        val registry = buildRegistry(listOf("a1", "a2", "a3"))
        val selector = AgentSelector(registry)
        val list = selector.selectAgentsForConsensus(task())
        assertEquals(3, list.size)
        // ensure unique
        assertEquals(3, list.map { it.id }.toSet().size)
    }

}
