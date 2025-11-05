/**
 * Dark Mode Toggle Script
 *
 * Minimal JavaScript for theme switching.
 * Respects system preference and persists user choice.
 */

(function() {
  'use strict';

  const STORAGE_KEY = 'orchestrator-theme';
  const THEMES = {
    LIGHT: 'light',
    DARK: 'dark'
  };

  /**
   * Get the current theme preference
   * Priority: localStorage > system preference > default (light)
   */
  function getPreferredTheme() {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored && (stored === THEMES.LIGHT || stored === THEMES.DARK)) {
      return stored;
    }

    if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) {
      return THEMES.DARK;
    }

    return THEMES.LIGHT;
  }

  /**
   * Apply theme to document
   */
  function applyTheme(theme) {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem(STORAGE_KEY, theme);
  }

  /**
   * Toggle between light and dark themes
   */
  function toggleTheme() {
    const currentTheme = document.documentElement.getAttribute('data-theme') || getPreferredTheme();
    const newTheme = currentTheme === THEMES.LIGHT ? THEMES.DARK : THEMES.LIGHT;
    applyTheme(newTheme);
  }

  /**
   * Initialize theme on page load
   */
  function initTheme() {
    const theme = getPreferredTheme();
    applyTheme(theme);
  }

  /**
   * Listen for system theme changes
   */
  function listenForSystemThemeChanges() {
    if (window.matchMedia) {
      window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', function(e) {
        // Only auto-update if user hasn't set a preference
        if (!localStorage.getItem(STORAGE_KEY)) {
          applyTheme(e.matches ? THEMES.DARK : THEMES.LIGHT);
        }
      });
    }
  }

  /**
   * Setup theme toggle button
   */
  function setupToggleButton() {
    const toggleButton = document.querySelector('.theme-toggle');
    if (toggleButton) {
      toggleButton.addEventListener('click', toggleTheme);
      toggleButton.setAttribute('aria-label', 'Toggle dark mode');
      toggleButton.setAttribute('role', 'button');
    }
  }

  // Initialize immediately to prevent flash
  initTheme();

  // Setup after DOM is ready
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function() {
      setupToggleButton();
      listenForSystemThemeChanges();
    });
  } else {
    setupToggleButton();
    listenForSystemThemeChanges();
  }

  // Expose toggle function globally for testing/debugging
  window.orchestratorTheme = {
    toggle: toggleTheme,
    set: applyTheme,
    get: getPreferredTheme
  };
})();
