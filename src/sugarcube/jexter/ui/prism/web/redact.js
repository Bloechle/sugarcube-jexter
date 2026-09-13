/* redact.js — "Redact": see what a redaction still hides, pick what must go, apply — and only keep a
 * result the engine proved clean.
 *
 * One list of ITEMS, all in the audit's JSON shape {page, rect, box, color, kind, text}, from four sources:
 *   Audit   — /api/convert?to=audit: what is still under the boxes (kind text_under_box · image_under_box ·
 *             pending_redact), red on the page, the hidden text in the tooltip and the list. The demo.
 *   Pick    — Zone: drag a rectangle · Block: click a paragraph (Shift-click: one run) · Page: the whole
 *             current page. Pure geometry, computed here, previewed at once. A zone STAYS: click it and
 *             it goes under the handles (see below).
 *   Find    — /api/convert?to=zones&match=<regex>: the engine returns each match's exact glyph span.
 *   Apply   — /api/convert?to=patch&redact=<items>: the engine reads only the touched pages, removes,
 *             proves the zones clean (HTTP 500 otherwise) and returns just the changed members — those
 *             pages, their images, structures/annots — which are put into the open container and the
 *             touched frames reloaded. Nothing else moves; the cost is what the edit touches.
 *   Reveal  — a VIEW, not an option: the boxes become outlines and everything hidden paints in one
 *             loud colour, so "did the text actually go?" is answered by looking. Writes nothing.
 *
 * Preview is instant and honest: an item WITHOUT a box (something to remove) is painted opaque in the
 * chosen colour — the page looks redacted now; an item WITH a box (an audit finding: already covered) is
 * shown red and translucent — what you see is what is still there. Items persist in the container as
 * OEBPS/ocd/redact.json, so they survive a reload and Export applies them even without Apply.
 *
 * A zone you can only draw is a zone you cannot correct: the second attempt is another drag from
 * scratch, and "a little more off the left" is not expressible. So a zone is an OBJECT — click it and
 * `gizmo.js` puts the eight handles on it, with the ANCHOR that makes a resize physical (the handle you
 * pull moves, the opposite one stays). Rotation is off: a redaction zone is an axis-aligned rectangle
 * in page space, which is exactly what the engine reads (`page:x,y,w,h`). The rect on the page is
 * CHROME — the model is the item list — so the selection is remembered as an INDEX and re-attached
 * after every redraw, never held as an element.
 *
 * Chrome is the #redact-drawer declared in index.html (one right drawer per mode); this file only binds
 * it. The page overlay is data-ui chrome that persist() strips. Coordinates: a page SVG is the page's
 * effective box (data-crop, else data-media) Y-flipped — svg (sx, sy) ↔ page (sx + bx, by + bh − sy);
 * items are kept in PAGE space, what the engine reads.
 */
import { unzipSync } from 'https://cdn.jsdelivr.net/npm/fflate@0.8.2/+esm';
import { SVG_NS } from '/shared/js/ocd.js';   // ONE spelling, ONE declaration: the grammar's
import { book } from '/shared/js/book.js';
import { createGizmo, boxOf, setBox, outerBox, clampTo, normBox, svgPointOf } from './gizmo.js';
import * as backend from './backend.js';

const P = window.prism;
const MODE = 'redact';
const MEMBER = 'OEBPS/ocd/redact.json';
const enc = (s) => new TextEncoder().encode(s), dec = (b) => new TextDecoder().decode(b);

const CSS = `
svg .rd-layer { pointer-events:none; }
svg .rd-find { fill:#e5484d; fill-opacity:.18; stroke:#e5484d; stroke-width:1.2; vector-effect:non-scaling-stroke; pointer-events:all; cursor:help; }
/* A zone is grabbable: it is what the gizmo picks, so it takes pointer events — the handles are the
   gizmo's own data-ui chrome and sit above it. */
svg .rd-zone { stroke:var(--jx-brand-bright, #5e9bd6); stroke-width:1.2; stroke-dasharray:4 3; vector-effect:non-scaling-stroke; pointer-events:all; cursor:move; }
svg .rd-band { fill:none; stroke:var(--jx-brand-bright, #5e9bd6); stroke-width:1; stroke-dasharray:3 3; vector-effect:non-scaling-stroke; }
svg.rd-zone-pick { cursor:crosshair; }
svg.rd-block-pick [data-ocd="run"], svg.rd-block-pick [data-ocd="paragraph"] { pointer-events:bounding-box; cursor:pointer; }
/* The candidate under the pointer. SOLID where a placed zone is dashed: one says "this is what a click
   takes", the other "this is what Apply removes" — two states of one shape, told apart without a legend. */
svg .rd-hov { pointer-events:none; }
svg .rd-hov-box { fill:var(--jx-brand-bright, #5e9bd6); fill-opacity:.10;
                  stroke:var(--jx-brand-bright, #5e9bd6); stroke-width:1.2; vector-effect:non-scaling-stroke; }
`;

/* -- state ------------------------------------------------------------------------------------ */
//
// Everything the ribbon shows lives HERE, not in its inputs: the ribbon is re-declared on every change
// of state and the chassis rebuilds the row, so a value read back from the DOM is a value that reverts
// to its default the next time anything happens. `sel` is an INDEX into items — the rects are redrawn
// constantly and an element reference would point at a node the page no longer holds.
const st = { items: [], pick: 'zone', on: false, sel: -1, fill: '#000000', meta: false, match: '', reveal: false };

/** Suggestions on the Find field. A regex box with nothing in it is a blank wall, and the patterns a
 *  redaction actually hunts are always the same handful. They are Java regexes — the engine's. */
const PATTERNS = [
  '\\d{3}\\.\\d{4}\\.\\d{4}\\.\\d{2}',            // AVS / AHV
  'CH\\d{2} ?(?:\\d{4} ?){4}\\d',                 // IBAN (CH)
  '[\\w.+-]+@[\\w-]+\\.[\\w.]+',                  // e-mail
  '\\+?\\d[\\d ()./-]{7,}\\d',                    // telephone
  '\\d{2}[./-]\\d{2}[./-]\\d{2,4}',               // date
];

