// editor.js — "Edit": the first tool that changes the DOCUMENT.
//
// Everything else in Prism is additive. Augment attaches a sidecar and never touches the page; Analysis
// and Trace only look. This one mutates the model, so it is held to a different standard and it starts
// deliberately small: MOVE, SCALE, ROTATE and REMOVE one element. One operation proven end to end is
// worth more than five half-done, because every one of them has to survive six projections.
//
// The handles are NOT here: they are gizmo.js, a component that knows geometry and nothing else. This
// file owns what is specific to editing a document — which elements may be picked, when a change is
// written, and how it is undone. A zone editor or a group tool reuses the same gizmo with another pick.

import { book } from '/shared/js/book.js';
import { createGizmo } from './gizmo.js';

const P = window.prism;
const PICK = 'image, path[id], [data-ocd="run"], [data-ocd="paragraph"]';

// A page's first child is usually its backdrop — a full-bleed path covering every point. It is the
// topmost thing under every click, so a naive pick always grabs it and dragging it moves white over
// white: the tool looks dead while working perfectly. Anything covering most of the page is furniture.
const COVERS = 0.85;

const gizmos = new Map();    // idx -> gizmo, one per bound page

// What the gizmo is allowed to do, flipped from the ribbon. Kept HERE and pushed into every page's
// gizmo, so the pages cannot disagree about what a drag will do.
const caps = { move: true, scale: true, rotate: true, uniform: false };
const undoStack = [];        // { idx, el, before, parent?, next? }
let before = '';             // the selected element's transform when the current gesture started

/* -- what may be picked -------------------------------------------------------------------------- */
//
// Hit-test by GEOMETRY, not by the DOM. Two browser facts make elementsFromPoint useless here, and both
// were measured on a real page rather than assumed:
//   . a text run's glyphs are <use href="fonts.svg#..."> — EXTERNAL references, which Chrome does not
//     hit-test, so a click straight on a word returns nothing at all;
//   . the only thing that does answer is the page backdrop, which covers every point.
// So: walk the candidates, keep those whose SCREEN rect contains the point, take the SMALLEST. Screen
// space, because a bbox is in the element's own user space before its transform — a placed image reads
// 1 x 1 there, its size living in its matrix.
function pickIn(doc, svg) {
  return (cx, cy) => {
    const pr = svg.getBoundingClientRect();
    const area = pr.width * pr.height;
    let best = null, bestArea = Infinity;
    for (const el of doc.querySelectorAll(PICK)) {
      if (el.closest('[data-ui]')) continue;                       // never pick the editor's own chrome
      const r = el.getBoundingClientRect();
      if (r.width < 2 || r.height < 2) continue;                   // a hairline or an empty group
      if (cx < r.left || cx > r.right || cy < r.top || cy > r.bottom) continue;
      const a = r.width * r.height;
      if (area && a / area > COVERS) continue;                     // a backdrop, not a thing to edit
      if (a < bestArea) { best = el; bestArea = a; }               // the tightest box wins
    }
    return best;
  };
}

/* -- binding a page ------------------------------------------------------------------------------ */

function bind(doc, idx) {
  const svg = doc.querySelector('svg[data-ocd="page"]');
  if (!svg || gizmos.has(idx)) return;

  // The armed click is taken in the CAPTURE phase, before the gizmo's own pointerdown: the point is to
  // write where you point, not to pick what is already there. A gesture carries ITS OWN page index —
  // in scroll mode the page under the pointer and the "current" page are routinely different.
  svg.addEventListener('pointerdown', e => {
    if (!txt.arming || P.tool?.() !== 'edit' || e.button !== 0) return;
    e.preventDefault(); e.stopPropagation();
    const pt = svg.createSVGPoint(); pt.x = e.clientX; pt.y = e.clientY;
    const q = pt.matrixTransform(svg.getScreenCTM().inverse());
    const fr = book.page(idx)?.frame || { x: 0, y: 0, w: 0, h: 0 };
    writeText(idx, q.x + fr.x, fr.y + fr.h - q.y);     // svg (top-down) → page space (y-up)
  }, true);

  gizmos.set(idx, createGizmo(svg, {
    ...caps,
    enabled: P.tool?.() === 'edit',                      // a page bound while another tool is up stays quiet
    pick: pickIn(doc, svg),
    onCommit: el => {
      if (P.tool?.() !== 'edit') return;
      undoStack.push({ idx, el, before });
      before = el.getAttribute('transform') || '';
      book.persist(idx);                                           // debounced 300 ms, data-ui stripped
      describe(el);
      ribbon();
      P.setStatus?.('<i data-lucide="pencil"></i> Edited — Ctrl+Z to undo');
    },
  }));
}

const each = fn => gizmos.forEach(fn);

