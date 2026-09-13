/* forms.js — "Forms": the document's form fields, filled where they are.
 *
 * A field is LIVE DATA. The source's appearance streams are never imported and no projection bakes a
 * field into a page, so a form lives in ONE place — `ocd/annots.json` (FORMAT §A5) — and a reader
 * that only paints pages shows nothing at all where a form is. That is the defect this tool answers:
 * it draws the fields from their own rectangles as `data-ui` chrome, and it is the one place a value
 * is typed.
 *
 * Nothing here writes a content node. The overlay is stripped by persist(); what is committed is the
 * MEMBER, and the engine states the form again as a real AcroForm on the next PDF export
 * (write/PdfForms) — so what you type here is what a reader can fill there.
 *
 * Geometry: a field's rect is PAGE space (origin bottom-left, y up) and the page's frame is its
 * data-mediabox (FORMAT §B2) — never the viewBox, which is the crop. The widget's own `rotate` is
 * INDEPENDENT of the page's: a form on a turned page carries 90 on every field, and an overlay that
 * ignores it writes its labels across the boxes.
 */
import { book } from '/shared/js/book.js';
import { SVG_NS } from '/shared/js/ocd.js';

const P = window.prism;
const TOOL = 'forms';
const MEMBER = 'ocd/annots.json';

const CSS = `
svg .fm-layer text { font-family:system-ui, sans-serif; fill:#2b3138; pointer-events:none; }
svg .fm-box { fill:#fafbfc; fill-opacity:.72; stroke:#c9ced4; stroke-width:.75;
              vector-effect:non-scaling-stroke; cursor:text; }
svg .fm-box:hover { stroke:var(--jx-brand-bright, #5e9bd6); }
svg .fm-box.fm-sel { fill-opacity:.9; stroke:var(--jx-brand-bright, #5e9bd6); stroke-width:1.6; }
svg .fm-tag { font-size:7px; fill:#8a9099; }
/* The editor IS the field: an input the size of the box, inside the page's own coordinate system, so
   it inherits the page rotation, the widget rotation and the zoom without computing any of them. */
svg .fm-edit { overflow:visible; }
svg .fm-edit input, svg .fm-edit textarea {
  width:100%; height:100%; box-sizing:border-box; margin:0; padding:2px 3px;
  font-family:system-ui, sans-serif; color:#2b3138; background:transparent;
  border:0; outline:0; resize:none; overflow:hidden; line-height:1.25;
}
`;

const XHTML_NS = 'http://www.w3.org/1999/xhtml';

/* -- state ------------------------------------------------------------------------------------- */
// `list` is the WHOLE form, flat and in document order: a field is addressed by its position here, in
// the drawer, in the ribbon and in the overlay alike, so the four can never point at different things.
const st = { on: false, list: [], sel: -1, names: false, dirty: false, focus: false };

const has = () => st.list.length > 0;
const cur = () => (st.sel >= 0 ? st.list[st.sel] : null);

/* -- the member: read, and written back ---------------------------------------------------------- */

/** Read `ocd/annots.json` into one flat list. The member keys pages by POSITION (`p1` is page 1), the
 *  same key the engine's reader parses back — so the index is the key minus one, and nothing here
 *  guesses from a file name. */
function read() {
  st.list = []; st.sel = -1;
  if (!book.isOpen?.()) return;
  // book.json is the read-MUTATE-write seam; reading alone is book.get on the same path, so the
  // member's absence (a document with no form) is a null and never a parse exception.
  const raw = book.get(book.member(MEMBER));
  if (!raw) return;
  let data; try { data = JSON.parse(new TextDecoder().decode(raw)); } catch { return; }
  const pages = data?.pages;
  if (!pages) return;
  for (const key of Object.keys(pages).sort(byPage)) {
    const idx = (parseInt(key.replace(/^\D+/, ''), 10) || 1) - 1;
    for (const f of pages[key].fields || []) st.list.push({ page: idx, key, f });
  }
}
const byPage = (a, b) => (parseInt(a.replace(/^\D+/, ''), 10) || 0) - (parseInt(b.replace(/^\D+/, ''), 10) || 0);

