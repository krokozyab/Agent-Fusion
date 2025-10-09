package com.orchestrator.integration

import com.orchestrator.config.ConfigLoader
import com.orchestrator.core.AgentRegistry
import com.orchestrator.domain.*
import com.orchestrator.mcp.tools.CreateSimpleTaskTool
import com.orchestrator.modules.routing.RoutingModule
import com.orchestrator.storage.Database
import com.orchestrator.storage.repositories.TaskRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end integration test for simple task workflow.
 * Tests the complete flow: create task -> route -> persist -> verify.
 */
class SimpleTaskIntegrationTest {
    
    private lateinit var testConfigDir: Path
    private lateinit var agentRegistry: AgentRegistry
    private lateinit var createSimpleTaskTool: CreateSimpleTaskTool
    
    @BeforeEach
    fun setup() {
        // Create test config directory
        testConfigDir = Files.createTempDirectory("orchestrator-test")
        val agentsFile = testConfigDir.resolve("agents.toml").toFile()
        agentsFile.writeText("""
            [agents.test-agent-1]
            type = "CODEX_CLI"
            name = "Test Agent 1"
            model = "gpt-4"
            
            [agents.test-agent-2]
            type = "CLAUDE_CODE"
            name = "Test Agent 2"
            model = "claude-3-5-sonnet-20241022"
        """.trimIndent())
        
        // Initialize database
        Database.getConnection()
        
        // Load agent registry
        agentRegistry = AgentRegistry.fromConfig(testConfigDir.resolve("agents.toml"))
        
        // Create tool
        createSimpleTaskTool = CreateSimpleTaskTool(agentRegistry, RoutingModule(agentRegistry), TaskRepository)
    }
    
    @AfterEach
    fun cleanup() {
        // Cleanup database
        Database.shutdown()
        
        // Cleanup test files
        File("data").deleteRecursively()
        testConfigDir.toFile().deleteRecursively()
    }
    
    @Test
    fun `test simple task creation and routing`() {
        // Create simple task
        val params = CreateSimpleTaskTool.Params(
            title = "Implement logging improvements",
            description = "Add structured logging and request tracing to the API",
            type = "IMPLEMENTATION",
            complexity = 5,
            risk = 3
        )
        
        val result = createSimpleTaskTool.execute(params)
        
        // Verify result
        assertNotNull(result.taskId)
        assertTrue(result.taskId.startsWith("task-"))
        assertEquals("PENDING", result.status)
        assertEquals("SOLO", result.routing)
        assertNotNull(result.primaryAgentId)
        assertEquals(1, result.participantAgentIds.size)
        
        // Verify task persisted in database
        val taskId = TaskId(result.taskId)
        val task = TaskRepository.findById(taskId)
        
        assertNotNull(task)
        assertEquals("Implement logging improvements", task.title)
        assertEquals("Add structured logging and request tracing to the API", task.description)
        assertEquals(TaskType.IMPLEMENTATION, task.type)
        assertEquals(TaskStatus.PENDING, task.status)
        assertEquals(RoutingStrategy.SOLO, task.routing)
        assertEquals(5, task.complexity)
        assertEquals(3, task.risk)
        assertEquals(1, task.assigneeIds.size)
    }
    
    @Test
    fun `test simple task with emergency directive`() {
        // Create emergency task
        val params = CreateSimpleTaskTool.Params(
            title = "Fix critical security vulnerability",
            description = "Patch SQL injection vulnerability",
            type = "BUGFIX",
            complexity = 8,
            risk = 10,
            directives = CreateSimpleTaskTool.Params.Directives(
                isEmergency = true
            )
        )
        
        val result = createSimpleTaskTool.execute(params)
        
        // Verify result
        assertNotNull(result.taskId)
        assertEquals("IN_PROGRESS", result.status) // Emergency tasks start immediately
        assertEquals("SOLO", result.routing)
        
        // Verify in database
        val task = TaskRepository.findById(TaskId(result.taskId))
        assertNotNull(task)
        assertEquals(TaskStatus.IN_PROGRESS, task.status)
        assertEquals(10, task.risk)
    }
    
    @Test
    fun `test simple task with assigned agent`() {
        // Get available agents
        val agents = agentRegistry.getAllAgents()
        assertTrue(agents.isNotEmpty())
        val targetAgent = agents.first()
        
        // Create task assigned to specific agent
        val params = CreateSimpleTaskTool.Params(
            title = "Write documentation",
            type = "DOCUMENTATION",
            complexity = 2,
            risk = 1,
            directives = CreateSimpleTaskTool.Params.Directives(
                assignToAgent = targetAgent.id.value
            )
        )
        
        val result = createSimpleTaskTool.execute(params)
        
        // Verify assigned to correct agent
        assertEquals(targetAgent.id.value, result.primaryAgentId)
        assertTrue(result.participantAgentIds.contains(targetAgent.id.value))
        
        // Verify in database
        val task = TaskRepository.findById(TaskId(result.taskId))
        assertNotNull(task)
        assertTrue(task.assigneeIds.contains(targetAgent.id))
    }
    
