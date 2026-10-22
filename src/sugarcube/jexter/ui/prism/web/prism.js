// prism.js — the Prism chassis (100% client): the reader every tool builds on.
//
// It reads ANY fixed-layout EPUB through a Service Worker (prism-sw.js) that serves the unzipped book
// under a virtual /epub/<id>/ scope, so the reader and every page frame fetch ordinary URLs — one render
// path, no asset rewriting. The book never leaves the browser unless a tool posts it to the engine.
//
//   open      file → fflate unzip → the worker → container.xml → OPF → spine + nav
//   stage     a card per page, its frame mounted on demand (a window of FRAME_WINDOW around the reader)
//   rail      Pages (pictures, shot.js) · Contents (nav) · Search (search.js)
//   layout    scroll · single page · two-page spread (persisted) · zoom anchored under the pointer
//   seam      window.prism: state, events (events.js), the tool registry (tools.js), the verbs below,
//             and the providers the engine answers (hooks: openSource · highlight · ttsNodes)
//   console   F2 (console.js)
import '/shared/js/icons.js';                          // the apps' icon set (icons.add): registered before boot() draws
import { boot, theme, toast, makeDropZone, makeDialog, makeMenu, makeSplitter } from '/shared/vendor/qry/qry-app.js';
import { unzip } from '/shared/vendor/fflate.js';
import { ocdRefusal } from '/shared/js/ocd.js';
import { on, emit } from './events.js';
import { registerTool, setTool, ribbon, keyFor, currentTool, devMode, setDevMode } from './tools.js';
import { logLine, toggleLog, installLogCapture, wireConsole } from './console.js';
import { wireSearch, clearSearch, clearAllHl, markRect, fold } from './search.js';
import { fileNameOf } from './backend.js';

// The chassis draws thumbnails, so it speaks the two shared authorities: `book` for "which member is
// page i", `shots` for "a small picture of it". They are imported DYNAMICALLY, in main(), and that is
// load-bearing: `book.js` captures `window.prism` at module scope, and a static import here would put
// it in THIS module's graph — evaluated before this file's own body, so before `window.prism` exists.
// The tools get away with a static import because their <script> tag runs after the chassis has
// already published the seam. Measured as `Cannot read properties of undefined (reading 'state')`
// from book.js, with the rail silently empty.
let book = null, shots = null;

const state = {
  id: null, pages: [], toc: [], current: -1, zoom: 1, lang: 'en',
  mode: localStorage.getItem('prism_mode') || 'scroll',   // 'scroll' | 'page' | 'spread'
  files: null, root: '', opfPath: '', opfDir: '', aug: null,   // in-memory book + augmentation model
};
let seq = 0;

window.prism = { state, hooks: {}, on };        // the tool seam: state · providers · events
// EVENTS (multicast, chassis-emitted): 'tool'(id) · 'book'(state) · 'close'() ·
// 'page'(idx) · 'frame'(iframe, idx) · 'mode'(layout) · 'export'(target) — the last one raised by
// whoever exports, through P.exported(to). Subscribe with P.on(evt, cb) → off().
// PROVIDERS (singular, hooks.*): openSource · highlight · ttsNodes — one implementation
// answers the chassis (engine.js owns them today).
// The window title with no book: the same string index.html carries, so closing a document returns the
// window to exactly what opening the app showed — one authority, declared ABOVE the code that reads it.
const TITLE = 'PRISM — the OCD document workbench';

// Footer status line (jexter-style): one place sets it; its <i data-icon> draws itself (qry's icons.watch, which boot() starts).
function setStatus(html) {
  const el = $.opt('#status'); if (!el) return;
  el.innerHTML = html;
}

/** The app's dialogs and sheets (native <dialog>, index.html), each made modal ONCE — backdrop, Escape,
 *  [data-close], focus back. P.dialog(id).open() / .close() for the tools. */
const dialogs = new Map();
function dialog(id) {
  if (!dialogs.has(id)) { const el = $.opt('#' + id); dialogs.set(id, el ? makeDialog(el) : { open() {}, close() {}, isOpen: () => false }); }
  return dialogs.get(id);
}
window.prism.dialog = dialog;
window.prism.setStatus = setStatus;

// Unit preference (Settings drawer) + page-dims readout — Prism's footer dims, here.
const UNIT = { pt: [1, 0], mm: [25.4 / 72, 1], cm: [2.54 / 72, 2], in: [1 / 72, 2] };
let unit = localStorage.getItem('prism_unit') || 'pt';
function setUnit(u) {
  unit = (u in UNIT || u === 'px') ? u : 'pt';
  localStorage.setItem('prism_unit', unit);
}
window.prism.toUnit = (v) => unit === 'px' ? Math.round(v * state.zoom) + ' px'
  : (v * (UNIT[unit] || UNIT.pt)[0]).toFixed((UNIT[unit] || UNIT.pt)[1]) + ' ' + unit;

window.prism.ribbon = ribbon;
window.prism.keyFor = keyFor;
window.prism.exported = (to) => emit('export', to);   // the book left the machine, as `to`
window.prism.registerTool = registerTool;
window.prism.setTool = setTool;
window.prism.tool = currentTool;

boot({ title: 'Prism', theme: { persist: 'prism:theme' }, ready: main });

async function main() {
  ({ book }  = await import('/shared/js/book.js'));     // after the seam exists — see the note on top
  ({ shots } = await import('/shared/js/shot.js'));
  watchMembers();
  installLogCapture();                                              // capture console.* into the footer console
  if (localStorage.getItem('prism:theme') == null) theme.mode('dark'); // dark by default, like Prism
  wireChrome();
  try { await initSW(); }
  catch (e) { toast(e.message, { tone: 'danger' }); return; }   // no Service Worker → nothing can be served

  // Optional: ?epub=<same-origin url> opens a book a host serves (still client-side). Its name is the one
  // the response gives (Content-Disposition), else the URL's last segment — a host's route for a result
  // ends in "result", which names nothing a reader could recognise.
  const src = new URLSearchParams(location.search).get('epub');
  if (src) {
    try {
      const res = await fetch(src);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      openEpub(await res.arrayBuffer(), fileNameOf(res.headers.get('content-disposition')) || src.split('/').pop());
    } catch (e) { toast(`Could not fetch "${src}": ${e.message}`, { tone: 'danger' }); }
  }
}

/* -- Service Worker: the client-side server ------------------------- */

async function initSW() {
  if (!('serviceWorker' in navigator))
    throw new Error('Service Worker unavailable — serve over https or localhost (not file://).');
  await navigator.serviceWorker.register('./prism-sw.js');
  // The worker has no window and a console few people open: what it says lands in F2, beside the rest.
  navigator.serviceWorker.addEventListener('message', e => {
    if (e.data?.type === 'log') logLine(e.data.level || 'info', e.data.msg, 'worker');
  });
  await navigator.serviceWorker.ready;
  if (!navigator.serviceWorker.controller)
    await new Promise(res => {
      navigator.serviceWorker.addEventListener('controllerchange', res, { once: true });
      setTimeout(res, 800);
    });
}

