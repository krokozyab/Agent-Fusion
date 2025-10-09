package com.orchestrator.modules.routing

import com.orchestrator.domain.RoutingStrategy
import com.orchestrator.modules.metrics.ConfidenceCalibration
import com.orchestrator.modules.metrics.DecisionAnalytics
import com.orchestrator.modules.metrics.StrategyMetrics
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round

/** Provider for telemetry-backed strategy metrics. */
interface StrategyTelemetryProvider {
    fun getStrategyMetrics(strategy: RoutingStrategy): StrategyMetrics?
    fun getRoutingAccuracy(): Double
    fun getConfidenceCalibration(): List<ConfidenceCalibration>
}

/** Default telemetry provider delegating to DecisionAnalytics singleton. */
class DecisionAnalyticsTelemetryProvider : StrategyTelemetryProvider {
    override fun getStrategyMetrics(strategy: RoutingStrategy): StrategyMetrics? =
        DecisionAnalytics.getStrategyMetrics(strategy)

    override fun getRoutingAccuracy(): Double = DecisionAnalytics.getRoutingAccuracy()

    override fun getConfidenceCalibration(): List<ConfidenceCalibration> =
        DecisionAnalytics.analyzeConfidenceCalibration()
}

/** Calibration tuning parameters. */
data class CalibrationSettings(
    val targetSuccessRate: Double = 0.75,
    val adjustmentSlope: Double = 0.6,
    val minAdjustment: Double = 0.05,
    val maxAdjustment: Double = 0.8,
    val minSamples: Int = 5,
    val baseConfidenceWeight: Double = 0.2,
    val minBaseConfidence: Double = 0.3,
    val maxBaseConfidence: Double = 0.85,
    val classifierWeightFloor: Double = 0.2,
    val classifierWeightCeiling: Double = 0.6
)

/**
 * Calibrates StrategyPickerConfig using observed decision telemetry.
 */
class StrategyPickerCalibrator(
    private val telemetry: StrategyTelemetryProvider,
    private val settings: CalibrationSettings = CalibrationSettings()
) {

    fun calibrate(current: StrategyPickerConfig): StrategyPickerConfig {
        val consensusMetrics = telemetry.getStrategyMetrics(RoutingStrategy.CONSENSUS)
        val soloMetrics = telemetry.getStrategyMetrics(RoutingStrategy.SOLO)
        val sequentialMetrics = telemetry.getStrategyMetrics(RoutingStrategy.SEQUENTIAL)
        val parallelMetrics = telemetry.getStrategyMetrics(RoutingStrategy.PARALLEL)

        val updatedAdjustments = current.confidenceAdjustments.copy(
            forceDirectivePriority = adjust(consensusMetrics, current.confidenceAdjustments.forceDirectivePriority),
            preventDirectivePriority = adjust(soloMetrics, current.confidenceAdjustments.preventDirectivePriority),
            forceDirectiveStrong = adjust(consensusMetrics, current.confidenceAdjustments.forceDirectiveStrong),
            preventDirectiveStrong = adjust(soloMetrics, current.confidenceAdjustments.preventDirectiveStrong),
            assignmentStrong = adjust(soloMetrics, current.confidenceAdjustments.assignmentStrong),
            emergencyStrong = adjust(soloMetrics, current.confidenceAdjustments.emergencyStrong),
            assignedAgentsConsensus = adjust(consensusMetrics, current.confidenceAdjustments.assignedAgentsConsensus),
            highRiskConsensus = adjust(consensusMetrics, current.confidenceAdjustments.highRiskConsensus),
            architectureSequential = adjust(sequentialMetrics, current.confidenceAdjustments.architectureSequential),
            reviewConsensus = adjust(consensusMetrics, current.confidenceAdjustments.reviewConsensus),
            testingParallel = adjust(parallelMetrics, current.confidenceAdjustments.testingParallel),
            parallelSignals = adjust(parallelMetrics, current.confidenceAdjustments.parallelSignals),
            highComplexitySequential = adjust(sequentialMetrics, current.confidenceAdjustments.highComplexitySequential),
            bugfixSequential = adjust(sequentialMetrics, current.confidenceAdjustments.bugfixSequential)
        )

        val baseConfidence = adjustBaseConfidence(current.baseConfidence)
        val classifierWeight = adjustClassifierWeight(current.classifierConfidenceWeight)

        return current.copy(
            baseConfidence = baseConfidence,
            classifierConfidenceWeight = classifierWeight,
            confidenceAdjustments = updatedAdjustments
        )
    }

    private fun adjust(metrics: StrategyMetrics?, base: Double): Double {
        if (metrics == null || metrics.totalDecisions < settings.minSamples) return base
        val delta = metrics.successRate - settings.targetSuccessRate
        val factor = 1.0 + delta * settings.adjustmentSlope
        val scaled = base * factor
        return scaled.coerceIn(settings.minAdjustment, settings.maxAdjustment).roundTo(3)
    }

    private fun adjustBaseConfidence(current: Double): Double {
        val accuracy = telemetry.getRoutingAccuracy()
        if (accuracy <= 0.0) return current
        val adjusted = current + (accuracy - settings.targetSuccessRate) * settings.baseConfidenceWeight
        return adjusted.coerceIn(settings.minBaseConfidence, settings.maxBaseConfidence).roundTo(3)
    }

    private fun adjustClassifierWeight(current: Double): Double {
        val calibration = telemetry.getConfidenceCalibration()
        if (calibration.isEmpty()) return current
        val totalSamples = calibration.sumOf { it.sampleSize }
        if (totalSamples == 0) return current
        val weightedError = calibration.sumOf { it.calibrationError * it.sampleSize } / totalSamples
        val factor = max(0.0, 1.0 - weightedError)
        val adjusted = current * (0.7 + 0.3 * factor)
        return adjusted.coerceIn(settings.classifierWeightFloor, settings.classifierWeightCeiling).roundTo(3)
    }

    private fun Double.roundTo(precision: Int): Double {
        if (!this.isFinite()) return this
        val factor = 10.0.pow(precision.toDouble())
        return kotlin.math.round(this * factor) / factor
    }
}
