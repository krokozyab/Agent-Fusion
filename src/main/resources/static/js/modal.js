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
            document.body.style.overflow = '';
        }
    };

    // Make functions available globally
    window.openModal = openModal;
    window.closeModal = closeModal;
}

document.addEventListener('DOMContentLoaded', () => {
    document.addEventListener('click', (event) => {
        if (event.target.dataset.modalOpen) {
            window.openModal(event.target.dataset.modalOpen);
        }

        if (event.target.dataset.modalClose) {
            window.closeModal(event.target.dataset.modalClose);
        }

        // Close modal when clicking on backdrop
        if (event.target.classList.contains('modal__backdrop')) {
            const modal = event.target.closest('.modal');
            if (modal) {
                window.closeModal(modal.id);
            }
        }
    });

    document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape') {
            const openModals = document.querySelectorAll('.modal.is-open');
            openModals.forEach(modal => {
                window.closeModal(modal.id);
            });
        }
    });
});

// Handle HTMX swap events - open modal after content is loaded
// Use afterSettle instead of afterSwap to ensure DOM is fully updated
document.addEventListener('htmx:afterSettle', (event) => {
    const target = event.detail?.target;
    console.log('htmx:afterSettle fired', { target: target?.id, hasContent: !!target?.innerHTML.trim() });
    if (target && target.id === 'modal-container') {
        // Check if there's actual content in the modal
        if (target.innerHTML.trim()) {
            console.log('Opening modal with openModal function');
            window.openModal(target.id);
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