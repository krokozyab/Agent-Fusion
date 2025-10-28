/**
 * ag-Grid Setup and Initialization
 * Provides utilities for creating and managing ag-Grid instances
 */

// CDN links for ag-Grid Community
const AG_GRID_JS_URL = '/static/js/ag-grid-community.min.js';
const AG_GRID_CSS_URL = '/static/css/ag-grid.css';
const AG_GRID_THEME_URL = '/static/css/ag-theme-quartz.css';

/**
 * Load ag-Grid CSS dynamically if not already loaded
 */
function loadAgGridStyles() {
  if (document.querySelector('link[href*="ag-grid.min.css"]')) {
    return; // Already loaded
  }

  const link1 = document.createElement('link');
  link1.rel = 'stylesheet';
  link1.href = AG_GRID_CSS_URL;
  document.head.appendChild(link1);

  const link2 = document.createElement('link');
  link2.rel = 'stylesheet';
  link2.href = AG_GRID_THEME_URL;
  document.head.appendChild(link2);
}

/**
 * Create an ag-Grid instance with standard options
 * @param {string} containerId - ID of the container div
 * @param {Array} columnDefs - Column definitions for ag-Grid
 * @param {Array} rowData - Row data
 * @param {Object} options - Additional grid options
 * @returns {Object} ag-Grid instance
 */
function createAgGrid(containerId, columnDefs, rowData, options = {}) {
  const container = document.getElementById(containerId);
  if (!container) {
    console.error(`Container with id "${containerId}" not found`);
    return null;
  }

  const gridOptions = {
    columnDefs: columnDefs,
    rowData: rowData,

    // Theme
    theme: 'ag-theme-quartz',

    // Default behavior
    pagination: true,
    paginationPageSize: 50,
    paginationPageSizeSelector: [10, 25, 50, 100, 200],

    // Features
    enableCellTextSelection: true,
    suppressRowClickSelection: false,
    suppressCellFocus: false,
    suppressFieldDotNotation: false,

    // Appearance
    headerHeight: 40,
    rowHeight: 40,

    // Performance
    virtualizationPageSize: 50,

    // Custom options override defaults
    ...options
  };

  // Create grid
  return agGrid.createGrid(container, gridOptions);
}

/**
 * Destroy an ag-Grid instance
 * @param {Object} gridApi - The grid API instance
 */
function destroyAgGrid(gridApi) {
  if (gridApi) {
    gridApi.destroy();
  }
}

/**
 * Update grid data
 * @param {Object} gridApi - The grid API instance
 * @param {Array} rowData - New row data
 */
function updateGridData(gridApi, rowData) {
  if (gridApi) {
    gridApi.setGridOption('rowData', rowData);
  }
}

/**
 * Refresh grid layout (useful after container resize)
 * @param {Object} gridApi - The grid API instance
 */
function refreshGridLayout(gridApi) {
  if (gridApi) {
    gridApi.sizeColumnsToFit();
  }
}

/**
 * Export grid data to CSV
 * @param {Object} gridApi - The grid API instance
 * @param {string} filename - Output filename
 */
function exportGridToCsv(gridApi, filename = 'export.csv') {
  if (gridApi) {
    gridApi.exportDataAsCsv({
      fileName: filename
    });
  }
}

// Load styles when script loads
document.addEventListener('DOMContentLoaded', loadAgGridStyles);
