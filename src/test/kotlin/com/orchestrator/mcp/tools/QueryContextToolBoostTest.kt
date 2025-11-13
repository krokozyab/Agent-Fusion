package com.orchestrator.mcp.tools

import com.orchestrator.context.config.BoostConfig
import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.config.QueryConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryContextToolBoostTest {

    @Test
    fun `path boosts configured by default`() {
        val config = ContextConfig()
        assertTrue(config.query.boosts.pathPrefixes.isNotEmpty())
    }

    @Test
    fun `language boosts configured by default`() {
        val config = ContextConfig()
        assertTrue(config.query.boosts.languages.isNotEmpty())
    }

    @Test
    fun `boosts can be disabled`() {
        val config = ContextConfig(
            query = QueryConfig(
                boosts = BoostConfig(
                    pathPrefixes = emptyMap(),
                    languages = emptyMap()
                )
            )
        )
        assertTrue(config.query.boosts.pathPrefixes.isEmpty())
        assertTrue(config.query.boosts.languages.isEmpty())
    }

    @Test
    fun `custom path boosts can be configured`() {
        val config = ContextConfig(
            query = QueryConfig(
                boosts = BoostConfig(
                    pathPrefixes = mapOf("custom/path" to 2.0)
                )
            )
        )
        assertEquals(2.0, config.query.boosts.pathPrefixes["custom/path"])
    }

    @Test
    fun `custom language boosts can be configured`() {
        val config = ContextConfig(
            query = QueryConfig(
                boosts = BoostConfig(
                    languages = mapOf("rust" to 1.5)
                )
            )
        )
        assertEquals(1.5, config.query.boosts.languages["rust"])
    }
}
