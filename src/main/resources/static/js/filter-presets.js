(function () {
  if (typeof window === 'undefined') {
    return;
  }

  const SHORTCUT_ATTR = 'data-search-shortcut';
  const CLEAR_ATTR = 'data-filter-clear';
  const PRESET_ATTR = 'data-filter-preset';
  const TARGET_ATTR = 'data-filter-target';

  function dispatchUpdate(form) {
    if (!form) return;
    if (window.htmx && typeof window.htmx.trigger === 'function') {
      window.htmx.trigger(form, 'change');
    } else {
      const event = new Event('change', { bubbles: true });
      form.dispatchEvent(event);
    }
  }

  document.addEventListener('keydown', function (event) {
    if (event.defaultPrevented) {
      return;
    }

    if (event.key === '/' && !event.metaKey && !event.ctrlKey && !event.altKey) {
      const active = document.activeElement;
      if (active && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA' || active.isContentEditable)) {
        return;
      }

      const target = document.querySelector('[' + SHORTCUT_ATTR + '="/"]');
      if (target) {
        event.preventDefault();
        target.focus({ preventScroll: false });
        if (typeof target.select === 'function') {
          target.select();
        }
      }
    }
  });

  document.addEventListener('click', function (event) {
    const clearButton = event.target.closest('[' + CLEAR_ATTR + ']');
    if (clearButton) {
      const formSelector = clearButton.getAttribute(TARGET_ATTR);
      const form = formSelector ? document.querySelector(formSelector) : clearButton.closest('form');
      if (form) {
        form.reset();
        dispatchUpdate(form);
      }
    }

    const presetButton = event.target.closest('[' + PRESET_ATTR + ']');
    if (presetButton) {
      const formSelector = presetButton.getAttribute('data-preset-target');
      const form = formSelector ? document.querySelector(formSelector) : presetButton.closest('form');
      if (!form) {
        return;
      }
      const query = presetButton.getAttribute('data-preset-query') || '';
      const status = presetButton.getAttribute('data-preset-status') || '';
      const type = presetButton.getAttribute('data-preset-type') || '';
      const agent = presetButton.getAttribute('data-preset-agent') || '';

      const queryField = form.querySelector('input[name="query"]');
      const statusField = form.querySelector('select[name="status"]');
      const typeField = form.querySelector('select[name="type"]');
      const agentField = form.querySelector('select[name="agent"]');

      if (queryField) {
        queryField.value = query;
      }
      if (statusField) {
        statusField.value = status;
      }
      if (typeField) {
        typeField.value = type;
      }
      if (agentField) {
        agentField.value = agent;
      }

      dispatchUpdate(form);
    }
  });
})();
