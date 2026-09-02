// prism-sw.js — the client-side "server", DURABLE edition.
//
// Serves every request under /epub/<id>/… from the book the main thread handed
// over. The book is persisted in the **Cache API** — the browser terminates an
// idle Service Worker after ~30 s, and an in-memory map dies with it (the
// "prism-sw: not found — page-007" failure once you scroll past what loaded
// early). Caches survive SW restarts; the map does not. One cache per book id.
//
// The main thread unzips (fflate) and sends the file map; we persist and serve.

const TYPE = {
  xhtml: 'application/xhtml+xml', html: 'application/xhtml+xml',
  opf: 'application/oebps-package+xml', ncx: 'application/x-dtbncx+xml',
  xml: 'application/xml', css: 'text/css', js: 'text/javascript',
  svg: 'image/svg+xml', png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg',
  gif: 'image/gif', webp: 'image/webp',
  otf: 'font/otf', ttf: 'font/ttf', woff: 'font/woff', woff2: 'font/woff2',
  mp3: 'audio/mpeg', m4a: 'audio/mp4', oga: 'audio/ogg', wav: 'audio/wav',
  mp4: 'video/mp4', m4v: 'video/mp4', webm: 'video/webm', ogv: 'video/ogg',
  json: 'application/json',
};

const CACHE_PREFIX = 'prism-book-';
const cacheName = id => CACHE_PREFIX + id;
const bookUrl = (id, path) =>
  self.registration.scope + 'epub/' + id + '/' + path.split('/').map(encodeURIComponent).join('/');

const respOf = (path, bytes) => {
  const ext = path.split('.').pop().toLowerCase();
  return new Response(bytes, {
    headers: { 'Content-Type': TYPE[ext] || 'application/octet-stream', 'Cache-Control': 'no-cache' },
  });
};

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));

self.addEventListener('message', e => {
  const d = e.data || {};
  const reply = ok => e.ports[0] && e.ports[0].postMessage({ ok });
  const job = (async () => {
    if (d.type === 'load') {
      // one book era at a time: drop every previous book cache, then persist this one
      for (const n of await caches.keys()) if (n.startsWith(CACHE_PREFIX) && n !== cacheName(d.id)) await caches.delete(n);
      const c = await caches.open(cacheName(d.id));
      await Promise.all(Object.entries(d.files).map(([p, bytes]) => c.put(bookUrl(d.id, p), respOf(p, bytes))));
      reply(true);
    } else if (d.type === 'unload') {
      await caches.delete(cacheName(d.id));
      reply(true);
    } else if (d.type === 'put') {
      const c = await caches.open(cacheName(d.id));
      await c.put(bookUrl(d.id, d.path), respOf(d.path, d.bytes));
      reply(true);
    } else reply(false);
  })().catch(() => reply(false));
  if (e.waitUntil) e.waitUntil(job);
});

self.addEventListener('fetch', e => {
  const m = new URL(e.request.url).pathname.match(/\/epub\/([^/]+)\/(.*)$/);
  if (!m) return; // not a book request — let it hit the network

  e.respondWith(caches.match(e.request).then(r =>
    r || new Response(`prism-sw: not found — ${decodeURIComponent(m[2])}`, { status: 404 })));
});