    @Test
    fun `test task query by status`() {
        // Create multiple tasks
        val task1 = createSimpleTaskTool.execute(
            CreateSimpleTaskTool.Params(
                title = "Task 1",
                type = "IMPLEMENTATION"
            )
        )
        
        val task2 = createSimpleTaskTool.execute(
            CreateSimpleTaskTool.Params(
                title = "Task 2",
                type = "REVIEW"
            )
        )
        
        // Query by status
        val pendingTasks = TaskRepository.findByStatus(TaskStatus.PENDING)
        
        assertTrue(pendingTasks.size >= 2)
        assertTrue(pendingTasks.any { it.id.value == task1.taskId })
        assertTrue(pendingTasks.any { it.id.value == task2.taskId })
    }
    
    @Test
    fun `test task query by agent`() {
        // Get first agent
        val agents = agentRegistry.getAllAgents()
        val agent = agents.first()
        
        // Create task assigned to agent
        val result = createSimpleTaskTool.execute(
            CreateSimpleTaskTool.Params(
                title = "Agent-specific task",
                directives = CreateSimpleTaskTool.Params.Directives(
                    assignToAgent = agent.id.value
                )
            )
        )
        
        // Query by agent
        val agentTasks = TaskRepository.findByAgent(agent.id)
        
        assertTrue(agentTasks.isNotEmpty())
        assertTrue(agentTasks.any { it.id.value == result.taskId })
    }
    
    @Test
    fun `test task update workflow`() {
        // Create task
        val result = createSimpleTaskTool.execute(
            CreateSimpleTaskTool.Params(
                title = "Original title",
                type = "IMPLEMENTATION"
            )
        )
        
        // Retrieve task
        val taskId = TaskId(result.taskId)
        val task = TaskRepository.findById(taskId)
        assertNotNull(task)
        
        // Update task status
        val updatedTask = task.copy(
            status = TaskStatus.IN_PROGRESS,
            updatedAt = java.time.Instant.now()
        )
        TaskRepository.update(updatedTask)
        
        // Verify update
        val retrieved = TaskRepository.findById(taskId)
        assertNotNull(retrieved)
        assertEquals(TaskStatus.IN_PROGRESS, retrieved.status)
        assertNotNull(retrieved.updatedAt)
    }
    
    @Test
    fun `test task with metadata`() {
        // Create task with metadata
        val metadata = mapOf(
            "priority" to "high",
            "team" to "backend",
            "sprint" to "2024-Q4"
        )
        
        val result = createSimpleTaskTool.execute(
            CreateSimpleTaskTool.Params(
                title = "Task with metadata",
                metadata = metadata
            )
        )
        
        // Verify metadata persisted
        val task = TaskRepository.findById(TaskId(result.taskId))
        assertNotNull(task)
        assertEquals(3, task.metadata.size)
        assertEquals("high", task.metadata["priority"])
        assertEquals("backend", task.metadata["team"])
        assertEquals("2024-Q4", task.metadata["sprint"])
    }
    
    @Test
    fun `test task deletion`() {
        // Create task
        val result = createSimpleTaskTool.execute(
            CreateSimpleTaskTool.Params(title = "Task to delete")
        )
        
        val taskId = TaskId(result.taskId)
        
        // Verify exists
        assertNotNull(TaskRepository.findById(taskId))
        
        // Delete
        TaskRepository.delete(taskId)
        
        // Verify deleted
        val deleted = TaskRepository.findById(taskId)
        assertEquals(null, deleted)
    }
    
    @Test
    fun `test complete workflow - create, route, execute, complete`() {
        // 1. Create task
        val createResult = createSimpleTaskTool.execute(
            CreateSimpleTaskTool.Params(
                title = "Complete workflow test",
                description = "Test full task lifecycle",
                type = "IMPLEMENTATION",
                complexity = 5,
                risk = 3
            )
        )
        
        val taskId = TaskId(createResult.taskId)
        
        // 2. Verify routing
        val task = TaskRepository.findById(taskId)
        assertNotNull(task)
        assertEquals(RoutingStrategy.SOLO, task.routing)
        assertEquals(TaskStatus.PENDING, task.status)
        assertTrue(task.assigneeIds.isNotEmpty())
        
        // 3. Simulate execution - update to IN_PROGRESS
        val inProgress = task.copy(
            status = TaskStatus.IN_PROGRESS,
            updatedAt = java.time.Instant.now()
        )
        TaskRepository.update(inProgress)
        
        val executing = TaskRepository.findById(taskId)
        assertNotNull(executing)
        assertEquals(TaskStatus.IN_PROGRESS, executing.status)
        
        // 4. Simulate completion
        val completed = executing.copy(
            status = TaskStatus.COMPLETED,
            updatedAt = java.time.Instant.now()
        )
        TaskRepository.update(completed)
        
        val final = TaskRepository.findById(taskId)
        assertNotNull(final)
        assertEquals(TaskStatus.COMPLETED, final.status)
        assertNotNull(final.updatedAt)
    }
}
