/* trace.js — "Trace": point at anything on the page and it answers for itself.
 *
 * Named for what it DOES, and renamed when the name stopped matching (2026-09-07). It was "Reveal",
 * and it never revealed anything hidden: it identifies what is already in front of you, by tracing its
 * real geometry. Redact now ships a Reveal that does strip the paint off hidden content, so one word
 * over two different things would have been the collision this codebase keeps meeting. The tab folded
 * into Analysis as the "Trace" overlay some time ago; only the file, the seam (`P.trace`) and the class
 * prefix were still saying the old word — a name that survives its meaning is a trap for whoever reads
 * it next.
 *
 * The demo thesis, made interactive. A PDF viewer can highlight a rectangle; it has nothing to
 * say about what is inside it. An OCD page knows: this run is Calibri-9 at 9 pt, it holds these
 * 12 characters, it is tagged page-header, its baseline runs at this angle, and here are its
 * glyph outlines — the actual curves, not a box around them.
 *
 * So the highlight is NOT a bounding box (every tool draws those). It is the real geometry,
 * stroke-drawn: the glyph outlines of a text run, the actual `d` of a vector path. That is the
 * part nobody else can do, and it is the part that should move.
 *
 * Read-only and self-cleaning: the stylesheet and the highlight layer are injected INTO THE PAGE and
 * removed when the overlay goes off; the caption is a CHASSIS element, so it is kept and faded to
 * `opacity:0` instead of being rebuilt on every pointer move — inert either way (`pointer-events:none`),
 * and it never goes near a page. The model and the container are never touched.
 *
 * Its class prefix is half a contract: the caption's rules live in `prism.css` (chassis-level, because
 * it floats over the page frame). Rename one side alone and the element is not "unstyled and hidden" —
 * it is a 1500 x 438 default block over the app with `pointer-events:auto`. Measured, the day of the
 * rename.
 */
import { book } from '/shared/js/book.js';        // the ONE frame iterator
import { SVG_NS } from '/shared/js/ocd.js';   // ONE spelling, ONE declaration: the grammar's

const P = window.prism;
const eachFrame = book.eachFrame;

const CSS = `
@keyframes trc-trace { from { stroke-dashoffset:var(--trc-len, 300) } to { stroke-dashoffset:0 } }
@keyframes trc-in    { from { opacity:0 } to { opacity:1 } }

svg .trc-layer { pointer-events:none; }
/* A traced outline is right for a vector path and WRONG for text: at 9 pt the strokes of 70
   glyphs merge into a solid blue mass that hides the very words it is pointing at. Text gets its
   BOUNDS — the box the model actually reasons about — and the ink never goes opaque over content. */
svg .trc-ink {
  fill:none; stroke:var(--jx-brand-bright, #5e9bd6); stroke-width:1.2; opacity:.85;
  stroke-linejoin:round; stroke-linecap:round; vector-effect:non-scaling-stroke;
  stroke-dasharray:var(--trc-len, 300); animation:trc-trace .55s ease-out both, trc-in .12s both;
}
svg .trc-bounds {
  fill:var(--jx-brand-bright, #5e9bd6); fill-opacity:.13;
  stroke:var(--jx-brand-bright, #5e9bd6); stroke-opacity:.92; stroke-width:1.1;
  vector-effect:non-scaling-stroke; animation:trc-in .14s both;
}
/* fill-opacity, NOT opacity: trc-in animates the opacity property to 1 and 'both' holds it there,
   so an opacity:.10 base is simply overwritten and the image washed out solid blue. Keep the
   animated property and the styled property distinct. (No backticks in here: this block lives
   inside a template literal, and one backtick ends the stylesheet mid-rule — a SyntaxError the
   browser reports as "Unexpected identifier", which node --check does not surface.) */
svg .trc-wash {
  fill:var(--jx-brand-bright, #5e9bd6); fill-opacity:.13;
  stroke:var(--jx-brand-bright, #5e9bd6); stroke-opacity:.92; stroke-width:1.1;
  vector-effect:non-scaling-stroke; animation:trc-in .18s both;
}
svg [data-ocd="run"], svg path[id^="v"], svg image { cursor:crosshair; }
/* Glyph OUTLINES are the only painted geometry, so SVG hit-testing loses the pointer in every gap:
   between letters inside a run, and between runs inside a block (leading, run boundaries). Either
   miss tears the highlight down and rebuilds it — it flickers on every move, unusable in front of
   an audience. Both the run AND the block get a box, so the whole paragraph is one continuous
   target, which is what a reader means by "that paragraph". */
svg [data-ocd="run"], svg [data-ocd="paragraph"] { pointer-events:bounding-box; }
`;

