(function() {
  if (window.__sseClientInitialized) return;
  window.__sseClientInitialized = true;

  function log() {
    if (window.location.search.indexOf('debug=1') !== -1) {
      console.log.apply(console, arguments);
    }
  }

  function connect() {
    var eventSource = new EventSource('/mcp');
    eventSource.onopen = function() {
      log('[SSE] connection opened');
    };
    eventSource.onerror = function(err) {
      console.warn('[SSE] connection error', err);
    };
    eventSource.onmessage = function(evt) {
      log('[SSE] message', evt.data);
    };
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', connect);
  } else {
    connect();
  }
})();
