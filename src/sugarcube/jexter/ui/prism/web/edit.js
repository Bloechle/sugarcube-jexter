// edit.js — the "Edit" tab (augmentations), a SELF-CONTAINED mode module over the
// tool seam, extracted verbatim from the chassis: distribution wiring = ONE
// <script> tag; remove the tag and the tab is gone. The augmentation MODEL stays
// book-level state (P.state.aug — the export bake and closeBook reset read it);
// this module owns every behavior: the sidecar (prism/augment.json, written via
// book.put — the seam's mutation hub), undo, the marquee editor bound on every
// frame (P.on('frame')), media/link/reveal/anim tools, the promise dialogs, the
// edits panel, and the transient overlay renderer (never exported).

import { book } from '/shared/js/book.js';

const P = window.prism;
const state = P.state;
const { toast, esc, goTo } = P;
const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
const SVG_NS = 'http://www.w3.org/2000/svg';
const enc = s => new TextEncoder().encode(s);
const dec = b => new TextDecoder().decode(b);
const pageZip = book.pagePath;                     // the seam's derivation — one authority
const frameDoc = book.frameDoc;
const eachLoadedFrame = (cb) => book.eachFrame((doc, idx) => cb(idx, doc));

let editing = false;
let dirty = false;   // augmentations changed since the last export
let editTool = 'media';   // what a drawn zone creates: 'media' | 'reveal-fade' | 'reveal-zoom' | 'link'

const XHTML_NS = 'http://www.w3.org/1999/xhtml';
const MEDIA_TYPE = {
  mp4: 'video/mp4', webm: 'video/webm', ogv: 'video/ogg', m4v: 'video/mp4',
  mp3: 'audio/mpeg', m4a: 'audio/mp4', oga: 'audio/ogg', wav: 'audio/wav',
  png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp', svg: 'image/svg+xml',
};
let pendingZone = null;   // { idx, rect } awaiting a media pick

const augZip = () => `${state.opfDir}prism/augment.json`;

const augFor = idx => (state.aug.pages[pageZip(idx)] ||= []);
const freshAug = () => ({ _readme: 'PRISM augmentation sidecar. Zones are % of the page; edit in PRISM.', version: '1', generator: 'PRISM', pages: {} });

// Path of `to` relative to the folder of `from` (both zip paths).
function relPath(from, to) {
  const a = from.split('/').slice(0, -1), b = to.split('/');
  while (a.length && b.length && a[0] === b[0]) { a.shift(); b.shift(); }
  return '../'.repeat(a.length) + b.join('/');
}

// Load the sidecar from the (re)opened book, or start fresh.
function loadAug() {
  const raw = state.files?.[augZip()];
  try { state.aug = raw ? JSON.parse(dec(raw)) : freshAug(); } catch { state.aug = freshAug(); }
  state.aug.pages ||= {};
}
function saveAug() {
  dirty = true;
  state.aug.updated = new Date().toISOString();
  book.put(augZip(), enc(JSON.stringify(state.aug, null, 2)));
  book.declare(augZip(), 'application/json');      // idempotent — THE manifest authority
  renderEditList();
}

// Undo: the model is plain data, so a snapshot is a deep JSON clone. Push one
// BEFORE every mutation; undo pops it back and re-renders every loaded page.
const undoStack = [];
function pushUndo() {
  if (!state.aug) return;
  undoStack.push(JSON.stringify(state.aug.pages || {}));
  if (undoStack.length > 40) undoStack.shift();
}
function undo() {
  if (!undoStack.length) { toast('Nothing to undo.', 'neutral'); return; }
  state.aug.pages = JSON.parse(undoStack.pop());
  saveAug();
  eachLoadedFrame((idx, doc) => renderAug(idx, doc));
  toast('Undone.', 'success');
}

const newAugId = () => 'aug_' + Date.now().toString(36) + Math.floor(Math.random() * 1e4).toString(36);
// A zone (% of the page) → its absolute-positioning CSS.
const zoneCss = (z, zi = 10) => `position:absolute;left:${z.x}%;top:${z.y}%;width:${z.w}%;height:${z.h}%;z-index:${zi}`;
// A drawn rect (page units) → a zone in % of the page.
function zoneOf(idx, rect) {
  const p = state.pages[idx], pct = (v, d) => +(v / d * 100).toFixed(3);
  return { x: pct(rect.x, p.w), y: pct(rect.y, p.h), w: pct(rect.w, p.w), h: pct(rect.h, p.h) };
}
// Persist the model + re-render one page's overlay (after any change).
function refreshAug(idx) {
  saveAug();
  const doc = frameDoc(idx); if (doc) renderAug(idx, doc);
}
// Snapshot, append an entry, persist/render, notify.
function commitAug(idx, entry, msg) {
  pushUndo();
  augFor(idx).push(entry);
  refreshAug(idx);
  if (msg) toast(msg, 'success');
}



