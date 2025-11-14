package com.orchestrator.mcp.tools

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.config.QueryConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueryContextToolNeighborTest {

    @Test
    fun `neighbor expansion enabled by default`() {
        val config = ContextConfig()
        assertEquals(1, config.query.neighborWindow)
    }

    @Test
    fun `neighbor expansion can be disabled`() {
        val config = ContextConfig(
            query = QueryConfig(neighborWindow = 0)
        )
        assertEquals(0, config.query.neighborWindow)
    }

    @Test
    fun `neighbor expansion window is configurable`() {
        val config = ContextConfig(
            query = QueryConfig(neighborWindow = 2)
        )
        assertEquals(2, config.query.neighborWindow)
    }

    @Test
    fun `neighbor expansion respects feature flag`() {
        val configDisabled = ContextConfig(
            query = QueryConfig(neighborWindow = 0)
        )
        val configEnabled = ContextConfig(
            query = QueryConfig(neighborWindow = 1)
        )

        assertFalse(configDisabled.query.neighborWindow > 0)
        assertTrue(configEnabled.query.neighborWindow > 0)
    }
}
