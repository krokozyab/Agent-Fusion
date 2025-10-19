/**
 * Pagination Component JavaScript
 *
 * Handles keyboard navigation for pagination controls.
 * Supports arrow keys (←→) for navigating between pages.
 */

(function() {
  'use strict';

  /**
   * Handle keyboard navigation for pagination
   */
  function initPaginationKeyboardNavigation() {
    document.addEventListener('keydown', function(e) {
      // Only handle arrow keys when pagination is focused or when no input is focused
      const activeElement = document.activeElement;
      const isInputFocused = activeElement && (
        activeElement.tagName === 'INPUT' ||
        activeElement.tagName === 'TEXTAREA' ||
        activeElement.isContentEditable
      );

      // Don't handle arrow keys if an input is focused
      if (isInputFocused) {
        return;
      }

      const pagination = document.querySelector('.data-table__pagination');
      if (!pagination) {
        return;
      }

      // Check if focus is within pagination
      const isPaginationFocused = pagination.contains(activeElement);

      // Left arrow or Right arrow
      if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
        // Only handle if pagination is focused or if we're on a pagination element
        if (!isPaginationFocused && !activeElement.closest('.data-table__footer')) {
          return;
        }

        e.preventDefault();

        if (e.key === 'ArrowLeft') {
          // Navigate to previous page
          const prevButton = pagination.querySelector('.data-table__page-button[aria-label*="previous" i]');
          if (prevButton && !prevButton.disabled) {
            prevButton.click();
          }
        } else if (e.key === 'ArrowRight') {
          // Navigate to next page
          const nextButton = pagination.querySelector('.data-table__page-button[aria-label*="next" i]');
          if (nextButton && !nextButton.disabled) {
            nextButton.click();
          }
        }
      }

      // Home key - go to first page
      if (e.key === 'Home' && isPaginationFocused) {
        e.preventDefault();
        const firstButton = pagination.querySelector('.data-table__page-button[aria-label*="first" i]');
        if (firstButton && !firstButton.disabled) {
          firstButton.click();
        }
      }

      // End key - go to last page
      if (e.key === 'End' && isPaginationFocused) {
        e.preventDefault();
        const lastButton = pagination.querySelector('.data-table__page-button[aria-label*="last" i]');
        if (lastButton && !lastButton.disabled) {
          lastButton.click();
        }
      }
    });
  }

  /**
   * Initialize on DOM ready
   */
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function() {
      initPaginationKeyboardNavigation();
    });
  } else {
    initPaginationKeyboardNavigation();
  }

  /**
   * Re-initialize after HTMX page swaps
   */
  document.body.addEventListener('htmx:afterSwap', function() {
    initPaginationKeyboardNavigation();
  });
})();