function setEditing(on) {
  editing = on;
  eachLoadedFrame((idx, doc) => { doc.documentElement.style.cursor = on ? 'crosshair' : ''; renderAug(idx, doc); });
  if (on) { renderEditList(); toast('Edit mode — pick a tool below, drag a zone to add · drag to move · corner to resize · double-click to remove.', 'primary'); }
}

// Marquee drawing (add) + delete, bound once per frame; inert unless editing.
// Also does the initial overlay render for this frame (read mode shows media).
function bindEditor(f, idx) {
  const doc = f.contentDocument, svg = doc.querySelector('svg');
  if (!svg) return;
  if (editing) doc.documentElement.style.cursor = 'crosshair';
  const toUser = (cx, cy) => { const p = svg.createSVGPoint(); p.x = cx; p.y = cy; return p.matrixTransform(svg.getScreenCTM().inverse()); };
  let box = null, p0 = null;

  doc.addEventListener('mousedown', e => {
    if (!editing || e.button !== 0) return;
    e.preventDefault();
    p0 = toUser(e.clientX, e.clientY);
    box = doc.createElementNS(SVG_NS, 'rect');
    box.setAttribute('fill', 'rgba(78,143,31,.18)'); box.setAttribute('stroke', '#4e8f1f');
    box.setAttribute('stroke-width', '1.5'); box.setAttribute('stroke-dasharray', '6 4'); box.setAttribute('data-px-edit', '1');
    svg.appendChild(box);
  });
  doc.addEventListener('mousemove', e => {
    if (!box) return;
    const p = toUser(e.clientX, e.clientY);
    box.setAttribute('x', Math.min(p0.x, p.x)); box.setAttribute('y', Math.min(p0.y, p.y));
    box.setAttribute('width', Math.abs(p.x - p0.x)); box.setAttribute('height', Math.abs(p.y - p0.y));
  });
  doc.addEventListener('mouseup', () => {
    if (!box) return;
    const rect = { x: +box.getAttribute('x') || 0, y: +box.getAttribute('y') || 0, w: +box.getAttribute('width') || 0, h: +box.getAttribute('height') || 0 };
    box.remove(); box = null;
    if (rect.w < 8 || rect.h < 8) return;   // ignore a stray click
    if (editTool === 'media') { pendingZone = { idx, rect }; $('#media-input').click(); }
    else if (editTool === 'link') addLink(idx, rect);
    else if (editTool === 'anim') addAnim(idx, rect);
    else addReveal(idx, rect, editTool === 'reveal-zoom' ? 'zoom' : 'fade');
  });
  // double-click an augmentation (edit mode) to remove it
  doc.addEventListener('dblclick', e => {
    if (!editing) return;
    const el = e.target.closest?.('[data-px-aug], g[data-px-anim]');
    if (el) deleteAug(idx, el.getAttribute('data-px-aug') || el.getAttribute('data-px-anim'));
  });

  renderAug(idx, doc);   // show existing augmentations (read or edit)
}

function onMediaPicked(file) {
  const zone = pendingZone; pendingZone = null;
  if (zone && file) addAugment(zone.idx, zone.rect, file);
}

// Add a media file to the book, record an augmentation entry, render it.
async function addAugment(idx, rect, file) {
  const ext = file.name.split('.').pop().toLowerCase();
  const mime = MEDIA_TYPE[ext] || 'application/octet-stream';
  const kind = mime.startsWith('image/') ? 'image' : mime.startsWith('audio/') ? 'audio' : 'video';
  const name = await (kind === 'image' ? book.addImage : book.addMedia)(new Uint8Array(await file.arrayBuffer()), ext);
  const mediaZip = `${state.opfDir}${kind === 'image' ? 'images' : 'media'}/${name}`;

  commitAug(idx, {
    id: newAugId(), type: kind, mime, zone: zoneOf(idx, rect),
    src: relPath(pageZip(idx), mediaZip), media: mediaZip,
    opts: { controls: true, autoplay: false, loop: false, muted: false },
    note: '', created: new Date().toISOString(),
  }, `Added ${file.name}`);
}

