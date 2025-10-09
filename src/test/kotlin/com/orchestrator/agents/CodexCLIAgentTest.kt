package com.orchestrator.agents

import com.orchestrator.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CodexCLIAgentTest {

    @Test
    fun `agent has correct identity`() {
        val connection = McpConnection("http://localhost:3000")
        val agent = CodexCLIAgent(connection)
        
        assertEquals(AgentId("codex-cli"), agent.id)
        assertEquals(AgentType.CODEX_CLI, agent.type)
        assertEquals("Codex CLI", agent.displayName)
    }

    @Test
    fun `agent uses custom name from config`() {
        val connection = McpConnection("http://localhost:3000")
        val config = AgentConfig(name = "Codex-Custom")
        val agent = CodexCLIAgent(connection, config)
        
        assertEquals(AgentId("Codex-Custom"), agent.id)
        assertEquals("Codex-Custom", agent.displayName)
    }

    @Test
    fun `agent has comprehensive capabilities`() {
        val connection = McpConnection("http://localhost:3000")
        val agent = CodexCLIAgent(connection)
        
        val expected = setOf(
            Capability.ARCHITECTURE,
            Capability.PLANNING,
            Capability.CODE_REVIEW,
            Capability.DATA_ANALYSIS,
            Capability.DOCUMENTATION,
            Capability.CODE_GENERATION,
            Capability.DEBUGGING
        )
        
        assertEquals(expected, agent.capabilities)
    }

    @Test
    fun `agent has defined strengths`() {
        val connection = McpConnection("http://localhost:3000")
        val agent = CodexCLIAgent(connection)
        
        assertEquals(7, agent.strengths.size)
        
        val archStrength = agent.strengths.find { it.capability == Capability.ARCHITECTURE }
        assertNotNull(archStrength)
        assertEquals(95, archStrength?.score)
        
        val planningStrength = agent.strengths.find { it.capability == Capability.PLANNING }
        assertEquals(93, planningStrength?.score)
    }

    @Test
    fun `fromConfig creates agent with url`() {
        val config = AgentConfig(
            name = "test-codex",
            extra = mapOf("url" to "http://localhost:4000/mcp")
        )
        
        val agent = CodexCLIAgent.fromConfig(config)
        
        assertEquals(AgentId("test-codex"), agent.id)
        assertEquals("test-codex", agent.displayName)
    }

    @Test
    fun `fromConfig requires url in extra`() {
        val config = AgentConfig(name = "test-codex")
        
        val exception = assertThrows(IllegalArgumentException::class.java) {
            CodexCLIAgent.fromConfig(config)
        }
        
        assertTrue(exception.message?.contains("url") == true)
    }

    @Test
    fun `fromConfig uses custom timeout`() {
        val config = AgentConfig(
            extra = mapOf(
                "url" to "http://localhost:3000",
                "timeout" to "60"
            )
        )
        
        val agent = CodexCLIAgent.fromConfig(config)
        assertNotNull(agent)
    }

    @Test
    fun `fromConfig uses custom retry attempts`() {
        val config = AgentConfig(
            extra = mapOf(
                "url" to "http://localhost:3000",
                "retryAttempts" to "5"
            )
        )
        
        val agent = CodexCLIAgent.fromConfig(config)
        assertNotNull(agent)
    }

    @Test
    fun `agent starts with OFFLINE status`() {
        val connection = McpConnection("http://localhost:3000")
        val agent = CodexCLIAgent(connection)
        
        assertEquals(AgentStatus.OFFLINE, agent.status)
    }

    @Test
    fun `agent has architecture and planning capabilities`() {
        val connection = McpConnection("http://localhost:3000")
        val agent = CodexCLIAgent(connection)
        
        assertTrue(agent.capabilities.contains(Capability.ARCHITECTURE))
        assertTrue(agent.capabilities.contains(Capability.PLANNING))
        assertTrue(agent.capabilities.contains(Capability.CODE_REVIEW))
    }

    @Test
    fun `all strengths are within valid range`() {
        val connection = McpConnection("http://localhost:3000")
        val agent = CodexCLIAgent(connection)
        
        agent.strengths.forEach { strength ->
            assertTrue(strength.score in 0..100, 
                "Strength ${strength.capability} has invalid score: ${strength.score}")
        }
    }

    @Test
    fun `architecture strength is highest`() {
        val connection = McpConnection("http://localhost:3000")
        val agent = CodexCLIAgent(connection)
        
        val archStrength = agent.strengths.find { it.capability == Capability.ARCHITECTURE }
        val maxStrength = agent.strengths.maxOf { it.score }
        
        assertEquals(maxStrength, archStrength?.score)
    }

    @Test
    fun `toString includes agent details`() {
        val connection = McpConnection("http://localhost:3000")
        val agent = CodexCLIAgent(connection)
        
        val str = agent.toString()
        
        assertTrue(str.contains("codex-cli"))
        assertTrue(str.contains("CODEX_CLI"))
    }
}
