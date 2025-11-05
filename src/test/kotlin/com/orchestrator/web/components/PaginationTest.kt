package com.orchestrator.web.components

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaginationTest {

    @Test
    fun `renders pagination controls with page numbers`() {
        val config = Pagination.Config(
            page = 5,
            pageSize = 25,
            totalCount = 250,
            makePageUrl = Pagination.PageUrlBuilder { page, pageSize -> "/items?page=$page&size=$pageSize" },
            hxTargetId = "items-table",
            hxIndicatorId = "items-indicator"
        )

        val html = Pagination.renderControls(config)

        // Check for pagination navigation
        assertContains(html, "data-table__pagination", ignoreCase = false)
        assertContains(html, "role=\"navigation\"", ignoreCase = false)
        assertContains(html, "aria-label=\"Pagination\"", ignoreCase = false)

        // Check for page numbers
        assertContains(html, ">5<", ignoreCase = false) // Current page
    }

    @Test
    fun `renders First and Last buttons`() {
        val config = Pagination.Config(
            page = 5,
            pageSize = 25,
            totalCount = 250,
            makePageUrl = Pagination.PageUrlBuilder { page, pageSize -> "/items?page=$page&size=$pageSize" },
            hxTargetId = "items-table",
            hxIndicatorId = "items-indicator"
        )

        val html = Pagination.renderControls(config)

        // Check for First/Last buttons
        assertContains(html, ">First<", ignoreCase = false)
        assertContains(html, ">Last<", ignoreCase = false)
        assertContains(html, "aria-label=\"Go to first page\"", ignoreCase = false)
        assertContains(html, "aria-label=\"Go to last page\"", ignoreCase = false)
    }

    @Test
    fun `renders Previous and Next buttons`() {
        val config = Pagination.Config(
            page = 5,
            pageSize = 25,
            totalCount = 250,
            makePageUrl = Pagination.PageUrlBuilder { page, pageSize -> "/items?page=$page&size=$pageSize" },
            hxTargetId = "items-table",
            hxIndicatorId = "items-indicator"
        )

        val html = Pagination.renderControls(config)

        // Check for Previous/Next buttons
        assertContains(html, ">Previous<", ignoreCase = false)
        assertContains(html, ">Next<", ignoreCase = false)
        assertContains(html, "aria-label=\"Go to previous page\"", ignoreCase = false)
        assertContains(html, "aria-label=\"Go to next page\"", ignoreCase = false)
    }

    @Test
    fun `disables First and Previous on first page`() {
        val config = Pagination.Config(
            page = 1,
            pageSize = 25,
            totalCount = 250,
            makePageUrl = Pagination.PageUrlBuilder { page, pageSize -> "/items?page=$page&size=$pageSize" },
            hxTargetId = "items-table",
            hxIndicatorId = "items-indicator"
        )

        val html = Pagination.renderControls(config)

        // Count disabled attributes for First/Previous buttons
        val disabledCount = Regex("disabled=\"disabled\"").findAll(html).count()
        assertTrue(disabledCount >= 2, "Should have at least 2 disabled buttons (First and Previous)")
    }

    @Test
    fun `disables Last and Next on last page`() {
        val config = Pagination.Config(
            page = 10, // Last page
            pageSize = 25,
            totalCount = 250,
            makePageUrl = Pagination.PageUrlBuilder { page, pageSize -> "/items?page=$page&size=$pageSize" },
            hxTargetId = "items-table",
            hxIndicatorId = "items-indicator"
        )

        val html = Pagination.renderControls(config)

        // Count disabled attributes for Last/Next buttons
        val disabledCount = Regex("disabled=\"disabled\"").findAll(html).count()
        assertTrue(disabledCount >= 2, "Should have at least 2 disabled buttons (Last and Next)")
    }

    @Test
    fun `highlights current page`() {
        val config = Pagination.Config(
            page = 5,
            pageSize = 25,
            totalCount = 250,
            makePageUrl = Pagination.PageUrlBuilder { page, pageSize -> "/items?page=$page&size=$pageSize" },
            hxTargetId = "items-table",
            hxIndicatorId = "items-indicator"
        )

        val html = Pagination.renderControls(config)

        // Check for active state on current page
        assertContains(html, "data-state=\"active\"", ignoreCase = false)
        assertContains(html, "aria-pressed=\"true\"", ignoreCase = false)
    }

    @Test
    fun `includes HTMX attributes`() {
        val config = Pagination.Config(
            page = 5,
            pageSize = 25,
            totalCount = 250,
            makePageUrl = Pagination.PageUrlBuilder { page, pageSize -> "/items?page=$page&size=$pageSize" },
            hxTargetId = "items-table",
            hxIndicatorId = "items-indicator"
        )

        val html = Pagination.renderControls(config)

        // Check for HTMX attributes
        assertContains(html, "hx-get=", ignoreCase = false)
        assertContains(html, "hx-target=\"#items-table\"", ignoreCase = false)
        assertContains(html, "hx-swap=\"outerHTML\"", ignoreCase = false)
        assertContains(html, "hx-indicator=\"#items-indicator\"", ignoreCase = false)
    }

    @Test
    fun `renders per-page selector`() {
        val config = Pagination.Config(
            page = 1,
            pageSize = 25,
            totalCount = 250,
            perPageOptions = listOf(10, 25, 50, 100),
            makePageUrl = Pagination.PageUrlBuilder { page, pageSize -> "/items?page=$page&size=$pageSize" },
            hxTargetId = "items-table",
            hxIndicatorId = "items-indicator"
        )

        val html = Pagination.renderControls(config)

        // Check for per-page selector
        assertContains(html, "data-table__page-size", ignoreCase = false)
        assertContains(html, "Rows per page:", ignoreCase = false)

        // Check for all page size options
        assertContains(html, ">10<", ignoreCase = false)
        assertContains(html, ">25<", ignoreCase = false)
        assertContains(html, ">50<", ignoreCase = false)
        assertContains(html, ">100<", ignoreCase = false)
    }

    @Test
    fun `shows correct page summary`() {
        val config = Pagination.Config(
            page = 5,
            pageSize = 25,
            totalCount = 250,
            makePageUrl = Pagination.PageUrlBuilder { page, pageSize -> "/items?page=$page&size=$pageSize" },
            hxTargetId = "items-table",
            hxIndicatorId = "items-indicator"
        )

        val html = Pagination.renderControls(config)

        // Check for summary text
        assertContains(html, "Showing page 5 of 10", ignoreCase = false)
        assertContains(html, "(250 items)", ignoreCase = false)
    }

    @Test
    fun `calculates total pages correctly`() {
        // Test exact division
        val config1 = Pagination.Config(
            page = 1,
            pageSize = 25,
            totalCount = 100,
            makePageUrl = Pagination.PageUrlBuilder { page, pageSize -> "/items?page=$page&size=$pageSize" },
            hxTargetId = "items-table",
            hxIndicatorId = "items-indicator"
        )
        assertEquals(4, config1.totalPages)

        // Test with remainder
        val config2 = Pagination.Config(
            page = 1,
            pageSize = 25,
            totalCount = 101,
            makePageUrl = Pagination.PageUrlBuilder { page, pageSize -> "/items?page=$page&size=$pageSize" },
            hxTargetId = "items-table",
            hxIndicatorId = "items-indicator"
        )
        assertEquals(5, config2.totalPages)

        // Test empty result
        val config3 = Pagination.Config(
            page = 1,
            pageSize = 25,
            totalCount = 0,
            makePageUrl = Pagination.PageUrlBuilder { page, pageSize -> "/items?page=$page&size=$pageSize" },
            hxTargetId = "items-table",
            hxIndicatorId = "items-indicator"
        )
        assertEquals(1, config3.totalPages)
    }

    @Test
    fun `shows ellipsis for many pages`() {
        val config = Pagination.Config(
            page = 50,
            pageSize = 10,
            totalCount = 1000, // 100 total pages
            windowSize = 5,
            makePageUrl = Pagination.PageUrlBuilder { page, pageSize -> "/items?page=$page&size=$pageSize" },
            hxTargetId = "items-table",
            hxIndicatorId = "items-indicator"
        )

        val html = Pagination.renderControls(config)

        // Check for ellipsis
        assertContains(html, "data-table__page-ellipsis", ignoreCase = false)
        assertContains(html, "...", ignoreCase = false)
        assertContains(html, "aria-hidden=\"true\"", ignoreCase = false)
    }

    @Test
    fun `shows first and last page with ellipsis`() {
        val config = Pagination.Config(
            page = 50,
            pageSize = 10,
            totalCount = 1000, // 100 total pages
            windowSize = 5,
            makePageUrl = Pagination.PageUrlBuilder { page, pageSize -> "/items?page=$page&size=$pageSize" },
            hxTargetId = "items-table",
            hxIndicatorId = "items-indicator"
        )

        val html = Pagination.renderControls(config)

        // Should show page 1
        assertContains(html, "Page 1 of 100", ignoreCase = false)

        // Should show page 100
        assertContains(html, "Page 100 of 100", ignoreCase = false)

        // Should have ellipsis between
        val ellipsisCount = Regex("data-table__page-ellipsis").findAll(html).count()
        assertTrue(ellipsisCount >= 1, "Should have at least one ellipsis")
    }

    @Test
    fun `per-page selector marks current size as active`() {
        val config = Pagination.Config(
            page = 1,
            pageSize = 50,
            totalCount = 250,
            perPageOptions = listOf(10, 25, 50, 100),
            makePageUrl = Pagination.PageUrlBuilder { page, pageSize -> "/items?page=$page&size=$pageSize" },
            hxTargetId = "items-table",
            hxIndicatorId = "items-indicator"
        )

        val html = Pagination.renderControls(config)

        // Check that current page size (50) is marked as active
        assertTrue(html.contains("Show 50 rows per page"), "Should have aria-label for page size")
    }

    @Test
    fun `per-page selector resets to page 1`() {
        val config = Pagination.Config(
            page = 5,
            pageSize = 25,
            totalCount = 250,
            perPageOptions = listOf(10, 25, 50),
            makePageUrl = Pagination.PageUrlBuilder { page, pageSize -> "/items?page=$page&size=$pageSize" },
            hxTargetId = "items-table",
            hxIndicatorId = "items-indicator"
        )

        val html = Pagination.renderControls(config)

        // Check that per-page selector URLs go to page 1 (note: HTML escapes & as &amp;)
        assertContains(html, "/items?page=1&amp;size=10", ignoreCase = false)
        assertContains(html, "/items?page=1&amp;size=50", ignoreCase = false)
    }

    @Test
    fun `uses custom window size`() {
        val config = Pagination.Config(
            page = 10,
            pageSize = 10,
            totalCount = 1000, // 100 pages
            windowSize = 3, // Show only 3 page numbers
            makePageUrl = Pagination.PageUrlBuilder { page, pageSize -> "/items?page=$page&size=$pageSize" },
            hxTargetId = "items-table",
            hxIndicatorId = "items-indicator"
        )

        val html = Pagination.renderControls(config)

        // With window size 3 centered on page 10, should show pages 9, 10, 11
        // (plus page 1 and 100 always shown with ellipsis)
        assertContains(html, "Page 9 of 100", ignoreCase = false)
        assertContains(html, "Page 10 of 100", ignoreCase = false)
        assertContains(html, "Page 11 of 100", ignoreCase = false)
    }

    @Test
    fun `handles single page correctly`() {
        val config = Pagination.Config(
            page = 1,
            pageSize = 50,
            totalCount = 25, // Only 1 page
            makePageUrl = Pagination.PageUrlBuilder { page, pageSize -> "/items?page=$page&size=$pageSize" },
            hxTargetId = "items-table",
            hxIndicatorId = "items-indicator"
        )

        val html = Pagination.renderControls(config)

        // All navigation buttons should be disabled
        val disabledCount = Regex("disabled=\"disabled\"").findAll(html).count()
        assertTrue(disabledCount >= 4, "All navigation buttons should be disabled for single page")

        // Should show page 1 of 1
        assertContains(html, "page 1 of 1", ignoreCase = false)
    }

    @Test
    fun `sets correct aria labels for page numbers`() {
        val config = Pagination.Config(
            page = 5,
            pageSize = 25,
            totalCount = 250,
            makePageUrl = Pagination.PageUrlBuilder { page, pageSize -> "/items?page=$page&size=$pageSize" },
            hxTargetId = "items-table",
            hxIndicatorId = "items-indicator"
        )

        val html = Pagination.renderControls(config)

        // Check for proper aria-labels
        assertContains(html, "aria-label=\"Page", ignoreCase = false)
        assertContains(html, "of 10\"", ignoreCase = false)
    }

    @Test
    fun `applies correct tabindex`() {
        val config = Pagination.Config(
            page = 1,
            pageSize = 25,
            totalCount = 100,
            makePageUrl = Pagination.PageUrlBuilder { page, pageSize -> "/items?page=$page&size=$pageSize" },
            hxTargetId = "items-table",
            hxIndicatorId = "items-indicator"
        )

        val html = Pagination.renderControls(config)

        // Disabled buttons should have tabindex="-1"
        assertTrue(html.contains("tabindex=\"-1\""), "Disabled buttons should have tabindex -1")

        // Active buttons should have tabindex="0"
        assertTrue(html.contains("tabindex=\"0\""), "Active buttons should have tabindex 0")
    }
}
