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
 *   LAYOUT      makeTabs · makeMenu · makeSplitter · makeSidebar · makeAutoHideHeader · makeZoomPan · makeIdle
 *   CANVAS      makeCanvas (sharp, themed, redrawn) · makeInk (freehand: signatures, notes)
 *   CHARTS      makeChart (dots, lines, bars, rules — plain SVG in the theme's tokens)
 *   INPUT       makeKeyboard · makeSlider (a custom slider's keys + ARIA) · makeDropZone (drop, paste and choose)
 *   SERVER      makeEvents (one SSE stream: online, last(name), on(name))
 *   ICONS       icons.add · icon · icons.render   (qry draws none: the app registers its own)
 *   DEVICE      makeWakeLock · makePwa (service worker, updates, install, offline copy)
 *   TABLES      makeTable · makeChips · infoRow
 *
 * Requires qry.js 3.x (global `$`) and qry-web.js beside this file.
 *
 * @version 3.0.0
 * @author  Jean-Luc Bloechle with Claude.ai
 * @license MIT
 */
import { labels } from './qry-web.js';

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

let _dialogs = 0;
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
    const id = `qry-dlg-${_dialogs += 1}`, back = document.activeElement;
    const dlg = $.create('dialog', { class: `qry-dialog ${cls}`.trim(), role: role === 'dialog' ? null : role, 'aria-describedby': `${id}-body`,
        'aria-labelledby': title ? `${id}-title` : `${id}-body` });
    dlg.html($.html`${title && $.html`<h2 class="qry-dialog-title" id="${id}-title">${title}</h2>`}<div class="qry-dialog-body" id="${id}-body"></div>
        <div class="qry-dialog-actions">${buttons.map((b, i) => $.html`<button type="button" class="qry-btn ${b.tone}" data-act="${i}">${b.label}</button>`)}</div>`);
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
        running = true; btn.attr('aria-busy', 'true');
        try { const v = await b.value(); error.hidden = true; done(v); }
        catch (err) { error.text(err?.message || String(err)).hidden = false; }      // stays open: say why
        finally { running = false; btn.attr('aria-busy', null); }
    });
    dlg.on('cancel', (e) => { e.preventDefault(); if (dismissible && !running) done(dismiss); });     // Escape
    dlg.on('close', () => {
        if (!dismissible && !answered) return dlg.showModal();          // closed by the browser itself: again
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
 *  @param {string} msg  plain text
 *  @param {{tone?: string, duration?: number}} [opts]  duration in ms (default 3000); 0 = until closed
 *  @returns {{ element: Element, update: (msg: string) => void, close: () => void }}
 *  @example toast('Saved', { tone: 'ok' })
 *  @example const t = toast('Packing…', { duration: 0 }); … t.update('Packing 3 / 10'); … t.close()
 */
export const toast = (msg, { tone = '', duration = 3000 } = {}) => {
    let host = document.body;
    try { host = [...document.querySelectorAll('dialog:modal')].at(-1) ?? host; } catch { /* no :modal here: the page */ }
    const stack = host.querySelector(':scope > .qry-toasts') ?? $.create('div', { class: 'qry-toasts' }).mount(host);
    const text = $.create('span', { class: 'qry-toast-text', role: tone === 'danger' ? 'alert' : 'status' });
    let current = msg;
    const t = $.create('div', { class: `qry-toast ${tone}`.trim() }, text,
        $.create('button', { type: 'button', class: 'qry-toast-close', 'aria-label': labels.dismiss, text: '×' }));
    let timer = 0, closed = false;
    const close = () => { closed = true; clearTimeout(timer); t.cls('+is-leaving'); setTimeout(() => t.remove(), $.reduced ? 0 : $.motion()); };
    t.on('click', close);
    stack.add(t);
    requestAnimationFrame(() => text.text(current));                     // filled once in the live region: announced
    if (host !== document.body) host.on('close', () => {                  // the dialog closes: the toast outlives it
        if (!closed) (document.body.querySelector(':scope > .qry-toasts') ?? $.create('div', { class: 'qry-toasts' }).mount(document.body)).add(t);
    }, { once: true });
    if (duration) timer = setTimeout(close, duration);
    return { element: t, update: (m) => { current = m; text.text(m); }, close };
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

/** @private — the OS colour scheme, asked lazily (importing the module touches nothing). */
let _osQuery = null;
const _os = () => (_osQuery ??= matchMedia('(prefers-color-scheme: dark)'));

/** Light / dark / system, through ONE hook: `data-theme` on <html> (qry-ui.css turns it
 *  into the native color-scheme; without it, the OS decides). `mode()` reads the choice, `mode(m)`
 *  makes it; it is persisted (as JSON, under 'qry:theme' or init's `persist` key); 'system' removes
 *  the attribute and follows the OS live. Every change — mode(m), or the OS while on 'system' —
 *  puts EVERY `<meta name="theme-color">` (media-scoped ones included) on the page ground
 *  (`ground`, default --qry-bg) — never throwing — and fires `qry:theme` on <html> with { mode, dark }.
 *  A closure: its functions can be handed around (`btn.on('click', theme.toggle)`).
 *  No flash before the module runs: in <head>, after the stylesheet and the theme-color meta
 *  (same key, same ground token) — the README has it.
 *  @example theme.init(); theme.toggle(); theme.mode('system'); theme.mode() → 'system'; theme.isDark()
 *  @example theme.init({ persist: 'floop:theme', ground: '--floop-paper' })   // the app's own key and ground
 */
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
    const isDark = () => {
        const t = document.documentElement.dataset.theme;
        return t ? t === 'dark' : _os().matches;
    };
    /** @param {'light'|'dark'|'system'} [m]  without it: the saved choice ('system' by default) */
    const mode = (...m) => {
        if (!m.length) return $.store().get(key, 'system');
        const html = document.documentElement;
        html.attr('data-theme', m[0] === 'system' ? null : m[0]);
        $.store().set(key, m[0]);
        bar();
        html.trigger('qry:theme', { mode: m[0], dark: isDark() });
        return m[0];
    };
    return {
        mode, isDark,
        toggle: () => mode(isDark() ? 'light' : 'dark'),
        /** Apply the saved choice (default: follow the OS), and follow the OS while on 'system'.
         *  @param {{persist?: string, ground?: string}} [opts]  persist: the localStorage key of the
         *  choice (default 'qry:theme'); ground: the token (or CSS colour) of the page ground the
         *  browser bar takes (default '--qry-bg' — an app not on qry-ui.css names its own) */
        init({ persist = 'qry:theme', ground = '--qry-bg' } = {}) {
            key = persist; groundToken = ground;
            if (!watching) { watching = true; _os().addEventListener('change', () => { if (mode() === 'system') mode('system'); }); }
            return mode(mode());
        },
    };
})();

/** One-call convenience for the common opening sequence:
 *  set the title → apply the saved theme → run `ready` on DOM-ready.
 *  Every step is also available standalone — use this only if it saves typing.
 *  @param {Object}   [opts]
 *  @param {Function} [opts.ready]        Run once the DOM is ready
 *  @param {boolean|{persist?: string, ground?: string}} [opts.theme=true]   Apply persisted/system theme (an object: theme.init's options)
 *  @param {string}   [opts.title]        Sets document.title if given
 *  @example boot({ title: 'Gallery', ready: () => wire() })
 */
export const boot = ({ ready, theme: useTheme = true, title } = {}) => {
    if (title) document.title = title;
    if (useTheme) theme.init(useTheme === true ? {} : useTheme);
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
        try {
            return await write(await window.showSaveFilePicker({ suggestedName: name, types: _types(ext), ...(startIn && { startIn }), ...(id && { id }) }));
        } catch (e) {
            // the user cancelled — unless the "cancel" came back faster than any hand: the dialog was
            // never SHOWN (a headless or locked-down browser answers AbortError at once) → the other way
            if (e?.name === 'AbortError' && performance.now() - asked > _NOT_SHOWN) return null;
        }                                                     // refused here: the other way
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
            { method: dv.getUint16(p + 10, true), size: dv.getUint32(p + 20, true), offset: dv.getUint32(p + 42, true) });
        p += 46 + len + dv.getUint16(p + 30, true) + dv.getUint16(p + 32, true);
    }
    const read = async (name) => {
        const e = entries.get(name);
        if (!e) return null;
        if (e.size === 0xFFFFFFFF) throw new Error('zip64 archives are not supported');
        const start = e.offset + 30 + dv.getUint16(e.offset + 26, true) + dv.getUint16(e.offset + 28, true);
        const raw = u8.subarray(start, start + e.size);
        if (e.method === 0) return raw;
        if (e.method !== 8) throw new Error(`unsupported zip compression (${e.method})`);
        return new Uint8Array(await new Response(new Blob([raw]).stream().pipeThrough(new DecompressionStream('deflate-raw'))).arrayBuffer());
    };
    return { names: [...entries.keys()], read };
};

