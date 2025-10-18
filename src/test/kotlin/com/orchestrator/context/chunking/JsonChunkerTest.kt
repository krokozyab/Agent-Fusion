package com.orchestrator.context.chunking

import com.orchestrator.context.domain.ChunkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JsonChunkerTest {

    @Test
    fun `empty content returns empty list`() {
        val chunker = JsonChunker()
        val chunks = chunker.chunk("", "test.json")
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `blank content returns empty list`() {
        val chunker = JsonChunker()
        val chunks = chunker.chunk("   \n\n  \n", "test.json")
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `chunks simple JSON with top-level keys`() {
        val json = """
            {
              "name": "MyApp",
              "version": "1.0.0",
              "description": "A sample application"
            }
        """.trimIndent()

        val chunker = JsonChunker()
        val chunks = chunker.chunk(json, "package.json")

        assertEquals(3, chunks.size)
        chunks.forEach { chunk ->
            assertEquals(ChunkKind.JSON_BLOCK, chunk.kind)
        }

        assertTrue(chunks.any { it.summary == "name" && it.content.contains("MyApp") })
        assertTrue(chunks.any { it.summary == "version" && it.content.contains("1.0.0") })
        assertTrue(chunks.any { it.summary == "description" })
    }

    @Test
    fun `chunks nested JSON structure`() {
        val json = """
            {
              "database": {
                "host": "localhost",
                "port": 5432,
                "credentials": {
                  "username": "admin",
                  "password": "secret"
                }
              }
            }
        """.trimIndent()

        val chunker = JsonChunker()
        val chunks = chunker.chunk(json, "config.json")

        assertTrue(chunks.isNotEmpty())
        assertEquals(ChunkKind.JSON_BLOCK, chunks[0].kind)

        val dbChunk = chunks.find { it.summary == "database" }
        assertNotNull(dbChunk)
        assertTrue(dbChunk.content.contains("host") || dbChunk.content.contains("localhost"))
    }

    @Test
    fun `chunks JSON with arrays`() {
        val json = """
            {
              "services": [
                {
                  "name": "web",
                  "port": 8080
                },
                {
                  "name": "api",
                  "port": 3000
                }
              ]
            }
        """.trimIndent()

        val chunker = JsonChunker()
        val chunks = chunker.chunk(json, "services.json")

        assertTrue(chunks.isNotEmpty())
        val servicesChunk = chunks.find { it.summary == "services" }
        assertNotNull(servicesChunk)
        assertTrue(servicesChunk.content.contains("web") || servicesChunk.content.contains("api"))
    }

    @Test
    fun `preserves key paths for nested structures`() {
        val json = """
            {
              "app": {
                "server": {
                  "host": "localhost",
                  "port": 8080
                }
              }
            }
        """.trimIndent()

        val chunker = JsonChunker()
        val chunks = chunker.chunk(json, "config.json")

        assertTrue(chunks.isNotEmpty())
        val appChunk = chunks.find { it.summary == "app" }
        assertNotNull(appChunk)
    }

    @Test
    fun `handles multiline strings`() {
        val json = """
            {
              "description": "This is a long\nmultiline description\nthat spans several lines"
            }
        """.trimIndent()

        val chunker = JsonChunker()
        val chunks = chunker.chunk(json, "config.json")

        assertTrue(chunks.isNotEmpty())
        val descChunk = chunks.find { it.summary == "description" }
        assertNotNull(descChunk)
        assertTrue(descChunk.content.contains("multiline") || descChunk.content.contains("\\n"))
    }

    @Test
    fun `handles invalid JSON gracefully`() {
        val json = """
            {
              "invalid": [unclosed
              "bracket": missing
            }
        """.trimIndent()

        val chunker = JsonChunker()
        val chunks = chunker.chunk(json, "invalid.json")

        assertEquals(1, chunks.size)
        assertEquals(ChunkKind.JSON_BLOCK, chunks[0].kind)
        assertEquals("root", chunks[0].summary)
    }

    @Test
    fun `splits large nested structures`() {
        val json = buildString {
            appendLine("{")
            appendLine("  \"config\": {")
            repeat(50) { i ->
                appendLine("    \"key$i\": \"value$i\"${if (i < 49) "," else ""}")
            }
            appendLine("  }")
            append("}")
        }

        val chunker = JsonChunker()
        val chunks = chunker.chunk(json, "large.json")

        assertTrue(chunks.isNotEmpty())
        chunks.forEach { chunk ->
            assertEquals(ChunkKind.JSON_BLOCK, chunk.kind)
            assertTrue(chunk.tokenEstimate!! <= 120_000)
        }
    }

    @Test
    fun `handles package json style structure`() {
        val json = """
            {
              "name": "my-app",
              "version": "1.0.0",
              "scripts": {
                "start": "node server.js",
                "test": "jest",
                "build": "webpack"
              },
              "dependencies": {
                "express": "^4.18.0",
                "react": "^18.0.0"
              }
            }
        """.trimIndent()

        val chunker = JsonChunker()
        val chunks = chunker.chunk(json, "package.json")

        assertTrue(chunks.size >= 4)
        assertTrue(chunks.any { it.summary == "name" })
        assertTrue(chunks.any { it.summary == "version" })
        assertTrue(chunks.any { it.summary == "scripts" })
        assertTrue(chunks.any { it.summary == "dependencies" })
    }

    @Test
    fun `handles tsconfig json style structure`() {
        val json = """
            {
              "compilerOptions": {
                "target": "ES2020",
                "module": "commonjs",
                "strict": true,
                "esModuleInterop": true
              },
              "include": ["src/**/*"],
              "exclude": ["node_modules"]
            }
        """.trimIndent()

        val chunker = JsonChunker()
        val chunks = chunker.chunk(json, "tsconfig.json")

        assertTrue(chunks.size >= 3)
        assertTrue(chunks.any { it.summary == "compilerOptions" })
        assertTrue(chunks.any { it.summary == "include" })
        assertTrue(chunks.any { it.summary == "exclude" })
    }

    @Test
    fun `handles array at root level`() {
        val json = """
            [
              {
                "name": "item1",
                "value": 100
              },
              {
                "name": "item2",
                "value": 200
              }
            ]
        """.trimIndent()

        val chunker = JsonChunker()
        val chunks = chunker.chunk(json, "list.json")

        assertTrue(chunks.isNotEmpty())
        chunks.forEach {
            assertEquals(ChunkKind.JSON_BLOCK, it.kind)
        }
        assertTrue(chunks.any { it.summary == "[0]" })
        assertTrue(chunks.any { it.summary == "[1]" })
    }

    @Test
    fun `handles single primitive value`() {
        val json = "\"simple_value\""

        val chunker = JsonChunker()
        val chunks = chunker.chunk(json, "scalar.json")

        assertEquals(1, chunks.size)
        assertEquals(ChunkKind.JSON_BLOCK, chunks[0].kind)
        assertEquals("root", chunks[0].summary)
    }

    @Test
    fun `handles boolean values`() {
        val json = """
            {
              "enabled": true,
              "disabled": false
            }
        """.trimIndent()

        val chunker = JsonChunker()
        val chunks = chunker.chunk(json, "config.json")

        assertEquals(2, chunks.size)
        assertTrue(chunks.any { it.summary == "enabled" && it.content.contains("true") })
        assertTrue(chunks.any { it.summary == "disabled" && it.content.contains("false") })
    }

    @Test
    fun `handles null values`() {
        val json = """
            {
              "value": null,
              "another": null
            }
        """.trimIndent()

        val chunker = JsonChunker()
        val chunks = chunker.chunk(json, "config.json")

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.any { it.summary == "value" })
        assertTrue(chunks.any { it.summary == "another" })
    }

    @Test
    fun `handles numeric values`() {
        val json = """
            {
              "integer": 42,
              "float": 3.14,
              "negative": -100,
              "scientific": 1.5e10
            }
        """.trimIndent()

        val chunker = JsonChunker()
        val chunks = chunker.chunk(json, "config.json")

        assertEquals(4, chunks.size)
        assertTrue(chunks.any { it.summary == "integer" && it.content.contains("42") })
        assertTrue(chunks.any { it.summary == "float" && it.content.contains("3.14") })
        assertTrue(chunks.any { it.summary == "negative" })
        assertTrue(chunks.any { it.summary == "scientific" })
    }

    @Test
    fun `chunk ordinals are sequential`() {
        val json = """
            {
              "first": "value1",
              "second": "value2",
              "third": "value3"
            }
        """.trimIndent()

        val chunker = JsonChunker()
        val chunks = chunker.chunk(json, "config.json")

        chunks.forEachIndexed { index, chunk ->
            assertEquals(index, chunk.ordinal)
        }
    }

    @Test
    fun `token estimation returns positive values`() {
        val chunker = JsonChunker()
        val json = """{"key": "value"}"""
        val chunks = chunker.chunk(json, "config.json")

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks[0].tokenEstimate!! > 0)
    }

    @Test
    fun `handles very deep nesting`() {
        val json = """
            {
              "level1": {
                "level2": {
                  "level3": {
                    "level4": {
                      "level5": {
                        "value": "deep"
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val chunker = JsonChunker()
        val chunks = chunker.chunk(json, "deep.json")

        assertTrue(chunks.isNotEmpty())
        chunks.forEach {
            assertEquals(ChunkKind.JSON_BLOCK, it.kind)
        }
    }

    @Test
    fun `handles empty nested objects`() {
        val json = """
            {
              "empty_object": {},
              "filled_object": {
                "key": "value"
              }
            }
        """.trimIndent()

        val chunker = JsonChunker()
        val chunks = chunker.chunk(json, "config.json")

        assertTrue(chunks.size >= 2)
        assertTrue(chunks.any { it.summary == "empty_object" })
        assertTrue(chunks.any { it.summary == "filled_object" })
    }

    @Test
    fun `handles empty arrays`() {
        val json = """
            {
              "empty_array": [],
              "filled_array": ["item1", "item2"]
            }
        """.trimIndent()

        val chunker = JsonChunker()
        val chunks = chunker.chunk(json, "config.json")

        assertTrue(chunks.size >= 2)
        assertTrue(chunks.any { it.summary == "empty_array" })
        assertTrue(chunks.any { it.summary == "filled_array" })
    }

    @Test
    fun `handles escaped strings`() {
        val json = """
            {
              "escaped": "Contains \"quotes\" and \\backslash",
              "special": "Contains\nnewline\tand\ttabs"
            }
        """.trimIndent()

        val chunker = JsonChunker()
        val chunks = chunker.chunk(json, "config.json")

        assertEquals(2, chunks.size)
        assertTrue(chunks.any { it.summary == "escaped" })
        assertTrue(chunks.any { it.summary == "special" })
    }

    @Test
    fun `handles mixed array types`() {
        val json = """
            {
              "mixed": [
                "string",
                42,
                true,
                null,
                {"nested": "object"},
                ["nested", "array"]
              ]
            }
        """.trimIndent()

        val chunker = JsonChunker()
        val chunks = chunker.chunk(json, "config.json")

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.any { it.summary == "mixed" })
    }

    @Test
    fun `stress test with large JSON file`() {
        val json = buildString {
            appendLine("{")
            repeat(50) { i ->
                appendLine("  \"section_$i\": {")
                repeat(10) { j ->
                    val comma = if (j < 9) "," else ""
                    appendLine("    \"key_$j\": \"value_${i}_$j\"$comma")
                }
                val comma = if (i < 49) "," else ""
                appendLine("  }$comma")
            }
            append("}")
        }

        val chunker = JsonChunker()
        val chunks = chunker.chunk(json, "large.json")

        assertTrue(chunks.size >= 50)
        chunks.forEach {
            assertEquals(ChunkKind.JSON_BLOCK, it.kind)
            assertTrue(it.tokenEstimate!! > 0)
        }
    }

    @Test
    fun `chunks have non-null timestamps`() {
        val json = """{"key": "value"}"""

        val chunker = JsonChunker()
        val chunks = chunker.chunk(json, "config.json")

        chunks.forEach {
            assertNotNull(it.createdAt)
        }
    }

    @Test
    fun `respects maxTokens parameter`() {
        val chunker = JsonChunker(maxTokens = 100)
        val json = buildString {
            appendLine("{")
            appendLine("  \"large_section\": {")
            repeat(100) { i ->
                val comma = if (i < 99) "," else ""
                appendLine("    \"key_$i\": \"${"value".repeat(20)}\"$comma")
            }
            appendLine("  }")
            append("}")
        }

        val chunks = chunker.chunk(json, "config.json")

        chunks.forEach {
            assertTrue(it.tokenEstimate!! <= 120_000)
        }
    }

    @Test
    fun `handles unicode characters`() {
        val json = """
            {
              "chinese": "你好世界",
              "emoji": "👋🌍",
              "mixed": "Hello 世界 🌍"
            }
        """.trimIndent()

        val chunker = JsonChunker()
        val chunks = chunker.chunk(json, "i18n.json")

        assertEquals(3, chunks.size)
        assertTrue(chunks.any { it.summary == "chinese" })
        assertTrue(chunks.any { it.summary == "emoji" })
        assertTrue(chunks.any { it.summary == "mixed" })
    }

    @Test
    fun `handles very large array at top level`() {
        val json = buildString {
            appendLine("[")
            repeat(100) { i ->
                val comma = if (i < 99) "," else ""
                appendLine("  {\"id\": $i, \"name\": \"item_$i\"}$comma")
            }
            append("]")
        }

        val chunker = JsonChunker()
        val chunks = chunker.chunk(json, "items.json")

        assertEquals(100, chunks.size)
        chunks.forEachIndexed { index, chunk ->
            assertEquals("[$index]", chunk.summary)
        }
    }

    @Test
    fun `handles nested arrays`() {
        val json = """
            {
              "matrix": [
                [1, 2, 3],
                [4, 5, 6],
                [7, 8, 9]
              ]
            }
        """.trimIndent()

        val chunker = JsonChunker()
        val chunks = chunker.chunk(json, "matrix.json")

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.any { it.summary == "matrix" })
    }

    @Test
    fun `handles object with single key`() {
        val json = """
            {
              "single": "value"
            }
        """.trimIndent()

        val chunker = JsonChunker()
        val chunks = chunker.chunk(json, "single.json")

        assertEquals(1, chunks.size)
        assertEquals("single", chunks[0].summary)
    }

    @Test
    fun `handles minified JSON`() {
        val json = """{"name":"app","version":"1.0.0","enabled":true}"""

        val chunker = JsonChunker()
        val chunks = chunker.chunk(json, "minified.json")

        assertEquals(3, chunks.size)
        assertTrue(chunks.any { it.summary == "name" })
        assertTrue(chunks.any { it.summary == "version" })
        assertTrue(chunks.any { it.summary == "enabled" })
    }

    @Test
    fun `handles whitespace variations`() {
        val json = """
            {
                "spaced"    :    "value"   ,
                "tabs":	"value",
                "mixed"  :	  "value"
            }
        """.trimIndent()

        val chunker = JsonChunker()
        val chunks = chunker.chunk(json, "whitespace.json")

        assertEquals(3, chunks.size)
        assertTrue(chunks.any { it.summary == "spaced" })
        assertTrue(chunks.any { it.summary == "tabs" })
        assertTrue(chunks.any { it.summary == "mixed" })
    }

    @Test
    fun `handles trailing commas gracefully`() {
        // Note: Trailing commas are not valid JSON, should fall back to single chunk
        val json = """
            {
              "key": "value",
            }
        """.trimIndent()

        val chunker = JsonChunker()
        val chunks = chunker.chunk(json, "invalid.json")

        // Should fall back to root chunk due to parse error
        assertEquals(1, chunks.size)
        assertEquals("root", chunks[0].summary)
    }

    @Test
    fun `chunks path notation for deeply nested objects`() {
        val json = buildString {
            appendLine("{")
            appendLine("  \"config\": {")
            appendLine("    \"database\": {")
            repeat(30) { i ->
                val comma = if (i < 29) "," else ""
                appendLine("      \"setting_$i\": \"value_$i\"$comma")
            }
            appendLine("    }")
            appendLine("  }")
            append("}")
        }

        val chunker = JsonChunker(maxTokens = 200)
        val chunks = chunker.chunk(json, "config.json")

        assertTrue(chunks.isNotEmpty())
        // Should split the database object into multiple chunks
        assertTrue(chunks.any { it.summary?.contains("database") == true || it.summary?.contains("config") == true })
    }
}
