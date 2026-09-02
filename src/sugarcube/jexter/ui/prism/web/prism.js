// prism.js — the Prism chassis (100% client), verbatim from the PRISM reader core.
//
// Prism = this chassis + jexter.js (the engine seam). The chassis stays generic — it
// reads ANY fixed-layout EPUB off the original tree via the Service Worker; jexter.js
// plugs in through window.prism.hooks when the book is an OCD-EPUB (jexter/ members)
// or when a local engine (/api/convert) is present: PDF import, member-based search
// text, id-addressed highlights, TTS over the text layer, structure rail, exports.
//
// The book never leaves the browser. A Service Worker (prism-sw.js) serves the
// unzipped EPUB under a virtual /epub/<id>/ scope, so the reader and every page
// iframe just fetch normal URLs — one render path, no asset rewriting.
//
//   initSW     — register + take control (needs https or localhost).
//   openEpub   — read file -> async fflate unzip -> hand map to SW -> load.
//   loadEpub   — META-INF/container.xml -> OPF -> spine (order) + nav (labels/TOC).
//   prefetch   — one pass per page: viewBox (layout) + text (search).
//   rails      — Pages (live thumbnails) · Contents (TOC) · Search.
//   modes      — scroll · single page · two-page spread (persisted).
//   zoom       — anchored (buttons/keys, ctrl+wheel, pinch).
//   gestures   — swipe to turn (paged), pinch to zoom.
//   augment    — edit as DATA: a sidecar (prism/augment.json) holds entries
//                (zones in % of the page): media (video/audio/image),
//                reveal-on-tap (CSS-only spoiler), link hotspots (page jump /
//                URL), and anim (staggered CSS entrance on the SVG content of
//                a zone; the selection is compiled at edit time into {t, i}
//                indices so bake needs no layout). Rendered as a transient
//                overlay; drag to add/move/resize, note tab to caption,
//                dbl-click removes, Ctrl/Cmd+Z undoes.
//   export     — bake the model into clean page copies (portable) + ship the
//                sidecar, so the .epub is portable AND re-editable in PRISM.
//   console    — F2 log panel capturing console.* + errors.
import { boot, theme, toast, makeDropZone, copy } from 'https://cdn.jsdelivr.net/gh/Bloechle/qry-js@1.3.0/qry-kit.js';
import { unzip, zipSync } from 'https://cdn.jsdelivr.net/npm/fflate@0.8.2/+esm';

const state = {
  id: null, pages: [], toc: [], current: -1, zoom: 1, lang: 'en',
  mode: localStorage.getItem('prism_mode') || 'scroll',   // 'scroll' | 'page' | 'spread'
  files: null, root: '', opfPath: '', opfDir: '', aug: null,   // in-memory book + augmentation model
};
let seq = 0;

window.prism = { state, hooks: {}, on };        // the tool seam: state · providers · events
// EVENTS (multicast, chassis-emitted): 'mode'(id) · 'book'(state) · 'close'() ·
// 'page'(idx) · 'frame'(iframe, idx). Subscribe with P.on(evt, cb) → off().
// PROVIDERS (singular, hooks.*): openPdf · highlight · ttsNodes — one implementation
// answers the chassis (jexter.js owns them today).
const subs = new Map();                          // event → Set<cb>
function on(evt, cb) {
  if (!subs.has(evt)) subs.set(evt, new Set());
  subs.get(evt).add(cb);
  return () => subs.get(evt).delete(cb);
}
function emit(evt, ...args) {
  for (const cb of subs.get(evt) || []) { try { cb(...args); } catch (e) { console.error(e); } }
}

// Footer status line (jexter-style): one place sets it, lucide re-renders its icon.
function setStatus(html) {
  const el = $.opt('#status'); if (!el) return;
  el.innerHTML = html;
  if (window.lucide) lucide.createIcons();
}
window.prism.setStatus = setStatus;

// Unit preference (Settings drawer) + page-dims readout — Prism's footer dims, here.
const UNIT = { pt: [1, 0], mm: [25.4 / 72, 1], cm: [2.54 / 72, 2], in: [1 / 72, 2] };
let unit = localStorage.getItem('prism_unit') || 'pt';
function setUnit(u) {
  unit = (u in UNIT || u === 'px') ? u : 'pt';
  localStorage.setItem('prism_unit', unit);
}
window.prism.getUnit = () => unit;
window.prism.toUnit = (v) => unit === 'px' ? Math.round(v * state.zoom) + ' px'
  : (v * (UNIT[unit] || UNIT.pt)[0]).toFixed((UNIT[unit] || UNIT.pt)[1]) + ' ' + unit;

// App modes — a REGISTRY: any module registers its tab; the chassis renders the
// segmented bar from it and drives a generic lifecycle. A mode is declarative:
//   { id, label, icon, title?, onEnter?(), onLeave?() }
// The chassis toggles body.mode-<id> (CSS keys panes off it), calls the lifecycle
// hooks, then emits 'mode'(id). The chassis owns read + edit; distributions
// add/remove tabs by (not) loading their module — no chassis change ever needed.
// ONE authority: setAppMode.
const modes = [];
let appMode = 'read';

