const WEB_CACHE_PREFIX = 'loc-web-assets-';
const VERSION = new URL(self.location.href).searchParams.get('v') || 'dev';
const CACHE_NAME = `${WEB_CACHE_PREFIX}${VERSION}`;

self.addEventListener('install', (event) => {
    event.waitUntil(self.skipWaiting());
});

self.addEventListener('activate', (event) => {
    event.waitUntil((async () => {
        const names = await caches.keys();
        await Promise.all(names
            .filter((name) => name.startsWith(WEB_CACHE_PREFIX) && name !== CACHE_NAME)
            .map((name) => caches.delete(name)));
        await self.clients.claim();
    })());
});

self.addEventListener('message', (event) => {
    const version = event.data && event.data.type === 'loc-web-cache-version'
        ? String(event.data.version || '')
        : '';
    if (!version) {
        return;
    }

    const keep = `${WEB_CACHE_PREFIX}${version}`;
    event.waitUntil((async () => {
        const names = await caches.keys();
        await Promise.all(names
            .filter((name) => name.startsWith(WEB_CACHE_PREFIX) && name !== keep)
            .map((name) => caches.delete(name)));
    })());
});

self.addEventListener('fetch', (event) => {
    if (!shouldCacheRequest(event.request)) {
        return;
    }

    event.respondWith((async () => {
        const cache = await caches.open(CACHE_NAME);
        const cached = await cache.match(event.request);
        if (cached) {
            return cached;
        }

        const response = await fetch(event.request);
        if (response && response.ok) {
            cache.put(event.request, response.clone());
        }
        return response;
    })());
});

function shouldCacheRequest(request) {
    if (!request || request.method !== 'GET') {
        return false;
    }

    const url = new URL(request.url);
    if (url.origin !== self.location.origin || !url.pathname.startsWith('/assets/')) {
        return false;
    }

    return /\.(?:js|css|webmanifest|png|svg|woff|woff2)$/i.test(url.pathname);
}
