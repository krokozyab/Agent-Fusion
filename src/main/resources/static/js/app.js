(function () {
  function whenReady(cb) {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', cb, { once: true });
    } else {
      cb();
    }
  }

  function renderMermaid() {
    try {
      // Find all mermaid elements first so we only render when needed
      var mermaidElements = document.querySelectorAll('.mermaid');
      if (mermaidElements.length === 0) return;

      if (!window.mermaid) {
        console.warn('Mermaid scripts missing; skipping diagram render');
        return;
      }

      // Use mermaid.run() for modern Mermaid (v10+)
      if (typeof window.mermaid.run === 'function') {
        window.mermaid.run({
          querySelector: '.mermaid'
        }).catch(function(error) {
          console.error('Mermaid rendering error:', error);
        });
      }
    } catch (error) {
      console.error('Error rendering mermaid diagrams:', error);
    }
  }

  function attachHtmxHooks() {
    if (!window.htmx || window.__mermaidHtmxHooked) return;

    // Render mermaid after HTMX swaps content
    document.body.addEventListener('htmx:afterSwap', function(evt) {
      // Small delay to ensure new content is in DOM
      setTimeout(renderMermaid, 100);
    });

    document.body.addEventListener('htmx:afterSettle', function(evt) {
      renderMermaid();
    });

    window.__mermaidHtmxHooked = true;
  }

  function configureHtmx() {
    if (window.htmx) {
      window.htmx.config.defaultSwapStyle = 'outerHTML';
    }
  }

  whenReady(function () {
    configureHtmx();
    attachHtmxHooks();
  });
})();
