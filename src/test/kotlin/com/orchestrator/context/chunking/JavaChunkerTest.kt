package com.orchestrator.context.chunking

import com.orchestrator.context.domain.ChunkKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavaChunkerTest {
    
    private val chunker = JavaChunker(maxTokens = 600, overlapPercent = 15)

    @Test
    fun `unparseable java falls back to whole-file chunk instead of dropping the file`() {
        // Not valid Java — the parser will reject it. Previously this returned emptyList(),
        // which made the file persist with zero chunks and vanish from search.
        val broken = "this is not valid java @@@ ### <<< >>> public class {{{ "

        val chunks = chunker.chunk(broken, "Broken.java")

        assertTrue(chunks.isNotEmpty(), "Unparseable file must still yield at least one chunk")
        assertTrue(
            chunks.any { it.kind == ChunkKind.CODE_BLOCK },
            "Fallback chunk should be a CODE_BLOCK"
        )
        assertTrue(
            chunks.any { it.content.contains("not valid java") },
            "Fallback chunk must preserve the original file content"
        )
    }

    @Test
    fun `blank content yields no chunks`() {
        assertTrue(chunker.chunk("   \n\t", "Empty.java").isEmpty())
    }

    @Test
    fun `chunks simple class with methods`() {
        val code = """
            package com.example;
            
            import java.util.List;
            
            /**
             * User service class
             */
            public class UserService {
                
                /**
                 * Find user by ID
                 */
                public User findById(Long id) {
                    return repository.findById(id);
                }
                
                public void save(User user) {
                    repository.save(user);
                }
            }
        """.trimIndent()
        
        val chunks = chunker.chunk(code, "UserService.java")
        
        assertTrue(chunks.size >= 3, "Should have header, class, and methods")
        assertEquals(ChunkKind.CODE_HEADER, chunks[0].kind)
        assertEquals(ChunkKind.CODE_CLASS, chunks[1].kind)
        assertEquals(ChunkKind.CODE_METHOD, chunks[2].kind)
        assertTrue(chunks[2].summary?.contains("findById") == true)
    }
    
    @Test
    fun `chunks interface`() {
        val code = """
            public interface Repository {
                User findById(Long id);
                void save(User user);
            }
        """.trimIndent()
        
        val chunks = chunker.chunk(code, "Repository.java")
        
        assertTrue(chunks.isNotEmpty())
        assertEquals(ChunkKind.CODE_INTERFACE, chunks[0].kind)
    }
    
    @Test
    fun `chunks enum`() {
        val code = """
            public enum Status {
                ACTIVE, INACTIVE, PENDING
            }
        """.trimIndent()
        
        val chunks = chunker.chunk(code, "Status.java")
        
        assertTrue(chunks.isNotEmpty())
        assertEquals(ChunkKind.CODE_ENUM, chunks[0].kind)
    }
    
    @Test
    fun `chunks constructor`() {
        val code = """
            public class User {
                private String name;
                
                /**
                 * Constructor
                 */
                public User(String name) {
                    this.name = name;
                }
            }
        """.trimIndent()
        
        val chunks = chunker.chunk(code, "User.java")
        
        val ctorChunk = chunks.find { it.kind == ChunkKind.CODE_CONSTRUCTOR }
        assertTrue(ctorChunk != null)
        assertTrue(ctorChunk.summary?.contains("<init>") == true)
    }
    
    @Test
    fun `chunks nested class`() {
        val code = """
            public class Outer {
                public static class Inner {
                    public void method() {}
                }
            }
        """.trimIndent()
        
        val chunks = chunker.chunk(code, "Outer.java")
        
        val innerClass = chunks.find { it.summary?.contains("Outer.Inner") == true }
        assertTrue(innerClass != null)
    }
    
    @Test
    fun `chunks static initializer`() {
        val code = """
            public class Config {
                static {
                    System.loadLibrary("native");
                }
            }
        """.trimIndent()
        
        val chunks = chunker.chunk(code, "Config.java")
        
        val initChunk = chunks.find { it.kind == ChunkKind.CODE_BLOCK && it.summary?.contains("<clinit>") == true }
        assertTrue(initChunk != null)
    }
    
    @Test
    fun `preserves javadoc`() {
        val code = """
            /**
             * Main class
             */
            public class Main {
                /**
                 * Main method
                 * @param args arguments
                 */
                public static void main(String[] args) {}
            }
        """.trimIndent()
        
        val chunks = chunker.chunk(code, "Main.java")
        
        val classChunk = chunks.find { it.kind == ChunkKind.CODE_CLASS }
        assertTrue(classChunk?.content?.contains("Main class") == true)
        
        val methodChunk = chunks.find { it.kind == ChunkKind.CODE_METHOD }
        assertTrue(methodChunk?.content?.contains("Main method") == true)
    }
    
    @Test
    fun `respects token limit`() {
        val chunks = chunker.chunk(generateLargeClass(), "Large.java")
        
        chunks.forEach { chunk ->
            val estimate = chunk.tokenEstimate ?: 0
            assertTrue(estimate <= 600, "Chunk ${chunk.summary} exceeds token limit: $estimate")
        }
    }
    
    private fun generateLargeClass(): String {
        val lines = (1..100).map { "    System.out.println(\"Line $it\");" }
        return """
            public class Large {
                public void largeMethod() {
                    ${lines.joinToString("\n")}
                }
            }
        """.trimIndent()
    }
}
