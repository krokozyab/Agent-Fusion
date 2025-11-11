(function () {
  const FLASH_COLUMNS = [
    'title',
    'statusLabel',
    'typeLabel',
    'routingDisplay',
    'assigneesDisplay'
  ];

  let gridApi = null;

  function setGridApi(api) {
    gridApi = api;
  }

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

  function flashRow(taskId) {
    const node = findNode(taskId);
    if (!node || !gridApi) return;

    if (typeof gridApi.flashCells === 'function') {
      gridApi.flashCells({
        rowNodes: [node],
        columns: FLASH_COLUMNS,
        flashDelay: 750,
        fadeDelay: 500
      });
    }
  }

  function highlightNewRow(taskId) {
    const node = findNode(taskId);
    if (!node || !gridApi) return;

    if (typeof gridApi.ensureNodeVisible === 'function') {
      gridApi.ensureNodeVisible(node, 'top');
    }

    if (typeof gridApi.flashCells === 'function') {
      gridApi.flashCells({
        rowNodes: [node],
        columns: FLASH_COLUMNS,
        flashDelay: 1200,
        fadeDelay: 600
      });
    }
  }

  function removeDeletedRow(taskId) {
    if (!gridApi) return;
    const node = findNode(taskId);
    if (!node) return;

    try {
      gridApi.applyTransaction({ remove: [node.data] });
    } catch (err) {
      console.error('Failed to remove task row', err);
    }
  }

  window.TaskUpdates = {
    setGridApi,
    flashRow,
    highlightNewRow,
    removeDeletedRow,
    handleTaskDeletion: removeDeletedRow,
    updateRowCount: function () {
      // No-op: ag-Grid manages row counts internally.
    }
  };
})();
