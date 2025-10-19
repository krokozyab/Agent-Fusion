package com.orchestrator.web.components

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationTest {

    @Test
    fun `navigation renders with basic links`() {
        val config = Navigation.Config(
            links = listOf(
                Navigation.Link(label = "Home", href = "/"),
                Navigation.Link(label = "Tasks", href = "/tasks"),
                Navigation.Link(label = "Metrics", href = "/metrics")
            )
        )

        val html = Navigation.render(config)

        // Check for header
        assertContains(html, "<header", ignoreCase = false)
        assertContains(html, "role=\"banner\"", ignoreCase = false)
        assertContains(html, "class=\"main-header\"", ignoreCase = false)

        // Check for brand/logo
        assertContains(html, "Orchestrator", ignoreCase = false)
        assertContains(html, "main-header__logo", ignoreCase = false)

        // Check for navigation links
        assertContains(html, "Home", ignoreCase = false)
        assertContains(html, "Tasks", ignoreCase = false)
        assertContains(html, "Metrics", ignoreCase = false)
        assertContains(html, "href=\"/\"", ignoreCase = false)
        assertContains(html, "href=\"/tasks\"", ignoreCase = false)
        assertContains(html, "href=\"/metrics\"", ignoreCase = false)
    }

    @Test
    fun `navigation marks active link correctly`() {
        val config = Navigation.Config(
            links = listOf(
                Navigation.Link(label = "Home", href = "/", active = false),
                Navigation.Link(label = "Tasks", href = "/tasks", active = true),
                Navigation.Link(label = "Metrics", href = "/metrics", active = false)
            )
        )

        val html = Navigation.render(config)

        // Check for active state class
        assertContains(html, "main-nav__link--active", ignoreCase = false)

        // Check for aria-current on active link
        assertContains(html, "aria-current=\"page\"", ignoreCase = false)
    }

    @Test
    fun `navigation includes mobile menu toggle`() {
        val config = Navigation.Config(
            links = listOf(
                Navigation.Link(label = "Home", href = "/")
            )
        )

        val html = Navigation.render(config)

        // Check for mobile menu toggle button
        assertContains(html, "main-header__menu-toggle", ignoreCase = false)
        assertContains(html, "aria-label=\"Toggle navigation menu\"", ignoreCase = false)
        assertContains(html, "aria-expanded=\"false\"", ignoreCase = false)
        assertContains(html, "aria-controls=\"mobile-menu\"", ignoreCase = false)

        // Check for hamburger icon
        assertContains(html, "hamburger", ignoreCase = false)
        assertContains(html, "hamburger__line", ignoreCase = false)
    }

    @Test
    fun `navigation includes mobile menu`() {
        val config = Navigation.Config(
            links = listOf(
                Navigation.Link(label = "Home", href = "/"),
                Navigation.Link(label = "Tasks", href = "/tasks")
            )
        )

        val html = Navigation.render(config)

        // Check for mobile nav
        assertContains(html, "mobile-nav", ignoreCase = false)
        assertContains(html, "id=\"mobile-menu\"", ignoreCase = false)
        assertContains(html, "aria-hidden=\"true\"", ignoreCase = false)

        // Mobile nav should have same links
        val mobileNavMatches = Regex("mobile-nav__link").findAll(html).count()
        assertTrue(mobileNavMatches >= 2, "Should have at least 2 mobile nav links")
    }

    @Test
    fun `navigation adds HTMX boost by default`() {
        val config = Navigation.Config(
            links = listOf(
                Navigation.Link(label = "Home", href = "/")
            )
        )

        val html = Navigation.render(config)

        // Check for hx-boost attribute
        assertContains(html, "hx-boost=\"true\"", ignoreCase = false)
    }

    @Test
    fun `navigation can disable HTMX boost`() {
        val config = Navigation.Config(
            links = listOf(
                Navigation.Link(label = "Home", href = "/")
            ),
            enableHtmxBoost = false
        )

        val html = Navigation.render(config)

        // Should not contain hx-boost
        assertFalse(html.contains("hx-boost=\"true\""), "Should not contain hx-boost when disabled")
    }

    @Test
    fun `navigation supports custom title and href`() {
        val config = Navigation.Config(
            links = listOf(),
            title = "Custom App",
            titleHref = "/custom"
        )

        val html = Navigation.render(config)

        assertContains(html, "Custom App", ignoreCase = false)
        assertContains(html, "href=\"/custom\"", ignoreCase = false)
    }

    @Test
    fun `navigation supports custom mobile menu ID`() {
        val config = Navigation.Config(
            links = listOf(),
            mobileMenuId = "custom-menu-id"
        )

        val html = Navigation.render(config)

        assertContains(html, "id=\"custom-menu-id\"", ignoreCase = false)
        assertContains(html, "aria-controls=\"custom-menu-id\"", ignoreCase = false)
    }

    @Test
    fun `navigation includes proper ARIA labels`() {
        val config = Navigation.Config(
            links = listOf(
                Navigation.Link(
                    label = "Tasks",
                    href = "/tasks",
                    ariaLabel = "View all tasks"
                )
            )
        )

        val html = Navigation.render(config)

        // Check for navigation role and label
        assertContains(html, "role=\"navigation\"", ignoreCase = false)
        assertContains(html, "aria-label=\"Main navigation\"", ignoreCase = false)
        assertContains(html, "aria-label=\"Mobile navigation\"", ignoreCase = false)

        // Check for custom aria label on link
        assertContains(html, "aria-label=\"View all tasks\"", ignoreCase = false)
    }

    @Test
    fun `navigation hides desktop nav on mobile`() {
        val config = Navigation.Config(
            links = listOf(
                Navigation.Link(label = "Home", href = "/")
            )
        )

        val html = Navigation.render(config)

        // Desktop nav should have hide-mobile class
        val desktopNavRegex = Regex("class=\"main-nav[^\"]*hide-mobile")
        assertTrue(desktopNavRegex.containsMatchIn(html), "Desktop nav should have hide-mobile class")

        // Mobile nav should have show-mobile class
        val mobileNavRegex = Regex("class=\"mobile-nav[^\"]*show-mobile")
        assertTrue(mobileNavRegex.containsMatchIn(html), "Mobile nav should have show-mobile class")
    }

    @Test
    fun `navigation link supports icons`() {
        val config = Navigation.Config(
            links = listOf(
                Navigation.Link(
                    label = "Home",
                    href = "/",
                    icon = "🏠"
                )
            )
        )

        val html = Navigation.render(config)

        // Check for icon span
        assertContains(html, "nav-link__icon", ignoreCase = false)
        assertContains(html, "🏠", ignoreCase = false)
        assertContains(html, "aria-hidden=\"true\"", ignoreCase = false) // Icon should be hidden from screen readers
    }

    @Test
    fun `breadcrumbs renders simple trail`() {
        val config = Breadcrumbs.Config(
            crumbs = listOf(
                Breadcrumbs.Crumb(label = "Home", href = "/"),
                Breadcrumbs.Crumb(label = "Tasks", href = "/tasks"),
                Breadcrumbs.Crumb(label = "Details", current = true)
            )
        )

        val html = Breadcrumbs.render(config)

        // Check for nav element
        assertContains(html, "<nav", ignoreCase = false)
        assertContains(html, "class=\"breadcrumbs\"", ignoreCase = false)
        assertContains(html, "aria-label=\"Breadcrumb navigation\"", ignoreCase = false)

        // Check for ordered list
        assertContains(html, "<ol", ignoreCase = false)

        // Check for all crumb labels
        assertContains(html, "Home", ignoreCase = false)
        assertContains(html, "Tasks", ignoreCase = false)
        assertContains(html, "Details", ignoreCase = false)
    }

    @Test
    fun `breadcrumbs includes structured data`() {
        val config = Breadcrumbs.Config(
            crumbs = listOf(
                Breadcrumbs.Crumb(label = "Home", href = "/"),
                Breadcrumbs.Crumb(label = "Current", current = true)
            )
        )

        val html = Breadcrumbs.render(config)

        // Check for schema.org structured data
        assertContains(html, "itemscope", ignoreCase = false)
        assertContains(html, "https://schema.org/BreadcrumbList", ignoreCase = false)
        assertContains(html, "https://schema.org/ListItem", ignoreCase = false)
        assertContains(html, "itemprop=\"itemListElement\"", ignoreCase = false)
        assertContains(html, "itemprop=\"position\"", ignoreCase = false)
        assertContains(html, "itemprop=\"name\"", ignoreCase = false)
    }

    @Test
    fun `breadcrumbs marks current page correctly`() {
        val config = Breadcrumbs.Config(
            crumbs = listOf(
                Breadcrumbs.Crumb(label = "Home", href = "/"),
                Breadcrumbs.Crumb(label = "Current", current = true)
            )
        )

        val html = Breadcrumbs.render(config)

        // Check for current page span
        assertContains(html, "breadcrumbs__current", ignoreCase = false)
        assertContains(html, "aria-current=\"page\"", ignoreCase = false)
    }

    @Test
    fun `breadcrumbs auto-marks last item as current`() {
        val config = Breadcrumbs.Config(
            crumbs = listOf(
                Breadcrumbs.Crumb(label = "Home", href = "/"),
                Breadcrumbs.Crumb(label = "Last", href = "/last")
            )
        )

        val html = Breadcrumbs.render(config)

        // Should still mark last as current even without explicit flag
        assertContains(html, "aria-current=\"page\"", ignoreCase = false)
    }

    @Test
    fun `breadcrumbs includes separators`() {
        val config = Breadcrumbs.Config(
            crumbs = listOf(
                Breadcrumbs.Crumb(label = "Home", href = "/"),
                Breadcrumbs.Crumb(label = "Tasks", href = "/tasks"),
                Breadcrumbs.Crumb(label = "Details", current = true)
            ),
            separator = ">"
        )

        val html = Breadcrumbs.render(config)

        // Check for separator
        assertContains(html, "breadcrumbs__separator", ignoreCase = false)
        assertContains(html, ">", ignoreCase = false)

        // Separator should be hidden from screen readers
        val separatorRegex = Regex("breadcrumbs__separator[^>]*aria-hidden=\"true\"")
        assertTrue(separatorRegex.containsMatchIn(html), "Separator should have aria-hidden")
    }

    @Test
    fun `breadcrumbs requires at least one crumb`() {
        assertThrows<IllegalArgumentException> {
            Breadcrumbs.Config(crumbs = emptyList())
        }
    }

    @Test
    fun `breadcrumbs prevents current crumb from having href`() {
        assertThrows<IllegalArgumentException> {
            Breadcrumbs.Crumb(label = "Current", href = "/current", current = true)
        }
    }

    @Test
    fun `breadcrumbs only allows one current crumb`() {
        assertThrows<IllegalArgumentException> {
            Breadcrumbs.Config(
                crumbs = listOf(
                    Breadcrumbs.Crumb(label = "First", current = true),
                    Breadcrumbs.Crumb(label = "Second", current = true)
                )
            )
        }
    }

    @Test
    fun `breadcrumbs simple helper creates valid config`() {
        val config = Breadcrumbs.simple("Home", "Tasks", "Details")

        val html = Breadcrumbs.render(config)

        assertContains(html, "Home", ignoreCase = false)
        assertContains(html, "Tasks", ignoreCase = false)
        assertContains(html, "Details", ignoreCase = false)
        assertContains(html, "aria-current=\"page\"", ignoreCase = false)
    }

    @Test
    fun `breadcrumbs trail helper creates config with hrefs`() {
        val config = Breadcrumbs.trail(
            "Home" to "/",
            "Tasks" to "/tasks",
            "Details" to null
        )

        val html = Breadcrumbs.render(config)

        assertContains(html, "href=\"/\"", ignoreCase = false)
        assertContains(html, "href=\"/tasks\"", ignoreCase = false)
        assertContains(html, "aria-current=\"page\"", ignoreCase = false)
    }

    @Test
    fun `breadcrumbs supports HTMX boost`() {
        val config = Breadcrumbs.Config(
            crumbs = listOf(
                Breadcrumbs.Crumb(label = "Home", href = "/"),
                Breadcrumbs.Crumb(label = "Current", current = true)
            ),
            enableHtmxBoost = true
        )

        val html = Breadcrumbs.render(config)

        assertContains(html, "hx-boost=\"true\"", ignoreCase = false)
    }

    @Test
    fun `breadcrumbs can disable HTMX boost`() {
        val config = Breadcrumbs.Config(
            crumbs = listOf(
                Breadcrumbs.Crumb(label = "Home", href = "/"),
                Breadcrumbs.Crumb(label = "Current", current = true)
            ),
            enableHtmxBoost = false
        )

        val html = Breadcrumbs.render(config)

        assertFalse(html.contains("hx-boost"), "Should not contain hx-boost when disabled")
    }
}
