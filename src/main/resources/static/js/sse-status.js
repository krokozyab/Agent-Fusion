/**
 * SSE Connection Status Indicator
 *
 * Monitors HTMX SSE connection and updates visual status indicator
 */

document.addEventListener('DOMContentLoaded', function() {
    const statusIndicator = document.getElementById('sse-status-indicator');
    const statusLight = document.getElementById('sse-status-light');
    const statusText = document.getElementById('sse-status-text');

    if (!statusIndicator || !statusLight || !statusText) {
        console.warn('SSE status indicator elements not found');
        return;
    }

    /**
     * Update status light and text
     */
    const updateStatus = (state, message) => {
        // Remove all state classes
        statusLight.classList.remove(
            'sse-status__light--connected',
            'sse-status__light--disconnected',
            'sse-status__light--connecting'
        );

        // Add new state class
        statusLight.classList.add(`sse-status__light--${state}`);
        statusText.textContent = message;
        statusLight.setAttribute('aria-label', message);
    };

    // Initial state
    updateStatus('connecting', 'Connecting...');

    // Track if we've received at least one message (indicates connection is live)
    let hasReceivedMessage = false;

    // Listen for HTMX SSE events - the custom extension dispatches htmx:sseMessage instead of htmx:sseOpen
    document.addEventListener('htmx:sseMessage', function(event) {
        console.log('SSE message received, marking as connected');
        if (!hasReceivedMessage) {
            hasReceivedMessage = true;
            updateStatus('connected', 'Connected');
        }
    });

    // Alternative: Listen for message events before they're processed
    document.addEventListener('htmx:sseBeforeMessage', function(event) {
        console.log('SSE message before processing');
        if (!hasReceivedMessage) {
            hasReceivedMessage = true;
            updateStatus('connected', 'Connected');
        }
    });

    // Standard HTMX SSE events (for compatibility with standard HTMX SSE)
    document.addEventListener('htmx:sseOpen', function(event) {
        console.log('SSE connection opened (standard event)');
        updateStatus('connected', 'Connected');
        hasReceivedMessage = true;
    });

    document.addEventListener('htmx:sseError', function(event) {
        console.error('SSE connection error', event);
        updateStatus('disconnected', 'Connection error');
        hasReceivedMessage = false;
    });

    document.addEventListener('htmx:sseClose', function(event) {
        console.log('SSE connection closed');
        updateStatus('disconnected', 'Disconnected');
        hasReceivedMessage = false;
    });

    // Fallback: Check connection every 5 seconds
    setInterval(() => {
        // If we have an SSE source, update status accordingly
        const sseSource = document.querySelector('[hx-ext~="sse"]');
        if (sseSource && statusLight.classList.contains('sse-status__light--disconnected')) {
            // Try to reconnect or verify connection
            if (typeof htmx !== 'undefined') {
                console.log('Checking HTMX SSE connection...');
            }
        }
    }, 5000);
});
