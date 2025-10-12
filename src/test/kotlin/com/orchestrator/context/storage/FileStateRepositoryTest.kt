package com.orchestrator.context.storage

import com.orchestrator.context.config.StorageConfig
import com.orchestrator.context.domain.FileState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileStateRepositoryTest {

    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("file-state-repo-test")
        ContextDatabase.initialize(StorageConfig(dbPath = tempDir.resolve("context.duckdb").toString()))
    }

    @AfterEach
    fun tearDown() {
        ContextDatabase.shutdown()
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun newState(id: Long = 0, path: String = "src/File.kt", indexedAt: Instant = Instant.now()): FileState =
        FileState(
            id = id,
            relativePath = path,
            contentHash = "hash-$path",
            sizeBytes = 100,
            modifiedTimeNs = 1,
            language = "kotlin",
            kind = "source",
            fingerprint = "fp-$path",
            indexedAt = indexedAt,
            isDeleted = false
        )

    @Test
    fun `insert and find`() {
        val inserted = FileStateRepository.insert(newState())
        assertTrue(inserted.id > 0)

        val byId = FileStateRepository.findById(inserted.id)
        assertEquals(inserted, byId)

        val byPath = FileStateRepository.findByPath(inserted.relativePath)
        assertEquals(inserted, byPath)
    }

    @Test
    fun `update modifies existing state`() {
        val inserted = FileStateRepository.insert(newState())
        val updated = inserted.copy(contentHash = "hash2", isDeleted = true)
        FileStateRepository.update(updated)

        val fetched = FileStateRepository.findById(inserted.id)
        assertNotNull(fetched)
        assertEquals("hash2", fetched.contentHash)
        assertTrue(fetched.isDeleted)
    }

    @Test
    fun `find modified since and by language`() {
        val early = FileStateRepository.insert(newState(path = "a.kt", indexedAt = Instant.parse("2024-01-01T00:00:00Z")))
        val late = FileStateRepository.insert(newState(path = "b.kt", indexedAt = Instant.parse("2024-02-01T00:00:00Z")))

        val recent = FileStateRepository.findModifiedSince(Instant.parse("2024-01-15T00:00:00Z"))
        assertEquals(listOf(late), recent)

        val kotlinFiles = FileStateRepository.findByLanguage("kotlin")
        assertEquals(2, kotlinFiles.size)
        assertTrue(kotlinFiles.any { it.id == early.id })
    }

    @Test
    fun `delete operations remove records`() {
        val first = FileStateRepository.insert(newState(path = "del1.kt"))
        val second = FileStateRepository.insert(newState(path = "del2.kt"))
        assertEquals(2, FileStateRepository.count())

        FileStateRepository.delete(first.id)
        assertNull(FileStateRepository.findById(first.id))
        assertEquals(1, FileStateRepository.count())

        FileStateRepository.deleteByPath(second.relativePath)
        assertNull(FileStateRepository.findByPath(second.relativePath))
        assertEquals(0, FileStateRepository.count())
    }

    @Test
    fun `find all with limit`() {
        (0 until 5).forEach { idx ->
            FileStateRepository.insert(newState(path = "f$idx.kt"))
        }

        val limited = FileStateRepository.findAll(limit = 3)
        assertEquals(3, limited.size)

        val all = FileStateRepository.findAll()
        assertEquals(5, all.size)
    }
}