function swSend(msg) {
  return new Promise((resolve, reject) => {
    const ctrl = navigator.serviceWorker.controller;
    if (!ctrl) return reject(new Error('Service Worker not controlling the page yet.'));
    const ch = new MessageChannel();
    ch.port1.onmessage = e => (e.data && e.data.ok) ? resolve() : reject(new Error('Service Worker rejected the book.'));
    ctrl.postMessage(msg, [ch.port2]);
  });
}

// Update / add one file in the SW's live copy (edit mode) — best-effort.
/** THE member write: in-memory book (edit + export truth) + live SW copy (display
 *  truth). Awaitable. Every write in the app — chassis or tool — goes through it. */
function putMember(path, bytes) {
  state.files[path] = bytes;
  return swPut(path, bytes);
}

function swPut(path, bytes) {
  return new Promise(resolve => {
    const ctrl = navigator.serviceWorker.controller;
    if (!ctrl) return resolve();
    const ch = new MessageChannel();
    ch.port1.onmessage = () => resolve();
    ctrl.postMessage({ type: 'put', id: state.id, path, bytes }, [ch.port2]);
  });
}

/* -- Open: file -> unzip -> serve -> load --------------------------- */

// What only an engine can open, by SIGNATURE, never by name: %PDF, PNG, JPEG, TIFF (both byte orders).
// The engine sniffs the same four bytes (Conversion), so the two can never disagree on what a file is.
const isSource = h => (h[0] === 0x25 && h[1] === 0x50 && h[2] === 0x44 && h[3] === 0x46)
  || (h[0] === 0x89 && h[1] === 0x50 && h[2] === 0x4E && h[3] === 0x47)
  || (h[0] === 0xFF && h[1] === 0xD8 && h[2] === 0xFF)
  || (h[0] === 0x49 && h[1] === 0x49 && h[2] === 0x2A && h[3] === 0x00)
  || (h[0] === 0x4D && h[1] === 0x4D && h[2] === 0x00 && h[3] === 0x2A);
// The names a picker or a drop offers to it — the gate before the bytes are read.
const OPENABLE = /\.(epub|pdf|png|jpe?g|tiff?)$/i;

async function openEpub(buffer, name) {
  state.name = name || state.name || 'document';
  const head = new Uint8Array(buffer.slice(0, 4));
  if (isSource(head)) {                                   // a PDF or an image: the engine converts it, the chassis cannot
    if (window.prism.hooks.openSource) {
      clearSurfaces(); $('body').cls('+is-loading');
      return window.prism.hooks.openSource(buffer, name);
    }
    toast('This is a PDF or an image — no conversion engine is available here. Open an .epub / .ocd.epub.', { tone: 'warn' });
    return;
  }
  setStatus('<span class="jx-spin"></span> Opening\u2026');
  // The old book leaves the screen NOW, not when the new one lands — and `is-loading` says a
  // document is coming, so the drop prompt never flashes back between the two.
  clearSurfaces(); $('body').cls('+is-loading');
  let files;
  try { files = await new Promise((res, rej) => unzip(new Uint8Array(buffer), (err, out) => err ? rej(err) : res(out))); }
  catch { toast('Not a valid ZIP/EPUB archive.', { tone: 'danger' }); setStatus('<i data-icon="sparkles"></i> Ready'); return; }
  const refused = ocdRefusal(files);
  if (refused) { toast(refused, { tone: 'danger' }); $('body').cls('-is-loading'); setStatus('<i data-icon="sparkles"></i> Ready'); return; }

  const prev = state.id;
  const id = 'b' + (++seq);
  try {
    await swSend({ type: 'load', id, files });
    state.files = files;   // book in memory (edit + export); loadEpub reads the sidecar from it
    await loadEpub(id, name);
    if (prev) swSend({ type: 'unload', id: prev }).catch(() => {});
    setStatus('<i data-icon="check"></i> Ready');
  } catch (e) {
    toast(`Could not open the EPUB: ${e.message}`, { tone: 'danger' });
    swSend({ type: 'unload', id }).catch(() => {});
    setStatus('<i data-icon="sparkles"></i> Ready');
  }
}

/* -- Close: clear the book, back to the empty state ---------------- */

function closeBook() {
  shots?.clear();
  window.prism.ttsStop?.();          // silence any narration before the book goes
  setTool('home');
  emit('close');
  if (state.id) swSend({ type: 'unload', id: state.id }).catch(() => {});
  state.id = null; state.pages = []; state.toc = []; state.current = -1;
  state.files = null; state.opfPath = ''; state.opfDir = ''; state.root = ''; state.aug = null;
  clearSurfaces();
  $('body').cls('-has-book -is-loading');   // a real close: back to "no document"
  setStatus('<i data-icon="sparkles"></i> Ready');
  document.title = TITLE;
}

/* -- Load: container.xml -> OPF -> spine + nav ---------------------- */

async function loadEpub(id, name) {
  const root = new URL(`./epub/${id}/`, location.href).href;

  const container = await fetchXml(root + 'META-INF/container.xml');
  const opfPath = container.querySelector('rootfile')?.getAttribute('full-path');
  if (!opfPath) throw new Error('no rootfile in container.xml');
  const opfUrl = new URL(opfPath, root).href;
  const opf = await fetchXml(opfUrl);

  // Fixed-layout only — PRISM is the faithful facsimile, not a reflow engine.
  const layoutMeta = [...opf.getElementsByTagName('meta')]
    .find(m => m.getAttribute('property') === 'rendition:layout');
  if (layoutMeta && /reflow/i.test(layoutMeta.textContent))
    throw new Error('reflowable EPUB — PRISM renders fixed-layout only');

  state.id = id;
  state.zoom = 1;   // every book opens at 100%
  state.root = root;
  state.opfPath = opfPath;
  state.opfDir = opfPath.includes('/') ? opfPath.replace(/[^/]+$/, '') : '';   // '' or 'OEBPS/'
  state.lang = text1(opf, 'dc\\:language') || 'en';
  // The APP first, then the document — the way a window title is read: what this is, then which one.
  // And the FILE name, not `dc:title`: a converted PDF that declared no title gets its content id there
  // ("37d9dc4f"), which named nothing a reader could recognise. The metadata title is the fallback, for
  // a book whose file name is the meaningless one.
  document.title = 'PRISM — ' + (state.name || text1(opf, 'dc\\:title') || text1(opf, 'title') || 'document');

  const items = [...opf.getElementsByTagName('item')];
  const href = {}; items.forEach(it => { href[it.getAttribute('id')] = new URL(it.getAttribute('href'), opfUrl).href; });
  const spine = [...opf.getElementsByTagName('itemref')].map(r => href[r.getAttribute('idref')]).filter(Boolean);
  if (!spine.length) throw new Error('empty spine');
  const navItem = items.find(it => (it.getAttribute('properties') || '').split(/\s+/).includes('nav'));

  state.pages = await prefetch(spine);
  await loadNav(navItem ? href[navItem.getAttribute('id')] : null);

  buildRails();
  buildScroll();
  observePages();
  $('body').cls('+has-book -is-loading');   // the stage owns the screen from here
  emit('book', state);   // tool seam: OCD-EPUB awareness (text layer, rails, …)
  // Open at page ONE. Resuming looked considerate and was not: posKey is derived from the document title
  // and the page count, so two different books that share both resume onto each other, and opening a file
  // you have just converted drops you in its middle with no way to know why. A book opens at its cover.
  state.current = 0;
  setMode(state.mode);   // data-mode + segmented + layout + pager
  // At 100% a page is wider than a phone: there, a book opens at the stage's width instead.
  if (state.pages[0].w > $('#epub-scroll').clientWidth) fitZoom('width');
}

