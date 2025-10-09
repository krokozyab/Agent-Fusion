package com.orchestrator.agents.factories

import com.orchestrator.agents.ClaudeCodeAgent
import com.orchestrator.core.AgentFactory
import com.orchestrator.core.AgentFactoryRegistry
import com.orchestrator.domain.AgentConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.ServiceLoader

class ClaudeCodeAgentFactoryTest {

    @Test
    fun `factory has correct supported type`() {
        val factory = ClaudeCodeAgentFactory()
        
        assertEquals("CLAUDE_CODE", factory.supportedType)
    }

    @Test
    fun `factory creates ClaudeCodeAgent`() {
        val factory = ClaudeCodeAgentFactory()
        val config = AgentConfig(
            name = "test-claude",
            extra = mapOf("url" to "http://localhost:3000")
        )
        
        val agent = factory.createAgent(config)
        
        assertNotNull(agent)
        assertTrue(agent is ClaudeCodeAgent)
    }

    @Test
    fun `factory throws on invalid config`() {
        val factory = ClaudeCodeAgentFactory()
        val config = AgentConfig(name = "test-claude") // Missing url
        
        assertThrows(IllegalArgumentException::class.java) {
            factory.createAgent(config)
        }
    }

    @Test
    fun `factory is discoverable via SPI`() {
        val factories = ServiceLoader.load(AgentFactory::class.java).toList()
        
        val claudeFactory = factories.find { it.supportedType == "CLAUDE_CODE" }
        assertNotNull(claudeFactory, "ClaudeCodeAgentFactory should be discoverable via SPI")
        assertTrue(claudeFactory is ClaudeCodeAgentFactory)
    }

    @Test
    fun `factory is registered in AgentFactoryRegistry`() {
        val registry = AgentFactoryRegistry.fromServiceLoader()
        
        val factory = registry.getFactory("CLAUDE_CODE")
        assertNotNull(factory, "ClaudeCodeAgentFactory should be in registry")
        assertTrue(factory is ClaudeCodeAgentFactory)
    }

    @Test
    fun `factory creates agent with all capabilities`() {
        val factory = ClaudeCodeAgentFactory()
        val config = AgentConfig(
            extra = mapOf("url" to "http://localhost:3000")
        )
        
        val agent = factory.createAgent(config)
        
        assertTrue(agent.capabilities.isNotEmpty())
        assertTrue(agent.strengths.isNotEmpty())
    }
}