/** @private — an .xlsx (bytes) → its FIRST worksheet as TSV text, one line per sheet row (empty
 *  rows kept, so line n is row n), cells quoted when they hold a tab, a newline or a quote.
 *  Values as Excel stores them: numbers and dates as numbers (a date is a serial day). */
const _xlsx = async (u8) => {
    const { read } = _unzip(u8), utf8 = new TextDecoder();
    const doc = async (name) => { const b = await read(name); return b && new DOMParser().parseFromString(utf8.decode(b), 'application/xml'); };
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
            const type = c.getAttribute('t'), v = tags(c, 'v')[0]?.textContent ?? '';
            cells[col(c.getAttribute('r') || '')] = type === 's' ? shared[+v] ?? ''
                : type === 'inlineStr' ? tags(c, 't').map((t) => t.textContent).join('')
                : type === 'b' ? (v === '1' ? 'TRUE' : 'FALSE') : v;
        }
        lines[(+row.getAttribute('r') || lines.length + 1) - 1] = Array.from(cells, (t) => cell(t ?? '')).join('\t');
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
            t.attr({ 'aria-selected': String(sel), tabindex: sel ? '0' : '-1' });
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
            const j = { ArrowRight: i + 1, ArrowDown: i + 1, ArrowLeft: i - 1 + n, ArrowUp: i - 1 + n, Home: 0, End: n - 1 }[e.key];
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
 *  The handle is a focusable separator: ←/→ (↑/↓ on the y axis) move it by 16 px.
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
        return sign > 0 ? p - r[y ? 'top' : 'left'] : r[y ? 'bottom' : 'right'] - p;
    };
    el.attr({ role: 'separator', tabindex: '0', 'aria-orientation': y ? 'horizontal' : 'vertical', 'aria-valuemin': min });
    el.onDrag(({ phase, event }) => {
        if (phase === 'start') { el.cls('+is-dragging'); onStart?.(current() ?? at(event)); }
        else if (phase === 'move') write(at(event));
        else { el.cls('-is-dragging'); onEnd(current()); }
    }, on);
    el.on('keydown', (e) => {
        const d = { ArrowLeft: -16, ArrowRight: 16, ArrowUp: -16, ArrowDown: 16 }[e.key];
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
    const el = $(sidebar), ov = $.opt(overlay), btn = $.opt(button), small = matchMedia('(max-width: 768px)');
    const { on, off } = _scope();
    const isExpanded = () => (small.matches ? el.cls('?is-open') : !el.cls('?is-collapsed'));
    let last = null;
    const expanded = () => {
        const v = isExpanded();
        btn?.attr('aria-expanded', String(v));
        if (v !== last) { const first = last === null; last = v; if (!first) onChange?.(v); }
    };
    const open = () => { el.cls('+is-open'); ov?.cls('+is-visible'); expanded(); };
    const close = () => { el.cls('-is-open'); ov?.cls('-is-visible'); expanded(); };
    const toggle = () => { if (!small.matches) { el.cls('~is-collapsed'); expanded(); } else if (el.cls('?is-open')) close(); else open(); };
    ov?.on('click', close, on);
    btn?.on('click', toggle, on);
    document.on('keydown', (e) => { if (e.key === 'Escape' && el.cls('?is-open')) close(); }, on);
    small.addEventListener('change', close, on);                      // crossing the breakpoint: a clean state
    expanded();
    return { open, close, toggle, isExpanded, destroy: off };
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

/** Pan + zoom a large content element inside a scrollable stage (`.qry-stage`).
 *  Zoom sets the content's CSS width in % (100% = fit at zoom 1), so the stage's own
 *  scrollbars track the overflow; the content can be a div, an <img> or a <canvas>.
 *  Ctrl/⌘ + wheel zooms toward the cursor (plain wheel with `wheelModifier: false`), a drag
 *  pans, two fingers pinch, a double-click toggles zoom; drags starting on an interactive
 *  child (`noPan`) are left alone. The content is resolved live (it may be swapped).
 *  @param {string|Element} stage  the scroll container
 *  @param {Object} [opts]
 *  @param {string|Element} [opts.content]  zoom target (default: the stage's first element child)
 *  @param {number} [opts.min=0.25] @param {number} [opts.max=8]
 *  @param {number} [opts.step=1.25]  button / dblclick factor
 *  @param {number} [opts.wheelStep=1.1]  wheel factor per notch
 *  @param {boolean}[opts.wheelModifier=true]  require Ctrl/⌘ for wheel-zoom
 *  @param {boolean}[opts.doubleClick=true]  a double-click toggles the zoom
 *  @param {string} [opts.noPan]  selector of children that do NOT start a pan
 *  @param {Function} [opts.onZoom]  (zoom:number) → void
 *  @param {Function} [opts.onPan]   (scrollLeft:number, scrollTop:number) → void
 *  @returns {{ zoom:(z?:number)=>number, zoomIn, zoomOut, zoomAt:(factor, clientX?, clientY?)=>void, fit, destroy }}   zoom() reads, zoom(z) sets
 *  (A pinch that ends with one finger still down continues as a pan from there.)
 *  @example const view = makeZoomPan('#stage', { content: '#canvas', onZoom: () => scheduleRender() })
 */
export const makeZoomPan = (stage, {
    content, min = 0.25, max = 8, step = 1.25, wheelStep = 1.1, wheelModifier = true, doubleClick = true,
    noPan = 'a,button,input,select,textarea,label,[contenteditable],[data-no-pan]',
    onZoom, onPan,
} = {}) => {
    const s = $(stage);
    const { on, off } = _scope();
    const target = () => (content ? $(content) : s.firstElementChild);
    let z = 1;
    const apply = () => target()?.css('width', z * 100 + '%');
    const set = (nz) => { const prev = z; z = $.clamp(nz, min, max); apply(); onZoom?.(z); return z / prev; };
    const zoomAt = (factor, cx, cy) => {               // keep the viewport point (cx, cy) fixed
        const r = s.getBoundingClientRect();
        if (cx == null) { cx = r.left + s.clientWidth / 2; cy = r.top + s.clientHeight / 2; }
        const x = s.scrollLeft + (cx - r.left), y = s.scrollTop + (cy - r.top);
        const ratio = set(z * factor);
        s.scrollLeft = x * ratio - (cx - r.left);
        s.scrollTop = y * ratio - (cy - r.top);
    };
    s.on('wheel', (e) => {
        if (wheelModifier && !(e.ctrlKey || e.metaKey)) return;
        e.preventDefault();
        zoomAt(e.deltaY < 0 ? wheelStep : 1 / wheelStep, e.clientX, e.clientY);
    }, { ...on, passive: false });
    // one pointer registry drives both the one-pointer pan and the two-pointer pinch
    const pts = new Map();
    let sx = 0, sy = 0, sl = 0, st = 0, pinch = 0;
    const panFrom = (e) => { sx = e.clientX; sy = e.clientY; sl = s.scrollLeft; st = s.scrollTop; };
    const pair = () => [...pts.values()];
    const dist = () => { const [a, b] = pair(); return Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY); };
    s.on('pointerdown', (e) => {
        if ((e.pointerType === 'mouse' && e.button !== 0) || e.target.closest?.(noPan)) return;
        pts.set(e.pointerId, e);
        s.setPointerCapture(e.pointerId);
        if (pts.size === 2) { pinch = dist(); s.cls('-is-grabbing'); }
        else { panFrom(e); s.cls('+is-grabbing'); }
    }, on);
    s.on('pointermove', (e) => {
        if (!pts.has(e.pointerId)) return;
        pts.set(e.pointerId, e);
        if (pts.size >= 2) {
            const d = dist(), [a, b] = pair();
            if (pinch) zoomAt(d / pinch, (a.clientX + b.clientX) / 2, (a.clientY + b.clientY) / 2);
            pinch = d;
        } else {
            s.scrollLeft = sl - (e.clientX - sx); s.scrollTop = st - (e.clientY - sy);
            onPan?.(s.scrollLeft, s.scrollTop);
        }
    }, on);
    s.on('pointerup pointercancel', (e) => {
        pts.delete(e.pointerId);
        if (pts.size < 2) pinch = 0;
        if (pts.size === 1) panFrom(pair()[0]);                    // a pinch ending in one finger: pan from here
        if (!pts.size) s.cls('-is-grabbing');
    }, on);
    s.on('dblclick', (e) => { if (doubleClick && !e.target.closest?.(noPan)) zoomAt(z > 1 ? 1 / z : step * step, e.clientX, e.clientY); }, on);
    apply();
    return {
        zoom: (...v) => { if (v.length) set(v[0]); return z; },
        zoomIn: () => zoomAt(step),
        zoomOut: () => zoomAt(1 / step),
        zoomAt,
        fit: () => { set(1); s.scrollLeft = s.scrollTop = 0; },
        destroy: off,
    };
};

let _anchors = 0;
/** A menu on the native popover: `<button popovertarget="m">⋯</button>` +
 *  `<div id="m" popover class="qry-menu"><button data-value="copy">Copy</button><hr>…</div>`.
 *  The browser already opens, closes, light-dismisses and handles Escape; qry-ui.css places it
 *  under its button with CSS anchor positioning (flipping at the viewport edges). makeMenu adds
 *  what a menu needs on top: an explicit anchor, the ARIA menu pattern (roles, aria-haspopup,
 *  aria-expanded), focus on the first item, ↑/↓/Home/End between items, Tab closes, a click
 *  on an item calls onSelect and closes. Items can be added later: they are read on open.
 *  Without makeMenu, `.qry-menu` / `.qry-popover` still open and anchor (implicit anchor).
 *  @param {string|Element} menu  the popover element (it needs an id)
 *  @param {Object} [opts]
 *  @param {string|Element} [opts.button]  its trigger (default: the `[popovertarget=<id>]` button)
 *  @param {(value:string, item:Element)=>void} [opts.onSelect]  value = data-value, else the text
 *  @returns {{ open:()=>void, close:()=>void, toggle:()=>void, isOpen:()=>boolean, destroy:()=>void }}
 *  @example makeMenu('#more', { onSelect: (v) => v === 'export' && exportCsv() })
 */
export const makeMenu = (menu, { button, onSelect } = {}) => {
    const m = $(menu);
    const b = $.opt(button ?? `[popovertarget="${CSS.escape(m.id)}"]`);
    const { on, off } = _scope();
    const anchor = `--qry-anchor-${_anchors += 1}`;
    if (!m.hasAttribute('popover')) m.attr('popover', '');
    m.attr('role', 'menu').css('position-anchor', anchor);
    b?.attr({ popovertarget: m.id, 'aria-haspopup': 'menu', 'aria-expanded': 'false' }).css('anchor-name', anchor);
    const items = () => m.find(':scope > :is(button, a, [role="menuitem"])').filter((i) => !i.disabled && !i.hidden);
    const isOpen = () => m.matches(':popover-open');
    m.on('beforetoggle', (e) => {                         // synchronous: ARIA is right before anyone looks
        const open = e.newState === 'open';
        b?.attr('aria-expanded', String(open));
        if (open) items().forEach((i) => i.attr({ role: 'menuitem', tabindex: '-1' }));
        else if (m.contains(document.activeElement)) b?.focus();
    }, on);
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
        m.hidePopover();
    }, on);
    return {
        open: () => { if (!isOpen()) m.showPopover(); },
        close: () => { if (isOpen()) m.hidePopover(); },
        toggle: () => m.togglePopover(),
        isOpen,
        destroy: off,
    };
};

