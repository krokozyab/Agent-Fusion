package com.orchestrator.modules.metrics

import com.orchestrator.domain.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.concurrent.thread

class DecisionAnalyticsTest {
    
    @AfterEach
    fun cleanup() {
        DecisionAnalytics.reset()
    }
    
    @Test
    fun `should track routing accuracy`() {
        recordOutcome(RoutingStrategy.SOLO, confidence = 0.9, success = true)
        recordOutcome(RoutingStrategy.SOLO, confidence = 0.8, success = true)
        recordOutcome(RoutingStrategy.SOLO, confidence = 0.7, success = false)
        
        val accuracy = DecisionAnalytics.getRoutingAccuracy()
        assertEquals(0.666, accuracy, 0.01)
    }
    
    @Test
    fun `should return zero accuracy when no outcomes`() {
        assertEquals(0.0, DecisionAnalytics.getRoutingAccuracy())
    }
    
    @Test
    fun `should track strategy-specific metrics`() {
        recordOutcome(RoutingStrategy.CONSENSUS, confidence = 0.9, success = true)
        recordOutcome(RoutingStrategy.CONSENSUS, confidence = 0.8, success = true)
        recordOutcome(RoutingStrategy.CONSENSUS, confidence = 0.7, success = false)
        recordOutcome(RoutingStrategy.SOLO, confidence = 0.6, success = true)
        
        val consensusMetrics = DecisionAnalytics.getStrategyMetrics(RoutingStrategy.CONSENSUS)
        assertNotNull(consensusMetrics)
        assertEquals(3, consensusMetrics!!.totalDecisions)
        assertEquals(2, consensusMetrics.successCount)
        assertEquals(1, consensusMetrics.failureCount)
        assertEquals(0.666, consensusMetrics.successRate, 0.01)
        assertEquals(0.8, consensusMetrics.avgConfidence, 0.01)
    }
    
    @Test
    fun `should return null metrics for unused strategy`() {
        recordOutcome(RoutingStrategy.SOLO, confidence = 0.9, success = true)
        
        val metrics = DecisionAnalytics.getStrategyMetrics(RoutingStrategy.PARALLEL)
        assertNull(metrics)
    }
    
    @Test
    fun `should get all strategy metrics`() {
        recordOutcome(RoutingStrategy.SOLO, confidence = 0.9, success = true)
        recordOutcome(RoutingStrategy.CONSENSUS, confidence = 0.8, success = true)
        recordOutcome(RoutingStrategy.SEQUENTIAL, confidence = 0.7, success = false)
        
        val allMetrics = DecisionAnalytics.getAllStrategyMetrics()
        assertEquals(3, allMetrics.size)
        assertTrue(allMetrics.any { it.strategy == RoutingStrategy.SOLO })
        assertTrue(allMetrics.any { it.strategy == RoutingStrategy.CONSENSUS })
        assertTrue(allMetrics.any { it.strategy == RoutingStrategy.SEQUENTIAL })
    }
    
    @Test
    fun `should analyze confidence calibration`() {
        // Low confidence outcomes
        repeat(5) { recordOutcome(RoutingStrategy.SOLO, confidence = 0.1, success = false) }
        repeat(1) { recordOutcome(RoutingStrategy.SOLO, confidence = 0.1, success = true) }
        
        // High confidence outcomes
        repeat(9) { recordOutcome(RoutingStrategy.SOLO, confidence = 0.9, success = true) }
        repeat(1) { recordOutcome(RoutingStrategy.SOLO, confidence = 0.9, success = false) }
        
        val calibration = DecisionAnalytics.analyzeConfidenceCalibration()
        
        assertTrue(calibration.isNotEmpty())
        
        val lowRange = calibration.find { it.confidenceRange == "0-20%" }
        assertNotNull(lowRange)
        assertEquals(6, lowRange!!.sampleSize)
        assertEquals(0.166, lowRange.actualSuccess, 0.01)
        
        val highRange = calibration.find { it.confidenceRange == "80-100%" }
        assertNotNull(highRange)
        assertEquals(10, highRange!!.sampleSize)
        assertEquals(0.9, highRange.actualSuccess, 0.01)
    }
    
