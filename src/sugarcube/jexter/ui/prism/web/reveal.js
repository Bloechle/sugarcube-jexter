/* reveal.js — "Reveal": point at anything on the page and it answers for itself.
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
 * Read-only and self-cleaning: it injects a stylesheet, a highlight layer and a caption, and
 * `onLeave` removes all three. The model and the container are never touched.
 */
import { book } from '/shared/js/book.js';        // the ONE frame iterator

const P = window.prism;
const eachFrame = book.eachFrame;
const SVGNS = 'http://www.w3.org/2000/svg';

const CSS = `
@keyframes rv-trace { from { stroke-dashoffset:var(--rv-len, 300) } to { stroke-dashoffset:0 } }
@keyframes rv-in    { from { opacity:0 } to { opacity:1 } }

svg .rv-layer { pointer-events:none; }
/* A traced outline is right for a vector path and WRONG for text: at 9 pt the strokes of 70
   glyphs merge into a solid green mass that hides the very words it is pointing at. Text gets its
   BOUNDS — the box the model actually reasons about — and the ink never goes opaque over content. */
svg .rv-ink {
  fill:none; stroke:var(--jx-brand-bright, #74b73e); stroke-width:1.2; opacity:.85;
  stroke-linejoin:round; stroke-linecap:round; vector-effect:non-scaling-stroke;
  stroke-dasharray:var(--rv-len, 300); animation:rv-trace .55s ease-out both, rv-in .12s both;
}
svg .rv-bounds {
  fill:var(--jx-brand-bright, #74b73e); fill-opacity:.13;
  stroke:var(--jx-brand-bright, #74b73e); stroke-opacity:.92; stroke-width:1.1;
  vector-effect:non-scaling-stroke; animation:rv-in .14s both;
}
/* fill-opacity, NOT opacity: rv-in animates the opacity property to 1 and 'both' holds it there,
   so an opacity:.10 base is simply overwritten and the image washed out solid green. Keep the
   animated property and the styled property distinct. (No backticks in here: this block lives
   inside a template literal, and one backtick ends the stylesheet mid-rule — a SyntaxError the
   browser reports as "Unexpected identifier", which node --check does not surface.) */
svg .rv-wash {
  fill:var(--jx-brand-bright, #74b73e); fill-opacity:.13;
  stroke:var(--jx-brand-bright, #74b73e); stroke-opacity:.92; stroke-width:1.1;
  vector-effect:non-scaling-stroke; animation:rv-in .18s both;
}
svg [data-ocd="t"], svg path[id^="v"], svg image { cursor:crosshair; }
/* Glyph OUTLINES are the only painted geometry, so SVG hit-testing loses the pointer in every gap:
   between letters inside a run, and between runs inside a block (leading, run boundaries). Either
   miss tears the highlight down and rebuilds it — it flickers on every move, unusable in front of
   an audience. Both the run AND the block get a box, so the whole paragraph is one continuous
   target, which is what a reader means by "that paragraph". */
svg [data-ocd="t"], svg [data-ocd="p"] { pointer-events:bounding-box; }
`;

/* -- the caption, in the CHASSIS document (not the frame): it must float over the page --- */

