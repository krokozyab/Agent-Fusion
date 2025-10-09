package com.orchestrator.modules.routing

import com.orchestrator.core.AgentRegistry
import com.orchestrator.domain.AgentId
import com.orchestrator.domain.UserDirective
import kotlin.math.exp

/**
 * Parses natural language user requests to extract routing directives.
 *
 * Keyword sets are based on CONVERSATION_HANDOFF_WORKFLOW.md -> "Directive Keywords Reference".
 * We also support a few reasonable synonyms and robustness for punctuation/case.
 *
 * Priority rules when conflicting signals are found:
 * - Explicit agent assignment has highest priority (assignToAgent)
 * - Emergency defaults to prevent=true UNLESS user explicitly mentions consensus
 * - Otherwise, the side (force vs prevent) with stronger weighted score wins
 * - Ties default to forceConsensus=false, preventConsensus=false (ambiguous -> neutral)
 */
class DirectiveParser(private val agentRegistry: AgentRegistry) {

    private val whitespace = Regex("\\s+")

    private data class DirectivePattern(
        val regex: Regex,
        val weight: Double,
        val capturesAgent: Boolean = false,
        val assignmentWeight: Double? = null,
        val note: String = ""
    )

    private data class AssignmentPattern(
        val regex: Regex,
        val weight: Double,
        val fixedAlias: String? = null,
        val captureGroupIndex: Int? = 1,
        val note: String = ""
    )

    private data class SimplePattern(
        val regex: Regex,
        val weight: Double,
        val note: String = ""
    )

    // Agent alias map built from registry (lowercase normalized)
    private val agentAliases: Map<String, AgentId> = buildAliasMap()

    // Core keyword patterns with weights (case-insensitive)
    private val forceConsensusPatterns = listOf(
        DirectivePattern(
            regex = Regex("\\bneed\\s+consensus\\b", RegexOption.IGNORE_CASE),
            weight = 1.2,
            note = "explicit consensus requirement"
        ),
        DirectivePattern(
            regex = Regex("\\bconsensus\\s+required\\b", RegexOption.IGNORE_CASE),
            weight = 1.2,
            note = "explicit consensus requirement"
        ),
        DirectivePattern(
            regex = Regex("\\bconsensus\\s+please\\b", RegexOption.IGNORE_CASE),
            weight = 0.8,
            note = "polite consensus request"
        ),
        DirectivePattern(
            regex = Regex("\\bget\\s+([a-z0-9_\'-]+)\\s*'?s?\\s+input\\b", RegexOption.IGNORE_CASE),
            weight = 1.0,
            capturesAgent = true,
            assignmentWeight = 0.9,
            note = "requesting specific agent input"
        ),
        DirectivePattern(
            regex = Regex("\\bwant\\s+([a-z0-9_\'-]+)\\s+to\\s+review\\b", RegexOption.IGNORE_CASE),
            weight = 1.0,
            capturesAgent = true,
            assignmentWeight = 0.9,
            note = "explicit agent review request"
        ),
        DirectivePattern(
            regex = Regex("\\bcheck\\s+with\\s+([a-z0-9_\'-]+)\\b", RegexOption.IGNORE_CASE),
            weight = 0.9,
            capturesAgent = true,
            assignmentWeight = 0.8,
            note = "check-in with named agent"
        ),
        DirectivePattern(
            regex = Regex("\\bask\\s+([a-z0-9_\'-]+)\\s+about\\b", RegexOption.IGNORE_CASE),
            weight = 0.9,
            capturesAgent = true,
            assignmentWeight = 0.8,
            note = "ask named agent for guidance"
        ),
        DirectivePattern(
            regex = Regex("\\bhave\\s+([a-z0-9_\'-]+)\\s+look\\s+at\\b", RegexOption.IGNORE_CASE),
            weight = 0.9,
            capturesAgent = true,
            assignmentWeight = 0.8,
            note = "have agent review"
        ),
        DirectivePattern(
            regex = Regex("\\bneed\\s+([a-z0-9_\'-]+)\\s*'?s?\\s+opinion\\b", RegexOption.IGNORE_CASE),
            weight = 0.9,
            capturesAgent = true,
            assignmentWeight = 0.8,
            note = "ask for agent's opinion"
        ),
        DirectivePattern(
            regex = Regex("\\bask\\s+([a-z0-9_\'-]+)\\s+to\\s+review\\b", RegexOption.IGNORE_CASE),
            weight = 1.0,
            capturesAgent = true,
            assignmentWeight = 0.9,
            note = "request agent review"
        )
    )

