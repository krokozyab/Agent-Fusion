package com.orchestrator.agents

import com.orchestrator.domain.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration

class McpAgentTest {

    private class TestMcpAgent(
        connection: McpConnection,
        override val id: AgentId = AgentId("test-agent"),
        override val type: AgentType = AgentType.CUSTOM,
        override val displayName: String = "Test Agent",
        override val capabilities: Set<Capability> = setOf(Capability.CODE_GENERATION),
        override val strengths: List<Strength> = emptyList(),
        config: AgentConfig? = null,
        private val mockResponse: Map<String, Any> = mapOf("result" to "success")
    ) : McpAgent(connection, config) {
        
        var lastMethod: String? = null
        var lastParams: Map<String, Any>? = null
        var shouldFail: Boolean = false
        
        override suspend fun sendMcpRequest(
            method: String,
            params: Map<String, Any>
        ): Map<String, Any> {
            lastMethod = method
            lastParams = params
            
            if (shouldFail) {
                throw RuntimeException("Simulated failure")
            }
            
            return mockResponse
        }
        
        // Expose protected methods for testing
        fun setStatus(status: AgentStatus) = updateStatus(status)
        suspend fun <T> testRetry(block: suspend () -> T) = sendWithRetry(block)
    }

    @Test
    fun `McpConnection validates parameters`() {
        assertThrows(IllegalArgumentException::class.java) {
            McpConnection(url = "")
        }
        
        assertThrows(IllegalArgumentException::class.java) {
            McpConnection(url = "http://localhost", timeout = Duration.ofMillis(-1))
        }
        
        assertThrows(IllegalArgumentException::class.java) {
            McpConnection(url = "http://localhost", retryAttempts = -1)
        }
    }

    @Test
    fun `McpConnection has sensible defaults`() {
        val conn = McpConnection(url = "http://localhost:3000")
        
        assertEquals("http://localhost:3000", conn.url)
        assertEquals(Duration.ofSeconds(30), conn.timeout)
        assertEquals(3, conn.retryAttempts)
        assertEquals(Duration.ofMillis(500), conn.retryDelay)
    }

    @Test
    fun `agent starts with OFFLINE status`() {
        val conn = McpConnection(url = "http://localhost:3000")
        val agent = TestMcpAgent(conn)
        
        assertEquals(AgentStatus.OFFLINE, agent.status)
    }

    @Test
    fun `updateStatus changes agent status`() {
        val conn = McpConnection(url = "http://localhost:3000")
        val agent = TestMcpAgent(conn)
        
        agent.setStatus(AgentStatus.ONLINE)
        assertEquals(AgentStatus.ONLINE, agent.status)
        
        agent.setStatus(AgentStatus.BUSY)
        assertEquals(AgentStatus.BUSY, agent.status)
    }

    @Test
    fun `executeTask sends MCP request`() = runBlocking {
        val conn = McpConnection(url = "http://localhost:3000")
        val agent = TestMcpAgent(conn)
        
        val taskId = TaskId("task-1")
        val result = agent.executeTask(taskId, "Test task")
        
        assertEquals("success", result)
        assertEquals("execute_task", agent.lastMethod)
        assertEquals(taskId.value, agent.lastParams?.get("taskId"))
        assertEquals("Test task", agent.lastParams?.get("description"))
    }

    @Test
    fun `executeTask updates status to BUSY then ONLINE`() = runBlocking {
        val conn = McpConnection(url = "http://localhost:3000")
        val agent = TestMcpAgent(conn)
        agent.setStatus(AgentStatus.ONLINE)
        
        agent.executeTask(TaskId("task-1"), "Test")
        
        assertEquals(AgentStatus.ONLINE, agent.status)
    }

    @Test
    fun `executeTask sets status to OFFLINE on failure`() = runBlocking {
        val conn = McpConnection(url = "http://localhost:3000", retryAttempts = 0)
        val agent = TestMcpAgent(conn)
        agent.shouldFail = true
        
        assertThrows(RuntimeException::class.java) {
            runBlocking {
                agent.executeTask(TaskId("task-1"), "Test")
            }
        }
        
        assertEquals(AgentStatus.OFFLINE, agent.status)
    }