// ONE pass per page: size + rotation (layout) + concatenated text (search).
//
// A stored page is the UNROTATED document (FORMAT §B2): its width/height are its own, and `data-rotate`
// says how it must be turned to be read — page metadata, exactly as PDF's /Rotate, so rotating a page
// touches one attribute and no element. THE VIEWER TURNS IT: `pw`/`ph` are the page's own size, `w`/`h`
// the visual one the layout reserves.
async function prefetch(urls) {
  const pages = new Array(urls.length);
  let i = 0;
  const worker = async () => {
    while (i < urls.length) {
      const idx = i++;
      const page = { href: urls[idx], label: String(idx + 1), w: 468, h: 780, rot: 0, text: '' };
      try {
        const svg = (await fetchHtml(urls[idx])).querySelector('svg');
        if (svg) {
          const vb = (svg.getAttribute('viewBox') || '').split(/[\s,]+/).map(Number);
          if (vb.length === 4) { page.w = vb[2] || page.w; page.h = vb[3] || page.h; }
          page.w = +svg.getAttribute('width')  || page.w;
          page.h = +svg.getAttribute('height') || page.h;
          const rot = ((+svg.getAttribute('data-rotate') || 0) % 360 + 360) % 360;
          if (rot) {
            page.rot = rot;
            page.pw = page.w; page.ph = page.h;                          // the page's own size
            if (rot === 90 || rot === 270) { page.w = page.ph; page.h = page.pw; }
          }
          page.text = [...svg.querySelectorAll('text')].map(t => t.textContent).join(' ').replace(/\s+/g, ' ').trim();
        }
      } catch { /* keep defaults */ }
      pages[idx] = page;
    }
  };
  await Promise.all(Array.from({ length: Math.min(8, urls.length) }, worker));
  return pages;
}

async function loadNav(navUrl) {
  if (!navUrl) return;
  let doc; try { doc = await fetchHtml(navUrl); } catch { return; }

  const byFile = {}; state.pages.forEach((p, i) => { byFile[fileOf(p.href)] = i; });
  doc.querySelectorAll('nav[epub\\:type="page-list"] a').forEach(a => {
    const i = byFile[fileOf(a.getAttribute('href'))];
    if (i != null) state.pages[i].label = a.textContent.trim() || state.pages[i].label;
  });

  const ol = doc.querySelector('nav[epub\\:type="toc"] > ol');
  state.toc = ol ? parseToc(ol, navUrl) : [];
}

function parseToc(ol, base) {
  return [...ol.children].filter(li => li.tagName.toLowerCase() === 'li').map(li => {
    const a = li.querySelector(':scope > a'), sub = li.querySelector(':scope > ol');
    return {
      href: a ? new URL(a.getAttribute('href'), base).href : null,
      label: a ? a.textContent.trim() : '',
      children: sub ? parseToc(sub, base) : [],
    };
  });
}

/* -- Rails ---------------------------------------------------------- */

/**
 * Empty every surface that belongs to the document on screen — stage, rail, contents, search.
 *
 * <p>Shared by {@code closeBook} and by the open path, because they clear the same things and must
 * not drift. What each ADDS differs: closing goes back to "no document", opening announces that one
 * is coming (see {@code is-loading}).
 */
function clearSurfaces() {
  mounted.clear();                               // no frame of the previous book stays live
  $.opt('#epub-stage')?.empty();
  $.opt('#page-list')?.empty();
  $.opt('#toc-list')?.empty();
  clearSearch();
  shots?.clear();                                // the cached pictures are of the document leaving
  state.pages = []; state.toc = []; state.current = -1;
}

function buildRails() {
  const pl = $('#page-list').empty();
  state.pages.forEach((pg, idx) => {
    const li = $.create('li');
    const a = $.create('a', { class: 'pg-item', onclick: () => goTo(idx) }).attr('data-page', idx);
    const thumb = $.create('div', { class: 'pg-thumb' }).attr('data-w', pg.w).attr('data-h', pg.h);
    thumb.mount(a);                              // the frame is made when the thumbnail is wanted
    $.create('span', { class: 'pg-num', text: pg.label }).mount(a);
    a.mount(li); li.mount(pl);
  });
  sizeThumbs();
  observeThumbs();
  renderToc(state.toc, $('#toc-list').empty());
  clearSearch();
}

// Thumbnails fill the rail: their scale follows the panel width, so dragging the
// splitter resizes them live. One ResizeObserver, rAF-throttled, recomputes all.
let thumbRO = null, thumbTick = false;
function sizeThumbs() {
  const pl = $.opt('#page-list'); if (!pl) return;
  const tw = Math.max(80, pl.clientWidth - 10);
  $.all('#page-list .pg-thumb').forEach(t => {
    // The thumbnail carries the page's own SHAPE (the visual one — a turned page is landscape), and
    // the picture inside simply fills it. Computing a pixel height from a nominal width was right only
    // while every page had the same format.
    const w = +t.getAttribute('data-w') || 468, h = +t.getAttribute('data-h') || 780, s = tw / w;
    t.style.setProperty('--tw', tw + 'px');
    t.style.setProperty('--th', Math.round(h * s) + 'px');
  });
  if (!thumbRO) {
    thumbRO = new ResizeObserver(() => {
      if (thumbTick) return; thumbTick = true;
      requestAnimationFrame(() => { thumbTick = false; sizeThumbs(); });
    });
    thumbRO.observe(pl);
  }
}