    private val preventConsensusPatterns = listOf(
        DirectivePattern(
            regex = Regex("\\bsolo\\b", RegexOption.IGNORE_CASE),
            weight = 1.1,
            note = "explicit solo keyword"
        ),
        DirectivePattern(
            regex = Regex("\\bno\\s+consensus\\b", RegexOption.IGNORE_CASE),
            weight = 1.2,
            note = "explicit no consensus"
        ),
        DirectivePattern(
            regex = Regex("\\bskip\\s+consensus\\b", RegexOption.IGNORE_CASE),
            weight = 1.2,
            note = "skip consensus directive"
        ),
        DirectivePattern(
            regex = Regex("\\bskip\\s+review\\b", RegexOption.IGNORE_CASE),
            weight = 1.0,
            note = "skip review directive"
        ),
        DirectivePattern(
            regex = Regex("\\bjust\\s+implement\\b", RegexOption.IGNORE_CASE),
            weight = 0.9,
            note = "preference for quick implementation"
        ),
        DirectivePattern(
            regex = Regex("\\bquick\\s+fix\\b", RegexOption.IGNORE_CASE),
            weight = 0.9,
            note = "quick fix directive"
        ),
        DirectivePattern(
            regex = Regex("\\bhotfix\\b", RegexOption.IGNORE_CASE),
            weight = 1.0,
            note = "hotfix directive"
        ),
        DirectivePattern(
            regex = Regex("\\bi'?ll\\s+handle\\s+this\\b", RegexOption.IGNORE_CASE),
            weight = 0.8,
            note = "self-assignment preference"
        )
    )

    private val assignmentPatterns = listOf(
        AssignmentPattern(
            regex = Regex("\\bask\\s+codex\\b", RegexOption.IGNORE_CASE),
            weight = 1.3,
            fixedAlias = "codex",
            note = "directly asks Codex"
        ),
        AssignmentPattern(
            regex = Regex("\\bcodex\\s*,", RegexOption.IGNORE_CASE),
            weight = 1.1,
            fixedAlias = "codex",
            note = "addresses Codex by name"
        ),
        AssignmentPattern(
            regex = Regex("\\bhave\\s+codex\\b", RegexOption.IGNORE_CASE),
            weight = 1.2,
            fixedAlias = "codex",
            note = "assigns work to Codex"
        ),
        AssignmentPattern(
            regex = Regex("\\bget\\s+codex\\s+to\\b", RegexOption.IGNORE_CASE),
            weight = 1.2,
            fixedAlias = "codex",
            note = "requests Codex to act"
        ),
        AssignmentPattern(
            regex = Regex("\\bcodex\\s+should\\b", RegexOption.IGNORE_CASE),
            weight = 1.1,
            fixedAlias = "codex"
        ),
        AssignmentPattern(
            regex = Regex("\\bneed\\s+codex\\b", RegexOption.IGNORE_CASE),
            weight = 1.0,
            fixedAlias = "codex"
        ),
        AssignmentPattern(
            regex = Regex("\\blet\\s+codex\\s+handle\\b", RegexOption.IGNORE_CASE),
            weight = 1.3,
            fixedAlias = "codex"
        ),
        AssignmentPattern(
            regex = Regex("\\bpass\\s+to\\s+codex\\b", RegexOption.IGNORE_CASE),
            weight = 1.2,
            fixedAlias = "codex"
        ),
        AssignmentPattern(
            regex = Regex("\\bcodex\\s+can\\s+do\\b", RegexOption.IGNORE_CASE),
            weight = 1.0,
            fixedAlias = "codex"
        ),
        AssignmentPattern(
            regex = Regex("\\bask\\s+claude\\b", RegexOption.IGNORE_CASE),
            weight = 1.3,
            fixedAlias = "claude",
            note = "directly asks Claude"
        ),
        AssignmentPattern(
            regex = Regex("\\bclaude\\s*,", RegexOption.IGNORE_CASE),
            weight = 1.1,
            fixedAlias = "claude",
            note = "addresses Claude by name"
        ),
        AssignmentPattern(
            regex = Regex("\\bhave\\s+claude\\b", RegexOption.IGNORE_CASE),
            weight = 1.2,
            fixedAlias = "claude"
        ),
        AssignmentPattern(
            regex = Regex("\\bget\\s+claude\\s+to\\b", RegexOption.IGNORE_CASE),
            weight = 1.2,
            fixedAlias = "claude"
        ),
        AssignmentPattern(
            regex = Regex("\\bclaude\\s+should\\b", RegexOption.IGNORE_CASE),
            weight = 1.1,
            fixedAlias = "claude"
        ),
        AssignmentPattern(
            regex = Regex("\\bneed\\s+claude\\b", RegexOption.IGNORE_CASE),
            weight = 1.0,
            fixedAlias = "claude"
        ),
        AssignmentPattern(
            regex = Regex("\\blet\\s+claude\\s+handle\\b", RegexOption.IGNORE_CASE),
            weight = 1.3,
            fixedAlias = "claude"
        ),
        AssignmentPattern(
            regex = Regex("\\bpass\\s+to\\s+claude\\b", RegexOption.IGNORE_CASE),
            weight = 1.2,
            fixedAlias = "claude"
        ),
        AssignmentPattern(
            regex = Regex("\\bclaude\\s+can\\s+do\\b", RegexOption.IGNORE_CASE),
            weight = 1.0,
            fixedAlias = "claude"
        ),
        AssignmentPattern(
            regex = Regex("@([a-z0-9_-]+)", RegexOption.IGNORE_CASE),
            weight = 0.9,
            note = "@mention assignment"
        )
    )

