// ocd.js — THE client OCD-EPUB API: read, create, edit, build — as a LIVE DOM model.
//
// An OCD-EPUB is not just any EPUB (think Inkscape-SVG vs plain SVG): a valid
// fixed-layout EPUB whose pages are the strongly-typed SVG-OCD dialect and whose
// members carry the model (doc/SPEC.md + doc/SVG-OCD.md). This module is the JS
// twin of the Java pair OcdEpubWriter ↔ OCDReader: everything it EMITS uses the
// writer's exact forms (verbatim-conformant, engine-gated), and everything it
// READS follows the reader's contract. It is the canvas layer every Prism tab
// builds derived apps on: compose sheets, labels, forms — printable electronic
// documents, created and edited in the browser, exported by the engine.
//
// Since v2 the page under authoring is a LIVE SVGSVGElement, not a string buffer:
// mount doc.page(i).svg in any host and the document renders itself WHILE you
// author it — the file is the render, for authoring too. Drawing verbs create
// real elements and return them; pan, restyle or remove them with plain DOM.
// Serialization (build) walks the DOM.
//
//   READ    loadOcd(bytes|Blob)          → { meta, fonts, pages, structures, … }
//   CREATE  OcdDoc.create({ … })         → doc  (blank document, given page format)
//   EDIT    OcdDoc.open(bytes|Blob)      → doc  (existing container; page(i) checks a
//                                           page out as live DOM, build() re-emits it)
//   DRAW    doc.page(i).rect/circle/line/path/image/text({ …, into })  → live Element
//   BUILD   doc.build()                  → Uint8Array (.ocd.epub)
//
// The page DOM is structured in three strictly separated strata:
//
//   <style> + <defs>                           deduped paint classes, model clips
//   <g data-ocd="layer" data-ref="ID">         MODEL content — one group per authored
//                                              layer (OCDLayerContent in the engine;
//                                              the OCDLayer registry rides in
//                                              jexter/meta.json "layers")
//   <defs|g data-ui="ID" data-z="under|over">  CLIENT-ONLY chrome (guides, ghosts,
//                                              hints, local glyph defs): same
//                                              coordinate space, renders live, and is
//                                              STRIPPED at build — data-ui never
//                                              reaches the container.
//
// Live-DOM addressing vs stored forms — swapped transparently, both ways:
//   images   blob: URL (live)                       ↔  ../images/name (stored)
//   glyphs   #fN-gid over <defs data-ui="fonts">    ↔  f.svg#fN-gid   (stored)
//
// Coordinates are the MODEL's: points (1 mm = 72/25.4 pt), Y-up, origin bottom-left
// — the OCD logic, one space everywhere; mm() converts. UI strata may draw directly
// in viewBox space (top-down). Text needs a font: reuse an opened document's
// (OcdDoc.open reads pages/f.svg) or parseFonts() any f.svg text.

import { unzip, zipSync } from 'https://cdn.jsdelivr.net/npm/fflate@0.8.2/+esm';

/* ══ shared utils ═══════════════════════════════════════════════════════ */

export const esc = (s) => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

const PT = 72 / 25.4;                              // pt per mm (mm() is the public converter)
export const mm = (v) => v * PT;                   // mm → pt
export const FORMATS = {                           // page formats, portrait, mm
    A3: [297, 420], A4: [210, 297], A5: [148, 210], A6: [105, 148], Letter: [215.9, 279.4],
};

export const SVG_NS   = 'http://www.w3.org/2000/svg';
const XLINK_NS = 'http://www.w3.org/1999/xlink';
const XMLNS_NS = 'http://www.w3.org/2000/xmlns/';

/** Create an SVG element with attributes (null/undefined skipped) — the one DOM minting rule. */
export function svgel(tag, attrs = {}, text) {
    const e = document.createElementNS(SVG_NS, tag);
    for (const [k, v] of Object.entries(attrs)) if (v != null) e.setAttribute(k, v);
    if (text != null) e.textContent = text;
    return e;
}

const F = (v) => { const r = Math.round(v * 100) / 100; return Object.is(r, -0) ? '0' : String(r); };
const OPF = 'OEBPS/';
const JX = OPF + 'jexter/';
const enc = (s) => new TextEncoder().encode(s);
const dec = (b) => new TextDecoder().decode(b);
const MIME = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp', svg: 'image/svg+xml' };

const inflate = (data) => new Promise((res, rej) =>
    unzip(data instanceof Uint8Array ? data : new Uint8Array(data), (e, out) => e ? rej(e) : res(out)));

async function toFiles(src) {                      // Blob | ArrayBuffer | Uint8Array | files map
    if (src && typeof src === 'object' && !(src instanceof Blob) && !(src instanceof ArrayBuffer)
        && !(src instanceof Uint8Array)) return src;
    const buf = src instanceof Blob ? await src.arrayBuffer() : src;
    return inflate(buf);
}

/* ══ fonts: parse a pages/f.svg — THE font representation ═══════════════ */

