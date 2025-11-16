/**
 * Context Explorer JavaScript utilities
 */

(function() {
'use strict';

console.log('[Explorer] script loaded');

var STORAGE_KEY = 'explorer-filters';

/**
 * Delegate clicks for result Open buttons
 */
document.addEventListener('click', function(event) {
    const button = event.target.closest('[data-open-file]');
    if (!button) return;
    event.preventDefault();
    const filePath = button.dataset.openFile;
    const lineNumber = parseInt(button.dataset.lineNumber || '1', 10);
    openFile(filePath, lineNumber);
});

/**
 * Toggle filter panel visibility
 */
window.toggleFilters = function() {
    const panel = document.getElementById('filter-panel');
    if (panel) {
        panel.style.display = panel.style.display === 'none' ? 'block' : 'none';
    }
}

/**
 * Copy code snippet to clipboard
 */
window.copyToClipboard = function(button) {
    const content = button.getAttribute('data-content');
    if (content) {
        navigator.clipboard.writeText(content).then(() => {
            const originalText = button.textContent;
            button.textContent = '✓ Copied';
            setTimeout(() => {
                button.textContent = originalText;
            }, 2000);
        }).catch(err => {
            console.error('Failed to copy:', err);
        });
    }
}

/**
 * Update tokens display with K suffix
 */
window.updateTokensDisplay = function(value) {
    const display = document.getElementById('max-tokens-value');
    if (display) {
        const num = parseInt(value);
        display.textContent = num >= 1000 ? (num / 1000) + 'K' : num;
    }
}

/**
 * Get current filter values from form
 */
function getFilterValues() {
    return {
        paths: document.getElementById('filter-paths')?.value || '',
        excludePatterns: document.getElementById('filter-exclude')?.value || '',
        languages: Array.from(document.querySelectorAll('input[name="languages"]:checked')).map(el => el.value),
        kinds: Array.from(document.querySelectorAll('input[name="kinds"]:checked')).map(el => el.value),
        maxResults: document.getElementById('filter-max-results')?.value || '20',
        maxTokens: document.getElementById('filter-max-tokens')?.value || '6000'
    };
}

/**
 * Set filter values in form
 */
function setFilterValues(filters) {
    if (!filters) return;
    
    if (filters.paths) document.getElementById('filter-paths').value = filters.paths;
    if (filters.excludePatterns) document.getElementById('filter-exclude').value = filters.excludePatterns;
    
    // Languages
    document.querySelectorAll('input[name="languages"]').forEach(el => {
        el.checked = filters.languages?.includes(el.value) || false;
    });
    
    // Kinds
    document.querySelectorAll('input[name="kinds"]').forEach(el => {
        el.checked = filters.kinds?.includes(el.value) || false;
    });
    
    // Sliders
    if (filters.maxResults) {
        const slider = document.getElementById('filter-max-results');
        slider.value = filters.maxResults;
        document.getElementById('max-results-value').textContent = filters.maxResults;
    }
    
    if (filters.maxTokens) {
        const slider = document.getElementById('filter-max-tokens');
        slider.value = filters.maxTokens;
        updateTokensDisplay(filters.maxTokens);
    }
}

/**
 * Save filters to localStorage
 */
window.saveFilters = function() {
    const filters = getFilterValues();
    localStorage.setItem(STORAGE_KEY, JSON.stringify(filters));
    
    // Show feedback
    const btn = event.target;
    const originalText = btn.textContent;
    btn.textContent = '✓ Saved';
    btn.classList.add('btn-success');
    btn.classList.remove('btn-outline-primary');
    
    setTimeout(() => {
        btn.textContent = originalText;
        btn.classList.remove('btn-success');
        btn.classList.add('btn-outline-primary');
    }, 2000);
}

/**
 * Load filters from localStorage
 */
function loadFilters() {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved) {
        try {
            const filters = JSON.parse(saved);
            setFilterValues(filters);
        } catch (e) {
            console.error('Failed to load filters:', e);
        }
    }
}

/**
 * Reset filters to defaults
 */
window.resetFilters = function() {
    // Clear text areas
    document.getElementById('filter-paths').value = '';
    document.getElementById('filter-exclude').value = '';
    
    // Reset languages (Kotlin and Java checked by default)
    document.querySelectorAll('input[name="languages"]').forEach(el => {
        el.checked = ['kotlin', 'java'].includes(el.value);
    });
    
    // Reset kinds (code kinds checked by default)
    document.querySelectorAll('input[name="kinds"]').forEach(el => {
        el.checked = el.value.startsWith('CODE_');
    });
    
    // Reset sliders
    document.getElementById('filter-max-results').value = '20';
    document.getElementById('max-results-value').textContent = '20';
    
    document.getElementById('filter-max-tokens').value = '6000';
    updateTokensDisplay('6000');
    
    // Clear localStorage
    localStorage.removeItem(STORAGE_KEY);
}

/**
 * Open file in modal viewer
 */
window.openFile = function(filePath, lineNumber) {
    console.log('[Explorer] openFile clicked', { filePath, lineNumber });
    fetch(`/api/files/content?path=${encodeURIComponent(filePath)}`)
        .then(response => {
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}`);
            }
            return response.json();
        })
        .then(data => {
            console.log('[Explorer] file content loaded', { length: data?.content?.length });
            if (!data || typeof data.content !== 'string') {
                throw new Error('Missing file content');
            }
            showFileModal(filePath, lineNumber, data.content);
        })
        .catch(error => {
            console.error('Failed to load file:', error);
            alert('Failed to load file: ' + error.message);
            closeModal();
        });
}

/**
 * Show file content in modal
 */
function showFileModal(filePath, lineNumber, content) {
    console.log('[Explorer] showFileModal render', { filePath, lineNumber, length: content?.length });
    const modal = document.getElementById('modal-container');
    if (!modal) {
        console.warn('[Explorer] modal container not found');
        return;
    }
    
    const lines = content.split('\n');
    const startLine = Math.max(0, lineNumber - 10);
    const endLine = Math.min(lines.length, lineNumber + 10);
    const snippet = lines.slice(startLine, endLine)
        .map((line, idx) => {
            const num = startLine + idx + 1;
            const highlight = num === lineNumber ? ' bg-warning' : '';
            return `<div class="code-line${highlight}"><span class="line-num">${num}</span>${escapeHtml(line)}</div>`;
        })
        .join('');
    
    modal.innerHTML = `
        <div class="modal__backdrop" data-modal-close="modal-container"></div>
        <div class="modal__content">
            <div class="modal__header d-flex justify-content-between align-items-center">
                <h5 class="modal__title mb-0">${filePath}:${lineNumber}</h5>
                <button type="button" class="btn-close" aria-label="Close" data-modal-close="modal-container"></button>
            </div>
            <div class="modal__body">
                <pre class="code-viewer">${snippet}</pre>
            </div>
            <div class="modal__footer d-flex justify-content-end gap-2">
                <button type="button" class="btn btn-secondary btn-sm" data-modal-close="modal-container">Close</button>
            </div>
        </div>
    `;
    console.log('[Explorer] modal HTML injected, adding is-open');
    modal.classList.add('is-open');
    if (typeof window.openModal === 'function') {
        console.log('[Explorer] calling openModal');
        window.openModal('modal-container');
    } else {
        console.warn('[Explorer] window.openModal missing; relying on is-open');
    }
}

/**
 * Escape HTML for safe display
 */
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

/**
 * Submit search form
 */
window.submitSearch = function(event) {
    if (event) event.preventDefault();
    
    const form = document.getElementById('query-form');
    const query = document.getElementById('query-input')?.value?.trim();
    
    if (!query || query.length < 2) {
        alert('Please enter at least 2 characters');
        return false;
    }

    setQueryLoading(true);
    if (form && typeof form.requestSubmit === 'function') {
        form.requestSubmit();
    } else {
        htmx.trigger(form, 'submit');
    }
    return false;
}

/**
 * Clear search and results
 */
window.clearSearch = function() {
    document.getElementById('query-input').value = '';
    document.getElementById('results-container').innerHTML = '';
}

/**
 * Setup keyboard shortcuts
 */
function setupKeyboardShortcuts() {
    document.addEventListener('keydown', function(e) {
        // Ctrl/Cmd + K: Focus search
        if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
            e.preventDefault();
            document.getElementById('query-input')?.focus();
        }
        
        // Ctrl/Cmd + Enter: Submit search
        if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
            const activeElement = document.activeElement;
            if (activeElement && activeElement.id === 'query-input') {
                e.preventDefault();
                submitSearch();
            }
        }
        
        // Escape: Close modal or clear focus
        if (e.key === 'Escape') {
            const modal = document.getElementById('modal-container');
            if (modal && modal.innerHTML) {
                closeModal();
            }
        }
    });
}

function setQueryLoading(isLoading) {
    const btn = document.getElementById('run-query-btn');
    const label = document.getElementById('run-query-label');
    const spinner = document.getElementById('run-query-spinner');
    if (!btn || !label || !spinner) return;

    if (isLoading) {
        btn.disabled = true;
        spinner.classList.remove('d-none');
        label.textContent = 'Running...';
    } else {
        btn.disabled = false;
        spinner.classList.add('d-none');
        label.textContent = '▶ Run Query';
    }
}

function setupQueryLoadingHandlers() {
    const form = document.getElementById('query-form');
    if (!form) return;
    if (form.dataset.loadingHandlersBound === 'true') return;
    form.dataset.loadingHandlersBound = 'true';

    document.body.addEventListener('htmx:beforeRequest', function(evt) {
        if ((evt.detail && evt.detail.elt === form) || evt.target === form) {
            setQueryLoading(true);
        }
    });

    const resetHandler = function(evt) {
        if ((evt.detail && evt.detail.elt === form) || evt.target === form) {
            setQueryLoading(false);
        }
    };

    document.body.addEventListener('htmx:afterRequest', resetHandler);
    document.body.addEventListener('htmx:responseError', resetHandler);
}

/**
 * Initialize filters on page load
 */
// No auto-initialization - functions are called manually or via onclick

// Initialize loading handlers on load
setQueryLoading(false);
setupQueryLoadingHandlers();

})(); // End IIFE
