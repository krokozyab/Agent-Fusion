package com.orchestrator.web.components

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.time.Instant

@DisplayName("FileRow Component Tests")
class FileRowTest {

    @Test
    @DisplayName("converts file model to data table row")
    fun testFileRowConversion() {
        val model = FileBrowser.Model(
            path = "src/main/kotlin/com/example/Example.kt",
            status = "indexed",
            sizeBytes = 2048,
            lastModified = Instant.now(),
            chunkCount = 5,
            extension = "kt"
        )

        val row = FileRow.toRow(model, "/files/example/detail")

        assertNotNull(row)
        assertNotNull(row.id)
        assertNotNull(row.ariaLabel)
        assertTrue(row.cells.isNotEmpty())
    }

    @Test
    @DisplayName("renders file path with directory context")
    fun testFilePathCell() {
        val model = FileBrowser.Model(
            path = "src/main/kotlin/com/example/Example.kt",
            status = "indexed",
            sizeBytes = 1024,
            lastModified = null,
            chunkCount = 1,
            extension = "kt"
        )

        val row = FileRow.toRow(model, "/files/test")

        // First cell should contain path information
        assertTrue(row.cells[0].raw)
        assertContains(row.cells[0].content, "Example.kt")
        assertContains(row.cells[0].content, "src/main/kotlin/com/example")
    }

    @Test
    @DisplayName("renders status badge with correct tone")
    fun testStatusBadge() {
        listOf("indexed", "outdated", "error", "pending").forEach { status ->
            val model = FileBrowser.Model(
                path = "test.kt",
                status = status,
                sizeBytes = 100,
                lastModified = null,
                chunkCount = 1,
                extension = "kt"
            )

            val row = FileRow.toRow(model, "/files/test")

            // Status should be in the second cell
            assertContains(row.cells[1].content, status.uppercase())
        }
    }

    @Test
    @DisplayName("formats file sizes correctly")
    fun testFileSizeFormatting() {
        val testCases = listOf(
            512L to "512 B",
            1024L to "1 KB",
            1048576L to "1 MB",
            1073741824L to "1 GB"
        )

        testCases.forEach { (bytes, expected) ->
            val model = FileBrowser.Model(
                path = "file.kt",
                status = "indexed",
                sizeBytes = bytes,
                lastModified = null,
                chunkCount = 1,
                extension = "kt"
            )

            val row = FileRow.toRow(model, "/files/test")

            // Size should be in the fourth cell (numeric)
            assertContains(row.cells[3].content, expected)
        }
    }

    @Test
    @DisplayName("displays file extension correctly")
    fun testFileExtension() {
        listOf("kt", "ts", "py", "java").forEach { ext ->
            val model = FileBrowser.Model(
                path = "file.$ext",
                status = "indexed",
                sizeBytes = 100,
                lastModified = null,
                chunkCount = 1,
                extension = ext
            )

            val row = FileRow.toRow(model, "/files/test")

            // Extension should be in the third cell
            assertContains(row.cells[2].content, ext.uppercase())
        }
    }

    @Test
    @DisplayName("includes chunk count in row")
    fun testChunkCount() {
        val model = FileBrowser.Model(
            path = "file.kt",
            status = "indexed",
            sizeBytes = 100,
            lastModified = null,
            chunkCount = 42,
            extension = "kt"
        )

        val row = FileRow.toRow(model, "/files/test")

        // Chunk count should be in the sixth cell (numeric)
        assertContains(row.cells[5].content, "42")
    }

    @Test
    @DisplayName("includes view action button")
    fun testActionButtons() {
        val model = FileBrowser.Model(
            path = "file.kt",
            status = "indexed",
            sizeBytes = 100,
            lastModified = null,
            chunkCount = 1,
            extension = "kt"
        )

        val row = FileRow.toRow(model, "/files/example/detail")

        // Last cell should contain actions
        val actionCell = row.cells.last()
        assertContains(actionCell.content, "View")
        assertContains(actionCell.content, "hx-get")
        assertContains(actionCell.content, "/files/example/detail")
    }

    @Test
    @DisplayName("sets proper HTMX attributes on row")
    fun testHTMXAttributes() {
        val model = FileBrowser.Model(
            path = "file.kt",
            status = "indexed",
            sizeBytes = 100,
            lastModified = null,
            chunkCount = 1,
            extension = "kt"
        )

        val row = FileRow.toRow(model, "/files/test")

        // Check for HTMX attributes in row
        assertTrue(row.attributes.containsKey("data-file-path"))
        assertTrue(row.attributes.containsKey("data-file-status"))
        assertEquals(row.attributes["data-file-path"], "file.kt")
        assertEquals(row.attributes["data-file-status"], "indexed")
    }

    @Test
    @DisplayName("handles files without modification time")
    fun testMissingModificationTime() {
        val model = FileBrowser.Model(
            path = "file.kt",
            status = "pending",
            sizeBytes = 100,
            lastModified = null,
            chunkCount = 1,
            extension = "kt"
        )

        val row = FileRow.toRow(model, "/files/test")

        // Should not throw and should have empty dash for time
        assertNotNull(row)
        assertContains(row.cells[4].content, "—")
    }

    @Test
    @DisplayName("includes row ID with file hash")
    fun testRowId() {
        val model = FileBrowser.Model(
            path = "src/main/kotlin/Example.kt",
            status = "indexed",
            sizeBytes = 100,
            lastModified = null,
            chunkCount = 1,
            extension = "kt"
        )

        val row = FileRow.toRow(model, "/files/test")

        assertNotNull(row.id)
        assertTrue(row.id!!.startsWith("file-row-"))
    }

    @Test
    @DisplayName("renders raw HTML for badges and links")
    fun testRawHTMLContent() {
        val model = FileBrowser.Model(
            path = "file.kt",
            status = "indexed",
            sizeBytes = 100,
            lastModified = null,
            chunkCount = 1,
            extension = "kt"
        )

        val row = FileRow.toRow(model, "/files/test")

        // Status and action cells should have raw HTML
        assertTrue(row.cells[1].raw, "Status cell should be raw HTML")
        assertTrue(row.cells[6].raw, "Action cell should be raw HTML")
    }

    @Test
    @DisplayName("handles different status values")
    fun testStatusValues() {
        val statuses = listOf("indexed", "outdated", "error", "pending")

        statuses.forEach { status ->
            val model = FileBrowser.Model(
                path = "file.kt",
                status = status,
                sizeBytes = 100,
                lastModified = null,
                chunkCount = 1,
                extension = "kt"
            )

            val row = FileRow.toRow(model, "/files/test")

            assertNotNull(row)
            assertContains(row.cells[1].content, status.uppercase())
        }
    }
}

private fun assertEquals(a: String?, b: String?) {
    if (a != b) {
        throw AssertionError("Expected $b but got $a")
    }
}
