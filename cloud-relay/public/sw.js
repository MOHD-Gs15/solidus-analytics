/* Solidus Cloud PWA service worker: offline shell + push notification handler. */
'use strict';

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open('solidus-cloud-v1').then((c) => c.addAll(['/', '/style.css', '/app.js', '/manifest.webmanifest', '/icon.svg'])));
  self.skipWaiting();
});

self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== 'solidus-cloud-v1').map((k) => caches.delete(k)))));
  self.clients.claim();
});

self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET' || e.request.url.includes('/api/')) return;
  e.respondWith(
    caches.match(e.request).then((hit) => hit || fetch(e.request).then((res) => {
      const copy = res.clone();
      caches.open('solidus-cloud-v1').then((c) => c.put(e.request, copy));
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
