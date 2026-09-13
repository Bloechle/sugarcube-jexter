/* pages.js — "Pages": the page itself. Crop it, turn it.
 *
 * Both edits are ROOT-ONLY, and that is the whole design (FORMAT §B2): the crop IS the page's
 * viewBox, the rotation IS its data-rot, and the page's coordinate frame is its media box. So this
 * tool rewrites ONE element per page — the <svg> — and never a content node, never a matrix. A crop
 * is therefore free, exact and reversible: "Full page" is not an undo that re-derives anything, it
 * is the window put back. Nothing here calls the engine; the engine reads the window on the next
 * export and every projection agrees, because it reads the same model the page states.
 *
 * A page's own size and its VISUAL size differ once it is turned: the stored page is the unrotated
 * document, exactly as PDF stores one. This file changes the page; the chassis (P.setPageBox) decides
 * what the layout reserves for it — one verb, so the stage and the rail cannot disagree.
 *
 * A page whose frame is not mounted (the reader keeps a window of twelve) has no live DOM to edit, so
 * its member is re-opened, its root changed and the member REBUILT through pageShell — never spliced:
 * a childless <svg> self-closes under the browser serializer and an anchor-based splice silently
 * no-ops. One function, apply(), covers both cases; every verb goes through it.
 */
import { book } from '/shared/js/book.js';
import { pageShell, SVG_NS } from '/shared/js/ocd.js';
import { shots } from '/shared/js/shot.js';
import { createGizmo, boxOf, setBox, outerBox, clampTo, normBox, svgPointOf } from './gizmo.js';
import * as backend from './backend.js';

const P = window.prism;
const TOOL = 'pages';
const enc = (s) => new TextEncoder().encode(s), dec = (b) => new TextDecoder().decode(b);

const CSS = `
svg.pg-crop-pick { cursor:crosshair; }
/* The rectangle is the crop: light enough to read the page through it, outlined clearly enough to
   place an edge. It is what the gizmo grabs, so it takes pointer events — the handles are the
   gizmo's own data-ui chrome and sit above it. */
svg #pg-crop { fill:var(--jx-brand-bright, #5e9bd6); fill-opacity:.08;
               stroke:var(--jx-brand-bright, #5e9bd6); stroke-width:1.4; stroke-dasharray:5 4;
               vector-effect:non-scaling-stroke; cursor:move; }
`;

/* -- state ------------------------------------------------------------------------------------- */
// `plan` is the light table's working order: source page indices (0-based) and `null` for a blank to
// insert. It is a PLAN, not a document — nothing moves until Apply, so a reorganisation is one engine
// call and one undo (Reset), never a trail of half-applied edits.
// `view` is the tool's own half of the stage: the light table (organise the SET) or the reader (work
// on ONE page — crop, turn). One tool, two things to look at, and a command that needs a page says so
// by switching to it rather thanfailing quietly.
const st = { on: false, view: 'table', all: false, plan: null, sel: new Set(), drag: -1 };
const idxNow = () => Math.max(0, P.state.current ?? 0);
const count = () => P.state.pages?.length || 0;

/* -- the page's root, read ---------------------------------------------------------------------- */

/** The media frame in SVG space. The flip subtracts the media origin, so the frame is ALWAYS at
 *  (0,0) there — its size is what a window must be clamped to. */
function frame(svg) {
  const m = (svg.getAttribute('data-media') || '').trim().split(/[\s,]+/).map(Number);
  if (m.length === 4 && m.every(Number.isFinite)) return { w: m[2], h: m[3] };
  return { w: +svg.getAttribute('width') || 0, h: +svg.getAttribute('height') || 0 };
}

/** The current window — the viewBox, which IS the crop. */
function window_(svg) {
  const v = (svg.getAttribute('viewBox') || '').trim().split(/[\s,]+/).map(Number);
  return v.length === 4 && v.every(Number.isFinite)
    ? { x: v[0], y: v[1], w: v[2], h: v[3] } : { x: 0, y: 0, ...frame(svg) };
}

const rotOf = (svg) => ((+svg.getAttribute('data-rot') || 0) % 360 + 360) % 360;
const cropped = (svg) => { const f = frame(svg), w = window_(svg); return w.x !== 0 || w.y !== 0 || w.w !== f.w || w.h !== f.h; };
const n4 = (v) => String(Math.round(v * 1e4) / 1e4);

/* -- the page's root, written -------------------------------------------------------------------- */

/** Clamp a window to the media frame and refuse a degenerate one — a crop a user cannot undo by
 *  dragging again is worse than no crop. */
