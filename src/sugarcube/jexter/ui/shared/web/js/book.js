// book.js — THE document seam: one open ocd-epub, one source of truth, one API.
//
// PRISM displays exactly one document; every tab (Read, Edit, and every
// tool to come) is a view or an editor over that SAME document. This module is the
// contract they all build on — the whole document lifecycle behind a dozen verbs:
//
//   SOURCE OF TRUTH   the DISPLAYED DOM: pages are same-origin frames served by the
//                     Service Worker; book.page(idx) adopts a frame's live <svg> as
//                     an OcdPage (ocd.js) — reading it derives state, writing it
//                     edits the document, on screen, immediately.
//
//   DURABLE TRUTHS    two synchronized projections of the members:
//                     P.state.files (in-memory book — edit + export truth) and the
//                     Service Worker cache (display truth, serves /epub/<id>/…).
//                     book.put() writes BOTH and is awaitable: when it resolves,
//                     any frame, any view, any reload and any export sees the data.
//
//   PERSISTENCE       book.persist(idx) projects the live DOM back into the page
//                     member (data-ui chrome stripped), debounced; book.flush() runs
//                     what is pending. Only pages a tool actually edited are ever
//                     rewritten — untouched members stay byte-verbatim.
//
//   CHROME CONTRACT   anything a tool injects into a page carries data-ui (+ data-z
//                     "under|over"): it renders live in page space and NEVER reaches
//                     the container — persist() strips it. A tool's stylesheet goes
//                     through book.frameCss, which states that once.
//
// The epub file itself is a transport format: unzipped on open, re-zipped by
// book.bytes() — in between, the document lives as members + displayed DOM only.
//
//   import { book } from '/shared/js/book.js';
//   book.onFrame((idx) => { const page = book.page(idx); … });       // decorate
//   const name = await book.addImage(bytes, 'png');                  // resource
//   book.persist(idx);                                               // project

import { OcdPage, parseFonts, enc, dec, mediaType } from './ocd.js';
import { zipSync } from '../vendor/fflate.js';

const P = window.prism;

/* ── identity & paths ────────────────────────────────────────────────────── */

/** True when a book is open. */
const isOpen = () => !!P.state.files;

/** OPF-relative → zip path ('images/x.png' → 'OEBPS/images/x.png'). */
const member = (rel) => P.state.opfDir + rel;

/** Page index → zip path of its xhtml member. */
const pagePath = (idx) => P.state.pages[idx].href.replace(P.state.root, '').split('/').map(decodeURIComponent).join('/');

/* ── members: committed, awaitable ───────────────────────────────────────── */

/** Raw member bytes (Uint8Array) or null. */
const get = (path) => P.state.files?.[path] ?? null;

/** THE write — the chassis's single member-write seam, and the MUTATION HUB:
 *  every document change flows through here, is committed to both truths, and is
 *  announced to subscribers (onChange). Awaitable: resolved means visible
 *  everywhere (frames, views, reloads, export). */
const changeSubs = new Set();
function onChange(cb) { changeSubs.add(cb); return () => changeSubs.delete(cb); }
function put(path, bytes) {
    const done = P.putMember(path, bytes);
    for (const cb of changeSubs) { try { cb(path); } catch { } }
    return done;
}

/** Read-mutate-write a JSON member (OPF-relative). `mutate` returning false skips. */
function json(rel, mutate) {
    const path = member(rel);
    const obj = JSON.parse(dec(P.state.files[path]));
    return mutate(obj) !== false ? put(path, enc(JSON.stringify(obj, null, 2))) : Promise.resolve();
}

/** Read-transform-write the OPF (manifest patches). */
const opf = (transform) => put(P.state.opfPath, enc(transform(dec(P.state.files[P.state.opfPath]))));

/* ── resources ───────────────────────────────────────────────────────────── */

/** Idempotently DECLARE a member in the OPF manifest — THE one manifest-add
 *  authority (resources, sidecars, anything). `path` is the zip path; the href is
 *  derived relative to the OPF folder. Declaring twice is a no-op. */
