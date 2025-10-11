package com.orchestrator.agents.factories

import com.orchestrator.agents.QCLIAgent
import com.orchestrator.core.AgentFactory
import com.orchestrator.core.AgentFactoryRegistry
import com.orchestrator.domain.AgentConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.ServiceLoader

class QCLIAgentFactoryTest {

    @Test
    fun `factory has correct supported type`() {
        val factory = QCLIAgentFactory()

        assertEquals("Q_CLI", factory.supportedType)
    }

    @Test
    fun `factory creates QCLIAgent`() {
        val factory = QCLIAgentFactory()
        val config = AgentConfig(
            name = "test-q",
            extra = mapOf("url" to "http://localhost:3001")
        )

        val agent = factory.createAgent(config)

        assertNotNull(agent)
        assertTrue(agent is QCLIAgent)
    }

    @Test
    fun `factory throws on invalid config`() {
        val factory = QCLIAgentFactory()
        val config = AgentConfig(name = "test-q") // Missing url

        assertThrows(IllegalArgumentException::class.java) {
            factory.createAgent(config)
        }
    }

    @Test
    fun `factory is discoverable via SPI`() {
        val factories = ServiceLoader.load(AgentFactory::class.java).toList()

        val qFactory = factories.find { it.supportedType == "Q_CLI" }
        assertNotNull(qFactory, "QCLIAgentFactory should be discoverable via SPI")
        assertTrue(qFactory is QCLIAgentFactory)
    }

    @Test
    fun `factory is registered in AgentFactoryRegistry`() {
        val registry = AgentFactoryRegistry.fromServiceLoader()

        val factory = registry.getFactory("Q_CLI")
        assertNotNull(factory, "QCLIAgentFactory should be in registry")
        assertTrue(factory is QCLIAgentFactory)
    }

    @Test
    fun `factory creates agent with all capabilities`() {
        val factory = QCLIAgentFactory()
        val config = AgentConfig(
            extra = mapOf("url" to "http://localhost:3001")
        )

        val agent = factory.createAgent(config)

        assertTrue(agent.capabilities.isNotEmpty())
        assertTrue(agent.strengths.isNotEmpty())
    }

    @Test
    fun `all three factories are registered`() {
        val registry = AgentFactoryRegistry.fromServiceLoader()

        assertNotNull(registry.getFactory("CLAUDE_CODE"))
        assertNotNull(registry.getFactory("CODEX_CLI"))
        assertNotNull(registry.getFactory("Q_CLI"))
    }
}
