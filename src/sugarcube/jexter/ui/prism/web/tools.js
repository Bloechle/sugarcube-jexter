// tools.js — the TOOL registry of the chassis: the tab bar, each tool's ribbon slot and its drawer, and
// the one switch between them (setTool). prism.js publishes it on the seam; the tools register through it.
import { emit } from './events.js';

// ── TOOLS ──────────────────────────────────────────────────────────────────
//
// A TOOL is what the reader is doing to the document: home, edit, analysis, redact… Not a "mode" — that
// word is the layout (scroll · spread · page).
//
// A tool is declarative: { id, label, icon, title?, experimental?, onEnter?(), onLeave?() }
// and it owns THREE surfaces, all keyed on the same thing so none can disagree:
//
//   the tab      .px-tooltab[data-tool=id]      rendered from this registry
//   the ribbon   #ribbon [data-ribbon=id]      its second row, filled by the module via P.ribbon(id)
//   the drawer   #<id>-drawer                  the right pane, opened by CSS on body.tool-<id>
//
// ONE authority sets all three: setTool. It toggles body.tool-<id>; every pane, ribbon slot and
// accent keys off that class, so adding a tool needs no chassis change — register it, ask for its
// ribbon slot, name its drawer, and the layout follows.
//
// `experimental: true` hides the tab and NOTHING else: the module is loaded, registered and wired,
// and setTool(id) still works. Shipping an unfinished tool is deleting one flag. Dev mode reveals
// them — Ctrl/Cmd+Shift+D, or ?dev=1 for a link — and the choice is remembered.
const tools = [];
let tool = 'home';

const DEV_KEY = 'prism.dev';
export function devMode() {
  try {
    const q = new URLSearchParams(location.search);
    if (q.has('dev') || location.hash === '#dev') { localStorage.setItem(DEV_KEY, '1'); return true; }
    return localStorage.getItem(DEV_KEY) === '1';
  } catch { return false; }
}

export function setDevMode(on) {
  try { on ? localStorage.setItem(DEV_KEY, '1') : localStorage.removeItem(DEV_KEY); } catch {}
  if (!on && toolOf(tool)?.experimental) setTool('home');   // never strand the reader on a hidden tab
  renderTabs();
}

/** THE READER: Prism reading, and nothing else (`?reader` — a host that hands people a book to read, never
 *  a workbench). Every tool still loads, but none can be reached: the tab bar is empty, `setTool` only
 *  ever lands on home, the workbench's chrome is hidden (prism.css, `body.px-reader`) and its keys are
 *  off (prism.js). What stays is the reader: the rail (pages, contents, search), the layouts, the zoom,
 *  read aloud, the theme. */
export const readerOnly = (() => { try { return new URLSearchParams(location.search).has('reader'); } catch { return false; } })();

export const toolOf = id => tools.find(t => t.id === id);
/** What the tab bar shows: everything in dev mode, the finished ones otherwise — nothing to the reader. */
const visibleTools = () => readerOnly ? [] : devMode() ? tools : tools.filter(t => !t.experimental);

export function registerTool(t) {
  tools.push(t);
  renderTabs();
  // The tool that is ALREADY current gets its onEnter too — otherwise the first tab registered, which is
  // the one showing at startup, never declares its ribbon (setTool only fires the hook on a change).
  // Deferred by a microtask so the hook runs after this module finishes evaluating, and can reach
  // helpers declared further down.
  if ($.opt(`#${t.id}-drawer`)) drawer(t.id);      // its head comes from this declaration, not from markup
  if (t.id === tool) queueMicrotask(() => t.onEnter?.());
}

function renderTabs() {
  const host = $.opt('.px-tooltabs'); if (!host) return;
  host.cls((devMode() ? '+' : '-') + 'px-dev');   // the bar states the mode; the amber tabs are what SHOWS it
  host.innerHTML = visibleTools().map(t =>
    `<button class="px-tooltab${t.id === tool ? ' active' : ''}${t.experimental ? ' px-wip' : ''}" data-tool="${t.id}"
             title="${$.esc(t.title || t.label)}${t.experimental ? ' — in development' : ''}"><i data-icon="${t.icon}"></i><span>${$.esc(t.label)}</span></button>`).join('');
  $.all('.px-tooltab').forEach(b => b.on('click', () => setTool(b.attr('data-tool'))));
}