function declare(path, mime) {
    const href = path.startsWith(P.state.opfDir) ? path.slice(P.state.opfDir.length) : path;
    if (dec(P.state.files[P.state.opfPath]).includes(`href="${href}"`)) return Promise.resolve();
    const id = 'res-' + href.replace(/[^\w.-]+/g, '-');
    return opf(s => s.replace('</manifest>', `<item id="${id}" href="${href}" media-type="${mime}"/>\n</manifest>`));
}

/** New resource bytes → document member + OPF manifest item, COMMITTED — when this
 *  resolves, ../<dir>/<name> is live for every frame. ONE verb for the whole
 *  "PDF and beyond" range: addImage (images/img-c*) and addMedia (media/med-c*,
 *  audio + video — the SW already serves every type) are its two faces. */
async function addResource(dir, prefix, bytes, ext) {
    let n = 1;
    while (P.state.files[member(`${dir}/${prefix}${n}.${ext}`)]) n++;
    const name = `${prefix}${n}.${ext}`;
    await Promise.all([
        put(member(`${dir}/` + name), bytes),
        declare(member(`${dir}/` + name), mediaType(name)),
    ]);
    return name;
}
const addImage = (bytes, ext) => addResource('images', 'img-c', bytes, ext);
const addMedia = (bytes, ext) => addResource('media', 'med-c', bytes, ext);

/* ── lifecycle: the document's front door ────────────────────────────────── */

/** Open a container (ArrayBuffer|Uint8Array) as THE document — the canonical
 *  path: SW load, rails, nav, pager, events. */
const open = (buffer, name) => P.openEpub(buffer.buffer || buffer, name);

/** The open book as a container (Uint8Array), the members as they stand — what an export, a re-open and
 *  every engine call post. The mimetype first and STORED (OCF); null when no book is open. */
function bytes() {
    const files = P.state.files; if (!files) return null;
    const out = { mimetype: [files['mimetype'] || enc('application/epub+zip'), { level: 0 }] };
    for (const [p, v] of Object.entries(files)) if (p !== 'mimetype') out[p] = v;
    return zipSync(out);
}

/* ── pages: the displayed DOM, adopted ───────────────────────────────────── */

const adopted = new Map();                         // idx → OcdPage (re-adopted when the frame reloads)

/** The raw frame document for a page index (null when not loaded).
 *
 *  The address is the CHASSIS's: it mints each card with both an id (`#page-N`) and `data-index`, and
 *  this module reads the second because that is what every tool addresses. They agree because one file
 *  sets both — if the card's markup ever changes, this selector is the thing to change with it. */
function frameDoc(idx) {
    try { return document.querySelector(`.prism-page[data-index="${idx}"] .prism-frame`)?.contentDocument || null; }
    catch { return null; }
}

/** The live page: the frame's <svg data-ocd="page"> adopted as an OcdPage — THE
 *  authoring surface. Null when the frame isn't loaded or isn't SVG-OCD. */
function page(idx) {
    const doc = frameDoc(idx);
    const svg = doc?.querySelector('svg[data-ocd="page"]');
    if (!svg) return null;
    let p = adopted.get(idx);
    if (!p || p.svg !== svg) { p = OcdPage.adopt(svg, fonts()); adopted.set(idx, p); }
    return p;
}

/** The container's font library, parsed ONCE per book: the adopted page's shim doc starts empty, and
 *  `OcdPage.text()` needs it — without this the authoring API throws "no font registered" on every
 *  container, which is why nothing in the app had ever reached it. */
let fontCache = null;
P.on?.('book',  () => { fontCache = null; });     // a new book brings its own library
P.on?.('close', () => { fontCache = null; });
function fonts() {
    if (fontCache) return fontCache;
    const raw = get(member('pages/fonts.svg'));
    fontCache = raw ? parseFonts(dec(raw)) : [];
    return fontCache;
}

/** After a page member was put: reload just that page's frame — the SW already serves the new
 *  bytes — and forget its adopted model. The page-scoped counterpart of reload(). */
function refreshPage(idx) {
    const f = document.querySelector(`.prism-page[data-index="${idx}"] .prism-frame`);
    if (!f?.contentWindow) return;
    adopted.delete(idx);
    try { f.contentWindow.location.reload(); } catch { /* not ready */ }
}

