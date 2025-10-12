package com.orchestrator.context.chunking

import com.orchestrator.context.domain.ChunkKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CSharpChunkerTest {
    
    private val chunker = CSharpChunker(maxTokens = 600, overlapPercent = 15)
    
    @Test
    fun `chunks simple class with methods`() {
        val code = """
            using System;
            
            namespace MyApp
            {
                /// <summary>
                /// User service class
                /// </summary>
                public class UserService
                {
                    /// <summary>
                    /// Find user by ID
                    /// </summary>
                    public User FindById(int id)
                    {
                        return repository.FindById(id);
                    }
                    
                    public void Save(User user)
                    {
                        repository.Save(user);
                    }
                }
            }
        """.trimIndent()
        
        val chunks = chunker.chunk(code, "UserService.cs")
        
        assertTrue(chunks.size >= 3, "Should have header, class, and methods")
        assertEquals(ChunkKind.CODE_HEADER, chunks[0].kind)
        assertEquals(ChunkKind.CODE_CLASS, chunks[1].kind)
        assertTrue(chunks.any { it.kind == ChunkKind.CODE_METHOD && it.summary?.contains("FindById") == true })
    }
    
    @Test
    fun `chunks interface`() {
        val code = """
            public interface IRepository
            {
                User FindById(int id);
                void Save(User user);
            }
        """.trimIndent()
        
        val chunks = chunker.chunk(code, "IRepository.cs")
        
        assertTrue(chunks.isNotEmpty())
        assertEquals(ChunkKind.CODE_INTERFACE, chunks[0].kind)
    }
    
    @Test
    fun `chunks enum`() {
        val code = """
            public enum Status
            {
                Active,
                Inactive,
                Pending
            }
        """.trimIndent()
        
        val chunks = chunker.chunk(code, "Status.cs")
        
        assertTrue(chunks.isNotEmpty())
        assertEquals(ChunkKind.CODE_ENUM, chunks[0].kind)
    }
    
    @Test
    fun `chunks record`() {
        val code = """
            public record User(int Id, string Name);
        """.trimIndent()
        
        val chunks = chunker.chunk(code, "User.cs")
        
        assertTrue(chunks.isNotEmpty())
        assertEquals(ChunkKind.CODE_CLASS, chunks[0].kind)
    }
    
    @Test
    fun `chunks constructor`() {
        val code = """
            public class User
            {
                private string name;
                
                /// <summary>
                /// Constructor
                /// </summary>
                public User(string name)
                {
                    this.name = name;
                }
            }
        """.trimIndent()
        
        val chunks = chunker.chunk(code, "User.cs")
        
        val ctorChunk = chunks.find { it.kind == ChunkKind.CODE_CONSTRUCTOR }
        assertTrue(ctorChunk != null)
        assertTrue(ctorChunk.summary?.contains("<init>") == true)
    }
    
    @Test
    fun `chunks property`() {
        val code = """
            public class User
            {
                public string Name { get; set; }
                
                public int Age
                {
                    get { return age; }
                    set { age = value; }
                }
            }
        """.trimIndent()
        
        val chunks = chunker.chunk(code, "User.cs")
        
        assertTrue(chunks.any { it.summary?.contains("Age") == true })
    }
    
    @Test
    fun `preserves XML doc comments`() {
        val code = """
            /// <summary>
            /// Main class
            /// </summary>
            public class Main
            {
                /// <summary>
                /// Main method
                /// </summary>
                /// <param name="args">Arguments</param>
                public static void Main(string[] args)
                {
                }
            }
        """.trimIndent()
        
        val chunks = chunker.chunk(code, "Main.cs")
        
        val classChunk = chunks.find { it.kind == ChunkKind.CODE_CLASS }
        assertTrue(classChunk?.content?.contains("Main class") == true)
        
        val methodChunk = chunks.find { it.kind == ChunkKind.CODE_METHOD }
        assertTrue(methodChunk?.content?.contains("Main method") == true)
    }
    
    @Test
    fun `respects token limit`() {
        val chunks = chunker.chunk(generateLargeClass(), "Large.cs")
        
        chunks.forEach { chunk ->
            assertTrue(chunk.tokenEstimate ?: 0 <= 600, "Chunk ${chunk.summary} exceeds token limit: ${chunk.tokenEstimate}")
        }
    }
    
    private fun generateLargeClass(): String {
        val lines = (1..100).map { "        Console.WriteLine(\"Line $it\");" }
        return """
            public class Large
            {
                public void LargeMethod()
                {
                    ${lines.joinToString("\n")}
                }
            }
        """.trimIndent()
    }
}