/** Commit the values to the member. The whole tool's write surface is this one function: it re-reads
 *  the member and sets the values by position, so a field the tool never touched is left exactly as the
 *  engine wrote it — including the keys this tool does not display (border colours, maxlen, flags). */
// Typing commits on its own, shortly after the last keystroke — NOT only when the field is left. The
// export reads the MEMBER, and a reader who types into the last field and exports straight away would
// otherwise ship a form missing exactly what they just wrote (measured: 4 of 5 values in the member,
// the fifth still on screen). Leaving the field, leaving the tool and closing the book still commit
// at once; this only makes the window where they disagree a fraction of a second wide.
// The ribbon's Value field catches up on the same beat: it is a SECOND view of the value being typed,
// and a second view that only refreshes when the field is left is one of them drifting.
let timer = null;
const scheduleSave = () => { clearTimeout(timer); timer = setTimeout(() => { save(); ribbon(); }, 400); };

async function save() {
  clearTimeout(timer);
  if (!st.dirty) return;
  st.dirty = false;
  if (!book.get(book.member(MEMBER))) return;
  const per = new Map();
  for (const it of st.list) (per.get(it.key) || per.set(it.key, []).get(it.key)).push(it.f.value || '');
  await book.json(MEMBER, (o) => {
    for (const [key, vals] of per) {
      const fields = o.pages?.[key]?.fields;
      if (!fields) continue;
      fields.forEach((f, i) => { if (vals[i]) f.value = vals[i]; else delete f.value; });
    }
  });
}

/* -- the overlay: one data-ui layer per page ------------------------------------------------------ */

function styleOn(doc) {
  if (doc.getElementById('fm-style')) return;
  const s = doc.createElement('style');
  s.id = 'fm-style'; s.setAttribute('data-ui', ''); s.textContent = CSS;
  doc.head.appendChild(s);
}

/** The page's own frame — the MEDIA box. A crop is a window over it (the viewBox), so a cropped page
 *  must not shift its fields: the flip is anchored here and nowhere else. */
function frame(svg) {
  const m = (svg.getAttribute('data-mediabox') || '').trim().split(/[\s,]+/).map(Number);
  if (m.length === 4 && m.every(Number.isFinite)) return { x: m[0], y: m[1], w: m[2], h: m[3] };
  return { x: 0, y: 0, w: +svg.getAttribute('width') || 0, h: +svg.getAttribute('height') || 0 };
}
const toSvg = (b, r) => ({ x: r[0] - b.x, y: b.y + b.h - r[1] - r[3], w: r[2], h: r[3] });

function layer(doc) {
  const svg = doc.querySelector('svg[data-ocd="page"]'); if (!svg) return null;
  let g = svg.querySelector('.fm-layer');
  if (!g) {
    g = doc.createElementNS(SVG_NS, 'g');
    g.setAttribute('class', 'fm-layer'); g.setAttribute('data-ui', ''); g.setAttribute('data-z', 'over');
    svg.appendChild(g);
  }
  while (g.firstChild) g.removeChild(g.firstChild);
  return g;
}

