package com.orchestrator.integration

import com.orchestrator.core.AgentRegistry
import com.orchestrator.domain.*
import com.orchestrator.mcp.tools.CreateConsensusTaskTool
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
 * End-to-end integration test for user directive handling.
 * Tests: force consensus, prevent consensus, agent assignment, emergency bypass.
 */
class UserDirectiveIntegrationTest {
    
    private lateinit var testConfigDir: Path
    private lateinit var agentRegistry: AgentRegistry
    private lateinit var createSimpleTaskTool: CreateSimpleTaskTool
    private lateinit var createConsensusTaskTool: CreateConsensusTaskTool
    
    @BeforeEach
    fun setup() {
        testConfigDir = Files.createTempDirectory("orchestrator-test")
        val agentsFile = testConfigDir.resolve("agents.toml").toFile()
        agentsFile.writeText("""
            [agents.agent-1]
            type = "CODEX_CLI"
            name = "Agent 1"
            model = "gpt-4"
            
            [agents.agent-2]
            type = "CLAUDE_CODE"
            name = "Agent 2"
            model = "claude-3-5-sonnet-20241022"
            
            [agents.agent-3]
            type = "GEMINI"
            name = "Agent 3"
            model = "gemini-pro"
        """.trimIndent())
        
        Database.getConnection()
        agentRegistry = AgentRegistry.fromConfig(testConfigDir.resolve("agents.toml"))
        createSimpleTaskTool = CreateSimpleTaskTool(agentRegistry, RoutingModule(agentRegistry), TaskRepository)
        createConsensusTaskTool = CreateConsensusTaskTool(agentRegistry, RoutingModule(agentRegistry), TaskRepository)
    }
    
    @AfterEach
    fun cleanup() {
        Database.shutdown()
        File("data").deleteRecursively()
        testConfigDir.toFile().deleteRecursively()
    }
    
    @Test
    fun `test allow consensus directive`() {
        // Create simple task and explicitly allow consensus escalation
        val result = createSimpleTaskTool.execute(
            CreateSimpleTaskTool.Params(
                title = "Security-sensitive payment redesign",
                description = "Security critical payment overhaul requiring consensus review",
                type = "IMPLEMENTATION",
                complexity = 8,
                risk = 9,
                directives = CreateSimpleTaskTool.Params.Directives(
                    skipConsensus = false // Allow router to consider consensus
                )
            )
        )

        // Verify task created
        val task = TaskRepository.findById(TaskId(result.taskId))
        assertNotNull(task)

        // Router should select consensus for high risk when not blocked
        assertEquals(RoutingStrategy.CONSENSUS, task.routing)
        assertTrue(task.assigneeIds.size >= 2)
        assertEquals("CONSENSUS", result.routing)
        assertTrue(result.participantAgentIds.size >= 2)
        assertTrue(result.warnings.isEmpty())
    }
    
    @Test
    fun `test prevent consensus directive with simple task`() {
        // Test prevent consensus directive with simple task tool
        val result = createSimpleTaskTool.execute(
            CreateSimpleTaskTool.Params(
                title = "Simple task preventing consensus",
                type = "IMPLEMENTATION",
                complexity = 8,
                risk = 7,
                directives = CreateSimpleTaskTool.Params.Directives(
                    skipConsensus = true // Prevent consensus
                )
            )
        )
        
        // Verify SOLO routing (consensus prevented)
        val task = TaskRepository.findById(TaskId(result.taskId))
        assertNotNull(task)
        assertEquals(RoutingStrategy.SOLO, task.routing)
        assertEquals(1, task.assigneeIds.size)
    }
    
    @Test
    fun `test agent assignment directive`() {
        // Get first agent
        val agents = agentRegistry.getAllAgents()
        assertTrue(agents.isNotEmpty())
        val targetAgent = agents.first()
        
        // Create task with agent assignment
        val result = createSimpleTaskTool.execute(
            CreateSimpleTaskTool.Params(
                title = "Task assigned to specific agent",
                type = "DOCUMENTATION",
                directives = CreateSimpleTaskTool.Params.Directives(
                    assignToAgent = targetAgent.id.value
                )
            )
        )
        
        // Verify assigned to correct agent
        assertEquals(targetAgent.id.value, result.primaryAgentId)
        assertTrue(result.participantAgentIds.contains(targetAgent.id.value))
        
        val task = TaskRepository.findById(TaskId(result.taskId))
        assertNotNull(task)
        assertTrue(task.assigneeIds.contains(targetAgent.id))
    }
    
