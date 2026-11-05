/**
 * qry-app.js — the APP tier (ES module): dialogs, theme, files, layout widgets, canvas, charts,
 * keyboard, icons, PWA, tables — over qry.js and qry-ui.css, for Chromium 133+ and Safari 26+.
 * It re-exports everything of qry-web.js (the website tier), so an app imports from ONE file:
 *
 *   <link rel="stylesheet" href="qry-ui.css">
 *   <script src="qry.js"></script>
 *   <script type="module">
 *     import { boot, toast, ask, makeTabs, makeKeyboard, labels } from './qry-app.js';   // + qry-web.js beside it
 *   </script>
 *
 * The conventions are qry-web.js's (AGENTS.md has them all).
 *
 *   DIALOGS     toast · ask (any dialog) · alert · confirm · prompt (ask, pre-set) · makeDialog (a page's own <dialog>)
 *   THEME       theme.{mode,toggle,isDark,init} · boot
 *   FILES       copy · download · saveFile · openFile · readFiles · zip
 *   LAYOUT      makeTabs · makeMenu · makePopover · toggletips · makeSplitter · makeSidebar · makeDrawer · makeAutoHideHeader · makeZoomPan
 *   CANVAS      makeInk (freehand: signatures, notes) · makeLaser (a talk's laser pointer) — on qry-web's makeCanvas
 *   CHARTS      makeChart (dots, lines, bars, rules — plain SVG in the theme's tokens)
 *   INPUT       makeKeyboard · makeSlider (a custom slider's keys + ARIA) · makeCalendar (a month to pick a day) · makeDropZone (drop, paste and choose)
 *   CONSOLE     makeConsole (the app's log in a drawer: its own lines, console.*, errors — F2)
 *   SERVER      makeEvents (one SSE stream: online, last(name), on(name))
 *   ICONS       icons.add · icons.watch (boot starts it) · icon   (qry draws none: the app registers its own)
 *   DEVICE      makePwa (service worker, updates, install, offline copy) — the wake lock is qry-web's
 *   TABLES      makeTable · makeChips · infoRow
 *
 * Requires qry.js 3.x (global `$`) and qry-web.js beside this file.
 *
 * @version 3.7.1
 * @author  Jean-Luc Bloechle with Claude.ai
 * @license MIT
 */
import { labels, format, busy, makeCanvas, makeChoice } from './qry-web.js';

export * from './qry-web.js';

/** @private — a controller's teardown: listeners get `on` ({ signal }), destroy() calls `off`
 *  (qry-web.js has the same four lines: a module shares no private helper). */
const _scope = () => {
    const ac = new AbortController();
    return { on: { signal: ac.signal }, signal: ac.signal, off: () => ac.abort() };
};

// ════════════════════════════════════════════════════════════════════════════
// DIALOGS — native <dialog> and a toast stack, styled by qry-ui.css
// ════════════════════════════════════════════════════════════════════════════

/** @private — a click on a modal dialog's backdrop: the target is the dialog itself AND the
 *  point is outside its box (its own padding is the dialog too). */
const _backdrop = (dlg, e) => {
    const r = dlg.getBoundingClientRect();
    return e.target === dlg && (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom);
};

/** Any modal question → a Promise of the answer. A <dialog class="qry-dialog"> is built,
 *  shown, and removed once closed; the promise settles exactly once: with the clicked button's
 *  `value`, or with `dismiss` on Escape or a click outside the box.
 *  - `body`: a Node (fields, a radio group…), a $.html result, or text (escaped, in a <p>).
 *  - a button: `{ label, value, tone, focus }` — `tone` is a class ('primary', 'danger', any
 *    tone; none = a plain button); a `value` that is a FUNCTION (even async) is called on click
 *    and its result is the answer — read the fields of the body there. If it THROWS, the dialog
 *    stays open and shows the error's message under the body (`.qry-dialog-error`): validation is
 *    a throw, as on a QryHost route; the button is busy (aria-busy) while a promise runs.
 *  - focus: a body element with `autofocus`, else the button marked `focus`, else the last one;
 *    it goes back where it was once closed.
 *  - named by its title (else its body), described by its body; `role: 'alertdialog'` for a
 *    message that needs an answer (alert and confirm); `class` adds classes to the <dialog> (e.g.
 *    'center': centred, equal-width actions).
 *  - `dismissible: false`: only a button answers — Escape and a click outside do nothing (a
 *    required choice, a lock screen). Chromium closes a dialog anyway on a second Escape without
 *    a click between (its anti-abuse rule): the dialog then simply opens again.
 *  alert, confirm and prompt are this, pre-set (and take `class` too).
 *  @param {{title?: string, body?: Node|string|Object, buttons: Array<{label: string, value?: any, tone?: string, focus?: boolean}>, dismiss?: any, dismissible?: boolean, role?: 'dialog'|'alertdialog', class?: string}} o
 *  @returns {Promise<any>}
 *  @example
 *  const body = $.create('div', { html: $.html`<label><input type="radio" name="why" value="typo" checked> A typo</label>
 *      <label><input type="radio" name="why" value="wrong"> A wrong answer</label><textarea class="qry-input" name="note"></textarea>` });
 *  const report = await ask({ title: 'Report this question', body, dismiss: null, buttons: [
 *      { label: labels.cancel, value: null },
 *      { label: 'Send', tone: 'primary', value: () => ({ why: body.querySelector(':checked').value, note: body.querySelector('textarea').value }) }] });
 */
export const ask = ({ title, body, buttons = [], dismiss, dismissible = true, role = 'dialog', class: cls = '' } = {}) => new Promise((resolve) => {
    const id = $.uid('qry-dlg'), back = document.activeElement;
    const dlg = $.create('dialog', { class: `qry-dialog ${cls}`.trim(), role: role === 'dialog' ? null : role, 'aria-describedby': `${id}-body`,
        'aria-labelledby': title ? `${id}-title` : `${id}-body` });
    dlg.html($.html`${title && $.html`<h2 class="qry-dialog-title" id="${id}-title">${title}</h2>`}<div class="qry-dialog-body" id="${id}-body"></div>
        <div class="qry-dialog-actions">${buttons.map((b, i) => $.html`<button type="button" class="qry-btn ${b.tone ?? ''}" data-act="${i}">${b.label}</button>`)}</div>`);
    const box = dlg.querySelector('.qry-dialog-body');
    if (body instanceof Node) box.add(body);
    else if (typeof body === 'string' || typeof body === 'number') box.add($.create('p', { text: body }));
    else if (body != null) box.html(body);                              // a $.html result
    const error = $.create('p', { class: 'qry-dialog-error', role: 'alert', hidden: true });
    box.after(error);
    let value = dismiss, answered = false, running = false;
    const done = (v) => { value = v; answered = true; dlg.close(); };
    dlg.on('click', async (e) => {
        if (_backdrop(dlg, e)) return dismissible && !running && done(dismiss);
        const btn = e.target.closest('[data-act]');
        if (!btn || running) return;
        const b = buttons[+btn.dataset.act];
        if (typeof b.value !== 'function') return done(b.value);
        running = true;
        try { const v = await busy(btn, b.value); error.hidden = true; done(v); }
        catch (err) { error.text(err?.message || String(err)).hidden = false; }      // stays open: say why
        finally { running = false; }
    });
    dlg.on('cancel', (e) => { e.preventDefault(); if (dismissible && !running) done(dismiss); });     // Escape
    dlg.on('close', () => {
        if (!answered && (running || !dismissible)) return dlg.showModal();   // closed by the browser itself (a second Escape), or while a button runs: again
        dlg.remove(); if (back?.isConnected) back.focus(); resolve(value);
    });
    dlg.mount(document.body).showModal();
    const acts = dlg.find('[data-act]');
    (box.querySelector('[autofocus]') ?? acts[buttons.findIndex((b) => b.focus)] ?? acts.at(-1))?.focus();
});

/** A page's own modal <dialog> (rules, settings, a form): `open()` shows it, and it closes on
 *  Escape (native), on a click on its backdrop, and on a click on a `close` element inside it;
 *  the focus goes back where it was. `onClose(returnValue)` follows every close.
 *  @param {string|HTMLDialogElement} dialog
 *  @param {{close?: string, onClose?: (value: string) => void}} [opts]  close: a selector (default '[data-close]')
 *  @returns {{ open: () => void, close: (value?: string) => void, isOpen: () => boolean, destroy: () => void }}
 *  @example const rules = makeDialog('#rules'); $('#help').on('click', rules.open)
 */
export const makeDialog = (dialog, { close = '[data-close]', onClose } = {}) => {
    const dlg = $(dialog), { on, off } = _scope();
    let back = null;
    dlg.on('click', (e) => { if (_backdrop(dlg, e) || e.target.closest(close)) dlg.close(); }, on);
    dlg.on('close', () => { back?.focus?.(); back = null; onClose?.(dlg.returnValue); }, on);
    return {
        open() { if (dlg.open) return; back = document.activeElement; dlg.showModal(); },
        close: (value) => dlg.close(value),
        isOpen: () => dlg.open,
        destroy: off,
    };
};

/** A message that goes away by itself (or by its × button), stacked bottom-right — inside the open
 *  modal dialog when there is one (the top layer would cover it otherwise). Its text is a live
 *  region: polite, or assertive for a 'danger' toast. A tone colours it (ok · warn · danger ·
 *  purple · accent; none = neutral). The controller updates the text (a progress) or closes it.
 *  `id`: one toast per id — a toast whose id is still shown is UPDATED (text, tone, its time
 *  restarted), never stacked again (« Saved » after every keystroke, « Connection lost » every retry).
 *  @param {string} msg  plain text
 *  @param {{tone?: string, duration?: number, id?: string}} [opts]  duration in ms (default 3000); 0 = until closed
 *  @returns {{ element: Element, update: (msg: string) => void, close: () => void }}
 *  @example toast('Saved', { tone: 'ok', id: 'save' })
 *  @example const t = toast('Packing…', { duration: 0 }); … t.update('Packing 3 / 10'); … t.close()
 */
const _toasts = new Map();                                             // id → set() of the toast shown under it
/** @private — the stack of toasts in `host` (the page, or the open modal dialog), made on first use. */
const _stack = (host) => host.querySelector(':scope > .qry-toasts') ?? $.create('div', { class: 'qry-toasts' }).mount(host);
export const toast = (msg, { tone = '', duration = 3000, id } = {}) => {
    if (id != null && _toasts.has(id)) return _toasts.get(id)(msg, tone, duration);
    let host = document.body;
    try { host = [...document.querySelectorAll('dialog:modal')].at(-1) ?? host; } catch { /* no :modal here: the page */ }
    const text = $.create('span', { class: 'qry-toast-text' });
    const t = $.create('div', {}, text, $.create('button', { type: 'button', class: 'qry-toast-close', 'aria-label': labels.dismiss, text: '×' }));
    let timer = 0, closed = false, current = msg;
    const close = () => {
        if (closed) return;
        closed = true; clearTimeout(timer); _toasts.delete(id);
        t.cls('+is-leaving'); setTimeout(() => t.remove(), $.reduced ? 0 : $.motion());
    };
    const controller = { element: t, update: (m) => { current = m; if (text.textContent) text.text(m); }, close };
    /** text, tone and time — at creation, and again for a toast shown under the same id */
    const set = (m, tn, d) => {
        t.attr('class', `qry-toast ${tn}`.trim()); text.attr('role', tn === 'danger' ? 'alert' : 'status');
        controller.update(m);                                            // before the first frame: the text it fills (below)
        clearTimeout(timer); if (d) timer = setTimeout(close, d);
        return controller;
    };
    t.on('click', close);
    _stack(host).add(t);
    requestAnimationFrame(() => text.text(current));                     // filled once in the live region: announced
    if (host !== document.body) host.on('close', () => { if (!closed) _stack(document.body).add(t); }, { once: true });   // the dialog closes: the toast outlives it
    if (id != null) _toasts.set(id, set);
    return set(msg, tone, duration);
};

/** A message to acknowledge (an alertdialog).  @example await alert('Export finished.') */
export const alert = (message, { title = '', ok = labels.ok, class: cls } = {}) =>
    ask({ title, body: message, role: 'alertdialog', class: cls, buttons: [{ label: ok, tone: 'primary' }] });

/** Yes or no → true / false (Escape and the backdrop say no). `danger` paints OK red and
 *  puts the focus on Cancel, so a stray Enter never deletes.
 *  @example if (await confirm('Delete this take?', { ok: 'Delete', danger: true })) remove()
 */
export const confirm = (message, { title = '', ok = labels.ok, cancel = labels.cancel, danger = false, class: cls } = {}) =>
    ask({ title, body: message, dismiss: false, role: 'alertdialog', class: cls,
        buttons: [{ label: cancel, value: false, focus: danger }, { label: ok, tone: danger ? 'danger' : 'primary', value: true }] });

/** A line of text → the string, or null when cancelled. Enter validates. The answer is trimmed
 *  (a stray space is never part of a name or an answer); `trim: false` keeps it exactly as typed.
 *  @example const name = await prompt('Session name?', { value: 'take-1' })
 */
export const prompt = (message, { title = '', value = '', placeholder = '', ok = labels.ok, cancel = labels.cancel, trim = true, class: cls } = {}) => {
    const input = $.create('input', { class: 'qry-input', type: 'text', autocomplete: 'off', autofocus: true, value, placeholder });
    const body = $.create('label', { class: 'qry-field' }, $.create('span', { text: message }), input);
    const p = ask({ title, body, dismiss: null, class: cls, buttons: [{ label: cancel, value: null }, { label: ok, tone: 'primary', value: () => (trim ? input.value.trim() : input.value) }] });
    input.select();
    input.on('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); input.closest('dialog').querySelector('.qry-dialog-actions > :last-child').click(); } });
    return p;
};

// ════════════════════════════════════════════════════════════════════════════
// THEME — light / dark / system, boot
// ════════════════════════════════════════════════════════════════════════════


/** Light / dark / system, through ONE hook: `data-theme` on <html> (qry-ui.css turns it
 *  into the native color-scheme; without it, the OS decides). `mode()` reads the choice, `mode(m)`
 *  makes it; it is persisted (as JSON, under 'qry:theme' or init's `persist` key); 'system' removes
 *  the attribute and follows the OS live. Every change — mode(m), or the OS while on 'system' —
 *  puts EVERY `<meta name="theme-color">` (media-scoped ones included) on the page ground
 *  (`ground`, default --qry-bg) — never throwing — and fires `qry:theme` on <html> with { mode, dark }.
 *  `isDark()` is reactive: read in an effect or a binding, it follows every change
 *  (`btn.attr('aria-pressed', theme.isDark)`, `icon.attr('data-icon', () => theme.isDark() ? 'sun' : 'moon')`).
 *  A closure: its functions can be handed around (`btn.on('click', theme.toggle)`).
 *  No flash before the module runs: qry.js itself puts the saved choice on <html> as it loads — load it
 *  in <head>, after the stylesheet. An app's own key is written ONCE, on that script tag
 *  (`<script src="qry.js" data-theme-persist="floop:theme">`): init's default `persist` reads it.
 *  @example theme.init(); theme.toggle(); theme.mode('system'); theme.mode() → 'system'; theme.isDark()
 *  @example theme.init({ persist: 'floop:theme', ground: '--floop-paper' })   // the app's own key and ground
 */
/** @private — what qry.js's own script tag says of the theme (its data-theme-* attributes). */
const _themeTag = () => document.querySelector('script[data-theme-persist], script[data-theme-initial]')?.dataset ?? {};
export const theme = (() => {
    let key = 'qry:theme', groundToken = '--qry-bg', watching = false;
    const bar = () => {                      // every theme-color meta on the page ground; never throws
        try {
            const metas = document.querySelectorAll('meta[name="theme-color"]');
            if (!metas.length) return;
            const c = $.color(groundToken, document.body ?? document.documentElement);
            if (c) for (const m of metas) m.content = c;
        } catch { /* no colour to read here (a test sandbox): the metas stay as they are */ }
    };
    let changes = null, initial = 'system';  // changes: a signal, made on first use (importing stays inert), bumped by every change
    const os = () => $.media('(prefers-color-scheme: dark)');
    const changed = () => { if (changes) changes.value += 1; };
    const dark = () => { const t = document.documentElement.dataset.theme; return t ? t === 'dark' : os().peek(); };
    const isDark = () => { (changes ??= $.signal(0)).value; return dark(); };   // read in an effect (an icon, a chart): it follows
    /** @param {'light'|'dark'|'system'} [m]  without it: the saved choice (init's `initial` when none: 'system') */
    const mode = (...m) => {
        if (!m.length) return $.store().get(key, initial);
        const html = document.documentElement;
        html.attr('data-theme', m[0] === 'system' ? null : m[0]);
        $.store().set(key, m[0]);
        bar();
        changed();
        html.trigger('qry:theme', { mode: m[0], dark: dark() });   // untracked: mode() in an effect never follows the theme
        return m[0];
    };
    return {
        mode, isDark,
        toggle: () => mode(dark() ? 'light' : 'dark'),
        /** Apply the saved choice, and follow the OS while on 'system'. The key and the first-visit mode
         *  default to what qry.js's script tag says (`data-theme-persist`, `data-theme-initial`): written once.
         *  @param {{persist?: string, initial?: 'light'|'dark'|'system', ground?: string}} [opts]  persist: the
         *  localStorage key of the choice (else 'qry:theme'); initial: the mode before any choice (else 'system');
         *  ground: the token (or CSS colour) of the page ground the browser bar takes (default '--qry-bg' — an app
         *  not on qry-ui.css names its own) */
        init({ persist = _themeTag().themePersist || 'qry:theme', initial: first = _themeTag().themeInitial || 'system', ground = '--qry-bg' } = {}) {
            key = persist; initial = first; groundToken = ground;
            if (!watching) { watching = true; $.watch(os(), () => { if (mode() === 'system') mode('system'); }); }
            return mode(mode());
        },
    };
})();

/** One-call convenience for the common opening sequence:
 *  set the title → apply the saved theme → draw the icons (`icons.watch()`) → run `ready` on DOM-ready.
 *  Every step is also available standalone — use this only if it saves typing.
 *  @param {Object}   [opts]
 *  @param {Function} [opts.ready]        Run once the DOM is ready
 *  @param {boolean|{persist?: string, ground?: string}} [opts.theme=true]   Apply persisted/system theme (an object: theme.init's options)
 *  @param {boolean}  [opts.icons=true]   Draw every <i data-icon>, now and as they come (icons.watch)
 *  @param {string}   [opts.title]        Sets document.title if given
 *  @example boot({ title: 'Gallery', ready: () => wire() })
 */
export const boot = ({ ready, theme: useTheme = true, icons: useIcons = true, title } = {}) => {
    if (title) document.title = title;
    if (useTheme) theme.init(useTheme === true ? {} : useTheme);
    if (useIcons) icons.watch();                                   // every <i data-icon> drawn, now and later
    if (ready) $.ready(ready);
};

// ════════════════════════════════════════════════════════════════════════════
// FILES — clipboard, files in and out, zip
// ════════════════════════════════════════════════════════════════════════════

/** Copy text to the clipboard. Shows a toast unless `notify` is false — `message` (default
 *  labels.copied) says what was copied.
 *  @returns {Promise<boolean>} whether the copy succeeded
 *  @example await copy(report.url, { message: 'Link copied' })
 */
export const copy = async (text, { notify = true, message } = {}) => {
    try {
        await navigator.clipboard.writeText(text);
        if (notify) toast(message ?? labels.copied, { tone: 'ok', duration: 1500 });
        return true;
    } catch {
        if (notify) toast(labels.copyFailed, { tone: 'danger' });
        return false;
    }
};

/** Save data as a file. A Blob goes as is; a string or bytes get `type`; anything else (an
 *  object, an array) is saved as pretty JSON.
 *  @param {Blob|string|BufferSource|Object} data
 *  @param {string} filename
 *  @param {{type?: string}} [opts]  MIME type for a string or bytes (default text/plain)
 *  @example download(rows, 'data.json')
 *  @example download(pdfBytes, 'letter.pdf', { type: 'application/pdf' })
 */
export const download = (data, filename, { type = 'text/plain' } = {}) => {
    const blob = _blob(data, type);
    const url = URL.createObjectURL(blob);
    const a = $.create('a', { href: url, download: filename }).mount(document.body);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);   // an immediate revoke can abort the download
};