    private val conjunctionAgentPattern = Regex("\\b(?:and|&)\\s+([a-z0-9_\'-]+)\\b", RegexOption.IGNORE_CASE)

    private val emergencyPatterns = listOf(
        SimplePattern(
            regex = Regex("\\bemergency\\b", RegexOption.IGNORE_CASE),
            weight = 0.9,
            note = "explicit emergency"
        ),
        SimplePattern(
            regex = Regex("\\bproduction\\s+down\\b", RegexOption.IGNORE_CASE),
            weight = 1.4,
            note = "production outage"
        ),
        SimplePattern(
            regex = Regex("\\burgent\\b", RegexOption.IGNORE_CASE),
            weight = 0.8,
            note = "urgent keyword"
        ),
        SimplePattern(
            regex = Regex("\\basap\\b", RegexOption.IGNORE_CASE),
            weight = 0.8,
            note = "ASAP keyword"
        ),
        SimplePattern(
            regex = Regex("\\bimmediately\\b", RegexOption.IGNORE_CASE),
            weight = 0.9,
            note = "immediate action"
        ),
        SimplePattern(
            regex = Regex("\\bcritical\\b", RegexOption.IGNORE_CASE),
            weight = 0.9,
            note = "critical keyword"
        ),
        SimplePattern(
            regex = Regex("\\bsev[0-1]\\b", RegexOption.IGNORE_CASE),
            weight = 1.3,
            note = "severity indicator"
        )
    )

    private val forceNegationPattern = Regex(
        "\\b(don't|dont|do\\s+not|no|not|never)\\s+(really\\s+)?(need|want|require|do\\s+we\\s+need)\\s+(a\\s+)?consensus\\b",
        RegexOption.IGNORE_CASE
    )

    private val modalSoftenerPattern = Regex("\\b(maybe|might|could|can you|possible|if you can)\\b", RegexOption.IGNORE_CASE)

