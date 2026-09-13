// gizmo.js — a transform handle set for SVG content, usable by any Prism tool.
//
// It knows about GEOMETRY and nothing else: no document model, no persistence, no undo, no ribbon. The
// host says what may be picked and what to do when a gesture ends; the gizmo draws the frame, runs the
// drag, and composes the transform. That split is what makes it reusable — Edit moves page content,
// but a zone editor, a group tool or an annotation placer want exactly the same handles.
//
// THE SPACE IS THE WHOLE PROBLEM. A transform composed in FRONT of the element's own —
// `transform="MINE OWN"` — acts in the PARENT's coordinate system, so every gesture is computed there,
// and the bbox corners are mapped through the element's own matrix first. Measuring with
// getBoundingClientRect instead (always axis-aligned) is why hand-rolled gizmos scale a rotated element
// along the page's axes rather than its own. The frame is drawn inside a <g> carrying the element's
// transform, so it rotates and scales with it for free.
//
// Everything it draws is marked data-ui, which book.persist strips before writing a member: the chrome
// of editing cannot leak into the document, by construction rather than by care.
//
//   const giz = createGizmo(svg, {
//     pick:     (x, y) => elementOrNull,      // the host owns what is selectable
//     onSelect: el => …,                      // what is under the handles changed (null: nothing)
//     onChange: el => …,                      // during a drag, every frame
//     onCommit: el => …,                      // once, when the gesture ends
//     enabled:  true,                         // inert when false: no picking, no handles, no listeners
//     move:     true,                         // drag the body
//     scale:    true,                         // the eight handles
//     rotate:   true,                         // the arm
//     uniform:  false,                        // true: corners always keep the ratio, Shift frees it
//     snap:     15,                           // rotation step with Shift, in degrees (0 = free)
//     hover:    true,                         // faint outline under the pointer
//   });
//   giz.select(el);  giz.clear();  giz.redraw();  giz.set({ rotate:false });  giz.destroy();  giz.target
//
// `enabled` matters as much as the rest: a gizmo bound to a page OUTLIVES the tool that created it, so
// without it the handles keep answering the pointer under the next tool — which is exactly what happened
// to Augment. A host turns it off on leave and on again on enter; nothing is destroyed or rebound.
//
// A pure SELECTOR is this same component with { move:false, scale:false, rotate:false }: it picks, it
// outlines, it draws no handles. One mechanism, not a second component — the capabilities already say it.
//
// The options are READ AT EVERY GESTURE, not captured at creation, so a host can flip one from a ribbon
// button and the next drag obeys — no teardown, no second instance. A tool that places zones wants scale
// without rotation; one that positions a stamp wants rotation and uniform scale only; a reading overlay
// wants move alone. Turning a capability off also removes its handles: an affordance that does nothing
// is worse than an absent one.


// NOT imported from ocd.js, and that is the point: this component knows GEOMETRY and nothing else, so
// it takes no dependency on the OCD grammar (nor, through it, on fflate) for one namespace string. The
// spelling is the shared one — SVG_NS — so a reader moving between files reads the same word.
const SVG_NS = 'http://www.w3.org/2000/svg';

/* -- the rectangle, shared ---------------------------------------------------------------------
 *
 * A host that puts a rectangle under these handles needs the same four things every time: read its box,
 * write it back, fold a composed matrix into it, and clamp it to the page. Pages (the crop) and Redact
 * (a zone) each carried their own copy — identical for `boxOf`, `clampTo` and `normBox`, and for the
 * matrix fold one had it inline in `bake` while the other had extracted it. Two copies of one geometry
 * is how they drift, and this file is already the place that knows geometry and nothing else.
 */

/** A plain rect's own numbers. */
export const boxOf = (r) => ({ x: +r.getAttribute('x'), y: +r.getAttribute('y'), w: +r.getAttribute('width'), h: +r.getAttribute('height') });