const isFinding = (it) => !!it.box;                                    // an audit finding covers something already
const item = (page, rect, kind, text = '', box = '', color = '') => ({ page, rect: rect.map(v => +v.toFixed(2)), box, color, kind, text });

/* -- page geometry ------------------------------------------------------------------------------ */
function pageBox(svg) {
  const [x, y, w, h] = (svg.getAttribute('data-crop') || svg.getAttribute('data-media') || '').split(/\s+/).map(Number);
  return Number.isFinite(h) ? { x, y, w, h } : { x: 0, y: 0, w: 0, h: 0 };
}
const toSvg  = (b, r) => ({ x: r[0] - b.x, y: b.y + b.h - r[1] - r[3], w: r[2], h: r[3] });
const toPage = (b, s) => [s.x + b.x, b.y + b.h - s.y - s.h, s.w, s.h];
const ref = (svg) => svg.querySelector('g[data-ocd="rot"]') || svg;   // the CTM that maps screen → page-SVG space
// `ref` is the page root, or the rotation carrier a page written before the grammar moved rotation to
// data-rot still has: the mapping is gizmo.js's, the choice of reference is this format's.
const svgPoint = (svg, e) => svgPointOf(svg, e, ref(svg));
/** An element's box in page-SVG space (its bbox through its CTM relative to the page frame). */
function elBox(svg, el) {
  const bb = el.getBBox(), m = ref(svg).getScreenCTM().inverse().multiply(el.getScreenCTM());
  const pts = [[bb.x, bb.y], [bb.x + bb.width, bb.y], [bb.x, bb.y + bb.height], [bb.x + bb.width, bb.y + bb.height]]
    .map(([x, y]) => { const p = svg.createSVGPoint(); p.x = x; p.y = y; return p.matrixTransform(m); });
  const xs = pts.map(p => p.x), ys = pts.map(p => p.y);
  return { x: Math.min(...xs), y: Math.min(...ys), w: Math.max(...xs) - Math.min(...xs), h: Math.max(...ys) - Math.min(...ys) };
}

/* -- overlay: one data-ui layer per page, rebuilt from the items ------------------------------ */
function layer(doc) {
  const svg = doc.querySelector('svg[data-ocd="page"]'); if (!svg) return null;
  let g = svg.querySelector('.rd-layer');
  if (!g) { g = doc.createElementNS(SVG_NS, 'g'); g.setAttribute('class', 'rd-layer'); g.setAttribute('data-ui', ''); g.setAttribute('data-z', 'over'); ref(svg).appendChild(g); }
  return g;
}
function rect(doc, cls, s, title, fill) {
  const r = doc.createElementNS(SVG_NS, 'rect');
  r.setAttribute('class', cls);
  r.setAttribute('x', s.x); r.setAttribute('y', s.y); r.setAttribute('width', s.w); r.setAttribute('height', s.h);
  if (fill) r.setAttribute('fill', fill);
  if (title) { const t = doc.createElementNS(SVG_NS, 'title'); t.textContent = title; r.appendChild(t); }
  return r;
}
const label = (it) => it.kind === 'image_under_box' ? `image, ${Math.round(+it.text * 100)}% covered`
  : it.kind === 'pending_redact' ? `pending /Redact ${it.text}` : it.text || it.kind;
// The preview is honest: an item to be removed is painted OPAQUE in the chosen colour — the page looks
// redacted now. Seeing whether the text underneath is really gone is Reveal's job, not a translucent
// box's. Every zone carries its index, which is how a click on the page and a click in the list name
// the same item.
function draw(doc, idx) {
  const g = layer(doc); if (!g) return;
  const svg = g.closest('svg'), b = pageBox(svg);
  g.replaceChildren();
  st.items.forEach((it, i) => {
    if (it.page !== idx + 1) return;
    const r = isFinding(it)
      ? rect(doc, 'rd-find', toSvg(b, it.rect), label(it))
      : rect(doc, 'rd-zone', toSvg(b, it.rect), label(it), st.fill);
    r.setAttribute('data-i', String(i));
    g.appendChild(r);
  });
}
function styleOn(doc) {
  if (!doc.getElementById('rd-style')) { const s = doc.createElement('style'); s.id = 'rd-style'; s.setAttribute('data-ui', ''); s.textContent = CSS; doc.head.appendChild(s); }
  const svg = doc.querySelector('svg'); if (!svg) return;
  svg.classList.toggle('rd-zone-pick', st.pick === 'zone'); svg.classList.toggle('rd-block-pick', st.pick === 'block');
}
// `clean`, not `clear`: the gizmo has a clear() of its own and one name for two things is how a caller
// picks the wrong one. It takes the handles with it — chrome that outlives its tool answers the pointer
// under the next one.
const clean = (doc) => {
  doc.querySelectorAll(`.rd-layer, .rd-hov, #rd-style, #${RV_STYLE}, [data-ui="gz"], [data-ui="gzh"]`).forEach(n => n.remove());
  doc.querySelector('svg')?.classList.remove('rd-zone-pick', 'rd-block-pick');
};

/** The pages only — no list, no ribbon, no write. What a colour change costs. Reveal is re-applied
 *  here because a draw() rebuilds the overlay, markers included, and its rules follow the item list. */
const paint = () => book.eachFrame((doc, idx) => { styleOn(doc); draw(doc, idx); if (st.reveal) revealOn(doc, idx); });
/** Everything: the pages, the handles back on the selected zone, the drawer, the ribbon, the member. */
const refresh = () => { paint(); attach(); status(); persist(); };

/* -- the selected zone: handles that move and resize it ----------------------------------------
 *
 * `gizmo.js` owns the geometry — the eight handles, the anchor, and the three space traps (a transform
 * composed in FRONT acts in the PARENT's space; measure the bbox corners through the element's own
 * matrix, never getBoundingClientRect; append the frame to the SVG root or document order buries it).
 * This file owns only what a zone MEANS: which item it is, and where that item's rectangle ends up.
 *
 * The gizmo composes its transform in front of the rect, so the matrix is BAKED back into x/y/w/h at
 * every commit and the result written to the item in page space. Nothing accumulates: the next gesture
 * starts from a clean matrix, and the numbers in the drawer are the attributes.
 */