    fun parseUserDirective(request: String): UserDirective {
        val text = request.trim()
        if (text.isEmpty()) return UserDirective(originalText = request)

        val normalized = text.replace(whitespace, " ")
        val notes = mutableListOf<String>()
        val mentionedAgents = mutableSetOf<AgentId>()
        val assignmentScores = mutableMapOf<AgentId, Double>()

        var forceScore = evaluateDirectivePatterns(
            normalized,
            forceConsensusPatterns,
            mentionedAgents,
            assignmentScores,
            signal = "force",
            notes = notes
        )

        var preventScore = evaluateDirectivePatterns(
            normalized,
            preventConsensusPatterns,
            mentionedAgents,
            assignmentScores,
            signal = "prevent",
            notes = notes
        )

        val emergencyScore = evaluateEmergencyPatterns(normalized, notes)
        evaluateAssignmentPatterns(normalized, assignmentPatterns, mentionedAgents, assignmentScores, notes)
        detectConjunctionAgents(normalized, mentionedAgents, assignmentScores, notes)

        // Negation handling (e.g., "don't need consensus")
        if (forceNegationPattern.containsMatchIn(normalized)) {
            notes.add("negation: detected consensus negation -> boosting prevent")
            preventScore += 0.9
            forceScore *= 0.25
        }

        // Soft modal verbs reduce confidence slightly
        if (modalSoftenerPattern.containsMatchIn(normalized)) {
            notes.add("context: modal language detected -> softening directive scores")
            forceScore *= 0.9
            preventScore *= 0.9
        }

        if (mentionedAgents.size >= 2) {
            notes.add("context: multiple agents referenced -> boosting force score")
            forceScore += MULTI_AGENT_FORCE_BOOST
        }

        val forceConfidence = scoreToConfidence(forceScore)
        val preventConfidence = scoreToConfidence(preventScore)
        val emergencyConfidence = scoreToConfidence(emergencyScore)
        val isEmergency = emergencyScore > 0.0

        val bestAssignment = assignmentScores.maxByOrNull { it.value }
        val assignmentConfidence = bestAssignment?.let { scoreToConfidence(it.value) } ?: 0.0
        val assignToAgent = bestAssignment?.key

        val assignmentOrder = assignmentScores.entries
            .sortedWith(compareByDescending<Map.Entry<AgentId, Double>> { it.value }
                .thenBy { it.key.value })
            .map { it.key }

        val additionalMentions = mentionedAgents.filterNot { assignmentScores.containsKey(it) }
            .sortedBy { it.value }

        val assignedAgents = (assignmentOrder + additionalMentions).ifEmpty { null }

        val (force, prevent) = resolveDirectiveFlags(
            isEmergency = isEmergency,
            forceScore = forceScore,
            preventScore = preventScore,
            forceConfidence = forceConfidence,
            preventConfidence = preventConfidence,
            notes = notes
        )

        return UserDirective(
            originalText = request,
            forceConsensus = force,
            preventConsensus = prevent,
            assignToAgent = assignToAgent,
            assignedAgents = assignedAgents,
            isEmergency = isEmergency,
            forceConsensusConfidence = forceConfidence,
            preventConsensusConfidence = preventConfidence,
            assignmentConfidence = assignmentConfidence,
            emergencyConfidence = emergencyConfidence,
            parsingNotes = notes.take(MAX_NOTES)
        )
    }

    private fun evaluateDirectivePatterns(
        text: String,
        patterns: List<DirectivePattern>,
        mentionedAgents: MutableSet<AgentId>,
        assignmentScores: MutableMap<AgentId, Double>,
        signal: String,
        notes: MutableList<String>
    ): Double {
        var score = 0.0
        patterns.forEach { pattern ->
            pattern.regex.findAll(text).forEach { match ->
                var appliedWeight = pattern.weight
                var resolvedAgent: AgentId? = null

                if (pattern.capturesAgent) {
                    val capturedName = match.groupValues.getOrNull(1)?.trim().orEmpty()
                    val sanitizedName = sanitizeAgentToken(capturedName)
                    if (sanitizedName.isEmpty()) {
                        appliedWeight = 0.0
                    } else {
                        resolvedAgent = resolveAgentName(sanitizedName)
                        if (resolvedAgent == null) {
                            notes.add("$signal: skipped '${match.value}' (unknown agent '$capturedName')")
                            appliedWeight = 0.0
                        }
                    }
                }

                if (appliedWeight > 0.0) {
                    score += appliedWeight
                    resolvedAgent?.let { mentionedAgents.add(it) }

                    if (pattern.assignmentWeight != null && resolvedAgent != null) {
                        val assignmentWeight = pattern.assignmentWeight
                        assignmentScores[resolvedAgent] = assignmentScores.getOrDefault(resolvedAgent, 0.0) + assignmentWeight
                        notes.add("assignment: inferred via '${match.value}' (+${"%.2f".format(assignmentWeight)})")
                    }

                    val description = if (pattern.note.isNotEmpty()) " - ${pattern.note}" else ""
                    val agentSuffix = resolvedAgent?.let { " agent=${it.value}" } ?: ""
                    notes.add("$signal: matched '${match.value}' (weight=${"%.2f".format(pattern.weight)})$agentSuffix$description")
                }
            }
        }
        return score
    }

