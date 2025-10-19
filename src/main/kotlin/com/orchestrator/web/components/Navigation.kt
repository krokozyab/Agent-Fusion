package com.orchestrator.web.components

import com.orchestrator.web.rendering.Fragment
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.header
import kotlinx.html.li
import kotlinx.html.nav
import kotlinx.html.span
import kotlinx.html.ul

/**
 * Navigation component for the Orchestrator dashboard.
 *
 * Provides a responsive navigation bar with:
 * - Main navigation links
 * - Active state highlighting
 * - Mobile hamburger menu
 * - HTMX boost for SPA-like navigation
 * - Full accessibility support
 */
object Navigation {

    /**
     * Navigation link configuration
     */
    data class Link(
        val label: String,
        val href: String,
        val active: Boolean = false,
        val ariaLabel: String? = null,
        val icon: String? = null
    )

    /**
     * Navigation configuration
     */
    data class Config(
        val links: List<Link>,
        val title: String = "Orchestrator",
        val titleHref: String = "/",
        val mobileMenuId: String = "mobile-menu",
        val enableHtmxBoost: Boolean = true
    )

    /**
     * Render the complete navigation component as HTML string
     */
    fun render(config: Config): String = Fragment.render {
        navigationBar(config)
    }

    /**
     * Render navigation bar into existing FlowContent context
     */
    fun FlowContent.navigationBar(config: Config) {
        header(classes = "main-header") {
            attributes["role"] = "banner"

            div(classes = "container") {
                div(classes = "main-header__inner") {
                    // Logo/Title
                    brandSection(config)

                    // Desktop Navigation
                    desktopNav(config)

                    // Mobile Menu Toggle
                    mobileMenuToggle(config.mobileMenuId)
                }
            }

            // Mobile Navigation (hidden by default)
            mobileNav(config)
        }
    }

    private fun FlowContent.brandSection(config: Config) {
        div(classes = "main-header__brand") {
            a(href = config.titleHref, classes = "main-header__logo") {
                if (config.enableHtmxBoost) {
                    attributes["hx-boost"] = "true"
                }
                attributes["aria-label"] = "Home"
                span(classes = "main-header__logo-text") {
                    +config.title
                }
            }
        }
    }

    private fun FlowContent.desktopNav(config: Config) {
        nav(classes = "main-nav hide-mobile") {
            attributes["role"] = "navigation"
            attributes["aria-label"] = "Main navigation"

            ul(classes = "main-nav__list") {
                config.links.forEach { link ->
                    navItem(link, config.enableHtmxBoost)
                }
            }
        }
    }

    private fun FlowContent.mobileMenuToggle(mobileMenuId: String) {
        button(classes = "main-header__menu-toggle show-mobile") {
            attributes["type"] = "button"
            attributes["aria-label"] = "Toggle navigation menu"
            attributes["aria-expanded"] = "false"
            attributes["aria-controls"] = mobileMenuId
            attributes["onclick"] = "toggleMobileMenu()"

            // Hamburger icon
            span(classes = "hamburger") {
                attributes["aria-hidden"] = "true"
                span(classes = "hamburger__line")
                span(classes = "hamburger__line")
                span(classes = "hamburger__line")
            }
        }
    }

    private fun FlowContent.mobileNav(config: Config) {
        nav(classes = "mobile-nav show-mobile") {
            attributes["id"] = config.mobileMenuId
            attributes["role"] = "navigation"
            attributes["aria-label"] = "Mobile navigation"
            attributes["aria-hidden"] = "true"

            ul(classes = "mobile-nav__list") {
                config.links.forEach { link ->
                    navItem(link, config.enableHtmxBoost, mobile = true)
                }
            }
        }
    }

    private fun kotlinx.html.UL.navItem(
        link: Link,
        htmxBoost: Boolean,
        mobile: Boolean = false
    ) {
        li(classes = if (mobile) "mobile-nav__item" else "main-nav__item") {
            val linkClasses = buildString {
                append(if (mobile) "mobile-nav__link" else "main-nav__link")
                if (link.active) {
                    append(" ")
                    append(if (mobile) "mobile-nav__link--active" else "main-nav__link--active")
                }
            }

            a(href = link.href, classes = linkClasses) {
                if (htmxBoost) {
                    attributes["hx-boost"] = "true"
                }
                if (link.active) {
                    attributes["aria-current"] = "page"
                }
                link.ariaLabel?.let {
                    attributes["aria-label"] = it
                }

                link.icon?.let { icon ->
                    span(classes = "nav-link__icon") {
                        attributes["aria-hidden"] = "true"
                        +icon
                    }
                }

                span(classes = "nav-link__text") {
                    +link.label
                }
            }
        }
    }

    /**
     * JavaScript for mobile menu toggle (inline in the component)
     */
    const val MOBILE_MENU_SCRIPT = """
function toggleMobileMenu() {
    const menu = document.getElementById('mobile-menu');
    const toggle = document.querySelector('.main-header__menu-toggle');
    const isExpanded = toggle.getAttribute('aria-expanded') === 'true';

    toggle.setAttribute('aria-expanded', !isExpanded);
    menu.setAttribute('aria-hidden', isExpanded);

    if (!isExpanded) {
        menu.classList.add('mobile-nav--open');
        document.body.style.overflow = 'hidden';
    } else {
        menu.classList.remove('mobile-nav--open');
        document.body.style.overflow = '';
    }
}

// Close mobile menu when clicking on a link
document.addEventListener('DOMContentLoaded', function() {
    const mobileLinks = document.querySelectorAll('.mobile-nav__link');
    mobileLinks.forEach(function(link) {
        link.addEventListener('click', function() {
            const menu = document.getElementById('mobile-menu');
            const toggle = document.querySelector('.main-header__menu-toggle');
            if (menu && toggle) {
                toggle.setAttribute('aria-expanded', 'false');
                menu.setAttribute('aria-hidden', 'true');
                menu.classList.remove('mobile-nav--open');
                document.body.style.overflow = '';
            }
        });
    });

    // Close on escape key
    document.addEventListener('keydown', function(e) {
        if (e.key === 'Escape') {
            const toggle = document.querySelector('.main-header__menu-toggle');
            const menu = document.getElementById('mobile-menu');
            if (toggle && menu && toggle.getAttribute('aria-expanded') === 'true') {
                toggle.setAttribute('aria-expanded', 'false');
                menu.setAttribute('aria-hidden', 'true');
                menu.classList.remove('mobile-nav--open');
                document.body.style.overflow = '';
            }
        }
    });
});
"""
}