// Duplicate an entry: same params, new id, zone nudged so both stay grabbable.
function duplicateAug(idx, id) {
  const list = state.aug.pages[pageZip(idx)]; if (!list) return;
  const src = list.find(e => e.id === id); if (!src) return;
  const copy = JSON.parse(JSON.stringify(src));
  copy.id = newAugId();
  copy.created = new Date().toISOString();
  copy.zone.x = clamp(copy.zone.x + 3, 0, 100 - copy.zone.w);
  copy.zone.y = clamp(copy.zone.y + 3, 0, 100 - copy.zone.h);
  commitAug(idx, copy, 'Duplicated.');
}

function deleteAug(idx, id) {
  const list = state.aug.pages[pageZip(idx)]; if (!list) return;
  const i = list.findIndex(e => e.id === id); if (i < 0) return;
  pushUndo();
  list.splice(i, 1);
  refreshAug(idx);
  toast('Augmentation removed', 'success');
}

// Edit an entry: anim reopens its settings dialog; others edit the note
// (baked as the element title — a portable tooltip).
async function editNote(idx, entry) {
  if (entry.type === 'anim') {
    const r = await askAnim(entry);
    if (!r) return;
    pushUndo();
    Object.assign(entry, r);
    refreshAug(idx);
    return;
  }
  const val = await askText({ title: 'Note / caption', label: 'Shown as a tooltip on the element', value: entry.note || '' });
  if (val === null) return;   // cancelled
  pushUndo();
  entry.note = val;
  refreshAug(idx);
}

// Add a reveal-on-tap zone (a CSS-only spoiler cover — no file, no scripting).
function addReveal(idx, rect, effect) {
  commitAug(idx, {
    id: newAugId(), type: 'reveal', effect: effect === 'zoom' ? 'zoom' : 'fade',
    zone: zoneOf(idx, rect), note: '', created: new Date().toISOString(),
  }, 'Reveal zone added — tap to reveal; set its label via the note tab.');
}