/** Every loaded frame document, raw: cb(doc, idx). */
function eachFrame(cb) {
    document.querySelectorAll('.prism-page .prism-frame').forEach(f => {
        try {
            const doc = f.contentDocument, idx = +f.closest('.prism-page')?.getAttribute('data-index');
            if (doc?.querySelector('svg') && Number.isFinite(idx)) cb(doc, idx);
        } catch { /* not ready */ }
    });
}

/** A tool's stylesheet in a page frame: set to `css` (made once, under `id`), or removed when `css` is
 *  falsy. Chrome by contract — it carries data-ui, whatever the tool remembers to say. */
function frameCss(doc, id, css) {
    if (!doc) return;
    let s = doc.getElementById(id);
    if (!css) { s?.remove(); return; }
    if (!s) {
        s = doc.createElementNS('http://www.w3.org/1999/xhtml', 'style');
        s.id = id; s.setAttribute('data-ui', '');
        (doc.head || doc.documentElement).appendChild(s);
    }
    if (s.textContent !== css) s.textContent = css;
}

/* ── frame events: MULTI-CAST (the chassis hook is single-slot) ──────────── */

/** Subscribe to frame loads: cb(idx) whenever a page frame becomes ready.
 *  Returns the unsubscribe function. (The chassis event bus multicasts.) */
const onFrame = (cb) => P.on('frame', (f, idx) => cb(idx));

/* ── fonts: adding one the container does not carry ─────────────────────── */

/** The first free `fN` alias in `pages/fonts.svg` — the client picks it, because it alone knows which are
 *  taken; the engine mints the block under whatever it is asked for. */
function freeAlias() {
    const raw = get(member('pages/fonts.svg'));
    const taken = new Set([...(raw ? dec(raw) : '').matchAll(/<g id="(f\d+)"/g)].map(m => m[1]));
    for (let i = 0; ; i++) if (!taken.has('f' + i)) return 'f' + i;
}

/** Merge a one-font `fonts.svg` (what `/api/font` answers) into the container's shared library, and return
 *  the font as `parseFonts` sees it — ready for `OcdPage.text({ font })`.
 *
 *  The library is a plain `<defs>` list, so adding a font is inserting its `<g>` before `</defs>`: no
 *  second grammar, no re-emission of the fonts already there — a page that referenced `f0-7` still
 *  does. The parsed cache is dropped, since the library it described has changed. */
async function addFont(oneFontSvg) {
    const path = member('pages/fonts.svg');
    const lib = dec(get(path) || new Uint8Array());
    const g = /<g id="f\d+"[\s\S]*<\/g>/.exec(oneFontSvg);
    if (!g) throw new Error('not a font block');
    if (!lib.includes('</defs>')) throw new Error('this container carries no pages/fonts.svg');
    await put(path, enc(lib.replace('</defs>', g[0] + '\n</defs>')));
    fontCache = null;
    const f = fonts().find(x => x.alias === /<g id="(f\d+)"/.exec(g[0])[1]);
    return f;
}

/* ── persistence: the DOM → member projection, edited pages only ─────────── */

const pending = new Map();                         // idx → flushable persist

/** Project the live page DOM into its member, committed to both truths. Debounced 300 ms; `now` runs
 *  it synchronously.
 *
 *  The projection is `OcdPage.xhtml()` and nothing else: ocd.js is the grammar authority, and a second
 *  serializer is one that misses a clause (glyph refs back to the shared `fonts.svg#`, chrome stripped). */
function persist(idx, now = false) {
    clearTimeout(pending.get(idx)?.t);
    const run = () => {
        pending.delete(idx);
        const p = adopted.get(idx); if (!p) return;
        return put(pagePath(idx), enc(p.xhtml(idx + 1)));
    };
    if (now) run(); else pending.set(idx, { t: setTimeout(run, 300), run });
}

/** Run every pending persist now (call on tool/mode leave). */
function flush() {
    for (const [, p] of [...pending]) { clearTimeout(p.t); p.run(); }
    pending.clear();
}

/* ── the seam ────────────────────────────────────────────────────────────── */

export const book = {
    freeAlias, addFont,
    isOpen, member, pagePath,
    open, bytes,
    get, put, onChange, json,
    addImage, addMedia, declare,
    page, eachFrame, frameDoc, frameCss, onFrame, refreshPage, fonts,
    persist, flush,
};
