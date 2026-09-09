/* Solidus Cloud PWA service worker: offline shell + push notification handler.
 * SA2-028: the app shell used to be cache-first with no refresh path - a
 * stale admin UI (role logic, command index) could live in a browser forever.
 * Now: network-first for the HTML shell and app.js (fresh UI whenever the
 * relay is reachable, cache as offline fallback), cache-first only for the
 * immutable static assets, and the cache name is versioned so a new SW
 * release drops old shells atomically. */
'use strict';

const CACHE = 'solidus-cloud-v2';
const PRECACHE = ['/', '/style.css', '/app.js', '/manifest.webmanifest', '/icon.svg'];
const CACHE_FIRST = ['/style.css', '/manifest.webmanifest', '/icon.svg'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(PRECACHE)));
  self.skipWaiting();
});

self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))));
  self.clients.claim();
});

self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET' || e.request.url.includes('/api/')) return;
  const url = new URL(e.request.url);
  // HTML shell + app.js: network-first, cache fallback (SA2-028).
  if (!CACHE_FIRST.includes(url.pathname)) {
    e.respondWith(
      fetch(e.request).then((res) => {
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(e.request, copy));
        return res;
      }).catch(() => caches.match(e.request).then((hit) => hit || caches.match('/')))
    );
    return;
  }
  // Immutable-ish assets: cache-first, refreshed in the background.
  e.respondWith(
    caches.match(e.request).then((hit) => hit || fetch(e.request).then((res) => {
      const copy = res.clone();
      caches.open(CACHE).then((c) => c.put(e.request, copy));
      return res;
    }).catch(() => caches.match('/')))
  );
});

self.addEventListener('push', (e) => {
  let d = {};
  try { d = e.data.json(); } catch {}
  const reason = d.reason || d.code || 'alert';
  e.waitUntil(self.registration.showNotification('Solidus Cloud', {
    body: `${d.serverId || ''} — ${reason}`,
    icon: '/icon.svg',
    tag: d.serverId + ':' + (d.code || reason),
    data: d,
  }));
});

self.addEventListener('notificationclick', (e) => {
  e.notification.close();
  e.waitUntil(clients.matchAll({ type: 'window' }).then((list) => {
    for (const c of list) if ('focus' in c) return c.focus();
    return clients.openWindow('/');
  }));
});