function selected() {
  for (const [idx, g] of gizmos) if (g.target) return { idx, g, el: g.target };
  return null;
}

/* -- the drawer ---------------------------------------------------------------------------------- */

function describe(el) {
  // Selecting a run puts ITS text in the field: Retype then rewrites what you are looking at, and a
  // reader who wanted to change one word does not have to retype the sentence to avoid replacing it.
  if (el && el.getAttribute('data-ocd') === 'run' && el.hasAttribute('data-text')) txt.text = el.getAttribute('data-text');
  const info = document.getElementById('ed-info');
  if (!info) return;
  if (!el) { info.textContent = 'Click content on a page to select it.'; return; }
  const r = el.getBoundingClientRect();
  info.textContent = `${el.tagName}${el.id ? ' #' + el.id : ''} — ${Math.round(r.width)} x ${Math.round(r.height)} px on screen`;
}

/* -- verbs --------------------------------------------------------------------------------------- */

function deselect() {
  each(g => g.clear());
  describe(null);
  ribbon();
}

function remove() {
  const s = selected(); if (!s) return;
  undoStack.push({ idx: s.idx, el: s.el, before: s.el.getAttribute('transform') || '',
                   parent: s.el.parentNode, next: s.el.nextSibling });
  s.g.clear();
  s.el.remove();
  book.persist(s.idx);
  describe(null);
  ribbon();
  P.toast?.('Removed — Ctrl+Z to undo', 'primary');
}

function undo() {
  const u = undoStack.pop();
  if (!u) { P.toast?.('Nothing to undo', 'neutral'); return; }
  if (u.also?.el?.isConnected) u.also.el.remove();        // a retype is TWO halves; one Ctrl+Z undoes both
  if (u.added) u.el.remove();                             // an addition is undone by taking it away
  else if (u.parent && !u.el.isConnected) u.parent.insertBefore(u.el, u.next);
  if (u.before) u.el.setAttribute('transform', u.before); else u.el.removeAttribute('transform');
  gizmos.get(u.idx)?.clear();
  book.persist(u.idx);
  describe(null);
  ribbon();
}

/* -- writing text: the second thing this tool adds to a document ---------------------------------- */
//
// A run is PAGE CONTENT, which is why it lives here and not in Augment: Augment attaches a sidecar and
// bakes it at export, while this writes into the model every projection reads. `ocd.js` owns the
// grammar — `OcdPage.text()` states data-size, accounts for every character in data-blanks and addresses the
// SHARED library — so this file only says what, where, and in which font.
//
// Placement is a CLICK, not a dialog: the text lands where you point, and the gizmo that is already
// bound to the page picks it up immediately — so "a bit lower, a bit bigger" is a drag, not a re-type.

const txt = { text: 'Text', size: 18, font: null, arming: false };

/** The fonts the open container carries, by their stated name (never by alias — the engine re-derives
 *  fN alphabetically on every write). */
const fontsOf = () => (book.page(idxNow())?.doc.fonts || []).map(f => f.safe);
const idxNow = () => Math.max(0, P.state.current ?? 0);

/** The characters the chosen font cannot paint. A container's font is a SUBSET — it carries the glyphs
 *  its pages used and nothing else — so writing "Premier jet" in a font imported for "Bonjour, Jean-Luc!"
 *  prints "re erje", correctly and silently. The text is intact in the model either way; what is missing
 *  is the ink, and the reader has to be told which characters and what to do about it. */
function missingIn(font) {
  const f = (book.page(idxNow())?.doc.fonts || []).find(x => x.safe === font);
  if (!f) return '';
  const out = [];
  for (const ch of txt.text) {
    if (ch === ' ' || ch === '\u00a0') continue;                 // a space paints nothing anywhere
    const gid = f.cmap.get(ch.codePointAt(0));
    if (gid == null || !f.glyphByGid.get(gid)?.d) out.push(ch);
  }
  return [...new Set(out)].join('');
}

/** Say it once, where it happens: the run is written either way — its text is in the model — but the
 *  characters below will not paint until a font that has them is added, which Font… does for exactly
 *  the characters typed. */
function warnMissing(font) {
  const miss = missingIn(font);
  if (miss) P.toast?.(`${font} has no glyph for ${[...miss].map(c => `"${c}"`).join(' ')} — the text is `
                    + `written and those characters paint nothing. Font… adds a file for the characters you typed.`,
                      'warning', 9000);
}

/** Arm one click: the next pointerdown on a page writes the run there instead of picking. */
function armText() {
  if (!book.isOpen()) return;
  txt.arming = !txt.arming;
  ribbon();
  P.toast?.(txt.arming ? 'Click on the page where the text should start.' : 'Cancelled.',
            txt.arming ? 'primary' : 'neutral');
}

