package com.orchestrator.web.dto

data class TaskDetailDTO(
    val task: TaskDTO,
    val proposals: List<ProposalDTO>,
    val decision: DecisionDTO? = null,
    val mermaid: String? = null
) {
    data class ProposalDTO(
        val id: String,
        val agentId: String,
        val inputType: String,
        val confidence: Double,
        val tokenUsage: TokenUsageDTO,
        val createdAt: String,
        val content: Any?,
        val metadata: Map<String, String>
    )

    data class TokenUsageDTO(
        val inputTokens: Int,
        val outputTokens: Int,
        val totalTokens: Int
    )

    data class DecisionDTO(
        val id: String,
        val decidedAt: String,
        val agreementRate: Double?,
        val rationale: String?,
        val selected: Set<String>,
        val winnerProposalId: String?,
        val considered: List<ConsideredProposalDTO>,
        val tokenSavingsAbsolute: Int,
        val tokenSavingsPercent: Double
    ) {
        data class ConsideredProposalDTO(
            val id: String,
            val agentId: String,
            val inputType: String,
            val confidence: Double,
            val tokenUsage: TokenUsageDTO
        )
    }
}