// A thumbnail is a PICTURE, not a document. It used to be a real page frame — the same SW-served
// render, which was faithful and cost a whole document per thumbnail: DOM, styles and the book's glyph
// library, mounted and torn down as the rail scrolls. `shot.js` draws the page once into a canvas and
// hands back a blob URL, cached per member for the whole app, so the rail and the Pages light table
// now show the same picture and neither pays twice for it.
function observeThumbs() {
  const io = new IntersectionObserver(es => {
    for (const e of es) {
      if (!e.isIntersecting) continue;
      io.unobserve(e.target);
      paintThumb(e.target, +e.target.parentElement.getAttribute('data-page'));
    }
  }, { root: $('#tab-pages'), rootMargin: '400px 0px' });
  $.all('#page-list .pg-thumb').forEach(t => io.observe(t));
}

/** Draw one rail thumbnail — idempotent, so a refresh after a page was rewritten is just this again. */
async function paintThumb(thumb, idx) {
  const pg = state.pages[idx]; if (!thumb || !pg || !shots) return;
  let img = thumb.querySelector('img.pg-shot');
  if (!img) {
    img = document.createElement('img');
    img.className = 'pg-shot';
    img.alt = '';
    img.draggable = false;                       // an <img> drags natively and would hijack the rail
    thumb.appendChild(img);
  }
  const src = await shots.url(idx, { w: pg.pw || pg.w, h: pg.ph || pg.h, rot: pg.rot || 0, width: 220 });
  if (src && img.isConnected) { img.src = src; img.classList.add('ready'); }
}

/** A page member was written (an edit, a crop, a rotation): its picture is stale and it is the only
 *  one. This replaces the interval that used to reload every dirty thumbnail's iframe every 1.5 s —
 *  polling to notice a change the write itself already announced. */
function watchMembers() {
  book.onChange(path => {
    const n = state.pages?.length || 0;
    for (let i = 0; i < n; i++) {
      if (book.pagePath(i) !== path) continue;
      const thumb = $.opt(`#page-list .pg-item[data-page="${i}"] .pg-thumb`);
      if (thumb) paintThumb(thumb, i);
      break;
    }
  });
}


function renderToc(nodes, into) {
  nodes.forEach(n => {
    const li = $.create('li');
    if (n.href) {
      const idx = state.pages.findIndex(p => fileOf(p.href) === fileOf(n.href));
      $.create('a', { text: n.label, onclick: () => idx >= 0 && goTo(idx) }).mount(li);
    } else $.create('span', { text: n.label }).mount(li);
    if (n.children.length) { const ol = $.create('ol'); renderToc(n.children, ol); ol.mount(li); }
    li.mount(into);
  });
}

/* -- Placeholders: the shape of the book, before the book -------------- */

// A conversion is one long request, and until it returns the stage is empty — nothing to look at and no
// idea how far along it is. The engine announces the page count and the first page's size as soon as it
// has opened the PDF (~0.8 s on a 1014-page book), which is enough to lay the book out for real. Cards
// only, no frames: those are made when a page is wanted, like every other card here. buildScroll replaces
// them wholesale when the container lands, so nothing has to be reconciled — the sizes are provisional by
// construction, taken from page one.
window.prism.placeholders = (count, w, h) => {
  const stage = $.opt('#epub-stage'); if (!stage || !count) return;
  stage.empty();
  for (let i = 0; i < count; i++) {
    const wrap = $.create('div', { class: 'prism-page pending' });
    wrap.style.setProperty('--w', w + 'px');
    wrap.style.setProperty('--h', h + 'px');
    $.create('span', { class: 'pg-pending-num', text: String(i + 1) }).mount(wrap);
    wrap.mount(stage);
  }
  // The count is known here, so the footer stops showing an empty "/ –": the pager reads 1 / 1014 from
  // the first tenth of a second, and setCurrent refines it once the real pages are in.
  const j = $.opt('#page-jump'), t = $.opt('#page-total');
  if (j && t) { j.value = '1'; t.textContent = `/ ${count}`; }
  $('body').cls('+has-book -is-loading');   // placeholders already fill the stage
  setZoom(state.zoom);
};

/* -- Scroll stage + observer ---------------------------------------- */

// ── frames on demand ───────────────────────────────────────────────────────
//
// A card holds NO iframe until its page is wanted. An empty iframe is not an inert element: the browser
// gives each one a browsing context, its own about:blank document and its own bookkeeping. Built up
// front, a 1014-page book carried ~2028 of them — one per page in the stage, one per page in the rail —
// before a single page was read. Small books never showed it; big ones did, with frames that received a
// src and never fired `load`.
//
// One authority for both surfaces. Removing the frame beats blanking its src: that kept the context.
function frameOf(card, cls) { return card.querySelector('iframe.' + cls); }

function openFrame(card, cls, src, attrs, onReady) {
  if (!card || frameOf(card, cls)) return null;
  const f = document.createElement('iframe');
  f.className = cls;
  for (const [k, v] of Object.entries(attrs)) f.setAttribute(k, v);
  // An iframe fires `load` for its INITIAL about:blank, before it has navigated anywhere — and a
  // {once:true} listener is SPENT on it: onReady then ran against an empty document and never ran
  // again for the real page. Every tool binds on the `frame` event this raises, so a page that
  // mounted while a tool was active was dead to that tool — no picker, no gizmo, no crop, silently,
  // and only pages already loaded when the tab was opened ever answered. Measured in Chromium:
  // three frames, three events, all `url=about:blank svg=false`. Wait for a real document, and stay
  // subscribed: a frame RELOADS (book.refreshPage after a page-scoped edit) and must be re-bound.
  // Handlers mark their own document, so a repeat is idempotent by contract.
  f.addEventListener('load', () => { if (f.contentDocument?.URL !== 'about:blank') onReady(f); });
  card.insertBefore(f, card.firstChild);       // under whatever else the card carries
  f.setAttribute('src', src);
  return f;
}

function closeFrame(card, cls) { frameOf(card, cls)?.remove(); }

/** Resolves once the worker controls this document — or right away when there is none to wait for. */
const frameGate = (async () => {
  if (!('serviceWorker' in navigator)) return;
  try {
    await navigator.serviceWorker.ready;
    if (navigator.serviceWorker.controller) return;
    await new Promise(done => {                  // a fresh worker still has to claim us
      const to = setTimeout(done, 3000);         // never hang the reader on it
      navigator.serviceWorker.addEventListener('controllerchange', () => { clearTimeout(to); done(); }, { once: true });
    });
  } catch (noWorker) { /* served straight from the engine: nothing to wait for */ }
})();

/** THE page-rotation rule for a live FRAME: the card reserves the VISUAL size (--w/--h), the frame keeps
 *  the page's own (--pw/--ph) and is turned by --rot. The reader mounts frames, so it calls this; the
 *  rail shows pictures, where the turn is baked into the raster instead. An unrotated page sets
 *  nothing, which is what keeps its rendering byte-for-byte what it was. */