/** Chrome that steps aside while nobody moves: after `after` ms without a pointer move, a key or a
 *  touch, `el` gets the class `is-idle` (your CSS fades the bar, hides the cursor) and `onIdle`
 *  runs; the next move takes it away (`onActive`). Never idle while `busy()` says so (a menu open, a
 *  drag) or while the keyboard's focus is inside `el` (a button just clicked does not hold it).
 *  `keys: false` follows the pointer alone: a key neither wakes it nor holds it (chrome over a
 *  stimulus that the keyboard drives). `wake(ms)` shows the chrome now, for `ms` at least.
 *  @param {string|Element} el  what goes idle (often the page, or a player)
 *  @param {{after?: number, keys?: boolean, busy?: () => boolean, onIdle?: Function, onActive?: Function}} [opts]
 *  @returns {{ wake: (ms?: number) => void, isIdle: () => boolean, destroy: () => void }}
 *  @example makeIdle('#stage', { after: 2500, busy: () => menu.isOpen() })   // CSS: #stage.is-idle .bar { opacity: 0 }
 */
export const makeIdle = (el, { after = 2500, keys = true, busy, onIdle, onActive } = {}) => {
    const box = $(el), { on, off } = _scope();
    let timer = 0, until = 0;
    const idle = () => {
        if (busy?.() || (keys && box.querySelector(':focus-visible')) || Date.now() < until) return arm(Math.max(after / 4, until - Date.now()));   // the KEYBOARD's focus holds it (a clicked Play does not)
        if (!box.cls('?is-idle')) { box.cls('+is-idle'); onIdle?.(); }
    };
    const arm = (ms) => { clearTimeout(timer); timer = setTimeout(idle, ms); };
    const wake = (ms = after) => { if (ms > after) until = Date.now() + ms; if (box.cls('?is-idle')) { box.cls('-is-idle'); onActive?.(); } arm(Math.max(ms, after)); };
    for (const type of ['pointermove', 'pointerdown', 'wheel', ...(keys ? ['keydown'] : [])]) document.on(type, () => wake(), { ...on, passive: true });
    arm(after);
    return { wake, isIdle: () => box.cls('?is-idle'), destroy() { off(); clearTimeout(timer); box.cls('-is-idle'); } };
};

