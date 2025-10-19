/**
 * Navigation Component JavaScript
 *
 * Handles mobile menu toggle functionality and navigation interactions.
 * Works seamlessly with HTMX for SPA-like navigation experience.
 */

(function() {
  'use strict';

  /**
   * Toggle mobile navigation menu
   */
  function toggleMobileMenu() {
    const menu = document.getElementById('mobile-menu');
    const toggle = document.querySelector('.main-header__menu-toggle');

    if (!menu || !toggle) {
      console.warn('Mobile menu elements not found');
      return;
    }

    const isExpanded = toggle.getAttribute('aria-expanded') === 'true';
    const newState = !isExpanded;

    // Update ARIA states
    toggle.setAttribute('aria-expanded', String(newState));
    menu.setAttribute('aria-hidden', String(!newState));

    // Toggle menu visibility
    if (newState) {
      menu.classList.add('mobile-nav--open');
      document.body.style.overflow = 'hidden'; // Prevent background scrolling
    } else {
      menu.classList.remove('mobile-nav--open');
      document.body.style.overflow = ''; // Restore scrolling
    }
  }

  /**
   * Close mobile menu
   */
  function closeMobileMenu() {
    const menu = document.getElementById('mobile-menu');
    const toggle = document.querySelector('.main-header__menu-toggle');

    if (!menu || !toggle) return;

    toggle.setAttribute('aria-expanded', 'false');
    menu.setAttribute('aria-hidden', 'true');
    menu.classList.remove('mobile-nav--open');
    document.body.style.overflow = '';
  }

  /**
   * Initialize navigation event listeners
   */
  function initNavigation() {
    // Mobile menu toggle button
    const toggleButton = document.querySelector('.main-header__menu-toggle');
    if (toggleButton) {
      toggleButton.addEventListener('click', toggleMobileMenu);
    }

    // Close mobile menu when clicking on a link
    const mobileLinks = document.querySelectorAll('.mobile-nav__link');
    mobileLinks.forEach(function(link) {
      link.addEventListener('click', function() {
        closeMobileMenu();
      });
    });

    // Close mobile menu on Escape key
    document.addEventListener('keydown', function(e) {
      if (e.key === 'Escape') {
        const toggle = document.querySelector('.main-header__menu-toggle');
        if (toggle && toggle.getAttribute('aria-expanded') === 'true') {
          closeMobileMenu();
          toggle.focus(); // Return focus to toggle button
        }
      }
    });

    // Close mobile menu when clicking outside
    document.addEventListener('click', function(e) {
      const toggle = document.querySelector('.main-header__menu-toggle');
      const menu = document.getElementById('mobile-menu');

      if (!toggle || !menu) return;

      const isExpanded = toggle.getAttribute('aria-expanded') === 'true';
      const clickedInsideMenu = menu.contains(e.target);
      const clickedToggle = toggle.contains(e.target);

      if (isExpanded && !clickedInsideMenu && !clickedToggle) {
        closeMobileMenu();
      }
    });

    // Handle window resize to close mobile menu if switching to desktop
    let resizeTimer;
    window.addEventListener('resize', function() {
      clearTimeout(resizeTimer);
      resizeTimer = setTimeout(function() {
        if (window.innerWidth > 768) {
          closeMobileMenu();
        }
      }, 250);
    });
  }

  /**
   * Handle active navigation state after HTMX navigation
   */
  function updateActiveNavigation() {
    const currentPath = window.location.pathname;

    // Update desktop nav
    document.querySelectorAll('.main-nav__link').forEach(function(link) {
      const href = link.getAttribute('href');
      const isActive = href === currentPath ||
                       (href !== '/' && currentPath.startsWith(href));

      if (isActive) {
        link.classList.add('main-nav__link--active');
        link.setAttribute('aria-current', 'page');
      } else {
        link.classList.remove('main-nav__link--active');
        link.removeAttribute('aria-current');
      }
    });

    // Update mobile nav
    document.querySelectorAll('.mobile-nav__link').forEach(function(link) {
      const href = link.getAttribute('href');
      const isActive = href === currentPath ||
                       (href !== '/' && currentPath.startsWith(href));

      if (isActive) {
        link.classList.add('mobile-nav__link--active');
        link.setAttribute('aria-current', 'page');
      } else {
        link.classList.remove('mobile-nav__link--active');
        link.removeAttribute('aria-current');
      }
    });
  }

  /**
   * Initialize on DOM ready
   */
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function() {
      initNavigation();
      updateActiveNavigation();
    });
  } else {
    initNavigation();
    updateActiveNavigation();
  }

  /**
   * Update navigation after HTMX page swaps
   */
  document.body.addEventListener('htmx:afterSwap', function() {
    updateActiveNavigation();
  });

  // Expose toggle function globally for inline onclick handlers
  window.toggleMobileMenu = toggleMobileMenu;
})();
