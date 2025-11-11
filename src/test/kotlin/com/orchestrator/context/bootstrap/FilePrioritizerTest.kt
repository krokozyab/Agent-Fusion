package com.orchestrator.context.bootstrap

import com.orchestrator.context.config.BootstrapConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FilePrioritizerTest {

    @Test
    fun `prioritizes small files first`(@TempDir tempDir: Path) {
        val bigFile = tempDir.resolve("big.kt").also { Files.write(it, ByteArray(50_000) { 'a'.code.toByte() }) }
        val smallFile = tempDir.resolve("small.kt").also { Files.write(it, ByteArray(1_000) { 'a'.code.toByte() }) }
        val config = BootstrapConfig()

        val ordered = FilePrioritizer.prioritize(listOf(bigFile, smallFile), config)

        assertEquals(listOf(smallFile, bigFile), ordered)
    }

    @Test
    fun `respects extension priorities`(@TempDir tempDir: Path) {
        val sourceFile = tempDir.resolve("module.ts").also { Files.writeString(it, "export const x = 1") }
        val docFile = tempDir.resolve("README.md").also { Files.writeString(it, "# Title") }
        val configFile = tempDir.resolve("app.yaml").also { Files.writeString(it, "key: value") }
        val otherFile = tempDir.resolve("notes.bin").also { Files.write(it, ByteArray(1024) { 1 }) }

        val ordered = FilePrioritizer.prioritize(listOf(otherFile, configFile, docFile, sourceFile), BootstrapConfig())

        assertEquals(setOf(sourceFile, docFile, configFile), ordered.subList(0, 3).toSet())
        assertEquals(otherFile, ordered.last())
    }

    @Test
    fun `detects special script names`(@TempDir tempDir: Path) {
        val dockerfile = tempDir.resolve("Dockerfile").also { Files.writeString(it, "FROM alpine") }
        val fallback = tempDir.resolve("artifact.bin").also { Files.write(it, ByteArray(5_000) { 0 }) }

        val ordered = FilePrioritizer.prioritize(listOf(fallback, dockerfile), BootstrapConfig())

        assertEquals(listOf(dockerfile, fallback), ordered)
    }

    @Test
    fun `uses bootstrap priority extensions`(@TempDir tempDir: Path) {
        val markedExt = tempDir.resolve("special.foo").also { Files.writeString(it, "data") }
        val regular = tempDir.resolve("regular.ts").also { Files.writeString(it, "export const r = 1") }
        val config = BootstrapConfig(priorityExtensions = listOf(".foo"))

        val ordered = FilePrioritizer.prioritize(listOf(regular, markedExt), config)

        assertEquals(listOf(markedExt, regular), ordered)
    }

    @Test
    fun `stable within same priority`(@TempDir tempDir: Path) {
        val doc1 = tempDir.resolve("a.md").also { Files.writeString(it, "A") }
        val doc2 = tempDir.resolve("b.md").also { Files.writeString(it, "B") }
        val config = BootstrapConfig()

        val ordered = FilePrioritizer.prioritize(listOf(doc1, doc2), config)

        assertEquals(listOf(doc1, doc2), ordered)
    }

    @Test
    fun `downgrades oversized files`(@TempDir tempDir: Path) {
        val large = tempDir.resolve("Large.kt").also { Files.write(it, ByteArray(3 * 1024 * 1024) { 1 }) }
        val medium = tempDir.resolve("Medium.log").also { Files.write(it, ByteArray(200 * 1024) { 1 }) }
        val config = BootstrapConfig()

        val ordered = FilePrioritizer.prioritize(listOf(large, medium), config)

        assertEquals(listOf(medium, large), ordered)
    }
}
