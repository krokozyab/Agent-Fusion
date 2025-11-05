package com.orchestrator.context.discovery

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ExtensionFilterTest {

    @Test
    fun `allowlist permits configured extensions`() {
        val filter = ExtensionFilter.fromConfig(listOf(".kt", "MD"), emptyList())

        assertTrue(filter.shouldInclude(Path.of("Main.kt")))
        assertTrue(filter.shouldInclude(Path.of("README.MD")))
        assertFalse(filter.shouldInclude(Path.of("index.ts")))
        assertFalse(filter.shouldInclude(Path.of("Dockerfile")))
    }

    @Test
    fun `blocklist excludes configured extensions`() {
        val filter = ExtensionFilter.fromConfig(emptyList(), listOf("log", ".tmp"))

        assertFalse(filter.shouldInclude(Path.of("application.log")))
        assertFalse(filter.shouldInclude(Path.of("artifact.TMP")))
        assertTrue(filter.shouldInclude(Path.of("Main.kt")))
        assertTrue(filter.shouldInclude(Path.of("README")))
    }

    @Test
    fun `mutually exclusive configuration enforced`() {
        assertThrows(IllegalArgumentException::class.java) {
            ExtensionFilter.fromConfig(listOf(".kt"), listOf(".log"))
        }
    }

    @Test
    fun `disabled filter includes everything`() {
        val filter = ExtensionFilter.fromConfig(emptyList(), emptyList())

        assertTrue(filter.shouldInclude(Path.of("notes.random")))
        assertTrue(filter.shouldInclude(Path.of("README")))
    }
}
