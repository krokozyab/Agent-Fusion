// Prevent redeclaration if script is loaded multiple times
if (typeof window.openModal === 'undefined') {
    const openModal = (modalId) => {
        const modal = document.getElementById(modalId);
        console.log('openModal called', { modalId, found: !!modal, classList: modal?.classList.toString() });
        if (modal) {
            console.log('Adding is-open class to modal', modalId);
            modal.classList.add('is-open');
            document.body.style.overflow = 'hidden';
        } else {
            console.error('Modal not found:', modalId);
        }
    };

    const closeModal = (modalId) => {
        const modal = document.getElementById(modalId);
        if (modal) {
            modal.classList.remove('is-open');
            modal.innerHTML = '';
            document.body.style.overflow = '';
            console.log('Modal closed and content cleared:', modalId);
        }
    };

    // Make functions available globally
    window.openModal = openModal;
    window.closeModal = closeModal;
}

// OUTSIDE DOMContentLoaded so it works for dynamically added elements
document.addEventListener('click', (event) => {
    console.log('Click event:', { target: event.target.tagName, dataset: event.target.dataset, classList: event.target.className });

    if (event.target.dataset.modalOpen) {
        console.log('Opening modal:', event.target.dataset.modalOpen);
        window.openModal(event.target.dataset.modalOpen);
    }

    if (event.target.dataset.modalClose) {
        console.log('Closing modal:', event.target.dataset.modalClose);
        window.closeModal(event.target.dataset.modalClose);
    }

    // Close modal when clicking on backdrop
    if (event.target.classList.contains('modal__backdrop')) {
        console.log('Backdrop clicked');
        const modal = event.target.closest('.modal');
        if (modal) {
            window.closeModal(modal.id);
        }
    }
});

// ESC key handler OUTSIDE DOMContentLoaded
document.addEventListener('keydown', (event) => {
    console.log('Keydown event:', { key: event.key });
    if (event.key === 'Escape') {
        console.log('ESC pressed - closing modals');
        event.preventDefault();
        const openModals = document.querySelectorAll('.modal.is-open');
        console.log('Open modals found:', openModals.length);
        openModals.forEach(modal => {
            console.log('Closing modal:', modal.id);
            window.closeModal(modal.id);
        });
    }
}, true);

// Handle HTMX swap events - open modal after content is loaded
// Use afterSettle instead of afterSwap to ensure DOM is fully updated
document.addEventListener('htmx:afterSettle', (event) => {
    const target = event.detail?.target;
    console.log('htmx:afterSettle fired', { target: target?.id, hasContent: !!target?.innerHTML.trim() });

    // Find modal-container directly (might not be the swap target itself)
    const modalContainer = document.getElementById('modal-container');
    if (modalContainer && modalContainer.innerHTML.trim()) {
        console.log('Opening modal with openModal function');
        window.openModal('modal-container');
    }
});

// Clean up modal state when swap is about to happen
document.addEventListener('htmx:beforeSwap', (event) => {
    const target = event.detail?.target;
    console.log('htmx:beforeSwap fired', { target: target?.id });
    if (target && target.id === 'modal-container') {
        // Clear the modal container before new content loads
        target.innerHTML = '';
    }
});

// Clear any open modals before page navigation (HTMX page change)
document.addEventListener('htmx:prompt', () => {
    const openModals = document.querySelectorAll('.modal.is-open');
    openModals.forEach(modal => {
        console.log('Clearing modal before navigation:', modal.id);
        window.closeModal(modal.id);
    });
}, true);

// Also clear modals on htmx requests to different pages
document.addEventListener('htmx:beforeRequest', (event) => {
    const target = event.detail?.target;
    // Only clear modals if this is NOT a modal-container request
    if (target && target.id !== 'modal-container') {
        const openModals = document.querySelectorAll('.modal.is-open');
        openModals.forEach(modal => {
            console.log('Clearing modal before page navigation:', modal.id);
            window.closeModal(modal.id);
        });
    }
}, true);