    @Test
    fun `sendWithRetry retries on failure`() = runBlocking {
        val conn = McpConnection(url = "http://localhost:3000", retryAttempts = 2, retryDelay = Duration.ofMillis(10))
        val agent = TestMcpAgent(conn)
        
        var attempts = 0
        
        assertThrows(RuntimeException::class.java) {
            runBlocking {
                agent.testRetry {
                    attempts++
                    throw RuntimeException("Fail")
                }
            }
        }
        
        assertEquals(3, attempts) // Initial + 2 retries
    }

    @Test
    fun `sendWithRetry succeeds on retry`() = runBlocking {
        val conn = McpConnection(url = "http://localhost:3000", retryAttempts = 2, retryDelay = Duration.ofMillis(10))
        val agent = TestMcpAgent(conn)
        
        var attempts = 0
        val result = agent.testRetry {
            attempts++
            if (attempts < 2) throw RuntimeException("Fail")
            mapOf("result" to "success")
        }
        
        assertEquals(2, attempts)
        assertEquals("success", result["result"])
    }

    @Test
    fun `getInfo sends get_info request`() = runBlocking {
        val conn = McpConnection(url = "http://localhost:3000")
        val mockInfo = mapOf("name" to "Test Agent", "version" to "1.0")
        val agent = TestMcpAgent(conn, mockResponse = mockInfo)
        
        val info = agent.getInfo()
        
        assertEquals("get_info", agent.lastMethod)
        assertEquals(mockInfo, info)
    }

    @Test
    fun `executeTask includes context in params`() = runBlocking {
        val conn = McpConnection(url = "http://localhost:3000")
        val agent = TestMcpAgent(conn)
        
        val context = mapOf("key1" to "value1", "key2" to "value2")
        agent.executeTask(TaskId("task-1"), "Test", context)
        
        assertEquals(context, agent.lastParams?.get("context"))
    }

    @Test
    fun `toString includes agent details`() {
        val conn = McpConnection(url = "http://localhost:3000")
        val agent = TestMcpAgent(conn)
        
        val str = agent.toString()
        
        assertTrue(str.contains("test-agent"))
        assertTrue(str.contains("CUSTOM"))
        assertTrue(str.contains("http://localhost:3000"))
    }

    @Test
    fun `agent properties are accessible`() {
        val conn = McpConnection(url = "http://localhost:3000")
        val config = AgentConfig(name = "Test Config")
        val capabilities = setOf(Capability.CODE_GENERATION, Capability.CODE_REVIEW)
        val strengths = listOf(Strength(Capability.CODE_GENERATION, 90))
        
        val agent = TestMcpAgent(
            connection = conn,
            id = AgentId("agent-1"),
            type = AgentType.CLAUDE_CODE,
            displayName = "Claude Agent",
            capabilities = capabilities,
            strengths = strengths,
            config = config
        )
        
        assertEquals(AgentId("agent-1"), agent.id)
        assertEquals(AgentType.CLAUDE_CODE, agent.type)
        assertEquals("Claude Agent", agent.displayName)
        assertEquals(capabilities, agent.capabilities)
        assertEquals(strengths, agent.strengths)
        assertEquals(config, agent.config)
    }

    @Test
    fun `HealthCheckResult captures result data`() {
        val result = HealthCheckResult(
            healthy = true,
            latencyMs = 150
        )
        
        assertTrue(result.healthy)
        assertEquals(150, result.latencyMs)
        assertNull(result.error)
        assertNotNull(result.timestamp)
    }

    @Test
    fun `HealthCheckResult captures error`() {
        val result = HealthCheckResult(
            healthy = false,
            latencyMs = 5000,
            error = "Connection timeout"
        )
        
        assertFalse(result.healthy)
        assertEquals(5000, result.latencyMs)
        assertEquals("Connection timeout", result.error)
    }
}
