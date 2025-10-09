package com.orchestrator.modules.metrics

import com.orchestrator.core.EventBus
import com.orchestrator.domain.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class MetricsModuleTest {
    
    @AfterEach
    fun cleanup() {
        MetricsModule.global.stop()
        MetricsModule.global.reset()
    }
    
    @Test
    fun `should get current metrics snapshot`() {
        val module = MetricsModule()
        
        // Generate some metrics
        TokenTracker.track(TokenRecord(TaskId("task-1"), AgentId("agent-1"), 100, 200))
        PerformanceMonitor.trackTaskCompletion(TaskId("task-1"), Duration.ofSeconds(5), true)
        
        val snapshot = module.getSnapshot()
        
        assertNotNull(snapshot)
        assertEquals(300, snapshot.tokenUsage.totalTokens)
        assertTrue(snapshot.performance.overallSuccessRate > 0)
    }
    
    @Test
    fun `should get metrics snapshot for time range`() {
        val module = MetricsModule()
        val now = Instant.now()
        val past = now.minusSeconds(3600)
        
        TokenTracker.track(TokenRecord(TaskId("task-1"), AgentId("agent-1"), 100, 200))
        
        val snapshot = module.getSnapshot(past, now)
        
        assertNotNull(snapshot)
        assertEquals(300, snapshot.tokenUsage.totalTokens)
    }
    
    @Test
    fun `should track task execution with all metrics`() {
        val module = MetricsModule()
        
        val task = createTask()
        val agent = createAgent()
        val proposal = createProposal()
        val decision = createDecision()
        
        module.trackTaskExecution(
            task = task,
            agent = agent,
            proposal = proposal,
            decision = decision,
            duration = Duration.ofSeconds(5),
            success = true
        )
        
        val snapshot = module.getSnapshot()
        
        assertTrue(snapshot.tokenUsage.totalTokens > 0)
        assertTrue(snapshot.performance.overallSuccessRate > 0)
    }
    
    @Test
    fun `should track agent response`() {
        val module = MetricsModule()
        
        module.trackAgentResponse(
            taskId = TaskId("task-1"),
            agentId = AgentId("agent-1"),
            duration = Duration.ofSeconds(2),
            success = true
        )
        
        val snapshot = module.getSnapshot()
        assertEquals(1.0, snapshot.performance.overallSuccessRate)
    }
    
    @Test
    fun `should generate unified summary`() {
        val module = MetricsModule()
        
        TokenTracker.track(TokenRecord(TaskId("task-1"), AgentId("agent-1"), 100, 200))
        PerformanceMonitor.trackTaskCompletion(TaskId("task-1"), Duration.ofSeconds(5), true)
        
        val summary = module.getSummary()
        
        assertTrue(summary.contains("Metrics Summary"))
        assertTrue(summary.contains("Tokens:"))
        assertTrue(summary.contains("Performance:"))
        assertTrue(summary.contains("Decisions:"))
        assertTrue(summary.contains("Alerts:"))
    }
    
    @Test
    fun `should configure alert thresholds`() {
        val module = MetricsModule()
        
        module.configureAlerts(
            tokenBudget = 5000.0,
            failureRateThreshold = 0.5,
            performanceThreshold = 15000.0
        )
        
        val tokenThreshold = AlertSystem.getThreshold(AlertType.TOKEN_BUDGET_EXCEEDED)
        val failureThreshold = AlertSystem.getThreshold(AlertType.HIGH_FAILURE_RATE)
        val perfThreshold = AlertSystem.getThreshold(AlertType.PERFORMANCE_DEGRADATION)
        
        assertEquals(5000.0, tokenThreshold?.threshold)
        assertEquals(0.5, failureThreshold?.threshold)
        assertEquals(15000.0, perfThreshold?.threshold)
    }
    
    @Test
    fun `should set event bus`() {
        val module = MetricsModule()
        val eventBus = EventBus()
        
        module.setEventBus(eventBus)
        
        // Trigger alert and verify it uses the event bus
        AlertSystem.trigger(AlertType.CUSTOM, AlertSeverity.INFO, "Test")
        
        eventBus.shutdown()
    }
    
    @Test
    fun `should start and stop module`() = runBlocking {
        val config = MetricsConfig(
            aggregationInterval = Duration.ofMillis(100),
            autoCleanup = false
        )
        val module = MetricsModule(config)
        
        module.start()
        delay(50)
        assertTrue(module.getSnapshot().timestamp.isBefore(Instant.now().plusSeconds(1)))
        
        module.stop()
        delay(50)
    }
    
    @Test
    fun `should not start twice`() {
        val module = MetricsModule()
        
        module.start()
        module.start() // Should be no-op
        
        module.stop()
    }
    
    @Test
    fun `should not stop twice`() {
        val module = MetricsModule()
        
        module.start()
        module.stop()
        module.stop() // Should be no-op
    }
    
    @Test
    fun `should perform periodic aggregation`() = runBlocking {
        val config = MetricsConfig(
            aggregationInterval = Duration.ofMillis(100),
            autoCleanup = false
        )
        val module = MetricsModule(config)
        
        TokenTracker.track(TokenRecord(TaskId("task-1"), AgentId("agent-1"), 100, 200))
        
        module.start()
        delay(250) // Wait for at least 2 aggregations
        module.stop()
        
        // Verify metrics are still accessible after aggregation
        val snapshot = module.getSnapshot()
        assertEquals(300, snapshot.tokenUsage.totalTokens)
    }
    
    @Test
    fun `should perform periodic cleanup when enabled`() = runBlocking {
        val config = MetricsConfig(
            aggregationInterval = Duration.ofMillis(100),
            cleanupInterval = Duration.ofMillis(100),
            autoCleanup = true,
            retentionDays = 0
        )
        val module = MetricsModule(config)
        
        // Create and acknowledge an alert
        val alert = AlertSystem.trigger(AlertType.CUSTOM, AlertSeverity.INFO, "Test")
        AlertSystem.acknowledge(alert.id, "test")
        
        module.start()
        delay(250) // Wait for cleanup
        module.stop()
        
        // Old acknowledged alerts should be cleaned up
        val remaining = AlertSystem.getAllAlerts()
        assertTrue(remaining.isEmpty() || remaining.none { it.acknowledged })
    }
    
    @Test
    fun `should check token budget alerts`() {
        val module = MetricsModule()
        module.configureAlerts(tokenBudget = 100.0)
        
        val task = createTask()
        val agent = createAgent()
        val proposal = createProposal(inputTokens = 50, outputTokens = 100)
        val decision = createDecision()
        
        module.trackTaskExecution(task, agent, proposal, decision, Duration.ofSeconds(1), true)
        
        val alerts = AlertSystem.getAlertsByType(AlertType.TOKEN_BUDGET_EXCEEDED)
        assertTrue(alerts.isNotEmpty())
    }
    
    @Test
    fun `should check failure rate alerts`() {
        val module = MetricsModule()
        module.configureAlerts(failureRateThreshold = 0.3)
        
        val agentId = AgentId("agent-1")
        
        // Create high failure rate
        repeat(3) {
            module.trackAgentResponse(TaskId("task-$it"), agentId, Duration.ofSeconds(1), false)
        }
        module.trackAgentResponse(TaskId("task-4"), agentId, Duration.ofSeconds(1), true)
        
        val alerts = AlertSystem.getAlertsByType(AlertType.HIGH_FAILURE_RATE)
        assertTrue(alerts.isNotEmpty())
    }
    
    @Test
    fun `should include alert summary in snapshot`() {
        val module = MetricsModule()
        
        AlertSystem.trigger(AlertType.TOKEN_BUDGET_EXCEEDED, AlertSeverity.WARNING, "Alert 1")
        AlertSystem.trigger(AlertType.HIGH_FAILURE_RATE, AlertSeverity.ERROR, "Alert 2")
        
        val snapshot = module.getSnapshot()
        
        assertEquals(2, snapshot.alerts.total)
        assertEquals(2, snapshot.alerts.unacknowledged)
        assertTrue(snapshot.alerts.bySeverity[AlertSeverity.WARNING]!! > 0)
        assertTrue(snapshot.alerts.bySeverity[AlertSeverity.ERROR]!! > 0)
    }
    
    @Test
    fun `should reset all metrics`() {
        val module = MetricsModule()
        
        TokenTracker.track(TokenRecord(TaskId("task-1"), AgentId("agent-1"), 100, 200))
        PerformanceMonitor.trackTaskCompletion(TaskId("task-1"), Duration.ofSeconds(5), true)
        AlertSystem.trigger(AlertType.CUSTOM, AlertSeverity.INFO, "Test")
        
        module.reset()
        
        val snapshot = module.getSnapshot()
        assertEquals(0, snapshot.tokenUsage.totalTokens)
        assertEquals(0.0, snapshot.performance.overallSuccessRate)
        assertEquals(0, snapshot.alerts.total)
    }
    
    @Test
    fun `should use global instance`() {
        val global = MetricsModule.global
        
        assertNotNull(global)
        
        global.trackAgentResponse(TaskId("task-1"), AgentId("agent-1"), Duration.ofSeconds(1), true)
        
        val snapshot = global.getSnapshot()
        assertTrue(snapshot.performance.overallSuccessRate > 0)
    }
    
    // Helper functions
    private fun createTask(): Task {
        return Task(
            id = TaskId("task-1"),
            title = "Test Task",
            type = TaskType.IMPLEMENTATION,
            routing = RoutingStrategy.SOLO
        )
    }
    
    private fun createAgent(): Agent {
        return object : Agent {
            override val id = AgentId("agent-1")
            override val type = AgentType.CLAUDE_CODE
            override val displayName = "Test Agent"
            override val status = AgentStatus.ONLINE
            override val capabilities = emptySet<Capability>()
            override val strengths = emptyList<Strength>()
            override val config: AgentConfig? = null
        }
    }
    
    private fun createProposal(inputTokens: Int = 100, outputTokens: Int = 200): Proposal {
        return Proposal(
            id = ProposalId("proposal-1"),
            taskId = TaskId("task-1"),
            agentId = AgentId("agent-1"),
            inputType = InputType.IMPLEMENTATION_PLAN,
            content = "Test content",
            confidence = 0.8,
            tokenUsage = TokenUsage(inputTokens, outputTokens)
        )
    }
    
    private fun createDecision(): Decision {
        return Decision(
            id = DecisionId("decision-1"),
            taskId = TaskId("task-1"),
            considered = listOf(
                ProposalRef(
                    id = ProposalId("proposal-1"),
                    agentId = AgentId("agent-1"),
                    inputType = InputType.IMPLEMENTATION_PLAN,
                    confidence = 0.8,
                    tokenUsage = TokenUsage(100, 200)
                )
            ),
            selected = setOf(ProposalId("proposal-1")),
            winnerProposalId = ProposalId("proposal-1"),
            agreementRate = 1.0
        )
    }
}