/** Write them back, and drop any composed matrix: the numbers ARE the rectangle.
 *
 *  FOUR decimals, not the two the handles are drawn with: a handle is chrome and a rectangle is
 *  geometry a host commits — a crop becomes a page's viewBox, a zone becomes a redaction rectangle.
 *  Rounding it to 2 dp here would quietly cost 0.01 pt on every gesture, in a format whose own rule
 *  (`JxNum`, `ocd.js`) is four. */
const n4 = (v) => Math.round(v * 1e4) / 1e4;
export function setBox(r, b) {
  r.setAttribute('x', n4(b.x)); r.setAttribute('y', n4(b.y));
  r.setAttribute('width', n4(b.w)); r.setAttribute('height', n4(b.h));
  r.removeAttribute('transform');
}

/** The element's box AFTER its own matrix — during a gesture it carries the one composed in front. */
export function outerBox(r) {
  const b = boxOf(r), m = r.transform?.baseVal?.consolidate()?.matrix;
  if (!m) return b;
  const p = (x, y) => ({ x: m.a * x + m.c * y + m.e, y: m.b * x + m.d * y + m.f });
  const c = [p(b.x, b.y), p(b.x + b.w, b.y), p(b.x, b.y + b.h), p(b.x + b.w, b.y + b.h)];
  const xs = c.map(q => q.x), ys = c.map(q => q.y);
  return { x: Math.min(...xs), y: Math.min(...ys), w: Math.max(...xs) - Math.min(...xs), h: Math.max(...ys) - Math.min(...ys) };
}

/** Keep a box inside a w×h frame, never smaller than `min`. */
export function clampTo(box, f, min = 2) {
  const at = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
  const x = at(box.x, 0, f.w), y = at(box.y, 0, f.h);
  return { x, y, w: at(box.w, min, f.w - x), h: at(box.h, min, f.h - y) };
}

/** The box two corners describe, whichever way the drag went. */
export const normBox = (a, b) => ({ x: Math.min(a.x, b.x), y: Math.min(a.y, b.y), w: Math.abs(b.x - a.x), h: Math.abs(b.y - a.y) });

/** A pointer event in the coordinates of `el`'s own viewport. `ref` is the element whose CTM maps the
 *  screen — the page root, or a carrier group on a page written before the rotation moved to data-rot. */
export function svgPointOf(svg, e, ref = svg) {
  const pt = svg.createSVGPoint(); pt.x = e.clientX; pt.y = e.clientY;
  return pt.matrixTransform(ref.getScreenCTM().inverse());
}

const HANDLES = [
  ['nw', 0, 0], ['n', .5, 0], ['ne', 1, 0],
  ['w', 0, .5],               ['e', 1, .5],
  ['sw', 0, 1], ['s', .5, 1], ['se', 1, 1],
];

// The handle you drag moves, the OPPOSITE one stays: that is what makes a resize feel physical.
const ANCHOR = { nw: [1, 1], n: [.5, 1], ne: [0, 1], w: [1, .5], e: [0, .5], sw: [1, 0], s: [.5, 0], se: [0, 0] };

const num = v => Math.round(v * 100) / 100;

