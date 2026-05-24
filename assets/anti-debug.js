(() => {
    if (window.__LOC_ANTI_DEBUG_INSTALLED__) {
        return;
    }
    window.__LOC_ANTI_DEBUG_INSTALLED__ = true;

    let locked = false;
    let warnedAt = 0;

    function isTrustedAppWebView() {
        return !!window.LocationBridge;
    }

    function riskMessage() {
        return '检测到调试或开发者工具环境。请关闭开发者工具后重新打开页面。';
    }

    function lockPage() {
        if (locked || isTrustedAppWebView()) {
            return;
        }
        locked = true;

        const overlay = document.createElement('div');
        overlay.className = 'debug-block-overlay';
        overlay.innerHTML = `
            <div class="debug-block-card" role="alertdialog" aria-modal="true">
                <h2>环境风险</h2>
                <p>${riskMessage()}</p>
            </div>
        `;
        document.documentElement.classList.add('debug-blocked');
        document.body.append(overlay);
        window.requestAnimationFrame(() => overlay.classList.add('is-visible'));
    }

    function warn() {
        const now = Date.now();
        if (now - warnedAt < 1200) {
            lockPage();
            return;
        }
        warnedAt = now;

        if (typeof window.showSimplePopup === 'function') {
            window.showSimplePopup('环境风险', riskMessage(), {
                closeText: '我知道了',
                onClose: lockPage,
            });
            return;
        }

        lockPage();
    }

    function blockShortcut(event) {
        const key = String(event.key || '').toLowerCase();
        const code = String(event.code || '').toLowerCase();
        const blocked = key === 'f12'
            || code === 'f12'
            || (event.ctrlKey && event.shiftKey && ['i', 'j', 'c', 'k'].includes(key))
            || (event.metaKey && event.altKey && ['i', 'j', 'c'].includes(key))
            || (event.ctrlKey && ['u', 's'].includes(key));

        if (!blocked) {
            return;
        }

        event.preventDefault();
        event.stopPropagation();
        event.stopImmediatePropagation();
        warn();
    }

    ['keydown', 'keypress', 'keyup'].forEach((type) => {
        document.addEventListener(type, blockShortcut, true);
        window.addEventListener(type, blockShortcut, true);
    });

    document.addEventListener('contextmenu', (event) => {
        event.preventDefault();
        event.stopPropagation();
        warn();
    }, true);

    window.addEventListener('devtoolschange', () => lockPage(), true);

    window.setInterval(() => {
        if (isTrustedAppWebView()) {
            return;
        }

        const widthGap = Math.abs(window.outerWidth - window.innerWidth);
        const heightGap = Math.abs(window.outerHeight - window.innerHeight);
        if (widthGap > 160 || heightGap > 160) {
            lockPage();
        }
    }, 5000);

    window.installAntiDebugGuards = () => {};
})();
