document.addEventListener('DOMContentLoaded', () => {
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

    document.addEventListener('click', (event) => {
        if (event.target.dataset.modalOpen) {
            openModal(event.target.dataset.modalOpen);
        }

        if (event.target.dataset.modalClose) {
            closeModal(event.target.dataset.modalClose);
        }
    });

    document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape') {
            const openModal = document.querySelector('.modal.is-open');
            if (openModal) {
                closeModal(openModal.id);
            }
        }
    });
});