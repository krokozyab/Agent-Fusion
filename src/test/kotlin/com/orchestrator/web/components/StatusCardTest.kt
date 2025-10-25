package com.orchestrator.web.components

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class StatusCardTest {

    @Test
    fun `renders status card with all required elements`() {
        val config = StatusCard.Config(
            id = "test-card",
            title = "Test Metric",
            value = "100",
            label = "Total Files",
            status = StatusCard.Status.HEALTHY
        )

        val html = StatusCard.render(config)

        // Check for container
        assertContains(html, "id=\"test-card\"")
        assertContains(html, "class=\"status-card status-card--healthy\"")
        assertContains(html, "role=\"region\"")

        // Check for indicator
        assertContains(html, "status-card__indicator")
        assertContains(html, "status-indicator--healthy")

        // Check for content
        assertContains(html, "status-card__value")
        assertContains(html, "100")
        assertContains(html, "status-card__label")
        assertContains(html, "Total Files")
    }

    @Test
    fun `renders card with degraded status`() {
        val config = StatusCard.Config(
            id = "degraded-card",
            title = "Degraded",
            value = "50",
            label = "Pending",
            status = StatusCard.Status.DEGRADED
        )

        val html = StatusCard.render(config)

        assertContains(html, "status-card--degraded")
        assertContains(html, "status-indicator--degraded")
    }

    @Test
    fun `renders card with critical status`() {
        val config = StatusCard.Config(
            id = "critical-card",
            title = "Critical",
            value = "5",
            label = "Failed",
            status = StatusCard.Status.CRITICAL
        )

        val html = StatusCard.render(config)

        assertContains(html, "status-card--critical")
        assertContains(html, "status-indicator--critical")
    }

    @Test
    fun `renders card without subtext when not provided`() {
        val config = StatusCard.Config(
            id = "no-subtext",
            title = "No Subtext",
            value = "42",
            label = "Items",
            status = StatusCard.Status.HEALTHY
        )

        val html = StatusCard.render(config)

        assertFalse(html.contains("status-card__subtext"))
    }

    @Test
    fun `renders card with subtext when provided`() {
        val config = StatusCard.Config(
            id = "with-subtext",
            title = "With Subtext",
            value = "42",
            label = "Items",
            status = StatusCard.Status.HEALTHY,
            subtext = "Last updated 5m ago"
        )

        val html = StatusCard.render(config)

        assertContains(html, "status-card__subtext")
        assertContains(html, "Last updated 5m ago")
    }

    @Test
    fun `includes test ID when provided`() {
        val config = StatusCard.Config(
            id = "test-id",
            title = "Test",
            value = "100",
            label = "Count",
            status = StatusCard.Status.HEALTHY,
            testId = "metric-total-files"
        )

        val html = StatusCard.render(config)

        assertContains(html, "data-testid=\"metric-total-files\"")
    }

    @Test
    fun `does not include test ID when not provided`() {
        val config = StatusCard.Config(
            id = "no-testid",
            title = "Test",
            value = "100",
            label = "Count",
            status = StatusCard.Status.HEALTHY
        )

        val html = StatusCard.render(config)

        assertFalse(html.contains("data-testid"))
    }

    @Test
    fun `includes aria-label when provided`() {
        val config = StatusCard.Config(
            id = "aria-label-test",
            title = "Accessible",
            value = "100",
            label = "Count",
            status = StatusCard.Status.HEALTHY,
            ariaLabel = "Total files in system: 100"
        )

        val html = StatusCard.render(config)

        assertContains(html, "aria-label=\"Total files in system: 100\"")
    }

    @Test
    fun `does not include aria-label when not provided`() {
        val config = StatusCard.Config(
            id = "no-aria",
            title = "Not Accessible",
            value = "100",
            label = "Count",
            status = StatusCard.Status.HEALTHY
        )

        val html = StatusCard.render(config)

        // Should not have explicit aria-label (but will have other aria attributes like role)
        assertFalse(html.contains("aria-label=\""))
    }

    @Test
    fun `indicator has aria-hidden attribute`() {
        val config = StatusCard.Config(
            id = "hidden-indicator",
            title = "Test",
            value = "100",
            label = "Count",
            status = StatusCard.Status.HEALTHY
        )

        val html = StatusCard.render(config)

        assertContains(html, "aria-hidden=\"true\"")
    }

    @Test
    fun `handles special characters in values and labels`() {
        val config = StatusCard.Config(
            id = "special-chars",
            title = "Special",
            value = "1,000+",
            label = "\"Files\" & Chunks",
            status = StatusCard.Status.HEALTHY
        )

        val html = StatusCard.render(config)

        assertContains(html, "1,000+")
        assertContains(html, "&quot;Files&quot; &amp; Chunks")
    }

    @Test
    fun `renders with all status types`() {
        val statusTypes = listOf(
            StatusCard.Status.HEALTHY,
            StatusCard.Status.DEGRADED,
            StatusCard.Status.CRITICAL
        )

        statusTypes.forEach { status ->
            val config = StatusCard.Config(
                id = "status-$status",
                title = status.name,
                value = "10",
                label = status.name,
                status = status
            )

            val html = StatusCard.render(config)
            assertContains(html, "status-card--${status.name.lowercase()}")
            assertContains(html, "status-indicator--${status.name.lowercase()}")
        }
    }

    @Test
    fun `includes role region attribute`() {
        val config = StatusCard.Config(
            id = "role-test",
            title = "Role Test",
            value = "50",
            label = "Metric",
            status = StatusCard.Status.HEALTHY
        )

        val html = StatusCard.render(config)

        assertContains(html, "role=\"region\"")
    }

    @Test
    fun `has proper HTML structure`() {
        val config = StatusCard.Config(
            id = "structure-test",
            title = "Structure",
            value = "99",
            label = "Test Label",
            status = StatusCard.Status.HEALTHY,
            subtext = "Extra info"
        )

        val html = StatusCard.render(config)

        // Verify proper div nesting
        assertContains(html, "<div class=\"status-card")
        assertContains(html, "<div class=\"status-card__indicator")
        assertContains(html, "<div class=\"status-card__content\"")
        assertContains(html, "<div class=\"status-card__value\">99</div>")
        assertContains(html, "<div class=\"status-card__label\">Test Label</div>")
        assertContains(html, "<div class=\"status-card__subtext\">Extra info</div>")
    }
}