/* -- the caption, in the CHASSIS document (not the frame): it must float over the page --- */

let cap = null;
function caption() {
  if (cap && cap.isConnected) return cap;
  cap = document.createElement('div');
  cap.className = 'trc-cap';
  cap.innerHTML = '';
  document.body.appendChild(cap);
  return cap;
}
function showCaption(html, x, y) {
  const c = caption();
  c.innerHTML = html;
  c.classList.add('on');
  // keep it on screen: flip sides near the right/bottom edge
  const w = c.offsetWidth || 240, h = c.offsetHeight || 90;
  c.style.left = Math.min(x + 18, window.innerWidth - w - 12) + 'px';
  c.style.top = Math.min(y + 18, window.innerHeight - h - 12) + 'px';
}
function hideCaption() { if (cap) cap.classList.remove('on'); }

/* -- what a node is, in its own words ---------------------------------- */

const num = v => (Math.round(v * 100) / 100);

function facts(el) {
  const kind = el.getAttribute('data-ocd');
  const row = (k, v) => v == null || v === '' ? '' : `<dt>${P.esc(k)}</dt><dd>${P.esc(v)}</dd>`;

  if (kind === 'run') {
    const u = el.getAttribute('data-text') || '';
    const glyphs = el.querySelectorAll('use').length;
    const blanks = (el.getAttribute('data-b') || '').trim().split(/\s+/).filter(Boolean).length;
    const m = /matrix\(([-\d.eE]+)[ ,]+([-\d.eE]+)/.exec(el.getAttribute('transform') || '');
    const angle = m ? num(Math.atan2(+m[2], +m[1]) * 180 / Math.PI) : null;
    return { title: 'text run', sub: el.id, body:
      row('text', u.length > 46 ? u.slice(0, 46) + '…' : u) +
      row('characters', u.length) +
      row('glyphs painted', glyphs + (blanks ? ` (+${blanks} blank)` : '')) +
      row('font', el.getAttribute('data-font')) +
      row('size', el.getAttribute('data-size') ? el.getAttribute('data-size') + ' pt' : null) +
      row('role', el.getAttribute('data-role')) +
      row('baseline', angle !== null && angle !== 0 ? angle + '°' : null) };
  }
  if (el.tagName.toLowerCase() === 'path') {
    const d = el.getAttribute('d') || '';
    const segs = (d.match(/[MLCQZAHVmlcqzahv]/g) || []).length;
    return { title: 'vector path', sub: el.id, body:
      row('segments', segs) +
      row('even-odd', /evenodd/i.test(el.getAttribute('class') || '') || el.getAttribute('fill-rule') === 'evenodd' ? 'yes' : null) +
      row('class', el.getAttribute('class')) +
      row('clip', el.getAttribute('data-clip') ? 'clipped' : null) };
  }
  if (el.tagName.toLowerCase() === 'image') {
    // An OCD <image> is a UNIT SQUARE placed by its matrix (the writer draws it that way), so its
    // width/height attributes are 1 by construction — reporting them says nothing. The size on the
    // page is the length of the matrix's basis vectors; the natural size comes from the bitmap.
    // The placing matrix is not necessarily ON the <image> — it can sit on any ancestor. Take the
    // full element→root transform and measure the basis vectors, so the size is right wherever the
    // transform lives, and multiply by the element's own box (1 for the unit-square convention).
    let w = null, h = null, rot = null;
    try {
      const svg = el.ownerDocument.querySelector('svg');
      const m = svg.getScreenCTM().inverse().multiply(el.getScreenCTM());
      w = num(Math.hypot(m.a, m.b) * (+el.getAttribute('width') || 1));
      h = num(Math.hypot(m.c, m.d) * (+el.getAttribute('height') || 1));
      const ang = num(Math.atan2(m.b, m.a) * 180 / Math.PI);
      if (Math.abs(ang) > 0.5) rot = ang + '°';
    } catch { }
    const href = el.getAttribute('href') || el.getAttributeNS('http://www.w3.org/1999/xlink', 'href') || '';
    const nat = el.naturalWidth || null;
    return { title: 'image', sub: el.id, body:
      row('on page', w != null ? w + ' × ' + h + ' pt' : null) +
      row('pixels', nat ? nat + ' × ' + (el.naturalHeight || '?') : null) +
      row('rotation', rot) +
      row('source', href ? href.replace(/^.*\//, '').slice(0, 34) : null) };
  }
  if (kind === 'paragraph') {
    const runs = [...el.querySelectorAll('[data-ocd="run"]')];
    const lines = el.querySelectorAll('[data-ocd="line"]').length;
    const text = runs.map(r => r.getAttribute('data-text') || '').join('');
    const glyphs = el.querySelectorAll('use').length;
    const blanks = runs.reduce((n, r) => n + (r.getAttribute('data-b') || '').trim().split(/\s+/).filter(Boolean).length, 0);
    const fonts = [...new Set(runs.map(r => r.getAttribute('data-font')).filter(Boolean))];
    const sizes = [...new Set(runs.map(r => r.getAttribute('data-size')).filter(Boolean))];
    const role = el.querySelector('[data-role]')?.getAttribute('data-role');
    return { title: 'block', sub: el.id, body:
      row('text', text.length > 46 ? text.slice(0, 46) + '…' : text) +
      row('role', role || 'paragraph') +
      row('lines', lines) + row('runs', runs.length) +
      row('characters', text.length) +
      row('glyphs painted', glyphs + (blanks ? ` (+${blanks} blank)` : '')) +
      row('font', fonts.length === 1 ? fonts[0] : fonts.length ? fonts.length + ' fonts' : null) +
      row('size', sizes.length === 1 ? sizes[0] + ' pt' : sizes.length ? sizes.length + ' sizes' : null) };
  }
  return null;
}

/* -- the highlight: the real geometry, traced ------------------------- */

/* ONE layer, appended to the <svg> root and reused. The first version inserted the highlight
 * INTO the hovered run: that repaints the run on every pointer move and changes its bounding box
 * while `pointer-events:bounding-box` is using that same box to hit-test — the text visibly
 * blinked. The content is now never mutated; only this layer is emptied and refilled. */
function layerOf(doc) {
  const svg = doc.querySelector('svg'); if (!svg) return null;
  let g = svg.querySelector(':scope > .trc-layer');
  if (!g) { g = doc.createElementNS(SVG_NS, 'g'); g.setAttribute('class', 'trc-layer'); svg.appendChild(g); }
  return g;
}

/** The matrix that maps an element's own coordinates into the <svg> root's, as an SVG transform.
 *  Lets the layer sit at the root while the geometry it draws stays expressed in element space. */
function toRoot(doc, el) {
  try {
    const svg = doc.querySelector('svg');
    const m = svg.getScreenCTM().inverse().multiply(el.getScreenCTM());
    return `matrix(${m.a} ${m.b} ${m.c} ${m.d} ${m.e} ${m.f})`;
  } catch { return null; }
}

function trace(doc, el) {
  const svg = doc.querySelector('svg'); if (!svg) return;
  clearInk(doc);
  const kind = el.getAttribute('data-ocd');
  const tag = el.tagName.toLowerCase();

  // A run or a block: re-draw its own glyph references as outlines, inside the run so every
  // transform above still applies. A <use> inherits fill/stroke, so the SAME reference that
  // paints the glyph draws its skeleton — no second geometry, no risk of drift.
  if (kind === 'run' || kind === 'paragraph') {
    const g = layerOf(doc); if (!g) return;
    let b; try { b = el.getBBox(); } catch { return; }
    if (!b || b.width <= 0) return;
    const r = doc.createElementNS(SVG_NS, 'rect');
    r.setAttribute('class', 'trc-bounds');
    const pad = 1;                                   // a hair of air, so the box does not clip ink
    r.setAttribute('x', b.x - pad); r.setAttribute('y', b.y - pad);
    r.setAttribute('width', b.width + pad * 2); r.setAttribute('height', b.height + pad * 2);
    r.setAttribute('rx', '1.5');
    const t = toRoot(doc, el); if (t) r.setAttribute('transform', t);
    g.appendChild(r);
    return;
  }
  // A vector path: trace its actual `d`, dash-animated over its own length.
  if (tag === 'path') {
    const g = layerOf(doc); if (!g) return;
    const c = el.cloneNode(false);
    c.removeAttribute('id');
    c.setAttribute('class', 'trc-ink');
    const t = toRoot(doc, el); if (t) c.setAttribute('transform', t);
    let len = 300; try { len = Math.max(24, Math.min(4000, el.getTotalLength())); } catch { }
    c.style.setProperty('--trc-len', len);
    g.appendChild(c);
    return;
  }
  // An image has no outline to trace — wash its box instead.
  if (tag === 'image') {
    const g = layerOf(doc); if (!g) return;
    const r = doc.createElementNS(SVG_NS, 'rect');
    r.setAttribute('class', 'trc-wash');
    for (const a of ['x', 'y', 'width', 'height']) if (el.hasAttribute(a)) r.setAttribute(a, el.getAttribute(a));
    const t = toRoot(doc, el); if (t) r.setAttribute('transform', t);
    g.appendChild(r);
  }
}

function clearInk(doc) { try { doc.querySelectorAll('.trc-layer').forEach(n => { n.textContent = ''; }); } catch { } }

/* -- wiring ------------------------------------------------------------ */

const PICK = '[data-ocd="run"], [data-ocd="paragraph"], path[id^="v"], image';
let pinned = false;

function styleOn(doc, on) {
  try {
    let st = doc.getElementById('trc-css');
    if (on && !st) {
      st = doc.createElementNS('http://www.w3.org/1999/xhtml', 'style');
      st.id = 'trc-css'; st.textContent = CSS;
      (doc.head || doc.documentElement).appendChild(st);
    } else if (!on && st) st.remove();
  } catch { }
}

function bind(doc) {
  if (doc.__trcBound) return; doc.__trcBound = true;
  // The caption lives in the chassis, the pointer event in the frame: translate through the
  // iframe's own box. `doc.defaultView.frameElement` is the frame — book.eachFrame yields
  // (doc, idx), and widening a shared iterator for one consumer would be the wrong trade.
  // The frame is CSS-transformed — scaled by --zoom, and TURNED when the page carries a rotation — so
  // the event's clientX/Y are in the frame's own untransformed space while the caption lives in the
  // chassis. `P.frameToClient` is the one mapping (it reads the frame's computed matrix); doing it by
  // hand from the frame's rect drifted with zoom and landed off the page entirely once turned.
  const at = (e) => {
    const fr = doc.defaultView?.frameElement;
    return fr && P.frameToClient ? P.frameToClient(fr, e.clientX, e.clientY) : { x: e.clientX, y: e.clientY };
  };
  doc.addEventListener('mousemove', e => {
    if (!on || pinned) return;
    const raw = e.target.closest?.(PICK);
    // A pointer lands on a RUN — a fragment, often half a sentence. Promote it to the block the
    // analysis actually produced: that is the unit the document is made of, and the unit worth
    // showing. Paths and images have no block and stay themselves.
    const el = raw && raw.getAttribute('data-ocd') === 'run' ? (raw.closest('[data-ocd="paragraph"]') || raw) : raw;
    if (!el) { clearInk(doc); hideCaption(); return; }
    if (el === doc.__trcLast) { const p = at(e); showCaptionFor(el, p); return; }
    doc.__trcLast = el;
    trace(doc, el);
    showCaptionFor(el, at(e));
  });
  doc.addEventListener('mouseleave', () => { if (!pinned) { clearInk(doc); hideCaption(); doc.__trcLast = null; } });
  doc.addEventListener('click', e => {
    if (!on) return;
    if (e.target.closest?.(PICK)) { pinned = !pinned; caption().classList.toggle('pin', pinned); }
  });
}

function showCaptionFor(el, p) {
  const f = facts(el); if (!f) { hideCaption(); return; }
  showCaption(`<b>${P.esc(f.title)}</b><span class="trc-id">${P.esc(f.sub || '')}</span><dl>${f.body}</dl>`, p.x, p.y);
}

// ── Reveal is a MODE OF ANALYSIS, not a tool of its own (2026-09-05) ─────────────────────────────────
//
// It used to be its own tab and it was a dead one: no ribbon, no drawer, nothing on screen said that the
// whole interaction was the pointer. Worse, it answered a question Analysis already answered — click an
// element, get what the model holds — so the app carried two entries to one question.
//
// What is NOT duplicated is the rendering, and that is what survives: Analysis draws the box the model
// reasons about; Reveal traces the REAL geometry, the glyph outlines and the path's own `d`. So it folds
// in as an overlay mode of Analysis, declared in that tool's ribbon, and this file stays separate — the
// merge is an interface decision, not a reason to move 300 lines into another module.
//
// `on` is owned here and read by jexter.js through P.trace, so one flag decides who paints.
let on = false;

function setOn(v) {
  if (on === v) return;
  on = v;
  if (on) eachFrame(doc => { styleOn(doc, true); bind(doc); });
  else { pinned = false; hideCaption(); eachFrame(doc => { doc.querySelectorAll('.trc-layer').forEach(n => n.remove()); styleOn(doc, false); }); }
}

P.trace = { get on() { return on; }, set: setOn, toggle: () => setOn(!on) };

P.on?.('tool', id => { if (id !== 'analysis') setOn(false); });   // leaves with the tool that owns it

P.on?.('frame', (f) => {
  if (!on) return;
  try { styleOn(f.contentDocument, true); bind(f.contentDocument); } catch { }
});

window.addEventListener('keydown', e => {
  if (!on) return;
  if (e.key === 'Escape') { pinned = false; hideCaption(); eachFrame(clearInk); }
});
