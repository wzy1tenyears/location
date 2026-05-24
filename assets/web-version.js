(() => {
    const API_BASE = 'api';
    const CHECK_MS = 60000;
    const RELOAD_PARAM = '_web_v';
    const STORAGE_KEY = 'web_asset_version';
    let timer = null;
    let startupTimer = null;
    let currentVersion = window.__WEB_ASSET_VERSION__ || window.localStorage.getItem(STORAGE_KEY) || '';
    let reloading = false;

    async function checkWebVersion() {
        if (reloading || document.visibilityState === 'hidden') {
            return;
        }

        try {
            const response = await fetch(`${API_BASE}/web_version.php?t=${Date.now()}`, {
                credentials: 'same-origin',
                cache: 'no-store',
                headers: {
                    Accept: 'application/json',
                },
            });
            const payload = await response.json();
            const version = String(payload.version || '');

            if (!response.ok || payload.ok === false || !version) {
                return;
            }

            if (currentVersion && currentVersion !== version) {
                window.localStorage.setItem(STORAGE_KEY, version);
                reloadForWebVersion(version);
                return;
            }

            currentVersion = version;
            window.localStorage.setItem(STORAGE_KEY, version);
        } catch (error) {
            // Version polling must never interrupt normal use.
        }
    }

    function reloadForWebVersion(version) {
        reloading = true;
        const url = new URL(window.location.href);
        url.searchParams.set(RELOAD_PARAM, version);
        window.localStorage.setItem(STORAGE_KEY, version);
        window.location.replace(url.toString());
    }

    function startWebVersionWatcher() {
        stopWebVersionWatcher();
        scheduleWebVersionCheck(8000);
        timer = window.setInterval(() => scheduleWebVersionCheck(), CHECK_MS);
    }

    function stopWebVersionWatcher() {
        if (startupTimer) {
            window.clearTimeout(startupTimer);
            startupTimer = null;
        }
        if (timer) {
            window.clearInterval(timer);
            timer = null;
        }
    }

    function scheduleWebVersionCheck(delay = 0) {
        const run = () => {
            startupTimer = null;
            if (typeof window.requestIdleCallback === 'function') {
                window.requestIdleCallback(() => checkWebVersion(), { timeout: 5000 });
                return;
            }
            window.setTimeout(checkWebVersion, 0);
        };

        if (delay > 0) {
            startupTimer = window.setTimeout(run, delay);
            return;
        }

        run();
    }

    window.AppWebVersion = {
        start: startWebVersionWatcher,
        stop: stopWebVersionWatcher,
        check: checkWebVersion,
    };
    window.startWebVersionWatcher = startWebVersionWatcher;
})();