const gizmos = new Map();                      // page index → gizmo, one per bound page
let selecting = false;                         // our own select()/clear() are not the reader picking
const MIN = 2;                                 // pt: a zone over one digit is legitimate, an empty one is not

/** Fold the gizmo's composed matrix back into the rect's own numbers, clamped to the page — both
 *  `gizmo.js`'s, so Pages' crop and this zone cannot drift apart. */
function bake(r, svg) {
  const box = clampTo(outerBox(r), pageBox(svg), MIN);
  setBox(r, box);
  return box;
}

/** What the tool lets you grab: the smallest zone containing the point. An audit finding is a REPORT of
 *  what the document still hides, not something you placed — it is not selectable, and moving one would
 *  say nothing. Hit-tested by GEOMETRY, so a zone under a handle or an overlapping one still answers. */
function zoneAt(svg, p) {
  let best = null, area = Infinity;
  for (const r of svg.querySelectorAll('.rd-zone')) {
    const b = boxOf(r), a = b.w * b.h;
    if (p.x < b.x || p.y < b.y || p.x > b.x + b.w || p.y > b.y + b.h || a >= area) continue;
    area = a; best = r;
  }
  return best;
}

/** ONE zone is selected in the document, by index. */
function select(i) {
  const next = Number.isInteger(i) && st.items[i] && !isFinding(st.items[i]) ? i : -1;
  st.sel = next;
  attach();
  status();
}

/** Put the handles back on the selected zone wherever it is, and take them off every other page — the
 *  rects are rebuilt by every draw(), so this runs after each one rather than surviving it. */
function attach() {
  selecting = true;
  const it = st.items[st.sel];
  book.eachFrame((doc, idx) => {
    const g = gizmos.get(idx); if (!g) return;
    const r = it && it.page === idx + 1 ? doc.querySelector(`.rd-zone[data-i="${st.sel}"]`) : null;
    if (r) g.select(r); else g.clear();
  });
  selecting = false;
  fields();
}

/** The gesture ended: the rectangle becomes the item's rectangle, in page space. */
function commit(idx) {
  const doc = book.frameDoc(idx), svg = doc?.querySelector('svg[data-ocd="page"]');
  const it = st.items[st.sel], r = doc && st.sel >= 0 ? doc.querySelector(`.rd-zone[data-i="${st.sel}"]`) : null;
  if (!svg || !it || !r) return;
  it.rect = toPage(pageBox(svg), bake(r, svg)).map(v => +v.toFixed(2));
  refresh();
}

const removeSel = () => { if (st.sel < 0) return; st.items.splice(st.sel, 1); st.sel = -1; refresh(); };

/* -- the four numbers: the other half of "precisely" ------------------------------------------- */
//
// The gesture puts the box roughly where it belongs; a field says 42.5. Both write the same rectangle,
// so neither is a second authority — and they are in PAGE space, the units a redaction spec states.

function showFields(rect, page) {
  ['x', 'y', 'w', 'h'].forEach((k, n) => {
    const f = $id('rd-' + k); if (!f) return;
    f.disabled = !rect;
    if (document.activeElement !== f) f.value = rect ? String(Math.round(rect[n] * 10) / 10) : '';
  });
  const info = $id('rd-zone-info');
  if (info) info.textContent = rect
    ? `Zone ${Math.round(rect[2])} × ${Math.round(rect[3])} pt at ${Math.round(rect[0])}, ${Math.round(rect[1])}`
      + (page ? ` on page ${page}` : '') + ' — drag the handles, or type. Delete removes it.'
    : 'Click a zone to select it — the handles move and resize it.';
}

const fields = () => { const it = st.items[st.sel]; showFields(it && !isFinding(it) ? it.rect : null, it?.page); };

/** During a drag the rect carries the gizmo's matrix and the item is not written yet: the numbers must
 *  follow the gesture without baking it, because baking mid-gesture moves the anchor it composes against. */
function liveFields(idx, el) {
  const svg = book.frameDoc(idx)?.querySelector('svg[data-ocd="page"]');
  if (svg && el) showFields(toPage(pageBox(svg), outerBox(el)), st.items[st.sel]?.page);
}

function bindFields() {
  ['x', 'y', 'w', 'h'].forEach((k, n) => {
    const f = $id('rd-' + k); if (!f || f.__rdField) return;
    f.__rdField = true;
    f.addEventListener('change', () => {
      const it = st.items[st.sel]; if (!it) return;
      const v = parseFloat(f.value); if (!Number.isFinite(v)) return;
      const r = [...it.rect]; r[n] = +v.toFixed(2);
      if (r[2] < MIN || r[3] < MIN) return;
      it.rect = r;
      refresh();
    });
  });
}

/* -- Reveal: is the text actually gone? ---------------------------------------------------------
 *
 * A redaction is judged by what SURVIVES, and every way of hiding text on a page looks identical to a
 * reader: a box painted over it, white ink on white paper, a zero alpha, the invisible render mode of
 * an OCR layer, a run whose glyphs carry no outline at all. Reveal answers the only question that
 * matters — *is it still in the file?* — by taking the paint away: the boxes become outlines and
 * everything hidden paints in one loud colour.
 *
 * It is a VIEW, not an option: it changes what this screen shows and nothing that any export writes.
 * (An earlier attempt offered the mark's OPACITY instead. That is an export setting — `redactFill`
 * takes `#rrggbbaa` — and it answered the question by changing the DOCUMENT, which is exactly wrong:
 * you would ship a translucent mark to see through it.)
 *
 * It writes NOTHING, and does not even touch the content: it is one generated stylesheet whose rules
 * are keyed on the ids the page already states, plus one `data-ui` layer for the runs that have no
 * glyph to colour. An attribute set on a content node would ride into the member on the next persist —
 * a `data-ui` element is stripped, an attribute is not.
 */
const RV_STYLE = 'rd-rv-style';
const RV_INK = '#e5484d';

const RV_CSS = `
svg .rd-rv-mark { fill:none; stroke:${RV_INK}; stroke-width:1.4; stroke-dasharray:5 4; vector-effect:non-scaling-stroke; }
svg .rd-zone { fill:none !important; }
`;