export function setTool(id) {
  let next = (!readerOnly && toolOf(id)) || tools[0];      // unknown, or the reader → the first tab (home)
  if (next.experimental && !devMode()) next = tools[0];     // hidden tab asked for outside dev mode
  const prev = toolOf(tool);
  if (prev && prev !== next) prev.onLeave?.();
  if (prev) $('body').cls('-tool-' + prev.id);
  tool = next.id;
  $('body').cls('+tool-' + next.id);
  $.all('.px-tooltab').forEach(b => b.cls((b.attr('data-tool') === tool ? '+' : '-') + 'active'));
  // The tool's three surfaces, marked from one place: its ribbon slot and its drawer follow its tab.
  $.all('#ribbon [data-ribbon]').forEach(el => el.cls((el.attr('data-ribbon') === tool ? '+' : '-') + 'active'));
  $.all('.px-pane-slot').forEach(el => el.cls((el.id === tool + '-drawer' ? '+' : '-') + 'active'));
  $('body').cls(($.opt(`#${tool}-drawer`) ? '+' : '-') + 'has-drawer');   // no slot, no pane, no toggle
  paneHead();
  // A tool activated AFTER pages are loaded has never seen them: the `frame` event fired before it
  // asked. Replay it for what is mounted, so entering a tool is enough to make it work on the page in
  // front of you. Handlers are expected to be idempotent — they mark their own document.
  if (prev !== next) {
    $.all('.prism-frame').forEach(f => {
      const idx = +f.closest('.prism-page')?.getAttribute('data-index');
      if (idx >= 0 && f.contentDocument?.querySelector('svg')) emit('frame', f, idx);
    });
    next.onEnter?.();
  }
  emit('tool', tool);
}

/** A tool's SLOT in the right pane, symmetric with its ribbon slot: `P.drawer(id)` returns it, creating
 *  it if the markup does not carry one. The pane itself — its width, its head, its scrolling, its fold
 *  — belongs to the chassis and is stated once; a module only fills its slot, and never learns that the
 *  five other tools share the column with it. */
function drawer(id) {
  const body = $.opt('#pane-right .px-pane-body'); if (!body) return null;
  let slot = $.opt(`#${id}-drawer`);
  if (!slot) {
    slot = $.create('section', { class: 'px-pane-slot' }).attr('id', `${id}-drawer`);
    slot.mount(body);
  }
  if (id === tool) { slot.cls('+active'); paneHead(); }
  return slot;
}

/** The pane's head, rendered FROM THE REGISTRY — the icon and label the active tool already declared.
 *  Hand-written shells used to repeat them once per tool, which is one chance to drift per tool. */
function paneHead() {
  const head = $.opt('#pane-right .px-pane-head'); if (!head) return;
  const t = toolOf(tool);
  head.innerHTML = `<i data-icon="${$.esc(t?.icon || 'panel-right')}"></i> ${$.esc(t?.drawer || t?.label || tool)}`;
}

/** One item of a ribbon group that is a CONTROL rather than a command — a search box, a colour, a
 *  number, a select. Its VALUE comes from the declaration, so the tool's state is the one authority
 *  and re-declaring can never revert what the reader typed or picked: the bug the hand-appended
 *  version had, where every re-render silently put the colour back to black and emptied the field.
 *  `on(value)` fires on change (add `live` for every keystroke, `enter` for the Enter key).
 *
 *  It is built on the COMMAND's rhythm — the control where a button's icon goes, its caption where the
 *  button's label goes — so a row reads as one line of things to touch and one line of names. A bare
 *  swatch wedged between two-line buttons is the shape that looks wrong, and it looks wrong because it
 *  is a different grammar in the same sentence. */
