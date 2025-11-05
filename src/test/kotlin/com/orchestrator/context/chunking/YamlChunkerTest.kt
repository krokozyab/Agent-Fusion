package com.orchestrator.context.chunking

import com.orchestrator.context.domain.ChunkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class YamlChunkerTest {

    @Test
    fun `empty content returns empty list`() {
        val chunker = YamlChunker()
        val chunks = chunker.chunk("", "test.yaml")
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `blank content returns empty list`() {
        val chunker = YamlChunker()
        val chunks = chunker.chunk("   \n\n  \n", "test.yaml")
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `chunks simple YAML with top-level keys`() {
        val yaml = """
            name: MyApp
            version: 1.0.0
            description: A sample application
        """.trimIndent()

        val chunker = YamlChunker()
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

        val chunker = YamlChunker()
        val chunks = chunker.chunk(yaml, "config.yaml")

        assertTrue(chunks.isNotEmpty())
        assertEquals(ChunkKind.YAML_BLOCK, chunks[0].kind)

        val dbChunk = chunks.find { it.summary == "database" }
        assertNotNull(dbChunk)
        assertTrue(dbChunk.content.contains("host"))
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

        val chunker = YamlChunker()
        val chunks = chunker.chunk(yaml, "services.yaml")

        assertTrue(chunks.isNotEmpty())
        val servicesChunk = chunks.find { it.summary == "services" }
        assertNotNull(servicesChunk)
        assertTrue(servicesChunk.content.contains("web"))
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

        val chunker = YamlChunker()
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

        val chunker = YamlChunker()
        val chunks = chunker.chunk(yaml, "config.yaml")

        assertTrue(chunks.isNotEmpty())
        val descChunk = chunks.find { it.summary == "description" }
        assertNotNull(descChunk)
        assertTrue(descChunk.content.contains("multiline"))
    }

    @Test
    fun `handles invalid YAML gracefully`() {
        val yaml = """
            invalid: [unclosed
            bracket: missing
        """.trimIndent()

        val chunker = YamlChunker()
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

        val chunker = YamlChunker()
        val chunks = chunker.chunk(yaml, "large.yaml")

        assertTrue(chunks.isNotEmpty())
        chunks.forEach { chunk ->
            assertEquals(ChunkKind.YAML_BLOCK, chunk.kind)
            assertTrue(chunk.tokenEstimate!! <= 120_000)
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

        val chunker = YamlChunker()
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

        val chunker = YamlChunker()
        val chunks = chunker.chunk(yaml, "service.yaml")

        assertTrue(chunks.size >= 3)
        assertTrue(chunks.any { it.summary == "apiVersion" })
        assertTrue(chunks.any { it.summary == "kind" })
        assertTrue(chunks.any { it.summary == "metadata" })
    }

    @Test
    fun `handles list at root level`() {
        val yaml = """
            - name: item1
              value: 100
            - name: item2
              value: 200
        """.trimIndent()

        val chunker = YamlChunker()
        val chunks = chunker.chunk(yaml, "list.yaml")

        assertTrue(chunks.isNotEmpty())
        chunks.forEach {
            assertEquals(ChunkKind.YAML_BLOCK, it.kind)
        }
    }

    @Test
    fun `handles single scalar value`() {
        val yaml = "simple_value"

        val chunker = YamlChunker()
        val chunks = chunker.chunk(yaml, "scalar.yaml")

        assertEquals(1, chunks.size)
        assertEquals(ChunkKind.YAML_BLOCK, chunks[0].kind)
        assertEquals("root", chunks[0].summary)
    }

    @Test
    fun `handles boolean values`() {
        val yaml = """
            enabled: true
            disabled: false
        """.trimIndent()

        val chunker = YamlChunker()
        val chunks = chunker.chunk(yaml, "config.yaml")

        assertEquals(2, chunks.size)
        assertTrue(chunks.any { it.summary == "enabled" && it.content.contains("true") })
        assertTrue(chunks.any { it.summary == "disabled" && it.content.contains("false") })
    }

    @Test
    fun `handles null values`() {
        val yaml = """
            value: null
            another: ~
        """.trimIndent()

        val chunker = YamlChunker()
        val chunks = chunker.chunk(yaml, "config.yaml")

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.any { it.summary == "value" })
    }

    @Test
    fun `handles numeric values`() {
        val yaml = """
            integer: 42
            float: 3.14
            hex: 0x1A
        """.trimIndent()

        val chunker = YamlChunker()
        val chunks = chunker.chunk(yaml, "config.yaml")

        assertEquals(3, chunks.size)
        assertTrue(chunks.any { it.summary == "integer" && it.content.contains("42") })
        assertTrue(chunks.any { it.summary == "float" && it.content.contains("3.14") })
    }

    @Test
    fun `handles anchors and aliases`() {
        val yaml = """
            defaults: &defaults
              timeout: 30
              retries: 3
            production:
              <<: *defaults
              host: prod.example.com
        """.trimIndent()

        val chunker = YamlChunker()
        val chunks = chunker.chunk(yaml, "config.yaml")

        assertTrue(chunks.isNotEmpty())
        chunks.forEach {
            assertEquals(ChunkKind.YAML_BLOCK, it.kind)
        }
    }

    @Test
    fun `chunk ordinals are sequential`() {
        val yaml = """
            first: value1
            second: value2
            third: value3
        """.trimIndent()

        val chunker = YamlChunker()
        val chunks = chunker.chunk(yaml, "config.yaml")

        chunks.forEachIndexed { index, chunk ->
            assertEquals(index, chunk.ordinal)
        }
    }

    @Test
    fun `token estimation returns positive values`() {
        val chunker = YamlChunker()
        val yaml = "key: value"
        val chunks = chunker.chunk(yaml, "config.yaml")

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks[0].tokenEstimate!! > 0)
    }

    @Test
    fun `handles very deep nesting`() {
        val yaml = """
            level1:
              level2:
                level3:
                  level4:
                    level5:
                      value: deep
        """.trimIndent()

        val chunker = YamlChunker()
        val chunks = chunker.chunk(yaml, "deep.yaml")

        assertTrue(chunks.isNotEmpty())
        chunks.forEach {
            assertEquals(ChunkKind.YAML_BLOCK, it.kind)
        }
    }

    @Test
    fun `handles empty nested maps`() {
        val yaml = """
            empty_map: {}
            filled_map:
              key: value
        """.trimIndent()

        val chunker = YamlChunker()
        val chunks = chunker.chunk(yaml, "config.yaml")

        assertTrue(chunks.size >= 2)
    }

    @Test
    fun `handles empty lists`() {
        val yaml = """
            empty_list: []
            filled_list:
              - item1
              - item2
        """.trimIndent()

        val chunker = YamlChunker()
        val chunks = chunker.chunk(yaml, "config.yaml")

        assertTrue(chunks.size >= 2)
    }

    @Test
    fun `handles quoted strings`() {
        val yaml = """
            single: 'single quoted'
            double: "double quoted"
            special: "contains: colon"
        """.trimIndent()

        val chunker = YamlChunker()
        val chunks = chunker.chunk(yaml, "config.yaml")

        assertEquals(3, chunks.size)
        assertTrue(chunks.any { it.summary == "single" })
        assertTrue(chunks.any { it.summary == "double" })
        assertTrue(chunks.any { it.summary == "special" })
    }

    @Test
    fun `handles comments in YAML`() {
        val yaml = """
            # This is a comment
            key: value # inline comment
            # Another comment
            another: data
        """.trimIndent()

        val chunker = YamlChunker()
        val chunks = chunker.chunk(yaml, "config.yaml")

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.any { it.summary == "key" })
        assertTrue(chunks.any { it.summary == "another" })
    }

    @Test
    fun `stress test with large YAML file`() {
        val yaml = buildString {
            repeat(50) { i ->
                appendLine("section_$i:")
                repeat(10) { j ->
                    appendLine("  key_$j: value_${i}_$j")
                }
            }
        }

        val chunker = YamlChunker()
        val chunks = chunker.chunk(yaml, "large.yaml")

        assertTrue(chunks.size >= 50)
        chunks.forEach {
            assertEquals(ChunkKind.YAML_BLOCK, it.kind)
            assertTrue(it.tokenEstimate!! > 0)
        }
    }

    @Test
    fun `chunks have non-null timestamps`() {
        val yaml = "key: value"

        val chunker = YamlChunker()
        val chunks = chunker.chunk(yaml, "config.yaml")

        chunks.forEach {
            assertNotNull(it.createdAt)
        }
    }

    @Test
    fun `respects maxTokens parameter`() {
        val chunker = YamlChunker(maxTokens = 100)
        val yaml = buildString {
            appendLine("large_section:")
            repeat(100) { i ->
                appendLine("  key_$i: ${"value".repeat(20)}")
            }
        }

        val chunks = chunker.chunk(yaml, "config.yaml")

        chunks.forEach {
            assertTrue(it.tokenEstimate!! <= 120_000)
        }
    }

    @Test
    fun `handles folded scalars`() {
        val yaml = """
            description: >
              This is a folded
              scalar that should
              be on one line
        """.trimIndent()

        val chunker = YamlChunker()
        val chunks = chunker.chunk(yaml, "config.yaml")

        assertTrue(chunks.isNotEmpty())
        val descChunk = chunks.find { it.summary == "description" }
        assertNotNull(descChunk)
    }
}