// Add a hotspot: internal page jump or external URL (a plain anchor when baked).
async function addLink(idx, rect) {
  const ans = await askText({ title: 'Add link', label: `Page number (1–${state.pages.length}) or URL`, placeholder: 'e.g. 5 or https://…' });
  if (!ans) return;
  const s = ans;
  let kind, to;
  if (/^https?:\/\//i.test(s)) { kind = 'url'; to = s; }
  else {
    const n = parseInt(s, 10);
    if (!n || n < 1 || n > state.pages.length) { toast(`Enter a page 1–${state.pages.length} or an http(s) URL.`, 'warning'); return; }
    kind = 'page'; to = n - 1;
  }
  commitAug(idx, {
    id: newAugId(), type: 'link', kind, to, zone: zoneOf(idx, rect), note: '', created: new Date().toISOString(),
  }, 'Link added.');
}

// Animate the SVG content inside a zone (entrance: fade / rise / zoom, staggered).
// The selection is COMPILED here, in the live frame (geometry available), into
// structural indices {t, i} — the metadata that lets render AND bake re-apply
// it without any layout engine.
async function addAnim(idx, rect) {
  const doc = frameDoc(idx); if (!doc) return;
  const zone = zoneOf(idx, rect);
  const els = selectEls(doc, idx, zone);
  if (!els.length) { toast('No SVG content in that zone.', 'warning'); return; }
  const r = await askAnim({});
  if (!r) return;
  commitAug(idx, {
    id: newAugId(), type: 'anim', ...r, els, zone, created: new Date().toISOString(),
  }, `Animating ${els.length} element${els.length > 1 ? 's' : ''} (${r.effect}${r.mode === 'together' ? ', together' : ', staggered'}).`);
}

// SVG elements intersecting a zone, as stable structural indices. The frame's
// client space IS page units (the scale lives on the iframe element outside),
// and our transient rects (marquee, highlights) are <rect> — never matched.
function selectEls(doc, idx, zone) {
  const svg = doc.querySelector('svg'); if (!svg) return [];
  const p = state.pages[idx];
  const zx = zone.x / 100 * p.w, zy = zone.y / 100 * p.h, zw = zone.w / 100 * p.w, zh = zone.h / 100 * p.h;
  const out = [];
  for (const t of ['text', 'path', 'image']) {
    svg.querySelectorAll(t).forEach((el, i) => {
      const r = el.getBoundingClientRect();
      if (r.width && r.height && r.left < zx + zw && r.left + r.width > zx && r.top < zy + zh && r.top + r.height > zy)
        out.push({ t, i });
    });
  }
  return out;
}

/* -- Modal inputs: promise-based Shoelace dialogs -------------------- */

// Show a dialog; resolve with read() on OK, null on cancel/escape/overlay.
function askDialog(dlg, okBtn, read) {
  return new Promise(resolve => {
    let settled = false;
    const done = v => { settled = true; cleanup(); resolve(v); dlg.hide(); };
    const onOk = () => done(read());
    const onKey = e => { if (e.key === 'Enter' && e.target.tagName === 'SL-INPUT') { e.preventDefault(); onOk(); } };
    const onHide = () => { if (!settled) { settled = true; cleanup(); resolve(null); } };
    const cleanup = () => { okBtn.removeEventListener('click', onOk); dlg.removeEventListener('keydown', onKey); dlg.removeEventListener('sl-after-hide', onHide); };
    okBtn.addEventListener('click', onOk);
    dlg.addEventListener('keydown', onKey);
    dlg.addEventListener('sl-after-hide', onHide);
    dlg.show();
  });
}

// One-line text input (replaces window.prompt).
function askText({ title, label = '', value = '', placeholder = '' }) {
  const dlg = $('#ask-dialog'), input = $('#ask-input');
  dlg.setAttribute('label', title);
  input.label = label; input.value = value; input.placeholder = placeholder;
  setTimeout(() => input.focus(), 60);
  return askDialog(dlg, $('#ask-ok'), () => input.value.trim());
}

// Animation settings (effect · apply-to · duration · per-element delay · note).
function askAnim(init = {}) {
  const effect = $('#anim-effect'), mode = $('#anim-mode'), dur = $('#anim-dur'), stag = $('#anim-stagger'), note = $('#anim-note');
  effect.value = init.effect || 'rise';
  mode.value = init.mode || 'each';
  dur.value = String(init.dur ?? 0.7);
  stag.value = String(init.stagger ?? 120);
  note.value = init.note || '';
  stag.cls(mode.value === 'each' ? '-px-hide' : '+px-hide');
  return askDialog($('#anim-dialog'), $('#anim-ok'), () => ({
    effect: effect.value, mode: mode.value, dur: +dur.value, stagger: +stag.value, note: note.value.trim(),
  }));
}


/* -- Edits panel: the current page's augmentations as a live list ---- */

const TYPE_ICON = { video: 'film', audio: 'volume-2', image: 'image', reveal: 'eye', link: 'link', anim: 'wand-2' };
function augRowLabel(e) {
  if (e.note) return e.note;
  if (e.type === 'link') return 'Link → ' + linkLabel(e);
  if (e.type === 'reveal') return `Reveal (${e.effect || 'fade'})`;
  if (e.type === 'anim') return `Anim (${e.effect} ${e.dur ?? 0.7}s) · ${e.els?.length || 0}`;
  return e.type.charAt(0).toUpperCase() + e.type.slice(1);
}
function mkBtn(into, icon, title, fn) {
  const b = $.create('button', { class: 'er-btn', title, html: `<i data-lucide="${icon}"></i>` });
  b.on('click', e => { e.stopPropagation(); fn(); });
  b.mount(into);
}
function renderEditList() {
  const out = $.opt('#edit-list'); if (!out) return;
  out.empty();
  const info = $.opt('#edits-info');
  if (state.current < 0 || !state.aug) { info?.text(''); return; }
  const list = state.aug.pages?.[pageZip(state.current)] || [];
  info?.text(list.length ? `${list.length} on this page` : 'None on this page — drag a zone.');
  list.forEach((e, i) => {
    const li = $.create('li');
    const row = $.create('a', { class: 'nav-link edit-row', html: `<i data-lucide="${TYPE_ICON[e.type] || 'square'}"></i><span class="er-lbl">${esc(augRowLabel(e))}</span>` });
    row.on('click', () => { goTo(state.current); flashAug(state.current, e.id); });
    const acts = $.create('span', { class: 'er-acts' });
    mkBtn(acts, 'chevron-up', 'Send backward', () => reorderAug(state.current, i, -1));
    mkBtn(acts, 'chevron-down', 'Bring forward', () => reorderAug(state.current, i, +1));
    mkBtn(acts, 'pencil', 'Edit note/label', () => editNote(state.current, e));
    mkBtn(acts, 'copy', 'Duplicate', () => duplicateAug(state.current, e.id));
    mkBtn(acts, 'trash-2', 'Remove', () => deleteAug(state.current, e.id));
    acts.mount(row); row.mount(li); li.mount(out);
  });
  window.lucide?.createIcons();
}

function reorderAug(idx, i, dir) {
  const list = state.aug.pages[pageZip(idx)]; if (!list) return;
  const j = i + dir; if (j < 0 || j >= list.length) return;
  pushUndo();
  [list[i], list[j]] = [list[j], list[i]];   // array order = stacking order
  saveAug();
  const doc = frameDoc(idx); if (doc) renderAug(idx, doc);
}

// Briefly outline an augmentation in its frame (after jumping to its page).
function flashAug(idx, id) {
  const doc = frameDoc(idx); if (!doc) return;
  const wraps = doc.querySelectorAll(`g[data-px-anim="${id}"]`);
  if (wraps.length) {   // replay the entrance, stagger included
    wraps.forEach(g => { g.style.animation = 'none'; });
    requestAnimationFrame(() => wraps.forEach(g => { g.style.animation = ''; }));
    return;
  }
  const el = doc.querySelector(`[data-px-aug="${id}"]`); if (!el) return;
  const prev = el.style.outline;
  el.style.outline = '3px solid #74b73e'; el.style.outlineOffset = '2px';
  setTimeout(() => { el.style.outline = prev; el.style.outlineOffset = ''; }, 1200);
}

// Build the clean media element for an entry (used by overlay + export bake).
// fill=true → 100% of a wrapper (edit); fill=false → absolute at the zone.
function buildMedia(doc, entry, fill) {
  const box = fill ? 'position:absolute;inset:0;width:100%;height:100%' : zoneCss(entry.zone);
  let el;
  if (entry.type === 'image') {
    el = doc.createElementNS(XHTML_NS, 'img');
    el.setAttribute('src', entry.src);
    el.setAttribute('style', box + ';object-fit:contain');
  } else {
    el = doc.createElementNS(XHTML_NS, entry.type === 'audio' ? 'audio' : 'video');
    const o = entry.opts || {};
    if (o.controls !== false) el.setAttribute('controls', 'controls');
    if (o.autoplay) el.setAttribute('autoplay', 'autoplay');
    if (o.loop) el.setAttribute('loop', 'loop');
    if (o.muted) el.setAttribute('muted', 'muted');
    el.setAttribute('src', entry.src);
    el.setAttribute('style', box + ';object-fit:contain;background:#000');
  }
  if (entry.note) el.setAttribute('title', entry.note);   // portable caption/tooltip
  return el;
}

// Inject a small edit stylesheet into a frame (transient — never exported).
function ensureEditStyle(doc) {
  if (doc.getElementById('px-edit-style')) return;
  const st = doc.createElementNS(XHTML_NS, 'style');
  st.setAttribute('id', 'px-edit-style');
  st.textContent = '.px-aug{outline:2px solid #4e8f1f;outline-offset:-1px}'
    + '.px-aug-shield{position:absolute;inset:0;cursor:move;background:rgba(78,143,31,.05)}'
    + '.px-aug-resize{position:absolute;right:-7px;bottom:-7px;width:14px;height:14px;border-radius:3px;'
    + 'background:#4e8f1f;border:2px solid #fff;cursor:nwse-resize;box-shadow:0 1px 4px rgba(0,0,0,.45)}'
    + '.px-aug-note{position:absolute;left:-2px;top:-24px;height:22px;padding:0 7px;display:inline-flex;'
    + 'align-items:center;font:600 12px/1 system-ui,sans-serif;color:#fff;background:#4e8f1f;border:0;'
    + 'border-radius:6px 6px 6px 0;cursor:text;white-space:nowrap;max-width:220px;overflow:hidden;'
    + 'text-overflow:ellipsis;box-shadow:0 2px 6px rgba(0,0,0,.4)}'
    + '.px-prev{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;'
    + 'font:600 clamp(11px,3vw,18px)/1.2 system-ui,sans-serif;color:#4e8f1f;background:rgba(78,143,31,.10);'
    + 'border:1px dashed #4e8f1f;border-radius:6px}';
  (doc.querySelector('head') || doc.documentElement).appendChild(st);
}

// Reveal-on-tap: a CSS checkbox toggles a cover panel — portable, no scripting.
// fill=true → just the cover (edit preview); else → the interactive label.
function buildReveal(doc, entry, fill) {
  const label = entry.note || 'Tap to reveal';
  if (fill) {
    const cover = doc.createElementNS(XHTML_NS, 'div');
    cover.setAttribute('class', 'px-reveal-cover');
    cover.setAttribute('style', 'position:absolute;inset:0;pointer-events:none');
    cover.textContent = label;
    return cover;
  }
  const wrap = doc.createElementNS(XHTML_NS, 'label');
  wrap.setAttribute('class', 'px-reveal' + (entry.effect === 'zoom' ? ' fx-zoom' : ''));
  wrap.setAttribute('style', zoneCss(entry.zone));
  const input = doc.createElementNS(XHTML_NS, 'input');
  input.setAttribute('type', 'checkbox'); input.setAttribute('style', 'position:absolute;opacity:0;width:0;height:0');
  const cover = doc.createElementNS(XHTML_NS, 'span');
  cover.setAttribute('class', 'px-reveal-cover'); cover.textContent = label;
  wrap.appendChild(input); wrap.appendChild(cover);
  return wrap;
}

// Link hotspot. href is resolved relative to the source page (portable when
// baked); the overlay intercepts internal jumps to drive the reader instead.
const linkLabel = entry => entry.kind === 'url' ? entry.to : `p.${(entry.to | 0) + 1}`;
function linkHref(srcZip, entry) {
  if (entry.kind === 'url') return entry.to;
  return relPath(srcZip, pageZip(clamp(entry.to | 0, 0, state.pages.length - 1)));
}
function buildLink(doc, srcZip, entry, fill) {
  if (fill) {
    const d = doc.createElementNS(XHTML_NS, 'div');
    d.setAttribute('class', 'px-prev'); d.setAttribute('style', 'position:absolute;inset:0;pointer-events:none');
    d.textContent = '→ ' + linkLabel(entry);
    return d;
  }
  const a = doc.createElementNS(XHTML_NS, 'a');
  a.setAttribute('class', 'px-link');
  a.setAttribute('style', zoneCss(entry.zone));
  a.setAttribute('href', linkHref(srcZip, entry));
  if (entry.note) a.setAttribute('title', entry.note);
  if (entry.kind === 'url') { a.setAttribute('target', '_blank'); a.setAttribute('rel', 'noopener'); }
  return a;
}

// Runtime CSS for reveal + link — embedded in the page so it works in PRISM
// *and* other readers (portable, no scripting).
function ensureAugCss(doc) {
  if (doc.getElementById('px-aug-style')) return;
  const st = doc.createElementNS(XHTML_NS, 'style');
  st.setAttribute('id', 'px-aug-style');
  st.textContent = '.px-reveal{display:block;cursor:pointer}'
    + '.px-reveal-cover{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;'
    + 'text-align:center;padding:.4em;box-sizing:border-box;font:600 clamp(11px,3.2vw,20px)/1.2 system-ui,sans-serif;'
    + 'color:#fff;background:#4e8f1f;border-radius:6px;transition:opacity .45s ease,transform .45s ease}'
    + '.px-reveal input:checked ~ .px-reveal-cover{opacity:0;pointer-events:none}'
    + '.px-reveal.fx-zoom input:checked ~ .px-reveal-cover{transform:scale(1.08)}'
    + '.px-link{display:block;cursor:pointer}'
    + '.px-link:hover{background:rgba(78,143,31,.12);outline:1px solid rgba(78,143,31,.5)}'
    + '@keyframes px-fade{from{opacity:0}}'
    + '@keyframes px-rise{from{opacity:0;transform:translateY(12px)}}'
    + '@keyframes px-zoom{from{opacity:0;transform:scale(.85)}}'
    + '@keyframes px-slide{from{opacity:0;transform:translateX(-14px)}}'
    + 'g[data-px-anim]{transform-box:fill-box;transform-origin:center;animation:px-rise .7s ease-out backwards}'
    + 'g[data-px-anim].px-fx-fade{animation-name:px-fade}'
    + 'g[data-px-anim].px-fx-zoom{animation-name:px-zoom}'
    + 'g[data-px-anim].px-fx-slide{animation-name:px-slide}';
  (doc.querySelector('head') || doc.documentElement).appendChild(st);
}
// Dispatch to the right builder for an entry's type (shared by overlay + bake).
function buildAugEl(doc, srcZip, entry, fill) {
  return entry.type === 'reveal' ? buildReveal(doc, entry, fill)
       : entry.type === 'link' ? buildLink(doc, srcZip, entry, fill)
       : buildMedia(doc, entry, fill);
}

// Entrance animation, applied in place: each compiled element is wrapped in its
// own <g> (paint order and attribute transforms untouched — CSS transform on a
// bare <g> is safe) and the wrapper animates, staggered by index. Works on the
// live frame AND on a DOMParser copy (bake): no layout needed, els are indices.
function unwrapAnims(doc) {
  doc.querySelectorAll('g[data-px-anim]').forEach(g => {
    while (g.firstChild) g.parentNode.insertBefore(g.firstChild, g);
    g.remove();
  });
}
function applyAnim(doc, entry) {
  const svg = doc.querySelector('svg'); if (!svg || !entry.els?.length) return;
  ensureAugCss(doc);
  entry.els.forEach(({ t, i }, j) => {
    const el = svg.querySelectorAll(t)[i]; if (!el) return;
    const g = doc.createElementNS(SVG_NS, 'g');
    g.setAttribute('data-px-anim', entry.id);
    g.setAttribute('class', 'px-fx-' + (entry.effect || 'rise'));
    const delay = entry.mode === 'together' ? 0 : (j * (entry.stagger ?? 120)) / 1000;
    g.setAttribute('style', `animation-duration:${entry.dur ?? 0.7}s;animation-delay:${delay}s`);
    el.parentNode.insertBefore(g, el);
    g.appendChild(el);
  });
}

// Render the page's augmentations as a transient overlay. Strips any prior
// overlay AND any baked media first, so PRISM is always driven by the model.
function renderAug(idx, doc) {
  const body = doc.querySelector('body') || doc.body; if (!body) return;
  unwrapAnims(doc);
  body.querySelectorAll('[data-px-aug]').forEach(n => n.remove());
  const list = state.aug?.pages?.[pageZip(idx)];
  if (!list || !list.length) return;

  const bs = body.getAttribute('style') || '';
  if (!/position\s*:/.test(bs)) body.setAttribute('style', (bs ? bs + ';' : '') + 'position:relative');
  if (list.some(e => e.type === 'reveal' || e.type === 'link')) ensureAugCss(doc);
  if (editing) ensureEditStyle(doc);

  const srcZip = pageZip(idx);
  list.forEach(entry => {
    if (entry.type === 'anim') { applyAnim(doc, entry); if (!editing) return; }
    if (!editing) {
      const el = buildAugEl(doc, srcZip, entry, false);
      el.setAttribute('data-px-aug', entry.id);
      if (entry.type === 'link' && entry.kind === 'page')   // in-reader jump, not an iframe navigation
        el.addEventListener('click', e => { e.preventDefault(); goTo(clamp(entry.to | 0, 0, state.pages.length - 1)); });
      body.appendChild(el);
      return;
    }
    const wrap = doc.createElementNS(XHTML_NS, 'div');
    wrap.setAttribute('class', 'px-aug'); wrap.setAttribute('data-px-aug', entry.id);
    applyZone(wrap, entry.zone);
    const inner = entry.type === 'anim' ? animPreview(doc, entry) : buildAugEl(doc, srcZip, entry, true);
    if (!['reveal', 'link', 'anim'].includes(entry.type)) inner.setAttribute('style', inner.getAttribute('style') + ';pointer-events:none');
    const shield = doc.createElementNS(XHTML_NS, 'div'); shield.setAttribute('class', 'px-aug-shield');
    const handle = doc.createElementNS(XHTML_NS, 'div'); handle.setAttribute('class', 'px-aug-resize');
    const note = doc.createElementNS(XHTML_NS, 'button'); note.setAttribute('class', 'px-aug-note');
    note.setAttribute('type', 'button'); note.textContent = entry.note || (entry.type === 'reveal' ? '＋ label' : entry.type === 'link' ? linkLabel(entry) : entry.type === 'anim' ? `anim · ${entry.els?.length || 0}` : '＋ note');
    note.addEventListener('mousedown', e => { e.preventDefault(); e.stopPropagation(); });
    note.addEventListener('click', e => { e.preventDefault(); e.stopPropagation(); editNote(idx, entry); });
    wrap.appendChild(inner); wrap.appendChild(shield); wrap.appendChild(handle); wrap.appendChild(note);
    body.appendChild(wrap);
    bindHandles(idx, wrap, entry, shield, handle);
  });
}

// Edit-mode preview panel for an anim zone (transient).
function animPreview(doc, entry) {
  const d = doc.createElementNS(XHTML_NS, 'div');
  d.setAttribute('class', 'px-prev'); d.setAttribute('style', 'position:absolute;inset:0;pointer-events:none');
  d.textContent = `✦ ${entry.effect} · ${entry.els?.length || 0}`;
  return d;
}

function applyZone(wrap, z) {
  wrap.setAttribute('style', zoneCss(z, 20));
}

// Drag the shield to move, the corner to resize; commit (persist) on mouseup.
function bindHandles(idx, wrap, entry, shield, handle) {
  const { w: W, h: H } = state.pages[idx];
  const doc = wrap.ownerDocument;
  const start = (e, mode) => {
    e.preventDefault(); e.stopPropagation();
    pushUndo();   // one snapshot per drag → a single undo reverts the whole move/resize
    const sx = e.clientX, sy = e.clientY, z0 = { ...entry.zone };
    const move = ev => {
      const dxp = (ev.clientX - sx) / W * 100, dyp = (ev.clientY - sy) / H * 100;
      if (mode === 'move') {
        entry.zone.x = +clamp(z0.x + dxp, 0, 100 - z0.w).toFixed(3);
        entry.zone.y = +clamp(z0.y + dyp, 0, 100 - z0.h).toFixed(3);
      } else {
        entry.zone.w = +clamp(z0.w + dxp, 3, 100 - z0.x).toFixed(3);
        entry.zone.h = +clamp(z0.h + dyp, 3, 100 - z0.y).toFixed(3);
      }
      applyZone(wrap, entry.zone);
    };
    const up = () => {
      doc.removeEventListener('mousemove', move); doc.removeEventListener('mouseup', up);
      if (entry.type === 'anim') { entry.els = selectEls(doc, idx, entry.zone); renderAug(idx, doc); }
      saveAug();
    };
    doc.addEventListener('mousemove', move); doc.addEventListener('mouseup', up);
  };
  shield.addEventListener('mousedown', e => start(e, 'move'));
  handle.addEventListener('mousedown', e => start(e, 'resize'));
}

/* -- Export: bake the model into page copies, re-zip a valid .epub -- */

// Serialize a DOM document to XML with exactly one leading declaration —
// some engines already emit one from serializeToString, which would produce a
// duplicate <?xml?> and break EPUB parsers.

/* ── the tab: registration, seam subscriptions, chrome wiring ─────────── */

// experimental: the tab is hidden unless dev mode is on (Ctrl/Cmd+Shift+D). The module stays fully
// loaded and wired — drop this one flag the day the editor ships.
P.registerMode({ id: 'edit', label: 'Edit', icon: 'pencil', title: 'Edit — augmentations', experimental: true,
                 onEnter: () => setEditing(true), onLeave: () => setEditing(false) });

P.on('book', () => loadAug());                     // read prism/augment.json (re-edit) or start fresh
P.on('frame', (f, idx) => bindEditor(f, idx));
P.on('page', () => { if (state.aug) renderEditList(); });
P.on('close', () => { undoStack.length = 0; dirty = false; pendingZone = null; editing = false; });

$.all('.px-tool').forEach(b => b.on('click', () => {
  $.all('.px-tool').forEach(x => x.cls('-active')); b.cls('+active');
  editTool = b.attr('data-tool');
}));
$('#ask-cancel').on('click', () => $('#ask-dialog').hide());
$('#anim-cancel').on('click', () => $('#anim-dialog').hide());
$('#anim-mode').on('sl-change', () => $('#anim-stagger').cls($('#anim-mode').value === 'each' ? '-px-hide' : '+px-hide'));
$('#media-input').on('change', async e => {
  const file = e.target.files[0];
  if (file) await onMediaPicked(file);
  e.target.value = '';
});
$.opt('#m-undo')?.on('click', () => undo());       // the tool's menu entry + shortcut
document.addEventListener('keydown', e => {       // the tool's shortcut: undo an augmentation
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z' && state.id) { e.preventDefault(); undo(); }
});
window.addEventListener('beforeunload', e => {   // unexported edits would be lost with the tab
  if (dirty && Object.values(state.aug?.pages || {}).some(l => l.length)) e.preventDefault();
});
