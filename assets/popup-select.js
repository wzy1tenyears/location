(() => {
    const enhanced = new WeakMap();

    function selectedText(select) {
        const option = select.options[select.selectedIndex];
        return option ? option.textContent.trim() : '请选择';
    }

    function selectTitle(select) {
        const label = select.closest('label');
        const labelText = label ? Array.from(label.childNodes)
            .filter((node) => node.nodeType === Node.TEXT_NODE || node.tagName === 'SPAN')
            .map((node) => node.textContent.trim())
            .filter(Boolean)[0] : '';

        return select.getAttribute('aria-label') || labelText || '选择';
    }

    function closeOverlay(overlay) {
        overlay.classList.remove('is-visible');
        window.setTimeout(() => overlay.remove(), 200);
    }

    function openSelect(select, button) {
        if (select.disabled) {
            return;
        }

        const overlay = document.createElement('div');
        overlay.className = 'popup-select-overlay';

        const card = document.createElement('div');
        card.className = 'popup-select-card';
        card.setAttribute('role', 'dialog');
        card.setAttribute('aria-modal', 'true');

        const heading = document.createElement('h2');
        heading.textContent = selectTitle(select);

        const list = document.createElement('div');
        list.className = 'popup-select-list';

        Array.from(select.options).forEach((option) => {
            const item = document.createElement('button');
            item.type = 'button';
            item.className = 'popup-select-option';
            item.textContent = option.textContent;
            item.disabled = option.disabled;
            item.setAttribute('aria-selected', option.selected ? 'true' : 'false');

            item.addEventListener('click', () => {
                if (select.value === option.value) {
                    closeOverlay(overlay);
                    return;
                }

                select.value = option.value;
                updateButton(select, button);
                closeOverlay(overlay);
                window.setTimeout(() => {
                    select.dispatchEvent(new Event('change', { bubbles: true }));
                }, 0);
            });
            list.append(item);
        });

        overlay.addEventListener('click', (event) => {
            if (event.target === overlay) {
                closeOverlay(overlay);
            }
        });

        function onKeydown(event) {
            if (!document.body.contains(overlay)) {
                document.removeEventListener('keydown', onKeydown);
                return;
            }

            if (event.key === 'Escape') {
                closeOverlay(overlay);
                document.removeEventListener('keydown', onKeydown);
            }
        }
        document.addEventListener('keydown', onKeydown);

        card.append(heading, list);
        overlay.append(card);
        document.body.append(overlay);
        window.requestAnimationFrame(() => overlay.classList.add('is-visible'));

        const selected = list.querySelector('[aria-selected="true"]') || list.querySelector('button');
        if (selected) {
            selected.focus();
        }
    }

    function openPopupDialog({ title, sections = [], closeText = '关闭', onClose = null } = {}) {
        const overlay = document.createElement('div');
        overlay.className = 'popup-select-overlay';
        let closed = false;

        const card = document.createElement('div');
        card.className = 'popup-select-card popup-dialog-card';
        card.setAttribute('role', 'dialog');
        card.setAttribute('aria-modal', 'true');

        const heading = document.createElement('h2');
        heading.textContent = title || '提示';

        const body = document.createElement('div');
        body.className = 'popup-dialog-body';

        sections.forEach((section) => {
            if (section.title) {
                const sectionTitle = document.createElement('h3');
                sectionTitle.textContent = section.title;
                body.append(sectionTitle);
            }

            (section.paragraphs || []).forEach((text) => {
                const paragraph = document.createElement('p');
                paragraph.textContent = text;
                body.append(paragraph);
            });
        });

        const actions = document.createElement('div');
        actions.className = 'popup-dialog-actions';

        const closeButton = document.createElement('button');
        closeButton.type = 'button';
        closeButton.textContent = closeText;
        function closeDialog() {
            if (closed) {
                return;
            }

            closed = true;
            closeOverlay(overlay);
            if (typeof onClose === 'function') {
                window.setTimeout(onClose, 210);
            }
        }

        closeButton.addEventListener('click', closeDialog);

        actions.append(closeButton);

        overlay.addEventListener('click', (event) => {
            if (event.target === overlay) {
                closeDialog();
            }
        });

        function onKeydown(event) {
            if (!document.body.contains(overlay)) {
                document.removeEventListener('keydown', onKeydown);
                return;
            }

            if (event.key === 'Escape') {
                closeDialog();
                document.removeEventListener('keydown', onKeydown);
            }
        }
        document.addEventListener('keydown', onKeydown);

        card.append(heading, body, actions);
        overlay.append(card);
        document.body.append(overlay);
        window.requestAnimationFrame(() => overlay.classList.add('is-visible'));
        closeButton.focus();
    }

    function updateButton(select, button) {
        button.textContent = selectedText(select);
        button.disabled = select.disabled;
    }

    function enhanceSelect(select) {
        if (enhanced.has(select) || select.hidden || select.dataset.popupSelect === 'off') {
            return;
        }

        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'popup-select-button';
        button.setAttribute('aria-haspopup', 'dialog');
        select.classList.add('popup-select-native');
        select.insertAdjacentElement('afterend', button);

        button.addEventListener('click', () => openSelect(select, button));
        select.addEventListener('change', () => updateButton(select, button));

        const observer = new MutationObserver(() => updateButton(select, button));
        observer.observe(select, {
            attributes: true,
            childList: true,
            subtree: true,
        });

        enhanced.set(select, { button, observer });
        updateButton(select, button);
    }

    function refreshPopupSelects(root = document) {
        const selects = root.matches && root.matches('select')
            ? [root]
            : Array.from(root.querySelectorAll('select'));

        selects.forEach(enhanceSelect);
        selects.filter((select) => select.classList.contains('popup-select-native')).forEach((select) => {
            const entry = enhanced.get(select);
            if (entry) {
                updateButton(select, entry.button);
            }
        });
    }

    window.refreshPopupSelects = refreshPopupSelects;
    window.showPopupDialog = openPopupDialog;

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => refreshPopupSelects());
    } else {
        refreshPopupSelects();
    }
})();