// NOT the numeric clamp the rest of the client calls `clamp(v, lo, hi)`: this one takes a WINDOW and a
// FRAME and answers a window or null. One name for two contracts is a reader's trap, and the tell is
// that its arguments do not even have the same arity.
function clampWindow(win, f) {
  const x = Math.max(0, Math.min(win.x, f.w)), y = Math.max(0, Math.min(win.y, f.h));
  const w = Math.min(win.w, f.w - x), h = Math.min(win.h, f.h - y);
  return (w >= 8 && h >= 8) ? { x, y, w, h } : null;
}

/** Change one page's root, mounted or not, and tell the chassis its box moved. `edit(svg)` returns
 *  false to leave the page alone. */
async function apply(idx, edit) {
  const live = book.page(idx);                       // the displayed DOM, when this page has one
  if (live) {
    if (edit(live.svg) === false) return false;
    const w = window_(live.svg);
    live.w = w.w; live.h = w.h;                      // the shell states the page's OWN size
    await book.persist(idx, true);
    P.setPageBox?.(idx, { pw: w.w, ph: w.h, rot: rotOf(live.svg) });
    return true;
  }
  const path = book.pagePath(idx), bytes = book.get(path);
  if (!bytes) return false;
  const doc = new DOMParser().parseFromString(dec(bytes), 'application/xhtml+xml');
  const svg = doc.querySelector('svg[data-ocd="page"]');
  if (!svg || edit(svg) === false) return false;
  const w = window_(svg);
  await book.put(path, enc(pageShell(new XMLSerializer().serializeToString(svg), w.w, w.h, idx + 1)));
  P.setPageBox?.(idx, { pw: w.w, ph: w.h, rot: rotOf(svg) });
  return true;
}

/** Write a window onto a page: the viewBox states it, width/height follow it. */
const setWindow = (svg, win) => {
  const c = clampWindow(win, frame(svg));
  if (!c) return false;
  svg.setAttribute('viewBox', `${n4(c.x)} ${n4(c.y)} ${n4(c.w)} ${n4(c.h)}`);
  svg.setAttribute('width', n4(c.w));
  svg.setAttribute('height', n4(c.h));
};

const setRot = (svg, deg) => {
  const r = ((deg % 360) + 360) % 360;
  if (r) svg.setAttribute('data-rot', String(r)); else svg.removeAttribute('data-rot');
};

/* -- the verbs ---------------------------------------------------------------------------------- */

const scope = (on) => st.all ? [...Array(count()).keys()] : [on ?? idxNow()];

/** Crop THE PAGE THE RECTANGLE WAS DRAWN ON — not the current one. In scroll mode the page under the
 *  pointer and the page the observer calls current are routinely different, and cropping the second
 *  while pointing at the first is the kind of wrong that looks like the tool doing nothing. Measured
 *  in Chromium: dragging on page 4 cropped page 3. Ribbon commands have no pointer, so they act on
 *  the current page; the gesture carries its own. */
async function crop(win, on) {
  for (const i of scope(on)) await apply(i, svg => setWindow(svg, { ...win }));
  done(st.all ? `Cropped ${count()} page(s).` : `Page ${(on ?? idxNow()) + 1} cropped.`);
}

async function full() {
  for (const i of scope()) await apply(i, svg => { const f = frame(svg); return setWindow(svg, { x: 0, y: 0, ...f }); });
  done(st.all ? 'Every page back to its full size.' : `Page ${idxNow() + 1} back to full size.`);
}

async function turn(delta) {
    if (st.view !== 'page' && !st.all && !st.sel.size) setView('page');
  for (const i of scope()) {
    const live = book.page(i);
    await apply(i, svg => setRot(svg, rotOf(svg) + delta));
    if (!live) continue;                               // nothing more to do: apply() already re-boxed it
  }
  done(st.all ? `Turned ${count()} page(s).` : `Page ${idxNow() + 1} turned.`);
}

/** A geometry change moves the page's own shape and its picture, and the light table shows both — so
 *  the grid is redrawn after one. The rasters are cached per member, and the member that changed is
 *  the only one whose cache was dropped, so this costs exactly the page that moved. */
function done(msg) {
    if (book.isOpen()) renderTable();
    refresh();
    P.toast?.(msg, 'success');
}

/* -- the crop: a RECTANGLE you can go on adjusting -------------------------------------------------
 *
 * A crop drawn in one drag is a crop you cannot correct: the second attempt is another drag from
 * scratch, and "a little more off the left" is not expressible. So the drag CREATES a rectangle and
 * the rectangle stays, driven by `gizmo.js` — the component that already owns the eight handles and,
 * more to the point, the ANCHOR: the handle you pull moves, the opposite one stays put, which is what
 * makes "take 4 mm off this edge" a gesture rather than an arithmetic problem. Rotation is turned off
 * (a page window is axis-aligned), so the gizmo shows exactly the affordances that do something.
 *
 * The rectangle lives in the page's own SVG space, which is the space the viewBox is written in — so
 * the box the reader sees IS the crop, with no conversion between the two. It carries data-ui, so it
 * is stripped from the member by persist() and can never reach the document.
 *
 * The gizmo composes its transform in FRONT of the element, so after every gesture the matrix is BAKED
 * back into x/y/w/h and cleared: the rectangle stays a plain rect, the numbers in the drawer read the
 * attributes directly, and the next gesture starts from a clean matrix instead of accumulating one.
 */