function rotVars(el, pg) {
  if (!pg.rot) return;
  el.style.setProperty('--pw', pg.pw + 'px');
  el.style.setProperty('--ph', pg.ph + 'px');
  el.style.setProperty('--rot', pg.rot === 90  ? `translate(${pg.ph}px,0) rotate(90deg)`
                             : pg.rot === 180 ? `translate(${pg.pw}px,${pg.ph}px) rotate(180deg)`
                             :                  `translate(0,${pg.pw}px) rotate(270deg)`);
}

/** A page's geometry CHANGED — it was cropped, uncropped or turned. The page member is the tool's
 *  business; what the layout RESERVES for it is the chassis's, and it is kept in three places (the
 *  page record, the stage card, the rail thumbnail). One verb re-states all three through the same
 *  rotVars rule the build uses, so a page edited at run time is laid out exactly as a page opened. */
function setPageBox(idx, { pw, ph, rot = 0 }) {
  const pg = state.pages[idx]; if (!pg || !(pw > 0) || !(ph > 0)) return;
  pg.rot = ((rot % 360) + 360) % 360;
  const swap = pg.rot === 90 || pg.rot === 270;
  pg.w = swap ? ph : pw; pg.h = swap ? pw : ph;
  if (pg.rot) { pg.pw = pw; pg.ph = ph; } else { delete pg.pw; delete pg.ph; }
  const drop = (el) => ['--pw', '--ph', '--rot'].forEach(v => el.style.removeProperty(v));
  const card = $.opt(`#page-${idx}`);
  if (card) {
    card.style.setProperty('--w', pg.w + 'px'); card.style.setProperty('--h', pg.h + 'px');
    drop(card); rotVars(card, pg);
  }
  const thumb = $.opt(`#page-list .pg-item[data-page="${idx}"] .pg-thumb`);
  if (thumb) { thumb.setAttribute('data-w', pg.w); thumb.setAttribute('data-h', pg.h); drop(thumb); }
  sizeThumbs();                                  // the rail's boxes follow the page's new shape
  const thumb2 = $.opt(`#page-list .pg-item[data-page="${idx}"] .pg-thumb`);
  if (thumb2) paintThumb(thumb2, idx);           // …and its picture follows the new geometry
  setCurrent(state.current);
}

function buildScroll() {
  const stage = $('#epub-stage').empty();
  state.pages.forEach((pg, idx) => {
    const wrap = $.create('div', { class: 'prism-page', id: `page-${idx}` }).attr('data-index', idx);
    wrap.style.setProperty('--w', pg.w + 'px');   // custom props need setProperty, not .css()
    wrap.style.setProperty('--h', pg.h + 'px');
    rotVars(wrap, pg);
    wrap.mount(stage);                           // the frame is made when the page is wanted
  });
  setZoom(state.zoom);
}

function observePages() {
  const ratios = new Map();
  const io = new IntersectionObserver(entries => {
    for (const e of entries) {
      const idx = +e.target.getAttribute('data-index');
      if (e.isIntersecting) loadFrame(idx);
      ratios.set(idx, e.isIntersecting ? e.intersectionRatio : 0);
    }
    let best = -1, top = 0;
    for (const [idx, r] of ratios) if (r > top) { top = r; best = idx; }
    if (best >= 0 && best !== state.current && state.mode === 'scroll') setCurrent(best);
    // Lookahead, not just laziness. A page is ~840px tall and takes ~100ms to render, so 400px of margin
    // gave only a few tenths of a second of lead at normal scrolling speed — enough to see a page arrive.
    // 1200px is a page and a half either way, ~1s of lead, and it costs what it did not before: frames are
    // made on demand now, so the reader holds a handful of contexts instead of one per page.
  }, { root: $('#epub-scroll'), rootMargin: '1200px 0px', threshold: [0, .25, .5, .75, 1] });
  $.all('.prism-page').forEach(el => io.observe(el));
}

async function loadFrame(idx) {
  const card = $.opt(`#page-${idx}`);
  if (!card || frameOf(card, 'prism-frame')) return;
  // A page lives in the Service Worker's cache, so it can only be fetched once the worker CONTROLS this
  // document. Navigate before that — the window after an update, while a new worker installs — and the
  // request goes to the network, Chrome puts its own error page in the frame, and being a foreign origin
  // it fires no `load`: no `ready` class, a blank card, and nothing said anywhere.
  await frameGate;
  if (frameOf(card, 'prism-frame')) return;      // a second observation got there first
  openFrame(card, 'prism-frame', state.pages[idx].href,
            { title: `Page ${state.pages[idx].label}`, scrolling: 'no' },
            f => { card.cls('+ready'); bindFrameInput(f, idx); })   // reveal only once rendered
    // A frame that fails to navigate reports nothing by itself — the browser's error page is a foreign
    // origin, so no `load` fires and the card stays blank. Say it, and mark the card.
    ?.addEventListener('error', () => {
      card.cls('+failed');
      logLine('error', `page ${idx + 1} did not load — ${state.pages[idx].href}`, 'reader');
    }, { once: true });
  mounted.add(idx);
  releaseFrames();
}

// A frame is a whole document: its DOM, its styles and its glyph library all stay alive until it is
// removed. Keep a window around the reader — far enough that a page-turn or a short scroll back never
// pays a reload, small enough that the tab does not grow without bound.
const mounted = new Set();
const FRAME_WINDOW = 12;

function releaseFrames() {
  if (mounted.size <= FRAME_WINDOW) return;
  const here = state.current < 0 ? 0 : state.current;
  [...mounted]
    .sort((a, b) => Math.abs(b - here) - Math.abs(a - here))
    .slice(0, mounted.size - FRAME_WINDOW)
    .forEach(idx => {
      const card = $.opt(`#page-${idx}`);
      if (card) { closeFrame(card, 'prism-frame'); card.cls('-ready'); }
      mounted.delete(idx);
    });
}

