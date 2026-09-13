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

// A worker that fails silently is a worker nobody can debug: it has no window, its console is a
// separate DevTools target most people never open, and a rejected promise inside a fetch handler
// surfaces to the page as a bare network error. Everything it says therefore goes BOTH to its own
// console and to every client, which lands it in the F2 console beside the engine's own log.
function say(level, msg) {
  const line = '[prism-sw] ' + msg;
  if (level === 'error') console.error(line); else console.log(line);
  self.clients.matchAll({ includeUncontrolled: true })
      .then(cs => cs.forEach(c => c.postMessage({ type: 'log', level, msg })))
      .catch(() => { });
}

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
  })().catch(err => { say('error', (d.type || 'message') + ' failed — ' + (err && err.message || err)); reply(false); });
  if (e.waitUntil) e.waitUntil(job);
});

self.addEventListener('fetch', e => {
  const m = new URL(e.request.url).pathname.match(/\/epub\/([^/]+)\/(.*)$/);
  if (!m) return; // not a book request — let it hit the network

  // A page frame is the one request whose failure is invisible from the page (a failed navigation shows
  // a foreign-origin error document: no load event, nothing to inspect). Trace those.
  if (e.request.destination === 'document' || e.request.mode === 'navigate') say('info', 'serving ' + m[2]);

  const path = decodeURIComponent(m[2]);
  e.respondWith(caches.match(e.request).then(r => {
    if (r) { if (e.request.destination === 'document') say('info', 'served ' + m[2] + ' (' + (r.headers.get('content-type') || '?') + ')'); return r; }
    say('warn', 'not in cache — ' + path + '  (' + e.request.destination + ')');
    return new Response(`prism-sw: not found — ${path}`, { status: 404 });
  }).catch(err => {
    say('error', 'serve failed — ' + path + ' — ' + (err && err.message || err));
    return new Response('prism-sw: ' + err, { status: 500 });
  }));
});

// A per-page glyph subset once lived here — the pages reference ONE pages/fonts.svg carrying every face of
// the book (7 MB, 6489 glyphs, where a page uses 35), and serving each frame only its own measured 3x
// faster. It was removed on the day it shipped, for two reasons worth keeping.
//
// The benchmark was wrong: it loaded pages as separate TOP-LEVEL documents, where nothing is shared, and
// measured a 290 ms plateau. Prism mounts frames as SIBLINGS in one document, and there Chrome amortises
// the external resource — measured 935 ms for the first frame, then 46/118/164/84/184/153/197 ms. Against
// ~100 ms with a subset, the gain is noise.
//
// And the cost was not: building the index blocks. A Service Worker has ONE thread, so scanning 7 MB on
// the first fonts.svg request froze every request behind it — the rail thumbnails already mounted rendered,
// every page frame after them waited forever, and the reader showed 1014 blank cards. The trace above is
// what finally said so. If this is ever attempted again: it must never sit on the critical path, and it
// must be measured in sibling frames, not in separate tabs.

