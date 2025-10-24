package com.orchestrator.web.dto

import com.orchestrator.modules.context.ContextModule.FileIndexEntry
import com.orchestrator.modules.context.ContextModule.FileIndexStatus
import com.orchestrator.modules.context.ContextModule.IndexHealthStatus
import com.orchestrator.modules.context.ContextModule.IndexStatusSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class IndexStatusDTOTest {

    @Test
    fun `index status snapshot maps to dto`() {
        val snapshot = IndexStatusSnapshot(
            totalFiles = 3,
            indexedFiles = 1,
            pendingFiles = 1,
            failedFiles = 1,
            lastRefresh = Instant.parse("2025-01-02T03:04:05Z"),
            health = IndexHealthStatus.DEGRADED,
            files = listOf(
                FileIndexEntry(
                    path = "src/Main.kt",
                    status = FileIndexStatus.INDEXED,
                    sizeBytes = 1_024,
                    lastModified = Instant.parse("2025-01-01T00:00:00Z"),
                    chunkCount = 6
                ),
                FileIndexEntry(
                    path = "README.md",
                    status = FileIndexStatus.PENDING,
                    sizeBytes = 512,
                    lastModified = null,
                    chunkCount = 0
                ),
                FileIndexEntry(
                    path = "docs/spec.md",
                    status = FileIndexStatus.ERROR,
                    sizeBytes = 2_048,
                    lastModified = Instant.parse("2024-12-31T23:59:59Z"),
                    chunkCount = 2
                )
            )
        )

        val dto = snapshot.toDTO()

        assertEquals(3, dto.totalFiles)
        assertEquals(1, dto.indexedFiles)
        assertEquals(1, dto.pendingFiles)
        assertEquals(1, dto.failedFiles)
        assertEquals("degraded", dto.health)
        assertEquals("2025-01-02T03:04:05Z", dto.lastRefresh)
        assertEquals(3, dto.files.size)

        val indexed = dto.files.first { it.path == "src/Main.kt" }
        assertEquals("indexed", indexed.status)
        assertEquals(1_024, indexed.sizeBytes)
        assertEquals("2025-01-01T00:00:00Z", indexed.lastModified)

        val pending = dto.files.first { it.path == "README.md" }
        assertEquals("pending", pending.status)
        assertEquals(0, pending.chunkCount)
    }

    @Test
    fun `file entry mapping handles null timestamps`() {
        val snapshot = IndexStatusSnapshot(
            totalFiles = 1,
            indexedFiles = 0,
            pendingFiles = 1,
            failedFiles = 0,
            lastRefresh = null,
            health = IndexHealthStatus.HEALTHY,
            files = listOf(
                FileIndexEntry(
                    path = "pending/file.txt",
                    status = FileIndexStatus.PENDING,
                    sizeBytes = 0,
                    lastModified = null,
                    chunkCount = 0
                )
            )
        )

        val dto = snapshot.toDTO()
        val pending = dto.files.single()

        assertEquals("pending/file.txt", pending.path)
        assertEquals("pending", pending.status)
        assertEquals(0L, pending.sizeBytes)
        assertEquals(null, pending.lastModified)
    }
}
