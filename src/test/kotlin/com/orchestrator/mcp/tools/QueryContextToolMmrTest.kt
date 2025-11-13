package com.orchestrator.mcp.tools

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.config.QueryConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class QueryContextToolMmrTest {

    @Test
    fun `MMR integration is enabled by default`() {
        val config = ContextConfig()
        val tool = QueryContextTool(config)
        
        // Verify config has MMR enabled
        assertTrue(config.query.useOptimizerInTool, "MMR should be enabled by default")
    }

    @Test
    fun `MMR can be disabled via config`() {
        val config = ContextConfig(
            query = QueryConfig(useOptimizerInTool = false)
        )
        val tool = QueryContextTool(config)
        
        // Verify config has MMR disabled
        assertTrue(!config.query.useOptimizerInTool, "MMR should be disabled when configured")
    }

    @Test
    fun `embedding cache size is configurable`() {
        val config = ContextConfig(
            query = QueryConfig(embeddingCacheSize = 500)
        )
        val tool = QueryContextTool(config)
        
        // Verify cache size is set
        assertTrue(config.query.embeddingCacheSize == 500, "Cache size should be 500")
    }
}
