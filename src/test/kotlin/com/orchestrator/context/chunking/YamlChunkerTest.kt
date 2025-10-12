package com.orchestrator.context.chunking

import com.orchestrator.context.domain.ChunkKind
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class YamlChunkerTest {
    
    private val chunker = YamlChunker()
    
    @Test
    fun `chunks simple YAML with top-level keys`() {
        val yaml = """
            name: MyApp
            version: 1.0.0
            description: A sample application
        """.trimIndent()
        
        val chunks = chunker.chunk(yaml, "config.yaml")
        
        assertEquals(3, chunks.size)
        chunks.forEach { chunk ->
            assertEquals(ChunkKind.YAML_BLOCK, chunk.kind)
        }
        
        assertTrue(chunks.any { it.summary == "name" && it.content.contains("MyApp") })
        assertTrue(chunks.any { it.summary == "version" && it.content.contains("1.0.0") })
        assertTrue(chunks.any { it.summary == "description" })
    }
    
    @Test
    fun `chunks nested YAML structure`() {
        val yaml = """
            database:
              host: localhost
              port: 5432
              credentials:
                username: admin
                password: secret
        """.trimIndent()
        
        val chunks = chunker.chunk(yaml, "config.yaml")
        
        assertTrue(chunks.isNotEmpty())
        assertEquals(ChunkKind.YAML_BLOCK, chunks[0].kind)
        
        val dbChunk = chunks.find { it.summary == "database" }
        assertNotNull(dbChunk)
        assertTrue(dbChunk!!.content.contains("host"))
        assertTrue(dbChunk.content.contains("localhost"))
    }
    
    @Test
    fun `chunks YAML with lists`() {
        val yaml = """
            services:
              - name: web
                port: 8080
              - name: api
                port: 3000
        """.trimIndent()
        
        val chunks = chunker.chunk(yaml, "services.yaml")
        
        assertTrue(chunks.isNotEmpty())
        val servicesChunk = chunks.find { it.summary == "services" }
        assertNotNull(servicesChunk)
        assertTrue(servicesChunk!!.content.contains("web"))
        assertTrue(servicesChunk.content.contains("api"))
    }
    
    @Test
    fun `preserves key paths for nested structures`() {
        val yaml = """
            app:
              server:
                host: localhost
                port: 8080
        """.trimIndent()
        
        val chunks = chunker.chunk(yaml, "config.yaml")
        
        assertTrue(chunks.isNotEmpty())
        val appChunk = chunks.find { it.summary == "app" }
        assertNotNull(appChunk)
    }
    
    @Test
    fun `handles multiline strings`() {
        val yaml = """
            description: |
              This is a long
              multiline description
              that spans several lines
        """.trimIndent()
        
        val chunks = chunker.chunk(yaml, "config.yaml")
        
        assertTrue(chunks.isNotEmpty())
        val descChunk = chunks.find { it.summary == "description" }
        assertNotNull(descChunk)
        assertTrue(descChunk!!.content.contains("multiline"))
    }
    
    @Test
    fun `handles empty YAML`() {
        val yaml = ""
        
        val chunks = chunker.chunk(yaml, "empty.yaml")
        
        assertEquals(0, chunks.size)
    }
    
    @Test
    fun `handles invalid YAML gracefully`() {
        val yaml = """
            invalid: [unclosed
            bracket: missing
        """.trimIndent()
        
        val chunks = chunker.chunk(yaml, "invalid.yaml")
        
        assertEquals(1, chunks.size)
        assertEquals(ChunkKind.YAML_BLOCK, chunks[0].kind)
        assertEquals("root", chunks[0].summary)
    }
    
    @Test
    fun `splits large nested structures`() {
        val yaml = buildString {
            appendLine("config:")
            repeat(50) { i ->
                appendLine("  key$i: value$i")
            }
        }
        
        val chunks = chunker.chunk(yaml, "large.yaml")
        
        assertTrue(chunks.isNotEmpty())
        chunks.forEach { chunk ->
            assertEquals(ChunkKind.YAML_BLOCK, chunk.kind)
            assertTrue(chunk.tokenEstimate!! <= 600, "Chunk has ${chunk.tokenEstimate} tokens")
        }
    }
    
    @Test
    fun `handles Docker Compose style YAML`() {
        val yaml = """
            version: '3.8'
            services:
              web:
                image: nginx:latest
                ports:
                  - "80:80"
              db:
                image: postgres:13
                environment:
                  POSTGRES_PASSWORD: secret
        """.trimIndent()
        
        val chunks = chunker.chunk(yaml, "docker-compose.yaml")
        
        assertTrue(chunks.size >= 2)
        assertTrue(chunks.any { it.summary == "version" })
        assertTrue(chunks.any { it.summary == "services" })
    }
    
    @Test
    fun `handles Kubernetes style YAML`() {
        val yaml = """
            apiVersion: v1
            kind: Service
            metadata:
              name: my-service
            spec:
              selector:
                app: MyApp
              ports:
                - protocol: TCP
                  port: 80
                  targetPort: 9376
        """.trimIndent()
        
        val chunks = chunker.chunk(yaml, "service.yaml")
        
        assertTrue(chunks.size >= 3)
        assertTrue(chunks.any { it.summary == "apiVersion" })
        assertTrue(chunks.any { it.summary == "kind" })
        assertTrue(chunks.any { it.summary == "metadata" })
    }
}