const CROP_ID = 'pg-crop';
const gizmos = new Map();                            // page index → gizmo, one per bound page
const MIN = 8;                                       // pt: a crop smaller than this cannot be dragged back

const cropRect = (doc) => doc?.getElementById(CROP_ID) || null;

function ensureRect(doc, win) {
    const svg = doc.querySelector('svg[data-ocd="page"]'); if (!svg) return null;
    let r = cropRect(doc);
    if (!r) {
        r = doc.createElementNS(SVG_NS, 'rect');
        r.setAttribute('id', CROP_ID);
        r.setAttribute('class', 'pg-crop');
        r.setAttribute('data-ui', ''); r.setAttribute('data-z', 'over');
        svg.appendChild(r);
    }
    if (win) setBox(r, win);
    return r;
}

/** Fold the gizmo's composed matrix back into the rectangle's own numbers, clamped to the page and
 *  snapped to its edges — a crop meant to be "the whole width" should not miss it by a tenth of a point
 *  because a handle landed there. The fold and the clamp are `gizmo.js`'s — one geometry, one copy —
 *  while the SNAP stays here: it is a statement about a PAGE, not about a rectangle.
 */
function bake(r, svg) {
  const box = outerBox(r);
  const f = frame(svg), SNAP = 3;
  if (Math.abs(box.x) < SNAP) { box.w += box.x; box.x = 0; }
  if (Math.abs(box.y) < SNAP) { box.h += box.y; box.y = 0; }
  if (Math.abs(box.x + box.w - f.w) < SNAP) box.w = f.w - box.x;
  if (Math.abs(box.y + box.h - f.h) < SNAP) box.h = f.h - box.y;
  const out = clampTo(box, f, MIN);
  setBox(r, out);
  return out;
}

/* -- binding a page: the gizmo, and the drag that creates the rectangle ---------------------------- */

function bind(doc, idx) {
    const svg = doc.querySelector('svg[data-ocd="page"]');
    if (!svg || svg.__pgBound) return;
    svg.__pgBound = true;                            // the replay of `frame` must be idempotent

    gizmos.set(idx, createGizmo(svg, {
        enabled: st.on && st.view === 'page',
        move: true, scale: true, rotate: false, uniform: false, hover: false,
        pick: () => cropRect(doc),                   // the ONLY thing this tool lets you grab
        onChange: () => readout(idx),
        onCommit: () => { const r = cropRect(doc); if (r) { bake(r, svg); readout(idx); ribbon(); } },
    }));

    // The drag that CREATES the rectangle — on empty page space, where the gizmo picked nothing.
    let from = null, live = null;
    const drop = () => { from = null; live = null; };
    svg.addEventListener('pointerdown', e => {
        if (!st.on || st.view !== 'page' || e.button !== 0) return;
        if (e.target.closest?.('[data-ui]')) return;  // the gizmo owns its own handles
        from = svgPointOf(svg, e);
        live = ensureRect(doc, { x: from.x, y: from.y, w: 0, h: 0 });
        svg.setPointerCapture(e.pointerId);
        e.preventDefault();
    }, true);
    svg.addEventListener('pointermove', e => {
        if (!from || !live) return;
        setBox(live, normBox(from, svgPointOf(svg, e)));
    });
    svg.addEventListener('pointerup', e => {
        if (!from || !live) return;
        const s = normBox(from, svgPointOf(svg, e));
        drop();
        if (s.w < 8 || s.h < 8) return;              // a click is not a crop
        const r = ensureRect(doc, s);
        bake(r, svg);
        gizmos.get(idx)?.select(r);                  // hand it to the handles at once
        readout(idx); ribbon();
    });
    doc.addEventListener('keydown', e => {
        if (e.key === 'Escape') { drop(); clearRect(doc, idx); }
        if (e.key === 'Enter' && st.on && cropRect(doc)) applyCrop();
    });
}

function clearRect(doc, idx) {
    gizmos.get(idx)?.clear();
    cropRect(doc)?.remove();
    readout(idx); ribbon();
}

/* -- the crop verbs ------------------------------------------------------------------------------- */

/** Put a rectangle on the current page, at its current window — the way to ADJUST a crop that is
 *  already applied, rather than drawing a new one blind. */
