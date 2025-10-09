package com.orchestrator.modules.routing

import com.orchestrator.domain.RoutingStrategy
import com.orchestrator.modules.metrics.ConfidenceCalibration
import com.orchestrator.modules.metrics.StrategyMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class StubTelemetryProvider(
    private val metrics: Map<RoutingStrategy, StrategyMetrics?>,
    private val accuracy: Double,
    private val calibration: List<ConfidenceCalibration>
) : StrategyTelemetryProvider {
    override fun getStrategyMetrics(strategy: RoutingStrategy): StrategyMetrics? = metrics[strategy]
    override fun getRoutingAccuracy(): Double = accuracy
    override fun getConfidenceCalibration(): List<ConfidenceCalibration> = calibration
}

class StrategyPickerCalibratorTest {

    @Test
    fun `calibration boosts successful strategies and dampens weak ones`() {
        val consensusMetrics = StrategyMetrics(
            strategy = RoutingStrategy.CONSENSUS,
            totalDecisions = 20,
            successCount = 18,
            failureCount = 2,
            successRate = 0.9,
            avgConfidence = 0.82
        )
        val soloMetrics = StrategyMetrics(
            strategy = RoutingStrategy.SOLO,
            totalDecisions = 18,
            successCount = 11,
            failureCount = 7,
            successRate = 0.61,
            avgConfidence = 0.6
        )
        val sequentialMetrics = StrategyMetrics(
            strategy = RoutingStrategy.SEQUENTIAL,
            totalDecisions = 15,
            successCount = 12,
            failureCount = 3,
            successRate = 0.8,
            avgConfidence = 0.7
        )
        val parallelMetrics = StrategyMetrics(
            strategy = RoutingStrategy.PARALLEL,
            totalDecisions = 12,
            successCount = 7,
            failureCount = 5,
            successRate = 0.58,
            avgConfidence = 0.65
        )

        val telemetry = StubTelemetryProvider(
            metrics = mapOf(
                RoutingStrategy.CONSENSUS to consensusMetrics,
                RoutingStrategy.SOLO to soloMetrics,
                RoutingStrategy.SEQUENTIAL to sequentialMetrics,
                RoutingStrategy.PARALLEL to parallelMetrics
            ),
            accuracy = 0.78,
            calibration = listOf(
                ConfidenceCalibration(
                    confidenceRange = "60-80%",
                    predictedSuccess = 0.7,
                    actualSuccess = 0.62,
                    calibrationError = 0.08,
                    sampleSize = 30
                )
            )
        )

        val calibrator = StrategyPickerCalibrator(telemetry)
        val current = StrategyPickerConfig()
        val calibrated = calibrator.calibrate(current)

        assertTrue(calibrated.confidenceAdjustments.forceDirectiveStrong > current.confidenceAdjustments.forceDirectiveStrong)
        assertTrue(calibrated.confidenceAdjustments.preventDirectiveStrong < current.confidenceAdjustments.preventDirectiveStrong)
        assertTrue(calibrated.confidenceAdjustments.architectureSequential > current.confidenceAdjustments.architectureSequential)
        assertTrue(calibrated.confidenceAdjustments.testingParallel < current.confidenceAdjustments.testingParallel)
        assertTrue(calibrated.baseConfidence >= current.baseConfidence)
        assertTrue(calibrated.classifierConfidenceWeight <= current.classifierConfidenceWeight)
    }

    @Test
    fun `insufficient telemetry leaves config unchanged`() {
        val telemetry = StubTelemetryProvider(
            metrics = emptyMap(),
            accuracy = 0.0,
            calibration = emptyList()
        )
        val calibrator = StrategyPickerCalibrator(telemetry)
        val current = StrategyPickerConfig()
        val calibrated = calibrator.calibrate(current)

        assertEquals(current, calibrated)
    }
}
