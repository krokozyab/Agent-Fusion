package com.orchestrator.context.providers

import com.orchestrator.context.domain.ContextScope
import com.orchestrator.context.domain.ContextSnippet
import com.orchestrator.context.domain.TokenBudget

/**
 * Contract implemented by all context retrieval providers.
 */
interface ContextProvider {
    val id: String
    val type: ContextProviderType

    suspend fun getContext(
        query: String,
        scope: ContextScope,
        budget: TokenBudget
    ): List<ContextSnippet>
}
