package com.orchestrator.modules.routing

import com.orchestrator.config.ConfigLoader
import com.orchestrator.core.AgentRegistry
import com.orchestrator.domain.AgentConfig
import com.orchestrator.domain.AgentId
import com.orchestrator.domain.AgentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectiveParserTest {

    private fun createRegistry(): AgentRegistry {
        val defs = listOf(
            ConfigLoader.AgentDefinition(
                AgentId("codex-cli"),
                AgentType.CODEX_CLI,
                AgentConfig(name = "Codex CLI", model = "codex")
            ),
            ConfigLoader.AgentDefinition(
                AgentId("claude-code"),
                AgentType.CLAUDE_CODE,
                AgentConfig(name = "Claude Code", model = "claude")
            )
        )
        return AgentRegistry.build(defs)
    }

    private val parser = DirectiveParser(createRegistry())

    @Test
    fun detectsForceConsensusPhrases() {
        val d1 = parser.parseUserDirective("We need consensus on this change")
        assertTrue(d1.forceConsensus, "expected force consensus; notes=${d1.parsingNotes}")
        assertFalse(d1.preventConsensus)

        val d2 = parser.parseUserDirective("Consensus required before merging")
        assertTrue(d2.forceConsensus, "expected force consensus; notes=${d2.parsingNotes}")

        val d3 = parser.parseUserDirective("Can you get Codex's input?")
        assertTrue(d3.forceConsensus, "expected force consensus; notes=${d3.parsingNotes}")
        assertEquals(AgentId("codex-cli"), d3.assignToAgent)
    }

    @Test
    fun detectsPreventConsensusPhrases() {
        val d1 = parser.parseUserDirective("Let's go solo on this one")
        assertTrue(d1.preventConsensus)
        assertFalse(d1.forceConsensus)

        val d2 = parser.parseUserDirective("skip consensus and ship it")
        assertTrue(d2.preventConsensus)

        val d3 = parser.parseUserDirective("just implement the fix")
        assertTrue(d3.preventConsensus)
    }

    @Test
    fun detectsEmergencyAndDefaultsPrevent() {
        // Emergency without consensus request defaults to prevent
        val d1 = parser.parseUserDirective("Emergency: production down. Skip review and ship")
        assertTrue(d1.isEmergency)
        assertTrue(d1.preventConsensus)

        val d2 = parser.parseUserDirective("ASAP hotfix needed")
        assertTrue(d2.isEmergency)
        assertTrue(d2.preventConsensus)

        // Emergency WITH consensus request honors the force
        val d3 = parser.parseUserDirective("URGENT need consensus from both agents")
        assertTrue(d3.isEmergency)
        assertTrue(d3.forceConsensus)
        assertFalse(d3.preventConsensus)
    }

    @Test
    fun detectsAgentAssignmentsWithCorrectIDs() {
        val a1 = parser.parseUserDirective("Ask Codex to review the API design")
        assertEquals(AgentId("codex-cli"), a1.assignToAgent)
        assertTrue(a1.forceConsensus)
        assertTrue(a1.forceConsensusConfidence > 0.5)

        val a2 = parser.parseUserDirective("Claude, please draft the plan")
        assertEquals(AgentId("claude-code"), a2.assignToAgent)

        val a3 = parser.parseUserDirective("Route to @codex-cli for implementation")
        assertEquals(AgentId("codex-cli"), a3.assignToAgent)
        assertTrue(a3.assignmentConfidence > 0.4)

        val a4 = parser.parseUserDirective("Let codex handle this")
        assertEquals(AgentId("codex-cli"), a4.assignToAgent)

        val a5 = parser.parseUserDirective("pass to claude")
        assertEquals(AgentId("claude-code"), a5.assignToAgent)
    }

    @Test
    fun rejectsFalsePositiveAgentNames() {
        // "get user input" should NOT detect agent assignment or force consensus
        val d1 = parser.parseUserDirective("Need to get user input for the form")
        assertNull(d1.assignToAgent)
        assertFalse(d1.forceConsensus)

        val d2 = parser.parseUserDirective("validate input fields")
        assertNull(d2.assignToAgent)
        assertFalse(d2.forceConsensus)
    }

    @Test
    fun detectsMultipleAgentMentions() {
        val d1 = parser.parseUserDirective("Have Codex and Claude review this design")
        assertTrue(d1.forceConsensus, "expected force consensus for multi-agent mention; notes=${d1.parsingNotes}")
        assertEquals(2, d1.assignedAgents?.size)
        assertTrue(d1.assignedAgents?.contains(AgentId("codex-cli")) ?: false)
        assertTrue(d1.assignedAgents?.contains(AgentId("claude-code")) ?: false)

        val d2 = parser.parseUserDirective("Check with @codex-cli and @claude-code")
        assertEquals(2, d2.assignedAgents?.size)
    }

    @Test
    fun negationFlipsConsensusSignals() {
        val directive = parser.parseUserDirective("Ship the feature but don't need consensus this time")
        assertTrue(directive.preventConsensus)
        assertFalse(directive.forceConsensus)
        assertTrue(directive.preventConsensusConfidence > directive.forceConsensusConfidence)
    }

    @Test
    fun exposesConfidenceAndNotes() {
        val directive = parser.parseUserDirective("We need consensus on this change")
        assertTrue(directive.forceConsensus)
        assertTrue(directive.forceConsensusConfidence > 0.6)
        assertTrue(directive.parsingNotes.isNotEmpty())
        assertTrue(directive.parsingNotes.size <= 25)
    }

    @Test
    fun fuzzyAgentMatchingHandlesTypos() {
        val directive = parser.parseUserDirective("Ask codx to review the rollout plan")
        assertEquals(AgentId("codex-cli"), directive.assignToAgent)
        assertTrue(directive.assignmentConfidence > 0.5)
        assertTrue(directive.forceConsensus, "expected force consensus via fuzzy match; notes=${directive.parsingNotes}")
    }

    @Test
    fun emergencyConfidenceReflectsSeverity() {
        val directive = parser.parseUserDirective("Emergency sev0 production down")
        assertTrue(directive.isEmergency)
        assertTrue(directive.emergencyConfidence > 0.7)
    }

    @Test
    fun ambiguousTieDefaultsNeutral() {
        val d = parser.parseUserDirective("We need consensus but let's keep it quick, maybe skip review if trivial")
        // Contains both force and prevent cues without emergency; should resolve to neutral
        assertFalse(d.forceConsensus)
        assertFalse(d.preventConsensus)
        assertNull(d.assignToAgent)
    }

    @Test
    fun assignmentAlongsideConflictsKeepsAssignment() {
        val d = parser.parseUserDirective("Ask Codex, but skip consensus if possible")
        // We have assignment + both directions; without emergency and with tie, neutral flags but assignment preserved
        assertEquals(AgentId("codex-cli"), d.assignToAgent)
        assertFalse(d.forceConsensus && d.preventConsensus)
    }

    @Test
    fun handlesDisplayNameVariations() {
        val d1 = parser.parseUserDirective("Ask Codex CLI to implement")
        assertEquals(AgentId("codex-cli"), d1.assignToAgent)

        val d2 = parser.parseUserDirective("Have Claude Code review")
        assertEquals(AgentId("claude-code"), d2.assignToAgent)
    }

    @Test
    fun handlesIDVariationsWithoutDashes() {
        val d1 = parser.parseUserDirective("Route to @codexcli")
        assertEquals(AgentId("codex-cli"), d1.assignToAgent)
    }
}