    @Test
    fun `should calculate calibration error`() {
        repeat(10) { recordOutcome(RoutingStrategy.SOLO, confidence = 0.5, success = true) }
        
        val calibration = DecisionAnalytics.analyzeConfidenceCalibration()
        val midRange = calibration.find { it.confidenceRange == "40-60%" }
        
        assertNotNull(midRange)
        assertEquals(0.5, midRange!!.predictedSuccess, 0.01)
        assertEquals(1.0, midRange.actualSuccess, 0.01)
        assertEquals(0.5, midRange.calibrationError, 0.01)
    }
    
    @Test
    fun `should identify patterns for high confidence success`() {
        repeat(10) { recordOutcome(RoutingStrategy.SOLO, confidence = 0.9, success = true) }
        
        val patterns = DecisionAnalytics.identifyPatterns()
        
        assertTrue(patterns.any { it.contains("High confidence") && it.contains("predicts success") })
    }
    
    @Test
    fun `should identify patterns for effective strategies`() {
        repeat(10) { recordOutcome(RoutingStrategy.CONSENSUS, confidence = 0.8, success = true) }
        
        val patterns = DecisionAnalytics.identifyPatterns()
        
        assertTrue(patterns.any { it.contains("CONSENSUS") && it.contains("highly effective") })
    }
    
    @Test
    fun `should identify patterns for calibration issues`() {
        // Create poorly calibrated confidence range
        repeat(10) { recordOutcome(RoutingStrategy.SOLO, confidence = 0.9, success = false) }
        
        val patterns = DecisionAnalytics.identifyPatterns()
        
        assertTrue(patterns.any { it.contains("poorly calibrated") })
    }
    
    @Test
    fun `should not identify patterns with insufficient data`() {
        recordOutcome(RoutingStrategy.SOLO, confidence = 0.9, success = true)
        recordOutcome(RoutingStrategy.SOLO, confidence = 0.9, success = true)
        
        val patterns = DecisionAnalytics.identifyPatterns()
        
        // Should not identify patterns with only 2 samples
        assertTrue(patterns.isEmpty())
    }
    
    @Test
    fun `should generate comprehensive report`() {
        recordOutcome(RoutingStrategy.SOLO, confidence = 0.9, success = true)
        recordOutcome(RoutingStrategy.CONSENSUS, confidence = 0.8, success = true)
        recordOutcome(RoutingStrategy.SOLO, confidence = 0.7, success = false)
        repeat(5) { recordOutcome(RoutingStrategy.CONSENSUS, confidence = 0.85, success = true) }
        
        val report = DecisionAnalytics.generateReport()
        
        // 7 successes out of 8 total = 0.875
        assertEquals(0.875, report.routingAccuracy, 0.01)
        assertTrue(report.strategyMetrics.isNotEmpty())
        assertTrue(report.confidenceCalibration.isNotEmpty())
        assertNotNull(report.period)
        assertTrue(report.patterns.any { it.contains("CONSENSUS") && it.contains("highly effective") })
    }
    
    @Test
    fun `should handle empty report gracefully`() {
        val report = DecisionAnalytics.generateReport()
        
        assertEquals(0.0, report.routingAccuracy)
        assertTrue(report.strategyMetrics.isEmpty())
        assertTrue(report.confidenceCalibration.isEmpty())
        assertTrue(report.optimalStrategies.isEmpty())
        assertTrue(report.patterns.isEmpty())
    }
    
    @Test
    fun `should record decision from Decision and Task objects`() {
        val decision = createDecision()
        val task = createTask(RoutingStrategy.CONSENSUS)
        
        DecisionAnalytics.recordDecision(decision, task, success = true)
        
        val accuracy = DecisionAnalytics.getRoutingAccuracy()
        assertEquals(1.0, accuracy)
    }
    