function draw(doc, idx) {
  const g = layer(doc); if (!g) return;
  const svg = doc.querySelector('svg[data-ocd="page"]');
  const b = frame(svg);
  st.list.forEach((it, i) => {
    if (it.page !== idx || !Array.isArray(it.f.rect)) return;
    const s = toSvg(b, it.f.rect);
    const rot = ((it.f.rotate || 0) % 360 + 360) % 360;
    const box = doc.createElementNS(SVG_NS, 'rect');
    box.setAttribute('x', s.x); box.setAttribute('y', s.y);
    box.setAttribute('width', s.w); box.setAttribute('height', s.h);
    box.setAttribute('rx', Math.min(3.5, Math.min(s.w, s.h) / 4));
    box.setAttribute('class', 'fm-box' + (i === st.sel ? ' fm-sel' : ''));
    box.setAttribute('data-fm', String(i));
    const t = doc.createElementNS(SVG_NS, 'title');
    t.textContent = `${it.f.name || '(unnamed)'} — ${it.f.type || 'text'}`;
    box.appendChild(t);
    g.appendChild(box);

    // The selected field is EDITABLE IN PLACE. A box with a text cursor that cannot be typed into is a
    // lie the reader pays for: the value had to be entered in the ribbon, which is discoverable only by
    // having read the help line. The ribbon field stays — it is how you edit without hunting for the
    // box — but the box is now the obvious way, and both write the same value.
    if (i === st.sel && !st.names) { editor(doc, g, it, s, rot); return; }

    const text = st.names ? (it.f.name || '(unnamed)') : (it.f.value || '');
    if (!text) return;
    // The widget's own rotation turns the CAPTION inside the box, exactly as /MK /R turns a real
    // appearance: the box itself is axis-aligned in page space, the writing inside it is not.
    const cx = s.x + s.w / 2, cy = s.y + s.h / 2;
    const turned = rot === 90 || rot === 270;
    const wide = turned ? s.h : s.w, high = turned ? s.w : s.h;
    const size = Math.max(6, Math.min(11, high * (it.f.multiline ? 0.28 : 0.55)));
    const lines = String(text).split('\n').slice(0, Math.max(1, Math.floor(high / (size * 1.25))));
    lines.forEach((ln, k) => {
      const e = doc.createElementNS(SVG_NS, 'text');
      e.setAttribute('x', -wide / 2 + 3);
      e.setAttribute('y', it.f.multiline ? -high / 2 + size * (k + 1.1) : size * 0.36);
      e.setAttribute('font-size', size);
      e.setAttribute('class', st.names ? 'fm-tag' : '');
      e.setAttribute('transform', `translate(${cx} ${cy}) rotate(${-rot})`);
      e.textContent = clip(ln, wide, size);
      g.appendChild(e);
    });
  });
}

/** The in-place editor: one `<foreignObject>` the size of the box, carrying a real input. It sits in
 *  the page's own space, so the page's rotation, the widget's own and the reader's zoom apply to it
 *  exactly as they apply to the box — nothing here maps a screen coordinate, which is the part that
 *  goes wrong when an editor floats over a frame instead of living inside it.
 *
 *  Typing does NOT repaint: the layer is rebuilt on every paint, and rebuilding the element you are
 *  typing into takes the focus and the caret with it. The model is updated on every keystroke, the
 *  page and the ribbon on commit. */
function editor(doc, g, it, s, rot) {
  const turned = rot === 90 || rot === 270;
  const w = turned ? s.h : s.w, h = turned ? s.w : s.h;
  const size = Math.max(6, Math.min(11, h * (it.f.multiline ? 0.28 : 0.55)));
  const fo = doc.createElementNS(SVG_NS, 'foreignObject');
  fo.setAttribute('class', 'fm-edit');
  fo.setAttribute('x', -w / 2); fo.setAttribute('y', -h / 2);
  fo.setAttribute('width', w);  fo.setAttribute('height', h);
  fo.setAttribute('transform', `translate(${s.x + s.w / 2} ${s.y + s.h / 2}) rotate(${-rot})`);
  const el = doc.createElementNS(XHTML_NS, it.f.multiline ? 'textarea' : 'input');
  if (!it.f.multiline) el.setAttribute('type', 'text');
  if (it.f.maxlen) el.setAttribute('maxlength', String(it.f.maxlen));
  if (it.f.readonly) el.setAttribute('readonly', '');
  el.setAttribute('style', `font-size:${size}px`);
  el.setAttribute('data-fm-edit', '');
  el.value = it.f.value || '';
  el.addEventListener('input', () => { it.f.value = el.value; st.dirty = true; drawer(); scheduleSave(); });
  el.addEventListener('blur', commit);
  el.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' || (e.key === 'Enter' && !it.f.multiline)) { e.preventDefault(); el.blur(); }
    e.stopPropagation();                       // the reader's own keys (arrows, page keys) are not typing
  });
  fo.appendChild(el);
  g.appendChild(fo);
  // Selecting a field puts the caret in it: picking and typing are ONE gesture. Focus is given on the
  // NEXT task, not here: this runs in the pointerdown capture phase, and the browser's own default
  // action follows it and moves focus to the nearest focusable ancestor of what was clicked — the
  // frame's body, since an SVG rect is not focusable. Focusing first and letting the default run
  // simply hands the focus back, and the first keystrokes fall into the page instead of the field.
  // The frame itself is focused too: giving focus to an element inside an iframe does not make that
  // iframe the focused one, so the keyboard would still be somewhere else.
  if (st.focus) {
    st.focus = false;
    setTimeout(() => {
      try { doc.defaultView?.focus(); el.focus(); el.setSelectionRange(el.value.length, el.value.length); } catch { }
    }, 0);
  }
}

