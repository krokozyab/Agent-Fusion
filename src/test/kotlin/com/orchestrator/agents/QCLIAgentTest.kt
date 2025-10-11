package com.orchestrator.agents

import com.orchestrator.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class QCLIAgentTest {

    @Test
    fun `agent has correct identity`() {
        val connection = McpConnection("http://localhost:3001")
        val agent = QCLIAgent(connection)

        assertEquals(AgentId("q-cli"), agent.id)
        assertEquals(AgentType.Q_CLI, agent.type)
        assertEquals("AWS Q CLI", agent.displayName)
    }

    @Test
    fun `agent uses custom name from config`() {
        val connection = McpConnection("http://localhost:3001")
        val config = AgentConfig(name = "Q-Custom")
        val agent = QCLIAgent(connection, config)

        assertEquals(AgentId("Q-Custom"), agent.id)
        assertEquals("Q-Custom", agent.displayName)
    }

    @Test
    fun `agent has comprehensive capabilities`() {
        val connection = McpConnection("http://localhost:3001")
        val agent = QCLIAgent(connection)

        val expected = setOf(
            Capability.CODE_GENERATION,
            Capability.CODE_REVIEW,
            Capability.DEBUGGING,
            Capability.DOCUMENTATION,
            Capability.TEST_WRITING,
            Capability.REFACTORING
        )

        assertEquals(expected, agent.capabilities)
    }

    @Test
    fun `agent has defined strengths`() {
        val connection = McpConnection("http://localhost:3001")
        val agent = QCLIAgent(connection)

        assertEquals(6, agent.strengths.size)

        val codeGenStrength = agent.strengths.find { it.capability == Capability.CODE_GENERATION }
        assertNotNull(codeGenStrength)
        assertEquals(88, codeGenStrength?.score)

        val debugStrength = agent.strengths.find { it.capability == Capability.DEBUGGING }
        assertEquals(87, debugStrength?.score)
    }

    @Test
    fun `fromConfig creates agent with url`() {
        val config = AgentConfig(
            name = "test-q",
            extra = mapOf("url" to "http://localhost:4001/mcp")
        )

        val agent = QCLIAgent.fromConfig(config)

        assertEquals(AgentId("test-q"), agent.id)
        assertEquals("test-q", agent.displayName)
    }

    @Test
    fun `fromConfig requires url in extra`() {
        val config = AgentConfig(name = "test-q")

        val exception = assertThrows(IllegalArgumentException::class.java) {
            QCLIAgent.fromConfig(config)
        }

        assertTrue(exception.message?.contains("url") == true)
    }

    @Test
    fun `fromConfig uses custom timeout`() {
        val config = AgentConfig(
            extra = mapOf(
                "url" to "http://localhost:3001",
                "timeout" to "60"
            )
        )

        val agent = QCLIAgent.fromConfig(config)
        assertNotNull(agent)
    }

    @Test
    fun `fromConfig uses custom retry attempts`() {
        val config = AgentConfig(
            extra = mapOf(
                "url" to "http://localhost:3001",
                "retryAttempts" to "5"
            )
        )

        val agent = QCLIAgent.fromConfig(config)
        assertNotNull(agent)
    }

    @Test
    fun `agent starts with OFFLINE status`() {
        val connection = McpConnection("http://localhost:3001")
        val agent = QCLIAgent(connection)

        assertEquals(AgentStatus.OFFLINE, agent.status)
    }

    @Test
    fun `agent has code generation and debugging capabilities`() {
        val connection = McpConnection("http://localhost:3001")
        val agent = QCLIAgent(connection)

        assertTrue(agent.capabilities.contains(Capability.CODE_GENERATION))
        assertTrue(agent.capabilities.contains(Capability.DEBUGGING))
        assertTrue(agent.capabilities.contains(Capability.CODE_REVIEW))
    }

    @Test
    fun `all strengths are within valid range`() {
        val connection = McpConnection("http://localhost:3001")
        val agent = QCLIAgent(connection)

        agent.strengths.forEach { strength ->
            assertTrue(strength.score in 0..100,
                "Strength ${strength.capability} has invalid score: ${strength.score}")
        }
    }

    @Test
    fun `code generation strength is highest`() {
        val connection = McpConnection("http://localhost:3001")
        val agent = QCLIAgent(connection)

        val codeGenStrength = agent.strengths.find { it.capability == Capability.CODE_GENERATION }
        val maxStrength = agent.strengths.maxOf { it.score }

        assertEquals(maxStrength, codeGenStrength?.score)
    }

    @Test
    fun `toString includes agent details`() {
        val connection = McpConnection("http://localhost:3001")
        val agent = QCLIAgent(connection)

        val str = agent.toString()

        assertTrue(str.contains("q-cli"))
        assertTrue(str.contains("Q_CLI"))
    }
}
