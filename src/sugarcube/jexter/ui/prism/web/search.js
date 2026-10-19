// search.js — find in the document, accent- and case-folded: the rail's Search tab, the in-page marks,
// and the highlight primitive (markRect) read-aloud and the engine's hook use as well.
//
// The chassis hands over what a search touches (`wireSearch`): the book state, the page it moves to, the
// frames it marks. The engine may answer the in-page highlight itself (hooks.highlight: runs by id).

import { SVG_NS } from '/shared/js/ocd.js';

let C = null;                                   // the chassis: { state, hooks, setCurrent, showPaged, whenFrameReady, centreOn }

export const fold = s => s.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
function runSearch() {
  const q = $('#search-input').val().trim();
  const out = $('#search-results').empty(), info = $('#search-info');
  clearAllHl();                                          // drop marks from a previous query
  if (!q) { info.text(''); return; }
  const nq = fold(q);
  let hits = 0;
  C.state.pages.forEach((pg, idx) => {
    if (!pg.text || !fold(pg.text).includes(nq)) return;
    hits++;
    const li = $.create('li');
    $.create('a', { class: 'nav-link', html: `<b>p.${pg.label}</b><span class="hit">${snippet(pg.text, q)}</span>`, onclick: () => gotoMatch(idx, q) }).mount(li);
    li.mount(out);
  });
  info.text(hits ? `${hits} page${hits > 1 ? 's' : ''} matched` : 'No results');
}

/* -- In-page highlight: talk to the same-origin page frame directly -- */


const clearHl = doc => doc && doc.querySelectorAll('[data-px-hl]').forEach(n => n.remove());
export const clearAllHl = () => $.all('.prism-frame').forEach(f => clearHl(f.contentDocument));

// A translucent <rect> behind an SVG element (text can't hold a <mark>); the
// rect copies the element's transform so it lands exactly on the glyphs.
// Flat, marker-style rectangle. The pad is expressed in PAGE units: a v2 run's
// local space is em-scaled (the font size lives in its matrix), so the pad is
// divided by the local scale — same optical margin at every font size.
//
// `box` is the element's OCCUPIED box in its parent's space, for what has no ink: an inkless run (an OCR
// layer) measures 0 × 0 by getBBox, and a highlight drawn from that is a point. The rect then sits in the
// parent's space, untransformed — the box already went through the element's matrix.
export function markRect(doc, el, attr, fill, box) {
  let bb; try { bb = el.getBBox(); } catch { bb = null; }
  if ((!bb || (!bb.width && !bb.height)) && box) {
    const r = doc.createElementNS(SVG_NS, 'rect');
    r.setAttribute('x', box.x); r.setAttribute('y', box.y);
    r.setAttribute('width', box.width); r.setAttribute('height', box.height);
    r.setAttribute('fill', fill); r.setAttribute('pointer-events', 'none'); r.setAttribute(attr, '1'); r.setAttribute('data-ui', '');
    el.parentNode.insertBefore(r, el);
    return;
  }
  if (!bb) return;
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
  r.setAttribute('pointer-events', 'none'); r.setAttribute(attr, '1'); r.setAttribute('data-ui', '');
  const tr = el.getAttribute('transform'); if (tr) r.setAttribute('transform', tr);
  el.parentNode.insertBefore(r, el);   // before the element → rendered behind it
}

// Highlight every <text> run whose folded content contains the query.
// Returns the first matched <text>, for centring.
function highlightDoc(doc, q, idx) {
  clearHl(doc);
  const svg = doc && doc.querySelector('svg');
  if (!svg || !q) return null;
  const hook = C.hooks.highlight;              // jexter: member runs by id
  if (hook) { const el = hook(doc, q, idx); if (el !== undefined) return el; }
  const nq = fold(q);
  let first = null;
  svg.querySelectorAll('text').forEach(t => {
    if (!fold(t.textContent).includes(nq)) return;
    markRect(doc, t, 'data-px-hl', 'rgba(51,105,159,.32)');
    if (!first) first = t;
  });
  return first;
}

// Jump to a search hit: show its page, highlight the matches, centre on the first.
function gotoMatch(idx, q) {
  idx = $.clamp(idx, 0, C.state.pages.length - 1);
  clearAllHl();
  C.setCurrent(idx);
  if (C.state.mode !== 'scroll') C.showPaged();
  C.whenFrameReady(idx, doc => {
    const first = highlightDoc(doc, q, idx);
    if (first) C.centreOn(idx, first);
    else if (C.state.mode === 'scroll') $.opt(`#page-${idx}`)?.scrollIntoView({ block: 'start' });
  });
}

function snippet(txt, q) {
  const at = fold(txt).indexOf(fold(q));
  if (at < 0) return '';
  const from = Math.max(0, at - 30), to = Math.min(txt.length, at + q.length + 40);
  return `${from > 0 ? '…' : ''}${$.esc(txt.slice(from, at))}<mark>${$.esc(txt.slice(at, at + q.length))}</mark>${$.esc(txt.slice(at + q.length, to))}${to < txt.length ? '…' : ''}`;
}

/** Bind the Search tab to the chassis. */
export function wireSearch(chassis) {
  C = chassis;
  $('#search-btn').on('click', runSearch);
  $('#search-input').on('keydown', e => { if (e.key === 'Enter') runSearch(); });
}

/** Empty the Search tab — the document it searched has left. */
export function clearSearch() {
  $.opt('#search-results')?.empty();
  $.opt('#search-info')?.text('');
  $.opt('#search-input')?.val('');
}
