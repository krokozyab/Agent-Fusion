package com.orchestrator.context.providers

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.domain.ContextScope
import com.orchestrator.context.domain.ContextSnippet
import com.orchestrator.context.domain.TokenBudget

class UnifiedContextProvider(
    private val hybridProvider: HybridContextProvider,
    private val config: ContextConfig
) : ContextProvider {

    override val id: String = "unified"
    override val type: ContextProviderType = ContextProviderType.HYBRID

    override suspend fun getContext(
        query: String,
        scope: ContextScope,
        budget: TokenBudget
    ): List<ContextSnippet> {
        return hybridProvider.getContext(query, scope, budget)
    }
}
