package com.orchestrator.modules.routing

/**
 * Lightweight, fast task classifier based on keyword and length heuristics.
 *
 * Goals:
 * - Complexity scored 1..10
 * - Risk scored 1..10
 * - Detect critical keywords (security, auth, payment, etc.)
 * - Confidence score in [0.0, 1.0]
 * - Execute in microseconds/milliseconds range (<10ms typical)
 */
object TaskClassifier {

    data class Result(
        val complexity: Int, // 1..10
        val risk: Int,       // 1..10
        val criticalKeywords: Set<String>,
        val confidence: Double // 0.0..1.0
    )

    // Critical domains that tend to elevate risk substantially
    // Stored lowercase; we do lowercase matching against description
    private val criticalKeywords = setOf(
        "security", "vulnerability", "xss", "csrf", "injection", "auth", "authentication",
        "authorization", "oauth", "jwt", "payment", "payments", "checkout", "pci",
        "encryption", "cryptography", "crypto", "gdpr", "pii", "secrets", "token",
        "admin", "privilege", "rce", "dos", "ddos"
    )

    // Signals that increase complexity (breadth, coordination, architecture)
    private val complexitySignals = mapOf(
        "refactor" to 1,
        "architecture" to 2,
        "migrate" to 2,
        "integration" to 2,
        "multiple" to 1,
        "consensus" to 1,
        "parallel" to 1,
        "optimize" to 1,
        "performance" to 1,
        "scal" to 2, // scale/scaling/scalable
        "concurrent" to 2,
        "distributed" to 2,
        "protocol" to 1,
        "kafka" to 1,
        "database" to 1,
        "index" to 1,
        "cache" to 1,
        "latency" to 1
    )

    // Signals that increase risk (data loss, prod impact, compliance)
    private val riskSignals = mapOf(
        "delete" to 1,
        "drop" to 1,
        "migration" to 2,
        "billing" to 2,
        "payout" to 2,
        "production" to 2,
        "outage" to 2,
        "hotfix" to 1,
        "critical" to 1,
        "incident" to 1,
        "compliance" to 2,
        "legal" to 2,
        "sla" to 1,
        "downtime" to 2
    )

    /**
     * Full classification returning complexity, risk, critical keywords and confidence.
     */
    fun classify(description: String): Result {
        val text = description.trim()
        if (text.isEmpty()) return Result(1, 1, emptySet(), 0.2)

        val normalized = text.lowercase()

        val baseComplexity = estimateComplexity(normalized)
        val baseRisk = estimateRisk(normalized)
        val critical = detectCriticalKeywords(normalized)

        // Confidence heuristic: combine normalized signal strengths from keyword hits and length signal
        val (complexitySignal, riskSignal, lengthSignal) = computeSignals(normalized)
        val rawConfidence = 0.45 * sigmoid(complexitySignal.toDouble()) +
                0.45 * sigmoid(riskSignal.toDouble()) +
                0.10 * sigmoid(lengthSignal.toDouble())
        val confidence = rawConfidence.coerceIn(0.0, 1.0)

        return Result(
            complexity = baseComplexity,
            risk = baseRisk,
            criticalKeywords = critical,
            confidence = confidence
        )
    }

    /**
     * Estimate relative complexity from 1..10 using:
     * - length-based scoring (more words -> more complexity, with diminishing returns)
     * - keyword-based additions
     */
    fun estimateComplexity(description: String): Int {
        val normalized = description.lowercase()
        val words = splitWords(normalized)
        val lengthScore = when (words.size) {
            in 0..5 -> 1
            in 6..12 -> 2
            in 13..25 -> 3
            in 26..40 -> 4
            in 41..70 -> 5
            in 71..110 -> 6
            in 111..170 -> 7
            else -> 8
        }

        var keywordScore = 0
        for ((kw, w) in complexitySignals) {
            if (normalized.contains(kw)) keywordScore += w
        }

        // Critical keywords also add a small amount to complexity (they often require more diligence)
        keywordScore += (detectCriticalKeywords(normalized).size).coerceAtMost(2)

        val raw = 1 + lengthScore + keywordScore
        return raw.coerceIn(1, 10)
    }

    /**
     * Estimate relative risk from 1..10 using:
     * - critical keywords significantly increase risk
     * - risk keyword heuristics
     * - small length component (longer tasks can hide more edge cases)
     */
    fun estimateRisk(description: String): Int {
        val normalized = description.lowercase()
        val words = splitWords(normalized)

        var score = 1

        // Critical domains carry higher weight
        val critical = detectCriticalKeywords(normalized)
        score += when (critical.size) {
            0 -> 0
            1 -> 2
            2 -> 3
            else -> 4
        }

        // Risk keywords
        for ((kw, w) in riskSignals) {
            if (normalized.contains(kw)) score += w
        }

        // Slightly raise risk for long specs
        score += when (words.size) {
            in 0..12 -> 0
            in 13..40 -> 1
            in 41..100 -> 2
            else -> 3
        }

        return score.coerceIn(1, 10)
    }

    /** Returns a set of matched critical keywords (lowercase) */
    fun detectCriticalKeywords(description: String): Set<String> {
        if (description.isEmpty()) return emptySet()
        val text = description.lowercase()
        val hits = HashSet<String>(4)
        for (kw in criticalKeywords) {
            if (text.contains(kw)) hits.add(kw)
        }
        return hits
    }

    // Internal helpers
    private fun splitWords(text: String): List<String> = text.split(Regex("\\s+"))
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toList()

    private fun computeSignals(text: String): Triple<Int, Int, Int> {
        var complexitySignal = 0
        for ((kw, w) in complexitySignals) if (text.contains(kw)) complexitySignal += w
        var riskSignal = 0
        for ((kw, w) in riskSignals) if (text.contains(kw)) riskSignal += w
        val lengthSignal = splitWords(text).size / 20 // coarse, grows slowly
        return Triple(complexitySignal, riskSignal, lengthSignal)
    }

    private fun sigmoid(x: Double): Double {
        // Simple bounded mapping; fast approximation is fine
        return 1.0 / (1.0 + kotlin.math.exp(-x))
    }
}