    @Test
    fun `should calculate average confidence from proposals`() {
        val decision = createDecision(
            proposals = listOf(
                createProposalRef(confidence = 0.8),
                createProposalRef(confidence = 0.6)
            )
        )
        val task = createTask(RoutingStrategy.CONSENSUS)
        
        DecisionAnalytics.recordDecision(decision, task, success = true)
        
        val metrics = DecisionAnalytics.getStrategyMetrics(RoutingStrategy.CONSENSUS)
        assertNotNull(metrics)
        assertEquals(0.7, metrics!!.avgConfidence, 0.01)
    }
    
    @Test
    fun `should be thread-safe`() {
        val threads = 10
        val recordsPerThread = 100
        
        val threadList = (1..threads).map {
            thread {
                repeat(recordsPerThread) {
                    recordOutcome(RoutingStrategy.SOLO, confidence = 0.8, success = true)
                }
            }
        }
        
        threadList.forEach { it.join() }
        
        val metrics = DecisionAnalytics.getStrategyMetrics(RoutingStrategy.SOLO)
        assertNotNull(metrics)
        assertEquals(1000, metrics!!.totalDecisions)
        assertEquals(1.0, metrics.successRate)
    }
    
    @Test
    fun `should reset all analytics`() {
        recordOutcome(RoutingStrategy.SOLO, confidence = 0.9, success = true)
        recordOutcome(RoutingStrategy.CONSENSUS, confidence = 0.8, success = true)
        
        DecisionAnalytics.reset()
        
        assertEquals(0.0, DecisionAnalytics.getRoutingAccuracy())
        assertTrue(DecisionAnalytics.getAllStrategyMetrics().isEmpty())
    }
    
    @Test
    fun `should track multiple strategies independently`() {
        repeat(5) { recordOutcome(RoutingStrategy.SOLO, confidence = 0.9, success = true) }
        repeat(3) { recordOutcome(RoutingStrategy.CONSENSUS, confidence = 0.8, success = false) }
        
        val soloMetrics = DecisionAnalytics.getStrategyMetrics(RoutingStrategy.SOLO)
        val consensusMetrics = DecisionAnalytics.getStrategyMetrics(RoutingStrategy.CONSENSUS)
        
        assertEquals(1.0, soloMetrics!!.successRate)
        assertEquals(0.0, consensusMetrics!!.successRate)
    }
    
    @Test
    fun `should handle edge case of single outcome`() {
        recordOutcome(RoutingStrategy.SOLO, confidence = 0.9, success = true)
        
        val accuracy = DecisionAnalytics.getRoutingAccuracy()
        assertEquals(1.0, accuracy)
        
        val metrics = DecisionAnalytics.getStrategyMetrics(RoutingStrategy.SOLO)
        assertEquals(1, metrics!!.totalDecisions)
    }
    
    // Helper functions
    private fun recordOutcome(strategy: RoutingStrategy, confidence: Double, success: Boolean) {
        val outcome = DecisionOutcome(
            decisionId = DecisionId("decision-${System.nanoTime()}"),
            taskId = TaskId("task-${System.nanoTime()}"),
            strategy = strategy,
            confidence = confidence,
            success = success,
            timestamp = Instant.now()
        )
        DecisionAnalytics.recordOutcome(outcome)
    }
    
    private fun createDecision(proposals: List<ProposalRef> = listOf(createProposalRef())): Decision {
        return Decision(
            id = DecisionId("decision-1"),
            taskId = TaskId("task-1"),
            considered = proposals,
            selected = proposals.map { it.id }.toSet(),
            winnerProposalId = proposals.firstOrNull()?.id,
            agreementRate = 1.0,
            rationale = "Test decision"
        )
    }
    
    private fun createTask(routing: RoutingStrategy): Task {
        return Task(
            id = TaskId("task-1"),
            title = "Test Task",
            type = TaskType.IMPLEMENTATION,
            routing = routing
        )
    }
    
    private fun createProposalRef(confidence: Double = 0.8): ProposalRef {
        return ProposalRef(
            id = ProposalId("proposal-${System.nanoTime()}"),
            agentId = AgentId("agent-1"),
            inputType = InputType.IMPLEMENTATION_PLAN,
            confidence = confidence,
            tokenUsage = TokenUsage(100, 200)
        )
    }
}