const rgbOf = (css) => {
  const m = /^rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)/.exec(css || '');
  return m ? { r: +m[1], g: +m[2], b: +m[3], a: m[4] == null ? 1 : +m[4] } : null;
};

/** The opaque shapes big enough to act as a background, in PAINT ORDER — which is document order in a
 *  page. Collected once per page: they are few (a banner, a table cell, a fake redaction), while runs
 *  are thousands, so the walk must not be per run. */
function backdrops(svg) {
  const out = [];
  for (const p of svg.querySelectorAll('path')) {
    const cs = getComputedStyle(p);
    const c = rgbOf(cs.fill);
    if (!c || c.a < .9 || +cs.fillOpacity < .9) continue;
    let b; try { b = elBox(svg, p); } catch { continue; }
    if (b.w < 4 || b.h < 4) continue;                              // a rule or an underline is not a background
    out.push({ el: p, b, c });
  }
  return out;
}

const WHITE = { r: 255, g: 255, b: 255 };                           // the page, when nothing covers it

/** What is painted UNDER a run: the LAST background that precedes it in paint order and contains it —
 *  or the page itself, which is white. */
function backdropOf(svg, g, bg) {
  let out = WHITE;
  const b = elBox(svg, g);
  for (const d of bg) {
    if (!(d.el.compareDocumentPosition(g) & Node.DOCUMENT_POSITION_FOLLOWING)) continue;   // painted after: not a backdrop
    if (b.x < d.b.x - .5 || b.y < d.b.y - .5 || b.x + b.w > d.b.x + d.b.w + .5 || b.y + b.h > d.b.y + d.b.h + .5) continue;
    out = d.c;
  }
  return out;
}

/** Why this run is invisible — or null if it is not. Read-only: computed style, what the format STATES
 *  (`data-render`), the ids the audit named, and what is painted under it.
 *
 *  The colour test is INK vs BACKDROP, not "is the ink white". White ink is not hidden ink: a dark
 *  banner with white text is ordinary design, and flagging it teaches a reader to ignore the tool —
 *  measured, "CONFIDENTIAL" on a black banner was reported as "white on white". The same rule catches
 *  what the white test could not even ask about: black ink on a black box. */
function whyHidden(g, covered, bg, svg) {
  if (covered.has(g.id)) return 'under a box';
  const rm = +(g.getAttribute('data-render') || 0);
  if (rm === 3 || rm === 7) return 'invisible (render mode)';      // INVISIBLE / CLIP — an OCR layer is this
  const cs = getComputedStyle(g);
  if (cs.fill === 'none' && cs.stroke === 'none') return 'unpainted';
  if (+cs.fillOpacity === 0 && cs.stroke === 'none') return 'transparent';
  const ink = rgbOf(cs.fill);
  if (ink) {
    if (ink.a === 0) return 'transparent';
    if (cs.stroke === 'none') {
      // No opaque shape on the page ⇒ the backdrop IS the page, and it is white: skip the per-run box
      // and its CTM entirely. Most pages are that page, and a run's box is the only cost here.
      const back = bg.length ? backdropOf(svg, g, bg) : WHITE;
      const d = Math.max(Math.abs(ink.r - back.r), Math.abs(ink.g - back.g), Math.abs(ink.b - back.b));
      if (d <= 12) return 'ink matches its backdrop';              // white on white, black on black, grey on grey
    }
  }
  // Nothing to paint AT ALL: every glyph of the run is inkless, so the page holds its characters
  // (data-text) and draws no <use>. There is no fill to change — it gets a marker instead.
  if (!g.querySelector('use') && /\S/.test(g.getAttribute('data-text') || '')) return 'no glyph';
  return null;
}

/** The box ids the audit named on this page, and the nodes they cover. */
function boxesOf(idx) {
  const boxes = new Set(), covered = new Set();
  for (const it of st.items) {
    if (it.page !== idx + 1) continue;
    if (it.box) boxes.add(it.box);
    if (it.node) covered.add(it.node);
  }
  return { boxes, covered };
}

/** A marker for a run that cannot be coloured, drawn in the overlay with the run's own matrix — never
 *  inside the content, which would change the very bbox other tools hit-test against. The span comes
 *  from what the page states: `data-blanks` records every inkless glyph's x, in em along the run. */
function markRun(doc, g, layerG) {
  const b = (g.getAttribute('data-b') || '').trim();
  if (!b) return;
  const xs = b.split(/\s+/).map(e => +e.split(':').pop()).filter(Number.isFinite);
  if (!xs.length) return;
  const x0 = Math.min(...xs), x1 = Math.max(...xs);
  const r = doc.createElementNS(SVG_NS, 'rect');
  r.setAttribute('class', 'rd-rv-mark');
  r.setAttribute('x', x0); r.setAttribute('y', 0);
  r.setAttribute('width', Math.max(x1 - x0, 0.5)); r.setAttribute('height', 0.72);   // baseline to x-height, in em
  const svg = layerG.closest('svg');
  const m = svg.getScreenCTM().inverse().multiply(g.getScreenCTM());
  r.setAttribute('transform', `matrix(${m.a} ${m.b} ${m.c} ${m.d} ${m.e} ${m.f})`);
  layerG.appendChild(r);
}