export function parseFonts(text) {
    // machine-emitted, regular markup → parsed by shape (DOM-free: works in workers/node too)
    const fonts = [];
    // The name must be anchored on a preceding space or it matches as a SUBSTRING of a longer
    // attribute name: 'd' found `id="f0-32"` first and returned "f0-32", so every glyph got the
    // tail of its own id as path data — 5 characters of nonsense that draw nothing. The whole
    // glyph grid rendered blank, the inspector reported «outline 5 chars» for an empty space,
    // and nothing upstream was wrong. Same trap waits for any name that ends another one.
    // A regex reads the RAW attribute, so XML entities come back undecoded — the writer correctly
    // stores data-u="&amp;" for the ampersand glyph, and without this the value is the five-character
    // string "&amp;" instead of "&". It showed as literal &quot; / &amp; under the glyph specimens,
    // and silently broke any comparison of `u` against a real character. Decoded in ONE pass: a
    // sequential replace would turn "&amp;lt;" into "<".
    const ENT = { '&amp;': '&', '&lt;': '<', '&gt;': '>', '&quot;': '"', '&apos;': "'" };
    const unesc = (v) => v == null ? v : v.replace(/&(?:amp|lt|gt|quot|apos);/g, e => ENT[e]);
    const attr = (src, name) => unesc(new RegExp('(?:^|\\s)' + name + '="([^"]*)"').exec(src)?.[1]);
    for (const m of text.matchAll(/<g\s+id="[^"]+"\s+data-f="[^"]*"[\s\S]*?<\/g>/g)) {
        const src = m[0], head = src.slice(0, src.indexOf('>') + 1);
        const font = {
            alias: attr(head, 'id'),
            id: attr(head, 'data-id') || attr(head, 'data-f'),
            safe: attr(head, 'data-f'),
            name: attr(head, 'data-name') || attr(head, 'data-id') || '',
            family: attr(head, 'data-family') || '',
            weight: attr(head, 'data-weight') || 'normal',
            style: attr(head, 'data-style') || 'normal',
            embedded: head.includes('data-embedded'),
            ascent: +attr(head, 'data-asc') || 0, descent: +attr(head, 'data-desc') || 0,
            cap: +attr(head, 'data-cap') || 0, xh: +attr(head, 'data-x') || 0,
            space: +attr(head, 'data-sp') || 0,
            cmap: new Map(), glyphs: [], glyphByGid: new Map(),
            raw: src,                                        // re-emitted verbatim at build
        };
        for (const tok of (attr(head, 'data-cmap') || '').trim().split(/\s+/)) {
            const i = tok.indexOf(':');
            if (i > 0) font.cmap.set(+tok.slice(0, i), +tok.slice(i + 1));
        }
        for (const pm of src.matchAll(/<path\s[^>]*\/>/g)) {
            const p = pm[0];
            const pid = attr(p, 'id') || '';
            const gid = +pid.slice(pid.lastIndexOf('-') + 1);
            const glyph = { gid, d: attr(p, 'd') || '',
                adv: +attr(p, 'data-adv') || 0, u: attr(p, 'data-u') || '',
                gname: attr(p, 'data-gname') || '' };
            font.glyphs.push(glyph);
            font.glyphByGid.set(gid, glyph);
        }
        fonts.push(font);
    }
    return fonts;
}

/* ══ READ: open a container for display (the inspectors' accessor) ══════ */

// Returns { name, meta, fonts, pages, structures, defaultStructureId, outline, files }
//   Page = { href, width, height, xhtml, svg }   svg = stored markup, image hrefs → blob: URLs
export async function loadOcd(src) {
    const files = await toFiles(src);
    const read = (p) => files[p] ? dec(files[p]) : null;
    const meta = files[JX + 'meta.json'] ? JSON.parse(read(JX + 'meta.json')) : null;
    if (!meta) throw new Error('not an OCD-EPUB — no ' + JX + 'meta.json member (a foreign EPUB is a book, not a model)');

    const fontsRaw = files[OPF + 'pages/f.svg'] ? read(OPF + 'pages/f.svg') : '';
    const fonts = fontsRaw ? parseFonts(fontsRaw) : [];
    const fontsDefs = fontsDefsOf(fontsRaw);          // embedded into each page svg (inline injection has no base URL)

    const imageUrl = {};
    for (const p of Object.keys(files)) {
        const m = p.match(/^OEBPS\/images\/(.+)$/);
        if (m) imageUrl[m[1]] = URL.createObjectURL(new Blob([files[p]]));
    }

    const pages = spine(read).map(href => {
        const xhtml = read(OPF + href) || '';
        const vb = /viewBox="([\d.\s-]+)"/.exec(xhtml);
        const [, , w, h] = vb ? vb[1].trim().split(/\s+/).map(Number) : [0, 0, 0, 0];
        return { href, width: w, height: h, xhtml, svg: pageSvg(xhtml, imageUrl, fontsDefs) };
    });

    let structures = [], defaultId = null;
    if (files[JX + 'structures.json']) {
        const ss = JSON.parse(read(JX + 'structures.json'));
        if (ss?.structures?.length) { structures = ss.structures; defaultId = ss.default || structures[0].id; }
    }
    const nav = files[JX + 'outline.json'] ? JSON.parse(read(JX + 'outline.json')) : null;
    const outline = (nav?.bookmarks || []).map(function map(b) {
        return { title: b.title, page: b.page, children: (b.children || []).map(map) };
    });

    return { name: meta.title || 'document.ocd.epub', meta, fonts, pages,
             structures, defaultStructureId: defaultId, outline, files };
}

function spine(read) {
    const container = read('META-INF/container.xml') || '';
    const opfPath = /full-path="([^"]+)"/.exec(container)?.[1] || OPF + 'content.opf';
    const opf = read(opfPath) || '';
    const hrefs = {};
    for (const m of opf.matchAll(/<item\s[^>]*id="([^"]+)"[^>]*href="([^"]+)"/g)) hrefs[m[1]] = m[2];
    for (const m of opf.matchAll(/<item\s[^>]*href="([^"]+)"[^>]*id="([^"]+)"/g)) hrefs[m[2]] ??= m[1];
    return [...opf.matchAll(/<itemref\s[^>]*idref="([^"]+)"/g)].map(m => hrefs[m[1]]).filter(Boolean);
}

