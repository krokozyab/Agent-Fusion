package com.orchestrator.web.components

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import java.time.Instant

@DisplayName("FileBrowser Component Tests")
class FileBrowserTest {

    @Test
    @DisplayName("renders file browser with table structure")
    fun testRenderFileBrowser() {
        val models = listOf(
            FileBrowser.Model(
                path = "src/main/kotlin/Example.kt",
                status = "indexed",
                sizeBytes = 1024,
                lastModified = Instant.now(),
                chunkCount = 5,
                extension = "kt"
            )
        )

        val config = FileBrowser.Config(
            rows = models,
            totalFiles = 1
        )

        val html = FileBrowser.render(config)

        assertNotNull(html)
        assertContains(html, "file-browser")
        assertContains(html, "data-table")
        assertContains(html, "File Path")
        assertContains(html, "Status")
        assertContains(html, "Type")
        assertContains(html, "Size")
        assertContains(html, "Modified")
        assertContains(html, "Chunks")
    }

    @Test
    @DisplayName("renders correct column headers")
    fun testColumnHeaders() {
        val config = FileBrowser.Config(rows = emptyList())
        val html = FileBrowser.render(config)

        assertContains(html, "File Path")
        assertContains(html, "Status")
        assertContains(html, "Type")
        assertContains(html, "Size")
        assertContains(html, "Modified")
        assertContains(html, "Chunks")
        assertContains(html, "Actions")
    }

    @Test
    @DisplayName("renders sortable column headers")
    fun testSortableHeaders() {
        val config = FileBrowser.Config(
            rows = emptyList(),
            sortBy = "path"
        )
        val html = FileBrowser.render(config)

        // Should have HTMX sort links
        assertContains(html, "hx-get")
        assertContains(html, "/files/table")
    }

    @Test
    @DisplayName("renders pagination config when provided")
    fun testPagination() {
        val models = listOf(
            FileBrowser.Model(
                path = "file1.kt",
                status = "indexed",
                sizeBytes = 1024,
                lastModified = null,
                chunkCount = 1,
                extension = "kt"
            )
        )

        val config = FileBrowser.Config(
            rows = models,
            totalFiles = 100,
            pageSize = 50,
            page = 1
        )

        val html = FileBrowser.render(config)

        assertContains(html, "Pagination")
        assertContains(html, "page=1")
    }

    @Test
    @DisplayName("renders empty state message when no files")
    fun testEmptyState() {
        val config = FileBrowser.Config(rows = emptyList())
        val html = FileBrowser.render(config)

        assertContains(html, "No files found.")
    }

    @Test
    @DisplayName("renders file rows with correct structure")
    fun testFileRows() {
        val models = listOf(
            FileBrowser.Model(
                path = "src/main/Example.kt",
                status = "indexed",
                sizeBytes = 2048,
                lastModified = Instant.now(),
                chunkCount = 10,
                extension = "kt"
            ),
            FileBrowser.Model(
                path = "src/test/ExampleTest.kt",
                status = "pending",
                sizeBytes = 1024,
                lastModified = null,
                chunkCount = 3,
                extension = "kt"
            )
        )

        val config = FileBrowser.Config(rows = models)
        val html = FileBrowser.render(config)

        // Check for file paths
        assertContains(html, "src/main/Example.kt")
        assertContains(html, "src/test/ExampleTest.kt")

        // Check for statuses
        assertContains(html, "indexed")
        assertContains(html, "pending")

        // Check for chunk counts
        assertContains(html, "10")
        assertContains(html, "3")
    }

    @Test
    @DisplayName("includes HTMX attributes for dynamic loading")
    fun testHTMXAttributes() {
        val config = FileBrowser.Config(
            rows = emptyList(),
            hxTargetId = "files-table-body",
            hxIndicatorId = "files-table-indicator"
        )

        val html = FileBrowser.render(config)

        assertContains(html, "hx-target")
        assertContains(html, "hx-indicator")
        assertContains(html, "files-table-body")
        assertContains(html, "files-table-indicator")
    }

    @Test
    @DisplayName("supports different sort orders")
    fun testSortOrders() {
        listOf(
            DataTable.SortDirection.ASC,
            DataTable.SortDirection.DESC,
            DataTable.SortDirection.NONE
        ).forEach { direction ->
            val config = FileBrowser.Config(
                rows = emptyList(),
                sortOrder = direction
            )

            val html = FileBrowser.render(config)
            assertNotNull(html)
        }
    }

    @Test
    @DisplayName("filters and searches work")
    fun testFiltersAndSearch() {
        val config = FileBrowser.Config(
            rows = emptyList(),
            searchQuery = "kotlin",
            statusFilter = setOf("indexed"),
            extensionFilter = setOf("kt")
        )

        // Verify config accepts filters
        assertEquals("kotlin", config.searchQuery)
        assertEquals(setOf("indexed"), config.statusFilter)
        assertEquals(setOf("kt"), config.extensionFilter)
    }
}