/** Build the reveal stylesheet for one page and return what it found, by reason. */
function revealOn(doc, idx) {
  const svg = doc.querySelector('svg[data-ocd="page"]'); if (!svg) return {};
  const { boxes, covered } = boxesOf(idx);
  const bg = backdrops(svg);
  const runs = [], marks = [], found = {};
  for (const g of svg.querySelectorAll('g[data-ocd="run"]')) {
    const why = whyHidden(g, covered, bg, svg); if (!why) continue;
    // Counted from the FINDINGS below, not here: a covered run is the engine's answer, and counting it
    // on both sides would report every text-under-box twice.
    if (why !== 'under a box') found[why] = (found[why] || 0) + 1;
    (why === 'no glyph' ? marks : runs).push(g);
  }
  // What a box covers is NOT always a text run. An IMAGE under a box — every "redacted" scan — has no
  // fill to change and no run to walk, so counting only runs made the tool announce "nothing hidden"
  // while it was outlining the very box that hid a passport number. The findings are the engine's
  // answer: count them, and name what kind of thing leaked.
  for (const it of st.items) {
    if (it.page !== idx + 1 || !isFinding(it)) continue;
    const k = it.kind === 'image_under_box' ? 'image under a box'
            : it.kind === 'pending_redact'  ? 'unapplied /Redact mark' : 'text under a box';
    found[k] = (found[k] || 0) + 1;
  }
  const esc = (id) => '#' + (window.CSS?.escape ? window.CSS.escape(id) : id);
  const rules = [RV_CSS];
  if (runs.length) rules.push(`${runs.map(g => esc(g.id)).join(', ')} { fill:${RV_INK}; fill-opacity:1; stroke:none; }`);
  // The covering box stops covering: outline only, so what it hid is readable THROUGH it — which is
  // also what makes the image case work without a rule of its own.
  if (boxes.size) rules.push(`${[...boxes].map(esc).join(', ')} { fill:none; stroke:${RV_INK}; stroke-width:1.4;
      stroke-dasharray:5 4; vector-effect:non-scaling-stroke; }`);
  let s = doc.getElementById(RV_STYLE);
  if (!s) { s = doc.createElement('style'); s.id = RV_STYLE; s.setAttribute('data-ui', ''); doc.head.appendChild(s); }
  s.textContent = rules.join('\n');
  const g = layer(doc);
  if (g) { g.querySelectorAll('.rd-rv-mark').forEach(n => n.remove()); for (const r of marks) markRun(doc, r, g); }
  return found;
}

const revealOff = (doc) => {
  doc.getElementById(RV_STYLE)?.remove();
  doc.querySelectorAll('.rd-rv-mark').forEach(n => n.remove());
};

function toggleReveal() {
  st.reveal = !st.reveal;
  const total = {};
  book.eachFrame((doc, idx) => {
    if (!st.reveal) { revealOff(doc); return; }
    const f = revealOn(doc, idx);
    for (const [k, v] of Object.entries(f)) total[k] = (total[k] || 0) + v;
  });
  ribbon();
  if (!st.reveal) return;
  const n = Object.values(total).reduce((a, b) => a + b, 0);
  // What a box hides is the ENGINE's to judge — it is a question about paint order over the model, and
  // RedactionAudit owns it. Re-deriving it here would be a second authority for one answer, so Reveal
  // uses the findings when there are some and says plainly what it cannot see when there are none.
  const audited = st.items.some(isFinding);
  const hint = audited ? '' : ' · run Audit to add what a box hides';
  // "item", not "run": an image under a box is a leak and it is not a run — the word has to cover both,
  // or the count reads as a contradiction of the very finding it is reporting.
  P.toast(n
    ? `${n} thing(s) still hidden on the visible page(s) — ${Object.entries(total).map(([k, v]) => `${v} ${k}`).join(' · ')}${hint}`
    : `Nothing hidden on the visible page(s): nothing painted over, nothing painted invisible${hint}.`, n ? 'warning' : 'success');
}

/* -- Block mode: what a click would take, shown before the click ------------------------------- */
//
// Clicking a paragraph was clicking blind: the pointer is over glyphs, the thing taken is the block the
// ANALYSIS produced, and nothing on screen said which. The candidate is outlined under the pointer —
// solid, where a placed zone is dashed — and it obeys the modifier, because the highlight promising one
// thing while Shift takes another is worse than no highlight at all.
//
// Drawn in its OWN layer at the svg root, never inside the hovered element: inserting into the content
// repaints the run on every pointer move and changes the very bounding box the hit test uses, which is
// what makes a highlight blink. `elBox` maps the element's bbox through its CTM, so a rotated or scaled
// node is boxed on the page's axes exactly like the zone a click will create.

let hover = null;                                  // { doc, el, x, y } — one pointer, one candidate

function hoverLayer(doc) {
  const svg = doc.querySelector('svg[data-ocd="page"]'); if (!svg) return null;
  let g = svg.querySelector('.rd-hov');
  if (!g) {
    g = doc.createElementNS(SVG_NS, 'g');
    g.setAttribute('class', 'rd-hov'); g.setAttribute('data-ui', ''); g.setAttribute('data-z', 'over');
    svg.appendChild(g);
  }
  return g;
}

/** What a click takes: the block, or the run with Shift — the same rule the click itself applies. */
const candidate = (el, shift) => {
  const run = el?.closest?.('[data-ocd="run"]'), block = el?.closest?.('[data-ocd="paragraph"]');
  return shift ? (run || block) : (block || run);
};

function showHover(doc, el) {
  const svg = doc.querySelector('svg[data-ocd="page"]'); if (!svg) return;
  const g = hoverLayer(doc); if (!g) return;
  g.replaceChildren();
  if (!el) return;
  const b = elBox(svg, el);
  const r = rect(doc, 'rd-hov-box', b);
  // What it is and how much of it — a block and one of its runs look alike until you read the count.
  const text = (el.getAttribute('data-text') || [...el.querySelectorAll('[data-text]')].map(n => n.getAttribute('data-text')).join(' ')).trim();
  const t = doc.createElementNS(SVG_NS, 'title');
  t.textContent = `${el.getAttribute('data-ocd') === 'paragraph' ? 'block' : 'run'} · ${text.length} char(s)`;
  r.appendChild(t);
  g.appendChild(r);
}

function trackHover(doc, e) {
  if (!st.on || st.pick !== 'block' || e.target.closest?.('[data-ui]')) { dropHover(doc); return; }
  const el = candidate(e.target, e.shiftKey);
  hover = el ? { doc, el, x: e.clientX, y: e.clientY } : null;
  showHover(doc, el);
}

/** Shift changes what a click takes, so it must change what the highlight promises — without waiting for
 *  the pointer to move a pixel. The last position is enough to ask the document again.
 *
 *  Bound on BOTH sides, and that is not belt and braces: a frame is its own document, so a key pressed
 *  while the focus is in the chassis never reaches it — the pointer can be over a page with the focus
 *  anywhere, which is the normal case for a modifier. Bound only in the frame, Shift silently did
 *  nothing until you clicked into the page first. */