// A mode may declare `experimental: true`. That hides its BUTTON — nothing else. The module is
// still loaded, still registered, still fully wired, and setAppMode('analysis') still works; only
// the segmented bar filters. So a tab can be finished and shipped by deleting one flag, and an
// unfinished one never has to be ripped out and put back.
//
// Dev mode reveals them: Ctrl/Cmd+Shift+D toggles it, `?dev=1` (or `#dev`) turns it on for a link,
// and the choice is remembered. Deliberately obscure — it is a developer switch, not a feature.
const DEV_KEY = 'prism.dev';
function devMode() {
  try {
    const q = new URLSearchParams(location.search);
    if (q.has('dev') || location.hash === '#dev') { localStorage.setItem(DEV_KEY, '1'); return true; }
    return localStorage.getItem(DEV_KEY) === '1';
  } catch { return false; }
}
function setDevMode(on) {
  try { on ? localStorage.setItem(DEV_KEY, '1') : localStorage.removeItem(DEV_KEY); } catch {}
  if (!on && modes.find(m => m.id === appMode)?.experimental) setAppMode('read');   // never strand the user on a hidden tab
  renderModeBar();
}
/** The modes the bar shows — everything when dev mode is on, the finished ones otherwise. */
function visibleModes() { return devMode() ? modes : modes.filter(m => !m.experimental); }

function registerMode(mode) {
  modes.push(mode);
  renderModeBar();
}

function renderModeBar() {
  const host = $.opt('.px-modes'); if (!host) return;
  const E = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  host.cls((devMode() ? '+' : '-') + 'px-dev');
  host.innerHTML = visibleModes().map(m =>
    `<button class="px-modebtn${m.id === appMode ? ' active' : ''}${m.experimental ? ' px-wip' : ''}" data-appmode="${m.id}"
             title="${E(m.title || m.label)}${m.experimental ? ' — in development' : ''}"><i data-lucide="${m.icon}"></i><span>${E(m.label)}</span></button>`).join('');
  $.all('.px-modebtn').forEach(b => b.on('click', () => setAppMode(b.attr('data-appmode'))));
  if (window.lucide) lucide.createIcons();
}

function setAppMode(id) {
  let next = modes.find(m => m.id === id) || modes[0];     // unknown → the first tab (read)
  if (next.experimental && !devMode()) next = modes[0];    // hidden tab asked for outside dev mode
  const prev = modes.find(m => m.id === appMode);
  if (prev && prev !== next) prev.onLeave?.();
  if (prev) $('body').cls('-mode-' + prev.id);
  appMode = next.id;
  $('body').cls('+mode-' + next.id);
  $.all('.px-modebtn').forEach(b => b.cls((b.attr('data-appmode') === appMode ? '+' : '-') + 'active'));
  if (prev !== next) next.onEnter?.();
  emit('mode', appMode);
}

registerMode({ id: 'read', label: 'Read', icon: 'book-open', title: 'Read' });

window.prism.registerMode = registerMode;
window.prism.devMode = devMode;
window.prism.setDevMode = setDevMode;
window.prism.setAppMode = setAppMode;
window.prism.appMode = () => appMode;

boot({ title: 'Prism', ready: main });

async function main() {
  installLogCapture();                                              // capture console.* into the footer console
  if (localStorage.getItem('qry_theme') == null) theme.set('dark'); // dark by default, like Prism
  wireChrome();
  try { await initSW(); }
  catch (e) { toast(e.message, 'danger'); return; }   // no Service Worker → nothing can be served

  // Optional: ?epub=<same-origin url> loads a co-located sample (still client-side).
  const src = new URLSearchParams(location.search).get('epub');
  if (src) {
    try { openEpub(await (await fetch(src)).arrayBuffer(), src.split('/').pop()); }
    catch (e) { toast(`Could not fetch "${src}": ${e.message}`, 'danger'); }
  }
}

/* -- Service Worker: the client-side server ------------------------- */