// The page's <svg>, exactly as stored, re-addressed for INLINE injection (which has no base
// URL): image hrefs → blob:, and the stored external glyph refs (f.svg#fN-gid) → local #fN-gid
// with the fonts <defs> embedded — a Service-Worker-served frame resolves f.svg relatively,
// an innerHTML injection cannot. Principle: the file is the render — no client renderer exists.
function pageSvg(xhtml, imageUrl, fontsDefs = '') {
    const a = xhtml.indexOf('<svg'), b = xhtml.lastIndexOf('</svg>');
    if (a < 0 || b < 0) return '';
    let s = xhtml.slice(a, b + 6)
        .replace(/(xlink:href|href)="\.\.\/images\/([^"]+)"/g,
            (all, attr, name) => imageUrl[name] ? `${attr}="${imageUrl[name]}"` : all)
        .replace(/(xlink:href|href)="(?:\.\.\/pages\/|\.\/)?f\.svg#/g, '$1="#');
    if (fontsDefs && s.includes('="#f') && !s.includes('data-ui="fonts"')) s = s.replace(/<svg\b[^>]*>/, m => m + fontsDefs);
    return s;
}

/** The <defs>…</defs> of a pages/f.svg — the glyph library, ready to embed. */
function fontsDefsOf(t) {
    if (!t) return '';
    const a = t.indexOf('<defs'), b = t.lastIndexOf('</defs>');
    return a < 0 || b < 0 ? '' : t.slice(a, b + 7);
}

export function pageViewport(p) {
    return { vw: p.width || 0, vh: p.height || 0 };
}

/* ══ AUTHOR / EDIT: OcdPage — one LIVE page of the document ══════════════ */

/** One page under authoring: a real, mountable SVGSVGElement. Model coordinates:
 *  POINTS, Y-UP (drawing verbs); UI strata may address viewBox space directly.
 *  Exported: adopt ANY live SVG-OCD page (e.g. a reader frame's svg) with a bare
 *  `new OcdDoc()` as registry shim — image({name}) then addresses ../images/name
 *  relatively, exactly what a Service-Worker-served frame resolves. */
export class OcdPage {

    /** Adopt a LIVE displayed page (e.g. a reader frame's svg) as the authoring
     *  surface — the displayed DOM is the source of truth. The registry shim is a
     *  bare OcdDoc: image({name}) then addresses ../images/name relatively, exactly
     *  what a Service-Worker-served frame resolves. */
    static adopt(svg) {
        const vb = (svg.getAttribute('viewBox') || '').trim().split(/\s+/).map(Number);
        return new OcdPage(new OcdDoc(), vb[2] || mm(210), vb[3] || mm(297), svg);
    }

