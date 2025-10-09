package com.orchestrator.modules.routing

/**
 * Configuration for StrategyPicker decision heuristics and confidence tuning.
 */
data class StrategyPickerConfig(
    val baseConfidence: Double = 0.5,
    val classifierBaseOffset: Double = 0.4,
    val classifierConfidenceWeight: Double = 0.4,
    val directiveConfidenceStrong: Double = 0.55,
    val directiveConfidenceMinimum: Double = 0.35,
    val confidenceEpsilon: Double = 0.05,
    val highRiskThreshold: Int = 8,
    val highComplexityThreshold: Int = 8,
    val architectureComplexityThreshold: Int = 6,
    val sequentialModerateRiskRange: IntRange = 5..7,
    val maxRiskForTestingParallel: Int = 4,
    val minComplexityForTestingParallel: Int = 5,
    val consensusAssignedAgentsThreshold: Int = 2,
    val bugfixLowComplexityThreshold: Int = 4,
    val bugfixLowRiskThreshold: Int = 6,
    val confidenceAdjustments: ConfidenceAdjustments = ConfidenceAdjustments()
) {

    data class ConfidenceAdjustments(
        val forceDirectivePriority: Double = 0.45,
        val preventDirectivePriority: Double = 0.35,
        val forceDirectiveStrong: Double = 0.5,
        val preventDirectiveStrong: Double = 0.3,
        val assignmentStrong: Double = 0.25,
        val emergencyStrong: Double = 0.2,
        val assignedAgentsConsensus: Double = 0.25,
        val highRiskConsensus: Double = 0.3,
        val architectureSequential: Double = 0.2,
        val reviewConsensus: Double = 0.2,
        val testingParallel: Double = 0.15,
        val parallelSignals: Double = 0.15,
        val highComplexitySequential: Double = 0.15,
        val bugfixSequential: Double = 0.1
    )
}
