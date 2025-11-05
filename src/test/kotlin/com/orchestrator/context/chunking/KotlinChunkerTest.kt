package com.orchestrator.context.chunking

import com.orchestrator.context.domain.ChunkKind
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class KotlinChunkerTest {
    
    private val chunker = KotlinChunker()
    
    @Test
    fun `chunks simple class`() {
        val code = """
            package com.example
            
            import kotlin.test.*
            
            /**
             * A simple user class
             */
            class User(val name: String, val age: Int) {
                fun greet() = "Hello, ${'$'}name"
            }
        """.trimIndent()
        
        val chunks = chunker.chunk(code, "User.kt")
        
        assertTrue(chunks.size >= 2)
        assertEquals(ChunkKind.CODE_HEADER, chunks[0].kind)
        assertTrue(chunks[0].content.contains("package com.example"))
        
        val classChunk = chunks.find { it.kind == ChunkKind.CODE_CLASS }
        assertNotNull(classChunk)
        assertTrue(classChunk!!.content.contains("class User"))
        assertTrue(classChunk.content.contains("A simple user class"))
        assertEquals("User", classChunk.summary)
    }
    
    @Test
    fun `chunks interface`() {
        val code = """
            interface Repository {
                fun save(item: Any)
                fun findById(id: Long): Any?
            }
        """.trimIndent()
        
        val chunks = chunker.chunk(code, "Repository.kt")
        
        val interfaceChunk = chunks.find { it.kind == ChunkKind.CODE_INTERFACE }
        assertNotNull(interfaceChunk)
        assertTrue(interfaceChunk!!.content.contains("interface Repository"))
        assertEquals("Repository", interfaceChunk.summary)
    }
    
    @Test
    fun `chunks data class`() {
        val code = """
            /**
             * User data class
             */
            data class User(
                val id: Long,
                val name: String,
                val email: String
            )
        """.trimIndent()
        
        val chunks = chunker.chunk(code, "User.kt")
        
        val dataClassChunk = chunks.find { it.kind == ChunkKind.CODE_CLASS }
        assertNotNull(dataClassChunk)
        assertTrue(dataClassChunk!!.content.contains("data class User"))
        assertTrue(dataClassChunk.content.contains("User data class"))
        assertEquals("User", dataClassChunk.summary)
    }
    
    @Test
    fun `chunks sealed class`() {
        val code = """
            sealed class Result {
                data class Success(val data: String) : Result()
                data class Error(val message: String) : Result()
            }
        """.trimIndent()
        
        val chunks = chunker.chunk(code, "Result.kt")
        
        val sealedChunk = chunks.find { it.kind == ChunkKind.CODE_CLASS && it.summary == "Result" }
        assertNotNull(sealedChunk)
        assertTrue(sealedChunk!!.content.contains("sealed class Result"))
    }
    
    @Test
    fun `chunks object declaration`() {
        val code = """
            object Singleton {
                const val VERSION = "1.0"
                fun getInstance() = this
            }
        """.trimIndent()
        
        val chunks = chunker.chunk(code, "Singleton.kt")
        
        val objectChunk = chunks.find { it.kind == ChunkKind.CODE_CLASS }
        assertNotNull(objectChunk)
        assertTrue(objectChunk!!.content.contains("object Singleton"))
        assertEquals("Singleton", objectChunk.summary)
    }
    
    @Test
    fun `chunks enum class`() {
        val code = """
            /**
             * Status enum
             */
            enum class Status {
                ACTIVE,
                INACTIVE,
                PENDING
            }
        """.trimIndent()
        
        val chunks = chunker.chunk(code, "Status.kt")
        
        val enumChunk = chunks.find { it.kind == ChunkKind.CODE_ENUM }
        assertNotNull(enumChunk)
        assertTrue(enumChunk!!.content.contains("enum class Status"))
        assertTrue(enumChunk.content.contains("Status enum"))
        assertEquals("Status", enumChunk.summary)
    }
    
    @Test
    fun `chunks top-level function`() {
        val code = """
            /**
             * Calculates sum
             */
            fun sum(a: Int, b: Int): Int {
                return a + b
            }
        """.trimIndent()
        
        val chunks = chunker.chunk(code, "Utils.kt")
        
        val funChunk = chunks.find { it.kind == ChunkKind.CODE_FUNCTION }
        assertNotNull(funChunk)
        assertTrue(funChunk!!.content.contains("fun sum"))
        assertTrue(funChunk.content.contains("Calculates sum"))
        assertEquals("sum", funChunk.summary)
    }
    
    @Test
    fun `chunks suspend function`() {
        val code = """
            suspend fun fetchData(url: String): String {
                return "data"
            }
        """.trimIndent()
        
        val chunks = chunker.chunk(code, "Api.kt")
        
        val funChunk = chunks.find { it.kind == ChunkKind.CODE_FUNCTION }
        assertNotNull(funChunk)
        assertTrue(funChunk!!.content.contains("suspend fun fetchData"))
        assertEquals("fetchData", funChunk.summary)
    }
    
    @Test
    fun `preserves KDoc comments`() {
        val code = """
            /**
             * This is a KDoc comment
             * with multiple lines
             * @param name the user name
             */
            class User(val name: String)
        """.trimIndent()
        
        val chunks = chunker.chunk(code, "User.kt")
        
        val classChunk = chunks.find { it.kind == ChunkKind.CODE_CLASS }
        assertNotNull(classChunk)
        assertTrue(classChunk!!.content.contains("This is a KDoc comment"))
        assertTrue(classChunk.content.contains("@param name"))
    }
    
    @Test
    fun `respects token limit`() {
        val largeClass = buildString {
            appendLine("class LargeClass {")
            repeat(200) { i ->
                appendLine("    fun method$i() { println(\"Method $i\") }")
            }
            appendLine("}")
        }
        
        val chunks = chunker.chunk(largeClass, "LargeClass.kt")
        
        val classChunks = chunks.filter { it.kind == ChunkKind.CODE_CLASS }
        assertTrue(classChunks.isNotEmpty())
        
        // Allow small margin for edge cases (class declaration line)
        classChunks.forEach { chunk ->
            assertTrue(chunk.tokenEstimate!! <= 610, "Chunk has ${chunk.tokenEstimate} tokens, expected <= 610")
        }
    }
}
