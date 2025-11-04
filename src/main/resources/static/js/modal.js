// Helper to ensure modal container exists (for cases where script runs before DOM is ready)
function ensureModalContainer() {
    let container = document.getElementById('modal-container');
    if (!container) {
        console.warn('Modal container not found, creating it');
        container = document.createElement('div');
        container.id = 'modal-container';
        container.className = 'modal';
        container.setAttribute('role', 'dialog');
        container.setAttribute('aria-modal', 'true');
        container.setAttribute('aria-hidden', 'true');
        document.body.appendChild(container);
    }
    return container;
}

// Prevent redeclaration if script is loaded multiple times
if (typeof window.openModal === 'undefined') {
    const openModal = (modalId) => {
        const modal = document.getElementById(modalId);
        console.log('openModal called', { modalId, found: !!modal, classList: modal?.classList.toString() });
        if (modal) {
            console.log('Adding is-open class to modal', modalId);
            modal.classList.add('is-open');
            console.log('After adding is-open:', modal.classList.toString());
            console.log('Modal is-open check:', modal.classList.contains('is-open'));
            document.body.style.overflow = 'hidden';
        } else {
            console.error('Modal not found:', modalId);
            console.log('Looking for element with id:', modalId);
            const allModals = document.querySelectorAll('[id]');
            console.log('Available elements with IDs:', Array.from(allModals).map(m => m.id).filter(id => id.includes('modal')));
        }
    };

    const closeModal = (modalId) => {
        const modal = document.getElementById(modalId);
        if (modal) {
            modal.classList.remove('is-open');
            document.body.style.overflow = '';
        }
    };

    // Make functions available globally
    window.openModal = openModal;
    window.closeModal = closeModal;
}

// Attach click handler - OUTSIDE of DOMContentLoaded to avoid race condition
// This needs to be at document level to catch dynamically added elements
document.addEventListener('click', (event) => {
    // Handle open modal buttons
    if (event.target.dataset.modalOpen) {
        console.log('Click detected on modalOpen button', { id: event.target.dataset.modalOpen });
        window.openModal(event.target.dataset.modalOpen);
        return;
    }

    // Handle close modal buttons - use closest() for robustness
    const closeButton = event.target.closest('[data-modal-close]');
    if (closeButton) {
        const modalId = closeButton.dataset.modalClose;
        console.log('Click detected on modalClose button', { id: modalId });
        window.closeModal(modalId);
        return;
    }

    // Close modal when clicking on backdrop
    if (event.target.classList.contains('modal__backdrop')) {
        console.log('Click detected on modal backdrop');
        const modal = event.target.closest('.modal');
        if (modal) {
            window.closeModal(modal.id);
            return;
        }
    }
});

// Attach ESC key handler - OUTSIDE of DOMContentLoaded to avoid race condition
document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
        const openModals = document.querySelectorAll('.modal.is-open');
        console.log('ESC key pressed, open modals found:', openModals.length);
        if (openModals.length > 0) {
            event.preventDefault();
            event.stopPropagation();
            openModals.forEach(modal => {
                console.log('Closing modal on ESC:', modal.id);
                window.closeModal(modal.id);
            });
        }
    }
}, true);

// Handle HTMX swap events - open modal after content is loaded
// Use afterSettle instead of afterSwap to ensure DOM is fully updated
document.addEventListener('htmx:afterSettle', (event) => {
    const target = event.detail?.target;
    console.log('htmx:afterSettle fired', { target: target?.id, targetClass: target?.className, hasContent: !!target?.innerHTML.trim() });

    // Also check if we just swapped something inside modal-container
    if (target && target.id === 'modal-container') {
        console.log('htmx:afterSettle: target is modal-container, content length:', target.innerHTML.trim().length);
        // Check if there's actual content in the modal
        if (target.innerHTML.trim()) {
            console.log('Opening modal with openModal function');
            window.openModal(target.id);
        }
    } else if (target) {
        console.log('htmx:afterSettle: target is NOT modal-container, is:', target.id);
        // Check if the target is inside modal-container
        const modalContainer = target.closest('#modal-container');
        if (modalContainer && modalContainer.innerHTML.trim()) {
            console.log('Target is inside modal-container, opening modal');
            window.openModal(modalContainer.id);
        }
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