    /** Internal — pages are minted by OcdDoc (addPage / page) or adopt(). `svg` adopts
     *  an existing element (a checked-out stored page); absent, a blank page is built. */
    constructor(doc, w, h, svg = null) {
        this.doc = doc; this.w = w; this.h = h;
        this.styles = new Map();                    // css body → class name (writer's dedup)
        this.sn = 0;                                // next .sN index
        if (svg) {
            this.svg = svg;
            this.styleEl = svg.querySelector(':scope > style');
            this.defsEl  = svg.querySelector(':scope > defs:not([data-ui])');
            if (this.styleEl)                       // absorb stored classes — cls() never collides
                for (const m of this.styleEl.textContent.matchAll(/\.(s(\d+))\{([^}]*)\}/g)) {
                    this.styles.set(m[3], m[1]);
                    this.sn = Math.max(this.sn, +m[2] + 1);
                }
        } else {
            const W = F(w), H = F(h);
            this.svg = svgel('svg', { xmlns: SVG_NS, viewBox: `0 0 ${W} ${H}`, width: W, height: H,
                'data-ocd': 'page', 'data-v': '2', 'data-media': `0 0 ${W} ${H}` });
            this.svg.setAttributeNS(XMLNS_NS, 'xmlns:xlink', XLINK_NS);
            this.styleEl = null; this.defsEl = null;
        }
    }

    get flip() { return `matrix(1 0 0 -1 0 ${F(this.h)})`; }   // pageFlip for box 0 0 w h

    /* ── strata plumbing ───────────────────────────────────────────────── */

    style() {
        if (!this.styleEl) { this.styleEl = svgel('style'); this.svg.insertBefore(this.styleEl, this.svg.firstChild); }
        return this.styleEl;
    }

    defs() {
        if (!this.defsEl) { this.defsEl = svgel('defs'); this.svg.insertBefore(this.defsEl, this.#contentStart()); }
        return this.defsEl;
    }

    /** First child past the <style>/<defs> head — where content begins. */
    #contentStart() {
        for (const c of this.svg.children)
            if (c.localName !== 'style' && c.localName !== 'defs') return c;
        return null;
    }

    /** Mount a model element: into an Element, into a layer (by id), or at the end
     *  of the content zone (before any data-z="over" UI). */
    mount(el, into) {
        if (into instanceof Element) { into.appendChild(el); return el; }
        if (typeof into === 'string') { this.layer(into).appendChild(el); return el; }
        this.svg.insertBefore(el, this.svg.querySelector(':scope > [data-ui][data-z="over"]'));
        return el;
    }

    /** Get-or-create a MODEL layer group: <g data-ocd="layer" data-ref="ID"> — read
     *  back by the engine as OCDLayerContent; the OCDLayer registry entry is minted
     *  on the doc (jexter/meta.json "layers"). */
    layer(id, name) {
        let g = this.svg.querySelector(`:scope > g[data-ocd="layer"][data-ref="${id}"]`);
        if (!g) {
            g = svgel('g', { id: 'a' + this.doc.seq++, 'data-ocd': 'layer', 'data-ref': id });
            this.mount(g);
        }
        this.doc.registerLayer(id, name);
        return g;
    }

    /** Get-or-create a CLIENT-ONLY chrome group: <g data-ui="ID"> — renders live in
     *  the page space, stripped at build. z: 'over' (default, on top of content) or
     *  'under' (behind content, e.g. ghosts). */
    ui(id, z = 'over') {
        let g = this.svg.querySelector(`:scope > g[data-ui="${id}"]`);
        if (!g) {
            g = svgel('g', { 'data-ui': id, 'data-z': z });
            if (z === 'under') this.svg.insertBefore(g, this.#contentStart());
            else this.svg.appendChild(g);
        }
        return g;
    }

    /* ── paint classes & clips ─────────────────────────────────────────── */

    cls(paint) {                                    // fill/stroke/… → deduped .sN class
        const css = [];
        const p = { fill: 'none', ...paint };
        if (p.fill && p.fill !== 'none') css.push('fill:' + p.fill); else css.push('fill:none');
        if (p.fillOpacity != null) css.push('fill-opacity:' + p.fillOpacity);
        if (p.stroke && p.stroke !== 'none') {
            css.push('stroke:' + p.stroke, 'stroke-width:' + (p.strokeWidth ?? 1));
            if (p.strokeOpacity != null) css.push('stroke-opacity:' + p.strokeOpacity);
            if (p.dash) css.push('stroke-dasharray:' + p.dash);
            if (p.cap) css.push('stroke-linecap:' + p.cap);
            if (p.join) css.push('stroke-linejoin:' + p.join);
        }
        if (p.opacity != null) css.push('opacity:' + p.opacity);
        const body = css.join(';');
        if (!this.styles.has(body)) {
            const name = 's' + this.sn++;
            this.styles.set(body, name);
            this.style().textContent += `\n.${name}{${body}}`;
        }
        return this.styles.get(body);
    }

    /** Register a rectangular clip (page space, Y-up) → clip id for the `clip`
     *  option. An explicit `id` makes the clip replaceable (removeClip / re-create). */
    clipRect({ x, y, w, h, id }) {
        const cid = id || 'c' + this.doc.seq++;
        this.removeClip(cid);
        const cp = svgel('clipPath', { id: cid, clipPathUnits: 'userSpaceOnUse' });
        cp.appendChild(svgel('path', { d: rectD(x, y, w, h) }));
        this.defs().appendChild(cp);
        return cid;
    }

    removeClip(id) { this.svg.querySelector(`[id="${id}"]`)?.remove(); }   // ids are page-unique; tag-free (camelCase SVG tags don't select in every DOM)

    /* ── drawing verbs — every verb returns the LIVE element ───────────── */

    /** THE clip carrier (FORMAT §B3), the one form every clipped node takes: a typed wrapper holding the
     *  NATIVE clip-path, entering page space to resolve the def (which is stored in page space) and leaving
     *  it again at once — the flip is self-inverse, so the node inside keeps its own placement. Returns the
     *  element to mount the node into. */
    clipWrap(clip) {
        const g = svgel('g', { 'data-ocd': 'clip', 'data-ref': clip, 'clip-path': `url(#${clip})`, transform: this.flip });
        const inner = svgel('g', { transform: this.flip });
        g.appendChild(inner);
        return { outer: g, inner };
    }

    /** Mount `el` under a clip wrapper when `clip` is set, else straight into `into`. */
    mountClipped(el, clip, into) {
        if (!clip) return this.mount(el, into);
        const { outer, inner } = this.clipWrap(clip);
        inner.appendChild(el);
        this.mount(outer, into);
        return el;
    }

    /** A vector path — `d` in PAGE space (pt, Y-up); the flip rides the transform,
     *  exactly the writer's form: <path id d class transform=flip/>. A clip rides on the wrapper. */
    path({ d, clip, into, ...paint }) {
        const p = svgel('path', { id: 'a' + this.doc.seq++, d, class: this.cls(paint) });
        p.setAttribute('transform', this.flip);
        return this.mountClipped(p, clip, into);
    }

    rect({ x, y, w, h, rx, ...opts }) {
        let d;
        if (rx) {
            const r = Math.min(rx, w / 2, h / 2), k = r * 0.5523;
            d = `M${F(x + r)} ${F(y)}L${F(x + w - r)} ${F(y)}C${F(x + w - r + k)} ${F(y)} ${F(x + w)} ${F(y + r - k)} ${F(x + w)} ${F(y + r)}`
              + `L${F(x + w)} ${F(y + h - r)}C${F(x + w)} ${F(y + h - r + k)} ${F(x + w - r + k)} ${F(y + h)} ${F(x + w - r)} ${F(y + h)}`
              + `L${F(x + r)} ${F(y + h)}C${F(x + r - k)} ${F(y + h)} ${F(x)} ${F(y + h - r + k)} ${F(x)} ${F(y + h - r)}`
              + `L${F(x)} ${F(y + r)}C${F(x)} ${F(y + r - k)} ${F(x + r - k)} ${F(y)} ${F(x + r)} ${F(y)}Z`;
        } else d = rectD(x, y, w, h);
        return this.path({ d, ...opts });
    }

    circle({ cx, cy, r, ...opts }) {
        const k = r * 0.5523;
        const d = `M${F(cx + r)} ${F(cy)}C${F(cx + r)} ${F(cy + k)} ${F(cx + k)} ${F(cy + r)} ${F(cx)} ${F(cy + r)}`
            + `C${F(cx - k)} ${F(cy + r)} ${F(cx - r)} ${F(cy + k)} ${F(cx - r)} ${F(cy)}`
            + `C${F(cx - r)} ${F(cy - k)} ${F(cx - k)} ${F(cy - r)} ${F(cx)} ${F(cy - r)}`
            + `C${F(cx + k)} ${F(cy - r)} ${F(cx + r)} ${F(cy - k)} ${F(cx + r)} ${F(cy)}Z`;
        return this.path({ d, ...opts });
    }

    line({ x1, y1, x2, y2, ...opts }) {
        return this.path({ d: `M${F(x1)} ${F(y1)}L${F(x2)} ${F(y2)}`, fill: 'none', stroke: opts.stroke || '#000', ...opts });
    }

    /** A raster image placed by its page-space rect (pt, Y-up); `clip` crops it (the
     *  model clip — what overflows is gone in every projection). `bytes`+`ext`
     *  register a NEW resource; `name` reuses one. Live href = blob: URL, swapped to
     *  ../images/name at build. Always returns the <image> — a clip rides on a wrapper above it. */
    image({ bytes, ext = 'png', name, x, y, w, h, clip, opacity, into }) {
        if (bytes) name = this.doc.addImage(bytes, ext);
        const url = this.doc.nameToUrl.get(name) || ('../images/' + name);
        const img = svgel('image', { x: 0, y: 0, width: 1, height: 1, preserveAspectRatio: 'none' });
        img.setAttributeNS(XLINK_NS, 'xlink:href', url);
        if (opacity != null) img.setAttribute('opacity', opacity);
        const place = placeM(x, y, w, h);           // unit square → rect, row 0 on top
        img.setAttribute('id', 'a' + this.doc.seq++);
        img.setAttribute('transform', `${this.flip} ${place}`);   // its own placement, clipped or not
        return this.mountClipped(img, clip, into);
    }

    /** Re-place an existing image (pan/zoom): el = what image() returned. The new
     *  rect is page space (pt, Y-up); only the placement matrix moves — LIVE. */
    place(el, { x, y, w, h }) {
        const img = el.localName === 'image' ? el : el.querySelector('image');
        img.setAttribute('transform', `${this.flip} ${placeM(x, y, w, h)}`);   // one form, clipped or not
        return el;
    }

    /** Native OCD text: one run of em-space glyph uses — searchable, selectable,
     *  engine-exportable. (x, y) = the BASELINE start, page space (pt, Y-up). Live
     *  glyphs resolve locally (#fN-gid over a data-ui defs); build restores f.svg#.
     *  Returns { el (the paragraph <g>), run (the text run <g>), width (pt) }. */
    text({ text, x, y, size, font, fill = '#000', align = 'left', into }) {
        const f = this.doc.font(font);
        if (!f) throw new Error('no font registered — open a container carrying pages/f.svg');
        let ax = 0;
        const run = [];
        for (const ch of text) {
            const gid = f.cmap.get(ch.codePointAt(0));
            const g = gid != null ? f.glyphByGid.get(gid) : null;
            run.push({ g, ax });
            ax += g ? g.adv : f.space || 0.25;
        }
        const shift = align === 'center' ? -ax / 2 : align === 'right' ? -ax : 0;
        // run matrix = fs·flip·position in viewBox space (grammar §4): matrix(s 0 0 -s tx ty_topdown)
        // id FIRST and in the engine's tN scheme — the text-layer tooling (search,
        // TTS, highlight) addresses runs by exactly this form
        const t = svgel('g', { id: this.#nextRunId(), 'data-ocd': 't', 'data-f': f.safe, class: this.cls({ fill }),
            'data-u': text, transform: `matrix(${F(size)} 0 0 ${F(-size)} ${F(x)} ${F(this.h - y)})` });
        for (const { g, ax: gx } of run)
            if (g && g.d) { this.glyphDef(f, g); t.appendChild(svgel('use', { href: `#${f.alias}-${g.gid}`, x: F(gx + shift) })); }
        this.doc.usedFonts.add(f.alias);
        const l = svgel('g', { 'data-ocd': 'l' });
        const p = svgel('g', { id: 'a' + this.doc.seq++, 'data-ocd': 'p' });
        l.appendChild(t); p.appendChild(l);
        this.mount(p, into);
        return { el: p, run: t, width: ax * size };
    }

    /** Next page-scoped run id in the engine's scheme (t1, t2, …). */
    #nextRunId() {
        if (this.tn == null) {
            this.tn = 0;
            for (const g of this.svg.querySelectorAll('g[data-ocd="t"]')) {
                const m = /^t(\d+)$/.exec(g.id || '');
                if (m) this.tn = Math.max(this.tn, +m[1]);
            }
        }
        return 't' + (++this.tn);
    }

    /** Local glyph defs so external f.svg# uses render live — data-ui, stripped at build. */
    glyphDef(f, g) {
        const id = `${f.alias}-${g.gid}`;
        let d = this.svg.querySelector(':scope > defs[data-ui="fonts"]');
        if (!d) { d = svgel('defs', { 'data-ui': 'fonts' }); this.svg.insertBefore(d, this.#contentStart()); }
        if (!d.querySelector(`path[id="${id}"]`)) d.appendChild(svgel('path', { id, d: g.d }));
    }

    /* ── serialization: the DOM, minus the UI strata, in stored addressing ─ */

    xhtml(n = 1) {
        const c = this.svg.cloneNode(true);
        c.removeAttribute('style');                                   // host sizing is chrome
        c.querySelectorAll('[data-ui]').forEach(e => e.remove());     // UI strata never ship
        for (const img of c.querySelectorAll('image')) {              // blob: → ../images/name
            const name = this.doc.urlToName.get(xhref(img));
            if (name) img.setAttributeNS(XLINK_NS, 'xlink:href', '../images/' + name);
        }
        for (const u of c.querySelectorAll('use')) {                  // #fN-gid → f.svg#fN-gid
            const href = u.getAttribute('href');
            if (href?.startsWith('#')) u.setAttribute('href', 'f.svg' + href);
        }
        const st = c.querySelector(':scope > style'); if (st && !st.textContent.trim()) st.remove();
        const de = c.querySelector(':scope > defs');  if (de && !de.children.length) de.remove();
        return pageShell(new XMLSerializer().serializeToString(c), this.w, this.h, n);
    }
}

/** THE page member shell: a serialized page <svg> wrapped into its stored xhtml.
 *  Shared by OcdPage.xhtml() and book.persist() — one shell, everywhere. */
export function pageShell(svgSrc, w, h, n = 1) {
    return '<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE html>\n'
        + '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">\n<head>\n'
        + `<meta charset="utf-8"/>\n<title>Page ${n}</title>\n`
        + `<meta name="viewport" content="width=${F(w)}, height=${F(h)}"/>\n</head>\n<body style="margin:0;padding:0">\n`
        + svgSrc + '\n</body>\n</html>\n';
}

export const xhref = (el) => el.getAttributeNS?.(XLINK_NS, 'href') || el.getAttribute('xlink:href') || el.getAttribute('href') || '';
const rectD  = (x, y, w, h) => `M${F(x)} ${F(y)}L${F(x + w)} ${F(y)}L${F(x + w)} ${F(y + h)}L${F(x)} ${F(y + h)}Z`;
const placeM = (x, y, w, h) => `matrix(${F(w)} 0 0 ${F(-h)} ${F(x)} ${F(y + h)})`;

/* ══ OcdDoc — the document: pages, resources, layer registry, build ══════ */

export class OcdDoc {

    /** A blank document. { title, language, format:'A4'|[Wmm,Hmm], pages:1, fonts:[] } */
    static create(opts = {}) {
        const d = new OcdDoc();
        d.meta = { format: 'ocd-epub', version: '2',
                   title: opts.title || 'Untitled', language: opts.language || 'en',
                   created: now() };
        const fm = Array.isArray(opts.format) ? opts.format : FORMATS[opts.format || 'A4'];
        d.pageSize = [fm[0] * PT, fm[1] * PT];
        d.fonts = opts.fonts || [];
        for (let i = 0; i < (opts.pages ?? 1); i++) d.addPage();
        return d;
    }

    /** An existing container, opened for editing. page(i) CHECKS OUT a stored page as
     *  live DOM (that page is re-serialized at build — touch only what you edit);
     *  untouched members ride through verbatim. Deep node surgery (re-segmentation,
     *  role edits) stays the engine's job. Adding pages to an opened doc: not in v2. */
    static async open(src, opts = {}) {
        const d = new OcdDoc();
        d.files = await toFiles(src);
        const read = (p) => d.files[p] ? dec(d.files[p]) : null;
        if (!d.files[JX + 'meta.json']) throw new Error('not an OCD-EPUB (no jexter/meta.json)');
        d.meta = JSON.parse(read(JX + 'meta.json'));
        for (const l of d.meta.layers || []) d.layersReg.set(l.id, { ...l });
        d.spineHrefs = spine(read);
        d.fonts = (opts.fonts || []).concat(d.files[OPF + 'pages/f.svg'] ? parseFonts(read(OPF + 'pages/f.svg')) : []);
        for (const p of Object.keys(d.files)) {
            const m = p.match(/^OEBPS\/images\/(.+)$/);
            if (m) d.#regUrl(m[1], URL.createObjectURL(new Blob([d.files[p]])));
        }
        return d;
    }

    constructor() {
        this.files = null;                          // opened container (edit) — null when creating
        this.meta = null; this.fonts = [];
        this.pages = [];                            // authored OcdPage list (created docs)
        this.opened = new Map();                    // spine index → { href, page } (opened docs)
        this.spineHrefs = [];
        this.images = []; this.imgSeq = 0; this.seq = 1;
        this.usedFonts = new Set();
        this.layersReg = new Map();                 // id → { id, name, visible, order }
        this.nameToUrl = new Map();                 // image resource ↔ live blob: URL
        this.urlToName = new Map();
    }

    font(sel) {
        if (!this.fonts.length) return null;
        if (!sel) return this.fonts[0];
        return this.fonts.find(f => f.safe === sel || f.id === sel || f.name === sel || f.alias === sel) || this.fonts[0];
    }

    /** Mint (or update) an OCDLayer registry entry — serialized in meta.json "layers". */
    registerLayer(id, name) {
        if (!this.layersReg.has(id))
            this.layersReg.set(id, { id, name: name || id, visible: true, order: this.layersReg.size });
        else if (name) this.layersReg.get(id).name = name;
        return this.layersReg.get(id);
    }

    addPage(size) {
        const [w, h] = size ? [size.w, size.h] : this.pageSize || [mm(210), mm(297)];
        const p = new OcdPage(this, w, h);
        this.pages.push(p);
        return p;
    }

    /** The live page: authored (created docs) or checked out of the container (opened
     *  docs) — parsed once, then the same live DOM on every call. */
    page(i) {
        if (!this.files) return this.pages[i];
        if (this.opened.has(i)) return this.opened.get(i).page;
        const href = this.spineHrefs[i];
        if (!href) throw new Error('no page ' + i);
        const parsed = new DOMParser().parseFromString(dec(this.files[OPF + href]), 'application/xhtml+xml');
        const svg = document.importNode(parsed.querySelector('svg'), true);
        const vb = (svg.getAttribute('viewBox') || '').trim().split(/\s+/).map(Number);
        const page = new OcdPage(this, vb[2] || mm(210), vb[3] || mm(297), svg);
        for (const img of svg.querySelectorAll('image')) {            // stored → live addressing
            const m = xhref(img).match(/^\.\.\/images\/(.+)$/);
            if (m && this.nameToUrl.has(m[1])) img.setAttributeNS(XLINK_NS, 'xlink:href', this.nameToUrl.get(m[1]));
        }
        for (const u of svg.querySelectorAll('use')) {
            const m = xhref(u).match(/^f\.svg#(.+)-(\d+)$/);
            if (!m) continue;
            u.setAttribute('href', `#${m[1]}-${m[2]}`);
            const f = this.fonts.find(x => x.alias === m[1]);
            const g = f?.glyphByGid.get(+m[2]);
            if (f && g) page.glyphDef(f, g);
        }
        this.opened.set(i, { href, page });
        return page;
    }

    /** Register image bytes as a document resource → its name; a live blob: URL is
     *  minted alongside (nameToUrl) so the DOM renders immediately. */
    addImage(bytes, ext) {
        const name = `img-a${++this.imgSeq}.${ext}`;
        const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
        this.images.push([name, u8]);
        this.#regUrl(name, URL.createObjectURL(new Blob([u8], { type: MIME[ext] || 'application/octet-stream' })));
        return name;
    }

    /** Drop an authored image resource (replaced/cleared slots — no dead weight in the zip). */
    removeImage(name) {
        const url = this.nameToUrl.get(name);
        if (url?.startsWith('blob:')) URL.revokeObjectURL(url);
        this.nameToUrl.delete(name);
        if (url) this.urlToName.delete(url);
        this.images = this.images.filter(([n]) => n !== name);
    }

    #regUrl(name, url) { this.nameToUrl.set(name, url); this.urlToName.set(url, name); }

    /** Assemble the .ocd.epub (Uint8Array). Created docs get the FULL engine
     *  skeleton (OcdEpubWriter parity: nav toc + landmarks + page-list, toc.ncx,
     *  pages/f.svg always, jexter/outline.json, cover raster, rendition +
     *  accessibility metadata) — seamless next to any engine-written container.
     *  Opened docs get their checked-out pages re-emitted, new images manifested,
     *  the layer registry merged into meta — everything else verbatim. */
    async build() {
        const out = { mimetype: [enc('application/epub+zip'), { level: 0 }] };   // stored FIRST (OCF)
        const meta = { ...this.meta };
        if (this.layersReg.size) meta.layers = [...this.layersReg.values()];

        if (this.files) {
            for (const [p, b] of Object.entries(this.files)) if (p !== 'mimetype') out[p] = b;
            for (const [i, { href, page }] of this.opened) out[OPF + href] = enc(page.xhtml(i + 1));
            if (this.images.length) {
                for (const [name, bytes] of this.images) out[OPF + 'images/' + name] = bytes;
                const opfPath = /full-path="([^"]+)"/.exec(dec(this.files['META-INF/container.xml'] || new Uint8Array()))?.[1] || OPF + 'content.opf';
                const items = this.images.map(([name], k) =>
                    `<item id="im-a${k + 1}" href="images/${name}" media-type="${MIME[name.split('.').pop()] || 'application/octet-stream'}"/>`).join('\n');
                out[opfPath] = enc(dec(out[opfPath]).replace('</manifest>', items + '\n</manifest>'));
            }
            out[JX + 'meta.json'] = enc(JSON.stringify(meta, null, 2));
            return zipSync(out);
        }

        // ── created document: the full container, engine-skeleton parity ──
        out['META-INF/container.xml'] = enc('<?xml version="1.0" encoding="UTF-8"?>\n'
            + '<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">\n'
            + '  <rootfiles>\n'
            + '    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>\n'
            + '  </rootfiles>\n'
            + '</container>\n');

        const uidv = 'urn:uuid:' + uid();
        const pf = (n) => 'page-' + String(n).padStart(3, '0');
        const manifest = [
            '    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>',
            '    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>',
            '    <item id="glyphs" href="pages/f.svg" media-type="image/svg+xml"/>',
        ];

        const cover = this.pages.length ? await this.#cover() : null;      // raster of page 1, best-effort
        if (cover) {
            out[OPF + 'images/cover.png'] = cover;
            manifest.push('    <item id="cover-image" href="images/cover.png" media-type="image/png" properties="cover-image"/>');
        }

        const spineRefs = [];
        this.pages.forEach((p, i) => {
            out[`${OPF}pages/${pf(i + 1)}.xhtml`] = enc(p.xhtml(i + 1));
            manifest.push(`    <item id="page-${i + 1}" href="pages/${pf(i + 1)}.xhtml" media-type="application/xhtml+xml" properties="svg"/>`);
            spineRefs.push(`    <itemref idref="page-${i + 1}"/>`);
        });

        // fonts — ALWAYS emitted: the single representation (empty defs when no text yet)
        const groups = this.fonts.filter(f => this.usedFonts.has(f.alias)).map(f => f.raw).join('\n');
        out[OPF + 'pages/f.svg'] = enc('<svg xmlns="http://www.w3.org/2000/svg" data-ocd="fonts" data-v="2">\n<defs>\n'
            + (groups ? groups + '\n' : '') + '</defs>\n</svg>\n');

        this.images.forEach(([name, bytes]) => {
            out[OPF + 'images/' + name] = bytes;
            manifest.push(`    <item id="img-${name}" href="images/${name}" media-type="${MIME[name.split('.').pop()] || 'application/octet-stream'}"/>`);
        });

        manifest.push('    <item id="jx-meta" href="jexter/meta.json" media-type="application/json"/>');
        manifest.push('    <item id="jx-outline" href="jexter/outline.json" media-type="application/json"/>');

        // logical navigation: toc (flat Page N) + landmarks + hidden page-list — the engine's navDoc
        const start = `pages/${pf(1)}.xhtml`;
        const li = (i, label) => `    <li><a href="pages/${pf(i + 1)}.xhtml">${label}</a></li>`;
        out[OPF + 'nav.xhtml'] = enc('<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE html>\n'
            + '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">\n'
            + `<head><meta charset="utf-8"/><title>${esc(meta.title)}</title></head>\n<body>\n`
            + `<nav epub:type="toc" role="doc-toc" id="toc"><h1>${esc(meta.title)}</h1>\n  <ol>\n`
            + this.pages.map((_, i) => li(i, 'Page ' + (i + 1))).join('\n') + '\n  </ol>\n</nav>\n'
            + '<nav epub:type="landmarks" aria-label="Guide" hidden="hidden">\n  <ol>\n'
            + `    <li><a epub:type="cover" href="${start}">Cover</a></li>\n`
            + `    <li><a epub:type="bodymatter" href="${start}">Start of Content</a></li>\n`
            + '  </ol>\n</nav>\n'
            + '<nav epub:type="page-list" role="doc-pagelist" id="page-list" hidden="hidden">\n  <ol>\n'
            + this.pages.map((_, i) => li(i, i + 1)).join('\n') + '\n  </ol>\n</nav>\n'
            + '</body>\n</html>\n');

        // NCX (EPUB2 compatibility)
        out[OPF + 'toc.ncx'] = enc('<?xml version="1.0" encoding="UTF-8"?>\n'
            + '<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">\n'
            + `<head><meta name="dtb:uid" content="${uidv}"/></head>\n`
            + `<docTitle><text>${esc(meta.title)}</text></docTitle>\n<navMap>\n`
            + this.pages.map((_, i) => `    <navPoint id="np-${i + 1}" playOrder="${i + 1}">`
                + `<navLabel><text>Page ${i + 1}</text></navLabel><content src="pages/${pf(i + 1)}.xhtml"/></navPoint>`).join('\n')
            + '\n</navMap>\n</ncx>\n');

        // package: rendition triple + Dublin Core + accessibility, spine toc="ncx"
        const hasImages = this.images.length > 0;
        const metaExtra = (cover ? '<meta name="cover" content="cover-image"/>\n' : '')
            + (meta.created ? `<dc:date>${esc(meta.created)}</dc:date>\n` : '')
            + '<meta property="schema:accessMode">textual</meta>\n'
            + (hasImages ? '<meta property="schema:accessMode">visual</meta>\n' : '')
            + (hasImages ? '<meta property="schema:accessModeSufficient">textual,visual</meta>\n'
                         : '<meta property="schema:accessModeSufficient">textual</meta>\n')
            + '<meta property="schema:accessibilityFeature">printPageNumbers</meta>\n'
            + '<meta property="schema:accessibilityHazard">none</meta>\n'
            + '<meta property="schema:accessibilitySummary">Page-level navigation with page-list.</meta>\n';
        out[OPF + 'content.opf'] = enc('<?xml version="1.0" encoding="UTF-8"?>\n'
            + '<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id" prefix="rendition: http://www.idpf.org/vocab/rendition/#">\n'
            + '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">\n'
            + `<dc:identifier id="pub-id">${uidv}</dc:identifier>\n`
            + `<dc:title>${esc(meta.title)}</dc:title>\n<dc:language>${esc(meta.language)}</dc:language>\n`
            + `<meta property="dcterms:modified">${now()}</meta>\n`
            + '<meta property="rendition:layout">pre-paginated</meta>\n'
            + '<meta property="rendition:orientation">auto</meta>\n'
            + '<meta property="rendition:spread">auto</meta>\n'
            + metaExtra + '</metadata>\n'
            + '<manifest>\n' + manifest.join('\n') + '\n</manifest>\n'
            + '<spine toc="ncx">\n' + spineRefs.join('\n') + '\n</spine>\n</package>\n');

        out[JX + 'meta.json'] = enc(JSON.stringify(meta, null, 2));
        out[JX + 'outline.json'] = enc('{"bookmarks":[]}');
        return zipSync(out);
    }

    /** The cover: page 1 rasterized on white, ~1000 px long edge (the engine's rule),
     *  via canvas — image resources inlined as data: URIs (an <img>-loaded SVG cannot
     *  fetch external blobs), glyph defs kept. Best-effort: null outside a browser. */
    async #cover() {
        try {
            if (typeof Image === 'undefined' || !document.createElement('canvas').getContext) return null;
            const page = this.pages[0];
            const c = page.svg.cloneNode(true);
            c.removeAttribute('style');
            c.querySelectorAll('[data-ui]').forEach(e => { if (e.localName !== 'defs') e.remove(); });
            const byName = new Map(this.images);
            for (const img of c.querySelectorAll('image')) {
                const name = this.urlToName.get(xhref(img));
                const bytes = name ? byName.get(name) : null;
                if (bytes) img.setAttributeNS(XLINK_NS, 'xlink:href',
                    `data:${MIME[name.split('.').pop()] || 'application/octet-stream'};base64,${b64(bytes)}`);
            }
            const img = new Image();
            const url = URL.createObjectURL(new Blob([new XMLSerializer().serializeToString(c)], { type: 'image/svg+xml' }));
            try { await new Promise((res, rej) => { img.onload = res; img.onerror = rej; img.src = url; }); }
            finally { URL.revokeObjectURL(url); }
            const scale = Math.max(48, Math.min(150, 72 * 1000 / Math.max(1, Math.max(page.w, page.h)))) / 72;
            const cv = document.createElement('canvas');
            cv.width = Math.round(page.w * scale); cv.height = Math.round(page.h * scale);
            const g = cv.getContext('2d');
            g.fillStyle = '#fff'; g.fillRect(0, 0, cv.width, cv.height);
            g.drawImage(img, 0, 0, cv.width, cv.height);
            const blob = await new Promise(res => cv.toBlob(res, 'image/png'));
            return blob ? new Uint8Array(await blob.arrayBuffer()) : null;
        } catch { return null; }
    }
}

function b64(u8) {
    let s = '';
    for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
    return btoa(s);
}

const now = () => new Date().toISOString().replace(/\.\d+Z$/, 'Z');
const uid = () => (globalThis.crypto?.randomUUID?.() ||
    'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
        const r = Math.random() * 16 | 0; return (c === 'x' ? r : (r & 3 | 8)).toString(16);
    }));
