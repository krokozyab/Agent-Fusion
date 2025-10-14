package com.orchestrator.modules.context

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.domain.TokenBudget
import com.orchestrator.domain.Agent
import com.orchestrator.domain.AgentConfig
import com.orchestrator.domain.AgentId
import com.orchestrator.domain.AgentStatus
import com.orchestrator.domain.AgentType
import com.orchestrator.domain.Capability
import com.orchestrator.domain.RoutingStrategy
import com.orchestrator.domain.Strength
import com.orchestrator.domain.Task
import com.orchestrator.domain.TaskId
import com.orchestrator.domain.TaskType
import com.orchestrator.modules.context.AgentDirectory
import com.orchestrator.utils.Logger as ApplicationLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class BudgetManagerTest {

    private val contextConfig = ContextConfig()
    private val expertAgent = TestAgent(
        id = AgentId("agent-expert"),
        type = AgentType.CLAUDE_CODE,
        displayName = "Expert Agent",
        capabilities = setOf(Capability.ARCHITECTURE, Capability.CODE_GENERATION),
        strengths = listOf(
            Strength(Capability.ARCHITECTURE, 92),
            Strength(Capability.CODE_GENERATION, 88)
        )
    )
    private val directory = MapAgentDirectory(mapOf(expertAgent.id to expertAgent))

    @Test
    fun `complex architecture tasks allocate larger budget`() {
        val manager = BudgetManager(contextConfig, directory)
        val task = Task(
            id = TaskId("task-architecture"),
            title = "Design distributed cache",
            type = TaskType.ARCHITECTURE,
            complexity = 9
        )

        val budget = manager.calculateBudget(task, expertAgent.id)

        assertEquals(5184, budget.maxTokens)
        assertEquals(500, budget.reserveForPrompt)
        assertEquals(0.8, budget.diversityWeight, 1e-6)
        assertTrue(budget.availableForSnippets > TokenBudget(contextConfig.budget.defaultMaxTokens).availableForSnippets)
    }

    @Test
    fun `warns when computed budget exceeds threshold`() {
        val loggerName = "BudgetManagerTest.warn"
        val underlying = LoggerFactory.getLogger(loggerName) as Logger
        val appender = ListAppender<ILoggingEvent>().apply {
            name = "warn-capture"
            start()
        }
        underlying.level = Level.WARN
        underlying.addAppender(appender)

        val manager = BudgetManager(contextConfig, directory, ApplicationLogger.logger(loggerName))
        val task = Task(
            id = TaskId("task-consensus"),
            title = "Critical architecture review",
            type = TaskType.ARCHITECTURE,
            complexity = 10,
            routing = RoutingStrategy.CONSENSUS
        )

        val budget = manager.calculateBudget(task, expertAgent.id)
        assertTrue(budget.maxTokens >= contextConfig.budget.defaultMaxTokens)

        underlying.detachAppender(appender)
        val warned = appender.list.any { event ->
            event.formattedMessage.contains("High context budget computed")
        }
        assertTrue(warned, "Expected high-budget warning to be logged")
    }

    private class MapAgentDirectory(private val agents: Map<AgentId, Agent>) : AgentDirectory {
        override fun getAgent(id: AgentId): Agent? = agents[id]
    }

    private data class TestAgent(
        override val id: AgentId,
        override val type: AgentType,
        override val displayName: String,
        override val capabilities: Set<Capability>,
        override val strengths: List<Strength>,
        override val status: AgentStatus = AgentStatus.ONLINE,
        override val config: AgentConfig? = null
    ) : Agent
}
