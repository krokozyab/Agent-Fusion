(function() {
  'use strict';

  var STATE = window.__indexStatusState || (window.__indexStatusState = {
    connection: null,
    listenersBound: false
  });

  var SELECTOR = '[data-index-action]';
  var ROOT_SELECTOR = '#index-status-container';
  var PROGRESS_ID = 'index-progress-region';
  var SUMMARY_ID = 'index-summary';
  var SSE_URL = '/sse/index';

  function hasIndexContent(scope) {
    var context = scope || document;
    return !!context.querySelector(ROOT_SELECTOR);
  }

  function closeConnection() {
    if (STATE.connection) {
      try {
        STATE.connection.close();
      } catch (err) {
        console.warn('[IndexStatus] Failed to close SSE connection', err);
      }
    }
    STATE.connection = null;
  }

  function ensureSSE(force) {
    if (!hasIndexContent()) {
      closeConnection();
      return;
    }

    var connection = STATE.connection;
    if (connection) {
      var ready = connection.readyState;
      if (!force && (ready === EventSource.OPEN || ready === EventSource.CONNECTING)) {
        return;
      }
      closeConnection();
    }

    try {
      connection = new EventSource(SSE_URL, { withCredentials: true });
      registerHandlers(connection);
      STATE.connection = connection;
    } catch (err) {
      console.error('[IndexStatus] Failed to create SSE connection', err);
      STATE.connection = null;
    }
  }

  function registerHandlers(source) {
    if (!source) return;

    source.addEventListener('indexProgress', function(event) {
      handleIndexProgressEvent(event.data);
    });

    source.addEventListener('indexSummary', function(event) {
      handleIndexSummaryEvent(event.data);
    });

    source.addEventListener('error', function(err) {
      if (source.readyState === EventSource.CLOSED) {
        console.warn('[IndexStatus] SSE connection closed; attempting reconnect');
      } else {
        console.error('[IndexStatus] SSE error', err);
      }
      closeConnection();
      setTimeout(function() {
        ensureSSE(true);
      }, 1000);
    });
  }

  function handleIndexProgressEvent(htmlFragment) {
    var container = document.getElementById(PROGRESS_ID);
    if (!container) {
      return;
    }
    container.outerHTML = htmlFragment;
  }

  function handleIndexSummaryEvent(htmlFragment) {
    var summary = document.getElementById(SUMMARY_ID);
    if (!summary) {
      return;
    }
    summary.outerHTML = htmlFragment;

    var progress = document.getElementById(PROGRESS_ID);
    if (progress) {
      progress.className = 'index-progress index-progress--idle';
      progress.innerHTML = '<span class="text-muted">No active operations.</span>';
    }

    enableAllIndexButtons();
  }

  function showPendingState(label) {
    var region = document.getElementById(PROGRESS_ID);
    if (!region) {
      return;
    }

    region.classList.remove('index-progress--idle');
    region.classList.add('index-progress', 'index-progress--pending');

    var safeLabel = (label || 'Index Operation').trim();
    region.innerHTML = ''
      + '<div class="index-progress__header">'
      +   '<span class="index-progress__title">' + safeLabel + '</span>'
      +   '<span class="index-progress__value">Preparing…</span>'
      + '</div>'
      + '<progress class="index-progress__bar" max="100" value="0"></progress>'
      + '<div class="index-progress__meta">Waiting for server updates…</div>';
  }

  function disableAllIndexButtons() {
    document.querySelectorAll(SELECTOR).forEach(function(btn) {
      btn.disabled = true;
      btn.classList.add('button--disabled');
    });
  }

  function enableAllIndexButtons() {
    document.querySelectorAll(SELECTOR).forEach(function(btn) {
      btn.disabled = false;
      btn.classList.remove('button--disabled');
    });
  }

  function bindIndexActionButtons(root) {
    var scope = root || document;
    var buttons = scope.querySelectorAll(SELECTOR);
    buttons.forEach(function(btn) {
      if (btn.dataset.indexActionBound === 'true') {
        return;
      }
      btn.dataset.indexActionBound = 'true';

      btn.addEventListener('click', function(event) {
        var endpoint = btn.getAttribute('data-action-endpoint');
        if (!endpoint) {
          return;
        }

        var confirmMessage = btn.getAttribute('data-action-confirm');
        if (confirmMessage && !window.confirm(confirmMessage)) {
          return;
        }

        event.preventDefault();
        var label = btn.getAttribute('data-action-label') || btn.textContent || '';

        ensureSSE(true);
        showPendingState(label);
        disableAllIndexButtons();

        fetch(endpoint, {
          method: 'POST',
          credentials: 'same-origin',
          headers: {
            'X-Requested-With': 'fetch'
          }
        }).catch(function(error) {
          console.error('[IndexStatus] Index action request failed', error);
          enableAllIndexButtons();
        });
      });
    });
  }

  function initialize(scope) {
    if (!hasIndexContent(scope)) {
      return;
    }
    bindIndexActionButtons(scope);
    ensureSSE(true);
  }

  function bindGlobalListeners() {
    if (STATE.listenersBound) {
      return;
    }
    STATE.listenersBound = true;

    document.addEventListener('htmx:afterSwap', function(evt) {
      if (hasIndexContent(evt.target)) {
        initialize(evt.target);
      } else if (!hasIndexContent()) {
        closeConnection();
      }
    });

    document.addEventListener('htmx:afterSettle', function() {
      ensureSSE();
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function() {
      initialize(document);
    }, { once: true });
  } else {
    initialize(document);
  }

  bindGlobalListeners();
  // Safety net in case DOMContent has already fired and HTMX navigation loads the fragment.
  setTimeout(function() {
    ensureSSE();
  }, 100);
})();
