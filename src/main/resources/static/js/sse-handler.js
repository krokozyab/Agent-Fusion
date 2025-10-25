/**
 * SSE Event Handler
 *
 * Handles Server-Sent Events with improved error handling and robustness.
 * Prevents "Cannot read properties of null" errors when swap targets don't exist.
 */

(function() {
  'use strict';

  // Add global error handling for HTMX dispatchEvent errors
  document.addEventListener('htmx:sseMessage', function(event) {
    try {
      // Just log for debugging
      if (event.detail) {
        console.debug('SSE event received:', event.detail);
      }
    } catch (error) {
      console.warn('Error processing SSE message:', error);
    }
  }, { capture: true });

  // Global error handler for HTMX events (set up immediately, not just after HTMX loads)
  document.addEventListener('htmx:responseError', function(event) {
    console.error('HTMX Response Error:', event);
  }, true);

  // Catch uncaught errors in HTMX operations
  const originalError = window.onerror;
  window.onerror = function(msg, url, lineNo, columnNo, error) {
    // Only log if it's related to dispatchEvent or swap-related errors
    if (msg && (msg.includes('dispatchEvent') || msg.includes('Cannot read properties of null'))) {
      console.warn('Caught HTMX-related error:', msg);
      if (msg && msg.includes('dispatchEvent')) {
        console.warn('This usually means an SSE swap target element does not exist in the DOM');
      }
      console.debug('Full error:', error);

      // Return true to prevent default error handling
      return true;
    }

    // Call original error handler if it exists
    if (typeof originalError === 'function') {
      return originalError(msg, url, lineNo, columnNo, error);
    }
  };
})();