// ════════════════════════════════════════════════════════════════════════════
// CANVAS — sharp at any pixel density, in the theme's colours, redrawn when needed
// ════════════════════════════════════════════════════════════════════════════

/** A canvas that stays sharp and in the theme: its bitmap follows its CSS box ×
 *  devicePixelRatio, and `draw(ctx, { width, height, dpr, color })` runs again on resize, on a
 *  theme change (qry:theme, the OS) and on redraw() — once per frame at most. The context is
 *  scaled: draw in CSS px. `color(token)` resolves a --qry-* token where the canvas is (a canvas
 *  cannot read CSS variables) — cached until the theme changes, so a 60 fps loop may call it freely. Size the canvas with CSS (e.g. width: 100%; height: 240px, or
 *  width: 100% alone to keep its aspect).
 *  @param {string|HTMLCanvasElement} canvas
 *  @param {{draw?: Function}} [opts]
 *  @returns {{ canvas: HTMLCanvasElement, ctx: CanvasRenderingContext2D, redraw: () => void, destroy: () => void }}
 *  @example makeCanvas('#chart', { draw(ctx, { width, height, color }) { ctx.fillStyle = color('--qry-accent'); ctx.fillRect(0, 0, width / 2, height); } })
 */
export const makeCanvas = (canvas, { draw } = {}) => {
    const c = $(canvas), ctx = c.getContext('2d');
    const { on, off } = _scope();
    let size = { width: 0, height: 0 }, raf = 0;
    const colors = new Map();
    const color = (token) => colors.get(token) ?? colors.set(token, $.color(token, c)).get(token);
    const paint = () => {
        raf = 0;
        if (!size.width || !size.height) return;
        const dpr = devicePixelRatio || 1, w = Math.round(size.width * dpr), h = Math.round(size.height * dpr);
        if (c.width !== w || c.height !== h) {
            const box = c.getBoundingClientRect();
            c.width = w; c.height = h;
            const r = c.getBoundingClientRect();                       // its box moved: sized by its attributes, not by CSS — pin it
            if (Math.abs(r.width - box.width) > 0.5 || Math.abs(r.height - box.height) > 0.5) c.css({ width: box.width + 'px', height: box.height + 'px' });
        }
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        ctx.clearRect(0, 0, size.width, size.height);
        draw?.(ctx, { ...size, dpr, color });
    };
    const redraw = () => { raf ||= requestAnimationFrame(paint); };
    const retheme = () => { colors.clear(); redraw(); };
    c.onResize((s) => { size = s; redraw(); }, on);
    document.documentElement.on('qry:theme', retheme, on);
    _os().addEventListener('change', retheme, on);
    return { canvas: c, ctx, redraw, destroy() { off(); cancelAnimationFrame(raf); } };
};

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