/** @private — data as a Blob: a Blob as is, a string or bytes with `type`, anything else as pretty JSON. */
const _blob = (data, type = 'text/plain') => (data instanceof Blob ? data
    : typeof data === 'string' || ArrayBuffer.isView(data) || data instanceof ArrayBuffer ? new Blob([data], { type })
    : new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }));

/** @private — an `accept` list ('.pdf,.epub', 'image/*') as the pickers' `types`. */
const _types = (accept) => {
    const exts = String(accept ?? '').split(',').map((a) => a.trim()).filter((a) => a.startsWith('.'));
    return exts.length ? [{ description: exts.map((e) => e.slice(1).toUpperCase()).join(', '), accept: { 'application/octet-stream': exts } }] : undefined;
};

/** @private — an AbortError sooner than this (ms) is a dialog that was never shown, not a cancel. */
const _NOT_SHOWN = 150;

/** Save a file WHERE THE USER CHOOSES — the system's own save dialog (File System Access API,
 *  Chromium), else a download (Firefox / Safari, or a page where the dialog is refused). Returns
 *  `{ how: 'native' | 'download' | 'fallback', name, handle? }`, or null when the user cancels (a
 *  "cancel" within 150 ms is a dialog the browser never showed — headless, kiosk policy: the other way).
 *  - `data`: a Blob, a string, bytes, an object (saved as JSON) — or a FUNCTION returning one (even
 *    async): the dialog then opens FIRST, while the click's permission to open it is still valid,
 *    and the data is made only once a place is chosen (a conversion, a PDF being built).
 *  - `handle`: a file handle from an earlier saveFile / openFile — written AGAIN, no dialog (an
 *    editor's Save); the browser asks the user once for write permission.
 *  - `startIn`: a handle (its folder) or 'desktop' | 'documents' | 'downloads'…; `id`: a name under
 *    which the browser remembers the last folder; the file types come from the name's extension.
 *  - `fallback(blob, name)`: where there is no dialog, instead of a download (an app's own way —
 *    qry-host.js's `saveVia()`, a folder browser served by QryHost.java); what it returns joins the
 *    result (`{ path }`), and null / false means the user cancelled there.
 *  @example const r = await saveFile(() => buildPdf(), 'report.pdf', { startIn: 'desktop' })
 *  @example await saveFile(bytes, doc.name, { handle: doc.handle })          // Save, in place */
export const saveFile = async (data, name, { type = 'application/octet-stream', handle, startIn, id, fallback } = {}) => {
    const make = async () => _blob(typeof data === 'function' ? await data() : data, type);
    // the data FIRST, then the writer: a build that fails (or that the app cancels by throwing) leaves the file untouched
    const write = async (h) => { const blob = await make(); const w = await h.createWritable(); await w.write(blob); await w.close(); return { how: 'native', name: h.name, handle: h }; };
    if (handle) {
        if ((await handle.requestPermission?.({ mode: 'readwrite' })) !== 'denied') return write(handle);
    } else if (window.showSaveFilePicker) {
        const ext = name.includes('.') ? name.slice(name.lastIndexOf('.')) : '';   // 'a.ocd.epub' → '.epub': one extension per type
        const asked = performance.now();
        let picked = null;
        try {
            picked = await window.showSaveFilePicker({ suggestedName: name, types: _types(ext), ...(startIn && { startIn }), ...(id && { id }) });
        } catch (e) {
            // the user cancelled — unless the "cancel" came back faster than any hand: the dialog was
            // never SHOWN (a headless or locked-down browser answers AbortError at once) → the other way
            if (e?.name === 'AbortError' && performance.now() - asked > _NOT_SHOWN) return null;
        }                                                     // refused here: the other way
        if (picked) return write(picked);                     // a file was chosen: a build or write that fails rejects — never a second build, never a download
    }
    const blob = await make();
    if (fallback) {                                           // the app's own way: null / false = the user cancelled there
        const out = await fallback(blob, name);
        return out === null || out === false ? null : { how: 'fallback', name, ...(out && typeof out === 'object' ? out : {}) };
    }
    download(blob, name);
    return { how: 'download', name };
};

/** Open files the user picks — the system's own dialog, keeping a HANDLE per file (save back to it
 *  with saveFile's `handle`); else a plain <input type=file>. Returns `[{ file, handle }]` (handle
 *  null without the dialog), or null when the user cancels.
 *  @param {{accept?: string, multiple?: boolean, startIn?: any, id?: string}} [opts]  accept as in <input accept>: '.pdf,.epub'
 *  @example const [doc] = await openFile({ accept: '.ocd.epub,.epub' }) ?? [] */
export const openFile = async ({ accept = '', multiple = false, startIn, id } = {}) => {
    if (window.showOpenFilePicker) {
        try {
            const handles = await window.showOpenFilePicker({ multiple, types: _types(accept), ...(startIn && { startIn }), ...(id && { id }) });
            return Promise.all(handles.map(async (h) => ({ file: await h.getFile(), handle: h })));
        } catch (e) {
            if (e?.name === 'AbortError') return null;
        }
    }
    return new Promise((resolve) => {                         // the plain input: change, or cancel
        const input = $.create('input', { type: 'file', accept, multiple, hidden: true }).mount(document.body);
        const done = (v) => { input.remove(); resolve(v); };
        input.on('change', () => done(input.files.length ? [...input.files].map((file) => ({ file, handle: null })) : null));
        input.on('cancel', () => done(null));
        input.click();
    });
};

/** @private — does a file pass an `accept` list, as in <input accept>: '.json,.zip',
 *  'image/*', 'text/csv'… ('' or '*' takes everything). */
const _accepts = (file, accept) => !accept || accept === '*' || accept.split(',').some((a) => {
    const t = a.trim().toLowerCase(), name = file.name.toLowerCase(), mime = (file.type || '').toLowerCase();
    return t.startsWith('.') ? name.endsWith(t) : t.endsWith('/*') ? mime.startsWith(t.slice(0, -1)) : mime === t;
});

/** @private — text → data by extension: JSON parsed, CSV / TSV through $.parseCSV. */
const _parsers = {
    json: (t) => JSON.parse(t),
    csv: (t) => $.parseCSV(t),
    tsv: (t) => $.parseCSV(t, { sep: '\t' }),
};

/** @private — a zip archive (bytes) → read(name) → its bytes, or null. The central directory +
 *  the browser's own inflate (DecompressionStream 'deflate-raw'): stored and deflated entries,
 *  no zip64, no encryption — what Office files and ordinary archives are. */
const _unzip = (u8) => {
    const dv = new DataView(u8.buffer, u8.byteOffset, u8.byteLength), utf8 = new TextDecoder();
    let end = -1;
    for (let i = u8.length - 22; i >= Math.max(0, u8.length - 65557); i--) if (dv.getUint32(i, true) === 0x06054b50) { end = i; break; }
    if (end < 0) throw new Error('not a zip archive');
    const entries = new Map();
    for (let k = 0, p = dv.getUint32(end + 16, true), n = dv.getUint16(end + 10, true); k < n; k++) {
        if (dv.getUint32(p, true) !== 0x02014b50) throw new Error('damaged zip directory');
        const len = dv.getUint16(p + 28, true);
        entries.set(utf8.decode(u8.subarray(p + 46, p + 46 + len)),
            { method: dv.getUint16(p + 10, true), crc: dv.getUint32(p + 16, true), size: dv.getUint32(p + 20, true), usize: dv.getUint32(p + 24, true), offset: dv.getUint32(p + 42, true) });
        p += 46 + len + dv.getUint16(p + 30, true) + dv.getUint16(p + 32, true);
    }
    const read = async (name) => {
        const e = entries.get(name);
        if (!e) return null;
        if (e.size === 0xFFFFFFFF) throw new Error('zip64 archives are not supported');
        const start = e.offset + 30 + dv.getUint16(e.offset + 26, true) + dv.getUint16(e.offset + 28, true);
        if (start + e.size > u8.length) throw new Error(`damaged zip entry (${name})`);
        const raw = u8.subarray(start, start + e.size);
        if (e.method !== 0 && e.method !== 8) throw new Error(`unsupported zip compression (${e.method})`);
        let n = 0;                                                       // never more than it declares: a zip bomb stops at its own word
        const cap = new TransformStream({ transform(c, ctl) { if ((n += c.length) > e.usize) ctl.error(new Error(`zip entry larger than declared (${name})`)); else ctl.enqueue(c); } });
        const out = e.method === 0 ? raw : new Uint8Array(await new Response(new Blob([raw]).stream().pipeThrough(new DecompressionStream('deflate-raw')).pipeThrough(cap)).arrayBuffer());
        if (out.length !== e.usize || _crc32(out) !== e.crc) throw new Error(`damaged zip entry (${name})`);
        return out;
    };
    return { names: [...entries.keys()], read };
};

/** @private — an .xlsx (bytes) → its FIRST worksheet as TSV text, one line per sheet row (empty
 *  rows kept, so line n is row n), cells quoted when they hold a tab, a newline or a quote.
 *  Values as Excel stores them: numbers and dates as numbers (a date is a serial day). */
