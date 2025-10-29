// Prevent redeclaration if script is loaded multiple times
if (typeof openModal === 'undefined') {
    const openModal = (modalId) => {
        const modal = document.getElementById(modalId);
        if (modal) {
            modal.classList.add('is-open');
            document.body.style.overflow = 'hidden';
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
document.addEventListener('htmx:afterSwap', (event) => {
    const target = event.detail?.target;
    if (target && target.id === 'modal-container') {
        // Check if there's actual content in the modal
        if (target.innerHTML.trim()) {
            window.openModal(target.id);
        }
    }
});

// Clean up modal state when it's cleared
document.addEventListener('htmx:beforeSwap', (event) => {
    const target = event.detail?.target;
    if (target && target.id === 'modal-container') {
        window.closeModal(target.id);
    }
});