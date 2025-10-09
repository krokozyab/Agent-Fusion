package com.orchestrator.modules.routing

import com.orchestrator.domain.RoutingStrategy
import com.orchestrator.domain.Task
import com.orchestrator.domain.TaskType
import com.orchestrator.domain.UserDirective
import com.orchestrator.utils.Logger
import kotlin.jvm.Volatile

/**
 * Picks a routing strategy for a task using configurable heuristics with
 * user-directive overrides and confidence scoring.
 */
class StrategyPicker(
    config: StrategyPickerConfig = StrategyPickerConfig(),
    private val logger: Logger = Logger.logger<StrategyPicker>(),
    private val auditSink: ((String) -> Unit)? = null
) {

    @Volatile
    private var config: StrategyPickerConfig = config

    data class Decision(
        val strategy: RoutingStrategy,
        val confidence: Double,
        val reasons: List<String>
    )

    /**
     * Main entry point: select a routing strategy.
     * Always logs the reasoning and confidence.
     */
    fun pickStrategy(
        task: Task,
        directive: UserDirective,
        classification: TaskClassifier.Result? = null
    ): RoutingStrategy {
        val decision = decide(task, directive, classification)
        logDecision(task, decision)
        return decision.strategy
    }

    // Core decision logic returning full decision for logging
    private fun decide(
        task: Task,
        directive: UserDirective,
        precomputedClassification: TaskClassifier.Result?
    ): Decision {
        val activeConfig = config
        val reasons = mutableListOf<String>()
        var confidence = activeConfig.baseConfidence

        // 0) Classify from description/title for heuristics
        val text = task.description ?: task.title
        val cls = precomputedClassification ?: TaskClassifier.classify(text)
        reasons += "classified complexity=${cls.complexity} risk=${cls.risk} critical=${cls.criticalKeywords} clfConf=${cls.confidence.formatConf()}"
        confidence = activeConfig.classifierBaseOffset + activeConfig.classifierConfidenceWeight * cls.confidence

        // 1) User directive overrides (with confidence awareness)
        val forceConf = directive.forceConsensusConfidence
        val preventConf = directive.preventConsensusConfidence
        val assignConf = directive.assignmentConfidence
        val emergencyConf = directive.emergencyConfidence
        val strongForce = directive.forceConsensus && forceConf >= activeConfig.directiveConfidenceMinimum
        val strongPrevent = directive.preventConsensus && preventConf >= activeConfig.directiveConfidenceMinimum
        val strongAssignment = directive.assignToAgent != null && assignConf >= activeConfig.directiveConfidenceMinimum
        val strongEmergency = directive.isEmergency && emergencyConf >= activeConfig.directiveConfidenceMinimum

        val adjustments = activeConfig.confidenceAdjustments

        if (directive.forceConsensus && directive.preventConsensus) {
            reasons += "conflicting directives (force=${forceConf.formatConf()}, prevent=${preventConf.formatConf()})"
            when {
                strongForce && strongPrevent -> {
                    when {
                        forceConf > preventConf + activeConfig.confidenceEpsilon -> {
                            reasons += "force directive higher confidence -> CONSENSUS"
                            return Decision(
                                RoutingStrategy.CONSENSUS,
                                (confidence + adjustments.forceDirectivePriority).coerceIn(0.0, 1.0),
                                reasons
                            )
                        }
                        preventConf > forceConf + activeConfig.confidenceEpsilon -> {
                            reasons += "prevent directive higher confidence -> SOLO"
                            return Decision(
                                RoutingStrategy.SOLO,
                                (confidence + adjustments.preventDirectivePriority).coerceIn(0.0, 1.0),
                                reasons
                            )
                        }
                        else -> reasons += "directive confidence nearly equal -> fallback to heuristics"
                    }
                }
                strongForce -> {
                    reasons += "force directive confidence=${forceConf.formatConf()} overrides prevent"
                    return Decision(
                        RoutingStrategy.CONSENSUS,
                        (confidence + adjustments.forceDirectivePriority).coerceIn(0.0, 1.0),
                        reasons
                    )
                }
                strongPrevent -> {
                    reasons += "prevent directive confidence=${preventConf.formatConf()} overrides force"
                    return Decision(
                        RoutingStrategy.SOLO,
                        (confidence + adjustments.preventDirectivePriority).coerceIn(0.0, 1.0),
                        reasons
                    )
                }
                else -> reasons += "both directives low confidence -> ignoring conflict"
            }
        } else {
            if (strongForce) {
                reasons += "user directive: forceConsensus (conf=${forceConf.formatConf()})"
                return Decision(
                    RoutingStrategy.CONSENSUS,
                    (confidence + adjustments.forceDirectiveStrong).coerceIn(0.0, 1.0),
                    reasons
                )
            } else if (directive.forceConsensus) {
                reasons += "user directive: forceConsensus low confidence=${forceConf.formatConf()} -> treating as hint"
            }

            if (strongPrevent) {
                reasons += "user directive: preventConsensus (conf=${preventConf.formatConf()}) -> SOLO"
                return Decision(
                    RoutingStrategy.SOLO,
                    (confidence + adjustments.preventDirectiveStrong).coerceIn(0.0, 1.0),
                    reasons
                )
            } else if (directive.preventConsensus) {
                reasons += "user directive: preventConsensus low confidence=${preventConf.formatConf()} -> treating as hint"
            }
        }

        if (strongAssignment) {
            reasons += "user directive: assignToAgent ${directive.assignToAgent} (conf=${assignConf.formatConf()}) -> SOLO"
            return Decision(
                RoutingStrategy.SOLO,
                (confidence + adjustments.assignmentStrong).coerceIn(0.0, 1.0),
                reasons
            )
        } else if (directive.assignToAgent != null) {
            reasons += "user directive: assignToAgent ${directive.assignToAgent} low confidence=${assignConf.formatConf()} -> evaluating heuristics"
        }

        if (strongEmergency) {
            // In emergencies, reduce coordination overhead
            reasons += "user directive: emergency (conf=${emergencyConf.formatConf()}) -> minimize overhead"
            return Decision(
                RoutingStrategy.SOLO,
                (confidence + adjustments.emergencyStrong).coerceIn(0.0, 1.0),
                reasons
            )
        } else if (directive.isEmergency) {
            reasons += "user directive: emergency low confidence=${emergencyConf.formatConf()} -> continuing heuristics"
        }

        // 2) Heuristics from classification and task attributes
        val hasCritical = cls.criticalKeywords.isNotEmpty()
        val mentionsParallel = text.contains("parallel", ignoreCase = true)
                || text.contains("concurrent", ignoreCase = true)
                || text.contains("multiple", ignoreCase = true)
                || (task.metadata["parallelizable"]?.equals("true", ignoreCase = true) == true)

        val directiveAgents = directive.assignedAgents?.filterNotNull() ?: emptyList()
        if (!strongForce && directiveAgents.size >= activeConfig.consensusAssignedAgentsThreshold) {
            reasons += "user directive: ${directiveAgents.size} agents requested -> leaning CONSENSUS"
            return Decision(
                RoutingStrategy.CONSENSUS,
                (confidence + adjustments.assignedAgentsConsensus).coerceIn(0.0, 1.0),
                reasons
            )
        }

        // Safety first: very high risk or critical domains -> CONSENSUS
        if (cls.risk >= activeConfig.highRiskThreshold || hasCritical) {
            reasons += when {
                cls.risk >= activeConfig.highRiskThreshold && hasCritical -> "high risk (>=${activeConfig.highRiskThreshold}) and critical keywords -> CONSENSUS"
                cls.risk >= activeConfig.highRiskThreshold -> "high risk (>=${activeConfig.highRiskThreshold}) -> CONSENSUS"
                else -> "critical domain -> CONSENSUS"
            }
            return Decision(
                RoutingStrategy.CONSENSUS,
                (confidence + adjustments.highRiskConsensus).coerceIn(0.0, 1.0),
                reasons
            )
        }

        // Architectural or planning tasks with breadth often benefit from sequential pipeline
        if ((task.type == TaskType.ARCHITECTURE || task.type == TaskType.PLANNING) && cls.complexity >= activeConfig.architectureComplexityThreshold) {
            reasons += "architecture/planning with complexity>=${activeConfig.architectureComplexityThreshold} -> SEQUENTIAL"
            return Decision(
                RoutingStrategy.SEQUENTIAL,
                (confidence + adjustments.architectureSequential).coerceIn(0.0, 1.0),
                reasons
            )
        }

        // Reviews benefit from multiple opinions even at moderate risk
        if (task.type == TaskType.REVIEW) {
            reasons += "task type REVIEW -> CONSENSUS"
            return Decision(
                RoutingStrategy.CONSENSUS,
                (confidence + adjustments.reviewConsensus).coerceIn(0.0, 1.0),
                reasons
            )
        }

        // Testing or research with low risk and moderate/high complexity -> PARALLEL for speed
        val testingApplies = task.type == TaskType.TESTING || task.type == TaskType.RESEARCH
        if (testingApplies && cls.risk <= activeConfig.maxRiskForTestingParallel && cls.complexity >= activeConfig.minComplexityForTestingParallel) {
            reasons += "testing/research with low risk and complexity>=${activeConfig.minComplexityForTestingParallel} -> PARALLEL"
            return Decision(
                RoutingStrategy.PARALLEL,
                (confidence + adjustments.testingParallel).coerceIn(0.0, 1.0),
                reasons
            )
        }

        // Explicit parallelizable signals
        if (mentionsParallel) {
            reasons += "parallelization signals detected -> PARALLEL"
            return Decision(
                RoutingStrategy.PARALLEL,
                (confidence + adjustments.parallelSignals).coerceIn(0.0, 1.0),
                reasons
            )
        }

        // High complexity with moderate risk often suits SEQUENTIAL handoffs
        if (cls.complexity >= activeConfig.highComplexityThreshold && cls.risk in activeConfig.sequentialModerateRiskRange) {
            reasons += "high complexity (>=${activeConfig.highComplexityThreshold}) with moderate risk -> SEQUENTIAL"
            return Decision(
                RoutingStrategy.SEQUENTIAL,
                (confidence + adjustments.highComplexitySequential).coerceIn(0.0, 1.0),
                reasons
            )
        }

        // Default based on task type hints
        when (task.type) {
            TaskType.DOCUMENTATION -> {
                reasons += "documentation default -> SOLO"
                return Decision(RoutingStrategy.SOLO, confidence, reasons)
            }
            TaskType.BUGFIX -> {
                if (cls.complexity <= activeConfig.bugfixLowComplexityThreshold && cls.risk <= activeConfig.bugfixLowRiskThreshold) {
                    reasons += "bugfix low complexity -> SOLO"
                    return Decision(RoutingStrategy.SOLO, confidence, reasons)
                }
                reasons += "bugfix non-trivial -> SEQUENTIAL"
                return Decision(
                    RoutingStrategy.SEQUENTIAL,
                    (confidence + adjustments.bugfixSequential).coerceIn(0.0, 1.0),
                    reasons
                )
            }
            else -> { /* fallthrough */ }
        }

        // Fallback default
        reasons += "default -> SOLO"
        return Decision(RoutingStrategy.SOLO, confidence, reasons)
    }

    private fun logDecision(task: Task, decision: Decision) {
        val formattedConfidence = decision.confidence.formatConf()
        val message = "[StrategyPicker] Selected strategy=${decision.strategy} (conf=$formattedConfidence) for task ${task.id}. reasons=${decision.reasons.joinToString("; ")}"
        logger.info(
            "strategy-picker.decision",
            mapOf(
                "taskId" to task.id.value,
                "taskType" to task.type.name,
                "strategy" to decision.strategy.name,
                "confidence" to formattedConfidence,
                "reasons" to decision.reasons
            )
        )
        auditSink?.invoke(message)
    }

    fun currentConfig(): StrategyPickerConfig = config

    fun updateConfig(newConfig: StrategyPickerConfig) {
        config = newConfig
    }

    fun applyCalibration(calibrator: StrategyPickerCalibrator) {
        val updated = calibrator.calibrate(config)
        if (updated != config) {
            updateConfig(updated)
        }
    }

    private fun Double.formatConf(): String = "%.2f".format(this.coerceIn(0.0, 1.0))

    companion object {
        fun default(): StrategyPicker = StrategyPicker()
    }
}