function proposeCrop() {
    if (st.view !== 'page') setView('page');         // a crop needs a page in front of you
    const idx = idxNow(), doc = book.frameDoc(idx), svg = doc?.querySelector('svg[data-ocd="page"]');
    if (!svg) { P.toast?.('Open a page first.', 'warning'); return; }
    const r = ensureRect(doc, window_(svg));
    gizmos.get(idx)?.set({ enabled: true });
    gizmos.get(idx)?.select(r);
    readout(idx); ribbon();
}

/** Commit the rectangle: it becomes the page's window. */
async function applyCrop() {
    const idx = idxNow(), doc = book.frameDoc(idx), svg = doc?.querySelector('svg[data-ocd="page"]');
    const r = svg && cropRect(doc);
    if (!r) { P.toast?.('Draw a rectangle on the page first.', 'warning'); return; }
    const box = bake(r, svg);
    clearRect(doc, idx);
    await crop(box, idx);
}

/** Show the light table or the reader — the tool owns which, the chassis owns everything else. */
function setView(v) {
    // No book: the stage carries the DROP CARD, and hiding it behind an empty grid would take away the
    // one thing the screen is for. The tool waits in the page view until there is a document.
    if (!book.isOpen()) v = 'page';
    st.view = v;
    const host = document.getElementById('pages-table');
    if (host) host.hidden = v !== 'table';
    document.body.classList.toggle('tool-pages-page', v === 'page');
    if (v === 'table') renderTable();
    book.eachFrame(d => styleOn(d));
    gizmos.forEach(g => g.set({ enabled: st.on && v === 'page' }));
    ribbon(); readout(idxNow());
}

function styleOn(doc) {
    if (!doc.getElementById('pg-style')) {
        const s = doc.createElement('style'); s.id = 'pg-style'; s.setAttribute('data-ui', '');
        s.textContent = CSS; doc.head.appendChild(s);
    }
    doc.querySelector('svg[data-ocd="page"]')?.classList.toggle('pg-crop-pick', st.on && st.view === 'page');
}

const clean = (doc) => {
    doc.querySelectorAll('#pg-crop, #pg-style, [data-ui="gz"], [data-ui="gzh"]').forEach(n => n.remove());
    doc.querySelector('svg')?.classList.remove('pg-crop-pick');
};

/** The four numbers, in points — the other half of "precisely". The gesture puts a box roughly where
 *  it belongs; a field says 42.5. Both write the same rectangle, so neither is a second authority. */
function readout(idx) {
    const doc = book.frameDoc(idx), r = doc && cropRect(doc);
    const box = r ? boxOf(r) : null;
    for (const k of ['x', 'y', 'w', 'h']) {
        const f = $id('pg-' + k); if (!f) continue;
        f.disabled = !box;
        if (document.activeElement !== f) f.value = box ? String(Math.round(box[k] * 10) / 10) : '';
    }
    const info = $id('pg-crop-info');
    if (info) info.textContent = box
        ? `Crop ${Math.round(box.w)} × ${Math.round(box.h)} pt at ${Math.round(box.x)}, ${Math.round(box.y)} — Enter applies, Esc drops it`
        : 'Drag a rectangle on the page, or press Crop to adjust the current window.';
}

/** A number typed in the drawer moves the same rectangle the handles move. */
function bindFields() {
    for (const k of ['x', 'y', 'w', 'h']) {
        const f = $id('pg-' + k); if (!f || f.__pgBound) continue;
        f.__pgBound = true;
        f.addEventListener('change', () => {
            const idx = idxNow(), doc = book.frameDoc(idx), svg = doc?.querySelector('svg[data-ocd="page"]');
            const r = svg && cropRect(doc); if (!r) return;
            const box = boxOf(r);
            const v = parseFloat(f.value);
            if (Number.isFinite(v)) { box[k] = v; setBox(r, box); bake(r, svg); }
            gizmos.get(idx)?.redraw();
            readout(idx);
        });
    }
}

/* -- what the drawer READS ------------------------------------------------------------------------ */

const $id = (id) => document.getElementById(id);

/** Every page that is cropped or turned — read from the members, so it is the truth and not a log. */
function edited() {
  const out = [];
  for (let i = 0; i < count(); i++) {
    const b = book.get(book.pagePath(i)); if (!b) continue;
    const s = dec(b), head = s.slice(s.indexOf('<svg'), s.indexOf('>', s.indexOf('<svg')) + 1);
    const at = (n) => (new RegExp(n + '="([^"]*)"').exec(head) || [])[1] || '';
    const m = at('data-media').trim().split(/[\s,]+/).map(Number);
    const v = at('viewBox').trim().split(/[\s,]+/).map(Number);
    const rot = ((+at('data-rot') || 0) % 360 + 360) % 360;
    const crop = m.length === 4 && v.length === 4 && (v[0] !== 0 || v[1] !== 0 || v[2] !== m[2] || v[3] !== m[3]);
    if (crop || rot) out.push({ i, crop, rot, size: v.length === 4 ? `${Math.round(v[2])}×${Math.round(v[3])} pt` : '' });
  }
  return out;
}