// Pages are iframes, so wheel/touch over a page never reaches the parent. The
// frame is same-origin (served by the SW), so we bind zoom + gesture listeners
// on load — one contained, single-purpose hook. In edit mode we also draw a
// marquee straight in the frame document (same-origin: no postMessage).
function bindFrameInput(f, idx) {
  // The `frame` event is what EVERY tool binds on — the marquee, Trace, Redact's picker. It used to sit
  // at the end of a try that also does wheel, touch and drop wiring, so any one of those throwing left
  // the whole tool layer unbound on that page, silently, with the catch swallowing the reason. Emit
  // first: the tools do not depend on the input wiring, and a failure there must cost only itself.
  try { emit('frame', f, idx); } catch (e) { logLine('error', `frame hook failed on page ${idx + 1} — ${e.message || e}`, 'reader'); }
  try {
    f.contentWindow.addEventListener('wheel', e => {
      if (!e.ctrlKey && !e.metaKey) return;
      e.preventDefault();
      const a = frameToClient(f, e.clientX, e.clientY);             // page point -> chassis viewport
      zoomTo(state.zoom * (e.deltaY < 0 ? 1.12 : 1 / 1.12), a.x, a.y);
    }, { passive: false });
    attachTouch(f.contentWindow, (x, y) => { const a = frameToClient(f, x, y); return [a.x, a.y]; });
    f.contentDocument.addEventListener('dragover', e => e.preventDefault());
    f.contentDocument.addEventListener('drop', async e => {    // frames are part of the drop zone:
      e.preventDefault();                                      // never let the frame navigate away;
      const file = [...(e.dataTransfer?.files || [])].find(x => OPENABLE.test(x.name));
      if (file) openEpub(await file.arrayBuffer(), file.name); // tools' own targets stopPropagation first
    });
  } catch (e) { logLine('warn', `input wiring failed on page ${idx + 1} — ${e.message || e}`, 'reader'); }
}

/* -- Navigation | current | layout --------------------------------- */

function goTo(idx) {
  idx = $.clamp(idx, 0, state.pages.length - 1);
  setCurrent(idx);
  if (state.mode === 'scroll') {
    loadFrame(idx);
    const el = $.opt(`#page-${idx}`);
    if (el) { el.scrollIntoView({ behavior: 'smooth', block: 'start' }); el.cls('+flash'); setTimeout(() => el.cls('-flash'), 1000); }
  } else {
    showPaged();
  }
}

// Spread pairing, book convention: page 1 alone (cover), then (2,3), (4,5)…
// i.e. left = odd index, right = even index.
function spreadPair(idx) {
  if (idx <= 0) return [0, 0];
  const lo = idx % 2 === 1 ? idx : idx - 1;
  return [lo, Math.min(lo + 1, state.pages.length - 1)];
}

// Prev/next moves one page (scroll, single) or one spread (two pages).
function pageStep(dir) {
  if (state.mode === 'spread') { const [lo, hi] = spreadPair(state.current); goTo(dir > 0 ? hi + 1 : lo - 1); }
  else goTo(state.current + dir);
}

function setCurrent(idx) {
  state.current = idx;
  const n = state.pages.length, pg = state.pages[idx];
  let label = pg ? pg.label : '';
  // In spread mode the pager shows the LEFT page only. "32–33" does not fit the field (3.4ch, sized for a
  // page number) and was cut to "32–:"; and the pair is already on screen, so naming both told the reader
  // nothing the page did not. Typing a number still goes to that page, spread or not.
  if (pg && state.mode === 'spread') label = state.pages[spreadPair(idx)[0]].label;
  { const j = $.opt('#page-jump'), t = $.opt('#page-total');
    if (j && t) {
      if (pg) { j.disabled = false; if (document.activeElement !== j) j.value = label; t.textContent = `/ ${n}`; }
      else    { j.disabled = true;  j.value = ''; t.textContent = '/ –'; } } }
  $.all('#page-list a').forEach(a => a.cls((+a.attr('data-page') === idx ? '+' : '-') + 'active'));
  $.opt(`#page-list a[data-page="${idx}"]`)?.scrollIntoView({ block: 'nearest' });
  emit('page', idx);
}

function setMode(mode) {
  state.mode = mode;
  localStorage.setItem('prism_mode', mode);
  $.all('.px-segbtn').forEach(b => b.cls((b.attr('data-mode') === mode ? '+' : '-') + 'active'));
  $('#epub-scroll').attr('data-mode', mode);
  applyMode();
  emit('mode', mode);                                  // a tool whose ribbon shows the layout listens; onEnter is for entering
}

function applyMode() {
  if (state.mode === 'scroll') {
    $.all('.prism-page').forEach(p => p.cls('-off'));
    $.opt(`#page-${Math.max(0, state.current)}`)?.scrollIntoView({ block: 'start' });
  } else {
    showPaged();
  }
  setCurrent(Math.max(0, state.current));
}

// Paged modes: show only the current page (page) or its pair (spread), hide the
// rest, and preload neighbours so the next turn shows an already-rendered page.
function showPaged() {
  const [lo, hi] = state.mode === 'spread' ? spreadPair(state.current) : [state.current, state.current];
  $.all('.prism-page').forEach(p => {
    const i = +p.attr('data-index');
    p.cls(i >= lo && i <= hi ? '-off' : '+off');
  });
  for (let i = lo - 2; i <= hi + 2; i++) if (i >= 0 && i < state.pages.length) loadFrame(i);
  $('#epub-scroll').scrollTop = 0;
}

/* -- Zoom ----------------------------------------------------------- */

// Up to 500%: an OCR'd scan is judged word by word — a box against the glyphs under it — and at 300% a
// line of small print was still too small to tell a misread O from a 0.
const ZOOM_MIN = 0.4, ZOOM_MAX = 5;
function setZoom(z) {
  state.zoom = $.clamp(z, ZOOM_MIN, ZOOM_MAX);
  $('#epub-scroll').style.setProperty('--zoom', state.zoom);
  $('#zoom-info').text(Math.round(state.zoom * 100) + '%');
}

// Zoom to an absolute factor, keeping the EPUB point under (clientX, clientY)
// fixed on screen. clientX/Y null → anchor to the viewport centre.
function zoomTo(z, clientX, clientY) {
  const scroll = $('#epub-scroll');
  const r = scroll.getBoundingClientRect();
  const ax = clientX == null ? r.left + r.width / 2 : clientX;     // viewport anchor
  const ay = clientY == null ? r.top + r.height / 2 : clientY;
  // Anchor on the PAGE under the cursor, in page space. Raw scroll-space math
  // (content*k) is wrong here: pages are centered, and centering margins do not
  // scale with the zoom — that offset is what made the anchor drift.
  const page = document.elementFromPoint(ax, ay)?.closest?.('.prism-page')
      || $.opt(`#page-${Math.max(0, state.current)}`);
  const z0 = state.zoom || 1;
  if (!page) { setZoom(z); return; }
  const pr = page.getBoundingClientRect();
  const px = (ax - pr.left) / z0, py = (ay - pr.top) / z0;         // page-space point under the cursor
  setZoom(z);
  const nr = page.getBoundingClientRect();                          // post-reflow position
  scroll.scrollLeft += nr.left + px * state.zoom - ax;              // put that point back under the cursor
  scroll.scrollTop  += nr.top  + py * state.zoom - ay;
}
const zoomAt = (delta, x, y) => zoomTo(state.zoom + delta, x, y);