    private fun evaluateAssignmentPatterns(
        text: String,
        patterns: List<AssignmentPattern>,
        mentionedAgents: MutableSet<AgentId>,
        assignmentScores: MutableMap<AgentId, Double>,
        notes: MutableList<String>
    ) {
        patterns.forEach { pattern ->
            pattern.regex.findAll(text).forEach { match ->
                val alias = pattern.fixedAlias ?: pattern.captureGroupIndex?.let { index ->
                    match.groupValues.getOrNull(index)?.trim()
                }

                if (alias.isNullOrEmpty()) return@forEach

                val sanitizedAlias = sanitizeAgentToken(alias)
                if (sanitizedAlias.isEmpty()) return@forEach

                val resolvedAgent = resolveAgentName(sanitizedAlias)
                if (resolvedAgent != null) {
                    mentionedAgents.add(resolvedAgent)
                    assignmentScores[resolvedAgent] = assignmentScores.getOrDefault(resolvedAgent, 0.0) + pattern.weight
                    val noteSuffix = if (pattern.note.isNotEmpty()) " (${pattern.note})" else ""
                    notes.add("assignment: matched '${match.value}' -> ${resolvedAgent.value} (+${"%.2f".format(pattern.weight)})$noteSuffix")
                } else {
                    notes.add("assignment: skipped '${match.value}' (unknown agent '$alias')")
                }
            }
        }
    }

    private fun detectConjunctionAgents(
        text: String,
        mentionedAgents: MutableSet<AgentId>,
        assignmentScores: MutableMap<AgentId, Double>,
        notes: MutableList<String>
    ) {
        conjunctionAgentPattern.findAll(text).forEach { match ->
            val rawName = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (rawName.isEmpty()) return@forEach

            val sanitized = sanitizeAgentToken(rawName)
            if (sanitized.isEmpty()) return@forEach

            val resolved = resolveAgentName(sanitized) ?: return@forEach
            mentionedAgents.add(resolved)
            val updated = assignmentScores.getOrDefault(resolved, 0.0) + CONJUNCTION_ASSIGNMENT_WEIGHT
            assignmentScores[resolved] = updated
            notes.add(
                "assignment: inferred via conjunction '${match.value}' -> ${resolved.value} (+${"%.2f".format(CONJUNCTION_ASSIGNMENT_WEIGHT)})"
            )
        }
    }

    private fun evaluateEmergencyPatterns(text: String, notes: MutableList<String>): Double {
        var score = 0.0
        emergencyPatterns.forEach { pattern ->
            pattern.regex.findAll(text).forEach { match ->
                score += pattern.weight
                val description = if (pattern.note.isNotEmpty()) " - ${pattern.note}" else ""
                notes.add("emergency: matched '${match.value}' (weight=${"%.2f".format(pattern.weight)})$description")
            }
        }
        return score
    }

    private fun resolveDirectiveFlags(
        isEmergency: Boolean,
        forceScore: Double,
        preventScore: Double,
        forceConfidence: Double,
        preventConfidence: Double,
        notes: MutableList<String>
    ): Pair<Boolean, Boolean> {
        if (isEmergency && forceScore <= MIN_FORCE_FOR_EMERGENCY) {
            notes.add("resolution: emergency without explicit consensus -> prevent")
            return false to true
        }

        if (isEmergency && forceScore > MIN_FORCE_FOR_EMERGENCY) {
            notes.add("resolution: emergency with explicit consensus -> force")
            return true to false
        }

        val difference = forceScore - preventScore
        return when {
            difference >= FORCE_ADVANTAGE_THRESHOLD && forceConfidence >= MIN_CONFIDENCE -> {
                notes.add("resolution: force score advantage ${"%.2f".format(difference)}")
                true to false
            }
            difference <= -FORCE_ADVANTAGE_THRESHOLD && preventConfidence >= MIN_CONFIDENCE -> {
                notes.add("resolution: prevent score advantage ${"%.2f".format(-difference)}")
                false to true
            }
            else -> {
                notes.add("resolution: scores within tolerance -> neutral")
                false to false
            }
        }
    }

    private fun scoreToConfidence(score: Double): Double = (1 - exp(-score)).coerceIn(0.0, 1.0)