const _xlsx = async (u8) => {
    const { read } = _unzip(u8), utf8 = new TextDecoder();
    const doc = async (name) => {
        const b = await read(name); if (!b) return null;
        const d = new DOMParser().parseFromString(utf8.decode(b), 'application/xml');
        if (d.getElementsByTagName('parsererror').length) throw new Error(`damaged workbook (${name})`);   // DOMParser never throws: it would cut the sheet
        return d;
    };
    const tags = (node, name) => [...node.getElementsByTagNameNS('*', name)];
    const book = await doc('xl/workbook.xml');
    const first = book && tags(book, 'sheet')[0];
    if (!first) throw new Error('not an Excel workbook');
    const rid = first.getAttributeNS('http://schemas.openxmlformats.org/officeDocument/2006/relationships', 'id');
    const rels = await doc('xl/_rels/workbook.xml.rels');
    const target = rels && tags(rels, 'Relationship').find((r) => r.getAttribute('Id') === rid)?.getAttribute('Target');
    const sheet = await doc(!target ? 'xl/worksheets/sheet1.xml' : target.startsWith('/') ? target.slice(1) : `xl/${target}`);
    if (!sheet) throw new Error('not an Excel workbook');
    const sst = await doc('xl/sharedStrings.xml');
    const shared = sst ? tags(sst, 'si').map((si) => tags(si, 't').filter((t) => !t.closest('rPh')).map((t) => t.textContent).join('')) : [];
    const col = (ref) => [...ref.replace(/\d+/g, '')].reduce((n, ch) => n * 26 + ch.charCodeAt(0) - 64, 0) - 1;
    const cell = (t) => (/[\t\n\r"]/.test(t) ? `"${t.replace(/"/g, '""')}"` : t);
    const lines = [];
    for (const row of tags(sheet, 'row')) {
        const cells = [];
        for (const c of tags(row, 'c')) {
            const type = c.getAttribute('t'), v = tags(c, 'v')[0]?.textContent ?? '', at = c.getAttribute('r');
            const k = at ? col(at) : cells.length;                       // `r` is optional: the next cell
            if (!(k >= 0 && k < 16384)) throw new Error('not an Excel workbook (a cell out of range)');   // Excel's own limits: no array sized by a file
            cells[k] = type === 's' ? shared[+v] ?? ''
                : type === 'inlineStr' ? tags(c, 't').map((t) => t.textContent).join('')
                : type === 'b' ? (v === '1' ? 'TRUE' : 'FALSE') : v;
        }
        const r = +row.getAttribute('r') || lines.length + 1;
        if (!(r >= 1 && r <= 1048576)) throw new Error('not an Excel workbook (a row out of range)');
        lines[r - 1] = Array.from(cells, (t) => cell(t ?? '')).join('\t');
    }
    return Array.from(lines, (l) => l ?? '').join('\n');
};

/** Read dropped / picked files: `.json`, `.csv` and `.tsv` are parsed, other text kept as
 *  text. An `.xlsx` is read natively (no library): its first sheet becomes TSV `text`, one line
 *  per row, and `data` as for a CSV (objects keyed by the first row). A `.zip` is opened natively
 *  too, and its entries read through `accept`. A file outside `accept` (labels.refused) and a file
 *  that fails are reported through `onError` — the batch goes on. (Inside a zip, entries outside
 *  `accept` are skipped silently: an archive carries what it carries.) Nothing is ever fetched.
 *  @param {FileList|File[]} files
 *  @param {Object}   [opts]
 *  @param {string}   [opts.accept='.json,.csv,.tsv,.txt,.xlsx']  which files (and zip entries) to read
 *  @param {boolean}  [opts.parse=true]   false → `{ name, text }` only
 *  @param {Function} [opts.onError]      (name, error) → void; default console.warn
 *  @returns {Promise<Array<{name: string, text: string, data?: any}>>}
 *  @example makeDropZone(document.body, { onFiles: async (f) => (await readFiles(f)).forEach(({ name, data }) => load(name, data)) })
 *  @example readFiles(files, { accept: '.xlsx,.csv', onError: (name, e) => toast(`${name}: ${e.message}`, { tone: 'danger' }) })
 */
export const readFiles = async (files, { accept = '.json,.csv,.tsv,.txt,.xlsx', parse = true, onError } = {}) => {
    const warn = onError || ((name, err) => console.warn(`readFiles: ${name} — ${err.message}`));
    const utf8 = new TextDecoder(), out = [];
    const ext = (name) => name.split('.').pop().toLowerCase();
    const take = async (name, get) => {
        try {
            const bytes = await get(), xlsx = ext(name) === 'xlsx';
            const raw = xlsx ? await _xlsx(bytes) : utf8.decode(bytes);
            const text = raw.charCodeAt(0) === 0xFEFF ? raw.slice(1) : raw;   // no BOM
            const p = parse && _parsers[xlsx ? 'tsv' : ext(name)];
            out.push(p ? { name, text, data: p(text) } : { name, text });
        } catch (err) { warn(name, err); }
    };
    for (const file of files) {
        try {
            const bytes = () => file.arrayBuffer().then((b) => new Uint8Array(b));
            if (ext(file.name) === 'zip') {
                const zip = _unzip(await bytes());
                for (const name of zip.names) if (!name.endsWith('/') && _accepts({ name }, accept)) await take(name, () => zip.read(name));
            } else if (_accepts(file, accept)) {
                await take(file.name, bytes);
            } else {
                warn(file.name, new Error(labels.refused));
            }
        } catch (err) {
            warn(file.name, err);
        }
    }
    return out;
};

/** @private — CRC-32 (zip's checksum), its table made once. */
let _crcTable = null;
const _crc32 = (u8) => {
    _crcTable ??= Uint32Array.from({ length: 256 }, (_, n) => { for (let k = 0; k < 8; k++) n = n & 1 ? 0xEDB88320 ^ (n >>> 1) : n >>> 1; return n >>> 0; });
    let c = 0xFFFFFFFF;
    for (const b of u8) c = _crcTable[(c ^ b) & 0xFF] ^ (c >>> 8);
    return (c ^ 0xFFFFFFFF) >>> 0;
};

/** Files → ONE .zip, written natively: the browser's own deflate (CompressionStream
 *  'deflate-raw'), no library. Each entry is `{ name, data }` — data a Blob, a string, bytes or an
 *  object (saved as JSON, as `download` does); a name may hold folders ('session/take-1.csv').
 *  `compress: false` stores them (already compressed: images, video). Pairs with readFiles, which
 *  opens zips the same way. No zip64: under 4 GB, under 65 535 entries.
 *  @param {Array<{name: string, data: Blob|string|BufferSource|Object}>} files
 *  @param {{compress?: boolean}} [opts]
 *  @returns {Promise<Blob>}  application/zip
 *  @example download(await zip([{ name: 'take.json', data: take }, { name: 'take.csv', data: csv }]), 'take.zip')
 */
export const zip = async (files, { compress = true } = {}) => {
    files = [...files];
    if (files.length > 0xFFFF) throw new RangeError('zip — more than 65 535 entries (no zip64)');
    const enc = new TextEncoder(), parts = [], dir = [];
    let offset = 0;
    const now = new Date();
    const time = (now.getHours() << 11) | (now.getMinutes() << 5) | (now.getSeconds() >> 1);
    const day = ((now.getFullYear() - 1980) << 9) | ((now.getMonth() + 1) << 5) | now.getDate();
    for (const { name, data } of files) {
        const raw = new Uint8Array(await _blob(data).arrayBuffer());
        const packed = compress ? new Uint8Array(await new Response(new Blob([raw]).stream().pipeThrough(new CompressionStream('deflate-raw'))).arrayBuffer()) : raw;
        const deflated = compress && packed.length < raw.length, body = deflated ? packed : raw;
        const n = enc.encode(name), crc = _crc32(raw);
        const head = (central) => {
            const h = new DataView(new ArrayBuffer(central ? 46 : 30));
            const fields = central
                ? [[0, 0x02014b50, 4], [4, 20, 2], [6, 20, 2], [8, 0x0800, 2], [10, deflated ? 8 : 0, 2], [12, time, 2], [14, day, 2], [16, crc, 4], [20, body.length, 4], [24, raw.length, 4], [28, n.length, 2], [42, offset, 4]]
                : [[0, 0x04034b50, 4], [4, 20, 2], [6, 0x0800, 2], [8, deflated ? 8 : 0, 2], [10, time, 2], [12, day, 2], [14, crc, 4], [18, body.length, 4], [22, raw.length, 4], [26, n.length, 2]];
            for (const [at, v, size] of fields) if (size === 4) h.setUint32(at, v, true); else h.setUint16(at, v, true);
            return new Uint8Array(h.buffer);
        };
        dir.push(head(true), n);
        parts.push(head(false), n, body);
        offset += 30 + n.length + body.length;
        if (offset > 0xFFFFFFFF) throw new RangeError('zip — over 4 GB (no zip64)');
    }
    const size = dir.reduce((t, x) => t + x.length, 0), end = new DataView(new ArrayBuffer(22));
    end.setUint32(0, 0x06054b50, true); end.setUint16(8, files.length, true); end.setUint16(10, files.length, true);
    end.setUint32(12, size, true); end.setUint32(16, offset, true);
    return new Blob([...parts, ...dir, new Uint8Array(end.buffer)], { type: 'application/zip' });
};

// ════════════════════════════════════════════════════════════════════════════
// LAYOUT — tabs, menus, splitter, sidebar, header, zoom-pan
// ════════════════════════════════════════════════════════════════════════════

/** Wire a tab bar: clicking a tab activates it and reveals the matching panel(s) — the
 *  ARIA tablist pattern (roles, aria-selected, roving tabindex, ←/→/Home/End). The state IS
 *  the ARIA: a tab is selected by aria-selected="true", a panel is shown by not being
 *  `hidden`. A panel may serve several tabs: list their names space-separated in its
 *  attribute. The tab marked aria-selected="true" in the markup is the start (else the first);
 *  give the other panels `hidden` in the markup too, so nothing flashes before the script.
 *  `selected()` is the current tab's name, `selected(name)` selects one — a no-op (no
 *  `onChange`) for the current one unless `{ force: true }`.
 *  @param {string|Element[]} [tabs='.qry-tab']  the tabs (a selector, or the elements)
 *  @param {Object} [opts]
 *  @param {string|Element[]} [opts.panels='.qry-panel']  (`.qry-panel` is a name, not a style: no rule)
 *  @param {string} [opts.attribute='data-tab']  its value links a tab to its panel(s)
 *  @param {Function} [opts.onChange]  (name:string) → void  (only on an actual change)
 *  @returns {{ selected:(name?:string, opts?:{force?:boolean})=>string|null, tabs:Element[], destroy:Function }}
 *  @example const tabs = makeTabs('.qry-tab', { onChange: n => { if (n === 'page') render(); } })
 */
export const makeTabs = (tabsOpt = '.qry-tab', { panels: panelsOpt = '.qry-panel', attribute: attr = 'data-tab', onChange } = {}) => {
    const tabs = $.all(tabsOpt), panels = $.all(panelsOpt);
    const { on, off } = _scope();
    let cur = null;
    const select = (name, { force = false } = {}) => {
        if (name == null || (name === cur && !force)) return;
        cur = name;
        for (const t of tabs) {
            const sel = t.attr(attr) === name;
            t.attr({ 'aria-selected': sel, tabindex: sel ? '0' : '-1' });
        }
        for (const p of panels) p.hidden = !(p.attr(attr) || '').split(' ').includes(name);
        onChange?.(name);
    };
    tabs[0]?.parentElement.attr('role', 'tablist');
    panels.forEach((p) => p.attr('role', 'tabpanel'));
    for (const t of tabs) {
        t.attr('role', 'tab').on('click', () => select(t.attr(attr)), on);
        t.on('keydown', (e) => {
            const i = tabs.indexOf(t), n = tabs.length;
            const j = { ArrowRight: i + 1, ArrowDown: i + 1, ArrowLeft: i - 1 + n, ArrowUp: i - 1 + n, Home: 0, End: n - 1 }[$.key(e)];
            if (j === undefined) return;
            e.preventDefault();
            tabs[j % n].focus();
            select(tabs[j % n].attr(attr));
        }, on);
    }
    const start = tabs.find((t) => t.attr('aria-selected') === 'true') || tabs[0];
    if (start) select(start.attr(attr), { force: true });
    return { selected: (...a) => { if (a.length) select(...a); return cur; }, tabs, destroy: off };
};

/** Make a drag handle resize a pane by writing a px length into a CSS variable on its
 *  container. Pairs with the qry-ui.css `.qry-split` handle and the `.qry-workspace` shell
 *  (its start aside reads `--qry-aside-w`, its end aside `--qry-aside-end-w`), but works on any
 *  container / variable. `from: 'end'` sizes a pane measured from the container's right (or
 *  bottom) edge — the end aside, an inspector — and turns the keys round to match.
 *  Live updates flow through `onResize` (every move); commit-once work belongs in `onEnd`.
 *  The handle is a focusable separator: ←/→ (↑/↓ on the y axis) move it by 16 px. While it is
 *  dragged, the handle is `is-dragging` and its container `is-resizing` (qry-ui.css stops text
 *  selection and the panes' transitions there; a page may calm a heavy view too).
 *  Double-click resets to `initial` (or clears the variable). `size()` reads the size (px, or null
 *  before any), `size(px)` sets it. `persist: 'key'` keeps the size the user left across visits
 *  ($.store; the saved one wins over `initial`).
 *  @param {string|Element} handle  the splitter element
 *  @param {Object}  [opts]
 *  @param {string|Element} [opts.container]  where the var is written (default: nearest `.qry-workspace`, else the handle's parent)
 *  @param {string}  [opts.prop]  CSS custom property to drive (default `--qry-aside-w`, or `--qry-aside-end-w` from the end)
 *  @param {'x'|'y'} [opts.axis='x']  x → a width, y → a height
 *  @param {'start'|'end'} [opts.from='start']  measured from the container's left / top, or from its right / bottom
 *  @param {number}  [opts.min=150]
 *  @param {number|((boxSize:number)=>number)} [opts.max]  default 60% of the container
 *  @param {number}  [opts.initial]  size (px) to apply now, and to reset to
 *  @param {string}  [opts.persist]  a storage key: the size is saved at the end of each move and applied at start
 *  @param {Function}[opts.onStart] @param {Function}[opts.onResize] @param {Function}[opts.onEnd]  (px:number) → void
 *  @returns {{ size:(px?:number)=>number|null, reset:()=>void, destroy:Function }}
 *  @example makeSplitter('#split', { min: 150, max: 560, onEnd: () => scheduleRender() })
 *  @example makeSplitter('#split-end', { from: 'end', min: 200 })   // the end aside (an inspector)
 */
export const makeSplitter = (handle, {
    container, from = 'start', prop = from === 'end' ? '--qry-aside-end-w' : '--qry-aside-w', axis = 'x', min = 150, max, initial, persist,
    onStart, onResize, onEnd: userEnd,
} = {}) => {
    const store = persist ? $.store() : null;
    const onEnd = (v) => { if (store) { if (v == null) store.remove(persist); else store.set(persist, Math.round(v)); } userEnd?.(v); };
    const el = $(handle), y = axis === 'y', sign = from === 'end' ? -1 : 1;
    const box = container ? $(container) : (el.closest('.qry-workspace') || el.parentElement);
    const { on, off } = _scope();
    const span = () => box.getBoundingClientRect()[y ? 'height' : 'width'];
    const hi = () => (max == null ? span() * 0.6 : typeof max === 'function' ? max(span()) : max);
    const current = () => { const v = parseFloat(box.css(prop)); return Number.isFinite(v) ? v : null; };
    const aria = (v) => el.attr({ 'aria-valuenow': v == null ? null : Math.round(v), 'aria-valuemax': Math.round(hi()) });
    const write = (px) => {
        const v = span() ? $.clamp(px, min, hi()) : Math.max(px, min);   // a hidden box (a tab not shown): no max to clamp to yet
        box.css(prop, v + 'px');
        aria(v);
        onResize?.(v);
        return v;
    };
    const at = (e) => {                                  // the pointer's distance from the measured edge
        const r = box.getBoundingClientRect(), p = y ? e.clientY : e.clientX;
        const fromLow = (sign > 0) !== (!y && getComputedStyle(box).direction === 'rtl');   // the start edge is the right one in RTL
        return fromLow ? p - r[y ? 'top' : 'left'] : r[y ? 'bottom' : 'right'] - p;
    };
    el.attr({ role: 'separator', tabindex: '0', 'aria-orientation': y ? 'horizontal' : 'vertical', 'aria-valuemin': min });
    el.onDrag(({ phase, event }) => {
        if (phase === 'start') { el.cls('+is-dragging'); box.cls('+is-resizing'); onStart?.(current() ?? at(event)); }   // the container too: an iframe under the pointer, a transition to pause
        else if (phase === 'move') write(at(event));
        else { el.cls('-is-dragging'); box.cls('-is-resizing'); onEnd(current()); }
    }, on);
    el.on('keydown', (e) => {
        const d = { ArrowLeft: -16, ArrowRight: 16, ArrowUp: -16, ArrowDown: 16 }[$.key(e)];
        if (!d || (y !== (e.key === 'ArrowUp' || e.key === 'ArrowDown'))) return;
        e.preventDefault();
        onEnd(write((current() ?? span() / 2) + d * sign));
    }, on);
    const reset = () => {
        if (initial != null) write(initial); else { box.css(prop, null); aria(current()); onResize?.(current()); }
        store?.remove(persist);                          // back to the default: nothing kept
        userEnd?.(current());
    };
    el.on('dblclick', reset, on);
    const saved = store?.get(persist, null);
    if (Number.isFinite(saved)) write(saved); else if (initial != null) write(initial); else aria(current());
    return { size: (...px) => { if (!px.length) return current(); const v = write(px[0]); onEnd(v); return v; }, reset, destroy: off };
};

/** Wire the qry-ui.css sidebar: slide-in on small screens (≤ 768 px, as in qry-ui.css),
 *  collapse to icons on large ones. Markup: `<aside class="qry-sidebar mobile">` (the
 *  `mobile` class enables the slide-in), a backdrop `<div id="qry-overlay" class="qry-overlay">`,
 *  and a toggle button. The button's aria-expanded follows (also across the breakpoint);
 *  Escape and the overlay close the mobile menu. `onChange(expanded)` hears every change (an
 *  icon to swap, a layout to redraw) — expanded: the drawer open on a phone, the bar not
 *  collapsed on a large screen.
 *  @param {string|Element} sidebar
 *  @param {Object} [opts] @param {string} [opts.button='#qry-collapse'] @param {string} [opts.overlay='#qry-overlay']
 *  @param {(expanded: boolean) => void} [opts.onChange]
 *  @returns {{ open, close, toggle, isExpanded: () => boolean, destroy }}
 *  @example const nav = makeSidebar('.qry-sidebar'); nav.toggle()
 */
export const makeSidebar = (sidebar, { button = '#qry-collapse', overlay = '#qry-overlay', onChange } = {}) => {
    const el = $(sidebar), ov = $.opt(overlay), btn = $.opt(button), small = $.media('(max-width: 768px)');
    const { on, off } = _scope();
    const isExpanded = () => (small.peek() ? el.cls('?is-open') : !el.cls('?is-collapsed'));
    let last = null;
    const expanded = () => {
        const v = isExpanded();
        btn?.attr('aria-expanded', v);
        if (v !== last) { const first = last === null; last = v; if (!first) onChange?.(v); }
    };
    const open = () => { el.cls('+is-open'); ov?.cls('+is-visible'); expanded(); };
    const close = () => { el.cls('-is-open'); ov?.cls('-is-visible'); expanded(); };
    const toggle = () => { if (!small.peek()) { el.cls('~is-collapsed'); expanded(); } else if (el.cls('?is-open')) close(); else open(); };
    ov?.on('click', close, on);
    btn?.on('click', toggle, on);
    document.on('keydown', (e) => { if (e.key === 'Escape' && el.cls('?is-open')) close(); }, on);
    const stop = $.watch(small, close);                                // crossing the breakpoint: a clean state
    expanded();
    return { open, close, toggle, isExpanded, destroy() { off(); stop(); } };
};

/** A workspace drawer that opens and closes — the start aside of a `.qry-workspace`, or its `.end`
 *  one — with its splitter (qry-ui.css: `is-aside-collapsed` / `is-end-collapsed` on the workspace).
 *  The `button` toggles it (aria-expanded, aria-controls); `persist` keeps it open or closed across
 *  visits; `narrow` (a media query) closes it whenever the window becomes that narrow — the room goes
 *  to the main view, the drawer stays one click away. `active` is a signal, true while it is open (bind an icon to it).
 *  @param {string|Element} aside  the `.qry-workspace-aside` (`.end` for the end one)
 *  @param {{button?: string|Element, persist?: string, narrow?: string, onChange?: (open: boolean) => void}} [opts]
 *  @returns {{ active: {value: boolean}, isOpen: () => boolean, open: () => void, close: () => void, toggle: () => void, destroy: () => void }}
 *  @example makeDrawer('#inspector', { button: '#inspector-btn', persist: 'app:inspector', narrow: '(max-width: 900px)' })
 */
export const makeDrawer = (aside, { button, persist, narrow, onChange } = {}) => {
    const el = $(aside), ws = el.closest('.qry-workspace') ?? el.parentElement, btn = button ? $(button) : null;
    const cls = el.cls('?end') ? 'is-end-collapsed' : 'is-aside-collapsed', { on, off } = _scope();
    const active = $.signal(true, persist ? { persist } : undefined);   // true while it is open
    if (!el.id) el.id = $.uid('qry-drawer');
    const stops = [
        $.effect(() => { ws.cls(cls, !active.value); }),
        $.watch(active, (v) => onChange?.(v)),
        ...(narrow ? [$.effect(() => { if ($.media(narrow).value) active.value = false; })] : []),
    ];
    btn?.attr({ 'aria-controls': el.id, 'aria-expanded': active }).on('click', () => { active.value = !active.peek(); }, on);
    return {
        active,
        isOpen: () => active.peek(),
        open: () => { active.value = true; },
        close: () => { active.value = false; },
        toggle: () => { active.value = !active.peek(); },
        destroy() { off(); for (const s of stops) s(); btn?.attr({ 'aria-controls': null, 'aria-expanded': null }); ws.cls(`-${cls}`); },
    };
};

/** Hide a sticky `.qry-header` on scroll-down, reveal on scroll-up (class `is-hidden`) — more room
 *  to read on a phone. Never hidden near the top, nor while the focus is inside it (keyboard).
 *  Follows `.qry-content` when its CSS makes it a scroller (overflow auto / scroll), else the
 *  window — decided by the CSS, not by how much content there is yet.
 *  @example makeAutoHideHeader('#top')
 *  @param {string|Element} [header='.qry-header']
 *  @param {Object} [opts] @param {string|Element|null} [opts.scroll='.qry-content'] @param {number} [opts.topGuard=8] @param {number} [opts.hideDelta=12] @param {number} [opts.showDelta=6]
 *  @returns {{ destroy: Function }}
 */
export const makeAutoHideHeader = (header = '.qry-header', { scroll = '.qry-content', topGuard = 8, hideDelta = 12, showDelta = 6 } = {}) => {
    const h = $.opt(header);
    const { on, off } = _scope();
    if (!h) return { destroy: off };
    const s = $.opt(scroll);
    const own = s && /(auto|scroll|overlay)/.test(s.css('overflowY'));
    const getY = own ? () => s.scrollTop : () => scrollY;
    let lastY = getY(), raf = 0;
    const update = () => {
        raf = 0;
        const y = getY(), dy = y - lastY, hidden = h.cls('?is-hidden');
        if (y <= topGuard || h.matches(':focus-within')) h.cls('-is-hidden');
        else if (!hidden && dy > hideDelta) h.cls('+is-hidden');
        else if (hidden && dy < -showDelta) h.cls('-is-hidden');
        lastY = y;
    };
    (own ? s : window).on('scroll', () => { raf ||= requestAnimationFrame(update); }, { ...on, passive: true });
    h.on('focusin', () => h.cls('-is-hidden'), on);
    return { destroy() { off(); cancelAnimationFrame(raf); h.cls('-is-hidden'); } };
};

/** Pan + zoom a content element inside a stage — two models, ONE set of gestures:
 *  - `mode: 'scroll'` (default): zoom sets the content's CSS width in % (100% = fit at zoom 1) in a
 *    scrollable stage (`.qry-stage`), whose own scrollbars track the overflow — a long plan, a page.
 *  - `mode: 'view'`: the content keeps its own size (an image, a drawing, a canvas of fixed logical size)
 *    and is placed by a transform — translate(x, y) scale(fit × zoom), zoom 1 = fitted and centred in the
 *    stage (overflow hidden); `view()` gives { zoom, scale, x, y }.
 *  `apply(view, content)` replaces the writing (a canvas redrawn, a --zoom variable); the anchor is
 *  measured on the content's box, so content centred or padded in the stage stays under the cursor.
 *  Ctrl/⌘ + wheel zooms toward the cursor (plain wheel with `wheelModifier: false`) — in proportion to
 *  the wheel's delta, so a trackpad zooms smoothly and a mouse notch by `wheelStep` —, a drag pans, two
 *  fingers pinch, a double-click toggles the zoom; drags starting on an interactive child (`noPan`)
 *  are left alone. The content is resolved live (it may be swapped).
 *  @param {string|Element} stage  the container
 *  @param {Object} [opts]
 *  @param {string|Element} [opts.content]  zoom target (default: the stage's first element child)
 *  @param {'scroll'|'view'} [opts.mode='scroll']
 *  @param {number} [opts.min=0.25] @param {number} [opts.max=8]
 *  @param {number} [opts.step=1.25]  button / dblclick factor
 *  @param {number} [opts.wheelStep=1.1]  factor of one wheel notch (100 px of delta)
 *  @param {boolean}[opts.wheelModifier=true]  require Ctrl/⌘ for wheel-zoom
 *  @param {boolean}[opts.doubleClick=true]  a double-click toggles the zoom
 *  @param {string} [opts.noPan]  selector of children that do NOT start a pan
 *  @param {(view: {zoom:number, scale:number, x:number, y:number}, content: Element) => void} [opts.apply]
 *  @param {Function} [opts.onZoom]  (zoom:number) → void
 *  @param {Function} [opts.onPan]   (x:number, y:number) → void   (scroll: the scroll offsets; view: the content's offset)
 *  @returns {{ zoom:(z?:number)=>number, view:()=>{zoom:number, scale:number, x:number, y:number}, zoomIn, zoomOut, zoomAt:(factor, clientX?, clientY?)=>void, fit, destroy }}   zoom() reads, zoom(z) sets
 *  (A pinch that ends with one finger still down continues as a pan from there.)
 *  @example const view = makeZoomPan('#stage', { content: '#canvas', onZoom: () => scheduleRender() })
 *  @example makeZoomPan('#stage', { mode: 'view', content: '#photo' })   // a photo at its own size, fitted, zoomed by transform
 */
export const makeZoomPan = (stage, {
    content, mode = 'scroll', min = 0.25, max = 8, step = 1.25, wheelStep = 1.1, wheelModifier = true, doubleClick = true,
    noPan = 'a,button,input,select,textarea,label,[contenteditable],[data-no-pan]',
    apply, onZoom, onPan,
} = {}) => {
    const s = $(stage), byView = mode === 'view';
    const { on, off } = _scope();
    const target = () => (content ? $(content) : s.firstElementChild);
    let z = 1, x = 0, y = 0;                                         // view: the content's offset in the stage (px)
    const fitScale = () => {                                          // view: the scale that fits the content's own size
        const c = target(), w = c?.offsetWidth, h = c?.offsetHeight;
        return byView && w && h ? Math.min(s.clientWidth / w, s.clientHeight / h) : 1;
    };
    const view = () => ({ zoom: z, scale: fitScale() * z, x, y });
    const paint = () => {
        const c = target();
        if (!c) return;
        if (apply) apply(view(), c);
        else if (byView) c.css({ transformOrigin: '0 0', transform: `translate(${x}px, ${y}px) scale(${fitScale() * z})` });
        else c.css('width', z * 100 + '%');
    };
    const center = () => {                                            // view at zoom 1: fitted and centred
        const c = target(), k = fitScale();
        if (c) { x = (s.clientWidth - c.offsetWidth * k) / 2; y = (s.clientHeight - c.offsetHeight * k) / 2; }
    };
    const zoomAt = (factor, cx, cy) => {               // keep the point under (cx, cy) where it is
        const nz = $.clamp(z * factor, min, max), c = target(), r = s.getBoundingClientRect();
        if (nz === z || !c) return;
        if (cx == null) { cx = r.left + s.clientLeft + s.clientWidth / 2; cy = r.top + s.clientTop + s.clientHeight / 2; }
        if (byView) {
            const k = nz / z, px = cx - r.left - s.clientLeft, py = cy - r.top - s.clientTop;
            x = px - (px - x) * k; y = py - (py - y) * k; z = nz; paint();
        } else {
            const a = c.getBoundingClientRect(), fx = a.width ? (cx - a.left) / a.width : 0, fy = a.height ? (cy - a.top) / a.height : 0;
            z = nz; paint();
            const b = c.getBoundingClientRect();                       // where that point went: scroll it back under the cursor
            s.scrollLeft += b.left + fx * b.width - cx; s.scrollTop += b.top + fy * b.height - cy;
        }
        onZoom?.(z);
    };
    let from = null;                                                  // a pan: where the pointer and the view were when it began
    const panFrom = (e) => { from = { cx: e.clientX, cy: e.clientY, x: byView ? x : s.scrollLeft, y: byView ? y : s.scrollTop }; };
    const panTo = (e) => {                                            // measured from the start: no rounding piles up
        const dx = e.clientX - from.cx, dy = e.clientY - from.cy;
        if (byView) { x = from.x + dx; y = from.y + dy; paint(); onPan?.(x, y); }
        else { s.scrollLeft = from.x - dx; s.scrollTop = from.y - dy; onPan?.(s.scrollLeft, s.scrollTop); }
    };
    s.on('wheel', (e) => {
        if (wheelModifier && !(e.ctrlKey || e.metaKey)) return;
        e.preventDefault();
        const px = e.deltaY * (e.deltaMode === 1 ? 16 : e.deltaMode === 2 ? s.clientHeight : 1);   // lines, pages → px
        zoomAt(wheelStep ** (-px / 100), e.clientX, e.clientY);
    }, { ...on, passive: false });
    // one pointer registry drives both the one-pointer pan and the two-pointer pinch
    const pts = new Map();
    let pinch = 0;
    const pair = () => [...pts.values()];
    const dist = () => { const [a, b] = pair(); return Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY); };
    s.on('pointerdown', (e) => {
        if ((e.pointerType === 'mouse' && e.button !== 0) || e.target.closest?.(noPan)) return;
        pts.set(e.pointerId, e);
        try { s.setPointerCapture(e.pointerId); } catch { /* a synthetic event */ }
        if (pts.size === 2) { pinch = dist(); s.cls('-is-grabbing'); } else { panFrom(e); s.cls('+is-grabbing'); }
    }, on);
    s.on('pointermove', (e) => {
        if (!pts.has(e.pointerId)) return;
        pts.set(e.pointerId, e);
        if (pts.size >= 2) {
            const d = dist(), [a, b] = pair();
            if (pinch) zoomAt(d / pinch, (a.clientX + b.clientX) / 2, (a.clientY + b.clientY) / 2);
            pinch = d;
        } else panTo(e);
    }, on);
    s.on('pointerup pointercancel', (e) => {
        pts.delete(e.pointerId);
        if (pts.size < 2) pinch = 0;
        if (pts.size === 1) panFrom(pair()[0]);                    // a pinch ending in one finger: pan from here
        if (!pts.size) s.cls('-is-grabbing');
    }, on);
    s.on('dblclick', (e) => { if (doubleClick && !e.target.closest?.(noPan)) zoomAt(z > 1 ? 1 / z : step * step, e.clientX, e.clientY); }, on);
    const fit = () => { z = 1; if (byView) center(); else s.scrollLeft = s.scrollTop = 0; paint(); onZoom?.(z); };
    if (byView) { s.css('overflow', 'hidden'); center(); }
    paint();
    return {
        zoom: (...v) => { if (v.length) zoomAt(v[0] / z); return z; },
        view,
        zoomIn: () => zoomAt(step),
        zoomOut: () => zoomAt(1 / step),
        zoomAt,
        fit,
        destroy: off,
    };
};

/** A menu on the native popover: `<button popovertarget="m">⋯</button>` +
 *  `<div id="m" popover class="qry-menu"><button data-value="copy">Copy</button><hr>…</div>`.
 *  The browser already opens, closes, light-dismisses and handles Escape; qry-ui.css places it
 *  under its button with CSS anchor positioning (flipping at the viewport edges). makeMenu adds
 *  what a menu needs on top: an explicit anchor, the ARIA menu pattern (roles, aria-haspopup,
 *  aria-expanded), focus on the first item, ↑/↓/Home/End between items, Tab closes, a click
 *  on an item calls onSelect and closes (an item marked `data-keep` keeps it open: a size − / +).
 *  Items can be added later: they are read on open. A menu built in a row not yet in the page finds
 *  its button in that row (its root node), and is given an id when it has none.
 *  Without makeMenu, `.qry-menu` / `.qry-popover` still open and anchor (implicit anchor).
 *  @param {string|Element} menu  the popover element
 *  @param {Object} [opts]
 *  @param {string|Element} [opts.button]  its trigger (default: the `[popovertarget=<id>]` button beside it)
 *  @param {(value:string, item:Element)=>void} [opts.onSelect]  value = data-value, else the text
 *  @returns {{ open:()=>void, close:()=>void, toggle:()=>void, isOpen:()=>boolean, destroy:()=>void }}
 *  @example makeMenu('#more', { onSelect: (v) => v === 'export' && exportCsv() })
 */
export const makeMenu = (menu, { button, onSelect } = {}) => {
    const { on, off } = _scope();
    const { m, b, controller } = _anchored(menu, button, on, off);
    m.attr('role', 'menu');
    b?.attr('aria-haspopup', 'menu');
    const items = () => m.find(':scope > :is(button, a, [role="menuitem"])').filter((i) => !i.disabled && !i.hidden);
    m.on('beforetoggle', (e) => { if (e.newState === 'open') items().forEach((i) => i.attr({ role: 'menuitem', tabindex: '-1' })); }, on);
    m.on('toggle', (e) => { if (e.newState === 'open') items()[0]?.focus(); }, on);   // focusable once shown
    m.on('keydown', (e) => {
        if (e.key === 'Tab') { m.hidePopover(); return; }
        const list = items(), i = list.indexOf(document.activeElement), n = list.length;
        const j = { ArrowDown: i + 1, ArrowUp: i - 1 + n, Home: 0, End: n - 1 }[e.key];
        if (j === undefined || !n) return;
        e.preventDefault();
        list[j % n].focus();
    }, on);
    m.on('click', (e) => {
        const item = e.target.closest?.('button, a, [role="menuitem"]');
        if (!item || !m.contains(item) || item.disabled) return;
        onSelect?.(item.dataset.value ?? item.textContent.trim(), item);
        if (!item.hasAttribute('data-keep')) m.hidePopover();
    }, on);
    return controller;
};

/** @private — a popover anchored to its button: the menu's and makePopover's common ground. The popover
 *  gets an id (if none) and `popover`; the button (given, else the `[popovertarget]` beside it — in its
 *  root, so a row not yet in the page works) gets popovertarget and an explicit CSS anchor (qry-ui.css
 *  places the popover under it, flipping at the edges); aria-expanded follows in `beforetoggle` (sync);
 *  closing with the focus inside gives it back to the button. → { m, b, controller }. */
const _anchored = (popover, button, on, off) => {
    const m = $(popover);
    if (!m.id) m.id = $.uid('qry-pop');
    if (!m.hasAttribute('popover')) m.attr('popover', '');
    const b = button != null ? $.opt(button) : m.getRootNode().querySelector(`[popovertarget="${CSS.escape(m.id)}"]`);
    const anchor = `--${$.uid('qry-anchor')}`;
    m.css('position-anchor', anchor);
    b?.attr({ popovertarget: m.id, 'aria-expanded': false }).css('anchor-name', anchor);
    const isOpen = () => m.matches(':popover-open');
    m.on('beforetoggle', (e) => {                         // synchronous: ARIA is right before anyone looks
        const open = e.newState === 'open';
        b?.attr('aria-expanded', open);
        if (!open && m.contains(document.activeElement)) b?.focus();
    }, on);
    return { m, b, controller: {
        open: () => { if (!isOpen()) m.showPopover(); },
        close: () => { if (isOpen()) m.hidePopover(); },
        toggle: () => m.togglePopover(),
        isOpen,
        destroy: off,
    } };
};

/** A panel on the native popover, anchored to its button — help, a picker, a few settings:
 *  `<button popovertarget="p">?</button><div id="p" class="qry-popover">…</div>`. The browser opens,
 *  light-dismisses and closes it on Escape; makePopover adds the anchor (under its button, flipping
 *  at the edges), aria-expanded, and the focus given back to the button on close.
 *  @param {string|Element} panel  the popover element
 *  @param {{button?: string|Element}} [opts]  its trigger (default: the `[popovertarget=<id>]` button beside it)
 *  @returns {{ open:()=>void, close:()=>void, toggle:()=>void, isOpen:()=>boolean, destroy:()=>void }}
 *  @example makePopover('#units-help')
 */
export const makePopover = (panel, { button } = {}) => {
    const { on, off } = _scope();
    const { m, controller } = _anchored(panel, button, on, off);
    m.cls('+qry-popover');
    return controller;
};

/** Toggle tips: every `.qry-help` under `root` — there now or added later — becomes a popover behind a
 *  small (i) button put before it (named labels.help), anchored there (makePopover). Write the help where
 *  it reads best, after a field or in a step; it shows on demand and never takes room.
 *  @param {Element|Document} [root=document]
 *  @param {{signal?: AbortSignal}} [opts]
 *  @example toggletips()   // <label>Gain <input …></label><p class="qry-help">Above 3, the signal clips.</p>
 */
export const toggletips = (root = document, { signal } = {}) => {
    const made = new Set();
    const wire = (help) => {
        if (help.hasAttribute('popover')) return;
        const b = $.create('button', { type: 'button', class: 'qry-help-button', 'aria-label': labels.help, text: 'i' });
        help.before(b);
        made.add({ help, b, ctrl: makePopover(help, { button: b }) });
    };
    const scan = () => { for (const h of (root.querySelectorAll ? root : document).querySelectorAll('.qry-help')) wire(h); };
    scan();
    const watch = new MutationObserver(scan);
    watch.observe(root === document ? document.documentElement : root, { childList: true, subtree: true });
    signal?.addEventListener('abort', () => {
        watch.disconnect();
        for (const { help, b, ctrl } of made) { ctrl.destroy(); b.remove(); help.removeAttribute('popover'); }
    }, { once: true });
};

// ════════════════════════════════════════════════════════════════════════════
// CANVAS — ink and a laser pointer, on qry-web's makeCanvas
// ════════════════════════════════════════════════════════════════════════════

/** Freehand ink on a canvas — a signature, a note on a page. Strokes are points in CSS px,
 *  captured from mouse, finger or pen (coalesced events: smooth lines), drawn sharp and in the
 *  theme (makeCanvas). The strokes FOLLOW the canvas: when its box is resized (a window, a phone
 *  turned) every point scales with the width, so a signature keeps its place and shape (a hidden,
 *  0 px canvas keeps its last size). `boxWidth()` is the box width the strokes are expressed in;
 *  `toPath(scale)` gives an SVG path ("M x y L …" per stroke) — e.g. for pdf-lib's drawSvgPath
 *  with scale = boxInPoints / boxWidth(); `strokes` is the raw data. `load(strokes, width)` puts
 *  saved strokes back (an answer restored, a draft reopened): they were drawn in a box `width`
 *  px wide and are scaled to this one — or, on a canvas not laid out yet, when it gets its size.
 *  @param {string|HTMLCanvasElement} canvas
 *  @param {{lineWidth?: number, color?: string, onChange?: (strokes: number[][][]) => void}} [opts]  lineWidth in CSS px (2.2); color: a --qry-* token or a CSS colour (default --qry-text)
 *  @returns {{ strokes: number[][][], load: (strokes: number[][][], boxWidth: number) => void, undo: () => void, clear: () => void, isEmpty: () => boolean, boxWidth: () => number, toPath: (scale?: number) => string, destroy: () => void }}
 *  @example const sign = makeInk('#signature'); … if (sign.isEmpty()) toast('Please sign', { tone: 'warn' })
 */
export const makeInk = (canvas, { lineWidth = 2.2, color = '--qry-text', onChange } = {}) => {
    const strokes = [];
    const view = makeCanvas(canvas, { draw(ctx, { color: resolve }) {
        Object.assign(ctx, { lineWidth, lineCap: 'round', lineJoin: 'round', strokeStyle: resolve(color) });
        for (const s of strokes) {
            ctx.beginPath();
            s.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y)));
            if (s.length === 1) ctx.lineTo(s[0][0] + 0.01, s[0][1]);   // a tap is a dot
            ctx.stroke();
        }
    } });
    const { on, off } = _scope();
    const changed = () => { view.redraw(); onChange?.(strokes); };
    let boxW = 0;
    view.canvas.onResize(({ width: w }) => {
        if (!w) return;                                                 // hidden: keep the last size
        if (boxW && Math.abs(w - boxW) > 0.5) { const k = w / boxW; for (const s of strokes) for (const p of s) { p[0] *= k; p[1] *= k; } }
        boxW = w;
    }, on);
    view.canvas.onDrag(({ x, y, phase, event }) => {
        const c = view.canvas, bx = c.clientLeft, by = c.clientTop;      // the bitmap starts inside the border
        if (phase === 'start') strokes.push([[x - bx, y - by]]);
        else if (phase === 'move') {
            const r = c.getBoundingClientRect();
            for (const p of event.getCoalescedEvents?.() ?? [event]) strokes.at(-1).push([p.clientX - r.left - bx, p.clientY - r.top - by]);
            view.redraw();
        } else changed();
    }, on);
    const pt = ([x, y], k) => `${+(x * k).toFixed(2)} ${+(y * k).toFixed(2)}`;
    return {
        strokes,
        load(saved, from = boxW) {
            const k = boxW && from ? boxW / from : 1;
            strokes.length = 0;
            for (const st of saved ?? []) strokes.push(st.map(([x, y]) => [x * k, y * k]));
            if (!boxW) boxW = from;                                        // the first size scales them
            view.redraw();
        },
        undo() { strokes.pop(); changed(); },
        clear() { strokes.length = 0; changed(); },
        isEmpty: () => !strokes.length,
        boxWidth: () => boxW,
        toPath: (scale = 1) => strokes.map((s) => `M${pt(s[0], scale)} L${(s.length > 1 ? s.slice(1) : [[s[0][0] + 0.01, s[0][1]]]).map((p) => pt(p, scale)).join(' L')}`).join(' '),
        destroy() { off(); view.destroy(); },
    };
};

/** A laser pointer for a talk: a dot that follows the mouse with a short fading trail, the cursor
 *  hidden while it is on (html.is-laser). One full-window canvas that takes no pointer events, drawn
 *  in a theme token (makeCanvas: sharp, follows the theme). `active` is a SIGNAL — bind a button's
 *  aria-pressed to it, or write it; `toggle()` flips it; `persist` keeps it across reloads.
 *  @param {{persist?: string, color?: string, trail?: number}} [opts]  color: a --qry-* token (--qry-danger); trail in ms (350)
 *  @returns {{ active: Signal<boolean>, toggle: () => void, destroy: () => void }}
 *  @example const laser = makeLaser({ persist: 'talk:laser' }); $('#laser').attr('aria-pressed', laser.active).on('click', laser.toggle)
 */
export const makeLaser = ({ persist, color = '--qry-danger', trail = 350 } = {}) => {
    const { on, off } = _scope();
    const canvas = $.create('canvas', { class: 'qry-laser', 'aria-hidden': 'true' });
    const active = $.signal(false, persist ? { persist } : undefined);
    let points = [], at = null;
    document.body.add(canvas);
    const view = makeCanvas(canvas, {
        draw(ctx, { color: c }) {
            const now = performance.now();
            points = points.filter((p) => now - p.t < trail);
            ctx.strokeStyle = ctx.fillStyle = c(color);
            ctx.lineCap = 'round';
            for (let i = 1; i < points.length; i += 1) {                 // older = thinner and fainter
                const k = 1 - (now - points[i].t) / trail;
                ctx.globalAlpha = 0.6 * k; ctx.lineWidth = 2 + 6 * k;
                ctx.beginPath(); ctx.moveTo(points[i - 1].x, points[i - 1].y); ctx.lineTo(points[i].x, points[i].y); ctx.stroke();
            }
            ctx.globalAlpha = 1;
            if (at) { ctx.beginPath(); ctx.arc(at.x, at.y, 7, 0, Math.PI * 2); ctx.fill(); }
            if (points.length > 1) view.redraw();                        // the trail is still fading
        },
    });
    document.on('pointermove', (e) => {
        if (!active.value || e.pointerType === 'touch') return;
        at = { x: e.clientX, y: e.clientY, t: performance.now() };
        points.push(at); view.redraw();
    }, { ...on, passive: true });
    document.documentElement.on('pointerleave', () => { at = null; points = []; view.redraw(); }, on);   // out of the window (or into an iframe)
    const stop = $.effect(() => {
        const lit = active.value;
        canvas.hidden = !lit;
        document.documentElement.cls('is-laser', lit);
        if (!lit) { at = null; points = []; }
    });
    return {
        active,
        toggle: () => { active.value = !active.value; },
        destroy() { off(); stop(); view.destroy(); canvas.remove(); document.documentElement.cls('-is-laser'); },
    };
};

// ════════════════════════════════════════════════════════════════════════════
// CHARTS — plain SVG, drawn for its box, in the theme's tokens
// ════════════════════════════════════════════════════════════════════════════

/** @private — a value accessor: a function (d, i) → v, or a property name. */
const _acc = (f) => (typeof f === 'function' ? f : (d) => d?.[f]);

/** @private — nice ticks (1, 2, 5 × 10ⁿ) covering [lo, hi], about `n` of them (whole numbers only
 *  with `integer`: counts, trial numbers). */
/** @private — min / max without spreading (Math.min(...a) overflows the stack past ~150 000 values). */
const _least = (a) => a.reduce((m, v) => (v < m ? v : m), Infinity), _most = (a) => a.reduce((m, v) => (v > m ? v : m), -Infinity);
const _ticks = (lo, hi, n = 5, integer = false) => {
    if (!(hi > lo)) { const w = Math.abs(lo) || 1; lo -= w / 2; hi += w / 2; }
    const raw = (hi - lo) / Math.max(1, n), mag = 10 ** Math.floor(Math.log10(raw)), f = raw / mag;
    let step = (f < 1.5 ? 1 : f < 3 ? 2 : f < 7 ? 5 : 10) * mag;
    if (integer) step = Math.max(1, Math.round(step));
    const a = Math.floor(lo / step + 1e-9) * step, b = Math.ceil(hi / step - 1e-9) * step;
    const out = [];
    for (let v = a; v <= b + step / 2; v += step) out.push(+v.toFixed(10));
    return { lo: a, hi: b, ticks: out, step };
};

/** A chart in plain SVG — dots, lines, bars and reference rules on shared axes — drawn for its box
 *  (size the element with CSS: a height) and in the theme: marks are painted with the tokens
 *  (`--qry-accent`, or a tone), so light / dark and a scoped `.qry-theme` follow with no redraw.
 *  `marks` is an array, or a FUNCTION returning one: it runs in an effect — the chart redraws when
 *  a signal it reads changes. It also redraws when its box is resized.
 *  A mark: `{ type: 'dot' | 'line' | 'bar' | 'rule', data, x, y, tone, tip }` —
 *    x / y: an accessor `(d, i) => value` or a property name; tone: a tone class (`ok` `warn` `danger`
 *    `purple` `accent` `highlight`, or `muted` for background data) or `(d) => tone`; tip: `(d) =>
 *    [title, content]` — $.tip on hover. dot: `r` (px, default 3). line: points in data order (a
 *    null y breaks it). bar: from 0 to y — on a BAND axis when its x values are not numbers (one
 *    slot per category), else from x to `x2` (a histogram's bins). rule: a line across at `y` (or
 *    down at `x`), with an optional `label`.
 *  Axes: `x` / `y` = { label, min, max, ticks (about how many, default 5), format: (v) => text, zero,
 *  integer (whole-number ticks only: counts, trial numbers) } — a domain from the data unless given,
 *  extended to nice ticks; bars always include 0. A band's bars are at most 72 px wide.
 *  No data: the chart says `labels.empty`. The SVG is role="img", named by `label` — a string, or a
 *  function read in the same effect (a name in the page's language, a year that changes).
 *  @param {string|Element} el  the chart's box
 *  @param {{marks: Array|Function, x?: Object, y?: Object, label?: string|Function}} opts
 *  @returns {{ readonly svg: SVGSVGElement|null, redraw: () => void, destroy: () => void }}
 *  @example makeChart('#rt', { label: 'RT per trial', y: { label: 'ms', zero: true },
 *    marks: () => [{ type: 'dot', data: trials.value, x: 'n', y: 'rt', tone: (t) => (t.ok ? 'accent' : 'danger') }] })
 */
export const makeChart = (el, { marks = [], x: xo = {}, y: yo = {}, label = '' } = {}) => {
    const box = $(el), { on, off } = _scope();
    let size = { width: 0, height: 0 }, svg = null, list = [], name = '';
    const draw = () => {
        const { width: W, height: H } = size;
        if (!W || !H) return;
        const all = list.filter(Boolean).map((m) => ({ ...m, xs: _acc(m.x), ys: _acc(m.y), data: m.data ?? [] }));
        const bars = all.filter((m) => m.type === 'bar');
        const band = bars.some((m) => !m.x2 && m.data.some((d, i) => typeof m.xs(d, i) !== 'number'));
        const cats = band ? [...new Set(bars.flatMap((m) => m.data.map((d, i) => String(m.xs(d, i)))))] : [];
        const xv = [], yv = [];
        for (const m of all) {
            if (m.type === 'rule') { if (m.y != null) yv.push(m.y); if (m.x != null && !band) xv.push(m.x); continue; }
            m.data.forEach((d, i) => {
                const yy = m.ys(d, i), xx = m.xs(d, i);
                if (Number.isFinite(yy)) yv.push(yy);
                if (!band && Number.isFinite(xx)) xv.push(xx);
                if (m.x2 && Number.isFinite(_acc(m.x2)(d, i))) xv.push(_acc(m.x2)(d, i));
            });
            if (m.type === 'bar') yv.push(0);
        }
        if (yo.zero) yv.push(0);
        const svgEl = $.svg('svg', { class: 'qry-chart', width: W, height: H, viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': name || null });
        if (!yv.length || (!band && !xv.length)) {
            svgEl.add($.svg('text', { class: 'qry-chart-empty', x: W / 2, y: H / 2, 'text-anchor': 'middle', 'dominant-baseline': 'middle', text: labels.empty }));
            return svgEl;
        }
        const fx = xo.format ?? ((v) => String(v)), fy = yo.format ?? ((v) => String(v));
        const ty = _ticks(yo.min ?? _least(yv), yo.max ?? _most(yv), yo.ticks ?? 5, yo.integer);
        const ylo = yo.min ?? ty.lo, yhi = yo.max ?? ty.hi, yt = ty.ticks.filter((v) => v >= ylo - 1e-9 && v <= yhi + 1e-9);
        const tx = band ? null : _ticks(xo.min ?? _least(xv), xo.max ?? _most(xv), xo.ticks ?? 5, xo.integer);
        const xlo = band ? 0 : xo.min ?? tx.lo, xhi = band ? 0 : xo.max ?? tx.hi;
        const chars = Math.max(...yt.map((v) => fy(v).length));
        const L = 10 + chars * 6.6 + (yo.label ? 16 : 0), R = 10, T = 10, B = 22 + (xo.label ? 16 : 0);
        const sy = (v) => $.scale(v, ylo, yhi, H - B, T);
        const slot = band ? (W - L - R) / Math.max(1, cats.length) : 0;
        const sx = band ? (c) => L + (cats.indexOf(String(c)) + 0.5) * slot : (v) => $.scale(v, xlo, xhi, L, W - R);
        const g = (cls) => $.svg('g', { class: cls }).mount(svgEl);
        const grid = g('qry-chart-grid'), axes = g('qry-chart-axes');
        for (const v of yt) {
            grid.add($.svg('line', { x1: L, x2: W - R, y1: sy(v), y2: sy(v) }));
            axes.add($.svg('text', { class: 'qry-chart-tick', x: L - 6, y: sy(v), 'text-anchor': 'end', 'dominant-baseline': 'middle', text: fy(v) }));
        }
        const xt = band ? cats : tx.ticks.filter((v) => v >= xlo - 1e-9 && v <= xhi + 1e-9);
        const every = Math.max(1, Math.ceil(xt.length / Math.max(1, (W - L - R) / Math.max(36, 7 * Math.max(...xt.map((v) => fx(v).length)) + 10))));
        xt.forEach((v, i) => { if (i % every === 0) axes.add($.svg('text', { class: 'qry-chart-tick', x: sx(v), y: H - B + 14, 'text-anchor': 'middle', text: fx(v) })); });
        axes.add($.svg('line', { class: 'qry-chart-axis', x1: L, x2: W - R, y1: H - B, y2: H - B }));
        if (yo.label) axes.add($.svg('text', { class: 'qry-chart-label', transform: `translate(12 ${(T + H - B) / 2}) rotate(-90)`, 'text-anchor': 'middle', text: yo.label }));
        if (xo.label) axes.add($.svg('text', { class: 'qry-chart-label', x: (L + W - R) / 2, y: H - 4, 'text-anchor': 'middle', text: xo.label }));
        const layer = g('qry-chart-marks');
        const tone = (m, d) => (typeof m.tone === 'function' ? m.tone(d) : m.tone) || '';
        const tip = (node, m, d) => { if (m.tip) { const [a, b] = [].concat(m.tip(d)); $.tip(node, a, b); } return node; };
        for (const m of all) {
            const cls = (d) => `qry-chart-${m.type} ${tone(m, d)}`.trim();
            if (m.type === 'rule') {
                const r = m.y != null ? { x1: L, x2: W - R, y1: sy(m.y), y2: sy(m.y) } : { x1: sx(m.x), x2: sx(m.x), y1: T, y2: H - B };
                layer.add($.svg('line', { class: cls(), ...r }));
                if (m.label) layer.add($.svg('text', { class: `qry-chart-rule-label ${tone(m)}`.trim(), x: m.y != null ? W - R - 4 : r.x1 + 4, y: m.y != null ? r.y1 - 4 : T + 10, 'text-anchor': m.y != null ? 'end' : 'start', text: m.label }));
            } else if (m.type === 'line') {
                let d = '', pen = false;
                m.data.forEach((p, i) => {
                    const yy = m.ys(p, i), xx = m.xs(p, i);
                    if (!Number.isFinite(yy)) { pen = false; return; }
                    d += `${pen ? 'L' : 'M'}${sx(xx).toFixed(1)} ${sy(yy).toFixed(1)}`; pen = true;
                });
                layer.add($.svg('path', { class: cls(m.data[0]), d }));
            } else {
                m.data.forEach((p, i) => {
                    const yy = m.ys(p, i), xx = m.xs(p, i);
                    if (!Number.isFinite(yy)) return;
                    if (m.type === 'dot') layer.add(tip($.svg('circle', { class: cls(p), cx: sx(xx), cy: sy(yy), r: m.r ?? 3 }), m, p));
                    else {
                        const y0 = sy(0), y1 = sy(yy);
                        const [x0, w] = band ? [sx(xx) - Math.min(slot * 0.4, 36), Math.min(slot * 0.8, 72)] : [sx(xx), Math.max(1, sx(_acc(m.x2)(p, i)) - sx(xx) - 1)];
                        layer.add(tip($.svg('rect', { class: cls(p), x: x0, y: Math.min(y0, y1), width: Math.max(1, w), height: Math.abs(y0 - y1) }), m, p));
                    }
                });
            }
        }
        return svgEl;
    };
    const paint = () => { const next = draw(); if (next) { if (svg?.isConnected) svg.replaceWith(next); else box.add(next); svg = next; } };
    box.onResize((s) => { size = s; paint(); }, on);
    const read = () => { list = (typeof marks === 'function' ? marks() : marks) ?? []; name = typeof label === 'function' ? label() : label; };
    const stop = typeof marks === 'function' || typeof label === 'function' ? $.effect(() => { read(); paint(); }) : (read(), null);
    return { get svg() { return svg; }, redraw: paint, destroy() { off(); stop?.(); svg?.remove(); svg = null; } };
};

// ════════════════════════════════════════════════════════════════════════════
// INPUT — keyboard shortcuts, sliders, a calendar, drop zone
// ════════════════════════════════════════════════════════════════════════════

/** Global keyboard shortcuts, in ONE syntax — the one `aria-keyshortcuts` uses:
 *  `Modifier+Key`, several alternatives space-separated — "s", "Escape", "Space", "ArrowLeft",
 *  "Shift+N", "Alt+E", "Control+S" (Control = Ctrl or ⌘), "Control+Shift+Z". A letter compares
 *  case-insensitively and its modifiers must match exactly (Control+Z is not Control+Shift+Z, and
 *  "e" never fires with Alt); a digit or a symbol ("1", "?") ignores Shift (layouts differ: digits
 *  need Shift in French). `add(shortcut, fn, { prevent, inFields, repeat })` registers and returns a remover
 *  (`inFields: true`: a Control / ⌘ shortcut, or a function key — F1 for the help — fires in a field too: an editor's Control+S;
 *  `repeat: false`: a key HELD fires once — Start, Next, a toggle — its repeats taken and ignored);
 *  a handler that returns `false` declines: the key goes on to the next one and to the browser.
 *  `destroy()` removes everything.
 *  A shortcut never steals a key from something that owns it: skipped when the event was already
 *  handled (defaultPrevented), while typing in a field (a text-like INPUT, TEXTAREA, SELECT,
 *  [contenteditable] — also inside a web component), while a modal dialog is open or the focus is in an
 *  open popover (a menu: its keys are its own), and — for a key
 *  without Control — when the control focused FROM THE KEYBOARD uses that very key (a button or link: Space /
 *  Enter; a checkbox or radio: Space and the arrows; a range: the arrows, Home / End, Page Up / Down;
 *  a tab, menu item, option, slider, separator: those and Space / Enter). A button just CLICKED keeps no key: Space after a click on Play is the
 *  page's shortcut again (no blur() needed).
 *
 *  `declarative: true` also honours the markup: when no registered shortcut took the key, the
 *  first visible, enabled element whose `aria-keyshortcuts` lists it is clicked — the attribute
 *  that tells assistive technology about the shortcut is the one that wires it. The same guards
 *  apply; while a modal dialog is open, only elements INSIDE it answer — nothing behind it.
 *  Opt-in, because a page may already handle the keys it documents (then both would act); one
 *  declarative keyboard per page is enough.
 *  @param {{declarative?: boolean}} [opts]
 *  @returns {{ add:(shortcut:string, fn:(e:KeyboardEvent)=>(void|false), opts?:{prevent?:boolean, inFields?:boolean, repeat?:boolean})=>(()=>void), destroy:()=>void }}
 *  @example
 *  const keys = makeKeyboard({ declarative: true });   // + <button aria-keyshortcuts="1">…</button>
 *  keys.add('Space', () => player.toggle());
 *  keys.add('Control+E', () => exportCsv());
 *  keys.add('Control+Shift+Z Control+Y', redo);
 */
const _OWNS = [   // [the controls, the keys they own, owned even when a click focused them]
    ['[role="tab"], [role="menuitem"], [role="option"], [role="slider"], [role="separator"]', /^( |Enter|Arrow\w+|Home|End|PageUp|PageDown)$/],
    ['input[type="range"]', /^(Arrow\w+|Home|End|PageUp|PageDown)$/, true],      // a slider clicked, then fine-tuned with the arrows
    ['input[type="radio"]', /^Arrow\w+$/, true],
    ['input:is([type="checkbox"], [type="radio"])', /^( |Arrow\w+)$/],
    ['button, a[href], summary, [role="button"], input:is([type="button"], [type="submit"], [type="reset"], [type="file"], [type="color"], [type="image"])', /^( |Enter)$/],
];
/** @private — does the focused control own this key? Only when the keyboard put the focus there:
 *  `pointer` is the element a press focused (`:focus-visible` cannot tell — a key press turns it on). */
const _owned = (t, key, pointer) => _OWNS.some(([sel, keys, always]) => (always || t !== pointer) && keys.test(key) && t.closest(sel));
/** @private — a control the user types into (a checkbox, a radio, a range or a button input is not). */
const _typing = (t) => t.isContentEditable || /^(TEXTAREA|SELECT)$/.test(t.tagName)
    || (t.tagName === 'INPUT' && !/^(checkbox|radio|range|color|button|submit|reset|file|image)$/.test(t.type));
/** @private — does a shortcut list (the aria-keyshortcuts syntax) name this key event? */
const _lists = (value, e) => value.split(/\s+/).some((s) => {
    const plus = s === '+' || s.endsWith('++');                       // the "+" key itself
    const parts = (plus ? s.slice(0, -1) : s).split('+').filter(Boolean);
    const key = plus ? '+' : parts.pop();
    if (!key) return false;
    const mods = new Set(parts.map((m) => m.toLowerCase()));
    if (mods.has('alt') !== e.altKey || (mods.has('control') || mods.has('meta')) !== (e.ctrlKey || e.metaKey)) return false;
    const symbol = key.length === 1 && key.toLowerCase() === key.toUpperCase();   // '1', '?': Shift may be how the layout types it
    if (!symbol && mods.has('shift') !== e.shiftKey) return false;
    return (e.key === ' ' ? 'space' : e.key.toLowerCase()) === key.toLowerCase();
});
export const makeKeyboard = ({ declarative = false } = {}) => {
    const handlers = new Set();
    const { on, off } = _scope();
    // the element a pointer press focused, if any: the one PRESSED (or around it) — a menu item a click
    // moved the focus to is the keyboard's (Space / Enter stay its own)
    let pressed = null, pressedAt = -1e9, pointer = null;
    document.on('pointerdown', (e) => { pressed = e.composedPath()[0]; pressedAt = e.timeStamp; }, { capture: true, ...on });
    document.on('keydown', () => { pressed = null; }, { capture: true, ...on });       // a key: the next focus is the keyboard's
    document.on('focusin', (e) => {
        const f = e.composedPath()[0];
        pointer = pressed instanceof Node && f.contains?.(pressed) && e.timeStamp - pressedAt < 1000 ? f : null;
    }, { capture: true, ...on });
    document.on('keydown', (e) => {
        if (e.defaultPrevented) return;
        const t = e.composedPath()[0];                 // the real field, even in a shadow root
        const typing = t instanceof Element && _typing(t);
        if (t instanceof Element && !typing && !(e.ctrlKey || e.metaKey) && _owned(t, e.key, pointer)) return;
        if (t instanceof Element && t.closest(':popover-open')) return;   // a menu, a picker: the focus there owns the keys
        const modal = document.querySelector('dialog:modal');
        if (!modal) for (const h of handlers) {                        // nothing registered fires behind an open modal
            if ((typing && !(h.inFields && (e.ctrlKey || e.metaKey || /^F\d{1,2}$/.test(e.key)))) || !_lists(h.shortcut, e)) continue;   // a field keeps its keys — but an editor's Control+S, and a function key (it types nothing)
            if (e.repeat && !h.repeat) { if (h.prevent) e.preventDefault(); return; }   // held: once
            if (h.fn(e) === false) continue;                           // declined: the next one, then the browser
            if (h.prevent) e.preventDefault();
            return;
        }
        if (!declarative || typing) return;
        const hit = [...(modal ?? document).querySelectorAll('[aria-keyshortcuts]')].find((x) => _lists(x.getAttribute('aria-keyshortcuts'), e) && !x.disabled && x.getAttribute('aria-disabled') !== 'true'
            && !x.closest('[inert]') && x.checkVisibility({ visibilityProperty: true }));
        if (!hit) return;
        e.preventDefault();                            // handled: another keyboard on the page skips it
        hit.click();
    }, on);
    return {
        add: (shortcut, fn, { prevent = true, inFields = false, repeat = true } = {}) => { const h = { shortcut, fn, prevent, inFields, repeat }; handlers.add(h); return () => handlers.delete(h); },
        destroy() { off(); handlers.clear(); },
    };
};

/** The keyboard and ARIA of a custom slider — a circle, a spectrum, a knob drawn in SVG or on
 *  a canvas (for a plain range, use <input type="range">). The element gets role="slider", a tab
 *  stop, aria-valuemin / max and a live aria-valuenow (and aria-valuetext with `valueText`); the keys
 *  move the signal: arrows ± step, Shift+arrows and Page Up / Down ± page, Home / End. `wrap`
 *  makes it circular (an angle: 359 + 1 → 0). Pointer input stays yours: write the signal.
 *  `valueText` runs in an effect: it follows the value AND any signal it reads (a curve's data).
 *  @param {string|Element} el
 *  @param {{value: {value: number}, min?: number, max?: number, step?: number, page?: number, wrap?: boolean, valueText?: (v: number) => string}} opts
 *  @returns {{ destroy: () => void }}
 *  @example makeSlider('#dial', { value: angle, max: 359, page: 15, wrap: true, valueText: (v) => `${v} degrees` })
 */
export const makeSlider = (el, { value, min = 0, max = 100, step = 1, page = step * 10, wrap = false, valueText } = {}) => {
    const s = $(el), { on, off } = _scope();
    s.attr({ role: 'slider', tabindex: s.getAttribute('tabindex') ?? '0', 'aria-valuemin': min, 'aria-valuemax': max });
    const stop = $.effect(() => { s.attr('aria-valuenow', value.value); if (valueText) s.attr('aria-valuetext', valueText(value.value)); });
    s.on('keydown', (e) => {
        const big = e.shiftKey ? page : step, v = value.peek();
        const next = { ArrowRight: v + big, ArrowUp: v + big, ArrowLeft: v - big, ArrowDown: v - big,
            PageUp: v + page, PageDown: v - page, Home: min, End: max }[e.key];
        if (next === undefined || e.ctrlKey || e.metaKey || e.altKey) return;
        e.preventDefault();
        const span = max - min + step;                                  // wrap: max + step is min again
        value.value = wrap ? min + ((((next - min) % span) + span) % span) : $.clamp(next, min, max);
    }, on);
    return { destroy() { off(); stop(); } };
};

/** A month to pick a day from — a booking page's calendar, a date picker's popover. It builds
 *  its markup in `el` (a `.qry-calendar`): a head (previous · « octobre 2026 » · next) and a
 *  grid of the month's days, the week starting where the language says (lundi in fr, Sunday in
 *  en-US). Days are LOCAL `YYYY-MM-DD` strings (format.day).
 *  - Keys (the ARIA grid pattern): arrows ± 1 day / 1 week, Home / End the week's ends, Page Up /
 *    Down a month (Shift: a year) — the month follows the focus; Enter or Space chooses. One tab stop.
 *  - `marks(day)` → `{ tone, label }` or nothing: a dot in a tone (ok warn danger accent…) under the
 *    number, its label read after the date. It runs in an effect, with `lang`: the grid follows any
 *    signal they read (the month's bookings arriving, the page's language changing).
 *  - `onChange(day)` when a day is chosen (a click, a key, `selected(d)`); `onMonth('YYYY-MM')` when
 *    the month shown changes, and once at creation — load that month's marks there.
 *  The chosen day is aria-pressed, today aria-current="date"; the arrows' names are
 *  `labels.previousMonth` / `labels.nextMonth`.
 *  @param {string|Element} el
 *  @param {{selected?: string, lang?: string|(() => string), marks?: (day: string) => ({tone?: string, label?: string}|null|undefined),
 *           onChange?: (day: string) => void, onMonth?: (month: string) => void}} [opts]
 *  @returns {{ selected: (day?: string) => string, month: (month?: string) => string, element: Element, destroy: () => void }}
 *  @example
 *  const cal = makeCalendar('#calendar', { lang: () => lang.value, onChange: (d) => { day.value = d; },
 *      onMonth: (m) => load(m), marks: (d) => states.value[d] === 'full' ? { tone: 'danger', label: 'Full' } : null });
 */
export const makeCalendar = (el, { selected: start = format.day(), lang, marks, onChange, onMonth } = {}) => {
    const box = $(el).cls('+qry-calendar'), { on, off } = _scope(), id = $.uid('qry-calendar');
    const day = $.signal(start), month = $.signal(start.slice(0, 7)), cursor = $.signal(start);   // cursor: the day with the tab stop
    const locale = () => (typeof lang === 'function' ? lang() : lang) || document.documentElement.lang || navigator.language;
    const at = (d) => new Date(`${d}T12:00`);
    const plus = (m, n) => { const [y, mo] = m.split('-').map(Number); return format.day(0, new Date(y, mo - 1 + n, 1)).slice(0, 7); };
    const firstDay = (loc) => {                                                 // Intl: 1 = Monday … 7 = Sunday
        try { const l = new Intl.Locale(loc).maximize(); return ((l.getWeekInfo?.() ?? l.weekInfo)?.firstDay ?? 1) % 7; } catch { return 1; }
    };
    box.html($.html`<div class="qry-calendar-head">
        <button type="button" class="qry-btn ghost icon sm qry-calendar-previous" data-step="-1" aria-label="${labels.previousMonth}"></button>
        <span class="qry-calendar-title" id="${id}" aria-live="polite"></span>
        <button type="button" class="qry-btn ghost icon sm qry-calendar-next" data-step="1" aria-label="${labels.nextMonth}"></button>
      </div><table class="qry-calendar-grid" role="grid" aria-labelledby="${id}"><thead></thead><tbody></tbody></table>`);
    const title = box.querySelector('.qry-calendar-title'), head = box.querySelector('thead'), body = box.querySelector('tbody');

    const stop = $.effect(() => {
        const loc = locale(), m = month.value, chosen = day.value, focus = cursor.value, today = format.day();
        const first = at(`${m}-01`), count = new Date(first.getFullYear(), first.getMonth() + 1, 0).getDate();
        const start = firstDay(loc), blanks = (first.getDay() - start + 7) % 7;
        const tab = focus.startsWith(m) ? focus : chosen.startsWith(m) ? chosen : `${m}-01`;
        const long = (d) => at(d).toLocaleDateString(loc, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
        const weekday = (i, style) => new Date(2024, 0, 7 + start + i).toLocaleDateString(loc, { weekday: style });   // 2024-01-07 is a Sunday
        const had = box.contains(document.activeElement) && document.activeElement.matches('[data-day]');
        title.textContent = first.toLocaleDateString(loc, { month: 'long', year: 'numeric' });
        head.html($.html`<tr>${[0, 1, 2, 3, 4, 5, 6].map((i) => $.html`<th scope="col" abbr="${weekday(i, 'long')}">${weekday(i, 'short')}</th>`)}</tr>`);
        const cells = [...Array(blanks).fill(null), ...Array.from({ length: count }, (_, i) => `${m}-${String(i + 1).padStart(2, '0')}`)];
        while (cells.length % 7) cells.push(null);
        body.html($.html`${Array.from({ length: cells.length / 7 }, (_, w) => $.html`<tr>${cells.slice(w * 7, w * 7 + 7).map((d) => {
            if (!d) return $.html`<td></td>`;
            const mark = marks?.(d) || null;
            return $.html`<td><button type="button" data-day="${d}" tabindex="${d === tab ? 0 : -1}" aria-pressed="${d === chosen}"
                aria-current="${d === today ? 'date' : 'false'}" aria-label="${long(d)}${mark?.label ? `, ${mark.label}` : ''}">${Number(d.slice(8))}${mark
                && $.html`<span class="qry-calendar-mark ${mark.tone ?? 'accent'}" aria-hidden="true"></span>`}</button></td>`;
        })}</tr>`)}`);
        if (had) body.querySelector(`[data-day="${tab}"]`)?.focus();
    });

    const show = (m) => { if (m === month.peek()) return; month.value = m; onMonth?.(m); };
    const choose = (d) => {
        if (!/^\d{4}-\d{2}-\d{2}$/.test(d ?? '')) return;
        const changed = d !== day.peek();
        $.batch(() => { cursor.value = d; day.value = d; show(d.slice(0, 7)); });
        if (changed) onChange?.(d);
    };
    box.delegate('[data-step]', 'click', function () { show(plus(month.peek(), Number(this.dataset.step))); }, on);
    box.delegate('[data-day]', 'click', function () { choose(this.dataset.day); }, on);
    box.delegate('[data-day]', 'keydown', function (e) {
        const d = this.dataset.day, col = [...this.closest('tr').children].indexOf(this.parentElement);
        const months = e.shiftKey ? 12 : 1;
        const sameDay = (m) => { const last = new Date(Number(m.slice(0, 4)), Number(m.slice(5)), 0).getDate(); return `${m}-${String(Math.min(Number(d.slice(8)), last)).padStart(2, '0')}`; };
        const next = { ArrowRight: format.day(1, at(d)), ArrowLeft: format.day(-1, at(d)), ArrowDown: format.day(7, at(d)), ArrowUp: format.day(-7, at(d)),
            Home: format.day(-col, at(d)), End: format.day(6 - col, at(d)),
            PageDown: sameDay(plus(d.slice(0, 7), months)), PageUp: sameDay(plus(d.slice(0, 7), -months)) }[$.key(e)];
        if (!next || e.ctrlKey || e.metaKey || e.altKey) return;
        e.preventDefault();
        cursor.value = next;
        show(next.slice(0, 7));
        body.querySelector(`[data-day="${next}"]`)?.focus();
    }, on);
    onMonth?.(month.peek());
    return {
        selected: (...a) => { if (a.length) choose(a[0]); return day.peek(); },
        month: (...a) => { if (a.length) show(a[0]); return month.peek(); },
        element: box,
        destroy() { off(); stop(); },
    };
};

/** Files by drag and drop — and by paste (a screenshot, a copied file) with `paste: true`.
 *  Shows `.qry-drop-overlay` (created if absent) while files are dragged over the target;
 *  `onFiles` gets the File[] that pass `accept` (the <input accept> syntax).
 *  Pair with `readFiles` to parse them.
 *  `button` names an element that ALSO opens the file chooser — a click, Enter or Space (it becomes a
 *  focusable role=button if it is not a button already) — so one call gives a page its whole
 *  upload: drop, paste and choose. `multiple: false` keeps one file (the first) from any of the three.
 *  `enable(false)` refuses new files (while one is processed): the button is aria-disabled
 *  and a drop shows no overlay. `onRefused` hears about files `accept` turned away (say why —
 *  a silent drop reads as a broken page); a disabled zone refuses nothing, it just does not listen.
 *  @param {string|Element} [target=document.body]
 *  @param {Object}   opts
 *  @param {Function} opts.onFiles                 (File[], event) → void  (required)
 *  @param {string}   [opts.accept='']             '.json,.zip', 'image/*'… ('' = any)
 *  @param {boolean}  [opts.paste=false]           also take files pasted anywhere on the page
 *  @param {string}   [opts.overlayLabel=labels.drop]  the overlay's text
 *  @param {string|Element} [opts.overlay='#qry-drop']  the overlay: an element, or '#id' (created with that id when absent)
 *  @param {string|Element} [opts.button]        an element that opens the file chooser too
 *  @param {boolean}  [opts.multiple=true]         false: one file only (the first)
 *  @param {Function} [opts.onRefused]             (File[], event) → void: the files `accept` refused
 *  @returns {{ enable: (on?: boolean) => void, isEnabled: () => boolean, destroy: Function }}
 *  @example makeDropZone(document.body, { accept: '.json,.zip', onFiles: async (f) => show(await readFiles(f)) })
 *  @example makeDropZone('#avatar', { accept: 'image/*', paste: true, onFiles: ([img]) => upload(img) })
 *  @example const zone = makeDropZone(document.body, { button: '#upload', accept: '.pdf', multiple: false, paste: true,
 *               onFiles: async ([pdf]) => { zone.enable(false); await send(pdf); zone.enable(true); },
 *               onRefused: () => toast(labels.refused, { tone: 'warn' }) })
 */
export const makeDropZone = (target = document.body, { onFiles, accept = '', paste = false, overlayLabel = labels.drop, overlay = '#qry-drop', button, multiple = true, onRefused } = {}) => {
    if (typeof onFiles !== 'function') throw new Error('makeDropZone: onFiles is required');
    if (typeof overlay === 'string' && !/^#[\w-]+$/.test(overlay)) throw new Error('makeDropZone: overlay is an element or "#id"');
    const el = $(target);
    const { on, off } = _scope();
    const found = $.opt(overlay);
    const ov = found ?? $.create('div', { id: overlay.slice(1), class: 'qry-drop-overlay',
        html: $.html`<div class="qry-drop-overlay-inner"><svg viewBox="0 0 24 24" width="40" height="40" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 16V4M7 9l5-5 5 5M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3"/></svg><span>${overlayLabel}</span></div>` }).mount(document.body);
    let enabled = true;
    const hand = (list, e) => {
        if (!enabled) return 0;
        const all = [...(list || [])];
        let files = all.filter((f) => _accepts(f, accept));
        if (files.length < all.length) onRefused?.(all.filter((f) => !files.includes(f)), e);
        if (!multiple) files = files.slice(0, 1);
        if (files.length) onFiles(files, e);
        return files.length;
    };
    let depth = 0;                                     // dragenter / dragleave fire for every child
    const show = (v) => { if (!v) depth = 0; ov.cls('is-visible', v); };
    const files = (e) => [...(e.dataTransfer?.types || [])].includes('Files');
    el.on('dragenter', (e) => { if (!files(e)) return; e.preventDefault(); if (enabled && ++depth === 1) show(true); }, on);
    el.on('dragover', (e) => { if (files(e)) e.preventDefault(); }, on);   // required to allow a drop
    el.on('dragleave', () => { if (depth && --depth === 0) show(false); }, on);
    el.on('drop', (e) => { if (!files(e)) return; e.preventDefault(); show(false); hand(e.dataTransfer.files, e); }, on);
    if (paste) document.on('paste', (e) => { if (hand(e.clipboardData?.files, e) || e.clipboardData?.files?.length) e.preventDefault(); }, on);
    let picker = null, input = null;
    if (button) {
        picker = $(button);
        const made = !picker.matches('button, [role="button"]');
        if (made) picker.attr({ role: 'button', tabindex: picker.getAttribute('tabindex') ?? 0 });
        input = $.create('input', { type: 'file', accept: accept || null, multiple, hidden: true, class: 'qry-pick' }).mount(document.body);
        const choose = (e) => { if (enabled && e.target !== input) input.click(); };
        picker.on('click', choose, on);
        picker.on('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); choose(e); } }, on);
        input.on('change', (e) => { hand(input.files, e); input.value = ''; }, on);   // the same file again is a change next time
        on.signal.addEventListener('abort', () => { input.remove(); if (made) picker.attr({ role: null, tabindex: null }); picker.attr('aria-disabled', null); });
    }
    return {
        enable(v = true) { enabled = !!v; if (!enabled) show(false); picker?.attr('aria-disabled', enabled ? null : 'true'); },
        isEnabled: () => enabled,
        destroy() { off(); show(false); if (!found) ov.remove(); },
    };
};

// ════════════════════════════════════════════════════════════════════════════
// CONSOLE — the app's log in a drawer: its own lines, console.*, the errors
// ════════════════════════════════════════════════════════════════════════════

const _LEVELS = ['debug', 'log', 'info', 'warn', 'error'];
/** @private — a level's weight: the filters and the badge count warn (3) and error (4). */
const _rank = (level) => _LEVELS.indexOf(level);
/** @private — one logged value on one line: an Error by its name and message (its stack is the line's
 *  detail), an object as compact JSON. */
const _brief = (v) => {
    if (v instanceof Error) return String(v);
    if (v !== null && typeof v === 'object') { try { return JSON.stringify(v); } catch { return String(v); } }
    return String(v);
};
/** @private — the stacks of the Errors among values, their frames only (the message is the line). */
const _frames = (values) => values.filter((v) => v instanceof Error && v.stack).map((v) => {
    const s = v.stack.startsWith(String(v)) ? v.stack.slice(String(v).length) : v.stack;     // Chromium repeats the message, the others do not
    return s.split('\n').map((l) => l.trim()).filter(Boolean).join('\n');
}).join('\n') || undefined;
/** @private — console.* and the page's uncaught errors into log(); returns what puts console.* back
 *  (unless someone patched it since). The listeners go with `on`'s signal. */
const _capture = (log, on) => {
    const patched = _LEVELS.map((level) => {
        const original = console[level], wrapper = (...args) => { original.apply(console, args); log(args.map(_brief).join(' '), { level, detail: _frames(args) }); };
        console[level] = wrapper;
        return [level, original, wrapper];
    });
    window.on('error', (e) => log(e.error ?? e.message, { level: 'error', source: e.filename ? `${e.filename.split('/').pop()}:${e.lineno}` : undefined }), on);
    window.on('unhandledrejection', (e) => log(e.reason, { level: 'error' }), on);
    return () => { for (const [level, original, wrapper] of patched) if (console[level] === wrapper) console[level] = original; };
};
/** The console's icons, by role — names of the app's set (icons.add): a role whose name is not
 *  registered shows its text instead (qry draws none). Lucide's names, the set our apps use. */
const _CONSOLE_ICONS = { console: 'terminal', all: 'list', warn: 'triangle-alert', error: 'circle-x', info: 'info', debug: 'bug', copy: 'copy', clear: 'eraser', close: 'x' };
/** @private — a time as the console shows it (24 h, seconds). */
const _clock = (ms) => new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' });

/** The console of an app — the drawer every tool of ours had its own copy of (F2, a footer button):
 *  the lines the app writes (`log`), and with `capture` what `console.*` says and every uncaught
 *  error or rejected promise. Give it an element — `<section id="console" class="qry-console" hidden>`,
 *  in a `.qry-workspace` between its body and its footer (qry-ui.css makes it a row there), or
 *  `class="qry-console floating"` anywhere: over the page at its bottom — and it is filled: a resize
 *  handle on its top edge (makeSplitter: drag, ↑/↓, double-click resets; the height in
 *  `--qry-console-h`), a head (the title — a click closes —, the count, what the element held: your
 *  own controls, then a find field, All · Warnings · Errors with their counts, Copy, Clear, ×) and the
 *  lines (`role="log"`: time · level · source · text, in aligned columns). A line is one line: an
 *  object as compact JSON, an Error by its message — its stack (or a `detail` given to `log`) folds
 *  under it (<details>). The same line again, at once, is not repeated: its row counts it (×3) and
 *  takes the last time. The lines come in once per frame, at most `max` are kept, and the view follows
 *  the end unless you scrolled up. The find field keeps the lines whose text, source or detail hold
 *  what is typed ($.fold: case and accents ignored); Escape there empties it, then closes.
 *  - `button`: its toggle(s) — `aria-expanded`, `aria-controls`, `aria-keyshortcuts` are set; while it
 *    is closed, a `.qry-console-badge` on each counts the warnings and errors (`.warn`, or `.danger`
 *    once an error came) — open, it goes.
 *  - `shortcut` (default 'F2', aria-keyshortcuts' syntax; null: none) toggles it from anywhere, a field
 *    or an open dialog included (a function key types nothing; the log is what one reads then);
 *    Escape closes it while the focus is inside — the focus goes back to its button.
 *  - `autoOpen: 'error'` (or 'warn') opens it at the first such line, once — until clear().
 *  - `onSelect(entry)`: a line logged with `data` is a button that calls it (an error's line in an editor).
 *  - `persist: 'app:console'`: open or closed, the filter and the last Clear are kept ($.store), the height under
 *    `<key>:height`. After Clear, a line dated before it is not taken again — a server's log loaded once more, by
 *    this page or (kept) the next one.
 *  - `theme`: 'dark' (default: a console reads as a terminal, whatever the page's mode — the app's
 *    accent and tones in their dark form) or 'page' (it follows the page).
 *  - `icons`: { role: name } over the defaults ({ console: 'terminal', all: 'list', warn:
 *    'triangle-alert', error: 'circle-x', info: 'info', debug: 'bug', copy: 'copy', clear: 'eraser',
 *    close: 'x' }) — names of the app's icons.add set; a role left unregistered (or set to null)
 *    shows its word, never a missing-icon square.
 *  `log`'s options: `level`, `source`, `data` (onSelect), `time` (ms since the epoch, default now: a
 *  line dated as its source did — a server's own clock), `detail` (folded under the line), `quiet`
 *  (a line that is not news — a server's past log loaded at start: no badge, no autoOpen).
 *  Copy takes the lines shown (their details indented), and says how many. destroy() puts console.*
 *  back and the element's own children.
 *  @param {string|Element} panel  the element it fills
 *  @param {{button?: string|Element|Element[], capture?: boolean, max?: number, shortcut?: string|null, autoOpen?: false|'warn'|'error', onSelect?: (entry: {time: number, level: string, text: string, source?: string, detail?: string, data?: any, count: number}) => void, persist?: string, theme?: 'dark'|'page', icons?: Object<string, string|null>}} [opts]
 *  @returns {{ element: Element, log: (text: any, opts?: {level?: 'debug'|'log'|'info'|'warn'|'error', source?: string, data?: any, time?: number, detail?: string, quiet?: boolean}) => void, clear: () => void, text: () => string, copy: () => Promise<boolean>, filter: (level?: 'all'|'warn'|'error') => string, search: (text?: string) => string, open: () => void, close: () => void, toggle: () => void, isOpen: () => boolean, destroy: () => void }}
 *  @example const con = makeConsole('#console', { button: '#console-btn', capture: true, persist: 'app:console' });
 *           con.log('Image loaded: 1920 × 1080');  con.log('No ./images folder', { level: 'warn', source: 'loader' })
 *  @example makeConsole('#console', { button: '#console-btn', onSelect: (e) => editor.goTo(e.data.line) })
 *           .log(err.message, { level: 'error', source: `L${err.line}`, data: { line: err.line } })
 *  @example for (const l of await $.load('/api/log')) con.log(l.text, { level: l.level, source: l.source, time: l.unixMs, quiet: true })
 */
export const makeConsole = (panel, { button, capture = false, max = 1000, shortcut = 'F2', autoOpen = false, onSelect, persist, theme: mode = 'dark', icons: names = {} } = {}) => {
    const box = $(panel), buttons = $.all(button), { on, off } = _scope();
    const glyph = (role) => { const n = { ..._CONSOLE_ICONS, ...names }[role]; return n && icons.has(n) ? icon(n) : null; };
    const dark = mode === 'dark' && !box.hasAttribute('data-theme');
    const store = persist ? $.store() : null, saved = store?.get(persist, null) ?? {};
    const own = [...box.childNodes];

    // ── the element: head and body ──
    if (!box.id) box.id = $.uid('qry-console');
    box.cls('+qry-console').attr('aria-label', box.attr('aria-label') || labels.console);
    if (dark) box.dataset.theme = 'dark';                          // its own ground: a terminal in any page
    const act = (name, label) => { const g = glyph(name);              // an icon button (named by its label), else its word
        return $.html`<button type="button" class="qry-console-act" data-act="${name}" ${g ? $.html`aria-label="${label}" title="${label}"` : ''}>${g ?? label}</button>`; };
    box.html($.html`<div class="qry-console-resize"></div>
        <div class="qry-console-head">
          <button type="button" class="qry-console-title">${glyph('console')}<span>${labels.console}</span></button>
          <span class="qry-console-count" hidden></span>
          <span class="qry-spacer"></span>
          <input type="search" class="qry-console-find" placeholder="${labels.find}" aria-label="${labels.find}" spellcheck="false">
          <span class="qry-console-filters" role="group" aria-label="${labels.level}">${[['all', labels.all], ['warn', labels.warnings], ['error', labels.errors]].map(([v, l]) =>
              $.html`<button type="button" class="qry-console-filter" data-filter="${v}">${glyph(v)}<span>${l}</span>${v !== 'all' && $.html`<span class="qry-console-n" data-n="${v}" hidden></span>`}</button>`)}</span>
          ${act('copy', labels.copy)}${act('clear', labels.clear)}${act('close', labels.close)}
        </div>
        <div class="qry-console-body" role="log" tabindex="0" aria-label="${box.attr('aria-label')}" data-empty="${labels.empty}"></div>`);
    const head = box.querySelector('.qry-console-head'), body = box.querySelector('.qry-console-body');
    const count = head.querySelector('.qry-console-count'), find = head.querySelector('.qry-console-find');
    head.querySelector('.qry-spacer').after(...own);              // the element's own controls: in the head
    const badges = buttons.map((b) => {
        b.attr({ 'aria-controls': box.id, 'aria-expanded': false, 'aria-keyshortcuts': shortcut || null });
        return $.create('span', { class: 'qry-console-badge', hidden: true }).mount(b);
    });

    // ── the lines: kept (entries), drawn once per frame (queue) ──
    const entries = [], entryOf = new WeakMap(), n = { warn: 0, error: 0 };
    let level = ['warn', 'error'].includes(saved.filter) ? saved.filter : 'all', query = '', cleared = saved.cleared ?? 0;
    let queue = [], frame = 0, unseen = 0, worst = 0, opened = false;
    const tally = (e, k) => { if (_rank(e.level) >= 3) n.warn += k; if (e.level === 'error') n.error += k; };
    const found = (e) => !query || $.fold(`${e.text} ${e.source ?? ''} ${e.detail ?? ''}`).includes(query);
    const shows = (e) => (level === 'all' || _rank(e.level) >= _rank(level)) && found(e);      // the level is CSS's (data-show); the find, each row's hidden
    const isOpen = () => !box.hidden;
    const keep = () => store?.set(persist, { open: isOpen(), filter: level, cleared });
    const badge = () => badges.forEach((s) => { s.text(unseen > 99 ? '99+' : String(unseen)).cls(worst > 3 ? '-warn +danger' : '+warn -danger'); s.hidden = !unseen; });
    const counted = () => {
        count.text(query ? `${entries.filter(found).length} / ${format.plural(entries.length, labels.lines)}` : format.plural(entries.length, labels.lines));
        count.hidden = !entries.length;
        head.querySelectorAll('[data-n]').forEach((s) => { const k = n[s.dataset.n]; s.text(k > 999 ? '999+' : String(k)); s.hidden = !k; });
        head.querySelectorAll('[data-act="copy"], [data-act="clear"]').forEach((b) => { b.disabled = !entries.length; });
    };
    const repeat = (e) => { const r = e.row.querySelector('.qry-console-repeat'); r.text(`×${e.count}`).hidden = false;
        const t = e.row.querySelector('time'); t.text(_clock(e.time)).attr('datetime', new Date(e.time).toISOString()); };
    const row = (e) => {
        const select = onSelect && e.data !== undefined, g = e.level === 'log' ? null : glyph(e.level);
        const said = $.html`${e.text}<span class="qry-console-repeat" hidden></span>`;
        const r = $.create(select ? 'button' : 'div', { class: 'qry-console-row', 'data-level': e.level, hidden: !found(e) });
        if (select) r.type = 'button';
        r.html($.html`<time datetime="${new Date(e.time).toISOString()}">${_clock(e.time)}</time><span class="qry-console-level${e.level !== 'log' && !g ? ' is-word' : ''}" title="${e.level}">${e.level === 'log' ? '' : g ?? e.level}${g && $.html`<span class="qry-sr-only">${e.level}</span>`}</span><span class="qry-console-source">${e.source ?? ''}</span>${
            !e.detail ? $.html`<span class="qry-console-text">${said}</span>`
            : select ? $.html`<span class="qry-console-text">${said}<span class="qry-console-detail">${e.detail}</span></span>`     // no <details> in a button
            : $.html`<details class="qry-console-text"><summary>${said}</summary><span class="qry-console-detail">${e.detail}</span></details>`}`);
        e.row = r; entryOf.set(r, e);
        if (e.count > 1) repeat(e);
        return r;
    };
    const flush = () => {                                         // once per frame: a print loop pays one layout, not one per line
        frame = 0;
        const end = body.scrollTop + body.clientHeight >= body.scrollHeight - 8;
        body.append(...queue.filter((e) => !e.gone).map(row));
        queue = [];
        counted();
        if (end) body.scrollTop = body.scrollHeight;
    };
    const show = (open) => {
        if (open === isOpen()) return;
        if (!open && box.contains(document.activeElement)) {                // the focus leaves with it: back to its button
            const b = buttons.find((x) => x.checkVisibility());
            if (b) b.focus(); else document.activeElement.blur();
        }
        box.hidden = !open;
        buttons.forEach((b) => b.attr('aria-expanded', open));
        if (open) { unseen = worst = 0; badge(); body.scrollTop = body.scrollHeight; }
        keep();
    };
    const log = (text, { level: lv = 'log', source, data, time = Date.now(), detail, quiet = false } = {}) => {
        if (time < cleared) return;                               // cleared already: a log loaded again (this page or, kept, the next)
        const e = { time, level: _LEVELS.includes(lv) ? lv : 'log', text: _brief(text), source: source ?? undefined, detail: detail ?? _frames([text]), data, count: 1 };
        const last = entries.at(-1);
        if (last && data === undefined && last.data === undefined && ['level', 'text', 'source', 'detail'].every((k) => last[k] === e[k])) {
            last.count += 1; last.time = time;                    // the same line again: counted on its row
            if (last.row) repeat(last);
        } else {
            entries.push(e); queue.push(e); tally(e, 1);
            if (entries.length > max) { const old = entries.shift(); old.gone = true; old.row?.remove(); tally(old, -1); }
            if (queue.length > max) queue = queue.slice(-max);    // a hidden tab draws no frame: the queue stays bounded
        }
        if (!frame) frame = requestAnimationFrame(flush);
        if (quiet) return;
        if (autoOpen && !opened && !isOpen() && _rank(e.level) >= _rank(autoOpen)) { opened = true; show(true); }
        if (!isOpen() && _rank(e.level) >= 3) { unseen += 1; worst = Math.max(worst, _rank(e.level)); badge(); }
    };
    const clear = () => {
        for (const e of entries) e.gone = true;
        entries.length = 0; queue = []; body.empty();
        n.warn = n.error = unseen = worst = 0; opened = false; cleared = Date.now();
        badge(); counted(); keep();
    };
    const text = () => entries.filter(shows).map((e) => {
        const line = [_clock(e.time), e.level === 'log' ? '' : e.level, e.source ?? '', e.count > 1 ? `${e.text} (×${e.count})` : e.text].filter(Boolean).join('  ');
        return e.detail ? `${line}\n${e.detail.replace(/^/gm, '    ')}` : line;
    }).join('\n');
    const filter = (...v) => {
        if (v.length && ['all', 'warn', 'error'].includes(v[0])) { level = v[0]; keep(); }
        body.dataset.show = level;
        head.querySelectorAll('[data-filter]').forEach((b) => b.attr('aria-pressed', b.dataset.filter === level));
        return level;
    };
    const search = (...v) => {
        if (v.length) {
            find.value = String(v[0] ?? ''); query = $.fold(find.value);
            for (const e of entries) if (e.row) e.row.hidden = !found(e);
            counted();
        }
        return find.value;
    };

    // ── what the user does ──
    head.on('click', (e) => {
        const b = e.target.closest('button');
        if (b?.dataset.filter) filter(b.dataset.filter);
        else if (b?.matches('.qry-console-title, [data-act="close"]')) show(false);
        else if (b?.dataset.act === 'clear') clear();
        else if (b?.dataset.act === 'copy') api.copy();
    }, on);
    find.on('input', () => search(find.value), on);
    if (onSelect) body.delegate('button.qry-console-row', 'click', function () { onSelect(entryOf.get(this)); }, on);
    box.on('keydown', (e) => {
        if (e.key !== 'Escape' || e.defaultPrevented) return;
        e.preventDefault();
        if (e.target === find && find.value) search(''); else show(false);      // the find first, then the console
    }, on);
    buttons.forEach((b) => b.on('click', () => show(!isOpen()), on));
    if (shortcut) document.on('keydown', (e) => { if (!e.defaultPrevented && _lists(shortcut, e)) { e.preventDefault(); show(!isOpen()); } }, on);
    const split = makeSplitter(box.querySelector('.qry-console-resize'), { container: box, axis: 'y', from: 'end', prop: '--qry-console-h', min: 96,
        max: () => innerHeight * 0.8, persist: persist && `${persist}:height` });
    const restore = capture ? _capture(log, on) : () => {};

    if (saved.open != null) box.hidden = !saved.open;               // kept, else as the markup has it
    buttons.forEach((b) => b.attr('aria-expanded', isOpen()));
    filter(); counted();
    const api = {
        element: box, log, clear, text, filter, search, isOpen,
        copy: () => { const t = text(), k = entries.filter(shows).length;
            return t ? copy(t, { message: `${format.plural(k, labels.lines)} · ${labels.copied}` }) : Promise.resolve(false); },
        open: () => show(true),
        close: () => show(false),
        toggle: () => show(!isOpen()),
        destroy() {
            off(); split.destroy(); cancelAnimationFrame(frame); restore();
            badges.forEach((s) => s.remove());
            if (dark) delete box.dataset.theme;
            buttons.forEach((b) => b.attr({ 'aria-controls': null, 'aria-expanded': null, 'aria-keyshortcuts': null }));
            box.empty().append(...own);
        },
    };
    return api;
};

// ════════════════════════════════════════════════════════════════════════════
// SERVER — the events a server pushes (SSE), one stream per page
// ════════════════════════════════════════════════════════════════════════════

/** ONE EventSource for the page, and what an app needs around it: `online` (a signal: null until
 *  the stream opens or fails, then true / false — the « server lost » badge binds to `=== false`), `last(name)` (a signal of the latest data of that event,
 *  JSON-parsed when it is JSON — reading it starts listening), `on(name, fn, { signal })` for
 *  each event as it comes. `seed: { name: url }` fills `last(name)` from a route at once, so a
 *  page shows a state before the first event. The browser reconnects by itself after a dropped
 *  stream (`online` follows); a server answering with an error status closes it for good (`online`
 *  stays false: make a new one);
 *  a browser opens at most 6 streams per host: one per page is the rule.
 *  @param {string} url  the stream (QryHost: host.sse(path))
 *  @param {{seed?: Object<string, string>}} [opts]
 *  @returns {{ online: {value: boolean|null}, last: (name: string) => {value: any}, on: (name: string, fn: (data: any, e: MessageEvent) => void, opts?: {signal?: AbortSignal}) => void, destroy: () => void }}
 *  @example const events = makeEvents('/api/events', { seed: { lidar: '/api/lidar/state' } });
 *           $('#offline').show(() => events.online.value === false);
 *           $('#hits').text(() => events.last('lidar').value?.hits ?? 0);
 */
export const makeEvents = (url, { seed = {} } = {}) => {
    const es = new EventSource(url), online = $.signal(null), lasts = new Map(), { on, off } = _scope();
    const parse = (t) => { try { return JSON.parse(t); } catch { return t; } };
    es.addEventListener('open', () => { online.value = true; }, on);
    es.addEventListener('error', () => { online.value = es.readyState === EventSource.OPEN; }, on);   // null until the first open or error: no « lost » flash while loading
    const last = (name) => {
        if (!lasts.has(name)) {
            const s = $.signal(null);
            lasts.set(name, s);
            es.addEventListener(name, (e) => { s.value = parse(e.data); }, on);
        }
        return lasts.get(name);
    };
    for (const [name, from] of Object.entries(seed)) {
        const s = last(name);
        $.load.json(from, { signal: on.signal }).then((v) => { if (s.peek() == null) s.value = v; }, () => { /* no state yet (or destroyed): the first event brings it */ });
    }
    return {
        online, last,
        on: (name, fn, { signal } = {}) => es.addEventListener(name, (e) => fn(parse(e.data), e), { signal: signal ? AbortSignal.any([signal, on.signal]) : on.signal }),
        destroy() { off(); es.close(); online.value = false; },
    };
};

// ════════════════════════════════════════════════════════════════════════════
// ICONS — a registry: qry draws none, the app registers its own (24 × 24, stroked)
// ════════════════════════════════════════════════════════════════════════════

const _icons = new Map(), _missing = new Set(), _watched = new WeakSet(), _drawn = new WeakMap();
/** @private — what an unknown name draws: a dashed square, seen at once (and said once in the console). */
const _MISSING = '<rect x="3.5" y="3.5" width="17" height="17" rx="2" stroke-dasharray="3 2.5"/>';
/** The app's icons, by name — ONE workflow, every app:
 *  1. its set, in one module that only registers: `icons.add({ name: drawing })` — a drawing is SVG
 *     markup for a 24 × 24 box, stroked (Lucide's convention: copy a file's inner SVG from
 *     lucide.dev as it is, its ISC licence beside the set), or a path's `d` ("M4 6h16M4 12h16");
 *  2. in markup, `<i data-icon="name"></i>` anywhere — drawn as soon as it is in the document
 *     (`boot()` starts `icons.watch()`: one MutationObserver; markup, templates, innerHTML just
 *     work), redrawn when its `data-icon` changes (play → pause); the element STAYS (its classes,
 *     its aria-label), the <svg> goes inside;
 *  3. in a string, `icon(name, opts)` ($.html-safe);
 *  4. the look is CSS: `.qry-icon` (qry-ui.css), 1.25em, stroked in currentColor, its width
 *     `--qry-icon-stroke` (2) — size it by the host's font-size or `[data-icon]`'s rules, never per
 *     drawing; a filled shape says so itself (`fill="currentColor"`).
 *  An unknown name draws a dashed square and logs ONE console error per name: a browser suite
 *  that fails on console errors (they all do) catches a typo or a forgotten registration.
 *  @example icons.add({ play: '<path d="M7 4.5v15l12.5-7.5z"/>', list: 'M4 6h16M4 12h16M4 18h16' })
 *  @example <button aria-label="Play"><i data-icon="play"></i></button>
 */
export const icons = {
    add(set) { for (const [name, drawing] of Object.entries(set)) _icons.set(name, drawing); return icons; },
    has: (name) => _icons.has(name),
    /** Draw every `[data-icon]` under root now, and each one that enters it or changes its name
     *  (boot() calls it for the document; a second call on the same root does nothing). */
    watch(root = document, { signal } = {}) {
        if (_watched.has(root) || signal?.aborted) return;
        _watched.add(root);
        const draw = (el) => { const n = el.dataset.icon; if (_drawn.get(el) !== n) { _drawn.set(el, n); el.html(icon(n)); } };
        const scan = (node) => { if (node.matches?.('[data-icon]')) draw(node); for (const el of node.querySelectorAll?.('[data-icon]') ?? []) draw(el); };
        const observer = new MutationObserver((records) => {
            for (const r of records) {
                if (r.type === 'attributes') { if (r.target.isConnected && r.target.matches('[data-icon]')) draw(r.target); }
                else for (const n of r.addedNodes) if (n.nodeType === 1) scan(n);
            }
        });
        observer.observe(root, { childList: true, subtree: true, attributes: true, attributeFilter: ['data-icon'] });
        scan(root);
        signal?.addEventListener('abort', () => { observer.disconnect(); _watched.delete(root); }, { once: true });
    },
};

/** One icon as markup ($.html-safe): an inline <svg class="qry-icon">, hidden from assistive
 *  technology unless `label` names it (a button with only an icon names the BUTTON instead).
 *  @param {string} name  registered with icons.add (an unknown name: a dashed square + one console error)
 *  @param {{size?: number|string, label?: string, class?: string}} [opts]  size: px or any CSS length (default 1.25em)
 *  @returns {SafeHTML}
 *  @example btn.html($.html`${icon('save')} Save`)
 */
export const icon = (name, { size, label, class: cls = '' } = {}) => {
    let d = _icons.get(name);
    if (d == null) {
        if (!_missing.has(name)) { _missing.add(name); console.error(`icon(${JSON.stringify(name)}) — not registered: icons.add({ ${JSON.stringify(name)}: '<path …/>' })`); }
        d = _MISSING; cls = `${cls} is-missing`;
    }
    const px = typeof size === 'number' ? `${size}px` : size;
    return $.raw(`<svg class="${$.esc(`qry-icon ${cls}`.trim())}" viewBox="0 0 24 24"${px ? ` style="width:${$.esc(px)};height:${$.esc(px)}"` : ''}${label
        ? ` role="img" aria-label="${$.esc(label)}"` : ' aria-hidden="true"'} focusable="false">${d.trimStart().startsWith('<') ? d : `<path d="${$.esc(d)}"/>`}</svg>`);
};

// ════════════════════════════════════════════════════════════════════════════
// DEVICE — an app that installs, works offline and updates itself
// ════════════════════════════════════════════════════════════════════════════

/** Make a page an installable app: register its service worker, offer each NEW version once
 *  it is ready (never a half-updated page), and give an install button what it needs.
 *  The worker is yours (it must live next to the page — a CDN cannot serve it); it only has to
 *  answer `{ type: 'SKIP_WAITING' }` with `self.skipWaiting()` — README « PWA » has a complete one.
 *  An update is offered only when a worker already controls the page (not on the first visit);
 *  accepting it activates the waiting worker and reloads once it has taken over.
 *  OFFLINE COPY (`keep`): a worker that caches what goes through it still misses what the page
 *  loaded BEFORE it took over (the first visit). With `keep`, once the page is controlled (the
 *  worker calls clients.claim()) and loaded (fonts too), those files are fetched again — through
 *  the worker — so the very first visit works offline; `keep` may add files the page has not
 *  loaded yet (its other pages, data, icons): an array of URLs, or a function returning one (even
 *  async). Later visits fetch only the page and the added files the Cache Storage lacks.
 *  `onKeep(done, total)` follows the progress; `kept` resolves with how many were fetched (0 after
 *  destroy()). The browser keeps 250 resource entries by default: a first visit loading more calls
 *  `performance.setResourceTimingBufferSize(n)` early, in the page.
 *  @param {Object} [opts]
 *  @param {string} [opts.sw='sw.js']   the worker's URL (relative to the page)
 *  @param {string} [opts.scope]        its scope (default: the worker's folder)
 *  @param {(apply: () => void) => void} [opts.onUpdate]  a new version waits: call `apply()` to
 *                                      switch (default: confirm(labels.update) → apply)
 *  @param {boolean|string[]|(() => string[]|Promise<string[]>)} [opts.keep]  the offline copy (above)
 *  @param {(done: number, total: number) => void} [opts.onKeep]
 *  @param {boolean} [opts.install=true]  false leaves the browser's own install offer alone (no button of yours)
 *  @returns {{ installable: Signal<boolean>, isStandalone: () => boolean, install: () => Promise<boolean>,
 *              update: () => Promise<void>, ready: Promise<ServiceWorkerRegistration|null>,
 *              kept: Promise<number>, isControlled: () => boolean, destroy: () => void }}
 *  `installable` turns true when the browser offers installation (Chromium's beforeinstallprompt;
 *  Safari installs from its Share menu, so it stays false there) — bind a button to it.
 *  @example const pwa = makePwa(); $('#install').show(pwa.installable).on('click', () => pwa.install());
 */
export const makePwa = ({ sw = 'sw.js', scope, onUpdate, keep, onKeep, install = true } = {}) => {
    const { on, off, signal } = _scope();
    const installable = $.signal(false);
    let offer = null, applying = false;
    if (install) window.on('beforeinstallprompt', (e) => { e.preventDefault(); offer = e; installable.value = true; }, on);   // false: the browser's own install UI
    window.on('appinstalled', () => { offer = null; installable.value = false; }, on);
    const sws = navigator.serviceWorker;
    const propose = onUpdate ?? ((apply) => confirm(labels.update, { ok: labels.reload }).then((yes) => yes && apply()));
    const offerUpdate = (reg) => {
        if (signal.aborted || !reg.waiting || !sws.controller) return;
        propose(() => { applying = true; reg.waiting?.postMessage({ type: 'SKIP_WAITING' }); });
    };
    sws?.addEventListener('controllerchange', () => { if (applying) location.reload(); }, on);
    const ready = !sws ? Promise.resolve(null) : sws.register(sw, scope ? { scope } : undefined).then((reg) => {
        offerUpdate(reg);                                             // a worker already waiting (an earlier visit)
        reg.addEventListener('updatefound', () => {
            const next = reg.installing;
            next?.addEventListener('statechange', () => { if (next.state === 'installed') offerUpdate(reg); }, on);
        }, on);
        return reg;
    }, (e) => { console.warn('makePwa: the service worker did not register', e); return null; });
    const first = !sws?.controller;                                   // its files came before the worker
    const kept = !keep || !sws ? Promise.resolve(0) : (async () => {
        if (!(await ready)) return 0;
        // a wait that ends with destroy() too: `kept` always settles
        const until = (target, type) => new Promise((ok) => { target.addEventListener(type, ok, { once: true, signal }); signal.addEventListener('abort', ok, { once: true }); });
        if (!sws.controller) await until(sws, 'controllerchange');
        if (document.readyState !== 'complete') await until(window, 'load');
        await document.fonts?.ready;
        if (signal.aborted) return 0;
        const worker = new URL(sw, location.href).pathname;
        const own = (u) => { try { const x = new URL(u, location.href); return x.origin === location.origin && x.pathname !== worker ? x.href.split('#')[0] : null; } catch { return null; } };
        const extra = (Array.isArray(keep) ? keep : typeof keep === 'function' ? (await keep()) ?? [] : []).map(own).filter(Boolean);
        // what the page LOADED (scripts, styles, images, fonts…) — never its API calls (fetch, XHR, beacons: a POST must not be
        // replayed as a GET) nor its media (a recording is fetched whole for nothing; list it in `keep` to keep it)
        const loaded = first ? performance.getEntriesByType('resource').filter((e) => !/^(fetch|xmlhttprequest|beacon|audio|video)$/.test(e.initiatorType))
            .map((e) => own(e.name)).filter(Boolean) : [];
        let urls = [...new Set([own(location.href), ...loaded, ...extra])];
        if (!first && globalThis.caches) urls = [urls[0], ...(await Promise.all(urls.slice(1).map(async (u) => ((await caches.match(u)) ? null : u)))).filter(Boolean)];
        let done = 0, got = 0;
        for (const u of urls) {
            if (signal.aborted) break;
            if (await fetch(u, { cache: 'no-cache' }).then((r) => r.ok, () => false)) got += 1;   // offline already: keep going
            onKeep?.(++done, urls.length);
        }
        return got;
    })().catch(() => 0);
    return {
        kept,
        isControlled: () => !!sws?.controller,
        installable,
        isStandalone: () => matchMedia('(display-mode: standalone)').matches || navigator.standalone === true,
        async install() {
            if (!offer) return false;
            const o = offer; offer = null; installable.value = false;
            await o.prompt();
            return (await o.userChoice).outcome === 'accepted';
        },
        update: async () => { await (await ready)?.update(); },
        ready,
        destroy: off,
    };
};

// ════════════════════════════════════════════════════════════════════════════
// TABLES — sortable tables, chips, label / value rows
// ════════════════════════════════════════════════════════════════════════════

/** A label / value line (`.qry-info-row`). Each may be text or a Node (a chip, a link…).
 *  @param {string|Node} label @param {string|number|Node} value
 *  @param {{tone?: 'ok'|'warn'|'danger'|'purple'|'accent', class?: string}} [opts]  a tone colours the value; class goes on the row
 *  @returns {Element}
 *  @example meta.add(infoRow('Pages', doc.pages.length))   meta.add(infoRow('Treadmill', 'ok', { tone: 'ok' }))
 */
export const infoRow = (label, value, { tone = '', class: cls = '' } = {}) => $.create('div', { class: `qry-info-row ${cls}`.trim() },
    $.create('span', { class: 'qry-info-label' }, label ?? ''),
    $.create('span', { class: `qry-info-value ${tone}`.trim() }, value ?? ''));

/** A wrap of toggle buttons (`.qry-chip`) bound to a signal — filters, a choice among a few: the chips
 *  are built from `items`, the choice is makeChoice's (a click sets `value`, aria-pressed follows; an
 *  ARRAY signal = several chips at once). Items are strings or objects; `key` and `label` are accessors
 *  (a function or a property name; default: the item's key|name|label, else the item itself); a label
 *  may be a Node (a colour dot and a name; text stays text). `name` labels the group.
 *  @param {Array} items
 *  @param {{value: *}} value   a signal: the key chosen (or the array of keys)
 *  @param {{key?: string|Function, label?: string|Function, name?: string, class?: string}} [opts]
 *  @returns {{ element: Element, destroy: () => void }}
 *  @example const format = $.signal('pdf'); box.add(makeChips(formats, format, { name: 'Format' }).element)
 */
export const makeChips = (items, value, { key = (i) => i?.key ?? i?.name ?? i?.label ?? i, label = (i) => i?.label ?? i?.name ?? i, name, class: cls = '' } = {}) => {
    const keyOf = _acc(key), labelOf = _acc(label);
    const element = $.create('div', { class: `qry-chips ${cls}`.trim(), 'aria-label': name ?? null },
        ...items.map((item) => $.create('button', { type: 'button', class: 'qry-chip', 'data-value': keyOf(item) }, labelOf(item))));
    const choice = makeChoice(element, value);
    return { element, destroy() { choice.destroy(); element.remove(); } };
};

/** @private — scroll `el` into view inside its nearest scrolling ancestor only (never the page). */
const _inView = (el) => {
    let box = el.parentElement;
    while (box && box !== document.body && !/(auto|scroll)/.test(getComputedStyle(box).overflowY)) box = box.parentElement;
    if (!box || box === document.body) return;
    const r = el.getBoundingClientRect(), b = box.getBoundingClientRect(), head = el.closest('table')?.tHead?.offsetHeight ?? 0;
    if (r.top < b.top + head) box.scrollTop -= b.top + head - r.top;    // under a sticky header: below it
    else if (r.bottom > b.bottom) box.scrollTop += r.bottom - b.bottom;
};

/** A sortable table (`.qry-table`). Each header is a button (click or keyboard) that sorts by its
 *  column; a `numeric` column sorts numerically and aligns right. Values are escaped; a column's
 *  `format(value, row)` returns text (escaped too) or $.html`…`. Rows that `onRow` activates are
 *  focusable buttons in the row sense: a click, Enter or Space. `update(rows)` and
 *  `sortBy(key, dir?)` refresh in place. `sort: false` keeps the rows in the order given (a log, the
 *  trials of a session: plain headers). `current(row)` marks the row that is current (aria-current) and
 *  keeps it in view inside the table's own scroll box — never scrolling the page.
 *  @param {Array<{key:string, label:string, numeric?:boolean, format?:(v:*, row:object)=>string}>} columns
 *  @param {Array<object>} rows
 *  @param {Object} [opts]
 *  @param {boolean} [opts.numbered]  a 1-based column first (header labels.position)
 *  @param {{key:string, dir:'asc'|'desc'}|false} [opts.sort]  default: the first numeric column desc, else the first column asc; false: as given
 *  @param {(row:object)=>boolean} [opts.current]  the current row (aria-current="true", kept in view)
 *  @param {(row:object, tr:Element)=>void} [opts.onRow]  body-row activation (click, Enter or Space on the focused row)
 *  @param {string} [opts.empty=labels.empty]
 *  @param {string} [opts.class]  extra class on the table
 *  @returns {{ element: HTMLTableElement, update: (rows: object[]) => void, sortBy: (key: string, dir?: string) => void, destroy: () => void }}
 *  @example const t = makeTable(columns, fonts, { numbered: true, onRow: (f) => pick(f) }); box.add(t.element); t.update(next)
 */
export const makeTable = (columns, rows, { numbered = false, sort, current, onRow, empty = labels.empty, class: cls = '' } = {}) => {
    const col = (k) => columns.find((c) => c.key === k);
    const first = columns.find((c) => c.numeric) ?? columns[0], sorts = sort !== false;
    const state = sort ? { ...sort } : { key: sorts ? first.key : null, dir: first.numeric ? 'desc' : 'asc' };
    const { on, off } = _scope();
    let data = [...(rows ?? [])], view = [];           // view: the sorted rows, index-aligned to the body <tr>s
    const element = $.create('table', { class: `qry-table ${cls}`.trim() });
    const cmp = (a, b) => (col(state.key)?.numeric
        ? (+a[state.key] || 0) - (+b[state.key] || 0)
        : String(a[state.key] ?? '').localeCompare(String(b[state.key] ?? '')));
    const sorted = (c) => (c.key !== state.key ? 'none' : state.dir === 'asc' ? 'ascending' : 'descending');
    const arrow = (c) => c.key === state.key && $.html` <span aria-hidden="true">${state.dir === 'asc' ? '▲' : '▼'}</span>`;   // aria-sort says it
    const cell = (c, r) => (c.format ? c.format(r[c.key], r) : r[c.key] ?? labels.missing);
    const render = () => {
        view = sorts ? data.toSorted((a, b) => (state.dir === 'asc' ? cmp(a, b) : cmp(b, a))) : data;
        const num = (c) => (c.numeric ? 'qry-num' : '');
        const head = (c) => (sorts ? $.html`<th scope="col" class="${num(c)}" aria-sort="${sorted(c)}"><button type="button" data-key="${c.key}">${c.label}${arrow(c)}</button></th>`
            : $.html`<th scope="col" class="${num(c)}">${c.label}</th>`);
        element.html($.html`<thead><tr>${numbered && $.html`<th scope="col" class="qry-num qry-idx">${labels.position}</th>`}${columns.map(head)}</tr></thead>
            <tbody>${view.length ? view.map((r, i) => $.html`<tr data-i="${i}"${onRow && $.raw(' tabindex="0"')}${current?.(r) && $.raw(' aria-current="true"')}>${numbered && $.html`<td class="qry-num qry-idx">${i + 1}</td>`}${columns.map((c) =>
                $.html`<td class="${num(c)}">${cell(c, r)}</td>`)}</tr>`)
            : $.html`<tr class="qry-table-empty"><td colspan="${columns.length + (numbered ? 1 : 0)}">${empty}</td></tr>`}</tbody>`);
        const at = current && element.querySelector('tbody tr[aria-current]');
        if (at) _inView(at);
    };
    element.delegate('th button[data-key]', 'click', function () {
        const k = this.dataset.key;
        if (state.key === k) state.dir = state.dir === 'asc' ? 'desc' : 'asc';
        else Object.assign(state, { key: k, dir: col(k).numeric ? 'desc' : 'asc' });
        render();
        element.querySelector(`th button[data-key="${CSS.escape(k)}"]`).focus();
    }, on);
    if (onRow) {
        element.delegate('tbody tr[data-i]', 'click', function () { onRow(view[+this.dataset.i], this); }, on);
        element.delegate('tbody tr[data-i]', 'keydown', function (e) {
            if ((e.key !== 'Enter' && e.key !== ' ') || e.target !== this) return;   // a link or a button in a cell keeps its keys
            e.preventDefault();                          // Space would scroll the page
            onRow(view[+this.dataset.i], this);
        }, on);
    }
    render();
    return {
        element,
        update(next) { data = [...(next ?? [])]; render(); },
        sortBy(key, dir) { state.key = key; if (dir) state.dir = dir; render(); },
        destroy() { off(); element.remove(); },
    };
};
