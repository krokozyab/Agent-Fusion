package com.orchestrator.domain

import com.orchestrator.modules.routing.TaskClassifier
import java.time.Instant

/**
 * Result of routing a Task. Captures the chosen strategy, selected agents,
 * classifier snapshot, directive applied, and audit metadata for traceability.
 */
 data class RoutingDecision(
     val taskId: TaskId,
     val strategy: RoutingStrategy,

     /** The primary agent chosen to execute (for SOLO or lead in other modes). */
     val primaryAgentId: AgentId? = null,

     /** All participating agents in the routing (includes primary when present). */
     val participantAgentIds: List<AgentId> = emptyList(),

     /** The directive that influenced routing (may be neutral/empty if none provided). */
     val directive: com.orchestrator.domain.UserDirective,

     /** Snapshot of classifier output used during routing. */
     val classification: TaskClassifier.Result,

     /** Timestamp when routing decision was produced. */
     val decidedAt: Instant = Instant.now(),

     /** Optional free-form notes or reasons (best-effort; also logged elsewhere). */
     val notes: String? = null,

     /** Extensible metadata */
     val metadata: Map<String, String> = emptyMap()
 ) {
     init {
         // participant list should include primary if primary is not null
         if (primaryAgentId != null) {
             require(participantAgentIds.any { it == primaryAgentId }) {
                 "participantAgentIds must include primaryAgentId when primaryAgentId is set"
             }
         }
     }
 }