function reHover(doc, shift) {
  if (!doc || !hover || hover.doc !== doc) return;
  const el = candidate(doc.elementFromPoint(hover.x, hover.y), shift);
  if (el && el !== hover.el) { hover.el = el; showHover(doc, el); }
}

const dropHover = (doc) => { doc.querySelector('.rd-hov')?.replaceChildren(); if (hover?.doc === doc) hover = null; };

/* -- picking on the page: a drag (zone) or a click (block / run) ------------------------------ */
function bind(doc, idx) {
  const svg = doc.querySelector('svg[data-ocd="page"]'); if (!svg || svg.__rd) return;
  svg.__rd = true;

  // The gizmo is created whether the tool is up or not (the chassis replays `frame` on the way OUT
  // too), and starts inert: a gizmo OUTLIVES the tool that made it, so `enabled` is the flag onEnter /
  // onLeave flip rather than a teardown. A gesture carries ITS OWN page index — in scroll mode the page
  // under the pointer and the page the observer calls current are routinely different.
  gizmos.set(idx, createGizmo(svg, {
    enabled: st.on, move: true, scale: true, rotate: false, uniform: false, hover: false,
    pick: (cx, cy) => zoneAt(svg, svgPoint(svg, { clientX: cx, clientY: cy })),
    onSelect: (el) => { if (!selecting) select(el ? +el.getAttribute('data-i') : -1); },
    onChange: (el) => liveFields(idx, el),
    onCommit: () => commit(idx),
  }));

  let from = null, band = null;
  const drop = () => { from = null; band?.remove(); band = null; };
  // `[data-ui]` covers BOTH the gizmo's handles and the overlay's own rects (the layer carries it), so
  // one guard says "this pointerdown is a selection or a resize, not a new zone". Without it a drag
  // starting on an existing zone would move it AND draw a band across it.
  const onChrome = (e) => !!e.target.closest?.('[data-ui]');
  svg.addEventListener('pointerdown', e => {
    if (!st.on || e.button !== 0 || st.pick !== 'zone' || onChrome(e)) return;
    from = svgPoint(svg, e); band = rect(doc, 'rd-band', { x: from.x, y: from.y, w: 0, h: 0 }); layer(doc)?.appendChild(band);
    svg.setPointerCapture(e.pointerId); e.preventDefault();
  });
  svg.addEventListener('pointermove', e => {
    if (!from) { trackHover(doc, e); return; }
    const s = normBox(from, svgPoint(svg, e));
    band.setAttribute('x', s.x); band.setAttribute('y', s.y); band.setAttribute('width', s.w); band.setAttribute('height', s.h);
  });
  svg.addEventListener('pointerleave', () => dropHover(doc));
  // A frame is its own document: a key pressed with the focus inside a page never reaches the chassis.
  doc.addEventListener('keyup', e => { if (e.key === 'Shift') reHover(doc, false); });
  doc.addEventListener('keydown', e => {
    if (!st.on) return;
    if (e.key === 'Shift') { reHover(doc, true); return; }
    if (e.key === 'Escape') { if (from) drop(); else if (st.sel >= 0) select(-1); return; }
    if ((e.key === 'Delete' || e.key === 'Backspace') && st.sel >= 0) { e.preventDefault(); removeSel(); }
  });
  svg.addEventListener('pointerup', e => {
    if (!from) return;
    const s = normBox(from, svgPoint(svg, e)); drop();
    if (s.w < 3 || s.h < 3) return;                                   // a click is not a zone
    add(item(idx + 1, toPage(pageBox(svg), s), 'zone'));
  });
  svg.addEventListener('click', e => {
    if (!st.on || st.pick !== 'block' || onChrome(e)) return;         // a click ON a zone selected it
    const run = e.target.closest('[data-ocd="run"]'), block = e.target.closest('[data-ocd="paragraph"]');
    const el = e.shiftKey ? (run || block) : (block || run); if (!el) return;
    const text = (el.getAttribute('data-text') || [...el.querySelectorAll('[data-text]')].map(r => r.getAttribute('data-text')).join(' ')).trim();
    dropHover(doc);                                                   // the candidate becomes the zone
    add(item(idx + 1, toPage(pageBox(svg), elBox(svg, el)), el === block ? 'block' : 'run', text.slice(0, 80)));
  });
}
/** Drawn, then adjustable at once: the new zone is handed to the handles, the way the crop is. */
function add(it) { st.items.push(it); st.sel = isFinding(it) ? -1 : st.items.length - 1; refresh(); }
function wholePage() {
  const idx = P.state.current ?? 0, doc = book.frameDoc(idx), svg = doc?.querySelector('svg[data-ocd="page"]');
  if (!svg) { P.toast('Open a page first.', 'warning'); return; }
  const b = pageBox(svg);
  add(item(idx + 1, [b.x, b.y, b.w, b.h], 'page', `page ${idx + 1}`));
}

/* -- the engine: audit, find, apply — all on the one route -------------------------------------- */
const bytes = () => { const b = P.bookBytes?.(); if (!b) P.toast('Open a document first.', 'warning'); return b; };
const reason = (e) => { try { return JSON.parse(e.message).error || e.message; } catch { return e.message || String(e); } };

/** The engine's refusals, said in the tool's own terms.
 *
 *  "still recoverable" is not the request failing: the audit is reporting that something INSIDE the zone
 *  is hidden by a box the zone left behind. The audit is GEOMETRIC — an opaque box above a node is a
 *  leak whatever the pixels under it now hold — so what matters is whether the box SURVIVES the zone.
 *  Measured, both ways: a zone covering the whole box removes it and the result is clean; a zone
 *  covering half of it leaves a fragment, and the fragment still hides. Two ways out, and the message
 *  names both rather than showing a Java sentence and leaving the reader to guess that the next move is
 *  a different command. The page and the kind come from the engine's own answer. */