function refresh() {
  ribbon();
  bindFields();
  readout(idxNow());
  const info = $id('pg-info'), list = $id('pg-list');
  if (!info || !list) return;
  if (!book.isOpen()) { info.textContent = 'Open a document first.'; list.replaceChildren(); return; }
  const idx = idxNow(), doc = book.frameDoc(idx), svg = doc?.querySelector('svg[data-ocd="page"]');
  info.textContent = svg
    ? `Page ${idx + 1} — ${Math.round(window_(svg).w)}×${Math.round(window_(svg).h)} pt`
      + (cropped(svg) ? ' · cropped' : '') + (rotOf(svg) ? ` · turned ${rotOf(svg)}°` : '')
    : `Page ${idx + 1}`;
  const items = edited();
  list.replaceChildren();
  for (const e of items) {
    const li = document.createElement('li');
    const a = document.createElement('a');
    a.className = 'nav-link';
    a.innerHTML = `<b>${e.i + 1}</b><span>${e.crop ? 'cropped ' + e.size : ''}${e.crop && e.rot ? ' · ' : ''}${e.rot ? 'turned ' + e.rot + '°' : ''}</span>`;
    a.addEventListener('click', () => P.goTo(e.i));
    li.appendChild(a); list.appendChild(li);
  }
  if (!items.length) {
    const li = document.createElement('li');
    li.className = 'search-info';
    li.textContent = 'No page is cropped or turned.';
    list.appendChild(li);
  }
}

/* -- the light table: the page SET, planned then committed ---------------------------------------- */
//
// Reordering is the one page operation the client cannot honestly do alone. The order lives in the
// spine, which the engine now reads — but the members address each other by POSITION (a structure
// ref's page, a bookmark's target, an internal link's `page-006.xhtml`), and only `ocd.model.Pages`
// owns the table that remaps all three. So the table plans, and Apply is `?to=ocd&pages=…`: one call,
// the engine reshapes and remaps, the answer is opened as the document. The alternative — rewriting
// the spine here — displays correctly and lies to every export.

const planned = () => st.plan || [...Array(count()).keys()];
const dirty = () => { const p = st.plan; return !!p && (p.length !== count() || p.some((v, i) => v !== i)); };

function setPlan(next) { st.plan = next; st.sel.clear(); renderTable(); ribbon(); }

function move(from, to) {
  const p = [...planned()];
  const [it] = p.splice(from, 1);
  p.splice(from < to ? to - 1 : to, 0, it);
  setPlan(p);
}

const removeSel = () => { const p = planned().filter((_, i) => !st.sel.has(i)); setPlan(p.length ? p : planned()); };
const duplicateSel = () => { const p = []; planned().forEach((v, i) => { p.push(v); if (st.sel.has(i)) p.push(v); }); setPlan(p); };
const insertBlank = () => { const p = [...planned()], at = st.sel.size ? Math.max(...st.sel) + 1 : p.length; p.splice(at, 0, null); setPlan(p); };
const reverse = () => setPlan([...planned()].reverse());

/** The spec the engine reads: 1-based, `+` for a blank — the same grammar the CLI takes. */
const spec = () => planned().map(v => v === null ? '+' : String(v + 1)).join(',');

async function applyPlan() {
  if (!dirty()) { P.toast?.('Nothing to apply — the order has not changed.', 'neutral'); return; }
  const bytes = P.bookBytes?.();
  if (!bytes) { P.toast?.('Open a document first.', 'warning'); return; }
  P.toast?.('Reorganising…', 'primary');
  try {
    const art = await backend.convert(bytes, 'ocd', { pages: spec() });
    st.plan = null; st.sel.clear();
    await book.open(art.bytes, P.state.name);                    // the canonical rebuild: rails, pager, nav
    P.toast?.('Pages reorganised — the outline, the links and the structure followed.', 'success');
  } catch (e) {
    P.toast?.(`Refused: ${e.message || e}`, 'danger');
  }
}

/* -- the table, drawn ----------------------------------------------------------------------------- */

const TILE = 150;                                   // css px of thumbnail width; the grid fills the rest

/* -- thumbnails: one shared rasteriser ------------------------------------------------------------
 *
 * The picture of a page is `shot.js` — the same module the rail uses, so a page has ONE small picture
 * in the whole app, made once, cached per member and dropped when that member is written. This file
 * only says which page, how big, and where to put it.
 */

