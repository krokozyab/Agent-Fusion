package com.orchestrator.context.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryConfigTest {
    
    @Test
    fun `loads default values correctly`() {
        val config = QueryConfig()
        assertEquals(true, config.useOptimizerInTool)
        assertEquals(1, config.neighborWindow)
        assertEquals(0.5, config.mmrLambda)
        assertEquals(1000, config.embeddingCacheSize)
        assertEquals(true, config.idfEnabled)
    }

    @Test
    fun `loads path boosts from config`() {
        val config = QueryConfig(
            boosts = BoostConfig(
                pathPrefixes = mapOf("src/main" to 1.05)
            )
        )
        assertEquals(1.05, config.boosts.pathPrefixes["src/main"])
    }

    @Test
    fun `default path boosts are configured`() {
        val config = QueryConfig()
        assertEquals(1.05, config.boosts.pathPrefixes["src/main"])
        assertEquals(0.95, config.boosts.pathPrefixes["src/test"])
        assertEquals(0.90, config.boosts.pathPrefixes["vendor"])
    }

    @Test
    fun `default language boosts are configured`() {
        val config = QueryConfig()
        assertEquals(1.02, config.boosts.languages["kotlin"])
        assertEquals(1.00, config.boosts.languages["markdown"])
    }

    @Test
    fun `can disable optimizer in tool`() {
        val config = QueryConfig(useOptimizerInTool = false)
        assertEquals(false, config.useOptimizerInTool)
    }

    @Test
    fun `can disable IDF scoring`() {
        val config = QueryConfig(idfEnabled = false)
        assertEquals(false, config.idfEnabled)
    }

    @Test
    fun `can configure neighbor window`() {
        val config = QueryConfig(neighborWindow = 2)
        assertEquals(2, config.neighborWindow)
    }

    @Test
    fun `can configure embedding cache size`() {
        val config = QueryConfig(embeddingCacheSize = 500)
        assertEquals(500, config.embeddingCacheSize)
    }

    @Test
    fun `custom boosts override defaults`() {
        val customBoosts = BoostConfig(
            pathPrefixes = mapOf("custom/path" to 1.10),
            languages = mapOf("java" to 1.05)
        )
        val config = QueryConfig(boosts = customBoosts)
        
        assertEquals(1.10, config.boosts.pathPrefixes["custom/path"])
        assertEquals(1.05, config.boosts.languages["java"])
        assertTrue(config.boosts.pathPrefixes["src/main"] == null)
    }
}
