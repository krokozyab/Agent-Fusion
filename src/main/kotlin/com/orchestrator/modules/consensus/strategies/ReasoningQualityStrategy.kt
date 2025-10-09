package com.orchestrator.modules.consensus.strategies

import com.orchestrator.domain.Proposal
import java.time.Instant

/**
 * Reasoning-quality based consensus strategy.
 *
 * Objective scoring criteria per proposal:
 * - Reasoning depth (0..1): derived from length/structure of textual reasoning fields.
 * - Edge-case consideration (0..1): presence and richness of edge cases/risks/limitations.
 * - Trade-off analysis (0..1): presence of trade-offs, pros/cons, alternatives.
 *
 * Aggregation:
 *   rawScore = 0.5*depth + 0.25*edgeCases + 0.25*tradeOffs
 *   finalScore = rawScore * (0.5 + 0.5*confidence)    // weight by confidence without zeroing thoughtful low-confidence outputs
 *
 * Determinism & tie-breaking:
 * - On ties of finalScore, pick highest confidence, then earliest createdAt, then lexicographic id.
 * - Empty input returns agreed=false with explanation.
 *
 * Content assumptions:
 * - Proposals carry JSON-compatible content (String, List, Map<String, *>). This strategy inspects commonly
 *   used fields: "reasoning", "analysis", "steps", "edgeCases", "risks", "limitations", "tradeOffs",
 *   "pros", "cons", "alternatives". When fields are absent, falls back to scanning text for indicative terms.
 */
class ReasoningQualityStrategy : ConsensusStrategy {

    override val type: ConsensusStrategyType = ConsensusStrategyType.REASONING_QUALITY

    override fun evaluate(proposals: List<Proposal>): ConsensusResult {
        if (proposals.isEmpty()) {
            return ConsensusResult(
                agreed = false,
                winningProposal = null,
                reasoning = "No proposals provided; cannot evaluate reasoning quality.",
                details = mapOf(
                    "totalProposals" to 0
                )
            )
        }

        val scored = proposals.map { p ->
            val features = extractFeatures(p.content)
            val depth = scoreDepth(features)
            val edge = scoreEdgeCases(features)
            val trade = scoreTradeOffs(features)
            val raw = 0.5 * depth + 0.25 * edge + 0.25 * trade
            val weighted = raw * (0.5 + 0.5 * p.confidence)
            ProposalScore(
                proposal = p,
                depth = depth,
                edgeCases = edge,
                tradeOffs = trade,
                rawScore = raw,
                confidence = p.confidence,
                finalScore = weighted
            )
        }

        val winner = scored.sortedWith(
            compareByDescending<ProposalScore> { it.finalScore }
                .thenByDescending { it.confidence }
                .thenBy { it.proposal.createdAt }
                .thenBy { it.proposal.id.value }
        ).first()

        val details = mapOf(
            "scoringWeights" to mapOf(
                "depth" to 0.5,
                "edgeCases" to 0.25,
                "tradeOffs" to 0.25,
                "confidenceWeighting" to "final = raw * (0.5 + 0.5*confidence)"
            ),
            "proposalScores" to scored.map { s ->
                mapOf(
                    "proposalId" to s.proposal.id.value,
                    "confidence" to s.confidence,
                    "depth" to round3(s.depth),
                    "edgeCases" to round3(s.edgeCases),
                    "tradeOffs" to round3(s.tradeOffs),
                    "rawScore" to round3(s.rawScore),
                    "finalScore" to round3(s.finalScore)
                )
            },
            "winningProposalId" to winner.proposal.id.value,
            "winningFinalScore" to round3(winner.finalScore)
        )

        val reasoningText = buildString {
            append("Selected proposal ")
            append(winner.proposal.id.value)
            append(" with highest reasoning quality score (")
            append("final=")
            append("${round3(winner.finalScore)}")
            append(", depth=")
            append("${round3(winner.depth)}")
            append(", edgeCases=")
            append("${round3(winner.edgeCases)}")
            append(", tradeOffs=")
            append("${round3(winner.tradeOffs)}")
            append(", confidence=")
            append("${round3(winner.confidence)}")
            append(").")
        }

        return ConsensusResult(
            agreed = true,
            winningProposal = winner.proposal,
            reasoning = reasoningText,
            details = details
        )
    }

    // --- Scoring helpers ---

    private fun scoreDepth(f: Features): Double {
        // Based on text length and structural cues like steps count.
        val textLen = f.combinedText.length
        // Normalize length against 1200 chars (beyond that saturates).
        val lengthScore = (textLen / 1200.0).coerceIn(0.0, 1.0)
        val stepsScore = (f.stepsCount / 10.0).coerceIn(0.0, 1.0) // up to 10 steps contributes fully
        // Slight bonus for multiple distinct sections detected
        val sectionsScore = (f.sectionCount / 6.0).coerceIn(0.0, 1.0)
        return (0.7 * lengthScore + 0.2 * stepsScore + 0.1 * sectionsScore).coerceIn(0.0, 1.0)
    }

    private fun scoreEdgeCases(f: Features): Double {
        // Count explicit edge case/risk items and textual mentions
        val explicit = f.edgeItems
        val explicitScore = (explicit / 5.0).coerceIn(0.0, 1.0)
        val mentionsScore = if (f.textHasEdgeCaseTerms) 0.5 else 0.0
        // Cap at 1.0
        return (0.7 * explicitScore + 0.3 * mentionsScore).coerceIn(0.0, 1.0)
    }