// ════════════════════════════════════════════════════════════════════════════
// CHARTS — plain SVG, drawn for its box, in the theme's tokens
// ════════════════════════════════════════════════════════════════════════════

/** @private — a value accessor: a function (d, i) → v, or a property name. */
const _acc = (f) => (typeof f === 'function' ? f : (d) => d?.[f]);

/** @private — nice ticks (1, 2, 5 × 10ⁿ) covering [lo, hi], about `n` of them (whole numbers only
 *  with `integer`: counts, trial numbers). */
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
 *  No data: the chart says `labels.empty`. The SVG is role="img", named by `label`.
 *  @param {string|Element} el  the chart's box
 *  @param {{marks: Array|Function, x?: Object, y?: Object, label?: string}} opts
 *  @returns {{ readonly svg: SVGSVGElement|null, redraw: () => void, destroy: () => void }}
 *  @example makeChart('#rt', { label: 'RT per trial', y: { label: 'ms', zero: true },
 *    marks: () => [{ type: 'dot', data: trials.value, x: 'n', y: 'rt', tone: (t) => (t.ok ? 'accent' : 'danger') }] })
 */
export const makeChart = (el, { marks = [], x: xo = {}, y: yo = {}, label = '' } = {}) => {
    const box = $(el), { on, off } = _scope();
    let size = { width: 0, height: 0 }, svg = null, list = [];
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
        const svgEl = $.svg('svg', { class: 'qry-chart', width: W, height: H, viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': label || null });
        if (!yv.length || (!band && !xv.length)) {
            svgEl.add($.svg('text', { class: 'qry-chart-empty', x: W / 2, y: H / 2, 'text-anchor': 'middle', 'dominant-baseline': 'middle', text: labels.empty }));
            return svgEl;
        }
        const fx = xo.format ?? ((v) => String(v)), fy = yo.format ?? ((v) => String(v));
        const ty = _ticks(yo.min ?? Math.min(...yv), yo.max ?? Math.max(...yv), yo.ticks ?? 5, yo.integer);
        const ylo = yo.min ?? ty.lo, yhi = yo.max ?? ty.hi, yt = ty.ticks.filter((v) => v >= ylo - 1e-9 && v <= yhi + 1e-9);
        const tx = band ? null : _ticks(xo.min ?? Math.min(...xv), xo.max ?? Math.max(...xv), xo.ticks ?? 5, xo.integer);
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
    const stop = typeof marks === 'function' ? $.effect(() => { list = marks() ?? []; paint(); }) : ((list = marks), null);
    return { get svg() { return svg; }, redraw: paint, destroy() { off(); stop?.(); svg?.remove(); svg = null; } };
};

// ════════════════════════════════════════════════════════════════════════════
// INPUT — keyboard shortcuts, drop zone
// ════════════════════════════════════════════════════════════════════════════

/** Global keyboard shortcuts, in ONE syntax — the one `aria-keyshortcuts` uses:
 *  `Modifier+Key`, several alternatives space-separated — "s", "Escape", "Space", "ArrowLeft",
 *  "Shift+N", "Alt+E", "Control+S" (Control = Ctrl or ⌘), "Control+Shift+Z". A letter compares
 *  case-insensitively and its modifiers must match exactly (Control+Z is not Control+Shift+Z, and
 *  "e" never fires with Alt); a digit or a symbol ("1", "?") ignores Shift (layouts differ: digits
 *  need Shift in French). `add(shortcut, fn, { prevent, inFields })` registers and returns a remover
 *  (`inFields: true`: a Control / ⌘ shortcut fires in a field too — an editor's Control+S);
 *  `destroy()` removes everything.
 *  A shortcut never steals a key from something that owns it: skipped when the event was already
 *  handled (defaultPrevented), while typing in a field (a text-like INPUT, TEXTAREA, SELECT,
 *  [contenteditable] — also inside a web component), while a modal dialog is open, and — for a key
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
 *  @returns {{ add:(shortcut:string, fn:(e:KeyboardEvent)=>void, opts?:{prevent?:boolean, inFields?:boolean})=>(()=>void), destroy:()=>void }}
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
        const modal = document.querySelector('dialog:modal');
        if (!modal) for (const h of handlers) {                        // nothing registered fires behind an open modal
            if ((typing && !(h.inFields && (e.ctrlKey || e.metaKey))) || !_lists(h.shortcut, e)) continue;   // a field keeps its keys — but an editor's Control+S
            if (h.prevent !== false) e.preventDefault();
            h.fn(e);
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
        add: (shortcut, fn, { prevent = true, inFields = false } = {}) => { const h = { shortcut, fn, prevent, inFields }; handlers.add(h); return () => handlers.delete(h); },
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

const _icons = new Map();
/** The app's icons, by name. `icons.add({ name: drawing })` registers them — a drawing is a path's
 *  `d` ("M4 6h16M4 12h16") or SVG markup for a 24 × 24 box ('<circle cx="12" cy="12" r="9"/>…',
 *  Lucide's files as they are). `icons.render(root)` fills every `[data-icon="name"]` under root
 *  (and root itself) — the element STAYS (change its data-icon and render again to swap: a play
 *  button becoming pause). qry-ui.css draws `.qry-icon` stroked in currentColor (`--qry-icon-stroke`,
 *  2); a filled shape says so itself (`fill="currentColor"`).
 *  @example icons.add({ play: 'M7 4.5v15l12.5-7.5z', list: 'M4 6h16M4 12h16M4 18h16' })
 *  @example <button data-icon="play" aria-label="Play"></button>  … icons.render()
 */
export const icons = {
    add(set) { for (const [name, drawing] of Object.entries(set)) _icons.set(name, drawing); return icons; },
    has: (name) => _icons.has(name),
    render(root = document) {
        const all = [...(root.matches?.('[data-icon]') ? [root] : []), ...root.querySelectorAll('[data-icon]')];
        for (const el of all) el.html(icon(el.dataset.icon));
    },
};

/** One icon as markup ($.html-safe): an inline <svg class="qry-icon">, hidden from assistive
 *  technology unless `label` names it (a button with only an icon names the BUTTON instead).
 *  @param {string} name  registered with icons.add (an unknown name warns and draws nothing)
 *  @param {{size?: number|string, label?: string, class?: string}} [opts]  size: px or any CSS length (default 1.25em)
 *  @returns {SafeHTML}
 *  @example btn.html($.html`${icon('save')} Save`)
 */
export const icon = (name, { size, label, class: cls = '' } = {}) => {
    const d = _icons.get(name);
    if (d == null) { console.warn(`icon(${JSON.stringify(name)}) — not registered: icons.add({ ${name}: '…' })`); return $.raw(''); }
    const px = typeof size === 'number' ? `${size}px` : size;
    return $.raw(`<svg class="${$.esc(`qry-icon ${cls}`.trim())}" viewBox="0 0 24 24"${px ? ` style="width:${$.esc(px)};height:${$.esc(px)}"` : ''}${label
        ? ` role="img" aria-label="${$.esc(label)}"` : ' aria-hidden="true"'} focusable="false">${d.trimStart().startsWith('<') ? d : `<path d="${$.esc(d)}"/>`}</svg>`);
};

// ════════════════════════════════════════════════════════════════════════════
// DEVICE — the screen awake, an app that installs, works offline and updates itself
// ════════════════════════════════════════════════════════════════════════════

/** Keep the screen awake while `when` holds — a signal or a function read in an effect (a
 *  metronome playing, a score on the music stand). The lock is taken again when the page comes
 *  back (the browser drops it when the tab is hidden); without the API it does nothing.
 *  @param {{when?: {value: boolean}|(() => boolean)}} [opts]  default: always
 *  @returns {{ isHeld: () => boolean, destroy: () => void }}
 *  @example makeWakeLock({ when: () => playing.value })
 */
export const makeWakeLock = ({ when = () => true } = {}) => {
    const { on, off } = _scope();
    let lock = null, want = false, asking = false;
    const release = () => { lock?.release().catch(() => {}); lock = null; };
    const take = async () => {
        if (!want || lock || asking || document.hidden || !navigator.wakeLock) return;
        asking = true;                                                    // one request at a time: never two locks
        try {
            const l = await navigator.wakeLock.request('screen');
            l.addEventListener('release', () => { if (lock === l) lock = null; });
            lock = l;
            if (!want) release();                                         // turned off while asking
        } catch { /* refused (a battery saver, no user gesture yet): the screen may sleep */ }
        finally { asking = false; }
    };
    const stop = $.effect(() => { want = !!(typeof when === 'function' ? when() : when.value); if (want) take(); else release(); });
    document.on('visibilitychange', () => { if (!document.hidden) take(); }, on);
    return { isHeld: () => !!lock, destroy() { off(); stop(); want = false; release(); } };
};

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

/** A wrap of toggle buttons (`.qry-chip`, aria-pressed) — filters, a choice among a few. Items
 *  are strings or objects; `key` and `label` are accessors (a function or a property name;
 *  default: the item's key|name|label, else the item itself); a label may be a Node (a colour
 *  dot and a name; text stays text). `selected` is a key or an array of keys; `onClick(item, key)`
 *  fires per chip. `selected()` reads the pressed keys, `selected(keys)` moves the highlight without
 *  rebuilding; `name` labels the group.
 *  @param {Array} items
 *  @param {{selected?: any, onClick?: Function, key?: string|Function, label?: string|Function, name?: string, class?: string}} [opts]
 *  @returns {{ element: Element, selected: (keys?: any|any[]) => any[]|void, destroy: () => void }}
 *  @example const c = makeChips(formats, { selected: current, name: 'Format', onClick: (f) => pick(f.key) }); box.add(c.element); c.selected(next)
 */
export const makeChips = (items, { selected, onClick, key = (i) => i?.key ?? i?.name ?? i?.label ?? i, label = (i) => i?.label ?? i?.name ?? i, name, class: cls = '' } = {}) => {
    const keyOf = _acc(key), labelOf = _acc(label), { on, off } = _scope();
    const element = $.create('div', { class: `qry-chips ${cls}`.trim(), role: 'group', 'aria-label': name ?? null });
    const pills = items.map((item) => {
        const k = keyOf(item);
        const chip = $.create('button', { type: 'button', class: 'qry-chip', 'data-key': k }, labelOf(item)).mount(element);
        if (onClick) chip.on('click', () => onClick(item, k), on);
        return { k, chip };
    });
    const select = (sel) => {
        const keys = [].concat(sel);
        for (const { k, chip } of pills) chip.attr('aria-pressed', String(keys.includes(k)));
    };
    select(selected);
    const read = () => pills.filter(({ chip }) => chip.attr('aria-pressed') === 'true').map(({ k }) => k);
    return { element, selected: (...keys) => (keys.length ? select(keys[0]) : read()), destroy() { off(); element.remove(); } };
};

/** A sortable table (`.qry-table`). Each header is a button (click or keyboard) that sorts by its
 *  column; a `numeric` column sorts numerically and aligns right. Values are escaped; a column's
 *  `format(value, row)` returns text (escaped too) or $.html`…`. Rows that `onRow` activates are
 *  focusable buttons in the row sense: a click, Enter or Space. `update(rows)` and
 *  `sortBy(key, dir?)` refresh in place.
 *  @param {Array<{key:string, label:string, numeric?:boolean, format?:(v:*, row:object)=>string}>} columns
 *  @param {Array<object>} rows
 *  @param {Object} [opts]
 *  @param {boolean} [opts.numbered]  a 1-based column first (header labels.position)
 *  @param {{key:string, dir:'asc'|'desc'}} [opts.sort]  default: the first numeric column desc, else the first column asc
 *  @param {(row:object, tr:Element)=>void} [opts.onRow]  body-row activation (click, Enter or Space on the focused row)
 *  @param {string} [opts.empty=labels.empty]
 *  @param {string} [opts.class]  extra class on the table
 *  @returns {{ element: HTMLTableElement, update: (rows: object[]) => void, sortBy: (key: string, dir?: string) => void, destroy: () => void }}
 *  @example const t = makeTable(columns, fonts, { numbered: true, onRow: (f) => pick(f) }); box.add(t.element); t.update(next)
 */
export const makeTable = (columns, rows, { numbered = false, sort, onRow, empty = labels.empty, class: cls = '' } = {}) => {
    const col = (k) => columns.find((c) => c.key === k);
    const first = columns.find((c) => c.numeric) ?? columns[0];
    const state = sort ? { ...sort } : { key: first.key, dir: first.numeric ? 'desc' : 'asc' };
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
        view = data.toSorted((a, b) => (state.dir === 'asc' ? cmp(a, b) : cmp(b, a)));
        const num = (c) => (c.numeric ? 'qry-num' : '');
        element.html($.html`<thead><tr>${numbered && $.html`<th scope="col" class="qry-num qry-idx">${labels.position}</th>`}${columns.map((c) =>
            $.html`<th scope="col" class="${num(c)}" aria-sort="${sorted(c)}"><button type="button" data-key="${c.key}">${c.label}${arrow(c)}</button></th>`)}</tr></thead>
            <tbody>${view.length ? view.map((r, i) => $.html`<tr data-i="${i}"${onRow && $.raw(' tabindex="0"')}>${numbered && $.html`<td class="qry-num qry-idx">${i + 1}</td>`}${columns.map((c) =>
                $.html`<td class="${num(c)}">${cell(c, r)}</td>`)}</tr>`)
            : $.html`<tr class="qry-table-empty"><td colspan="${columns.length + (numbered ? 1 : 0)}">${empty}</td></tr>`}</tbody>`);
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