    @Test
    fun `test emergency bypass directive`() {
        // Create task with emergency flag
        val result = createSimpleTaskTool.execute(
            CreateSimpleTaskTool.Params(
                title = "Emergency security fix",
                type = "BUGFIX",
                complexity = 8,
                risk = 10,
                directives = CreateSimpleTaskTool.Params.Directives(
                    isEmergency = true
                )
            )
        )
        
        // Verify task starts immediately
        assertEquals("IN_PROGRESS", result.status)
        
        val task = TaskRepository.findById(TaskId(result.taskId))
        assertNotNull(task)
        assertEquals(TaskStatus.IN_PROGRESS, task.status)
    }
    
    @Test
    fun `test immediate directive alias`() {
        // Test immediate as alias for isEmergency
        val result = createSimpleTaskTool.execute(
            CreateSimpleTaskTool.Params(
                title = "Immediate task",
                type = "BUGFIX",
                directives = CreateSimpleTaskTool.Params.Directives(
                    immediate = true
                )
            )
        )
        
        // Verify immediate execution
        assertEquals("IN_PROGRESS", result.status)
        
        val task = TaskRepository.findById(TaskId(result.taskId))
        assertNotNull(task)
        assertEquals(TaskStatus.IN_PROGRESS, task.status)
    }
    
    @Test
    fun `test combined directives - agent assignment and emergency`() {
        val agents = agentRegistry.getAllAgents()
        val targetAgent = agents.first()
        
        // Create emergency task assigned to specific agent
        val result = createSimpleTaskTool.execute(
            CreateSimpleTaskTool.Params(
                title = "Emergency task for specific agent",
                type = "BUGFIX",
                complexity = 9,
                risk = 10,
                directives = CreateSimpleTaskTool.Params.Directives(
                    assignToAgent = targetAgent.id.value,
                    isEmergency = true
                )
            )
        )
        
        // Verify both directives applied
        assertEquals("IN_PROGRESS", result.status)
        assertEquals(targetAgent.id.value, result.primaryAgentId)
        
        val task = TaskRepository.findById(TaskId(result.taskId))
        assertNotNull(task)
        assertEquals(TaskStatus.IN_PROGRESS, task.status)
        assertTrue(task.assigneeIds.contains(targetAgent.id))
    }
    
    @Test
    fun `test directive with notes`() {
        // Create task with directive notes
        val result = createSimpleTaskTool.execute(
            CreateSimpleTaskTool.Params(
                title = "Task with notes",
                type = "IMPLEMENTATION",
                directives = CreateSimpleTaskTool.Params.Directives(
                    notes = "This is a high priority task requiring immediate attention"
                )
            )
        )
        
        // Verify task created successfully
        assertNotNull(result.taskId)
        assertEquals("PENDING", result.status)
        
        val task = TaskRepository.findById(TaskId(result.taskId))
        assertNotNull(task)
    }
    
    @Test
    fun `test directive with original text`() {
        // Create task with original text preserved
        val originalText = "Please implement authentication ASAP and assign to agent-1"
        
        val result = createSimpleTaskTool.execute(
            CreateSimpleTaskTool.Params(
                title = "Authentication implementation",
                type = "IMPLEMENTATION",
                directives = CreateSimpleTaskTool.Params.Directives(
                    originalText = originalText,
                    isEmergency = true
                )
            )
        )
        
        // Verify task created with emergency status
        assertEquals("IN_PROGRESS", result.status)
        
        val task = TaskRepository.findById(TaskId(result.taskId))
        assertNotNull(task)
        assertEquals(TaskStatus.IN_PROGRESS, task.status)
    }
    
