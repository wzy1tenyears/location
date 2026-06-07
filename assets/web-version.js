(() => {
    const API_BASE = 'api';
    const CHECK_MS = 60000;
    const RELOAD_PARAM = '_web_v';
    const STORAGE_KEY = 'web_asset_version';
    const WEB_CACHE_PREFIX = 'loc-web-assets-';
    let timer = null;
    let startupTimer = null;
    let currentVersion = window.__WEB_ASSET_VERSION__ || window.localStorage.getItem(STORAGE_KEY) || '';
    let reloading = false;
    let inFlight = false;

    cleanupWebAssetCaches(currentVersion);
    if (isAppWebView()) {
        disableWebAssetServiceWorkerInApp();
    } else {
        registerWebAssetCache(currentVersion);
    }

    async function checkWebVersion() {
        if (reloading || inFlight || document.visibilityState === 'hidden') {
            return;
        }

        inFlight = true;
        const controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
        const abortTimer = controller ? window.setTimeout(() => controller.abort(), 3500) : null;
        try {
            const response = await fetch(`${API_BASE}/web_version.php?t=${Date.now()}`, {
                credentials: 'same-origin',
                cache: 'no-store',
                signal: controller ? controller.signal : undefined,
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
                cleanupWebAssetCaches(version);
                notifyWebAssetCache(version);
                reloadForWebVersion(version);
                return;
            }

            currentVersion = version;
            window.localStorage.setItem(STORAGE_KEY, version);
            cleanupWebAssetCaches(version);
            if (isAppWebView()) {
                disableWebAssetServiceWorkerInApp();
            } else {
                registerWebAssetCache(version);
            }
        } catch (error) {
            // Version polling must never interrupt normal use.
        } finally {
            if (abortTimer) {
                window.clearTimeout(abortTimer);
            }
            inFlight = false;
        }
    }

    function reloadForWebVersion(version) {
        reloading = true;
        const url = new URL(window.location.href);
        url.searchParams.set(RELOAD_PARAM, version);
        window.localStorage.setItem(STORAGE_KEY, version);
        window.location.replace(url.toString());
    }

    async function cleanupWebAssetCaches(version) {
        if (!version || !window.caches || typeof window.caches.keys !== 'function') {
            return;
        }

        try {
            const keep = `${WEB_CACHE_PREFIX}${version}`;
            const names = await window.caches.keys();
            await Promise.all(names
                .filter((name) => name.startsWith(WEB_CACHE_PREFIX) && name !== keep)
                .map((name) => window.caches.delete(name)));
        } catch (error) {
            // Cache cleanup is best-effort and must not block the app.
        }
    }

    async function registerWebAssetCache(version) {
        if (!version || isAppWebView() || !('serviceWorker' in navigator)) {
            return;
        }

        try {
            const scriptUrl = `/service-worker.js?v=${encodeURIComponent(version)}`;
            const registration = await navigator.serviceWorker.register(scriptUrl, { scope: '/' });
            notifyWebAssetCache(version, registration);
        } catch (error) {
            // Service Worker support varies by embedded WebView version.
        }
    }

    async function disableWebAssetServiceWorkerInApp() {
        if (!('serviceWorker' in navigator) || typeof navigator.serviceWorker.getRegistrations !== 'function') {
            return;
        }

        try {
            const registrations = await navigator.serviceWorker.getRegistrations();
            await Promise.all(registrations
                .filter((registration) => registration && registration.active && String(registration.active.scriptURL || '').includes('/service-worker.js'))
                .map((registration) => registration.unregister()));
        } catch (error) {
            // Android WebView Service Worker behavior varies; keep app startup non-blocking.
        }
    }

    function isAppWebView() {
        return !!window.LocationBridge;
    }

    function notifyWebAssetCache(version, registration = null) {
        const worker = registration && (registration.active || registration.waiting || registration.installing)
            ? registration.active || registration.waiting || registration.installing
            : navigator.serviceWorker && navigator.serviceWorker.controller
                ? navigator.serviceWorker.controller
                : null;
        if (worker) {
            worker.postMessage({ type: 'loc-web-cache-version', version });
        }
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
        schedule: scheduleWebVersionCheck,
        cleanupCaches: cleanupWebAssetCaches,
    };
    window.startWebVersionWatcher = startWebVersionWatcher;
})();
