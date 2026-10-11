const SHELL = 'reader-shell-v1';
const scopes = new Map();
const appPath = (path) => path === '/' || path === '/index.html' || /^\/(static\/|src\/|node_modules\/\.vite\/|@vite\/|@react-refresh$)/.test(path);
self.addEventListener('install', (event) => { event.waitUntil(caches.open(SHELL).then(async (cache) => { await cache.add('/'); await self.skipWaiting(); })); });
self.addEventListener('activate', (event) => { event.waitUntil(self.clients.claim()); });
self.addEventListener('message', (event) => { if (event.source?.id && event.data?.type === 'scope' && /^reader-resources-v1-[A-Za-z0-9_-]+$/.test(event.data.cache)) { const id = event.source.id; scopes.set(id, event.data.cache); event.waitUntil(caches.open(SHELL).then((cache) => cache.put(`/__reader_offline_scope?client=${encodeURIComponent(id)}`, new Response(event.data.cache)))); } });
self.addEventListener('fetch', (event) => {
  const request = event.request; const url = new URL(request.url); if (request.method !== 'GET' || (/\/(reader3)\//.test(url.pathname) && !url.pathname.endsWith('/cover'))) return;
  event.respondWith((async () => {
    try { const response = await fetch(request); if (response.ok && url.origin === self.location.origin && appPath(url.pathname)) { const cache = await caches.open(SHELL); await cache.put(request, response.clone()); } return response; }
    catch (error) { const shell = await caches.open(SHELL); const id = event.clientId; let resourceCache = scopes.get(id); if (!resourceCache && id) { resourceCache = await (await shell.match(`/__reader_offline_scope?client=${encodeURIComponent(id)}`))?.text(); if (resourceCache) scopes.set(id, resourceCache); } const cached = (appPath(url.pathname) ? await shell.match(request) : undefined) || (resourceCache ? await (await caches.open(resourceCache)).match(request) : undefined); if (cached) return cached; if (request.mode === 'navigate') { const root = await shell.match('/'); if (root) return root; } throw error; }
  })());
});
