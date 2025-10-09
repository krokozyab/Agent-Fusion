package com.orchestrator.modules.routing

import com.orchestrator.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StrategyPickerTest {

    private fun newPicker(logs: MutableList<String>? = null): StrategyPicker {
        val sink = logs?.let { list -> { message: String -> list += message } }
        return StrategyPicker(auditSink = sink)
    }

    private fun newTask(
        title: String = "Task",
        description: String? = null,
        type: TaskType = TaskType.IMPLEMENTATION,
        metadata: Map<String, String> = emptyMap()
    ): Task = Task(
        id = TaskId("t-${System.nanoTime()}"),
        title = title,
        description = description,
        type = type,
        metadata = metadata
    )

    @Test
    fun `user directive forceConsensus takes precedence`() {
        val task = newTask(description = "Small change", type = TaskType.DOCUMENTATION)
        val directive = UserDirective(
            originalText = "use consensus",
            forceConsensus = true,
            forceConsensusConfidence = 0.8
        )

        val logs = mutableListOf<String>()
        val strategy = newPicker(logs).pickStrategy(task, directive)
        assertEquals(RoutingStrategy.CONSENSUS, strategy)
        assertTrue(logs.any { it.contains("[StrategyPicker]") }, "Should log reasoning")
        assertTrue(logs.any { it.contains("forceConsensus") }, "Log should mention forceConsensus")
    }

    @Test
    fun `preventConsensus yields SOLO even if risky`() {
        val task = newTask(
            description = "Critical security fix for authentication vulnerability in production",
            type = TaskType.BUGFIX
        )
        val directive = UserDirective(
            originalText = "no consensus",
            preventConsensus = true,
            preventConsensusConfidence = 0.82
        )

        val logs = mutableListOf<String>()
        val strategy = newPicker(logs).pickStrategy(task, directive)
        assertEquals(RoutingStrategy.SOLO, strategy)
        assertTrue(logs.any { it.contains("preventConsensus") })
    }

    @Test
    fun `emergency defaults to SOLO`() {
        val task = newTask(description = "Hotfix outage in production payment flow", type = TaskType.BUGFIX)
        val directive = UserDirective(
            originalText = "urgent!",
            isEmergency = true,
            emergencyConfidence = 0.9
        )

        val strategy = newPicker().pickStrategy(task, directive)
        assertEquals(RoutingStrategy.SOLO, strategy)
    }

    @Test
    fun `high risk or critical keywords pick CONSENSUS`() {
        val task = newTask(description = "Investigate encryption for handling PII and security compliance", type = TaskType.RESEARCH)
        val directive = UserDirective(originalText = "")
        val strategy = newPicker().pickStrategy(task, directive)
        assertEquals(RoutingStrategy.CONSENSUS, strategy)
    }

    @Test
    fun high_complexity_moderate_risk_sequential() {
        val desc = """
            Implement migration with integration across multiple services; refactor architecture for scalability and performance.
            This is not high risk but requires careful planning and staged rollout.
        """.trimIndent()
        val task = newTask(description = desc, type = TaskType.ARCHITECTURE)
        val directive = UserDirective("")
        val strategy = newPicker().pickStrategy(task, directive)
        assertEquals(RoutingStrategy.SEQUENTIAL, strategy)
    }

    @Test
    fun explicit_parallelization_signals_parallel() {
        val task = newTask(
            description = "Run tests in parallel across multiple modules to speed up feedback",
            type = TaskType.TESTING
        )
        val directive = UserDirective("")
        val strategy = newPicker().pickStrategy(task, directive)
        assertEquals(RoutingStrategy.PARALLEL, strategy)
    }

    @Test
    fun `default fallback is SOLO`() {
        val task = newTask(description = "Write a short README section", type = TaskType.DOCUMENTATION)
        val directive = UserDirective("")
        val strategy = newPicker().pickStrategy(task, directive)
        assertEquals(RoutingStrategy.SOLO, strategy)
    }

    @Test
    fun `uses precomputed classification when provided`() {
        val task = newTask(description = "Short doc", type = TaskType.DOCUMENTATION)
        val directive = UserDirective("")
        val classification = TaskClassifier.Result(
            complexity = 9,
            risk = 9,
            criticalKeywords = setOf("security"),
            confidence = 0.9
        )

        val strategy = newPicker().pickStrategy(task, directive, classification)

        assertEquals(RoutingStrategy.CONSENSUS, strategy)
    }
}