// Rasterizing is cheap but not free, and a long book scrolls fast: three at a time, newest first, so
// what the reader is looking at is drawn before what they have already scrolled past.
const queue = [];
let workers = 0;
function want(el, idx) {
    queue.push([el, idx]);
    while (workers < 3 && queue.length) pump();
}
async function pump() {
    const job = queue.pop(); if (!job) return;
    const [el, idx] = job;
    workers++;
    try {
        const pg = P.state.pages[idx];
        const src = await shots.url(idx, { w: pg.pw || pg.w, h: pg.ph || pg.h, rot: pg.rot || 0, width: TILE });
        if (src && el.isConnected) { el.src = src; el.classList.add('ready'); }
    } finally { workers--; if (queue.length) pump(); }
}

let tableIO = null;

function renderTable() {
    const host = document.getElementById('pages-table'); if (!host) return;
    host.replaceChildren();
    tableIO?.disconnect();
    queue.length = 0;
    if (!book.isOpen()) {
        const e = document.createElement('div'); e.className = 'px-table-empty';
        e.textContent = 'Open a document to organise its pages.';
        host.appendChild(e); return;
    }
    tableIO = new IntersectionObserver(es => {
        for (const e of es) if (e.isIntersecting) {
            const img = e.target, i = +img.getAttribute('data-src');
            tableIO.unobserve(img);
            if (i >= 0) want(img, i);
        }
    }, { root: document.getElementById('epub-scroll'), rootMargin: '800px 0px' });

    planned().forEach((src, pos) => {
        const pg = src === null ? P.state.pages[0] : P.state.pages[src];
        const tile = document.createElement('div');
        tile.className = 'px-tile' + (src === null ? ' blank' : '') + (st.sel.has(pos) ? ' sel' : '');
        tile.setAttribute('tabindex', '0');
        tile.setAttribute('data-pos', pos);
        const shot = document.createElement('div');
        shot.className = 'px-tile-shot';
        // Each page keeps ITS OWN shape: the tile's width is whatever the grid column gives it, so a
        // height computed from a nominal 150 px was right only when the column happened to be 150 px
        // wide — every page then wore the same box whatever its format. An aspect-ratio holds at any
        // column width, and it is the VISUAL size (rotation already swapped by prefetch/setPageBox), so
        // a page turned 90° is a landscape tile.
        shot.style.aspectRatio = `${pg?.w || 595} / ${pg?.h || 842}`;
        if (src !== null) {
            const img = document.createElement('img');
            img.className = 'px-tile-img';
            // An <img> is DRAGGABLE by default, and a native drag hijacks the gesture: the browser
            // takes over the pointer, our pointermove/up never arrive — so the tile does not move —
            // and the chassis's body drop zone lights up with "Drop your .epub" over the reorder.
            // Measured: one dragstart and ten dragover per attempt. The table drags with pointer
            // events; nothing in it may start a native one.
            img.draggable = false;
            img.setAttribute('data-src', src);
            img.alt = '';
            shot.appendChild(img);
        }
        tile.appendChild(shot);
        const num = document.createElement('span'); num.className = 'px-tile-num'; num.textContent = String(pos + 1);
        tile.appendChild(num);
        if (src === null || src !== pos) {
            const tag = document.createElement('span'); tag.className = 'px-tile-tag';
            tag.textContent = src === null ? 'blank' : 'was ' + (src + 1);
            tile.appendChild(tag);
        }
        host.appendChild(tile);
        if (src !== null) tableIO.observe(shot.firstChild);
    });
    bindTable(host);
}

/** Selection changes a class, never the DOM: rebuilding the grid to show a blue outline is what made
 *  a click feel like a page load. */
function paintSel() {
    const host = document.getElementById('pages-table'); if (!host) return;
    for (const t of host.querySelectorAll('.px-tile'))
        t.classList.toggle('sel', st.sel.has(+t.getAttribute('data-pos')));
    ribbon();
}