// Fit the current page to the stage: 'width' or whole 'page' (spread = 2-up).
function fitZoom(kind) {
  const pg = state.pages[Math.max(0, state.current)]; if (!pg) return;
  const s = $('#epub-scroll'), pad = 36;
  const wide = state.mode === 'spread' ? pg.w * 2 : pg.w;
  const zw = (s.clientWidth - pad) / wide;
  const zh = (s.clientHeight - pad) / pg.h;
  setZoom(kind === 'page' ? Math.min(zw, zh) : zw);
  if (state.mode === 'scroll') $.opt(`#page-${Math.max(0, state.current)}`)?.scrollIntoView({ block: 'start' });
  else $('#epub-scroll').scrollTop = 0;
}

/* -- Touch gestures: swipe (paged) · pinch + pan (scroll) ---------- */

// Bound to the parent scroll AND each same-origin page frame. `toParent` maps a
// touch's (x,y) into #epub-scroll viewport coords (identity for the parent;
// frame-offset × zoom for a page).
function attachTouch(target, toParent) {
  let sx = 0, sy = 0, st = 0, pd = 0, pz = 1;
  const dist = t => Math.hypot(t[0].clientX - t[1].clientX, t[0].clientY - t[1].clientY);
  const mid = t => [(t[0].clientX + t[1].clientX) / 2, (t[0].clientY + t[1].clientY) / 2];

  target.addEventListener('touchstart', e => {
    if (e.touches.length === 2) { pd = dist(e.touches); pz = state.zoom; }
    else if (e.touches.length === 1) { sx = e.touches[0].clientX; sy = e.touches[0].clientY; st = Date.now(); pd = 0; }
  }, { passive: true });

  target.addEventListener('touchmove', e => {
    if (e.touches.length === 2 && pd) {
      e.preventDefault();                                          // pinch — we own the zoom
      const [mx, my] = mid(e.touches), [px, py] = toParent(mx, my);
      zoomTo(pz * dist(e.touches) / pd, px, py);
    }
  }, { passive: false });

  target.addEventListener('touchend', e => {
    if (pd && e.touches.length < 2) { pd = 0; return; }
    if (e.touches.length > 0) return;
    if (state.mode === 'scroll') return;                           // paged only: horizontal swipe = page turn
    const t = e.changedTouches[0], dx = t.clientX - sx, dy = t.clientY - sy;
    if (Math.abs(dx) > 45 && Math.abs(dx) > Math.abs(dy) * 1.3 && Date.now() - st < 800) pageStep(dx < 0 ? 1 : -1);
  }, { passive: true });
}

// Run cb with the frame's live document, once its page is rendered.
// Frames are made on demand, so mounting is ASYNCHRONOUS (loadFrame awaits worker control): the frame
// does not exist on the line after the call. Await it, or every caller silently gets nothing — which is
// what happened to search: the page changed and the hit was never highlighted.
async function whenFrameReady(idx, cb) {
  await loadFrame(idx);
  const f = $.opt(`#page-${idx} .prism-frame`);
  if (!f) return;
  const d = f.contentDocument;
  if (d && d.readyState === 'complete' && d.querySelector('svg')) cb(d);
  else f.addEventListener('load', () => { try { cb(f.contentDocument); } catch {} }, { once: true });
}

/** A point inside a page FRAME, in chassis client coordinates. THE mapping, because there were four
 *  of them and all four assumed the frame is only scaled: `rect.left + x * zoom`. That holds while a
 *  frame is scaled and breaks the moment one is turned — `getBoundingClientRect()` then returns the
 *  axis-aligned box of a rotated element, so both the offset and the factor are wrong, and the
 *  caption, the zoom anchor, the touch anchor and the search jump all land somewhere else. The frame's
 *  own computed transform already says exactly what happened to it; ask that instead, and the card
 *  (untransformed, with the frame at its 0,0 and `transform-origin: top left`) gives the origin. */
function frameToClient(f, x, y) {
  const card = f?.parentElement; if (!card) return { x, y };
  const r = card.getBoundingClientRect();
  const t = getComputedStyle(f).transform;
  const p = new DOMMatrix(t === 'none' ? undefined : t).transformPoint(new DOMPoint(x, y));
  return { x: r.left + p.x, y: r.top + p.y };
}

// Centre #epub-scroll on a matched element (mapped through the frame's own transform).
function centreOn(idx, el) {
  const s = $('#epub-scroll'), f = $.opt(`#page-${idx} .prism-frame`);
  if (!f) return;
  const er = el.getBoundingClientRect(), sr = s.getBoundingClientRect();
  const c = frameToClient(f, er.left + er.width / 2, er.top + er.height / 2);
  s.scrollLeft += c.x - (sr.left + sr.width / 2);
  s.scrollTop  += c.y - (sr.top  + sr.height / 2);
}

/* -- Read aloud ------------------------------------------------------ */
// Lives in tts.js: a feature nothing else uses is a module, not chassis. It needed no new API to leave —
// goTo, centreOn, markRect and whenFrameReady were already on the seam. The chassis only silences it,
// through the seam like anyone else — a bridging const here would be one more declaration to order.

/** The opened file's base name (source extensions stripped) + a new extension. */
function exportName(ext) {
  return (state.name || 'document').replace(/\.(ocd\.epub|epub|pdf)$/i, '') + ext;
}

/* -- Chrome wiring -------------------------------------------------- */

// The three splitters — the rail, the contextual pane, the console — are qry's: one drag, the size kept
// per side. Each measures from the edge its pane is flush with: the rail and the pane from the sides of the
// body row, the console (fixed to the window's bottom) from the bottom of the window.
function wireSplitters() {
  const body = $.opt('.px-body'); if (!body) return;
  makeSplitter('#split-left',  { container: body, prop: '--px-rail-w',   min: 170, max: 480, persist: 'prism:rail-w' });
  makeSplitter('#split-right', { container: body, prop: '--px-drawer-w', from: 'end', min: 210, max: 560, persist: 'prism:drawer-w' });
  makeSplitter('#split-log',   { container: document.documentElement, prop: '--px-log-h', axis: 'y', from: 'end',
                                 min: 140, max: () => Math.round(window.innerHeight * 0.88), persist: 'prism:log-h' });
}