export function createGizmo(svg, opts = {}) {
  const doc = svg.ownerDocument;
  const o = {
    pick: () => null, onSelect: () => {}, onChange: () => {}, onCommit: () => {},
    enabled: true, move: true, scale: true, rotate: true, uniform: false, snap: 15, hover: true,
    ...opts,
  };

  let target = null;      // the element under the handles
  let base = '';          // its transform when the current gesture started
  let drag = null;
  let raf = 0;

  /* -- drawing ------------------------------------------------------------------------------- */
  //
  // Drawn in the PARENT's space, with the element's own matrix applied to the CORNERS only. Copying the
  // element's transform onto the frame was simpler and wrong: a non-uniform scale then squashed the
  // handles and the stroke with it — the cursor said "resize" while the gizmo itself deformed. Mapping
  // the corners keeps the frame on the element's axes, and lets the handles stay square whatever the
  // element becomes. That is exactly what every drawing tool does, and now we know why.

  const clearUi = () => doc.querySelectorAll('[data-ui="gz"], [data-ui="gzh"]').forEach(n => n.remove());

  /** Screen pixels expressed in the PARENT's units, so handles keep their size at any zoom. */
  function unit(el) {
    const m = el.parentNode.getScreenCTM();
    return m ? 1 / Math.max(.001, Math.sqrt(Math.abs(m.a * m.d - m.b * m.c))) : 1;
  }

  /** The element's bbox corners, in the parent's space: [nw, n, ne, e, se, s, sw, w, centre]. */
  function corners(el) {
    let b; try { b = el.getBBox(); } catch { return null; }
    if (!b.width && !b.height) return null;
    const own = el.transform?.baseVal?.consolidate()?.matrix;
    const at = (fx, fy) => {
      const p = svg.createSVGPoint(); p.x = b.x + b.width * fx; p.y = b.y + b.height * fy;
      return own ? p.matrixTransform(own) : { x: p.x, y: p.y };
    };
    return { at, quad: [at(0, 0), at(1, 0), at(1, 1), at(0, 1)] };
  }

  // The gizmo is appended to the SVG ROOT, never next to the element: SVG paints in document order, so a
  // frame inserted beside its target is covered by everything drawn after it — the handles end up under
  // the page content, invisible and unclickable, and the click falls through to the move gesture. The
  // points are computed in the PARENT's space, so the group carries the parent's CTM to render them.
  function outlineOf(el, cls, opacity) {
    const c = corners(el); if (!c) return null;
    const u = unit(el);
    const g = doc.createElementNS(SVG_NS, 'g');
    g.setAttribute('data-ui', cls);
    g.setAttribute('pointer-events', 'none');
    const pm = el.parentNode.getCTM?.();
    if (pm) g.setAttribute('transform', `matrix(${pm.a} ${pm.b} ${pm.c} ${pm.d} ${pm.e} ${pm.f})`);
    const poly = doc.createElementNS(SVG_NS, 'polygon');
    poly.setAttribute('points', c.quad.map(p => `${num(p.x)},${num(p.y)}`).join(' '));
    poly.setAttribute('fill', 'none'); poly.setAttribute('stroke', '#33699f');
    poly.setAttribute('stroke-width', 1.2 * u);
    if (opacity != null) poly.setAttribute('stroke-opacity', opacity);
    g.appendChild(poly);
    return { g, c, u };
  }

  function redraw() {
    clearUi();
    if (!target || !o.enabled) return;
    // NOT `o`: that is the options object of the enclosing scope, and shadowing it here made o.rotate,
    // o.scale and o.debug all undefined — the frame drew, every handle and the arm silently did not.
    const f = outlineOf(target, 'gz'); if (!f) return;
    const { g, c, u } = f;
    const hs = 7 * u;

    // The arm leaves from the top edge, along the box's own "up" — the direction from the bottom edge
    // to the top one — so it stays perpendicular to the element however it is rotated.
    if (o.rotate) {
    const top = c.at(.5, 0), bot = c.at(.5, 1);
    const vx = top.x - bot.x, vy = top.y - bot.y;
    const len = Math.hypot(vx, vy) || 1;
    const tip = { x: top.x + (vx / len) * 24 * u, y: top.y + (vy / len) * 24 * u };

    const arm = doc.createElementNS(SVG_NS, 'line');
    arm.setAttribute('x1', num(top.x)); arm.setAttribute('y1', num(top.y));
    arm.setAttribute('x2', num(tip.x)); arm.setAttribute('y2', num(tip.y));
    arm.setAttribute('stroke', '#33699f'); arm.setAttribute('stroke-width', 1.2 * u);
    g.appendChild(arm);

    const knob = doc.createElementNS(SVG_NS, 'circle');
    knob.setAttribute('cx', num(tip.x)); knob.setAttribute('cy', num(tip.y));
    knob.setAttribute('r', hs * .75); knob.setAttribute('fill', '#fff');
    knob.setAttribute('stroke', '#33699f'); knob.setAttribute('stroke-width', 1.2 * u);
    knob.setAttribute('data-gz', 'rot');
    knob.setAttribute('pointer-events', 'all'); knob.setAttribute('style', 'cursor:grab');
    g.appendChild(knob);
    }

    for (const [id, fx, fy] of (o.scale ? HANDLES : [])) {
      const p = c.at(fx, fy);
      const h = doc.createElementNS(SVG_NS, 'rect');
      h.setAttribute('x', num(p.x - hs / 2)); h.setAttribute('y', num(p.y - hs / 2));
      h.setAttribute('width', hs); h.setAttribute('height', hs);   // square in parent space: never squashed
      h.setAttribute('fill', '#fff'); h.setAttribute('stroke', '#33699f');
      h.setAttribute('stroke-width', 1.2 * u);
      h.setAttribute('data-gz', id);
      h.setAttribute('pointer-events', 'all');
      h.setAttribute('style', `cursor:${id}-resize`);
      g.appendChild(h);
    }
    svg.appendChild(g);                                      // topmost: nothing can cover the handles

  }

  function outline(el) {
    doc.querySelectorAll('[data-ui="gzh"]').forEach(n => n.remove());
    if (!el || el === target) return;
    const f = outlineOf(el, 'gzh', .45);
    if (f) svg.appendChild(f.g);
  }

  /* -- the transform ------------------------------------------------------------------------- */

  // Compose IN FRONT of what the element already had: its own coordinates keep their meaning, and the
  // gesture is exactly reversible by restoring one attribute.
  function apply(el, op) {
    el.setAttribute('transform', (op + ' ' + base).trim());
    o.onChange(el);
  }

  /* -- the gestures -------------------------------------------------------------------------- */

  const toParent = (m, cx, cy) => {
    const p = svg.createSVGPoint(); p.x = cx; p.y = cy;
    return p.matrixTransform(m.inverse());
  };

  function onDown(e) {
    if (!o.enabled || e.button !== 0) return;
    const grip = e.target.closest?.('[data-gz]')?.getAttribute('data-gz');

    if (grip === 'rot' && !o.rotate) return;
    if (grip && grip !== 'rot' && !o.scale) return;

    if (grip && target) {
      e.preventDefault(); e.stopPropagation();
      // The matrix is captured HERE and reused for the whole gesture: composing in front changes the
      // element's own CTM at every move, so re-reading it mid-drag would feed the gesture back on itself.
      const pm = target.parentNode.getScreenCTM();
      const b = target.getBBox();
      const own = target.transform?.baseVal?.consolidate()?.matrix;
      const at = (fx, fy) => {
        const p = svg.createSVGPoint(); p.x = b.x + b.width * fx; p.y = b.y + b.height * fy;
        return own ? p.matrixTransform(own) : p;          // the element's OWN axes, not the page's
      };
      base = target.getAttribute('transform') || '';
      const p0 = toParent(pm, e.clientX, e.clientY);
      drag = grip === 'rot'
        ? { kind: 'rot', pm, c: at(.5, .5) }
        : { kind: 'scale', grip, pm, anchor: at(...ANCHOR[grip]), start: p0 };
      if (drag.kind === 'rot') drag.a0 = Math.atan2(p0.y - drag.c.y, p0.x - drag.c.x);
      svg.setPointerCapture(e.pointerId);
      return;
    }

    const el = o.pick(e.clientX, e.clientY);
    if (!el) { clear(); return; }
    e.preventDefault(); e.stopPropagation();
    if (el !== target) select(el);
    if (!o.move) { drag = null; return; }                 // selectable, but not draggable
    base = target.getAttribute('transform') || '';
    const pm = target.parentNode.getScreenCTM();
    drag = { kind: 'move', pm, p0: toParent(pm, e.clientX, e.clientY) };
    svg.setPointerCapture(e.pointerId);
  }

  function onMove(e) {
    if (!o.enabled) return;
    if (!drag) { if (o.hover) outline(o.pick(e.clientX, e.clientY)); return; }
    if (!target) return;
    const p = toParent(drag.pm, e.clientX, e.clientY);

    if (drag.kind === 'move') {
      apply(target, `translate(${num(p.x - drag.p0.x)} ${num(p.y - drag.p0.y)})`);

    } else if (drag.kind === 'scale') {
      const a = drag.anchor;
      const d0x = drag.start.x - a.x, d0y = drag.start.y - a.y;
      let kx = /w|e/.test(drag.grip) && Math.abs(d0x) > 1e-6 ? (p.x - a.x) / d0x : 1;
      let ky = /n|s/.test(drag.grip) && Math.abs(d0y) > 1e-6 ? (p.y - a.y) / d0y : 1;
      if (Math.abs(kx) < .05) kx = .05 * Math.sign(kx || 1);
      if (Math.abs(ky) < .05) ky = .05 * Math.sign(ky || 1);
      // `uniform` decides the DEFAULT and Shift inverts it, which is the convention everywhere: the
      // modifier means "the other behaviour", not always "keep the ratio".
      if ((o.uniform ? !e.shiftKey : e.shiftKey) && kx !== 1 && ky !== 1) {
        const k = Math.max(Math.abs(kx), Math.abs(ky));
        kx = Math.sign(kx) * k; ky = Math.sign(ky) * k;
      }
      apply(target, `translate(${num(a.x)} ${num(a.y)}) scale(${num(kx)} ${num(ky)}) translate(${num(-a.x)} ${num(-a.y)})`);

    } else {
      let deg = (Math.atan2(p.y - drag.c.y, p.x - drag.c.x) - drag.a0) * 180 / Math.PI;
      if (e.shiftKey && o.snap) deg = Math.round(deg / o.snap) * o.snap;
      apply(target, `rotate(${num(deg)} ${num(drag.c.x)} ${num(drag.c.y)})`);
    }
    if (!raf) raf = requestAnimationFrame(() => { raf = 0; redraw(); });
  }

  function onUp(e) {
    if (!o.enabled) return;
    if (e?.pointerId != null && svg.hasPointerCapture?.(e.pointerId)) svg.releasePointerCapture(e.pointerId);
    if (!drag || !target) { drag = null; return; }
    drag = null;
    if (raf) { cancelAnimationFrame(raf); raf = 0; }
    base = target.getAttribute('transform') || '';        // the result becomes the next gesture's baseline
    redraw();
    o.onCommit(target);
  }

  /* -- the surface the host uses ------------------------------------------------------------- */

  // A pure CLICK moves nothing, so neither onChange nor onCommit fires — a host that keeps its own
  // notion of "what is selected" would never learn about it. onSelect is that edge, and it fires for
  // the host's own `select`/`clear` too: one event for one fact, whoever caused it. A host that calls
  // back into the gizmo from it must guard its own re-entrancy — this component does not guess.
  function select(el) { target = el; base = el?.getAttribute('transform') || ''; redraw(); o.onSelect(el); }
  function clear() { const had = target; target = null; clearUi(); if (had) o.onSelect(null); }

  // POINTER events with capture: a drag that leaves the page — and it always does, pulling towards an
  // edge — stops being delivered to this document, so the gesture would freeze and the release never come.
  svg.addEventListener('pointerdown', onDown);
  svg.addEventListener('pointermove', onMove);
  svg.addEventListener('pointerup', onUp);
  svg.addEventListener('pointercancel', onUp);

  return {
    select, clear, redraw,
    /** Change options at runtime — the next gesture obeys, and the handles follow immediately. */
    set(next) { Object.assign(o, next); if (!o.enabled) clearUi(); else redraw(); },
    get options() { return { ...o }; },
    get target() { return target; },
    destroy() {
      clear();
      svg.removeEventListener('pointerdown', onDown);
      svg.removeEventListener('pointermove', onMove);
      svg.removeEventListener('pointerup', onUp);
      svg.removeEventListener('pointercancel', onUp);
    },
  };
}