/** Write the run at a page point (page space, y-up — what `OcdPage.text` takes). */
function writeText(idx, x, y) {
  const p = book.page(idx); if (!p) return;
  const names = fontsOf();
  const font = txt.font && names.includes(txt.font) ? txt.font : names[0];
  if (!font) { P.toast?.('This container carries no font to write with — add one first.', 'warning'); return; }
  let r;
  try { r = p.text({ text: txt.text, x, y, size: txt.size, font }); }
  catch (e) { P.toast?.(`Could not write: ${e.message || e}`, 'danger'); return; }
  // Added, so it must be removable the same way a removal is: the undo entry carries the parent and the
  // next sibling, which is exactly what `remove()` records — one stack, one shape.
  undoStack.push({ idx, el: r.el, before: '', parent: r.el.parentNode, next: r.el.nextSibling, added: true });
  book.persist(idx, true);
  gizmos.get(idx)?.select(r.run);          // the RUN, not its paragraph: it is what Retype acts on
  describe(r.run);
  txt.arming = false;
  ribbon();
  P.toast?.(`Written in ${font} — drag it, or Ctrl+Z`, 'success');
  warnMissing(font);
}

/** Load a font file into the open container: the engine turns it into the container's own font block
 *  (/api/font), `book` merges it, and it becomes selectable here. */
async function addFontFile() {
  const inp = document.createElement('input');
  inp.type = 'file'; inp.accept = '.ttf,.otf,font/ttf,font/otf';
  inp.onchange = async () => {
    const file = inp.files?.[0]; if (!file) return;
    try {
      const alias = book.freeAlias();
      const r = await fetch(`/api/font?chars=${encodeURIComponent(txt.text)}&id=${alias}`,
                            { method: 'POST', body: await file.arrayBuffer() });
      if (!r.ok) throw new Error((await r.json().catch(() => ({}))).error || `HTTP ${r.status}`);
      const f = await book.addFont(await r.text());
      txt.font = f.safe;
      ribbon();
      P.toast?.(`${f.safe} added — ${f.glyphs.length} glyph(s), for the characters of your text`, 'success');
    } catch (e) { P.toast?.(`Could not add the font: ${e.message || e}`, 'danger'); }
  };
  inp.click();
}

/** The selected RUN, if the selection is one — or the single run of a selected block, because the
 *  picker promotes a click on glyphs to the block and a one-run block is the same thing said twice. */
function selectedRun() {
  const sel = selected(); if (!sel) return null;
  if (sel.el.getAttribute('data-ocd') === 'run') return sel;
  if (sel.el.getAttribute('data-ocd') === 'paragraph') {
    const runs = sel.el.querySelectorAll(':scope [data-ocd="run"]');
    if (runs.length === 1) return { ...sel, el: runs[0] };
  }
  return null;
}

/** Rewrite the selected run with the current text: same origin, same size, same font unless another
 *  was picked. A run is not editable in place — its glyphs ARE its geometry — so this is a remove and
 *  a write, which is also why one Ctrl+Z puts the old one back: both halves go on the same stack. */
function retype() {
  const sel = selectedRun(); if (!sel) return;
  const el = sel.el, idx = sel.idx;
  const m = /matrix\(([-\d.eE]+)[ ,]+[-\d.eE]+[ ,]+[-\d.eE]+[ ,]+[-\d.eE]+[ ,]+([-\d.eE]+)[ ,]+([-\d.eE]+)\)/
              .exec(el.getAttribute('transform') || '');
  if (!m) { P.toast?.('That run states no matrix — nothing to place it by.', 'warning'); return; }
  const p = book.page(idx); if (!p) return;
  const fr = p.frame;
  const size = Math.abs(+m[1]) || txt.size;
  const x = +m[2] + fr.x, y = fr.y + fr.h - +m[3];        // back to page space, y-up
  const names = fontsOf();
  const font = txt.font && names.includes(txt.font) ? txt.font
             : (names.includes(el.getAttribute('data-font')) ? el.getAttribute('data-font') : names[0]);
  const parent = el.parentNode, next = el.nextSibling;
  let r;
  try { r = p.text({ text: txt.text, x, y, size, font }); }
  catch (e) { P.toast?.(`Could not write: ${e.message || e}`, 'danger'); return; }
  el.remove();
  undoStack.push({ idx, el, before: el.getAttribute('transform') || '', parent, next,
                   also: { el: r.el, parent: r.el.parentNode, next: r.el.nextSibling } });
  book.persist(idx, true);
  gizmos.get(idx)?.select(r.run);
  describe(r.run);
  ribbon();
  P.toast?.('Retyped — Ctrl+Z puts the old one back', 'success');
  warnMissing(font);
}

/* -- ribbon -------------------------------------------------------------------------------------- */

