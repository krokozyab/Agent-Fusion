package com.orchestrator.context.indexing

import com.orchestrator.context.config.StorageConfig
import com.orchestrator.context.embedding.Embedder
import com.orchestrator.context.storage.ChunkRepository
import com.orchestrator.context.storage.ContextDatabase
import com.orchestrator.context.storage.EmbeddingRepository
import com.orchestrator.context.storage.FileStateRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileIndexerTest {
    @TempDir
    lateinit var tempDir: Path
    
    private lateinit var projectRoot: Path
    private lateinit var dbPath: Path
    private lateinit var indexer: FileIndexer

    @BeforeEach
    fun setup() {
        projectRoot = tempDir.resolve("project")
        Files.createDirectories(projectRoot)
        
        dbPath = tempDir.resolve("test.db")
        ContextDatabase.initialize(StorageConfig(dbPath = dbPath.toString()))
        
        val embedder = TestEmbedder()
        indexer = FileIndexer(embedder, projectRoot)
    }

    @AfterEach
    fun teardown() {
        ContextDatabase.shutdown()
    }

    @Test
    fun `indexFile successfully indexes a file`() {
        val file = projectRoot.resolve("test.kt")
        Files.writeString(file, "fun main() { println(\"Hello\") }")
        
        val result = indexer.indexFile(file)
        
        assertTrue(result.success)
        assertTrue(result.chunkCount > 0)
        assertEquals(result.chunkCount, result.embeddingCount)
    }

    @Test
    fun `indexFile stores file state`() {
        val file = projectRoot.resolve("test.kt")
        Files.writeString(file, "fun main() {}")
        
        indexer.indexFile(file)
        
        val fileState = FileStateRepository.findByPath("test.kt")
        assertNotNull(fileState)
        assertEquals("test.kt", fileState.relativePath)
        assertEquals("kotlin", fileState.language)
        assertFalse(fileState.isDeleted)
    }

    @Test
    fun `indexFile stores chunks`() {
        val file = projectRoot.resolve("test.kt")
        Files.writeString(file, "fun main() { println(\"test\") }")
        
        indexer.indexFile(file)
        
        val fileState = FileStateRepository.findByPath("test.kt")
        assertNotNull(fileState)
        
        val chunks = ChunkRepository.findByFileId(fileState.id)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.fileId == fileState.id })
        assertTrue(chunks.all { it.content.isNotBlank() })
    }

    @Test
    fun `indexFile stores embeddings`() {
        val file = projectRoot.resolve("test.kt")
        Files.writeString(file, "fun main() {}")
        
        indexer.indexFile(file)
        
        val fileState = FileStateRepository.findByPath("test.kt")
        assertNotNull(fileState)
        
        val chunks = ChunkRepository.findByFileId(fileState.id)
        assertTrue(chunks.isNotEmpty())
        
        val embeddings = EmbeddingRepository.findByChunkIds(
            chunks.map { it.id },
            "test-model"
        )
        assertEquals(chunks.size, embeddings.size)
        assertTrue(embeddings.all { it.dimensions == 384 })
        assertTrue(embeddings.all { it.vector.size == 384 })
    }

    @Test
    fun `indexFile indexes repository pdf with large payload`() {
        val repositoryRoot = Paths.get("").toAbsolutePath().normalize()
        val sourcePdf = repositoryRoot.resolve("abraham_isaac_f_in_action_final_release.pdf")
        if (!Files.exists(sourcePdf)) {
            return
        }

        val targetPdf = projectRoot.resolve(sourcePdf.fileName)
        Files.copy(sourcePdf, targetPdf)

        val largeIndexer = FileIndexer(
            embedder = TestEmbedder(),
            projectRoot = projectRoot,
            maxFileSizeMb = 200,
            warnFileSizeMb = 50
        )

        val result = largeIndexer.indexFile(targetPdf)
        assertTrue(result.success, "Expected PDF indexing to succeed but got ${result.error}")

        val fileState = FileStateRepository.findByPath(sourcePdf.fileName.toString())
        assertNotNull(fileState, "File state missing for ${sourcePdf.fileName}")
        assertEquals(sourcePdf.fileName.toString(), fileState.relativePath)
        assertEquals(Files.size(targetPdf), fileState.sizeBytes)
        assertFalse(fileState.isDeleted)

        ChunkRepository.findByFileId(fileState.id)
    }

    @Test
    fun `indexFile updates existing file`() {
        val file = projectRoot.resolve("test.kt")
        Files.writeString(file, "fun main() {}")
        
        val result1 = indexer.indexFile(file)
        val fileState1 = FileStateRepository.findByPath("test.kt")
        assertNotNull(fileState1)
        val chunks1 = ChunkRepository.findByFileId(fileState1.id)
        
        Files.writeString(file, "fun main() { println(\"updated\") }")
        Thread.sleep(10)
        
        val result2 = indexer.indexFile(file)
        val fileState2 = FileStateRepository.findByPath("test.kt")
        assertNotNull(fileState2)
        
        assertEquals(fileState1.id, fileState2.id)
        assertTrue(fileState2.modifiedTimeNs >= fileState1.modifiedTimeNs)
        
        val chunks2 = ChunkRepository.findByFileId(fileState2.id)
        assertTrue(chunks2.isNotEmpty())
    }

    @Test
    fun `indexFile handles errors gracefully`() {
        val nonExistentFile = projectRoot.resolve("nonexistent.kt")
        
        val result = indexer.indexFile(nonExistentFile)
        
        assertFalse(result.success)
        assertNotNull(result.error)
        assertEquals(0, result.chunkCount)
        assertEquals(0, result.embeddingCount)
    }

    @Test
    fun `indexFile handles different file types`() {
        val files = mapOf(
            "test.py" to "def main(): pass",
            "test.java" to "class Test {}",
            "test.md" to "# Title\nContent"
        )
        
        files.forEach { (name, content) ->
            val file = projectRoot.resolve(name)
            Files.writeString(file, content)
            
            val result = indexer.indexFile(file)
            
            assertTrue(result.success, "Failed to index $name")
            assertTrue(result.chunkCount > 0, "No chunks for $name")
        }
    }

    private class TestEmbedder : Embedder {
        override suspend fun embed(text: String): FloatArray {
            return FloatArray(384) { (it + text.hashCode()) / 1000f }
        }

        override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
            return texts.map { embed(it) }
        }

        override fun getDimension(): Int = 384

        override fun getModel(): String = "test-model"
    }
}