    @Test
    fun `test routing respects agent assignment over default selection`() {
        val agents = agentRegistry.getAllAgents()
        assertTrue(agents.size >= 2)
        
        // Assign to second agent explicitly
        val targetAgent = agents[1]
        
        val result = createSimpleTaskTool.execute(
            CreateSimpleTaskTool.Params(
                title = "Task for second agent",
                type = "IMPLEMENTATION",
                directives = CreateSimpleTaskTool.Params.Directives(
                    assignToAgent = targetAgent.id.value
                )
            )
        )
        
        // Verify assigned to specified agent, not default
        assertEquals(targetAgent.id.value, result.primaryAgentId)
        
        val task = TaskRepository.findById(TaskId(result.taskId))
        assertNotNull(task)
        assertEquals(1, task.assigneeIds.size)
        assertTrue(task.assigneeIds.contains(targetAgent.id))
    }
    
    @Test
    fun `test consensus task with agent assignment`() {
        val agents = agentRegistry.getAllAgents()
        val targetAgent = agents.first()
        
        // Create consensus task with agent assignment
        val result = createConsensusTaskTool.execute(
            CreateConsensusTaskTool.Params(
                title = "Consensus with preferred agent",
                type = "ARCHITECTURE",
                complexity = 8,
                risk = 7,
                directives = CreateConsensusTaskTool.Params.Directives(
                    assignToAgent = targetAgent.id.value
                )
            )
        )
        
        // Verify consensus routing maintained
        assertEquals("CONSENSUS", result.routing)
        
        // Verify multiple agents assigned (consensus requirement)
        assertTrue(result.participantAgentIds.size >= 2)
        
        val task = TaskRepository.findById(TaskId(result.taskId))
        assertNotNull(task)
        assertEquals(RoutingStrategy.CONSENSUS, task.routing)
        assertTrue(task.assigneeIds.size >= 2)
    }
    
    @Test
    fun `test no directives uses defaults`() {
        // Create task without any directives
        val result = createSimpleTaskTool.execute(
            CreateSimpleTaskTool.Params(
                title = "Task with default behavior",
                type = "IMPLEMENTATION",
                complexity = 5,
                risk = 3
            )
        )
        
        // Verify default behavior
        assertEquals("PENDING", result.status)
        assertEquals("SOLO", result.routing)
        assertNotNull(result.primaryAgentId)
        assertEquals(1, result.participantAgentIds.size)
        
        val task = TaskRepository.findById(TaskId(result.taskId))
        assertNotNull(task)
        assertEquals(TaskStatus.PENDING, task.status)
        assertEquals(RoutingStrategy.SOLO, task.routing)
    }
    
    @Test
    fun `test directive warnings are returned`() {
        // Create simple task with conflicting directive
        val result = createSimpleTaskTool.execute(
            CreateSimpleTaskTool.Params(
                title = "Task forcing solo routing",
                type = "IMPLEMENTATION",
                directives = CreateSimpleTaskTool.Params.Directives(
                    skipConsensus = true // Explicitly opt out of consensus
                )
            )
        )

        // Verify warnings present
        assertTrue(result.warnings.isNotEmpty())
        assertTrue(result.warnings.any { it.contains("skipConsensus=true") })
    }
    
    @Test
    fun `test multiple tasks with different directives`() {
        val agents = agentRegistry.getAllAgents()
        
        // Task 1: Emergency
        val task1 = createSimpleTaskTool.execute(
            CreateSimpleTaskTool.Params(
                title = "Emergency task",
                directives = CreateSimpleTaskTool.Params.Directives(isEmergency = true)
            )
        )
        assertEquals("IN_PROGRESS", task1.status)
        
        // Task 2: Assigned agent
        val task2 = createSimpleTaskTool.execute(
            CreateSimpleTaskTool.Params(
                title = "Assigned task",
                directives = CreateSimpleTaskTool.Params.Directives(
                    assignToAgent = agents.first().id.value
                )
            )
        )
        assertEquals(agents.first().id.value, task2.primaryAgentId)
        
        // Task 3: Default
        val task3 = createSimpleTaskTool.execute(
            CreateSimpleTaskTool.Params(title = "Default task")
        )
        assertEquals("PENDING", task3.status)
        
        // Verify all tasks created independently
        assertNotNull(TaskRepository.findById(TaskId(task1.taskId)))
        assertNotNull(TaskRepository.findById(TaskId(task2.taskId)))
        assertNotNull(TaskRepository.findById(TaskId(task3.taskId)))
    }
}