function toggle(k) {
  caps[k] = !caps[k];
  each(g => g.set(caps));          // every page obeys the same rules, immediately
  ribbon();
}

function ribbon() {
  // TWO different questions, and `has` in this file has always answered the second: `open_` is whether a
  // document is open at all (nothing in a ribbon is armed without one — the rule every tool follows),
  // `has` is whether something is selected. Wiring the capability toggles to `has` would have disarmed
  // them until you had already selected something, which is backwards: they decide what the next drag
  // may do.
  const open_ = book.isOpen(), has = !!selected();
  P.ribbon('edit', [
    { group: 'Selection', items: [
      { icon: 'mouse-pointer-2', label: 'Pick',  title: 'Click content on the page to select it', active: has, disabled: !open_, on: () => {} },
      { icon: 'x',               label: 'Clear', title: 'Deselect (Esc)', disabled: !has, on: deselect },
    ]},
    // Capabilities, not settings buried in a dialog: what a drag will do must be visible where the drag
    // is about to happen. Turning one off removes its handles too.
    { group: 'Allow', items: [
      { icon: 'move',        label: 'Move',   title: 'Drag the body to move',            active: caps.move,   disabled: !open_, on: () => toggle('move') },
      { icon: 'scaling',     label: 'Scale',  title: 'The eight handles resize',         active: caps.scale,  disabled: !open_, on: () => toggle('scale') },
      { icon: 'rotate-cw',   label: 'Rotate', title: 'The arm rotates — Shift snaps to 15°', active: caps.rotate, disabled: !open_, on: () => toggle('rotate') },
      { icon: 'lock',        label: 'Ratio',  title: 'Corners keep the ratio; Shift frees it', active: caps.uniform, disabled: !open_, on: () => toggle('uniform') },
    ]},
    // A font is added FOR THE CHARACTERS you typed — that is why the field comes before the command.
    { group: 'Write', items: [
      { field: 'text', value: txt.text, live: true, title: 'The text to write', placeholder: 'text…',
        on: (v) => { txt.text = v; } },
      // The value set is the DOCUMENT's, so it is re-read on every declaration: a font added a second
      // ago is in the list without anything having to refresh it.
      { field: 'select', value: txt.font || fontsOf()[0] || '', options: fontsOf(), label: 'Font',
        title: 'Which of the fonts this document carries to write with', on: (v) => { txt.font = v; } },
      { field: 'number', value: txt.size, min: 4, max: 400, step: 1, unit: 'pt', title: 'Size, in points',
        on: (v) => { txt.size = Math.max(1, Math.round(+v || 18)); } },
      { icon: 'type', label: 'Place', title: 'Click on the page where the text should start',
        active: txt.arming, disabled: !open_, on: armText },
      { icon: 'file-plus', label: 'Font…', title: 'Add a font file to this document, for the characters of your text',
        disabled: !open_, on: addFontFile },
      { icon: 'replace', label: 'Retype', title: 'Rewrite the selected run with the text above — one Ctrl+Z puts the old one back',
        disabled: !selectedRun(), on: retype },
    ]},
    { group: 'Change', items: [
      { icon: 'trash-2', label: 'Remove', title: 'Remove the selected element (Del)', disabled: !has, on: remove },
      { icon: 'undo-2',  label: 'Undo',   title: 'Undo the last change (Ctrl+Z)', disabled: !undoStack.length, on: undo },
    ]},
  ], 'Hover to see what is editable — Allow decides what a drag may do');
}

/* -- the tool ------------------------------------------------------------------------------------ */

P.registerTool({
  id: 'edit', label: 'Edit', icon: 'pencil', drawer: 'Edit', experimental: true,
  title: 'Edit — move, scale, rotate or remove content; this one changes the document',
  onEnter() {
    ribbon(); describe(null);
    book.eachFrame((doc, idx) => bind(doc, idx));
    each(g => g.set({ enabled: true }));
  },
  onLeave() {
    // A gizmo outlives the tool: it stays bound to its page so re-entering costs nothing, but it must go
    // INERT or it keeps answering the pointer under the next tool — Augment inherited its handles once.
    each(g => g.set({ enabled: false }));
    deselect();
    book.flush();                                        // never leave a page half-written
  },
});

P.on?.('frame', (f, idx) => { try { bind(f.contentDocument, idx); } catch { } });
P.on?.('close', () => { each(g => g.destroy()); gizmos.clear(); undoStack.length = 0; });

window.addEventListener('keydown', e => {
  if (P.tool?.() !== 'edit') return;
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') { e.preventDefault(); undo(); }
  else if (e.key === 'Delete' || e.key === 'Backspace') { e.preventDefault(); remove(); }
  else if (e.key === 'Escape') deselect();
});