let cap = null;
function caption() {
  if (cap && cap.isConnected) return cap;
  cap = document.createElement('div');
  cap.className = 'rv-cap';
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

const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const num = v => (Math.round(v * 100) / 100);

function facts(el) {
  const kind = el.getAttribute('data-ocd');
  const row = (k, v) => v == null || v === '' ? '' : `<dt>${esc(k)}</dt><dd>${esc(v)}</dd>`;

  if (kind === 't') {
    const u = el.getAttribute('data-u') || '';
    const glyphs = el.querySelectorAll('use').length;
    const blanks = (el.getAttribute('data-b') || '').trim().split(/\s+/).filter(Boolean).length;
    const m = /matrix\(([-\d.eE]+)[ ,]+([-\d.eE]+)/.exec(el.getAttribute('transform') || '');
    const angle = m ? num(Math.atan2(+m[2], +m[1]) * 180 / Math.PI) : null;
    return { title: 'text run', sub: el.id, body:
      row('text', u.length > 46 ? u.slice(0, 46) + '…' : u) +
      row('characters', u.length) +
      row('glyphs painted', glyphs + (blanks ? ` (+${blanks} blank)` : '')) +
      row('font', el.getAttribute('data-f')) +
      row('size', el.getAttribute('data-fs') ? el.getAttribute('data-fs') + ' pt' : null) +
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
  if (kind === 'p') {
    const runs = [...el.querySelectorAll('[data-ocd="t"]')];
    const lines = el.querySelectorAll('[data-ocd="l"]').length;
    const text = runs.map(r => r.getAttribute('data-u') || '').join('');
    const glyphs = el.querySelectorAll('use').length;
    const blanks = runs.reduce((n, r) => n + (r.getAttribute('data-b') || '').trim().split(/\s+/).filter(Boolean).length, 0);
    const fonts = [...new Set(runs.map(r => r.getAttribute('data-f')).filter(Boolean))];
    const sizes = [...new Set(runs.map(r => r.getAttribute('data-fs')).filter(Boolean))];
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
  let g = svg.querySelector(':scope > .rv-layer');
  if (!g) { g = doc.createElementNS(SVGNS, 'g'); g.setAttribute('class', 'rv-layer'); svg.appendChild(g); }
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
  if (kind === 't' || kind === 'p') {
    const g = layerOf(doc); if (!g) return;
    let b; try { b = el.getBBox(); } catch { return; }
    if (!b || b.width <= 0) return;
    const r = doc.createElementNS(SVGNS, 'rect');
    r.setAttribute('class', 'rv-bounds');
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
    c.setAttribute('class', 'rv-ink');
    const t = toRoot(doc, el); if (t) c.setAttribute('transform', t);
    let len = 300; try { len = Math.max(24, Math.min(4000, el.getTotalLength())); } catch { }
    c.style.setProperty('--rv-len', len);
    g.appendChild(c);
    return;
  }
  // An image has no outline to trace — wash its box instead.
  if (tag === 'image') {
    const g = layerOf(doc); if (!g) return;
    const r = doc.createElementNS(SVGNS, 'rect');
    r.setAttribute('class', 'rv-wash');
    for (const a of ['x', 'y', 'width', 'height']) if (el.hasAttribute(a)) r.setAttribute(a, el.getAttribute(a));
    const t = toRoot(doc, el); if (t) r.setAttribute('transform', t);
    g.appendChild(r);
  }
}

function clearInk(doc) { try { doc.querySelectorAll('.rv-layer').forEach(n => { n.textContent = ''; }); } catch { } }

/* -- wiring ------------------------------------------------------------ */

const PICK = '[data-ocd="t"], [data-ocd="p"], path[id^="v"], image';
let pinned = false;

function styleOn(doc, on) {
  try {
    let st = doc.getElementById('rv-css');
    if (on && !st) {
      st = doc.createElementNS('http://www.w3.org/1999/xhtml', 'style');
      st.id = 'rv-css'; st.textContent = CSS;
      (doc.head || doc.documentElement).appendChild(st);
    } else if (!on && st) st.remove();
  } catch { }
}

function bind(doc) {
  if (doc.__rvBound) return; doc.__rvBound = true;
  // The caption lives in the chassis, the pointer event in the frame: translate through the
  // iframe's own box. `doc.defaultView.frameElement` is the frame — book.eachFrame yields
  // (doc, idx), and widening a shared iterator for one consumer would be the wrong trade.
  const at = (e) => {
    const fr = doc.defaultView?.frameElement;
    const r = fr?.getBoundingClientRect?.(); if (!r) return { x: e.clientX, y: e.clientY };
    // The frame is CSS-scaled by --zoom, so the event's clientX/Y are in the frame's UNSCALED
    // space while the rect is in screen pixels: the caption drifted further the more you zoomed.
    // Take the factor from the rect itself rather than reading --zoom — one source, always in sync.
    const sx = fr.offsetWidth ? r.width / fr.offsetWidth : 1;
    const sy = fr.offsetHeight ? r.height / fr.offsetHeight : 1;
    return { x: r.left + e.clientX * sx, y: r.top + e.clientY * sy };
  };
  doc.addEventListener('mousemove', e => {
    if (P.appMode?.() !== 'reveal' || pinned) return;
    const raw = e.target.closest?.(PICK);
    // A pointer lands on a RUN — a fragment, often half a sentence. Promote it to the block the
    // analysis actually produced: that is the unit the document is made of, and the unit worth
    // showing. Paths and images have no block and stay themselves.
    const el = raw && raw.getAttribute('data-ocd') === 't' ? (raw.closest('[data-ocd="p"]') || raw) : raw;
    if (!el) { clearInk(doc); hideCaption(); return; }
    if (el === doc.__rvLast) { const p = at(e); showCaptionFor(el, p); return; }
    doc.__rvLast = el;
    trace(doc, el);
    showCaptionFor(el, at(e));
  });
  doc.addEventListener('mouseleave', () => { if (!pinned) { clearInk(doc); hideCaption(); doc.__rvLast = null; } });
  doc.addEventListener('click', e => {
    if (P.appMode?.() !== 'reveal') return;
    if (e.target.closest?.(PICK)) { pinned = !pinned; caption().classList.toggle('pin', pinned); }
  });
}

function showCaptionFor(el, p) {
  const f = facts(el); if (!f) { hideCaption(); return; }
  showCaption(`<b>${esc(f.title)}</b><span class="rv-id">${esc(f.sub || '')}</span><dl>${f.body}</dl>`, p.x, p.y);
}

P.registerMode({
  id: 'reveal', label: 'Reveal', icon: 'scan-search', experimental: true,
  title: 'Reveal — point at anything and it answers for itself',
  onEnter() { eachFrame(doc => { styleOn(doc, true); bind(doc); }); },
  onLeave() { pinned = false; hideCaption(); eachFrame(doc => { doc.querySelectorAll('.rv-layer').forEach(n => n.remove()); styleOn(doc, false); }); },
});

P.on?.('frame', (f) => {
  if (P.appMode?.() !== 'reveal') return;
  try { styleOn(f.contentDocument, true); bind(f.contentDocument); } catch { }
});

window.addEventListener('keydown', e => {
  if (P.appMode?.() !== 'reveal') return;
  if (e.key === 'Escape') { pinned = false; hideCaption(); eachFrame(clearInk); }
});