function control(c) {
  const title = c.title || c.label || '';
  const box = $.create('label', { class: 'px-rb-field' });
  const ctl = $.create('div', { class: 'px-rb-ctl' });
  // No `switch` kind, deliberately: a BOOLEAN is a command that stays lit (`active`), which the ribbon
  // already says everywhere — the pick modes, the page scope, Reveal. Two ways of stating one thing is
  // one of them drifting, and a switch is also the heaviest object in a row of line icons.
  // A SELECT is the one control whose value set comes from the document — the fonts a container
  // carries, a layer, a page range. `options` is re-read on every declaration like everything else
  // here, so a tool refreshes the list by declaring again rather than by poking the DOM.
  if (c.field === 'select') {
    const sel = $.create('select');
    sel.setAttribute('title', title); sel.setAttribute('aria-label', title);
    for (const o of c.options || []) {
      const value = typeof o === 'string' ? o : o.value, label = typeof o === 'string' ? o : o.label;
      const e = document.createElement('option');
      e.value = value; e.textContent = label;
      if (String(value) === String(c.value)) e.selected = true;
      sel.appendChild(e);
    }
    sel.addEventListener('change', () => c.on?.(sel.value));
    sel.mount(ctl); ctl.mount(box);
    if (c.label) $.create('span', { class: 'px-rb-cap', text: c.label }).mount(box);
    box.setAttribute('title', title);
    return box;
  }
  const input = $.create('input');
  input.type = c.field;                                   // search · color · number · text
  input.value = c.value == null ? '' : String(c.value);
  // A WIDE control (a text box) is 1.9rem tall where a small one is 1.15rem — the icon's height — so it
  // fills the box the caption would otherwise share, and a caption under it makes the control taller
  // than a command and THE WHOLE ROW grows to fit: measured 84 px against 72 on Forms, which is a
  // visible jolt of the stage when you switch to that tool. prism.css states the rule ("small controls
  // are captioned, wide ones speak for themselves"); stating it is not enforcing it. So the chassis
  // enforces it here, and does not lose the name: a wide control WEARS its label, as its placeholder.
  const wide = c.field === 'search' || c.field === 'text';
  if (c.placeholder != null) input.placeholder = c.placeholder;
  else if (wide && c.label)  input.placeholder = c.label;
  for (const k of ['min', 'max', 'step']) if (c[k] != null) input.setAttribute(k, String(c[k]));
  if (c.list?.length) {                                   // suggestions, so a regex field is not a blank wall
    const dl = $.create('datalist');
    dl.id = `px-rb-list-${++listSeq}`;
    for (const v of c.list) { const o = document.createElement('option'); o.value = v; dl.appendChild(o); }
    input.setAttribute('list', dl.id);
    box.appendChild(dl);
  }
  input.addEventListener('change', () => c.on?.(input.value));
  if (c.live)  input.addEventListener('input', () => c.on?.(input.value));
  if (c.enter) input.addEventListener('keydown', e => { if (e.key === 'Enter') c.enter(input.value); });
  input.setAttribute('title', title);
  input.setAttribute('aria-label', title);
  input.mount(ctl);
  if (c.unit) { const u = document.createElement('span'); u.className = 'px-rb-unit'; u.textContent = c.unit; ctl.appendChild(u); }
  ctl.mount(box);
  // The caption is the control's own name and goes UNDER it, like every command's label — never inside
  // the control as well: two names for one thing is one of them going stale. A wide control has already
  // taken its name inside (above), which is the only place it fits.
  if (c.label && !wide) $.create('span', { class: 'px-rb-cap', text: c.label }).mount(box);
  box.setAttribute('title', title);
  return box;
}
let listSeq = 0;