function refusal(msg) {
  const m = /still recoverable:\s*\[(.*)\]/s.exec(msg);
  if (!m) return [`Refused: ${msg}`, 'danger', 6000];
  const kinds = [...m[1].matchAll(/kind=(\w+)/g)].map(x => x[1].toLowerCase());
  const pages = [...new Set([...m[1].matchAll(/page=(\d+)/g)].map(x => x[1]))];
  const what = kinds.every(k => k === 'image_under_box') ? 'an image' : 'text';
  return [`Nothing was written: on page ${pages.join(', ')}, ${what} is still hidden by a box your zone only `
        + `PARTLY covers — the piece left over goes on hiding it. Cover the whole box, or run Audit, which turns `
        + `that box into a repair zone of its own.`, 'warning', 9000];
}
const report = async (art) => JSON.parse(dec(art.bytes)).findings || [];
// Every one of these reshuffles the list, and `sel` is an index into it: an index kept across a
// reshuffle points at whatever moved into that slot, which is how a reader deletes a zone they never
// selected. The selection is dropped, deliberately, wherever the indices move.
async function audit() {
  const b = bytes(); if (!b) return;
  try {
    const found = await report(await backend.convert(b, 'audit'));
    st.items = st.items.filter(it => !isFinding(it)).concat(found); st.sel = -1; refresh();
    P.toast(found.length ? `${found.length} recoverable item(s) under the boxes — red on the pages.` : 'Clean: nothing recoverable under any box.', found.length ? 'warning' : 'success');
  } catch (e) { P.toast(`Audit failed: ${reason(e)}`, 'danger'); }
}
async function find() {
  const re = st.match.trim();
  if (!re) { P.toast('Type a pattern to find — the field is beside the command.', 'warning'); return; }
  const b = bytes(); if (!b) return;
  try {
    const found = await report(await backend.convert(b, 'zones', { match: re }));
    st.items = st.items.concat(found); st.sel = -1; refresh();
    P.toast(found.length ? `${found.length} match(es) marked.` : 'No match.', found.length ? 'primary' : 'warning');
  } catch (e) { P.toast(`Find failed: ${reason(e)}`, 'danger'); }
}
async function apply() {
  if (!st.items.length) { P.toast('Nothing to redact: pick, find, or audit first.', 'warning'); return; }
  const b = bytes(); if (!b) return;
  const n = st.items.length, pages = [...new Set(st.items.map(it => it.page))];
  try {
    const opts = { redact: JSON.stringify(st.items) };
    if (st.fill !== '#000000') opts.redactFill = st.fill;                 // black is the engine's default; audit zones keep their own colour unless one is chosen
    if (st.meta) opts.redactMeta = 'true';
    const art = await backend.convert(b, 'patch', opts);
    for (const [path, data] of Object.entries(unzipSync(art.bytes))) {
      await book.put(path, data);
      if (path.includes('/images/')) await book.declare(path, path.endsWith('.png') ? 'image/png' : 'image/jpeg');
    }
    st.items = []; st.sel = -1; refresh();
    pages.forEach(p => book.refreshPage(p - 1));
    P.toast(`Redacted ${n} item(s) on ${pages.length} page(s) — zones proven clean. Export to keep it.`, 'success');
  } catch (e) { P.toast(...refusal(reason(e))); }
}
const reset = () => { st.items = []; st.sel = -1; refresh(); };
const undo  = () => { if (st.items.length) { st.items.pop(); st.sel = -1; refresh(); } };

/* -- persistence: the items ride in the container, so Export applies them and a reload keeps them */
let loading = false;
function persist() {
  if (loading || !P.state.files) return;
  const json = st.items.length ? enc(JSON.stringify(st.items)) : null;
  if (json) { book.put(MEMBER, json); book.declare(MEMBER, 'application/json'); }
  else if (P.state.files[MEMBER]) book.put(MEMBER, enc('[]'));
}
function load() {
  loading = true;
  try { st.items = P.state.files?.[MEMBER] ? JSON.parse(dec(P.state.files[MEMBER])) : []; } catch { st.items = []; }
  loading = false;
}

/* -- the drawer ------------------------------------------------------------------------------- */
const $id = (id) => document.getElementById(id);
let bound = false;
function bindDrawer() {
  if (bound) return; bound = true;
  bindFields();
  // The list and the page mark the SAME zone: clicking an entry goes to its page AND selects it, so
  // there is one notion of "the zone in question", shown twice — the lesson the light table and the
  // rail already learned from marking two different pages.
  $id('rd-list')?.addEventListener('click', e => {
    const del = e.target.closest('.rd-del'), li = e.target.closest('[data-i]'); if (!li) return;
    const i = +li.dataset.i;
    if (del) { st.items.splice(i, 1); st.sel = -1; refresh(); return; }
    P.goTo(st.items[i].page - 1);
    select(i);
  });
}
function status() {
  if (st.on) ribbon();                       // Clear follows the list
  const info = $id('rd-info'), list = $id('rd-list'); if (!info || !list) return;
  const n = st.items.length;
  info.textContent = n ? `${n} item(s) — Apply removes them; the engine proves the result clean.` : 'Pick zones, find text, or audit — Apply proves and rewrites.';
  list.innerHTML = st.items.map((it, i) =>
    `<li><a class="nav-link${i === st.sel ? ' sel' : ''}" data-i="${i}"><span class="rd-kind${isFinding(it) ? '' : ' rd-zone-kind'}">p${it.page}</span><span class="rd-text">${P.esc(label(it))}</span><span class="rd-del" title="Remove">×</span></a></li>`).join('');
}

