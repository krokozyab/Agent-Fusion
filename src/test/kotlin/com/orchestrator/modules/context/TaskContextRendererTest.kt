package com.orchestrator.modules.context

import com.orchestrator.context.domain.ChunkKind
import com.orchestrator.context.domain.ContextSnippet
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName

/**
 * Unit tests for TaskContextRenderer.
 *
 * Tests XML generation, escaping, sorting, grouping, and budget enforcement.
 */
class TaskContextRendererTest {

    @Test
    @DisplayName("renders empty context")
    fun `renders empty context`() {
        val context = createTaskContext(emptyList())
        val xml = TaskContextRenderer.render(context)

        assertTrue(xml.contains("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(xml.contains("<project_context>"))
        assertTrue(xml.contains("</project_context>"))
        assertTrue(xml.contains("<total_snippets>0</total_snippets>"))
    }

    @Test
    @DisplayName("renders single snippet")
    fun `renders single snippet`() {
        val snippet = createSnippet(
            filePath = "src/main/User.kt",
            label = "User class",
            kind = ChunkKind.CODE_CLASS,
            score = 0.95,
            text = "class User { }",
            language = "kotlin"
        )

        val context = createTaskContext(listOf(snippet))
        val xml = TaskContextRenderer.render(context)

        assertTrue(xml.contains("<file path=\"src/main/User.kt\" language=\"kotlin\">"))
        assertTrue(xml.contains("<snippet label=\"User class\" kind=\"CODE_CLASS\" score=\"0.950\""))
        assertTrue(xml.contains("class User { }"))
        assertTrue(xml.contains("</file>"))
    }

    @Test
    @DisplayName("sorts snippets by score descending")
    fun `sorts snippets by score descending`() {
        val snippet1 = createSnippet(filePath = "file1.kt", score = 0.5, text = "low score")
        val snippet2 = createSnippet(filePath = "file2.kt", score = 0.9, text = "high score")
        val snippet3 = createSnippet(filePath = "file3.kt", score = 0.7, text = "medium score")

        val context = createTaskContext(listOf(snippet1, snippet2, snippet3))
        val xml = TaskContextRenderer.render(context)

        // Find positions of each snippet in XML
        val pos1 = xml.indexOf("low score")
        val pos2 = xml.indexOf("high score")
        val pos3 = xml.indexOf("medium score")

        // Verify ordering: high (0.9) < medium (0.7) < low (0.5)
        assertTrue(pos2 < pos3, "High score should appear before medium score")
        assertTrue(pos3 < pos1, "Medium score should appear before low score")
    }

    @Test
    @DisplayName("groups snippets by file")
    fun `groups snippets by file`() {
        val snippet1 = createSnippet(filePath = "File.kt", score = 0.9, text = "snippet 1")
        val snippet2 = createSnippet(filePath = "File.kt", score = 0.8, text = "snippet 2")
        val snippet3 = createSnippet(filePath = "Other.kt", score = 0.7, text = "snippet 3")

        val context = createTaskContext(listOf(snippet1, snippet2, snippet3))
        val xml = TaskContextRenderer.render(context)

        // Verify file grouping
        val fileCount = Regex("<file path=").findAll(xml).count()
        assertEquals(2, fileCount, "Should have 2 file groups")

        // Verify both snippets from File.kt appear together
        val fileKtStart = xml.indexOf("<file path=\"File.kt\"")
        val fileKtEnd = xml.indexOf("</file>", fileKtStart)
        val fileKtSection = xml.substring(fileKtStart, fileKtEnd)

        assertTrue(fileKtSection.contains("snippet 1"))
        assertTrue(fileKtSection.contains("snippet 2"))
        assertFalse(fileKtSection.contains("snippet 3"))
    }

    @Test
    @DisplayName("escapes XML special characters")
    fun `escapes XML special characters`() {
        val snippet = createSnippet(
            filePath = "path/with<special>&chars\"'",
            label = "Label with <xml> & \"quotes\"",
            text = "code with <brackets> & \"quotes\" and 'apostrophes'",
            language = "text"
        )

        val context = createTaskContext(listOf(snippet))
        val xml = TaskContextRenderer.render(context)

        // Verify escaping in attributes
        assertTrue(xml.contains("path/with&lt;special&gt;&amp;chars&quot;&apos;"))
        assertTrue(xml.contains("Label with &lt;xml&gt; &amp; &quot;quotes&quot;"))

        // Content inside CDATA should NOT be escaped
        assertTrue(xml.contains("<![CDATA["))
        assertTrue(xml.contains("code with <brackets> & \"quotes\" and 'apostrophes'"))
    }

    @Test
    @DisplayName("includes line ranges when available")
    fun `includes line ranges when available`() {
        val snippet = createSnippet(
            filePath = "File.kt",
            text = "code",
            offsets = IntRange(10, 45)
        )

        val context = createTaskContext(listOf(snippet))
        val xml = TaskContextRenderer.render(context)

        assertTrue(xml.contains("lines=\"10-45\""))
    }

    @Test
    @DisplayName("omits line ranges when not available")
    fun `omits line ranges when not available`() {
        val snippet = createSnippet(
            filePath = "File.kt",
            text = "code",
            offsets = null
        )

        val context = createTaskContext(listOf(snippet))
        val xml = TaskContextRenderer.render(context)

        assertFalse(xml.contains("lines="))
    }

    @Test
    @DisplayName("includes snippet metadata")
    fun `includes snippet metadata`() {
        val snippet = createSnippet(
            filePath = "File.kt",
            text = "code",
            metadata = mapOf(
                "provider" to "semantic",
                "chunk_id" to "123",
                "file_id" to "456"
            )
        )

        val context = createTaskContext(listOf(snippet))
        val xml = TaskContextRenderer.render(context)

        assertTrue(xml.contains("<metadata>"))
        assertTrue(xml.contains("<provider>semantic</provider>"))
        assertTrue(xml.contains("<chunk_id>123</chunk_id>"))
        assertTrue(xml.contains("<file_id>456</file_id>"))
    }

    @Test
    @DisplayName("includes task metadata")
    fun `includes task metadata`() {
        val context = createTaskContext(
            snippets = listOf(createSnippet("File.kt", text = "code")),
            metadata = mapOf(
                "task_type" to "implementation",
                "priority" to "high"
            )
        )

        val xml = TaskContextRenderer.render(context)

        assertTrue(xml.contains("<task_type>implementation</task_type>"))
        assertTrue(xml.contains("<priority>high</priority>"))
    }

    @Test
    @DisplayName("includes diagnostics information")
    fun `includes diagnostics information`() {
        val context = createTaskContext(listOf(createSnippet("File.kt", text = "code")))
        val xml = TaskContextRenderer.render(context)

        assertTrue(xml.contains("<diagnostics>"))
        assertTrue(xml.contains("<total_snippets>1</total_snippets>"))
        assertTrue(xml.contains("<tokens_requested>"))
        assertTrue(xml.contains("<tokens_used>"))
        assertTrue(xml.contains("<duration_ms>10</duration_ms>"))
        assertTrue(xml.contains("</diagnostics>"))
    }

    @Test
    @DisplayName("respects token budget")
    fun `respects token budget`() {
        val snippets = (1..10).map { i ->
            createSnippet(
                filePath = "File$i.kt",
                text = "x".repeat(100), // 100 chars ≈ 25 tokens
                score = 1.0 - (i * 0.01)
            )
        }

        val context = createTaskContext(snippets)

        // Set budget to allow only ~3 snippets (75 tokens for content + overhead)
        val xml = TaskContextRenderer.render(context, maxTokens = 200)

        val snippetCount = Regex("<snippet").findAll(xml).count()
        assertTrue(snippetCount <= 4, "Should truncate to fit budget (got $snippetCount snippets)")
        assertTrue(xml.contains("<!-- Context truncated due to token budget -->"))
    }

    @Test
    @DisplayName("renders compact XML without whitespace")
    fun `renders compact XML without whitespace`() {
        val snippet = createSnippet("File.kt", text = "code")
        val context = createTaskContext(listOf(snippet))

        val compact = TaskContextRenderer.renderCompact(context)

        // Compact should have no line breaks
        assertFalse(compact.contains("\n"))
        assertTrue(compact.contains("<project_context>"))
        assertTrue(compact.contains("</project_context>"))
    }

    @Test
    @DisplayName("validates well-formed XML")
    fun `validates well-formed XML`() {
        val snippet = createSnippet("File.kt", text = "code")
        val context = createTaskContext(listOf(snippet))
        val xml = TaskContextRenderer.render(context)

        assertTrue(TaskContextRenderer.validateXml(xml))
    }

    @Test
    @DisplayName("detects malformed XML")
    fun `detects malformed XML`() {
        val malformed = "<project_context><unclosed>"
        assertFalse(TaskContextRenderer.validateXml(malformed))
    }

    @Test
    @DisplayName("calculates statistics correctly")
    fun `calculates statistics correctly`() {
        val snippets = listOf(
            createSnippet("File1.kt", text = "code 1"),
            createSnippet("File1.kt", text = "code 2"),
            createSnippet("File2.kt", text = "code 3")
        )

        val context = createTaskContext(snippets)
        val xml = TaskContextRenderer.render(context)

        val stats = TaskContextRenderer.getStatistics(xml)

        assertEquals(3, stats.snippetCount, "Should count 3 snippets")
        assertEquals(2, stats.fileCount, "Should count 2 files")
        assertTrue(stats.estimatedTokens > 0, "Should estimate tokens")
        assertTrue(stats.byteSize > 0, "Should calculate byte size")
    }

    @Test
    @DisplayName("handles multiple snippets per file with different scores")
    fun `handles multiple snippets per file with different scores`() {
        val snippets = listOf(
            createSnippet("File.kt", score = 0.9, text = "high score snippet"),
            createSnippet("File.kt", score = 0.5, text = "low score snippet"),
            createSnippet("File.kt", score = 0.7, text = "medium score snippet")
        )

        val context = createTaskContext(snippets)
        val xml = TaskContextRenderer.render(context)

        // Find file section
        val fileStart = xml.indexOf("<file path=\"File.kt\"")
        val fileEnd = xml.indexOf("</file>", fileStart)
        val fileSection = xml.substring(fileStart, fileEnd)

        // Verify ordering within file (should be by score descending)
        val highPos = fileSection.indexOf("high score snippet")
        val mediumPos = fileSection.indexOf("medium score snippet")
        val lowPos = fileSection.indexOf("low score snippet")

        assertTrue(highPos < mediumPos, "High score should appear before medium")
        assertTrue(mediumPos < lowPos, "Medium score should appear before low")
    }

    @Test
    @DisplayName("escapes invalid XML tag characters in metadata keys")
    fun `escapes invalid XML tag characters in metadata keys`() {
        val snippet = createSnippet(
            "File.kt",
            text = "code",
            metadata = mapOf(
                "key-with-dash" to "value1",
                "key.with.dot" to "value2",
                "key with space" to "value3",
                "123numeric" to "value4"
            )
        )

        val context = createTaskContext(listOf(snippet))
        val xml = TaskContextRenderer.render(context)

        // Dashes and dots should be preserved
        assertTrue(xml.contains("<key-with-dash>"))
        assertTrue(xml.contains("<key.with.dot>"))

        // Spaces should be replaced with underscores
        assertTrue(xml.contains("<key_with_space>"))

        // Numeric start should be prefixed with underscore
        assertTrue(xml.contains("<_123numeric>"))
    }

    @Test
    @DisplayName("preserves code formatting in CDATA")
    fun `preserves code formatting in CDATA`() {
        val code = """
            class User {
                fun authenticate(): Boolean {
                    return true
                }
            }
        """.trimIndent()

        val snippet = createSnippet("File.kt", text = code)
        val context = createTaskContext(listOf(snippet))
        val xml = TaskContextRenderer.render(context)

        assertTrue(xml.contains("<![CDATA["))
        assertTrue(xml.contains("class User {"))
        assertTrue(xml.contains("    fun authenticate(): Boolean {"))
        assertTrue(xml.contains("        return true"))
    }

    // Helper functions

    private fun createSnippet(
        filePath: String,
        label: String? = null,
        kind: ChunkKind = ChunkKind.CODE_BLOCK,
        score: Double = 0.8,
        text: String,
        language: String? = null,
        offsets: IntRange? = null,
        metadata: Map<String, String> = emptyMap()
    ): ContextSnippet {
        return ContextSnippet(
            chunkId = 1L,
            score = score,
            filePath = filePath,
            label = label,
            kind = kind,
            text = text,
            language = language,
            offsets = offsets,
            metadata = metadata
        )
    }

    private fun createTaskContext(
        snippets: List<ContextSnippet>,
        metadata: Map<String, String> = emptyMap()
    ): ContextRetrievalModule.TaskContext {
        val budget = com.orchestrator.context.domain.TokenBudget(maxTokens = 10000)
        return ContextRetrievalModule.TaskContext(
            taskId = "test-task-123",
            snippets = snippets,
            diagnostics = ContextRetrievalModule.ContextDiagnostics(
                budget = budget,
                providerMetrics = emptyMap(),
                duration = java.time.Duration.ofMillis(10),
                warnings = emptyList(),
                fallbackUsed = false,
                fallbackProvider = null,
                tokensRequested = budget.availableForSnippets,
                tokensUsed = snippets.sumOf { it.text.length / 4 }
            ),
            metadata = metadata
        )
    }
}