/** A tool's ribbon slot, created on first ask, and optionally filled from a DECLARATION:
 *
 *    P.ribbon('redact', [
 *      { group: 'Pick',  items: [{ icon: 'square-dashed', label: 'Zone', on: () => …, active: true }] },
 *   *      { group: 'Mark',  items: [{ field: 'color', label: 'Box', value: st.fill, on: v => … }] },
 *      { group: 'Apply', items: [{ icon: 'eraser', label: 'Apply', title: '…', on: … }] },
 *    ], 'Drag on the page to add a zone · click one to select it');
 *
 * The chassis owns the markup so every tool's ribbon looks the same and no module positions anything —
 * commands AND the controls that configure them, which used to be appended by hand and lost their
 * values on every re-render. Commands carry `active` for a toggle and `disabled`; both are read again
 * on every render, so a module refreshes by declaring again rather than by poking the DOM. The third
 * argument is the help line a tool whose interaction is implicit owes the reader. The slot is still
 * returned, for whatever the grammar does not cover. */
export function ribbon(id, groups, help) {
  const host = $.opt('#ribbon'); if (!host) return null;
  let slot = host.querySelector(`[data-ribbon="${id}"]`);
  if (!slot) {
    slot = $.create('div', { class: 'px-ribbon-slot' }).attr('data-ribbon', id);
    slot.mount(host);
    if (id === tool) slot.cls('+active');
  }
  if (!groups) return slot;

  const focused = slot.contains(document.activeElement) ? document.activeElement.title : null;
  // `selectionStart` THROWS on a number or colour input (InvalidStateError), so it is asked for
  // behind a guard rather than tested by type — the list of types that answer is the browser's, not ours.
  let caret = null;
  if (focused) try { caret = document.activeElement.selectionStart; } catch { }
  slot.empty();
  for (const g of groups) {
    const box = $.create('div', { class: 'px-rb-group' });
    const row = $.create('div', { class: 'px-rb-row' });
    for (const c of g.items || []) {
      if (c.field) { control(c).mount(row); continue; }
      const b = $.create('button', {
        class: 'px-rb-btn' + (c.active ? ' active' : ''),
        title: c.title || c.label,
        onclick: () => c.on?.(),
      });
      if (c.disabled) b.setAttribute('disabled', '');
      $.create('i').attr('data-icon', c.icon || 'square').mount(b);
      $.create('span', { text: c.label }).mount(b);
      b.mount(row);
    }
    row.mount(box);
    if (g.group) $.create('span', { class: 'px-rb-lbl', text: g.group }).mount(box);
    box.mount(slot);
  }
  // The help line shrinks before any command does and can end up ellipsised or gone entirely, so the
  // sentence stays reachable on the element itself.
  if (help) $.create('span', { class: 'px-rb-help', text: help }).attr('title', help).mount(slot);
  // Re-declaring rebuilds the row, which would drop keyboard focus mid-interaction: put it back on the
  // command that had it. Titles are unique within a ribbon, and they are what a screen reader announces.
  // A field also keeps the caret — restoring focus and sending the cursor to 0 is its own small defect.
  const back = focused && slot.querySelector(`[title="${CSS.escape(focused)}"]`);
  if (back) {
    back.focus();
    if (caret != null && back.setSelectionRange) try { back.setSelectionRange(caret, caret); } catch { }
  }
  return slot;
}

// Home declares NO ribbon, deliberately. Layout and zoom live in the footer, where they belong: reading
// returns to them constantly and they cost no height there. Search and contents are the rail's. Putting
// them in a ribbon too would mean two controls for one action, two "active" states to keep in step, and
// a permanent band of chrome for a tool whose whole job is to get out of the way. The row collapses —
// that is what the collapse is for.
registerTool({ id: 'home', label: 'Home', icon: 'book-open', title: 'Home — read the document' });

/** Whether a key event is tool `id`'s to take: that tool is in front, and the key is not typed into a
 *  field — an input's Ctrl+Z is the input's own undo. The one gate of every tool's shortcuts. */
export const keyFor = (id, e) => tool === id && !e.target?.closest?.('input, textarea, select, [contenteditable]');

/** The tool in front. */
export const currentTool = () => tool;
