/**
 * Task Table Live Updates Handler
 *
 * Handles SSE events for task updates, creation, and deletion
 * with visual feedback animations
 */

var TaskUpdatesConfig = window.TaskUpdatesConfig = window.TaskUpdatesConfig || {
    FLASH_CLASS: 'flash',
    NEW_CLASS: 'new',
    DELETING_CLASS: 'deleting',
    SLIDE_IN_CLASS: 'slide-in'
};

var FLASH_CLASS = TaskUpdatesConfig.FLASH_CLASS;
var NEW_CLASS = TaskUpdatesConfig.NEW_CLASS;
var DELETING_CLASS = TaskUpdatesConfig.DELETING_CLASS;
var SLIDE_IN_CLASS = TaskUpdatesConfig.SLIDE_IN_CLASS;

document.addEventListener('DOMContentLoaded', function() {

    /**
     * Apply flash animation to updated row
     */
    const flashRow = (rowId) => {
        const row = document.getElementById(rowId);
        if (row) {
            row.classList.remove(FLASH_CLASS);
            // Trigger reflow to restart animation
            void row.offsetWidth;
            row.classList.add(FLASH_CLASS);

            // Remove class after animation completes
            setTimeout(() => {
                row.classList.remove(FLASH_CLASS);
            }, 1000);
        }
    };

    /**
     * Apply new row highlight and slide-in animation
     */
    const highlightNewRow = (rowId) => {
        const row = document.getElementById(rowId);
        if (row) {
            row.classList.add(NEW_CLASS, SLIDE_IN_CLASS);

            // Remove highlight class after animation
            setTimeout(() => {
                row.classList.remove(NEW_CLASS, SLIDE_IN_CLASS);
            }, 1500);
        }
    };

    /**
     * Remove deleted row with fade-out animation
     */
    const removeDeletedRow = (rowId) => {
        const row = document.getElementById(rowId);
        if (row) {
            row.classList.add(DELETING_CLASS);

            // Remove row from DOM after animation
            setTimeout(() => {
                row.remove();
                updateRowCount();
            }, 300);
        }
    };

    /**
     * Update row count indicator
     */
    const updateRowCount = () => {
        const tbody = document.querySelector('tbody[id$="-body"]');
        if (tbody) {
            const rowCount = tbody.querySelectorAll('tr').length;
            // Could update a row counter here if one exists
            console.log('Updated row count:', rowCount);
        }
    };

    /**
     * Listen for HTMX SSE swap events
     */
    document.addEventListener('htmx:oobBeforeSwap', function(event) {
        const detail = event.detail;

        // Check if this is a taskUpdated event
        if (detail.target && detail.target.hasAttribute('sse-swap')) {
            const sseSwap = detail.target.getAttribute('sse-swap');

            if (sseSwap.includes('taskUpdated')) {
                const rowId = detail.target.id;
                if (rowId) {
                    // Schedule flash after swap
                    setTimeout(() => flashRow(rowId), 50);
                }
            }
        }
    });

    /**
     * Listen for new rows being added
     */
    document.addEventListener('htmx:afterSwap', function(event) {
        const detail = event.detail;

        // Check if this is a taskCreated event
        if (detail.target && detail.target.hasAttribute('sse-swap')) {
            const sseSwap = detail.target.getAttribute('sse-swap');

            if (sseSwap.includes('taskCreated')) {
                // Find newly added rows and highlight them
                const newRows = detail.target.querySelectorAll('tr[id^="task-row-"]');
                if (newRows.length > 0) {
                    const firstNewRow = newRows[0];
                    if (firstNewRow.id) {
                        highlightNewRow(firstNewRow.id);
                    }
                }
                updateRowCount();
            }
        }
    });

    /**
     * Handle task deletion via SSE
     * Send message to handler or use explicit deletion event
     */
    const handleTaskDeletion = (taskId) => {
        const rowId = `task-row-${taskId}`;
        removeDeletedRow(rowId);
    };

    /**
     * Expose handler globally for SSE events
     */
    window.TaskUpdates = {
        flashRow,
        highlightNewRow,
        removeDeletedRow,
        handleTaskDeletion,
        updateRowCount
    };
});

/**
 * Monitor table for user interactions during updates
 * Prevents jarring updates if user is hovering/focusing
 */
document.addEventListener('mouseover', function(event) {
    const row = event.target.closest('tr[id^="task-row-"]');
    if (row && row.classList.contains(FLASH_CLASS)) {
        // Pause animation during hover
        row.style.animationPlayState = 'paused';
    }
});

document.addEventListener('mouseout', function(event) {
    const row = event.target.closest('tr[id^="task-row-"]');
    if (row && row.classList.contains(FLASH_CLASS)) {
        // Resume animation
        row.style.animationPlayState = 'running';
    }
});
