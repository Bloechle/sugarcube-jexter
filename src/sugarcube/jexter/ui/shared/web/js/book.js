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
//                     member (data-ui chrome stripped, head/shell spliced verbatim),
//                     debounced; book.flush() runs what is pending. Only pages a
//                     tool actually edited are ever rewritten — untouched members
//                     stay byte-verbatim from the original container.
//
//   CHROME CONTRACT   anything a tool injects for itself carries data-ui (+ data-z
//                     "under|over"): it renders live in page space and NEVER reaches
//                     the container — persist() strips it, ocd.js build() strips it.
//
// The epub file itself is a transport format: unzipped on open, re-zipped on
// export — in between, the document lives as members + displayed DOM only.
//
//   import { book } from '/shared/js/book.js';
//   book.onFrame((idx) => { const page = book.page(idx); … });       // decorate
//   const name = await book.addImage(bytes, 'png');                  // resource
//   page.image({ name, …, into: page.layer('my-layer', 'Label') });  // model edit
//   book.persist(idx);                                               // project

import { OcdPage, pageShell } from './ocd.js';
import { zipSync } from 'https://cdn.jsdelivr.net/npm/fflate@0.8.2/+esm';

const P = window.prism;
const enc = (s) => new TextEncoder().encode(s);
const dec = (b) => new TextDecoder().decode(b);

/* ── identity & paths ────────────────────────────────────────────────────── */

/** True when a book is open. */
const isOpen = () => !!P.state.files;

/** True when the open book is an OCD-EPUB (carries the jexter/ members). */
const isOcd = () => !!P.state.files?.[member('jexter/meta.json')];

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

// page members refresh their rail thumbnail on ANY write — whoever the writer is
onChange(path => {
    const n = P.state.pages?.length || 0;
    for (let i = 0; i < n; i++) if (pagePath(i) === path) { markThumb(i); break; }
});

/** Read-mutate-write a JSON member (OPF-relative). `mutate` returning false skips. */
function json(rel, mutate) {
    const path = member(rel);
    const obj = JSON.parse(dec(P.state.files[path]));
    return mutate(obj) !== false ? put(path, enc(JSON.stringify(obj, null, 2))) : Promise.resolve();
}

/** Read-transform-write the OPF (manifest patches). */
const opf = (transform) => put(P.state.opfPath, enc(transform(dec(P.state.files[P.state.opfPath]))));

/* ── resources ───────────────────────────────────────────────────────────── */

const MIME = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp', svg: 'image/svg+xml',
               mp3: 'audio/mpeg', m4a: 'audio/mp4', oga: 'audio/ogg', wav: 'audio/wav',
               mp4: 'video/mp4', m4v: 'video/mp4', webm: 'video/webm', ogv: 'video/ogg' };

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
        declare(member(`${dir}/` + name), MIME[ext] || 'application/octet-stream'),
    ]);
    return name;
}
const addImage = (bytes, ext) => addResource('images', 'img-c', bytes, ext);
const addMedia = (bytes, ext) => addResource('media', 'med-c', bytes, ext);

/** Drop a resource a tool introduced (img-c* / med-c*): member + manifest item
 *  (matched by href — robust to any declarer's id scheme). */
function removeResource(name) {
    const m = /^(img-c|med-c)\d+\./.exec(name || '');
    if (!m) return Promise.resolve();
    const rel = (m[1] === 'img-c' ? 'images/' : 'media/') + name;
    delete P.state.files[member(rel)];
    return opf(s => s.replace(new RegExp(`\\s*<item [^>]*href="${rel.replace(/\./g, '\\.')}"[^>]*/>`), ''));
}

/** OCDLayer registry entry in jexter/meta.json — idempotent. */
const registerLayer = (id, name) => json('jexter/meta.json', m => {
    if ((m.layers || []).some(l => l.id === id)) return false;
    m.layers = [...(m.layers || []), { id, name: name || id, visible: true, order: (m.layers || []).length }];
});

/** Append a blank page to the open document (v1: at the end — no renumbering).
 *  Four member patches (page, OPF manifest+spine, nav toc+page-list, NCX), then the
 *  canonical reload: re-zip → openEpub — rails, pager and nav rebuild themselves. */
async function addPage() {
    const files = P.state.files;
    const n = P.state.pages.length + 1;
    const last = P.state.pages[P.state.pages.length - 1];
    const W = last?.w || 595.28, H = last?.h || 841.89;
    let k = n; while (files[member(`pages/page-${String(k).padStart(3, '0')}.xhtml`)]) k++;
    const pf = `page-${String(k).padStart(3, '0')}`;
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" `
        + `viewBox="0 0 ${W} ${H}" width="${W}" height="${H}" data-ocd="page" data-v="2" data-media="0 0 ${W} ${H}"></svg>`;
    await put(member(`pages/${pf}.xhtml`), enc(pageShell(svg, W, H, n)));
    await opf(s => s
        .replace('</manifest>', `<item id="page-a${k}" href="pages/${pf}.xhtml" media-type="application/xhtml+xml" properties="svg"/>\n</manifest>`)
        .replace('</spine>', `<itemref idref="page-a${k}"/>\n</spine>`));
    const navPath = Object.keys(files).find(p => /nav\.xhtml$/.test(p));
    if (navPath) {
        let nav = dec(files[navPath]);
        const li = (label) => `<li><a href="pages/${pf}.xhtml">${label}</a></li>`;
        nav = nav.replace(/(epub:type="toc"[\s\S]*?)<\/ol>/, `$1${li('Page ' + n)}\n</ol>`)
                 .replace(/(epub:type="page-list"[\s\S]*?)<\/ol>/, `$1${li(n)}\n</ol>`);
        await put(navPath, enc(nav));
    }
    const ncxPath = Object.keys(files).find(p => /toc\.ncx$/.test(p));
    if (ncxPath) await put(ncxPath, enc(dec(files[ncxPath]).replace('</navMap>',
        `<navPoint id="np-a${k}" playOrder="${n}"><navLabel><text>Page ${n}</text></navLabel><content src="pages/${pf}.xhtml"/></navPoint>\n</navMap>`)));
    await reload();                                // the canonical rebuild
    P.goTo(n - 1);
    return n - 1;
}