function bindTable(host) {
  if (host.__pgTable) return;                       // one binding for the host; the tiles are delegated
  host.__pgTable = true;
  const posOf = (e) => { const t = e.target.closest('.px-tile'); return t ? +t.getAttribute('data-pos') : -1; };

  // ONE gesture, decided at pointerUP: under the drag threshold it was a selection, past it a move.
  // Splitting the two across `click` and a captured pointer sequence is what broke selection — with
  // the pointer captured on the host, the click that follows is dispatched to the HOST, so a handler
  // asking `e.target.closest('.px-tile')` finds nothing and the tile is never selected. Capture only
  // once a drag has really started, and let pointerup answer both cases.
  let downAt = null;

  host.addEventListener('dragstart', e => e.preventDefault());   // no native drag inside the table

  host.addEventListener('pointerdown', e => {
    const t = e.target.closest('.px-tile'); if (!t || e.button !== 0) return;
    e.preventDefault();                                          // …and none started by the pointer either
    downAt = { pos: +t.getAttribute('data-pos'), x: e.clientX, y: e.clientY, tile: t };
  });

  host.addEventListener('pointermove', e => {
    if (!downAt) return;
    if (st.drag < 0) {
      if (Math.hypot(e.clientX - downAt.x, e.clientY - downAt.y) < 5) return;   // a click is not a drag
      st.drag = downAt.pos;
      downAt.tile.classList.add('drag');
      host.setPointerCapture(e.pointerId);            // a drag always leaves the tile it started on
    }
    mark(host, e);
  });

  host.addEventListener('pointerup', e => {
    if (!downAt) return;
    const from = st.drag, at = downAt.pos;
    const marker = host.querySelector('.drop-before, .drop-after');
    const to = marker ? +marker.getAttribute('data-pos') + (marker.classList.contains('drop-after') ? 1 : 0) : -1;
    cleanMarks(host);
    st.drag = -1; downAt = null;
    if (host.hasPointerCapture?.(e.pointerId)) host.releasePointerCapture(e.pointerId);
    if (from >= 0) { if (to >= 0 && to !== from && to !== from + 1) move(from, to); return; }
    select(at, e);
  });

  host.addEventListener('pointercancel', () => { cleanMarks(host); st.drag = -1; downAt = null; });

  host.addEventListener('dblclick', e => {            // a page you want to LOOK at: back to the reader on it
    const t = e.target.closest('.px-tile'); if (!t) return;
    const src = planned()[+t.getAttribute('data-pos')];
    if (src != null && !dirty()) { P.goTo(src); setView('page'); }   // look at it, still in this tool
  });
}

const cleanMarks = (host) =>
  host.querySelectorAll('.drag, .drop-before, .drop-after')
      .forEach(n => n.classList.remove('drag', 'drop-before', 'drop-after'));

/** Plain click selects one, Ctrl/Cmd toggles, Shift extends — the vocabulary of every file manager.
 *
 *  And the CURRENT page follows the click. Two lists of the same pages were marking two different ones:
 *  the table marked what you had just selected, the rail went on marking whatever the reader was left
 *  on, and the rail's marker is the louder of the two — so the eye was pulled to a page nobody had
 *  chosen. Removing the rail's mark would have fixed the contradiction by taking away the "you are
 *  here" a list needs; making the two AGREE fixes it by leaving one notion of the page in question,
 *  shown twice. It also means switching to the page view lands on the page you were pointing at. */
function select(pos, e) {
  if (e.shiftKey && st.sel.size) {
    const lo = Math.min(...st.sel, pos), hi = Math.max(...st.sel, pos);
    for (let i = lo; i <= hi; i++) st.sel.add(i);
  } else if (e.metaKey || e.ctrlKey) {
    st.sel.has(pos) ? st.sel.delete(pos) : st.sel.add(pos);
  } else {
    const only = st.sel.size === 1 && st.sel.has(pos);
    st.sel.clear();
    if (!only) st.sel.add(pos);
  }
  const src = planned()[pos];                       // the page it came from, before any planned move
  if (src != null) P.goTo(src);                     // the rail marks what you just picked
  paintSel();                                       // a class, not a rebuild — see paintSel
}

/** Where the dragged tile would land: the nearest tile edge under the pointer, marked as a caret. */
function mark(host, e) {
  let best = null, bestD = Infinity, after = false;
  for (const t of host.querySelectorAll('.px-tile')) {
    const r = t.getBoundingClientRect();
    if (e.clientY < r.top - 12 || e.clientY > r.bottom + 12) continue;          // another row
    for (const [x, isAfter] of [[r.left, false], [r.right, true]]) {
      const d = Math.abs(e.clientX - x);
      if (d < bestD) { bestD = d; best = t; after = isAfter; }
    }
  }
  host.querySelectorAll('.drop-before, .drop-after').forEach(n => n.classList.remove('drop-before', 'drop-after'));
  if (best) best.classList.add(after ? 'drop-after' : 'drop-before');
}

/* -- ribbon --------------------------------------------------------------------------------------- */