function commit() {
  save(); ribbon(); drawer();
}

/** A caption is chrome, not the value: it is cut to what the box can show rather than spilling over
 *  the page. The value itself is untouched — what the field HOLDS is in the member. */
function clip(s, w, size) {
  const max = Math.max(1, Math.floor((w - 6) / (size * 0.52)));
  return s.length <= max ? s : s.slice(0, Math.max(1, max - 1)) + '…';
}

const paint = () => book.eachFrame((doc, idx) => { styleOn(doc); draw(doc, idx); bind(doc); });

function bind(doc) {
  if (doc.__fmBound) return;
  doc.__fmBound = true;
  doc.addEventListener('pointerdown', (e) => {
    if (!st.on) return;
    if (e.target?.closest?.('[data-fm-edit]')) return;      // typing in the editor is not picking a field
    const hit = e.target?.closest?.('.fm-box');
    if (!hit) return;
    const i = +hit.getAttribute('data-fm');
    if (i === st.sel) return;                               // already open on it — let the caret land
    e.preventDefault();                                     // the default action would move the focus away
    select(i);
  }, true);
}

/* -- selection --------------------------------------------------------------------------------- */

function select(i, go = false) {
  st.sel = i >= 0 && i < st.list.length ? i : -1;
  const it = cur();
  if (go && it) P.goTo?.(it.page);
  st.focus = !!it;                 // the editor takes the caret as it is drawn (see editor())
  paint(); ribbon(); drawer();
}

function nextEmpty() {
  const n = st.list.length;
  for (let k = 1; k <= n; k++) {
    const i = (Math.max(st.sel, -1) + k) % n;
    if (!st.list[i].f.value) { select(i, true); return; }
  }
}

function setValue(v) {
  const it = cur(); if (!it) return;
  it.f.value = v ?? '';
  st.dirty = true;
  paint(); drawer();
  save();
}

/* -- the ribbon -------------------------------------------------------------------------------- */

function ribbon() {
  const it = cur(), none = !has();
  P.ribbon(TOOL, [
    { group: 'Field', items: [
      { icon: 'chevron-up',   label: 'Previous', title: 'Previous field', disabled: none,
        on: () => select(st.sel <= 0 ? st.list.length - 1 : st.sel - 1, true) },
      { icon: 'chevron-down', label: 'Next',     title: 'Next field',     disabled: none,
        on: () => select(st.sel >= st.list.length - 1 ? 0 : st.sel + 1, true) },
      // Filling a form is walking the fields that are still EMPTY, not all of them — on a form of any
      // size, "next" lands you on one you have already done.
      { icon: 'skip-forward', label: 'Next empty', title: 'Jump to the next field still empty',
        disabled: none || !st.list.some(x => !x.f.value), on: nextEmpty },
      { icon: 'tag', label: 'Names', title: 'Show the field names instead of their values',
        active: st.names, disabled: none, on: () => { st.names = !st.names; paint(); ribbon(); } },
    ] },
    { group: 'Value', items: [
      // `title` is the control's IDENTITY in the ribbon — focus is put back on it by title after every
      // re-declaration — so it must not be the field's name, which changes with the selection.
      { field: 'search', title: 'Value', label: it ? (it.f.name || '(unnamed)') : 'Value',
        value: it ? (it.f.value || '') : '', on: (v) => setValue(v), enter: (v) => setValue(v) },
      { icon: 'eraser', label: 'Clear', title: 'Clear this field', disabled: !it,
        on: () => setValue('') },
    ] },
    { group: 'Form', items: [
      { icon: 'list-x', label: 'Clear all', title: 'Clear every value in the document', disabled: none,
        on: () => { st.list.forEach(x => { x.f.value = ''; }); st.dirty = true; paint(); drawer(); save(); } },
    ] },
  ], none ? 'This document carries no form fields.'
          : 'Click a field on the page, then type its value — it is written to the document and exported as a real PDF form.');
}