/* -- the ribbon: what this tool DOES; the drawer keeps what it SHOWS ---------------------------- */
//
// Declared, not marked up: the chassis owns how a ribbon looks, so every tool's reads the same. Called
// again on every change of state — `active` and `disabled` are read at each render — which is why the
// pick buttons need no class juggling of their own any more.
// The settings used to be markup appended after the declaration, which put every one of them at the far
// right whatever it configured — and rebuilt them from their DEFAULTS on every re-render: the colour
// went back to black and the pattern emptied itself the moment anything else moved. They are declared
// now, each in the group whose commands read it, with their value coming from `st`.
function ribbon() {
  const sel = st.sel >= 0 && !!st.items[st.sel];
  // ONE rule across every tool: with no document open, nothing in a ribbon is armed — a MODE for a
  // gesture you cannot make is as dead as the command it feeds. Pages already disabled its two views;
  // this row kept its picks live, which is three tools stating the same thing three ways.
  const has = book.isOpen();
  P.ribbon(MODE, [
    { group: 'Pick', items: [
      { icon: 'square-dashed', label: 'Zone',  title: 'Drag a rectangle on the page — click one to select it', active: st.pick === 'zone',  disabled: !has, on: () => pick('zone') },
      { icon: 'type',          label: 'Block', title: 'Click a paragraph — Shift-click a single run', active: st.pick === 'block', disabled: !has, on: () => pick('block') },
      { icon: 'file',          label: 'Page',  title: 'The whole current page', disabled: !has, on: wholePage },
    ]},
    { group: 'Find', items: [
      { field: 'search', value: st.match, placeholder: 'regex, e.g. \\d{4}-\\d{4}', list: PATTERNS, live: true,
        title: 'Pattern to find — a Java regular expression, matched against the page text',
        on: (v) => { st.match = v; }, enter: (v) => { st.match = v; find(); } },
      { icon: 'search', label: 'Find', title: 'Mark every match — the engine answers with each hit\'s exact glyph span', disabled: !has, on: find },
    ]},
    { group: 'Zone', items: [
      { icon: 'trash-2', label: 'Delete', title: 'Remove the selected zone (Delete)', disabled: !sel, on: removeSel },
      { icon: 'undo-2',  label: 'Undo',   title: 'Remove the last item added (Ctrl+Z)', disabled: !st.items.length, on: undo },
      { icon: 'x',       label: 'Clear',  title: 'Clear every zone and every finding', disabled: !st.items.length, on: reset },
    ]},

    // Two ways of asking the same question, and they answer it differently: the engine PROVES (it walks
    // the model in paint order), the eye SEES. Neither replaces the other — an audit finds what a box
    // covers, Reveal also finds white ink, a zero alpha and an OCR layer, which no box is involved in.
    { group: 'Check', items: [
      { icon: 'scan-search', label: 'Audit', title: 'What does this document still hide? — the engine walks the model and proves it', disabled: !has, on: audit },
      { icon: st.reveal ? 'eye-off' : 'eye', label: 'Reveal', active: st.reveal, disabled: !book.isOpen(),
        title: 'Take the paint away: boxes become outlines and every hidden run — under a box, white, transparent, an OCR layer — shows itself. Changes this view only, never the document',
        on: toggleReveal },
    ]},
    // Everything the removal is: what the mark looks like, what else goes with it, and the verb. The
    // colour used to sit under a "Mark" group with the metadata switch — but a metadata purge is not a
    // mark, and a group whose name fits only half its contents is a group looking for a reason.
    { group: 'Apply', items: [
      { field: 'color', label: 'Box', value: st.fill, live: true,
        title: 'Colour of the box painted over a zone — an audited box keeps its own',
        on: (v) => { st.fill = v; paint(); } },
      // A TOGGLE COMMAND, not a switch. The ribbon already says "on" with `active` — the pick modes, the
      // page scope, Reveal — so a switch was a second way of stating one boolean, and the heaviest thing
      // in a row of line icons. One grammar: a command that stays lit.
      { icon: 'tags', label: 'Metadata', active: st.meta, disabled: !has,
        title: 'Also strip title, subject, authors, keywords and the XMP packet — they quote the very text the zones remove',
        on: () => { st.meta = !st.meta; ribbon(); } },
      { icon: 'eraser', label: 'Apply', title: 'Remove the zones and prove the result clean', disabled: !st.items.length, on: apply },
    ]},
  ], 'Drag on the page to add a zone · click one to select it, then drag its handles · Delete removes it · Reveal shows what is still hidden');
}

function pick(kind) {
  st.pick = kind;
  book.eachFrame((doc) => { styleOn(doc); if (kind !== 'block') dropHover(doc); });
  ribbon();                      // the state moved: say it again
}

/* -- tool -------------------------------------------------------------------------------------- */
P.registerTool({
  id: MODE, label: 'Redact', icon: 'eraser', drawer: 'Redact', title: 'Redact — see what a redaction hides, remove it, prove it',
  onEnter() {
    st.on = true; bindDrawer(); ribbon();
    book.eachFrame((doc, idx) => { styleOn(doc); bind(doc, idx); draw(doc, idx); });
    gizmos.forEach(g => g.set({ enabled: true }));
    attach(); status();
  },
  onLeave() {
    st.on = false;
    st.sel = -1;
    gizmos.forEach(g => g.set({ enabled: false }));   // inert, not destroyed: re-entering costs nothing
    book.eachFrame(clean);
  },
});
P.on?.('book',  () => { load(); st.sel = -1; if (st.on) refresh(); else status(); });
// Bound either way — the chassis replays `frame` on the way OUT of a tool too — but a page is only
// DRESSED while the tool is up, or this overlay comes back the instant onLeave removed it.
P.on?.('frame', (f, idx) => {
  try {
    const d = f.contentDocument;
    bind(d, idx);
    if (P.tool?.() !== MODE) { clean(d); return; }
    styleOn(d); draw(d, idx); attach();
    // A frame that RELOADS (Apply patches its member and refreshes it) has never seen the reveal
    // stylesheet: without this the button still reads "on" while the page has gone back to hiding
    // things — measured, and the worst kind of defect this tool could have.
    if (st.reveal) revealOn(d, idx);
  } catch { }
});
P.on?.('close', () => { st.items = []; st.sel = -1; gizmos.forEach(g => g.destroy()); gizmos.clear(); status(); });
window.addEventListener('keyup', e => { if (P.tool?.() === MODE && e.key === 'Shift') reHover(hover?.doc, false); });
window.addEventListener('keydown', e => {
  if (P.tool?.() !== MODE || e.target.matches?.('input, textarea')) return;
  if (e.key === 'Shift') { reHover(hover?.doc, true); return; }
  if (e.key === 'z' && (e.ctrlKey || e.metaKey)) { undo(); e.preventDefault(); }
  else if (e.key === 'Escape' && st.sel >= 0) select(-1);
  else if ((e.key === 'Delete' || e.key === 'Backspace') && st.sel >= 0) { e.preventDefault(); removeSel(); }
});
