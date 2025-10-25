/**
 * SSE Event Handler
 *
 * Handles Server-Sent Events with improved error handling and robustness.
 * Prevents "Cannot read properties of null" errors when swap targets don't exist.
 */

(function() {
  'use strict';

  // Wait for HTMX to be ready before adding hooks
  function waitForHtmx(callback, maxWait) {
    maxWait = maxWait || 10000;
    const startTime = Date.now();

    const checkHtmx = function() {
      if (window.htmx && window.htmx.ext && window.htmx.ext.sse) {
        callback();
      } else if (Date.now() - startTime < maxWait) {
        setTimeout(checkHtmx, 100);
      } else {
        console.warn('HTMX SSE extension not available after', maxWait, 'ms');
      }
    };

    checkHtmx();
  }

  // Add error handling wrapper after HTMX loads
  waitForHtmx(function() {
    document.addEventListener('htmx:sseMessage', function(event) {
      try {
        // Check if the SSE event has OOB swap targets
        if (event.detail && event.detail.message) {
          const message = event.detail.message;

          // Parse the message to check for swap targets
          if (typeof message === 'object' && message.htmlFragment) {
            // Verify swap targets exist before processing
            const tempDiv = document.createElement('div');
            tempDiv.innerHTML = message.htmlFragment;

            // Check for oob-swap attributes
            const oobElements = tempDiv.querySelectorAll('[hx-swap-oob], [data-hx-swap-oob]');
            oobElements.forEach(elem => {
              const swapAttr = elem.getAttribute('hx-swap-oob') || elem.getAttribute('data-hx-swap-oob');
              const id = elem.getAttribute('id');

              if (id && !document.getElementById(id)) {
                console.warn(`SSE swap target not found: ${id} (${swapAttr})`);
              }
            });

            // Also check for sse-swap
            const sseElements = tempDiv.querySelectorAll('[sse-swap]');
            sseElements.forEach(elem => {
              const swapId = elem.getAttribute('id');
              if (swapId && !document.getElementById(swapId)) {
                console.warn(`SSE swap target not found: ${swapId}`);
              }
            });
          }
        }
      } catch (error) {
        console.error('Error validating SSE swap targets:', error);
      }
    }, { capture: true });

    // Log SSE events for debugging
    document.addEventListener('htmx:sseMessage', function(event) {
      if (event.detail && event.detail.message) {
        const message = event.detail.message;
        if (typeof message === 'object' && message.htmlFragment) {
          console.debug('SSE event received with HTML fragment:', {
            type: message.type,
            fragmentLength: message.htmlFragment.length
          });
        }
      }
    });
  });

  // Global error handler for HTMX events (set up immediately, not just after HTMX loads)
  document.addEventListener('htmx:responseError', function(event) {
    console.error('HTMX Response Error:', event);
  }, true);

  // Catch uncaught errors in HTMX operations
  const originalError = window.onerror;
  window.onerror = function(msg, url, lineNo, columnNo, error) {
    // Only log if it's related to dispatchEvent or swap-related errors
    if (msg && (msg.includes('dispatchEvent') || msg.includes('undefined'))) {
      console.warn('Caught HTMX-related error:', msg);
      if (msg.includes('dispatchEvent')) {
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