/* -- the drawer -------------------------------------------------------------------------------- */

/* The pane is a WORKLIST, not a second copy of the page. Echoing each value back was pure repetition
 * — the box on the page already shows it, larger and in place. What the page cannot show is what is
 * LEFT: how many fields remain, which of them are required, and where they are when the form runs
 * past one screen. On a five-field page it stays quiet, which is the right amount to say. */
function drawer() {
  const info = document.getElementById('fm-info');
  const list = document.getElementById('fm-list');
  const det  = document.getElementById('fm-detail');
  if (!info || !list) return;
  const left = st.list.filter(x => !x.f.value).length;
  const req  = st.list.filter(x => x.f.required && !x.f.value).length;
  info.textContent = !book.isOpen?.() ? 'Open a document first.'
                   : !has() ? 'No form fields in this document.'
                   : left === 0 ? `${st.list.length} fields · all filled`
                   : `${st.list.length} fields · ${st.list.length - left} filled · ${left} left`
                     + (req ? ` · ${req} required` : '');
  // A page heading only when there is more than one page to tell apart: on a one-page form it would
  // be a header over the whole list, which says nothing.
  const multi = new Set(st.list.map(x => x.page)).size > 1;
  let at = -1;
  const rows = [];
  st.list.forEach((it, i) => {
    if (multi && it.page !== at) { at = it.page; rows.push(`<li class="fm-group">Page ${at + 1}</li>`); }
    const status = it.f.value ? 'filled' : it.f.required ? 'required' : 'empty';
    rows.push(`<li><a class="nav-link${i === st.sel ? ' active' : ''}" data-fm="${i}">`
            + `<b>${esc(it.f.name || '(unnamed)')}</b>`
            + `<span class="hit px-sub fm-${status}">${status}</span></a></li>`);
  });
  list.innerHTML = rows.join('');
  list.querySelectorAll('[data-fm]').forEach(a =>
    a.addEventListener('click', () => select(+a.getAttribute('data-fm'), true)));
  const it = cur();
  if (det) det.textContent = !it ? 'Click a field to select it.'
    : [it.f.type || 'text',
       it.f.multiline ? 'multiline' : null,
       it.f.required ? 'required' : null,
       it.f.readonly ? 'read-only' : null,
       it.f.maxlen ? `max ${it.f.maxlen}` : null,
       it.f.rotate ? `${it.f.rotate}°` : null].filter(Boolean).join(' · ');
}

const esc = (s) => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

/* -- the tool ---------------------------------------------------------------------------------- */

P.registerTool({
  id: TOOL, label: 'Forms', icon: 'text-cursor-input', drawer: 'Fields',
  title: 'Forms — fill the document\u2019s form fields',
  onEnter() {
    st.on = true;
    read();
    paint(); ribbon(); drawer();
  },
  onLeave() {
    st.on = false;
    save();
    book.eachFrame((doc) => {
      doc.querySelectorAll('.fm-layer, #fm-style').forEach(n => n.remove());
    });
  },
});

// A new book brings its own form; a closed one leaves none.
P.on('book',  () => { if (st.on) { read(); paint(); ribbon(); drawer(); } });
P.on('close', () => { st.list = []; st.sel = -1; if (st.on) { ribbon(); drawer(); } });
// A frame mounted after the tool was entered has never been dressed: the chassis replays `frame` for
// what is up, and a page scrolled into view later arrives here.
book.onFrame((idx) => {
  if (!st.on) return;
  const doc = book.frameDoc(idx); if (!doc) return;
  styleOn(doc); draw(doc, idx); bind(doc);
});
