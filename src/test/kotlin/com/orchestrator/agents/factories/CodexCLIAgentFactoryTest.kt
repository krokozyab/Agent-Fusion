package com.orchestrator.agents.factories

import com.orchestrator.agents.CodexCLIAgent
import com.orchestrator.core.AgentFactory
import com.orchestrator.core.AgentFactoryRegistry
import com.orchestrator.domain.AgentConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.ServiceLoader

class CodexCLIAgentFactoryTest {

    @Test
    fun `factory has correct supported type`() {
        val factory = CodexCLIAgentFactory()
        
        assertEquals("CODEX_CLI", factory.supportedType)
    }

    @Test
    fun `factory creates CodexCLIAgent`() {
        val factory = CodexCLIAgentFactory()
        val config = AgentConfig(
            name = "test-codex",
            extra = mapOf("url" to "http://localhost:3000")
        )
        
        val agent = factory.createAgent(config)
        
        assertNotNull(agent)
        assertTrue(agent is CodexCLIAgent)
    }

    @Test
    fun `factory throws on invalid config`() {
        val factory = CodexCLIAgentFactory()
        val config = AgentConfig(name = "test-codex") // Missing url
        
        assertThrows(IllegalArgumentException::class.java) {
            factory.createAgent(config)
        }
    }

    @Test
    fun `factory is discoverable via SPI`() {
        val factories = ServiceLoader.load(AgentFactory::class.java).toList()
        
        val codexFactory = factories.find { it.supportedType == "CODEX_CLI" }
        assertNotNull(codexFactory, "CodexCLIAgentFactory should be discoverable via SPI")
        assertTrue(codexFactory is CodexCLIAgentFactory)
    }

    @Test
    fun `factory is registered in AgentFactoryRegistry`() {
        val registry = AgentFactoryRegistry.fromServiceLoader()
        
        val factory = registry.getFactory("CODEX_CLI")
        assertNotNull(factory, "CodexCLIAgentFactory should be in registry")
        assertTrue(factory is CodexCLIAgentFactory)
    }

    @Test
    fun `factory creates agent with all capabilities`() {
        val factory = CodexCLIAgentFactory()
        val config = AgentConfig(
            extra = mapOf("url" to "http://localhost:3000")
        )
        
        val agent = factory.createAgent(config)
        
        assertTrue(agent.capabilities.isNotEmpty())
        assertTrue(agent.strengths.isNotEmpty())
    }

    @Test
    fun `both factories are registered`() {
        val registry = AgentFactoryRegistry.fromServiceLoader()
        
        assertNotNull(registry.getFactory("CLAUDE_CODE"))
        assertNotNull(registry.getFactory("CODEX_CLI"))
    }
}
