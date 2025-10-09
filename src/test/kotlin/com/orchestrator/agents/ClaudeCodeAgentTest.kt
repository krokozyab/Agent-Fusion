package com.orchestrator.agents

import com.orchestrator.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration

class ClaudeCodeAgentTest {

    @Test
    fun `agent has correct identity`() {
        val connection = McpConnection("http://localhost:3000")
        val agent = ClaudeCodeAgent(connection)
        
        assertEquals(AgentId("claude-code"), agent.id)
        assertEquals(AgentType.CLAUDE_CODE, agent.type)
        assertEquals("Claude Code", agent.displayName)
    }

    @Test
    fun `agent uses custom name from config`() {
        val connection = McpConnection("http://localhost:3000")
        val config = AgentConfig(name = "Claude-Custom")
        val agent = ClaudeCodeAgent(connection, config)
        
        assertEquals(AgentId("Claude-Custom"), agent.id)
        assertEquals("Claude-Custom", agent.displayName)
    }

    @Test
    fun `agent has comprehensive capabilities`() {
        val connection = McpConnection("http://localhost:3000")
        val agent = ClaudeCodeAgent(connection)
        
        val expected = setOf(
            Capability.CODE_GENERATION,
            Capability.CODE_REVIEW,
            Capability.REFACTORING,
            Capability.TEST_WRITING,
            Capability.DOCUMENTATION,
            Capability.DEBUGGING,
            Capability.ARCHITECTURE
        )
        
        assertEquals(expected, agent.capabilities)
    }

    @Test
    fun `agent has defined strengths`() {
        val connection = McpConnection("http://localhost:3000")
        val agent = ClaudeCodeAgent(connection)
        
        assertEquals(7, agent.strengths.size)
        
        val codeGenStrength = agent.strengths.find { it.capability == Capability.CODE_GENERATION }
        assertNotNull(codeGenStrength)
        assertEquals(95, codeGenStrength?.score)
        
        val reviewStrength = agent.strengths.find { it.capability == Capability.CODE_REVIEW }
        assertEquals(90, reviewStrength?.score)
    }

    @Test
    fun `fromConfig creates agent with url`() {
        val config = AgentConfig(
            name = "test-claude",
            extra = mapOf("url" to "http://localhost:4000/mcp")
        )
        
        val agent = ClaudeCodeAgent.fromConfig(config)
        
        assertEquals(AgentId("test-claude"), agent.id)
        assertEquals("test-claude", agent.displayName)
    }

    @Test
    fun `fromConfig requires url in extra`() {
        val config = AgentConfig(name = "test-claude")
        
        val exception = assertThrows(IllegalArgumentException::class.java) {
            ClaudeCodeAgent.fromConfig(config)
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
        
        val agent = ClaudeCodeAgent.fromConfig(config)
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
        
        val agent = ClaudeCodeAgent.fromConfig(config)
        assertNotNull(agent)
    }

    @Test
    fun `fromConfig uses defaults when not specified`() {
        val config = AgentConfig(
            extra = mapOf("url" to "http://localhost:3000")
        )
        
        val agent = ClaudeCodeAgent.fromConfig(config)
        assertNotNull(agent)
    }

    @Test
    fun `agent config is accessible`() {
        val connection = McpConnection("http://localhost:3000")
        val config = AgentConfig(
            name = "test-agent",
            model = "claude-3.5-sonnet",
            temperature = 0.7
        )
        val agent = ClaudeCodeAgent(connection, config)
        
        assertEquals(config, agent.config)
        assertEquals("claude-3.5-sonnet", agent.config?.model)
        assertEquals(0.7, agent.config?.temperature)
    }

    @Test
    fun `agent starts with OFFLINE status`() {
        val connection = McpConnection("http://localhost:3000")
        val agent = ClaudeCodeAgent(connection)
        
        assertEquals(AgentStatus.OFFLINE, agent.status)
    }

    @Test
    fun `agent has all required capabilities for coding tasks`() {
        val connection = McpConnection("http://localhost:3000")
        val agent = ClaudeCodeAgent(connection)
        
        assertTrue(agent.capabilities.contains(Capability.CODE_GENERATION))
        assertTrue(agent.capabilities.contains(Capability.CODE_REVIEW))
        assertTrue(agent.capabilities.contains(Capability.REFACTORING))
        assertTrue(agent.capabilities.contains(Capability.TEST_WRITING))
    }

    @Test
    fun `all strengths are within valid range`() {
        val connection = McpConnection("http://localhost:3000")
        val agent = ClaudeCodeAgent(connection)
        
        agent.strengths.forEach { strength ->
            assertTrue(strength.score in 0..100, 
                "Strength ${strength.capability} has invalid score: ${strength.score}")
        }
    }

    @Test
    fun `toString includes agent details`() {
        val connection = McpConnection("http://localhost:3000")
        val agent = ClaudeCodeAgent(connection)
        
        val str = agent.toString()
        
        assertTrue(str.contains("claude-code"))
        assertTrue(str.contains("CLAUDE_CODE"))
    }
}