/* ── lifecycle: open / close / reload — the document's front door ────────── */

/** Open a container (ArrayBuffer|Uint8Array) as THE document — the canonical
 *  path: SW load, rails, nav, pager, events. */
const open = (buffer, name) => P.openEpub(buffer.buffer || buffer, name);

/** Close the document (frames, rails, state — everything). */
const close = () => P.closeBook();

/** Re-open the CURRENT members as a fresh container — the canonical rebuild
 *  after any structural change (page added/removed, spine reshaped): rails,
 *  pager, nav and spine reconstruct themselves through the open path. */
function reload() {
    const files = P.state.files;
    const out = { mimetype: [files['mimetype'], { level: 0 }] };
    for (const [p, v] of Object.entries(files)) if (p !== 'mimetype') out[p] = v;
    return P.openEpub(zipSync(out).buffer, P.state.name);
}

/* ── pages: the displayed DOM, adopted ───────────────────────────────────── */

const adopted = new Map();                         // idx → OcdPage (re-adopted when the frame reloads)

/** The raw frame document for a page index (null when not loaded). */
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
    if (!p || p.svg !== svg) { p = OcdPage.adopt(svg); adopted.set(idx, p); }
    return p;
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

/** Every loaded SVG-OCD page: cb(idx, page). */
function eachPage(cb) {
    document.querySelectorAll('.prism-page .prism-frame').forEach(f => {
        const idx = +f.closest('.prism-page')?.getAttribute('data-index');
        if (!Number.isFinite(idx)) return;
        const p = page(idx);
        if (p) cb(idx, p);
    });
}

/* ── frame events: MULTI-CAST (the chassis hook is single-slot) ──────────── */

/** Subscribe to frame loads: cb(idx) whenever a page frame becomes ready.
 *  Returns the unsubscribe function. (The chassis event bus multicasts.) */
const onFrame = (cb) => P.on('frame', (f, idx) => cb(idx));

/* ── persistence: the DOM → member projection, edited pages only ─────────── */

const pending = new Map();                         // idx → flushable persist

/** Project the live page DOM into its member (data-ui stripped, canonical shell),
 *  committed to both truths. Debounced 300 ms; `now` runs it synchronously.
 *  The member is REBUILT around the serialized <svg> — never spliced: a childless
 *  svg self-closes under the browser serializer, so anchor-based splicing is a trap. */
function persist(idx, now = false) {
    clearTimeout(pending.get(idx)?.t);
    const run = () => {
        pending.delete(idx);
        const p = adopted.get(idx); if (!p) return;
        const clone = p.svg.cloneNode(true);
        clone.querySelectorAll('[data-ui]').forEach(e => e.remove());
        return put(pagePath(idx), enc(pageShell(new XMLSerializer().serializeToString(clone), p.w, p.h, idx + 1)));
    };
    if (now) run(); else pending.set(idx, { t: setTimeout(run, 300), run });
}

/** Run every pending persist now (call on tool/mode leave). */
function flush() {
    for (const [, p] of [...pending]) { clearTimeout(p.t); p.run(); }
    pending.clear();
}

/* ── dirty thumbnails: an edited page marks its rail thumb; a sweep refreshes ── */

const dirtyThumbs = new Set();
let sweep = 0;                                     // interval alive ONLY while something is dirty

function markThumb(idx) {
    dirtyThumbs.add(idx);
    if (!sweep) sweep = setInterval(sweepThumbs, 1500);
}

function sweepThumbs() {
    for (const idx of [...dirtyThumbs]) {
        dirtyThumbs.delete(idx);
        const fr = document.querySelector(`#page-list .pg-item[data-page="${idx}"] iframe.pg-frame`);
        const src = fr?.getAttribute('src');
        if (src) fr.setAttribute('src', src);      // reload — the SW already serves the persisted page
    }                                              // (not loaded yet → it will load fresh on scroll)
    if (!dirtyThumbs.size) { clearInterval(sweep); sweep = 0; }
}

/* ── the seam ────────────────────────────────────────────────────────────── */

export const book = {
    isOpen, isOcd, member, pagePath,
    open, close, reload,
    get, put, onChange, json, opf,
    addImage, addMedia, removeResource, declare, registerLayer,
    page, eachPage, eachFrame, frameDoc, onFrame, addPage,
    persist, flush,
};
