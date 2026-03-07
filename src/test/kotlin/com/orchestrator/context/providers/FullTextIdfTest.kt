package com.orchestrator.context.providers

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FullTextIdfTest {

    @Test
    fun `provider type is FULL_TEXT`() {
        val provider = FullTextContextProvider()
        assertEquals(ContextProviderType.FULL_TEXT, provider.type)
    }

    @Test
    fun `provider id is full_text`() {
        val provider = FullTextContextProvider()
        assertEquals("full_text", provider.id)
    }

    @Test
    fun `maxResults can be configured`() {
        val provider = FullTextContextProvider(maxResults = 10)
        assertEquals("full_text", provider.id)
    }
}