    private fun scoreTradeOffs(f: Features): Double {
        val explicit = f.tradeOffItems + f.prosItems + f.consItems + f.alternativesItems
        val explicitScore = (explicit / 6.0).coerceIn(0.0, 1.0)
        val mentionsScore = if (f.textHasTradeOffTerms) 0.5 else 0.0
        return (0.7 * explicitScore + 0.3 * mentionsScore).coerceIn(0.0, 1.0)
    }

    // --- Feature extraction ---

    private data class Features(
        val combinedText: String,
        val stepsCount: Int,
        val sectionCount: Int,
        val edgeItems: Int,
        val tradeOffItems: Int,
        val prosItems: Int,
        val consItems: Int,
        val alternativesItems: Int,
        val textHasEdgeCaseTerms: Boolean,
        val textHasTradeOffTerms: Boolean
    )

    private fun extractFeatures(content: Any?): Features {
        val accumulator = FeatureAccumulator()
        accumulate(content, accumulator)

        val combined = (accumulator.texts + accumulator.stringsFromLists).joinToString("\n")
        val textLower = combined.lowercase()
        val hasEdgeTerms = listOf("edge case", "edge-case", "risk", "limitation", "failure mode", "pitfall").any { it in textLower }
        val hasTradeTerms = listOf("trade-off", "tradeoff", "pros", "cons", "alternative", "compare", "versus").any { it in textLower }

        return Features(
            combinedText = combined,
            stepsCount = accumulator.stepsCount,
            sectionCount = accumulator.sectionCount,
            edgeItems = accumulator.edgeCaseItems,
            tradeOffItems = accumulator.tradeOffItems,
            prosItems = accumulator.prosItems,
            consItems = accumulator.consItems,
            alternativesItems = accumulator.alternativesItems,
            textHasEdgeCaseTerms = hasEdgeTerms,
            textHasTradeOffTerms = hasTradeTerms
        )
    }

    private class FeatureAccumulator {
        val texts = mutableListOf<String>()
        val stringsFromLists = mutableListOf<String>()
        var stepsCount: Int = 0
        var sectionCount: Int = 0
        var edgeCaseItems: Int = 0
        var tradeOffItems: Int = 0
        var prosItems: Int = 0
        var consItems: Int = 0
        var alternativesItems: Int = 0
    }

    @Suppress("UNCHECKED_CAST")
    private fun accumulate(value: Any?, acc: FeatureAccumulator, keyHint: String? = null) {
        when (value) {
            null -> Unit
            is String -> {
                acc.texts += value
                // Detect steps by ordered/bulleted lines heuristics
                acc.stepsCount += detectSteps(value)
                acc.sectionCount += detectSections(value)
            }
            is Number, is Boolean -> Unit
            is List<*> -> {
                val nonNulls = value.filterNotNull()
                // If this list seems to represent steps or items, count them
                val key = keyHint?.lowercase()
                when (key) {
                    "steps" -> acc.stepsCount += nonNulls.size
                    "edgecases", "edge_cases", "risks", "limitations", "failuremodes", "failure_modes" -> acc.edgeCaseItems += nonNulls.size
                    "tradeoffs", "trade_offs" -> acc.tradeOffItems += nonNulls.size
                    "pros" -> acc.prosItems += nonNulls.size
                    "cons" -> acc.consItems += nonNulls.size
                    "alternatives" -> acc.alternativesItems += nonNulls.size
                }
                nonNulls.forEach { el ->
                    if (el is String) acc.stringsFromLists += el
                    accumulate(el, acc, key)
                }
            }
            is Map<*, *> -> {
                // Only consider string keys due to JSON-compat constraint
                for ((kAny, v) in value) {
                    val k = (kAny as? String) ?: continue
                    val lk = k.lowercase()
                    when (lk) {
                        "reasoning", "analysis", "justification", "summary", "explanation" -> if (v is String) acc.texts += v
                        "steps" -> if (v is List<*>) acc.stepsCount += v.size
                        "edgecases", "edge_cases", "risks", "limitations", "failuremodes", "failure_modes" -> if (v is List<*>) acc.edgeCaseItems += v.size
                        "tradeoffs", "trade_offs" -> if (v is List<*>) acc.tradeOffItems += v.size
                        "pros" -> if (v is List<*>) acc.prosItems += v.size
                        "cons" -> if (v is List<*>) acc.consItems += v.size
                        "alternatives" -> if (v is List<*>) acc.alternativesItems += v.size
                    }
                    accumulate(v, acc, lk)
                }
                acc.sectionCount += value.size
            }
            else -> Unit // ignore other JSON-compatible forms aren't expected per domain model
        }
    }

    private fun detectSteps(text: String): Int {
        // Heuristic: count lines starting with number/bullet up to a reasonable cap to avoid inflating
        val lines = text.lines()
        var count = 0
        for (ln in lines) {
            val t = ln.trim()
            if (t.isEmpty()) continue
            if (t.matches(Regex("^(?:[-*•]|\u2022) .*"))) count++
            else if (t.matches(Regex("^\\d+\\. .*"))) count++
        }
        return count.coerceAtMost(20)
    }

    private fun detectSections(text: String): Int {
        // Heuristic: headings-like patterns
        val lines = text.lines()
        var count = 0
        for (ln in lines) {
            val t = ln.trim()
            if (t.endsWith(":") && t.length in 3..80) count++
            if (t.matches(Regex("^(?:[A-Z][A-Za-z ]{2,30})$"))) count++
        }
        return count.coerceAtMost(20)
    }
}

private data class ProposalScore(
    val proposal: Proposal,
    val depth: Double,
    val edgeCases: Double,
    val tradeOffs: Double,
    val rawScore: Double,
    val confidence: Double,
    val finalScore: Double
)

private fun round3(v: Double): Double = kotlin.math.round(v * 1000.0) / 1000.0