async function initSW() {
  if (!('serviceWorker' in navigator))
    throw new Error('Service Worker unavailable — serve over https or localhost (not file://).');
  await navigator.serviceWorker.register('./prism-sw.js');
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

async function openEpub(buffer, name) {
  state.name = name || state.name || 'document';
  const head = new Uint8Array(buffer.slice(0, 4));
  if (head[0] === 0x25 && head[1] === 0x50 && head[2] === 0x44 && head[3] === 0x46) {   // %PDF
    if (window.prism.hooks.openPdf) return window.prism.hooks.openPdf(buffer, name);
    toast('This is a PDF — no conversion engine is available here. Open an .epub / .ocd.epub.', 'warning');
    return;
  }
  setStatus('<sl-spinner></sl-spinner> Opening\u2026');
  let files;
  try { files = await new Promise((res, rej) => unzip(new Uint8Array(buffer), (err, out) => err ? rej(err) : res(out))); }
  catch { toast('Not a valid ZIP/EPUB archive.', 'danger'); setStatus('<i data-lucide="sparkles"></i> Ready'); return; }

  const prev = state.id;
  const id = 'b' + (++seq);
  try {
    await swSend({ type: 'load', id, files });
    state.files = files;   // book in memory (edit + export); loadEpub reads the sidecar from it
    await loadEpub(id, name);
    if (prev) swSend({ type: 'unload', id: prev }).catch(() => {});
    setStatus('<i data-lucide="check"></i> Ready');
  } catch (e) {
    toast(`Could not open the EPUB: ${e.message}`, 'danger');
    swSend({ type: 'unload', id }).catch(() => {});
    setStatus('<i data-lucide="sparkles"></i> Ready');
  }
}

/* -- Close: clear the book, back to the empty state ---------------- */

function closeBook() {
  ttsStop();
  setAppMode('read');
  emit('close');
  if (state.id) swSend({ type: 'unload', id: state.id }).catch(() => {});
  state.id = null; state.pages = []; state.toc = []; state.current = -1;
  state.files = null; state.opfPath = ''; state.opfDir = ''; state.root = ''; state.aug = null;
  $('body').cls('-has-book');
  $('#epub-stage').empty(); $('#page-list').empty(); $('#toc-list').empty();
  $('#search-results').empty(); $('#search-info').text(''); $('#search-input').val('');
  setStatus('<i data-lucide="sparkles"></i> Ready');
  document.title = 'Prism';
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
  document.title = (text1(opf, 'dc\\:title') || text1(opf, 'title') || name || 'EPUB') + ' — PRISM';

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
  $('body').cls('+has-book');
  emit('book', state);   // tool seam: OCD-EPUB awareness (text layer, rails, …)
  const saved = +localStorage.getItem(posKey());
  state.current = saved > 0 && saved < state.pages.length ? saved : 0;   // resume where the reader left off
  setMode(state.mode);   // data-mode + segmented + layout + pager
}

// ONE pass per page: viewBox (layout) + concatenated text (search).
async function prefetch(urls) {
  const pages = new Array(urls.length);
  let i = 0;
  const worker = async () => {
    while (i < urls.length) {
      const idx = i++;
      const page = { href: urls[idx], label: String(idx + 1), w: 468, h: 780, text: '' };
      try {
        const svg = (await fetchHtml(urls[idx])).querySelector('svg');
        if (svg) {
          const vb = (svg.getAttribute('viewBox') || '').split(/[\s,]+/).map(Number);
          if (vb.length === 4) { page.w = vb[2] || page.w; page.h = vb[3] || page.h; }
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

function buildRails() {
  const pl = $('#page-list').empty();
  state.pages.forEach((pg, idx) => {
    const li = $.create('li');
    const a = $.create('a', { class: 'pg-item', onclick: () => goTo(idx) }).attr('data-page', idx);
    const thumb = $.create('div', { class: 'pg-thumb' }).attr('data-w', pg.w).attr('data-h', pg.h);
    const fr = $.create('iframe', { class: 'pg-frame' }).attr('scrolling', 'no').attr('tabindex', '-1').attr('aria-hidden', 'true');
    fr.style.setProperty('--w', pg.w + 'px');
    fr.style.setProperty('--h', pg.h + 'px');
    fr.mount(thumb); thumb.mount(a);
    $.create('span', { class: 'pg-num', text: pg.label }).mount(a);
    a.mount(li); li.mount(pl);
  });
  sizeThumbs();
  observeThumbs();
  renderToc(state.toc, $('#toc-list').empty());
  $('#search-results').empty(); $('#search-info').text(''); $('#search-input').val('');
}

// Thumbnails fill the rail: their scale follows the panel width, so dragging the
// splitter resizes them live. One ResizeObserver, rAF-throttled, recomputes all.
let thumbRO = null, thumbTick = false;
function sizeThumbs() {
  const pl = $.opt('#page-list'); if (!pl) return;
  const tw = Math.max(80, pl.clientWidth - 10);
  $.all('#page-list .pg-thumb').forEach(t => {
    const w = +t.getAttribute('data-w') || 468, h = +t.getAttribute('data-h') || 780, s = tw / w;
    t.style.setProperty('--tw', tw + 'px');
    t.style.setProperty('--th', Math.round(h * s) + 'px');
    const fr = t.querySelector('.pg-frame'); if (fr) fr.style.setProperty('--s', s);
  });
  if (!thumbRO) {
    thumbRO = new ResizeObserver(() => {
      if (thumbTick) return; thumbTick = true;
      requestAnimationFrame(() => { thumbTick = false; sizeThumbs(); });
    });
    thumbRO.observe(pl);
  }
}

// Thumbnails are real page frames (same SW-served render, fonts + images intact),
// loaded lazily as the rail scrolls so a long book doesn't spin up N iframes at once.
function observeThumbs() {
  const io = new IntersectionObserver(es => {
    for (const e of es) {
      if (!e.isIntersecting) continue;
      const fr = e.target.querySelector('.pg-frame');
      const idx = +e.target.parentElement.getAttribute('data-page');
      if (fr && !fr.getAttribute('src')) {
        fr.addEventListener('load', () => fr.classList.add('ready'), { once: true });
        fr.setAttribute('src', state.pages[idx].href);
      }
      io.unobserve(e.target);
    }
  }, { root: $('#tab-pages'), rootMargin: '400px 0px' });
  $.all('#page-list .pg-thumb').forEach(t => io.observe(t));
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

/* -- Scroll stage + observer ---------------------------------------- */

function buildScroll() {
  const stage = $('#epub-stage').empty();
  state.pages.forEach((pg, idx) => {
    const wrap = $.create('div', { class: 'prism-page', id: `page-${idx}` }).attr('data-index', idx);
    wrap.style.setProperty('--w', pg.w + 'px');   // custom props need setProperty, not .css()
    wrap.style.setProperty('--h', pg.h + 'px');
    $.create('iframe', { class: 'prism-frame' })
      .attr('title', `Page ${pg.label}`).attr('scrolling', 'no').mount(wrap);
    wrap.mount(stage);
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
  }, { root: $('#epub-scroll'), rootMargin: '400px 0px', threshold: [0, .25, .5, .75, 1] });
  $.all('.prism-page').forEach(el => io.observe(el));
}

function loadFrame(idx) {
  const f = $.opt(`#page-${idx} .prism-frame`);
  if (!f || f.getAttribute('src')) return;
  f.on('load', () => { $.opt(`#page-${idx}`)?.cls('+ready'); bindFrameInput(f, idx); });   // reveal only once fully rendered
  f.attr('src', state.pages[idx].href);
}

// Pages are iframes, so wheel/touch over a page never reaches the parent. The
// frame is same-origin (served by the SW), so we bind zoom + gesture listeners
// on load — one contained, single-purpose hook. In edit mode we also draw a
// marquee straight in the frame document (same-origin: no postMessage).
function bindFrameInput(f, idx) {
  try {
    f.contentWindow.addEventListener('wheel', e => {
      if (!e.ctrlKey && !e.metaKey) return;
      e.preventDefault();
      const fr = f.getBoundingClientRect();                        // page point -> parent viewport (frame is scaled)
      zoomTo(state.zoom * (e.deltaY < 0 ? 1.12 : 1 / 1.12), fr.left + e.clientX * state.zoom, fr.top + e.clientY * state.zoom);
    }, { passive: false });
    attachTouch(f.contentWindow, (x, y) => { const r = f.getBoundingClientRect(); return [r.left + x * state.zoom, r.top + y * state.zoom]; });
    f.contentDocument.addEventListener('dragover', e => e.preventDefault());
    f.contentDocument.addEventListener('drop', async e => {    // frames are part of the drop zone:
      e.preventDefault();                                      // never let the frame navigate away;
      const file = [...(e.dataTransfer?.files || [])].find(x => /\.(epub|pdf)$/i.test(x.name));
      if (file) openEpub(await file.arrayBuffer(), file.name); // tools' own targets stopPropagation first
    });
    emit('frame', f, idx);
  } catch { /* cross-origin — never happens under the SW scope */ }
}

/* -- Navigation | current | modes ---------------------------------- */

function goTo(idx) {
  idx = clamp(idx, 0, state.pages.length - 1);
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
  if (pg && state.mode === 'spread') {
    const [lo, hi] = spreadPair(idx);
    label = lo === hi ? state.pages[lo].label : `${state.pages[lo].label}–${state.pages[hi].label}`;
  }
  { const j = $.opt('#page-jump'), t = $.opt('#page-total');
    if (j && t) {
      if (pg) { j.disabled = false; if (document.activeElement !== j) j.value = label; t.textContent = `/ ${n}`; }
      else    { j.disabled = true;  j.value = ''; t.textContent = '/ –'; } } }
  $.all('#page-list a').forEach(a => a.cls((+a.attr('data-page') === idx ? '+' : '-') + 'active'));
  $.opt(`#page-list a[data-page="${idx}"]`)?.scrollIntoView({ block: 'nearest' });
  if (state.id) localStorage.setItem(posKey(), idx);
  emit('page', idx);
}

function setMode(mode) {
  state.mode = mode;
  localStorage.setItem('prism_mode', mode);
  $.all('.px-segbtn').forEach(b => b.cls((b.attr('data-mode') === mode ? '+' : '-') + 'active'));
  $('#epub-scroll').attr('data-mode', mode);
  applyMode();
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

function setZoom(z) {
  state.zoom = clamp(z, 0.4, 3);
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

/* -- Search (accent- and case-folded) ------------------------------- */

function runSearch() {
  const q = $('#search-input').val().trim();
  const out = $('#search-results').empty(), info = $('#search-info');
  clearAllHl();                                          // drop marks from a previous query
  if (!q) { info.text(''); return; }
  const nq = fold(q);
  let hits = 0;
  state.pages.forEach((pg, idx) => {
    if (!pg.text || !fold(pg.text).includes(nq)) return;
    hits++;
    const li = $.create('li');
    $.create('a', { class: 'nav-link', html: `<b>p.${pg.label}</b><span class="hit">${snippet(pg.text, q)}</span>`, onclick: () => gotoMatch(idx, q) }).mount(li);
    li.mount(out);
  });
  info.text(hits ? `${hits} page${hits > 1 ? 's' : ''} matched` : 'No results');
}

/* -- In-page highlight: talk to the same-origin page frame directly -- */

// Reading position, keyed by book identity (title + page count).
const posKey = () => 'prism_pos_' + fold(document.title).replace(/\W+/g, '').slice(0, 48) + '_' + state.pages.length;

const clearHl = doc => doc && doc.querySelectorAll('[data-px-hl]').forEach(n => n.remove());
const clearAllHl = () => $.all('.prism-frame').forEach(f => clearHl(f.contentDocument));

// A translucent <rect> behind an SVG element (text can't hold a <mark>); the
// rect copies the element's transform so it lands exactly on the glyphs.
// Flat, marker-style rectangle. The pad is expressed in PAGE units: a v2 run's
// local space is em-scaled (the font size lives in its matrix), so the pad is
// divided by the local scale — same optical margin at every font size.
function markRect(doc, el, attr, fill) {
  let bb; try { bb = el.getBBox(); } catch { return; }
  let k = 1;
  const m = /matrix\(([^)]+)\)/.exec(el.getAttribute('transform') || '');
  if (m) {
    const [a, b, c, d] = m[1].trim().split(/[\s,]+/).map(Number);
    const det = Math.abs(a * d - b * c);
    if (det > 0) k = Math.sqrt(det);
  }
  const pad = 0.8 / k;
  const r = doc.createElementNS(SVG_NS, 'rect');
  r.setAttribute('x', bb.x - pad); r.setAttribute('y', bb.y - pad);
  r.setAttribute('width', bb.width + 2 * pad); r.setAttribute('height', bb.height + 2 * pad);
  r.setAttribute('fill', fill);
  r.setAttribute('pointer-events', 'none'); r.setAttribute(attr, '1');
  const tr = el.getAttribute('transform'); if (tr) r.setAttribute('transform', tr);
  el.parentNode.insertBefore(r, el);   // before the element → rendered behind it
}

// Highlight every <text> run whose folded content contains the query.
// Returns the first matched <text>, for centring.
function highlightDoc(doc, q, idx) {
  clearHl(doc);
  const svg = doc && doc.querySelector('svg');
  if (!svg || !q) return null;
  const hook = window.prism.hooks.highlight;              // jexter: member runs by id
  if (hook) { const el = hook(doc, q, idx); if (el !== undefined) return el; }
  const nq = fold(q);
  let first = null;
  svg.querySelectorAll('text').forEach(t => {
    if (!fold(t.textContent).includes(nq)) return;
    markRect(doc, t, 'data-px-hl', 'rgba(78,143,31,.32)');
    if (!first) first = t;
  });
  return first;
}

// Run cb with the frame's live document, once its page is rendered.
function whenFrameReady(idx, cb) {
  loadFrame(idx);
  const f = $.opt(`#page-${idx} .prism-frame`);
  if (!f) return;
  const d = f.contentDocument;
  if (d && d.readyState === 'complete' && d.querySelector('svg')) cb(d);
  else f.addEventListener('load', () => { try { cb(f.contentDocument); } catch {} }, { once: true });
}

// Centre #epub-scroll on a matched element (the frame is scaled by state.zoom).
function centreOn(idx, el) {
  const s = $('#epub-scroll'), f = $.opt(`#page-${idx} .prism-frame`);
  if (!f) return;
  const er = el.getBoundingClientRect(), fr = f.getBoundingClientRect(), sr = s.getBoundingClientRect(), z = state.zoom;
  s.scrollLeft += fr.left + (er.left + er.width / 2) * z - (sr.left + sr.width / 2);
  s.scrollTop  += fr.top  + (er.top  + er.height / 2) * z - (sr.top  + sr.height / 2);
}

// Jump to a search hit: show its page, highlight the matches, centre on the first.
function gotoMatch(idx, q) {
  idx = clamp(idx, 0, state.pages.length - 1);
  clearAllHl();
  setCurrent(idx);
  if (state.mode !== 'scroll') showPaged();
  whenFrameReady(idx, doc => {
    const first = highlightDoc(doc, q, idx);
    if (first) centreOn(idx, first);
    else if (state.mode === 'scroll') $.opt(`#page-${idx}`)?.scrollIntoView({ block: 'start' });
  });
}

function snippet(txt, q) {
  const at = fold(txt).indexOf(fold(q));
  if (at < 0) return '';
  const from = Math.max(0, at - 30), to = Math.min(txt.length, at + q.length + 40);
  return `${from > 0 ? '…' : ''}${esc(txt.slice(from, at))}<mark>${esc(txt.slice(at, at + q.length))}</mark>${esc(txt.slice(at + q.length, to))}${to < txt.length ? '…' : ''}`;
}

/* -- Read aloud: Web Speech, highlight synced to the text layer ------ */
//
// The pages carry real <text> in reading order, so we can narrate them and
// track the spoken position: the page text is concatenated with offsets, and
// the utterance's boundary events (charIndex) map back to the <text> element
// being read — highlighted live, auto-scrolled into view, auto-advancing to
// the next page like an audiobook.

const tts = { on: false, cur: null };

function ttsToggle() {
  if (tts.on) { ttsStop(); return; }
  if (state.current < 0 || !('speechSynthesis' in window)) return;
  tts.on = true;
  $('#b-tts').cls('+active');
  ttsPage(state.current);
}

function ttsStop() {
  tts.on = false;
  try { speechSynthesis.cancel(); } catch {}
  clearTtsMarks();
  $.opt('#b-tts')?.cls('-active');
}

function eachLoadedFrame(cb) {
  $.all('.prism-frame').forEach(f => {
    try {
      const doc = f.contentDocument; if (!doc || !doc.querySelector('svg')) return;
      const idx = +f.closest('.prism-page')?.getAttribute('data-index');
      if (idx >= 0) cb(idx, doc, f);
    } catch { /* not ready */ }
  });
}
const clearTtsMarks = () => eachLoadedFrame((idx, doc) => doc.querySelectorAll('[data-px-tts]').forEach(n => n.remove()));

function ttsPage(idx) {
  if (!tts.on) return;
  tts.cur = null;
  goTo(idx);
  whenFrameReady(idx, doc => {
    if (!tts.on) return;
    const svg = doc.querySelector('svg');
    let nodes = window.prism.hooks.ttsNodes?.(doc, idx);   // jexter: text-layer runs (SVG-OCD pages carry no <text>)
    if (!nodes) nodes = (svg ? [...svg.querySelectorAll('text')].filter(t => t.textContent.trim()) : [])
        .map(el => ({ el, text: el.textContent }));
    if (!nodes.length) { ttsNext(idx); return; }

    // Paragraphs mode (array of arrays, texts pre-shaped by the hook): one utterance
    // per paragraph, queued with a short breath between — the synthesizer resets its
    // prosody per utterance, so sentences fall at periods and paragraphs breathe.
    const paras = Array.isArray(nodes[0]) ? nodes.filter(g => g.length)
        : [nodes.map(({ el, text }) => ({ el, text: text.replace(/\s+/g, ' ').trim() + ' ' }))];
    const lang = (svg && (svg.getAttribute('xml:lang') || svg.getAttribute('lang'))) || state.lang;
    let k = 0;
    const speakPara = () => {
      if (!tts.on) return;
      if (k >= paras.length) { ttsNext(idx); return; }
      const group = paras[k++];
      const spans = []; let full = '';
      group.forEach(({ el, text }) => {
        spans.push({ start: full.length, end: full.length + text.length, el });
        full += text;                                       // the hook owns spacing (hyphen repair)
      });
      if (!full.trim()) { speakPara(); return; }
      const u = new SpeechSynthesisUtterance(full);
      u.lang = lang;
      u.onboundary = e => { if (e.charIndex != null) ttsMark(doc, spans, e.charIndex, idx); };
      u.onend = () => { if (tts.on) setTimeout(speakPara, 340); };   // the paragraph breath
      u.onerror = () => ttsStop();
      speechSynthesis.speak(u);
    };
    speechSynthesis.cancel();
    speakPara();
  });
}

function ttsNext(idx) {
  clearTtsMarks();
  if (idx + 1 < state.pages.length) ttsPage(idx + 1);
  else ttsStop();
}

// Highlight the <text> containing the spoken charIndex; keep it in view.
function ttsMark(doc, spans, at, idx) {
  const span = spans.find(sp => at >= sp.start && at < sp.end);
  if (!span || span.el === tts.cur) return;
  tts.cur = span.el;
  doc.querySelectorAll('[data-px-tts]').forEach(n => n.remove());
  markRect(doc, span.el, 'data-px-tts', 'rgba(116,183,62,.30)');
  const f = $.opt(`#page-${idx} .prism-frame`), s = $('#epub-scroll');
  if (!f) return;
  const er = span.el.getBoundingClientRect(), fr = f.getBoundingClientRect(), sr = s.getBoundingClientRect();
  const top = fr.top + er.top * state.zoom, bot = fr.top + (er.top + er.height) * state.zoom;
  if (top < sr.top + 24 || bot > sr.bottom - 24) centreOn(idx, span.el);
}

/* -- Augment: a data model of edits, rendered as an overlay ---------- */
//
// One authority per concern: augmentations live as DATA in a sidecar
// (prism/augment.json), keyed by page, zones in % of the page. At read time we
// render them as a transient overlay in the same-origin frame (originals stay
// pristine); at export we bake clean HTML into page copies AND ship the sidecar,
// so an exported EPUB is portable *and* can be re-opened and re-edited in PRISM.

const SVG_NS = 'http://www.w3.org/2000/svg';

const enc = s => new TextEncoder().encode(s);
const dec = b => new TextDecoder().decode(b);
const zipOf = absUrl => decodeURIComponent(absUrl.slice(state.root.length));   // /epub/id/PATH -> PATH
const pageZip = idx => zipOf(state.pages[idx].href);
const frameDoc = idx => { try { return $.opt(`#page-${idx} .prism-frame`)?.contentDocument || null; } catch { return null; } };

// Re-zip the book into a valid EPUB: mimetype first + stored, augmentations
// baked into page copies (originals stay pristine), sidecar shipped for re-edit.
/* -- Saving: one native path for every export --------------------------------
   pickSave opens the Chromium save dialog (File System Access API) UP FRONT —
   while the click's transient activation is still valid — suggesting the
   original file name with the new extension. saveAs then writes to the picked
   handle, or falls back to a plain anchor download (Firefox/Safari). */

async function pickSave(name) {
  if (!window.showSaveFilePicker) return null;                     // fallback: anchor download
  const ext = name.slice(name.lastIndexOf('.'));
  try {
    return await window.showSaveFilePicker({
      suggestedName: name,
      types: [{ description: ext.slice(1).toUpperCase() + ' file',
                accept: { 'application/octet-stream': [ext] } }],
    });
  } catch (e) { return e && e.name === 'AbortError' ? 'aborted' : null; }
}

async function saveAs(blob, name, handle) {
  if (handle === 'aborted') return false;
  if (handle) {
    const w = await handle.createWritable();
    await w.write(blob); await w.close();
    return true;
  }
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob); a.download = name; a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 5000);
  return true;
}

/** The opened file's base name (source extensions stripped) + a new extension. */
function exportName(ext) {
  return (state.name || 'document').replace(/\.(ocd\.epub|epub|pdf)$/i, '') + ext;
}

/* -- Chrome wiring -------------------------------------------------- */

// Draggable splitters — Prism's pattern: pointer-capture drag sets a CSS var,
// persisted per side. Left resizes the rail, right the contextual pane.
function wireSplitters() {
  const root = document.documentElement;
  const saved = (k) => localStorage.getItem(k);
  if (saved('prism_leftw'))  root.style.setProperty('--px-rail-w',   saved('prism_leftw'));
  if (saved('prism_rightw')) root.style.setProperty('--px-drawer-w', saved('prism_rightw'));
  const wire = (el, cssVar, lsKey, fromLeft, lo, hi, panel) => {
    if (!el) return;
    el.addEventListener('pointerdown', e => {
      e.preventDefault();
      const startX = e.clientX, startW = panel().getBoundingClientRect().width;
      el.setPointerCapture(e.pointerId);
      el.classList.add('dragging'); document.body.classList.add('col-resizing');
      const move = ev => { const dx = ev.clientX - startX;
        const w = clamp(fromLeft ? startW + dx : startW - dx, lo, hi);
        root.style.setProperty(cssVar, w + 'px'); };
      const up = () => { el.classList.remove('dragging'); document.body.classList.remove('col-resizing');
        localStorage.setItem(lsKey, getComputedStyle(root).getPropertyValue(cssVar).trim());
        el.removeEventListener('pointermove', move); el.removeEventListener('pointerup', up); el.removeEventListener('pointercancel', up); };
      el.addEventListener('pointermove', move); el.addEventListener('pointerup', up); el.addEventListener('pointercancel', up);
    });
  };
  wire($.opt('#split-left'),  '--px-rail-w',   'prism_leftw',  true,  170, 480, () => $('#rail'));
  wire($.opt('#split-right'), '--px-drawer-w', 'prism_rightw', false, 210, 560,
       () => $.opt('#edit-drawer.px-drawer') && document.body.classList.contains('mode-edit') ? $('#edit-drawer') : $('#analysis-drawer'));
}

function wireChrome() {
  wireSplitters();
  $('#toggle-rail').on('click', () => $('body').cls('~rail-collapsed'));
  $('#b-config').on('click', () => $('#config-drawer').show());
  { const u = $.opt('#unit');
    if (u) {
      const setV = () => { u.value = unit; };
      if (window.customElements?.whenDefined)
        Promise.all([customElements.whenDefined('sl-select'), customElements.whenDefined('sl-option')]).then(setV);
      else setV();
      u.addEventListener('sl-change', e => setUnit(e.target.value));
    } }
  attachTouch($('#epub-scroll'), (x, y) => [x, y]);   // gestures over the gaps between pages

  const pick = () => $('#file-input').click();
  $('#m-open').on('click', pick);
  $('#m-close').on('click', closeBook);
  $('#m-export').on('click', () => $('#export-dialog').show());
  $('#b-help').on('click', () => $('#help-dialog').show());
  { const setIc = () => { $('#b-theme').html(`<i data-lucide="${theme.isDark() ? 'sun' : 'moon'}"></i>`); if (window.lucide) lucide.createIcons(); };
    $('#b-theme').on('click', () => { theme.toggle(); setIc(); });
    setIc(); }
  $('#m-console').on('click', () => toggleLog());
  // (no #m-theme: the header button #b-theme above owns the toggle AND its icon. The menu item it
  // belonged to is gone; the leftover binding warned on every boot and, had the element come back,
  // would have toggled without updating the icon — two authorities for one control.)
  $('#m-about').on('click', () => $('#about-dialog').show());
  $('#about-ok').on('click', () => $('#about-dialog').hide());

  // Console (footer + head controls + level filter)
  $('#b-tts').on('click', ttsToggle);
  $('#log-close').on('click', () => toggleLog(false));
  $('#log-title').on('click', () => toggleLog(false));
  $('#log-clear').on('click', clearLog);
  $('#log-copy').on('click', copyLog);
  $.all('.log-f').forEach(b => b.on('click', () => {
    $.all('.log-f').forEach(x => x.cls('-active')); b.cls('+active');
    $('#log-console').attr('data-filter', b.attr('data-lvl'));
  }));

  $('#file-input').on('change', async e => {
    const file = e.target.files[0];
    if (file) openEpub(await file.arrayBuffer(), file.name);
    e.target.value = '';
  });
  makeDropZone(document.body, { label: 'Drop your .epub', onFiles: async files => {
    const doc = [...files].find(f => /\.(epub|pdf)$/i.test(f.name));
    if (doc) openEpub(await doc.arrayBuffer(), doc.name);
    else toast('Drop a .epub / .ocd.epub to open it.', 'warning');
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

  $('#search-btn').on('click', runSearch);
  $('#search-input').on('keydown', e => { if (e.key === 'Enter') runSearch(); });


  window.addEventListener('keydown', e => {
    if (e.key === 'F2') { e.preventDefault(); toggleLog(); return; }
    if ((e.ctrlKey || e.metaKey) && e.shiftKey && e.key.toLowerCase() === 'd') {
      e.preventDefault(); const on = !devMode(); setDevMode(on);
      toast(on ? 'Dev mode on — tabs in development are visible' : 'Dev mode off', on ? 'success' : 'neutral', 2200);
      return;
    }
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'e') { e.preventDefault(); $('#export-dialog').show(); return; }
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'o') { e.preventDefault(); $('#file-input').click(); return; }
    if (e.key === 'Escape' && $.opt('#log-console')?.classList.contains('open')) { toggleLog(false); return; }
    if (/input|textarea/i.test(e.target.tagName) || state.current < 0) return;
    if (e.key === 'ArrowRight' || (e.code === 'Space' && !e.shiftKey)) { e.preventDefault(); pageStep(1); }
    else if (e.key === 'ArrowLeft' || (e.code === 'Space' && e.shiftKey)) { e.preventDefault(); pageStep(-1); }
    else if (e.key === '+' || e.key === '=') zoomAt(0.1);
    else if (e.key === '-') zoomAt(-0.1);
  });

  $('#epub-scroll').on('wheel', e => {
    if (!e.ctrlKey && !e.metaKey) return;
    e.preventDefault();
    zoomTo(state.zoom * (e.deltaY < 0 ? 1.12 : 1 / 1.12), e.clientX, e.clientY);
  }, { passive: false });
}

/* -- Drawers: a clean slide, no bounce ------------------------------- */
import('https://cdn.jsdelivr.net/npm/@shoelace-style/shoelace@2.20.1/cdn/utilities/animation-registry.js')
  .then(({ setDefaultAnimation }) => {
    setDefaultAnimation('drawer.showEnd', {
      keyframes: [{ transform: 'translateX(26px)', opacity: 0 }, { transform: 'translateX(0)', opacity: 1 }],
      options: { duration: 160, easing: 'ease-out' } });
    setDefaultAnimation('drawer.hideEnd', {
      keyframes: [{ transform: 'translateX(0)', opacity: 1 }, { transform: 'translateX(26px)', opacity: 0 }],
      options: { duration: 130, easing: 'ease-in' } });
  }).catch(() => {});

/* -- Log console (F2): capture console.* + errors ------------------- */

const LOG_MAX = 2000;

function logLine(level, msg, src = 'client') {
  const body = $.opt('#log-body'); if (!body) return;
  const atBottom = body.scrollTop + body.clientHeight >= body.scrollHeight - 8;
  const row = $.create('div', { class: `log-row lv-${level}` });
  row.innerHTML = `<span class="log-lv">${esc(level)}</span><span class="log-src">${esc(src)}</span><span class="log-msg">${esc(msg)}</span>`;
  body.appendChild(row);
  while (body.childElementCount > LOG_MAX) body.firstChild.remove();
  if (atBottom) body.scrollTop = body.scrollHeight;
}

function toggleLog(force) {
  const c = $.opt('#log-console'); if (!c) return;
  const open = force === undefined ? !c.classList.contains('open') : force;
  c.cls(open ? '+open' : '-open');
}

function clearLog() { $.opt('#log-body')?.empty(); }

function copyLog() {
  const body = $.opt('#log-body'); if (!body) return;
  const rows = [...body.querySelectorAll('.log-row')].filter(r => r.offsetParent !== null);
  const text = rows.map(r => `${r.querySelector('.log-lv').textContent} ${r.querySelector('.log-msg').textContent}`.trim()).join('\n');
  if (!text) { toast('Console is empty', 'warning'); return; }
  copy(text); toast(`Copied ${rows.length} line${rows.length === 1 ? '' : 's'}`, 'success');
}

const _fmt = a => { try { return typeof a === 'string' ? a : a instanceof Error ? a.message : JSON.stringify(a); } catch { return String(a); } };

function installLogCapture() {
  const map = { log: 'info', info: 'info', warn: 'warn', error: 'error', debug: 'debug' };
  for (const m of Object.keys(map)) {
    const orig = console[m] ? console[m].bind(console) : () => {};
    console[m] = (...a) => { orig(...a); try { logLine(map[m], a.map(_fmt).join(' ')); } catch {} };
  }
  window.addEventListener('error', e => { try { logLine('error', (e.message || 'error') + (e.filename ? ` @ ${fileOf(e.filename)}:${e.lineno}` : '')); } catch {} });
  window.addEventListener('unhandledrejection', e => { try { logLine('error', 'unhandled: ' + (e.reason?.message || e.reason || '')); } catch {} });
}

/* -- Helpers -------------------------------------------------------- */

const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
const fold = s => s.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
const fileOf = h => (h || '').split('/').pop().split('#')[0];
const esc = s => s.replace(/[&<>]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
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

/* -- The jexter seam: the chassis API jexter.js builds on ----------- */
Object.assign(window.prism, { openEpub, closeBook, goTo, whenFrameReady, markRect,
  clearAllHl, centreOn, fold, esc, toast, pickSave, saveAs, exportName, logLine, putMember });
