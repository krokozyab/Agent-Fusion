package com.orchestrator.modules.routing

import com.orchestrator.config.ConfigLoader
import com.orchestrator.core.AgentRegistry
import com.orchestrator.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoutingModuleTest {

    private fun buildTestRegistry(): AgentRegistry {
        val defs = listOf(
            ConfigLoader.AgentDefinition(AgentId("alpha"), AgentType.GPT, AgentConfig(name = "Alpha", model = "gpt-4o")),
            ConfigLoader.AgentDefinition(AgentId("bravo"), AgentType.CLAUDE_CODE, AgentConfig(name = "Bravo", model = "claude-3.5-sonnet")),
            ConfigLoader.AgentDefinition(AgentId("charlie"), AgentType.GEMINI, AgentConfig(name = "Charlie", model = "gemini-1.5"))
        )
        return AgentRegistry.build(defs)
    }

    @Test
    fun routesSoloByDefault() {
        val registry = buildTestRegistry()
        val module = RoutingModule(registry)
        val task = Task(
            id = TaskId("t1"),
            title = "Write small README blurb",
            description = "Very small documentation task",
            type = TaskType.DOCUMENTATION,
            complexity = 2,
            risk = 2
        )
        val decision = module.routeTask(task)
        assertEquals(RoutingStrategy.SOLO, decision.strategy)
        assertNotNull(decision.primaryAgentId)
        assertTrue(decision.participantAgentIds.isNotEmpty())
    }

    @Test
    fun honorsAssignToAgentDirective() {
        val registry = buildTestRegistry()
        val module = RoutingModule(registry)
        val task = Task(
            id = TaskId("t2"),
            title = "Implement feature X",
            description = "Simple change",
            type = TaskType.IMPLEMENTATION,
            complexity = 3,
            risk = 3
        )
        val directive = UserDirective(
            originalText = "@bravo please handle",
            assignToAgent = AgentId("bravo"),
            assignmentConfidence = 0.85
        )
        val decision = module.routeTaskWithDirective(task, directive)
        assertEquals(RoutingStrategy.SOLO, decision.strategy)
        assertEquals(AgentId("bravo"), decision.primaryAgentId)
        assertTrue(decision.participantAgentIds.contains(AgentId("bravo")))
        assertEquals(0.85, decision.metadata["directive.assignmentConfidence"]?.toDouble())
    }

    @Test
    fun forceConsensusDirectiveWins() {
        val registry = buildTestRegistry()
        val module = RoutingModule(registry)
        val task = Task(
            id = TaskId("t3"),
            title = "Design new module architecture",
            description = "We need consensus required",
            type = TaskType.ARCHITECTURE,
            complexity = 7,
            risk = 5
        )
        val directive = UserDirective(
            originalText = "consensus required",
            forceConsensus = true,
            forceConsensusConfidence = 0.9
        )
        val decision = module.routeTaskWithDirective(task, directive)
        assertEquals(RoutingStrategy.CONSENSUS, decision.strategy)
        assertTrue(decision.participantAgentIds.size >= 2)
        assertEquals("true", decision.metadata["directive.forceConsensus"])
        assertEquals(0.9, decision.metadata["directive.forceConsensusConfidence"]?.toDouble())
    }

    @Test
    fun preventConsensusLeadsToSolo() {
        val registry = buildTestRegistry()
        val module = RoutingModule(registry)
        val task = Task(
            id = TaskId("t4"),
            title = "Small bugfix",
            description = "quick fix solo please",
            type = TaskType.BUGFIX,
            complexity = 3,
            risk = 3
        )
        val directive = UserDirective(
            originalText = "solo please",
            preventConsensus = true,
            preventConsensusConfidence = 0.88
        )
        val decision = module.routeTaskWithDirective(task, directive)
        assertEquals(RoutingStrategy.SOLO, decision.strategy)
        assertTrue(decision.participantAgentIds.size == 1)
        assertEquals("true", decision.metadata["directive.preventConsensus"])
        assertEquals(0.88, decision.metadata["directive.preventConsensusConfidence"]?.toDouble())
    }

    @Test
    fun highRiskOrCriticalTriggersConsensus() {
        val registry = buildTestRegistry()
        val module = RoutingModule(registry)
        val task = Task(
            id = TaskId("t5"),
            title = "Handle payment encryption",
            description = "Work on PCI payment encryption security module",
            type = TaskType.IMPLEMENTATION,
            complexity = 6,
            risk = 9
        )
        val decision = module.routeTask(task)
        assertEquals(RoutingStrategy.CONSENSUS, decision.strategy)
        assertTrue(decision.participantAgentIds.size >= 2)
    }

    @Test
    fun architectureHighComplexitySequential() {
        val registry = buildTestRegistry()
        val module = RoutingModule(registry)
        val task = Task(
            id = TaskId("t6"),
            title = "Architecture plan",
            description = "Large-scale architecture integration with multiple components and migration steps",
            type = TaskType.ARCHITECTURE,
            complexity = 8,
            risk = 5
        )
        val decision = module.routeTask(task)
        assertEquals(RoutingStrategy.SEQUENTIAL, decision.strategy)
        assertTrue(decision.participantAgentIds.size >= 2)
    }

    @Test
    fun parallelizableSignalsPickParallel() {
        val registry = buildTestRegistry()
        val module = RoutingModule(registry)
        val task = Task(
            id = TaskId("t7"),
            title = "Testing plan",
            description = "Run parallel test generation across multiple modules",
            type = TaskType.TESTING,
            complexity = 6,
            risk = 3,
            metadata = mapOf("parallelizable" to "true")
        )
        val decision = module.routeTask(task)
        assertEquals(RoutingStrategy.PARALLEL, decision.strategy)
        assertTrue(decision.participantAgentIds.size >= 2)
    }
}