    private fun sanitizeAgentToken(token: String): String {
        var candidate = token.trim()
        if (candidate.isEmpty()) return candidate

        val lower = candidate.lowercase()
        if (lower.endsWith("'s") || lower.endsWith("’s")) {
            candidate = candidate.dropLast(2)
        }

        candidate = candidate.trimEnd {
            it == '\'' || it == '’' || it == ',' || it == '.' || it == '!' || it == '?' || it == ':'
        }

        return candidate.trim()
    }

    /**
     * Build alias map from registry agents.
     * Maps common variations to actual agent IDs.
     */
    private fun buildAliasMap(): Map<String, AgentId> {
        val map = mutableMapOf<String, AgentId>()
        agentRegistry.getAllAgents().forEach { agent ->
            val id = agent.id
            val idLower = id.value.lowercase()
            val displayLower = agent.displayName.lowercase()

            // Map ID and display name as-is
            map[idLower] = id
            map[displayLower] = id

            // Map display name without whitespace for robustness
            val displayCompact = displayLower.replace(" ", "")
            if (displayCompact != displayLower) {
                map[displayCompact] = id
            }

            // Common abbreviations for known agents
            when {
                "codex" in idLower || "codex" in displayLower -> {
                    map["codex"] = id
                }
                "claude" in idLower || "claude" in displayLower -> {
                    map["claude"] = id
                }
                "gemini" in idLower || "gemini" in displayLower -> {
                    map["gemini"] = id
                }
                "qwen" in idLower || "qwen" in displayLower -> {
                    map["qwen"] = id
                }
            }

            // Map variations with/without dashes/underscores
            val normalized = idLower.replace("-", "").replace("_", "")
            if (normalized != idLower) {
                map[normalized] = id
            }
        }
        return map
    }

    /**
     * Resolve agent name from text using registry.
     * Returns null if name doesn't match any known agent.
     */
    private fun resolveAgentName(name: String): AgentId? {
        val normalized = sanitizeAgentToken(name).trim().lowercase()
        if (normalized.length < 2) return null

        agentAliases[normalized]?.let { return it }

        val closestAlias = agentAliases.entries
            .map { it.key to levenshteinDistance(normalized, it.key) }
            .minByOrNull { it.second }

        if (closestAlias != null && closestAlias.second <= MAX_LEVENSHTEIN_DISTANCE) {
            agentAliases[closestAlias.first]?.let { return it }
        }

        // Try exact ID/display name matches
        agentRegistry.getAllAgents().forEach { agent ->
            if (agent.id.value.equals(normalized, ignoreCase = true)) return agent.id
            if (agent.displayName.equals(normalized, ignoreCase = true)) return agent.id
            val compactDisplay = agent.displayName.lowercase().replace(" ", "")
            if (compactDisplay == normalized) return agent.id
        }

        // Fall back to fuzzy matching with tight threshold
        val candidates = agentRegistry.getAllAgents()
        val bestMatch = candidates
            .map { agent ->
                val displayDistance = levenshteinDistance(normalized, agent.displayName.lowercase())
                val idDistance = levenshteinDistance(normalized, agent.id.value.lowercase())
                val compactDistance = levenshteinDistance(normalized, agent.id.value.lowercase().replace("-", "").replace("_", ""))
                val distance = listOf(displayDistance, idDistance, compactDistance).minOrNull() ?: Int.MAX_VALUE
                agent.id to distance
            }
            .minByOrNull { it.second }

        return when {
            bestMatch == null -> null
            bestMatch.second == 0 -> bestMatch.first
            bestMatch.second <= MAX_LEVENSHTEIN_DISTANCE -> bestMatch.first
            else -> null
        }
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val rows = a.length + 1
        val cols = b.length + 1
        val dp = Array(rows) { IntArray(cols) }

        for (i in 0 until rows) {
            dp[i][0] = i
        }
        for (j in 0 until cols) {
            dp[0][j] = j
        }

        for (i in 1 until rows) {
            for (j in 1 until cols) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }

        return dp[a.length][b.length]
    }

    private companion object {
        private const val MIN_FORCE_FOR_EMERGENCY = 0.2
        private const val FORCE_ADVANTAGE_THRESHOLD = 0.2
        private const val MIN_CONFIDENCE = 0.35
        private const val MAX_NOTES = 25
        private const val MAX_LEVENSHTEIN_DISTANCE = 2
        private const val MULTI_AGENT_FORCE_BOOST = 0.6
        private const val CONJUNCTION_ASSIGNMENT_WEIGHT = 0.6
    }
}