function ribbon() {
  // With no document open, every one of these acts on nothing: a control that cannot do its job is
  // disabled, not left to explain itself in a toast — the same rule the footer already follows when it
  // collapses to a status line with no book.
  const has = book.isOpen();
  P.ribbon(TOOL, [
    // The tool's two views. Organising a set and adjusting one page are different things to look at,
    // and neither belongs in a drawer: the grid needs the width, the crop needs the page.
    { group: 'View', items: [
      { icon: 'layout-grid', label: 'Organise', title: 'The light table — the whole document, page by page', active: st.view === 'table', disabled: !has, on: () => setView('table') },
      { icon: 'file', label: 'Page', title: 'The reader — crop and turn the page in front of you', active: st.view === 'page', disabled: !has, on: () => setView('page') },
    ]},
    { group: 'Crop', items: [
      { icon: 'crop', label: 'Crop', title: 'Put a crop rectangle on the page — drag the handles, or type the numbers', disabled: !has, on: proposeCrop },
      { icon: 'check', label: 'Apply crop', title: 'The rectangle becomes the page window (Enter)', disabled: !has, on: applyCrop },
      { icon: 'maximize', label: 'Full page', title: 'Put the window back to the whole page', disabled: !has, on: full },
    ]},
    { group: 'Turn', items: [
      { icon: 'rotate-ccw', label: 'Left', title: 'Turn 90° anticlockwise', disabled: !has, on: () => turn(-90) },
      { icon: 'rotate-cw', label: 'Right', title: 'Turn 90° clockwise', disabled: !has, on: () => turn(90) },
      { icon: 'flip-vertical-2', label: '180°', title: 'Turn upside down', disabled: !has, on: () => turn(180) },
    ]},
    // The page SET — planned on the light table, committed in one engine call. Disabled until there is
    // something to commit, so the row never offers an action that would do nothing.
    { group: 'Organise', items: [
      { icon: 'copy-plus', label: 'Insert blank', title: 'Insert a blank page after the selection', disabled: !has, on: insertBlank },
      { icon: 'copy', label: 'Duplicate', title: 'Duplicate the selected page(s) — a real copy, editable on its own', disabled: !st.sel.size, on: duplicateSel },
      { icon: 'trash-2', label: 'Delete', title: 'Remove the selected page(s) from the set', disabled: !st.sel.size, on: removeSel },
      { icon: 'arrow-up-down', label: 'Reverse', title: 'Reverse the whole document', disabled: !has, on: reverse },
    ]},
    { group: 'Page set', items: [
      // Named for what it commits: "Apply" alone sat in the same ribbon as "Apply crop", and two
      // commands with one name is one command a user picks wrong.
      { icon: 'check', label: 'Apply set', title: 'Commit the new page set — the engine remaps the outline, the links and the structure', disabled: !dirty(), on: applyPlan },
      { icon: 'undo-2', label: 'Reset', title: 'Back to the document as it is', disabled: !dirty(), on: () => setPlan(null) },
    ]},
    // Scope is a capability, not a setting in a dialog: what a command will touch must be visible
    // where the command is. A book scanned sideways is turned in one gesture.
    { group: 'Apply to', items: [
      { icon: 'file', label: 'This page', title: 'Act on the current page only', active: !st.all, disabled: !has, on: () => { st.all = false; ribbon(); } },
      { icon: 'files', label: 'All pages', title: 'Act on every page of the document', active: st.all, disabled: !has, on: () => { st.all = true; ribbon(); } },
    ]},
  ], 'Drag a page to move it · click to select · drag a rectangle ON a page to crop it');
}

/* -- the tool --------------------------------------------------------------------------------------- */

P.registerTool({
  id: TOOL, label: 'Pages', icon: 'crop', drawer: 'Pages',
  title: 'Pages — crop and turn the page itself',
  onEnter() {
    st.on = true;
    book.eachFrame((doc, idx) => { styleOn(doc); bind(doc, idx); });
    setView(st.view);                                 // the view the tool was left in
    refresh();
  },
  onLeave() {
    st.on = false;
    const host = document.getElementById('pages-table');
    if (host) host.hidden = true;
    st.plan = null; st.sel.clear();                   // an unapplied plan does not survive the tab
    // A gizmo OUTLIVES the tool that made it — it stays bound so re-entering costs nothing, but it must
    // go inert or it keeps answering the pointer under the next tool (Augment inherited Edit's handles
    // exactly this way).
    gizmos.forEach(g => g.set({ enabled: false }));
    book.eachFrame(doc => clean(doc));
    book.flush();                                     // never leave a page half-written
  },
});

// The chassis REPLAYS `frame` for every mounted page when the tool changes — including on the way OUT,
// which is how this tool's stylesheet came back the instant onLeave had removed it. Bind either way
// (binding is idempotent and the gizmo it makes starts inert), but only dress a page while the tool is
// actually up.
P.on?.('frame', (f, idx) => {
  try {
    const d = f.contentDocument;
    bind(d, idx);
    if (st.on) styleOn(d); else clean(d);
  } catch { }
});
P.on?.('page', () => { if (st.on) refresh(); });
P.on?.('book', () => { if (st.on) { st.plan = null; st.sel.clear(); renderTable(); refresh(); } });
P.on?.('close', () => {
  st.plan = null; st.sel.clear(); shots.clear();
  gizmos.forEach(g => g.destroy()); gizmos.clear();
  if (st.on) renderTable();
  refresh();
});
