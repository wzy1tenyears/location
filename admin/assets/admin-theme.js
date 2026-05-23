(function () {
    const storageKey = 'theme_mode';
    const scrollStorageKey = 'admin_scroll_position';
    const select = document.querySelector('#themeMode');

    if ('scrollRestoration' in window.history) {
        window.history.scrollRestoration = 'manual';
    }

    function rememberScrollPosition() {
        try {
            window.sessionStorage.setItem(scrollStorageKey, JSON.stringify({
                x: window.scrollX || 0,
                y: window.scrollY || 0,
            }));
        } catch (error) {
            // Session storage can be unavailable in restricted WebView modes.
        }
    }

    function restoreScrollPosition() {
        let saved = null;

        try {
            saved = JSON.parse(window.sessionStorage.getItem(scrollStorageKey) || 'null');
            window.sessionStorage.removeItem(scrollStorageKey);
        } catch (error) {
            saved = null;
        }

        if (!saved || typeof saved.y !== 'number') {
            return;
        }

        window.requestAnimationFrame(() => {
            window.scrollTo(saved.x || 0, saved.y || 0);
            window.requestAnimationFrame(() => window.scrollTo(saved.x || 0, saved.y || 0));
        });
    }

    function normalizeThemeMode(mode) {
        return ['system', 'light', 'dark'].includes(mode) ? mode : 'system';
    }

    function applyThemeMode(mode) {
        const normalized = normalizeThemeMode(mode);

        if (normalized === 'system') {
            delete document.documentElement.dataset.theme;
        } else {
            document.documentElement.dataset.theme = normalized;
        }

        try {
            window.localStorage.setItem(storageKey, normalized);
        } catch (error) {
            // Local storage can be unavailable in restricted WebView modes.
        }

        if (select) {
            select.value = normalized;
        }
    }

    let savedMode = 'system';

    try {
        savedMode = window.localStorage.getItem(storageKey) || 'system';
    } catch (error) {
        savedMode = 'system';
    }

    applyThemeMode(savedMode);
    restoreScrollPosition();

    if (select) {
        select.addEventListener('change', () => applyThemeMode(select.value));
    }

    function openConfirmModal(message, onConfirm) {
        const overlay = document.createElement('div');
        overlay.className = 'confirm-overlay';

        const dialog = document.createElement('div');
        dialog.className = 'confirm-dialog';
        dialog.setAttribute('role', 'dialog');
        dialog.setAttribute('aria-modal', 'true');

        const title = document.createElement('h2');
        title.textContent = '确认操作';

        const body = document.createElement('p');
        body.textContent = message || '确认继续？';

        const actions = document.createElement('div');
        actions.className = 'confirm-actions';

        const cancel = document.createElement('button');
        cancel.type = 'button';
        cancel.className = 'secondary';
        cancel.textContent = '取消';

        const confirm = document.createElement('button');
        confirm.type = 'button';
        confirm.className = 'danger';
        confirm.textContent = '确认';

        let closing = false;
        const close = () => {
            if (closing) {
                return;
            }

            closing = true;
            document.removeEventListener('keydown', onKeydown);
            overlay.classList.remove('is-visible');
            window.setTimeout(() => overlay.remove(), 200);
        };
        cancel.addEventListener('click', close);
        overlay.addEventListener('click', (event) => {
            if (event.target === overlay) {
                close();
            }
        });
        function onKeydown(event) {
            if (!document.body.contains(overlay)) {
                document.removeEventListener('keydown', onKeydown);
                return;
            }

            if (event.key === 'Escape') {
                close();
            }
        }
        document.addEventListener('keydown', onKeydown);
        confirm.addEventListener('click', () => {
            close();
            onConfirm();
        });

        actions.append(cancel, confirm);
        dialog.append(title, body, actions);
        overlay.append(dialog);
        document.body.append(overlay);
        window.requestAnimationFrame(() => overlay.classList.add('is-visible'));
        confirm.focus();
    }

    document.addEventListener('submit', rememberScrollPosition, true);
    document.addEventListener('click', (event) => {
        const link = event.target.closest('a[href]');
        if (link && link.target === '' && !link.classList.contains('disabled-link')) {
            rememberScrollPosition();
        }
    }, true);
    document.addEventListener('change', (event) => {
        if (event.target && event.target.form) {
            rememberScrollPosition();
        }
    }, true);

    function bindConfirmForm(form) {
        form.addEventListener('submit', (event) => {
            if (form.dataset.confirmed === '1') {
                form.dataset.confirmed = '';
                return;
            }

            event.preventDefault();
            openConfirmModal(form.dataset.confirm || '确认继续？', () => {
                form.dataset.confirmed = '1';
                form.requestSubmit();
            });
        });
    }

    function openDetailsModal(details) {
        const summary = details.querySelector('summary');
        const overlay = document.createElement('div');
        overlay.className = 'confirm-overlay';

        const dialog = document.createElement('div');
        dialog.className = 'confirm-dialog details-dialog';
        dialog.setAttribute('role', 'dialog');
        dialog.setAttribute('aria-modal', 'true');

        const title = document.createElement('h2');
        title.textContent = summary ? summary.textContent.trim() : '更多操作';

        const body = document.createElement('div');
        body.className = 'details-dialog-body';
        Array.from(details.childNodes).forEach((node) => {
            if (node === summary) {
                return;
            }
            body.append(node.cloneNode(true));
        });
        body.querySelectorAll('.popup-select-button').forEach((button) => button.remove());
        body.querySelectorAll('select.popup-select-native').forEach((nativeSelect) => {
            nativeSelect.classList.remove('popup-select-native');
            nativeSelect.style.removeProperty('height');
            nativeSelect.style.removeProperty('opacity');
            nativeSelect.style.removeProperty('pointer-events');
            nativeSelect.style.removeProperty('position');
            nativeSelect.style.removeProperty('width');
        });
        body.querySelectorAll('form[data-confirm]').forEach(bindConfirmForm);

        const actions = document.createElement('div');
        actions.className = 'confirm-actions';
        const closeButton = document.createElement('button');
        closeButton.type = 'button';
        closeButton.className = 'secondary';
        closeButton.textContent = '关闭';
        let closing = false;
        const close = () => {
            if (closing) {
                return;
            }
            closing = true;
            document.removeEventListener('keydown', onKeydown);
            overlay.classList.remove('is-visible');
            window.setTimeout(() => overlay.remove(), 200);
        };
        closeButton.addEventListener('click', close);
        overlay.addEventListener('click', (event) => {
            if (event.target === overlay) {
                close();
            }
        });
        function onKeydown(event) {
            if (!document.body.contains(overlay)) {
                document.removeEventListener('keydown', onKeydown);
                return;
            }
            if (event.key === 'Escape') {
                close();
            }
        }
        document.addEventListener('keydown', onKeydown);
        actions.append(closeButton);
        dialog.append(title, body, actions);
        overlay.append(dialog);
        document.body.append(overlay);
        if (typeof window.refreshPopupSelects === 'function') {
            window.refreshPopupSelects(body);
        }
        window.requestAnimationFrame(() => overlay.classList.add('is-visible'));
    }

    document.querySelectorAll('form[data-confirm]').forEach(bindConfirmForm);

    document.addEventListener('click', (event) => {
        const summary = event.target.closest('details.row-more > summary');
        if (!summary) {
            return;
        }

        const details = summary.parentElement;
        if (!details) {
            return;
        }

        event.preventDefault();
        details.open = false;
        openDetailsModal(details);
    });
})();