function wireChrome() {
  wireSplitters();
  wireConsole();
  $('#toggle-rail').on('click', () => $('body').cls('~rail-collapsed'));
  // A phone gives the page the width: the rail starts folded there and opens over the stage (prism.css).
  if (matchMedia('(max-width: 760px)').matches) $('body').cls('+rail-collapsed');
  // The tool panel folds the same way, from the corner it lives in. Purely visual: the tool stays
  // active, its ribbon stays, only the pane is out of the way — so nothing has to be re-selected.
  $('#toggle-drawer').on('click', () => $('body').cls('~drawer-collapsed'));
  makeMenu('#app-menu');
  $('#b-config').on('click', () => dialog('config-drawer').open());
  { const u = $.opt('#unit');
    if (u) { u.value = unit; u.on('change', () => setUnit(u.value)); } }
  attachTouch($('#epub-scroll'), (x, y) => [x, y]);   // gestures over the gaps between pages

  const pick = () => $('#file-input').click();
  $('#m-open').on('click', pick);
  $('#m-close').on('click', closeBook);
  $('#m-export').on('click', () => dialog('export-dialog').open());
  $('#b-help').on('click', () => dialog('help-dialog').open());
  { const setIc = () => { $('#b-theme').html(`<i data-icon="${theme.isDark() ? 'sun' : 'moon'}"></i>`); };
    $('#b-theme').on('click', () => { theme.toggle(); setIc(); });
    setIc(); }
  $('#m-console').on('click', () => toggleLog());
  // (no #m-theme: the header button #b-theme above owns the toggle AND its icon. The menu item it
  // belonged to is gone; the leftover binding warned on every boot and, had the element come back,
  // would have toggled without updating the icon — two authorities for one control.)
  $('#m-about').on('click', () => dialog('about-dialog').open());

  $('#file-input').on('change', async e => {
    const file = e.target.files[0];
    if (file) openEpub(await file.arrayBuffer(), file.name);
    e.target.value = '';
  });
  makeDropZone(document.body, { overlayLabel: 'Drop your .epub', onFiles: async files => {
    const doc = [...files].find(f => OPENABLE.test(f.name));
    if (doc) openEpub(await doc.arrayBuffer(), doc.name);
    else toast('Drop a .epub / .ocd.epub, a PDF or an image to open it.', { tone: 'warn' });
  }});

  $.all('.tab-btn').forEach(btn => btn.on('click', () => {
    $.all('.tab-btn').forEach(b => b.cls('-active')); btn.cls('+active');
    $.all('.tab-panel').forEach(p => p.cls('+hidden'));
    $('#' + btn.attr('data-tab')).cls('-hidden');
  }));

  $.all('.px-segbtn').forEach(b => {
    b.cls((b.attr('data-mode') === state.mode ? '+' : '-') + 'active');
    b.on('click', () => setMode(b.attr('data-mode')));
  });
  $('#epub-scroll').attr('data-mode', state.mode);

  $('#prev-btn').on('click', () => pageStep(-1));
  $('#next-btn').on('click', () => pageStep(1));
  { const j = $.opt('#page-jump');
    if (j) {
      const jump = () => {
        const v = j.value.trim(); if (!v || !state.pages.length) return;
        let idx = state.pages.findIndex(pg => pg.label === v);
        if (idx < 0) { const n2 = parseInt(v, 10); if (!Number.isNaN(n2)) idx = Math.min(Math.max(1, n2), state.pages.length) - 1; }
        if (idx >= 0) goTo(idx); else setCurrent(state.current);
        j.blur();
      };
      j.addEventListener('keydown', e => {
        e.stopPropagation();                                  // don't trip the arrow-key page nav
        if (e.key === 'Enter') jump();
        if (e.key === 'Escape') { setCurrent(state.current); j.blur(); }
      });
      j.addEventListener('focus', () => j.select());
      j.addEventListener('blur', () => setCurrent(state.current));
    } }
  $('#zoom-in-btn').on('click', () => zoomAt(0.1));
  $('#zoom-out-btn').on('click', () => zoomAt(-0.1));
  $('#fit-width-btn').on('click', () => fitZoom('width'));
  $('#fit-page-btn').on('click', () => fitZoom('page'));

  wireSearch({ state, hooks: window.prism.hooks, setCurrent, showPaged, whenFrameReady, centreOn });


  // Shortcuts must survive the focus being INSIDE a page. A frame is a separate document with its own
  // event path, so a keydown there never reaches this window — which is why F2 stopped answering the
  // moment a book was open and clicked into. Same-origin, so the frame's keys are forwarded here.
  const onKey = e => {
    if (e.key === 'F2') { e.preventDefault(); toggleLog(); return; }
    if ((e.ctrlKey || e.metaKey) && e.shiftKey && e.key.toLowerCase() === 'd') {
      e.preventDefault(); const on = !devMode(); setDevMode(on);
      toast(on ? 'Dev mode on — tabs in development are visible' : 'Dev mode off', { tone: on ? 'ok' : '', duration: 2200 });
      return;
    }
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'e') { e.preventDefault(); dialog('export-dialog').open(); return; }
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'o') { e.preventDefault(); $('#file-input').click(); return; }
    if (e.key === 'Escape' && $.opt('#log-console')?.classList.contains('open')) { toggleLog(false); return; }
    if (/input|textarea/i.test(e.target.tagName) || state.current < 0) return;
    if (e.key === 'ArrowRight' || (e.code === 'Space' && !e.shiftKey)) { e.preventDefault(); pageStep(1); }
    else if (e.key === 'ArrowLeft' || (e.code === 'Space' && e.shiftKey)) { e.preventDefault(); pageStep(-1); }
    else if (e.key === '+' || e.key === '=') zoomAt(0.1);
    else if (e.key === '-') zoomAt(-0.1);
  };

  window.addEventListener('keydown', onKey);
  on('frame', f => { try { f.contentWindow.addEventListener('keydown', onKey); } catch { } });

  $('#epub-scroll').on('wheel', e => {
    if (!e.ctrlKey && !e.metaKey) return;
    e.preventDefault();
    zoomTo(state.zoom * (e.deltaY < 0 ? 1.12 : 1 / 1.12), e.clientX, e.clientY);
  }, { passive: false });
}

/* -- Helpers -------------------------------------------------------- */

const fileOf = h => (h || '').split('/').pop().split('#')[0];
const text1 = (doc, sel) => { const el = doc.querySelector(sel); return el ? el.textContent.trim() : ''; };

async function fetchText(url) {
  const r = await fetch(url);
  if (!r.ok) throw new Error(`HTTP ${r.status} — ${fileOf(url)}`);
  return r.text();
}
async function fetchHtml(url) { return new DOMParser().parseFromString(await fetchText(url), 'text/html'); }
async function fetchXml(url) {
  const d = new DOMParser().parseFromString(await fetchText(url), 'application/xml');
  if (d.querySelector('parsererror')) throw new Error(`malformed XML — ${fileOf(url)}`);
  return d;
}

/* -- The jexter seam: the chassis API engine.js builds on ----------- */
Object.assign(window.prism, { openEpub, goTo, whenFrameReady, markRect, setPageBox, frameToClient,
  clearAllHl, centreOn, fold, toast, exportName, logLine, putMember });
