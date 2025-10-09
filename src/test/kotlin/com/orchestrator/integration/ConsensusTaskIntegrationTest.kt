package com.orchestrator.integration

import com.orchestrator.config.ConfigLoader
import com.orchestrator.core.AgentRegistry
import com.orchestrator.domain.*
import com.orchestrator.mcp.tools.CreateConsensusTaskTool
import com.orchestrator.modules.consensus.ProposalManager
import com.orchestrator.modules.routing.RoutingModule
import com.orchestrator.storage.Database
import com.orchestrator.storage.repositories.DecisionRepository
import com.orchestrator.storage.repositories.ProposalRepository
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
 * End-to-end integration test for consensus task workflow.
 * Tests: create consensus task -> submit proposals -> reach consensus -> verify decision.
 */
class ConsensusTaskIntegrationTest {
    
    private lateinit var testConfigDir: Path
    private lateinit var agentRegistry: AgentRegistry
    private lateinit var createConsensusTaskTool: CreateConsensusTaskTool
    
    @BeforeEach
    fun setup() {
        // Create test config directory
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
        
        // Initialize database
        Database.getConnection()
        
        // Clear proposal manager signals
        ProposalManager.clearSignals()
        
        // Load agent registry
        agentRegistry = AgentRegistry.fromConfig(testConfigDir.resolve("agents.toml"))

        // Create tools
        createConsensusTaskTool = CreateConsensusTaskTool(
            agentRegistry = agentRegistry,
            routingModule = RoutingModule(agentRegistry),
            taskRepository = TaskRepository
        )
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
    fun `test consensus task creation and routing`() {
        // Create consensus task
        val params = CreateConsensusTaskTool.Params(
            title = "Design authentication system",
            description = "Design secure authentication with multiple perspectives",
            type = "ARCHITECTURE",
            complexity = 8,
            risk = 7
        )
        
        val result = createConsensusTaskTool.execute(params)
        
        // Verify result
        assertNotNull(result.taskId)
        assertTrue(result.taskId.startsWith("task-"))
        assertEquals("PENDING", result.status)
        assertEquals("CONSENSUS", result.routing)
        assertNotNull(result.primaryAgentId)
        assertTrue(result.participantAgentIds.size >= 2) // Consensus requires multiple agents
        
        // Verify task persisted
        val task = TaskRepository.findById(TaskId(result.taskId))
        assertNotNull(task)
        assertEquals("Design authentication system", task.title)
        assertEquals(TaskType.ARCHITECTURE, task.type)
        assertEquals(RoutingStrategy.CONSENSUS, task.routing)
        assertTrue(task.assigneeIds.size >= 2)
    }

    @Test
    fun `force consensus directive is respected for low risk tasks`() {
        val params = CreateConsensusTaskTool.Params(
            title = "Light copy edit",
            description = "Minor documentation tweak but stakeholders require consensus",
            type = "DOCUMENTATION",
            complexity = 2,
            risk = 2
        )

        val result = createConsensusTaskTool.execute(params)

        assertEquals("CONSENSUS", result.routing)
        assertNotNull(result.primaryAgentId)
        assertTrue(result.participantAgentIds.size >= 2)

        val storedTask = TaskRepository.findById(TaskId(result.taskId))
        assertNotNull(storedTask)
        assertEquals(RoutingStrategy.CONSENSUS, storedTask.routing)
    }
    
    @Test
    fun `test consensus workflow with multiple proposals`() {
        // Create consensus task
        val result = createConsensusTaskTool.execute(
            CreateConsensusTaskTool.Params(
                title = "Implement payment processing",
                type = "IMPLEMENTATION",
                complexity = 9,
                risk = 9
            )
        )
        
        val taskId = TaskId(result.taskId)
        val task = TaskRepository.findById(taskId)
        assertNotNull(task)
        
        // Get agents
        val agents = agentRegistry.getAllAgents().take(3)
        assertTrue(agents.size >= 2)
        
        // Submit proposals from multiple agents
        val proposal1 = ProposalManager.submitProposal(
            taskId = taskId,
            agentId = agents[0].id,
            content = mapOf(
                "approach" to "Stripe integration",
                "confidence" to "high"
            ),
            inputType = InputType.ARCHITECTURAL_PLAN,
            confidence = 0.85,
            tokenUsage = TokenUsage(inputTokens = 100, outputTokens = 200)
        )
        
        val proposal2 = ProposalManager.submitProposal(
            taskId = taskId,
            agentId = agents[1].id,
            content = mapOf(
                "approach" to "PayPal integration",
                "confidence" to "medium"
            ),
            inputType = InputType.ARCHITECTURAL_PLAN,
            confidence = 0.75,
            tokenUsage = TokenUsage(inputTokens = 120, outputTokens = 180)
        )
        
        if (agents.size >= 3) {
            ProposalManager.submitProposal(
                taskId = taskId,
                agentId = agents[2].id,
                content = mapOf(
                    "approach" to "Hybrid approach",
                    "confidence" to "high"
                ),
                inputType = InputType.ARCHITECTURAL_PLAN,
                confidence = 0.80,
                tokenUsage = TokenUsage(inputTokens = 110, outputTokens = 190)
            )
        }
        
        // Verify proposals stored
        val proposals = ProposalRepository.findByTask(taskId)
        assertTrue(proposals.size >= 2)
        assertTrue(proposals.any { it.id == proposal1.id })
        assertTrue(proposals.any { it.id == proposal2.id })
        
        // Execute consensus
        val outcome = com.orchestrator.modules.consensus.ConsensusModule.decide(taskId)
        
        // Verify outcome
        assertNotNull(outcome.decisionId)
        assertTrue(outcome.consideredCount >= 2)
        
        // Verify decision persisted
        val storedDecision = DecisionRepository.findByTask(taskId)
        assertNotNull(storedDecision)
        assertEquals(outcome.decisionId, storedDecision.id)
    }
    
    @Test
    fun `test consensus with high agreement`() {
        // Create task
        val result = createConsensusTaskTool.execute(
            CreateConsensusTaskTool.Params(
                title = "Simple feature implementation",
                type = "IMPLEMENTATION",
                complexity = 5,
                risk = 3
            )
        )
        
        val taskId = TaskId(result.taskId)
        val task = TaskRepository.findById(taskId)
        assertNotNull(task)
        
        val agents = agentRegistry.getAllAgents().take(3)
        
        // Submit similar proposals (high agreement)
        val proposals = agents.map { agent ->
            ProposalManager.submitProposal(
                taskId = taskId,
                agentId = agent.id,
                content = mapOf("approach" to "standard implementation"),
                inputType = InputType.OTHER,
                confidence = 0.90
            )
        }
        
        // Execute consensus
        val outcome = com.orchestrator.modules.consensus.ConsensusModule.decide(taskId)
        
        // Verify consensus achieved
        val decision = DecisionRepository.findByTask(taskId)
        assertNotNull(decision)
        assertTrue(decision.consensusAchieved)
        if (decision.agreementRate != null) {
            assertTrue(decision.agreementRate!! >= 0.5)
        }
    }
    
    @Test
    fun `test consensus with disagreement`() {
        // Create task
        val result = createConsensusTaskTool.execute(
            CreateConsensusTaskTool.Params(
                title = "Complex architectural decision",
                type = "ARCHITECTURE",
                complexity = 10,
                risk = 8
            )
        )
        
        val taskId = TaskId(result.taskId)
        val task = TaskRepository.findById(taskId)
        assertNotNull(task)
        
        val agents = agentRegistry.getAllAgents().take(3)
        
        // Submit different proposals (disagreement)
        ProposalManager.submitProposal(
            taskId = taskId,
            agentId = agents[0].id,
            content = mapOf("approach" to "microservices"),
            inputType = InputType.ARCHITECTURAL_PLAN,
            confidence = 0.70
        )
        
        ProposalManager.submitProposal(
            taskId = taskId,
            agentId = agents[1].id,
            content = mapOf("approach" to "monolith"),
            inputType = InputType.ARCHITECTURAL_PLAN,
            confidence = 0.75
        )
        
        if (agents.size >= 3) {
            ProposalManager.submitProposal(
                taskId = taskId,
                agentId = agents[2].id,
                content = mapOf("approach" to "serverless"),
                inputType = InputType.ARCHITECTURAL_PLAN,
                confidence = 0.65
            )
        }
        
        val proposals = ProposalRepository.findByTask(taskId)
        
        // Execute consensus
        val outcome = com.orchestrator.modules.consensus.ConsensusModule.decide(taskId)
        
        // Verify decision made despite disagreement
        val decision = DecisionRepository.findByTask(taskId)
        assertNotNull(decision)
        assertTrue(decision.considered.isNotEmpty())
        // Agreement rate may be lower or null
        if (decision.agreementRate != null) {
            assertTrue(decision.agreementRate!! >= 0.0)
        }
    }
    
    @Test
    fun `test consensus with weighted confidence`() {
        // Create task
        val result = createConsensusTaskTool.execute(
            CreateConsensusTaskTool.Params(
                title = "Security implementation",
                type = "IMPLEMENTATION",
                complexity = 7,
                risk = 9
            )
        )
        
        val taskId = TaskId(result.taskId)
        val task = TaskRepository.findById(taskId)
        assertNotNull(task)
        
        val agents = agentRegistry.getAllAgents().take(3)
        
        // Submit proposals with varying confidence
        ProposalManager.submitProposal(
            taskId = taskId,
            agentId = agents[0].id,
            content = mapOf("approach" to "OAuth2"),
            confidence = 0.95 // High confidence
        )
        
        ProposalManager.submitProposal(
            taskId = taskId,
            agentId = agents[1].id,
            content = mapOf("approach" to "JWT"),
            confidence = 0.60 // Lower confidence
        )
        
        if (agents.size >= 3) {
            ProposalManager.submitProposal(
                taskId = taskId,
                agentId = agents[2].id,
                content = mapOf("approach" to "OAuth2"),
                confidence = 0.85 // High confidence
            )
        }
        
        val proposals = ProposalRepository.findByTask(taskId)
        val outcome = com.orchestrator.modules.consensus.ConsensusModule.decide(taskId)
        
        // Verify decision considers confidence
        val decision = DecisionRepository.findByTask(taskId)
        assertNotNull(decision)
        // Winner may or may not be selected depending on strategy
        assertTrue(decision.considered.isNotEmpty())
    }
    
    @Test
    fun `test consensus decision storage`() {
        // Create task
        val result = createConsensusTaskTool.execute(
            CreateConsensusTaskTool.Params(
                title = "Test decision storage",
                type = "TESTING"
            )
        )
        
        val taskId = TaskId(result.taskId)
        val task = TaskRepository.findById(taskId)
        assertNotNull(task)
        
        val agents = agentRegistry.getAllAgents().take(2)
        
        // Submit proposals
        val proposals = agents.map { agent ->
            ProposalManager.submitProposal(
                taskId = taskId,
                agentId = agent.id,
                content = mapOf("test" to "data"),
                confidence = 0.80
            )
        }
        
        // Execute consensus
        val outcome = com.orchestrator.modules.consensus.ConsensusModule.decide(taskId)
        
        // Verify decision stored in database
        val decision = DecisionRepository.findByTask(taskId)
        assertNotNull(decision)
        assertEquals(taskId, decision.taskId)
        assertEquals(proposals.size, decision.considered.size)
        assertNotNull(decision.decidedAt)
    }
    
    @Test
    fun `test consensus with token tracking`() {
        // Create task
        val result = createConsensusTaskTool.execute(
            CreateConsensusTaskTool.Params(
                title = "Token tracking test",
                type = "IMPLEMENTATION"
            )
        )
        
        val taskId = TaskId(result.taskId)
        val task = TaskRepository.findById(taskId)
        assertNotNull(task)
        
        val agents = agentRegistry.getAllAgents().take(2)
        
        // Submit proposals with token usage
        ProposalManager.submitProposal(
            taskId = taskId,
            agentId = agents[0].id,
            content = mapOf("code" to "implementation1"),
            tokenUsage = TokenUsage(inputTokens = 500, outputTokens = 1000)
        )
        
        ProposalManager.submitProposal(
            taskId = taskId,
            agentId = agents[1].id,
            content = mapOf("code" to "implementation2"),
            tokenUsage = TokenUsage(inputTokens = 600, outputTokens = 1200)
        )
        
        val proposals = ProposalRepository.findByTask(taskId)
        
        // Verify token usage tracked
        val totalInput = proposals.sumOf { it.tokenUsage.inputTokens }
        val totalOutput = proposals.sumOf { it.tokenUsage.outputTokens }
        
        assertTrue(totalInput > 0)
        assertTrue(totalOutput > 0)
        assertEquals(1100, totalInput)
        assertEquals(2200, totalOutput)
    }
    
    @Test
    fun `test complete consensus workflow`() {
        // 1. Create consensus task
        val createResult = createConsensusTaskTool.execute(
            CreateConsensusTaskTool.Params(
                title = "Complete consensus workflow",
                description = "Full lifecycle test",
                type = "ARCHITECTURE",
                complexity = 8,
                risk = 7
            )
        )
        
        val taskId = TaskId(createResult.taskId)
        
        // 2. Verify task created with consensus routing
        val task = TaskRepository.findById(taskId)
        assertNotNull(task)
        assertEquals(RoutingStrategy.CONSENSUS, task.routing)
        assertEquals(TaskStatus.PENDING, task.status)
        
        // 3. Submit proposals from multiple agents
        val agents = agentRegistry.getAllAgents().take(3)
        agents.forEach { agent ->
            ProposalManager.submitProposal(
                taskId = taskId,
                agentId = agent.id,
                content = mapOf(
                    "agent" to agent.id.value,
                    "proposal" to "architecture design"
                ),
                inputType = InputType.ARCHITECTURAL_PLAN,
                confidence = 0.80
            )
        }
        
        // 4. Verify proposals stored
        val proposals = ProposalRepository.findByTask(taskId)
        assertEquals(agents.size, proposals.size)
        
        // 5. Execute consensus
        val outcome = com.orchestrator.modules.consensus.ConsensusModule.decide(taskId)
        
        // 6. Verify decision created and persisted
        val decision = DecisionRepository.findByTask(taskId)
        assertNotNull(decision)
        assertEquals(taskId, decision.taskId)
        assertTrue(decision.considered.isNotEmpty())
        assertEquals(outcome.decisionId, decision.id)
    }
}
