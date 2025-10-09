package com.orchestrator.modules.consensus.strategies

import com.orchestrator.domain.Proposal
import com.orchestrator.utils.TokenEstimator
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Token-optimization consensus strategy.
 *
 * Goal: select the proposal that maximizes value per token while considering confidence.
 *
 * Definitions:
 * - tokens(p): If Proposal.tokenUsage is available (>0), use it; otherwise estimate via TokenEstimator
 *              from a deterministic string serialization of proposal content.
 * - quality(p): Heuristic [0..1]. Uses provided numeric field "qualityScore" in content map if present;
 *               else estimates from content length and light structure/keyword detection.
 * - adjustedQuality = quality(p) * (0.5 + 0.5 * confidence)
 * - valueRatio = adjustedQuality / max(tokens(p), 1)
 *
 * Winner = argmax valueRatio with deterministic tie-breaking:
 *   higher confidence, fewer tokens, earlier createdAt, lexicographic id.
 *
 * Edge cases:
 * - Empty input returns agreed=false with explanation.
 * - When all tokens are zero, denominator becomes 1 to avoid division by zero; details include a flag.
 */
class TokenOptimizationStrategy : ConsensusStrategy {

    override val type: ConsensusStrategyType = ConsensusStrategyType.CUSTOM

    override fun evaluate(proposals: List<Proposal>): ConsensusResult {
        if (proposals.isEmpty()) {
            return ConsensusResult(
                agreed = false,
                winningProposal = null,
                reasoning = "No proposals provided; cannot optimize for token efficiency.",
                details = mapOf("totalProposals" to 0)
            )
        }

        val scored = proposals.map { p ->
            val tokens = tokensFor(p)
            val quality = estimateQuality(p.content)
            val adjustedQuality = quality * (0.5 + 0.5 * p.confidence)
            val denom = max(1, tokens)
            val ratio = adjustedQuality / denom.toDouble()
            TokenScore(
                proposal = p,
                tokens = tokens,
                quality = quality,
                adjustedQuality = adjustedQuality,
                ratio = ratio
            )
        }

        val winner = scored.sortedWith(
            compareByDescending<TokenScore> { it.ratio }
                .thenByDescending { it.proposal.confidence }
                .thenBy { it.tokens }
                .thenBy { it.proposal.createdAt }
                .thenBy { it.proposal.id.value }
        ).first()

        val tokenValues = scored.map { it.tokens }
        val avgTokens = if (tokenValues.isNotEmpty()) tokenValues.average() else 0.0
        val maxTokens = tokenValues.maxOrNull() ?: 0
        val minTokens = tokenValues.minOrNull() ?: 0
        val winningTokens = winner.tokens
        val savingsVsAvg = avgTokens - winningTokens
        val savingsPctVsAvg = if (avgTokens > 0) savingsVsAvg / avgTokens else 0.0
        val savingsVsMax = (maxTokens - winningTokens).toDouble()
        val savingsPctVsMax = if (maxTokens > 0) savingsVsMax / maxTokens.toDouble() else 0.0

        val details = mapOf(
            "formula" to "ratio = (quality * (0.5 + 0.5*confidence)) / tokens",
            "proposalScores" to scored.map { s ->
                mapOf(
                    "proposalId" to s.proposal.id.value,
                    "tokens" to s.tokens,
                    "confidence" to s.proposal.confidence,
                    "quality" to round3(s.quality),
                    "adjustedQuality" to round3(s.adjustedQuality),
                    "valueRatio" to round6(s.ratio)
                )
            },
            "aggregate" to mapOf(
                "avgTokens" to round3(avgTokens),
                "maxTokens" to maxTokens,
                "minTokens" to minTokens
            ),
            "savings" to mapOf(
                "winningTokens" to winningTokens,
                "vsAvgTokens" to round3(savingsVsAvg),
                "vsAvgPct" to round3(savingsPctVsAvg * 100),
                "vsMaxTokens" to round3(savingsVsMax),
                "vsMaxPct" to round3(savingsPctVsMax * 100)
            ),
            "winningProposalId" to winner.proposal.id.value
        )

        val reasoning = buildString {
            append("Selected proposal ")
            append(winner.proposal.id.value)
            append(" maximizing value per token: ratio=")
            append(round6(winner.ratio))
            append(", tokens=")
            append(winner.tokens)
            append(", quality=")
            append(round3(winner.quality))
            append(", confidence=")
            append(round3(winner.proposal.confidence))
            append(". Token savings vs avg: ")
            append("${round3(savingsVsAvg)} tokens (")
            append("${round3(savingsPctVsAvg * 100)}%).")
        }

        return ConsensusResult(
            agreed = true,
            winningProposal = winner.proposal,
            reasoning = reasoning,
            details = details
        )
    }

    private fun tokensFor(p: Proposal): Int {
        val used = p.tokenUsage.totalTokens
        if (used > 0) return used
        val text = serialize(p.content)
        return TokenEstimator.estimateTokens(text)
    }

    /**
     * Estimate quality in [0..1].
     * Priority:
     * 1) If content is Map with numeric key "qualityScore" in [0,1], use it.
     * 2) Else compute heuristic from text length and presence of structural keywords.
     */
    private fun estimateQuality(content: Any?): Double {
        if (content is Map<*, *>) {
            val q = (content["qualityScore"] as? Number)?.toDouble()
            if (q != null && q in 0.0..1.0) return q
        }
        val text = serialize(content)
        if (text.isEmpty()) return 0.0

        val length = text.length
        // Map length to [0..~0.85] using a logarithmic curve (diminishing returns past ~2k chars)
        val lenScore = run {
            val normalized = ln(1.0 + length.toDouble()) / ln(1.0 + 2000.0)
            min(0.85, normalized)
        }
        val lower = text.lowercase()
        val keywords = listOf("reasoning", "analysis", "steps", "edge", "risk", "limitation", "trade-off", "tradeoff", "pros", "cons", "alternative")
        val hits = keywords.count { lower.contains(it) }
        val structScore = min(0.15, hits * 0.03)
        return (lenScore + structScore).coerceIn(0.0, 1.0)
    }

    private fun serialize(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is Number, is Boolean -> value.toString()
        is List<*> -> value.joinToString(separator = "\n") { serialize(it) }
        is Map<*, *> -> value.entries
            .sortedBy { (k, _) -> (k as? String) ?: k.toString() }
            .joinToString(separator = "\n") { (k, v) ->
                val ks = (k as? String) ?: k.toString()
                "$ks: ${serialize(v)}"
            }
        else -> value.toString()
    }
}

private data class TokenScore(
    val proposal: Proposal,
    val tokens: Int,
    val quality: Double,
    val adjustedQuality: Double,
    val ratio: Double
)

private fun round3(v: Double): Double = kotlin.math.round(v * 1000.0) / 1000.0
private fun round6(v: Double): Double = kotlin.math.round(v * 1_000_000.0) / 1_000_000.0
