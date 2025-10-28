(function () {
  const PENDING_EVENT_QUEUE = [];
  let gridApi = null;
  let columnApi = null;

  /**
   * Register grid instance once ag-Grid is ready.
   */
  function registerGrid(api, column) {
    if (gridApi && gridApi.destroy && gridApi !== api) {
      try {
        gridApi.destroy();
      } catch (err) {
        console.warn('Failed to destroy previous tasks grid instance', err);
      }
    }

    gridApi = api;
    columnApi = column;

    if (window.TaskUpdates && typeof window.TaskUpdates.setGridApi === 'function') {
      window.TaskUpdates.setGridApi(gridApi);
    }

    attachGridEventHandlers();
    flushPendingEvents();
  }

  /**
   * Tear down current grid instance.
   */
  function destroyGrid() {
    if (gridApi && gridApi.destroy) {
      try {
        gridApi.destroy();
      } catch (err) {
        console.warn('Failed to destroy tasks grid', err);
      }
    }
    gridApi = null;
    columnApi = null;
    PENDING_EVENT_QUEUE.length = 0;

    if (window.TaskUpdates && typeof window.TaskUpdates.setGridApi === 'function') {
      window.TaskUpdates.setGridApi(null);
    }
  }

  /**
   * Ensure quick filter input drives ag-Grid quick filter.
   */
  function initQuickFilter() {
    const input = document.getElementById('tasks-quick-filter');
    if (!input) return;

    const applyFilter = () => {
      if (!gridApi) return;
      const value = input.value || '';
      if (typeof gridApi.setGridOption === 'function') {
        gridApi.setGridOption('quickFilterText', value);
      } else if (typeof gridApi.setQuickFilter === 'function') {
        gridApi.setQuickFilter(value);
      }
    };

    input.addEventListener('input', applyFilter);
    input.addEventListener('change', applyFilter);
  }

  /**
   * Attach grid-specific event handlers once the API is ready.
   */
  function attachGridEventHandlers() {
    if (!gridApi) return;

    gridApi.addEventListener('rowDoubleClicked', (params) => {
      if (!params || !params.data || !params.data.detailUrl || !window.htmx) return;
      window.htmx.ajax('GET', params.data.detailUrl, {
        target: '#modal-container',
        swap: 'innerHTML',
        indicator: '#tasks-grid-indicator'
      });
    });
  }

  /**
   * Queue grid-mutating operations until the grid is ready.
   */
  function enqueueWhenReady(fn) {
    if (gridApi) {
      fn();
    } else {
      PENDING_EVENT_QUEUE.push(fn);
    }
  }

  function flushPendingEvents() {
    while (gridApi && PENDING_EVENT_QUEUE.length) {
      const fn = PENDING_EVENT_QUEUE.shift();
      try {
        fn();
      } catch (err) {
        console.error('Failed to process pending task grid event', err);
      }
    }
  }

  /**
   * Handle SSE fragment swaps for task updates.
   */
  function handleTaskEventFragment(target) {
    if (!target) return;
    const eventEl = target.querySelector('.task-grid-event');
    if (!eventEl) return;

    const taskId = eventEl.dataset.taskId;
    const eventType = eventEl.dataset.eventType || 'taskUpdated';
    const rowJson = eventEl.dataset.row;

    if (!rowJson) {
      console.warn('Task grid event missing row payload');
      target.innerHTML = '';
      return;
    }

    let rowData;
    try {
      rowData = JSON.parse(rowJson);
    } catch (err) {
      console.error('Failed to parse task row JSON', err);
      target.innerHTML = '';
      return;
    }

    if (!rowData.taskId) {
      rowData.taskId = taskId;
    }

    target.innerHTML = '';

    enqueueWhenReady(() => {
      if (eventType === 'taskCreated') {
        upsertRow(rowData, { highlightNew: true });
      } else {
        upsertRow(rowData, { flash: true });
      }
    });
  }

  /**
   * Insert or update a row in the grid.
   */
  function upsertRow(rowData, options = {}) {
    if (!gridApi || !rowData || !rowData.taskId) return;

    const existingNode = findNode(rowData.taskId);
    if (existingNode) {
      existingNode.setData(rowData);
      gridApi.refreshCells({ rowNodes: [existingNode], force: true });
      if (options.flash && window.TaskUpdates) {
        window.TaskUpdates.flashRow(rowData.taskId);
      }
      return;
    }

    const txResult = gridApi.applyTransaction({
      add: [rowData],
      addIndex: 0
    });

    const addedNode = txResult && txResult.add && txResult.add[0];
    if (addedNode) {
      if (window.TaskUpdates) {
        if (options.highlightNew && typeof window.TaskUpdates.highlightNewRow === 'function') {
          window.TaskUpdates.highlightNewRow(rowData.taskId);
        } else if (typeof window.TaskUpdates.flashRow === 'function') {
          window.TaskUpdates.flashRow(rowData.taskId);
        }
      }
    }
  }

  /**
   * Locate a row node by task ID.
   */
  function findNode(taskId) {
    if (!gridApi) return null;
    let match = null;
    gridApi.forEachNode((node) => {
      if (node && node.data && node.data.taskId === taskId) {
        match = node;
      }
    });
    return match;
  }

  /**
   * Listen for ag-Grid ready events dispatched by the Kotlin helper.
   */
  document.addEventListener('ag-grid:ready', (event) => {
    if (!event || !event.target || event.target.id !== 'tasks-grid') return;
    const detail = event.detail || {};
    if (!detail.gridApi) return;
    registerGrid(detail.gridApi, detail.columnApi);
  });

  /**
   * Destroy grid before HTMX swaps in a replacement.
   */
  document.addEventListener('htmx:beforeSwap', (event) => {
    if (!event.detail || !event.detail.target) return;
    if (event.detail.target.id === 'tasks-grid-container') {
      destroyGrid();
    }
  });

  /**
   * Handle HTMX swaps for SSE fragments and grid container updates.
   */
  document.addEventListener('htmx:afterSwap', (event) => {
    const target = event.detail && event.detail.target;
    if (!target) return;

    if (target.id === 'tasks-grid-event-updated' || target.id === 'tasks-grid-event-created') {
      handleTaskEventFragment(target);
    }
  });

  document.addEventListener('DOMContentLoaded', () => {
    initQuickFilter();
  });

  function escapeHtml(value) {
    if (value == null) return '';
    return String(value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function renderId(params) {
    const value = params?.value || params?.data?.idDisplay || '';
    return `<span class="task-row__id">${escapeHtml(value)}</span>`;
  }

  function renderTitle(params) {
    const data = params?.data || {};
    const title = escapeHtml(data.title || '');
    const routing = escapeHtml(data.routingDisplay || '');
    const complexity = typeof data.complexity === 'number' ? data.complexity : '–';
    const risk = typeof data.risk === 'number' ? data.risk : '–';

    return `
      <div class="task-row__main">
        <span class="task-row__title">${title}</span>
        <div class="task-row__meta">
          <span class="task-row__meta-item">${routing}</span>
          <span class="task-row__meta-item">Complexity ${escapeHtml(complexity)}</span>
          <span class="task-row__meta-item">Risk ${escapeHtml(risk)}</span>
        </div>
      </div>
    `;
  }

  function renderBadge(label, tone, outline) {
    const classes = ['badge'];
    classes.push(`badge--${tone || 'default'}`);
    if (outline) {
      classes.push('badge--outline');
    }
    return `<span class="${classes.join(' ')}">${escapeHtml(label)}</span>`;
  }

  function renderStatus(params) {
    const data = params?.data || {};
    return `
      <span class="task-row__status">
        ${renderBadge(data.statusLabel || '', data.statusTone || 'default', false)}
      </span>
    `;
  }

  function renderType(params) {
    const data = params?.data || {};
    return `
      <span class="task-row__type">
        ${renderBadge(data.typeLabel || '', data.typeTone || 'default', true)}
      </span>
    `;
  }

  function renderRouting(params) {
    const value = params?.data?.routingDisplay || params?.value || '';
    return `<span class="task-row__routing">${escapeHtml(value)}</span>`;
  }

  function renderAssignees(params) {
    const data = params?.data || {};
    const assignees = data.assignees;
    let content = 'Unassigned';
    if (Array.isArray(assignees) && assignees.length > 0) {
      content = assignees.map((agent) => `<span class="task-row__agent">${escapeHtml(agent)}</span>`).join('');
    } else if (typeof data.assigneesDisplay === 'string') {
      content = `<span class="task-row__agent">${escapeHtml(data.assigneesDisplay)}</span>`;
    }
    return `<div class="task-row__agents">${content}</div>`;
  }

  function renderUpdatedAt(params) {
    const data = params?.data || {};
    const human = escapeHtml(data.updatedAtHuman || '–');
    const absolute = escapeHtml(data.updatedAtAbsolute || '');
    return `<span class="task-row__timestamp" title="${absolute}">${human}</span>`;
  }

  function renderCreatedAt(params) {
    const data = params?.data || {};
    const human = escapeHtml(data.createdAtHuman || '–');
    const absolute = escapeHtml(data.createdAtAbsolute || '');
    return `<span class="task-row__timestamp" title="${absolute}">${human}</span>`;
  }

  function renderActions(params) {
    const data = params?.data || {};
    const detailUrl = data.detailUrl ? escapeHtml(data.detailUrl) : '#';
    const editUrl = data.editUrl ? escapeHtml(data.editUrl) : '#';

    return `
      <div class="task-row__actions">
        <button type="button"
                class="task-row__action task-row__action--view"
                hx-get="${detailUrl}"
                hx-target="#modal-container"
                hx-swap="innerHTML"
                hx-trigger="click consume"
                aria-label="View task">
          View
        </button>
        <button type="button"
                class="task-row__action task-row__action--edit"
                hx-get="${editUrl}"
                hx-target="#modal-container"
                hx-swap="innerHTML"
                hx-trigger="click consume"
                aria-label="Edit task">
          Edit
        </button>
      </div>
    `;
  }

  window.TaskGrid = {
    registerGrid,
    destroyGrid,
    handleTaskEventFragment,
    getGridApi: () => gridApi,
    renderId,
    renderTitle,
    renderStatus,
    renderType,
    renderRouting,
    renderAssignees,
    renderUpdatedAt,
    renderCreatedAt,
    renderActions
  };
})();
