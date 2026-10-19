// ocd.js — THE client grammar of the OCD-EPUB: read a container, and author on a live page.
//
// An OCD-EPUB is not just any EPUB (think Inkscape-SVG vs plain SVG): a valid fixed-layout EPUB whose
// pages are the strongly-typed SVG-OCD dialect and whose members carry the model (doc/FORMAT.md). This
// module is the client twin of the engine's ocd.io codec: what it WRITES uses the writer's exact forms,
// what it READS follows the reader's contract.
//
//   READ     loadOcd(bytes|Blob)   → { meta, fonts, pages, structures, … }   (the inspectors)
//   AUTHOR   OcdPage.adopt(svg)    → the displayed page as a LIVE authoring surface (book.page)
//            page.text({ … })      → a native run, glyphs from the container's fonts.svg
//            page.xhtml(n)         → the page member, chrome stripped, stored addressing restored
//
// The page DOM has three strictly separated strata:
//
//   <style> + <defs>                deduped paint classes, model clips
//   model content                   runs, paths, images, groups — what the engine reads back
//   [data-ui] / [data-ui="wrap"]    CLIENT-ONLY chrome: renders live, STRIPPED by xhtml() (a "wrap" is
//                                   unwrapped, its children kept) — data-ui never reaches the container
//
// Glyphs: a stored page addresses the shared library (fonts.svg#fN-gid); a live authored run addresses
// page-local defs (#fN-gid over <defs data-ui="fonts">), and xhtml() restores the stored form.
//
// Coordinates are the MODEL's: points, Y-up, origin bottom-left — one space everywhere.

import { unzip } from '../vendor/fflate.js';

/* ══ shared utils ═══════════════════════════════════════════════════════ */

export const SVG_NS   = 'http://www.w3.org/2000/svg';

/** The page MEMBER's file stem, 1-based: `1 → "page-001"` (FORMAT §B2). The engine states it once
 *  (`OCDVocab.pageFile`); this is the client's single copy of the same fact, and `book.js` asks for it
 *  rather than spelling the padding a third time. */
export const pageFile = (n) => 'page-' + String(n).padStart(3, '0');

/** THE format version this client reads and writes — the engine's `OCDVocab.VERSION`, stated once on this
 *  side. 4 since 2026-10-19 (FORMAT, Versions). A container that states another version is refused on open
 *  (`loadOcd`), never half-read. */
export const OCD_VERSION = '4';
const XLINK_NS = 'http://www.w3.org/1999/xlink';

/** Create an SVG element with attributes (null/undefined skipped) — the one DOM minting rule. */
export function svgel(tag, attrs = {}, text) {
    const e = document.createElementNS(SVG_NS, tag);
    for (const [k, v] of Object.entries(attrs)) if (v != null) e.setAttribute(k, v);
    if (text != null) e.textContent = text;
    return e;
}

// THE number rule for everything this module writes, and it is the ENGINE's: `JxNum.fmt` — four
// decimals, trailing zeros trimmed, an integer without a point, no `-0`. It used to round to two, so
// a page written here and a page written by the engine stated the same geometry differently and a
// client-placed element could not survive a round trip exactly. Two writers of one format have to
// agree on the digits.
const F = (v) => {
    if (!Number.isFinite(v)) return '0';
    const r = Math.round(v * 1e4) / 1e4;
    if (Object.is(r, -0) || r === 0) return '0';
    return Math.abs(r) < 1e21 ? String(r) : r.toFixed(4);   // String() would go exponential up there
};
const OPF = 'OEBPS/';
const JX = OPF + 'ocd/';

/** Why an unzipped container cannot be read as an OCD-EPUB of THIS version — the message, or null when it can
 *  (or when it is no OCD-EPUB at all: a foreign fixed-layout EPUB is a book, read as one). The one version
 *  gate of the client: `loadOcd` and Prism's open both ask it. */
export function ocdRefusal(files) {
    const raw = files[JX + 'meta.json'];
    if (!raw) return null;
    let v; try { v = JSON.parse(dec(raw)).version; } catch { return 'unreadable ' + JX + 'meta.json'; }
    return String(v) === OCD_VERSION ? null
        : 'OCD-EPUB version ' + v + ' — this client reads version ' + OCD_VERSION + ' only: reconvert the document from its source';
}
/** UTF-8 both ways — the client's one text codec. */
export const enc = (s) => new TextEncoder().encode(s);
export const dec = (b) => new TextDecoder().decode(b);

/** THE media type of a member, by extension — the engine's `EpubPackage.mediaType`, client side: images,
 *  audio and video, and the octet-stream fallback for anything else. */
const MEDIA = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp',
                svg: 'image/svg+xml', mp3: 'audio/mpeg', m4a: 'audio/mp4', oga: 'audio/ogg', ogg: 'audio/ogg',
                wav: 'audio/wav', mp4: 'video/mp4', m4v: 'video/mp4', webm: 'video/webm', ogv: 'video/ogg' };
export const mediaType = (name) => MEDIA[String(name).split('.').pop().toLowerCase()] || 'application/octet-stream';

const inflate = (data) => new Promise((res, rej) =>
    unzip(data instanceof Uint8Array ? data : new Uint8Array(data), (e, out) => e ? rej(e) : res(out)));

async function toFiles(src) {                      // Blob | ArrayBuffer | Uint8Array | files map
    if (src && typeof src === 'object' && !(src instanceof Blob) && !(src instanceof ArrayBuffer)
        && !(src instanceof Uint8Array)) return src;
    const buf = src instanceof Blob ? await src.arrayBuffer() : src;
    return inflate(buf);
}

/* ══ fonts: parse a pages/fonts.svg — THE font representation ═══════════════ */

export function parseFonts(text) {
    // machine-emitted, regular markup → parsed by shape (DOM-free: works in workers/node too)
    const fonts = [];
    // The name must be anchored on a preceding space or it matches as a SUBSTRING of a longer
    // attribute name: 'd' found `id="f0-32"` first and returned "f0-32", so every glyph got the
    // tail of its own id as path data — 5 characters of nonsense that draw nothing. The whole
    // glyph grid rendered blank, the inspector reported «outline 5 chars» for an empty space,
    // and nothing upstream was wrong. Same trap waits for any name that ends another one.
    // A regex reads the RAW attribute, so XML entities come back undecoded — the writer correctly
    // stores data-text="&amp;" for the ampersand glyph, and without this the value is the five-character
    // string "&amp;" instead of "&". It showed as literal &quot; / &amp; under the glyph specimens,
    // and silently broke any comparison of `u` against a real character. Decoded in ONE pass: a
    // sequential replace would turn "&amp;lt;" into "<".
    const ENT = { '&amp;': '&', '&lt;': '<', '&gt;': '>', '&quot;': '"', '&apos;': "'" };
    const unesc = (v) => v == null ? v : v.replace(/&(?:amp|lt|gt|quot|apos);/g, e => ENT[e]);
    const attr = (src, name) => unesc(new RegExp('(?:^|\\s)' + name + '="([^"]*)"').exec(src)?.[1]);
    for (const m of text.matchAll(/<g\s+id="[^"]+"\s+data-font="[^"]*"[\s\S]*?<\/g>/g)) {
        const src = m[0], head = src.slice(0, src.indexOf('>') + 1);
        const font = {
            alias: attr(head, 'id'),
            id: attr(head, 'data-id') || attr(head, 'data-font'),
            safe: attr(head, 'data-font'),
            name: attr(head, 'data-name') || attr(head, 'data-id') || '',
            family: attr(head, 'data-family') || '',
            weight: attr(head, 'data-weight') || 'normal',
            style: attr(head, 'data-style') || 'normal',
            embedded: head.includes('data-embedded'),
            // the metric names FORMAT §B6 states and SvgOcdWriter writes — a name it does not write reads 0
            ascent: +attr(head, 'data-ascent') || 0, descent: +attr(head, 'data-descent') || 0,
            cap: +attr(head, 'data-capheight') || 0, xh: +attr(head, 'data-xheight') || 0,
            space: +attr(head, 'data-space') || 0,
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
                adv: +attr(p, 'data-advance') || 0, u: attr(p, 'data-text') || '',
                gname: attr(p, 'data-glyphname') || '' };
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
    const refused = ocdRefusal(files);
    if (refused) throw new Error(refused);

    const fontsRaw = files[OPF + 'pages/fonts.svg'] ? read(OPF + 'pages/fonts.svg') : '';
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
// URL): image hrefs → blob:, and the stored external glyph refs (fonts.svg#fN-gid) → local #fN-gid
// with the fonts <defs> embedded — a Service-Worker-served frame resolves fonts.svg relatively,
// an innerHTML injection cannot. Principle: the file is the render — no client renderer exists.
/** A stored page as a STANDALONE svg string: glyph refs localized (`fonts.svg#` → `#`) and the font defs
 *  embedded, so a consumer that has no base URL — an inline injection, an `<img>`, a canvas raster —
 *  paints the text instead of silently dropping it. Exported because it is the one place that knows
 *  how to make a page self-contained. */
export function pageSvg(xhtml, imageUrl, fontsDefs = '') {
    const a = xhtml.indexOf('<svg'), b = xhtml.lastIndexOf('</svg>');
    if (a < 0 || b < 0) return '';
    let s = xhtml.slice(a, b + 6)
        .replace(/(xlink:href|href)="\.\.\/images\/([^"]+)"/g,
            (all, attr, name) => imageUrl[name] ? `${attr}="${imageUrl[name]}"` : all)
        .replace(/(xlink:href|href)="(?:\.\.\/pages\/|\.\/)?fonts\.svg#/g, '$1="#');
    if (fontsDefs && s.includes('="#f') && !s.includes('data-ui="fonts"')) s = s.replace(/<svg\b[^>]*>/, m => m + fontsDefs);
    return s;
}

/** The <defs>…</defs> of a pages/fonts.svg — the glyph library, ready to embed. */
export function fontsDefsOf(t) {
    if (!t) return '';
    const a = t.indexOf('<defs'), b = t.lastIndexOf('</defs>');
    return a < 0 || b < 0 ? '' : t.slice(a, b + 7);
}

/* -- PAINT order vs READING order — the one place the client states the difference ----------------
 *
 * A page is written in PAINT order, and that is not a detail: the rendering comes first and can never
 * regress, so the DOM IS the paint and a viewer that draws it in order gets the document exactly right.
 * The logical layer is recovered ON TOP of that and never moves a pixel: `data-order` carries the
 * reading index of a node, emitted only where it DIFFERS from the paint position (FORMAT §B3).
 *
 *   draw in DOM order · read in data-order
 *
 * So anything that PAINTS, hit-tests or measures walks the DOM as it stands. Anything that READS the
 * content — search, read-aloud, a text export, the reading-flow overlay, a label built from runs —
 * re-sorts through here FIRST. Getting that backwards is silent: the page still looks perfect and only
 * the words come out in the wrong order, on exactly the documents (two columns, a bottom-up producer,
 * a knockout label) where it matters.
 *
 * The rule is `OCDReader.reorder`'s, which is the authority: the key is `data-order` when stated, and
 * the node's own position when it is not — a node without the attribute was emitted at its content
 * position already. */
export const inReadingOrder = (els) => [...els]
    .map((el, i) => ({ el, k: +(el.getAttribute?.('data-order') ?? i) }))
    .sort((a, b) => a.k - b.k)
    .map(x => x.el);

/* -- The line-final hyphen: one rule, the engine's (`OCDIndex.keepsHyphen`) -- */

const WORD = /[\p{L}\p{N}]/u;

/** The words a book writes on one line, from its runs' texts: `whole` and `compounds` (hyphen inside), lower
 *  case. A word a run ENDS on with a hyphen is a break in progress and says nothing. */
export function vocabulary(texts) {
    const whole = new Set(), compounds = new Set();
    for (const t of texts)
        for (const m of String(t || '').matchAll(/[\p{L}\p{N}][\p{L}\p{N}-]*/gu)) {
            if (m[0].endsWith('-')) continue;
            const w = m[0].toLowerCase();
            (w.includes('-') ? compounds : whole).add(w);
        }
    return { whole, compounds };
}

/** `before` ends on a hyphen that splits a word `after` continues (a letter or digit on both sides). */
export const splitsWord = (before, after) =>
    /[\p{L}\p{N}]-$/u.test(before) && WORD.test(after.trimStart().charAt(0));

/** Whether that hyphen stays when the word joins again (always with no space): what the book writes
 *  decides — the compound and never the word keeps it, the reverse drops it; with no evidence, a lowercase
 *  continuation is a syllable break and a capital one a compound ("Saint-" + "Gall"). */
export function keepsHyphen(before, after, vocab) {
    const l = (before.slice(0, -1).match(/[\p{L}\p{N}]+$/u) || [''])[0].toLowerCase();
    const a = after.trimStart(), r = (a.match(/^[\p{L}\p{N}]+/u) || [''])[0].toLowerCase();
    const c = !!vocab?.compounds.has(l + '-' + r), w = !!vocab?.whole.has(l + r);
    return c !== w ? c : !/^\p{Ll}/u.test(a);
}

/** Every run under `root`, in reading order, depth first — the text of a page as it is meant to be
 *  read. Stops at the run: its `<use>` children are glyphs, not content. */
export function readingRuns(root) {
    const out = [];
    (function walk(node) {
        for (const el of inReadingOrder(node.children || [])) {
            if (el.getAttribute?.('data-ocd') === 'run') out.push(el);
            else walk(el);
        }
    })(root);
    return out;
}

/** The box a run OCCUPIES, in its parent's user space: from its matrix and its font, never from
 *  `getBBox()`, which measures INK. A run whose glyphs carry no outline — the OCR layer over a scan, in
 *  render mode 3 — has no ink at all, so `getBBox()` answers 0 × 0 for text that is really there, and
 *  anything drawn from it (a search highlight, a box) collapses to a point. The horizontal extent runs
 *  from the first glyph's x to the last one's x plus its advance — painted glyphs are `<use x>`, inkless
 *  ones `data-blanks` tokens (`at:gid:x`, or `at:x` for a sentinel with no glyph) — and the vertical
 *  one from the font's descent to its ascent. `fonts` is `parseFonts()` of the book. Returns
 *  `{x, y, width, height}` (axis-aligned), or null for a run that cannot be measured. */
export function runBox(el, fonts) {
    const m = /matrix\(([^)]+)\)/.exec(el.getAttribute('transform') || '');
    if (!m) return null;
    const [a, b, c, d, e, f] = m[1].trim().split(/[\s,]+/).map(Number);
    const use = [...el.children].find(u => u.localName === 'use');
    const alias = use ? /#(f\d+)-\d+$/.exec(xhref(use))?.[1] : null;
    const id = el.getAttribute('data-font');
    const font = (fonts || []).find(x => (alias && x.alias === alias) || x.safe === id || x.id === id);
    const adv = (gid) => font?.glyphByGid.get(gid)?.adv || font?.space || 0.5;
    let x0 = Infinity, x1 = -Infinity;
    const take = (x, gid) => { x0 = Math.min(x0, x); x1 = Math.max(x1, x + (gid == null ? 0 : adv(gid))); };
    for (const u of el.children) {
        if (u.localName !== 'use') continue;
        const href = xhref(u);
        take(+(u.getAttribute('x') || 0), +href.slice(href.lastIndexOf('-') + 1));
    }
    for (const tok of (el.getAttribute('data-blanks') || '').trim().split(/\s+/)) {
        const p = tok.split(':');
        if (p.length === 3) take(+p[2], +p[1]);
        else if (p.length === 2) take(+p[1], null);
    }
    if (!(x1 > x0)) return null;
    const asc = font?.ascent || 0.8, desc = Math.abs(font?.descent || 0.2);
    let bx0 = Infinity, by0 = Infinity, bx1 = -Infinity, by1 = -Infinity;
    for (const [u, v] of [[x0, -desc], [x1, -desc], [x1, asc], [x0, asc]]) {
        const X = a * u + c * v + e, Y = b * u + d * v + f;
        bx0 = Math.min(bx0, X); bx1 = Math.max(bx1, X); by0 = Math.min(by0, Y); by1 = Math.max(by1, Y);
    }
    return { x: bx0, y: by0, width: bx1 - bx0, height: by1 - by0 };
}

export function pageViewport(p) {
    return { vw: p.width || 0, vh: p.height || 0 };
}

/* ══ AUTHOR: OcdPage — one LIVE page of the document ═══════════════════════ */

/** One page under authoring: the displayed SVGSVGElement itself. Model coordinates: POINTS, Y-UP; chrome
 *  strata may address viewBox space directly. */
export class OcdPage {

    /** Adopt a LIVE displayed page (a reader frame's svg) as the authoring surface — the displayed DOM is
     *  the source of truth. `fonts` is `parseFonts()` of the container's library. */
    static adopt(svg, fonts = []) {
        const vb = (svg.getAttribute('viewBox') || '').trim().split(/\s+/).map(Number);
        return new OcdPage(svg, vb[2] || 595.28, vb[3] || 841.89, fonts);
    }

    /** The page's own frame — the MEDIA box, in SVG space. Everything the page paints is anchored
     *  on it; the viewBox is a WINDOW over it (a crop), so it must never enter a placement. Reads
     *  data-mediabox; falls back to the viewBox for a page that states none. */
    get frame() {
        const m = (this.svg?.getAttribute('data-mediabox') || '').trim().split(/[\s,]+/).map(Number);
        if (m.length === 4 && m.every(Number.isFinite)) return { x: m[0], y: m[1], w: m[2], h: m[3] };
        const vb = (this.svg?.getAttribute('viewBox') || '').trim().split(/[\s,]+/).map(Number);
        return vb.length === 4 && vb.every(Number.isFinite)
            ? { x: vb[0], y: vb[1], w: vb[2], h: vb[3] } : { x: 0, y: 0, w: this.w, h: this.h };
    }

    /** Internal — a page is adopted (`adopt`). */
    constructor(svg, w, h, fonts) {
        this.svg = svg; this.w = w; this.h = h; this.fonts = fonts;
        this.styles = new Map();                    // css body → class name (writer's dedup)
        this.sn = 0;                                // next .sN index
        this.styleEl = svg.querySelector(':scope > style');
        if (this.styleEl)                           // absorb stored classes — cls() never collides
            for (const m of this.styleEl.textContent.matchAll(/\.(s(\d+))\{([^}]*)\}/g)) {
                this.styles.set(m[3], m[1]);
                this.sn = Math.max(this.sn, +m[2] + 1);
            }
    }

    /** The font `sel` names (its safe id, id, name or alias), else the first — null when there is none. */
    font(sel) {
        if (!this.fonts.length) return null;
        return (sel && this.fonts.find(f => f.safe === sel || f.id === sel || f.name === sel || f.alias === sel)) || this.fonts[0];
    }

    // THE page flip, on the page's FRAME (the media box) — never on the viewBox, which is only the
    // crop window: a page cropped after the fact must not move a single element it already carries.
    get flip() { const f = this.frame; return `matrix(1 0 0 -1 ${F(-f.x)} ${F(f.y + f.h)})`; }

    /* ── strata plumbing ───────────────────────────────────────────────── */

    style() {
        if (!this.styleEl) { this.styleEl = svgel('style'); this.svg.insertBefore(this.styleEl, this.svg.firstChild); }
        return this.styleEl;
    }

    /** First child past the <style>/<defs> head — where content begins. */
    #contentStart() {
        for (const c of this.svg.children)
            if (c.localName !== 'style' && c.localName !== 'defs') return c;
        return null;
    }

    /** Mount a model element into an Element, or at the end of the content zone (before any
     *  data-z="over" chrome). */
    mount(el, into) {
        if (into instanceof Element) { into.appendChild(el); return el; }
        this.svg.insertBefore(el, this.svg.querySelector(':scope > [data-ui][data-z="over"]'));
        return el;
    }

    /* ── paint classes ─────────────────────────────────────────────────── */

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

    /** Native OCD text: one run of em-space glyph uses — searchable, selectable,
     *  engine-exportable. (x, y) = the BASELINE start, page space (pt, Y-up). Live
     *  glyphs resolve locally (#fN-gid over a data-ui defs); build restores fonts.svg#.
     *  Returns { el (the paragraph <g>), run (the text run <g>), width (pt) }. */
    text({ text, x, y, size, font, fill = '#000', align = 'left', into }) {
        const f = this.font(font);
        if (!f) throw new Error('no font registered — open a container carrying pages/fonts.svg');
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
        // data-size is STATED, never left to be inferred (FORMAT §B3): the run matrix is fs·flip·tr, and a
        // reader that has to recover fs from it gets 0 here — the text is then written at size zero, saved,
        // and invisible everywhere, with no error at any point. Measured: a run authored without it came
        // back `data-size="0"` and painted nothing in the OCD renderer AND in the PDF, while its text and
        // its 17 glyph references round-tripped perfectly.
        const t = svgel('g', { id: this.#nextRunId(), 'data-ocd': 'run', 'data-font': f.safe, 'data-size': F(size),
            class: this.cls({ fill }),
            'data-text': text, transform: (fr => `matrix(${F(size)} 0 0 ${F(-size)} ${F(x - fr.x)} ${F(fr.y + fr.h - y)})`)(this.frame) });
        // EVERY character is accounted for: painted as a <use>, or recorded in data-blanks with its x in em.
        // A glyph with no outline — the space, an unmapped char — that is skipped in silence makes the
        // reader align N-1 glyphs against N characters of data-text and TRUNCATE the run's tail. Measured:
        // "Bonjour, Jean-Luc!" came back "Bonjour, Jean-Luc", the space unrecorded, no error anywhere.
        // Same shape as SvgOcdWriter's: `index:x`, or `index:gid:x` when the glyph is known but inkless.
        const blanks = [];
        run.forEach(({ g, ax: gx }, i) => {
            if (g && g.d) { this.glyphDef(f, g); t.appendChild(svgel('use', { href: `#${f.alias}-${g.gid}`, x: F(gx + shift) })); }
            else blanks.push(`${i}:${g ? g.gid + ':' : ''}${F(gx + shift)}`);
        });
        // FORMAT §B4: every character is a <use> or a data-blanks entry, or the reader truncates the run
        if (blanks.length) t.setAttribute('data-blanks', blanks.join(' '));
        // The grammar's kinds: OCDReader treats any other data-ocd as a paint carrier and splices the run
        // out of its paragraph. Ids follow the engine's pN / tN schemes — a structure reference names a
        // node by id, and a page mixing two schemes has ids nobody can predict.
        const l = svgel('g', { 'data-ocd': 'line' });
        const p = svgel('g', { id: this.#nextId('p', 'paragraph'), 'data-ocd': 'paragraph' });
        l.appendChild(t); p.appendChild(l);
        this.mount(p, into);
        return { el: p, run: t, width: ax * size };
    }

    /** Next page-scoped id in the engine's own scheme — `t1, t2, …` for a run, `p1, p2, …` for a
     *  paragraph. Page-local by contract (IdStamper), so the only thing to avoid is a collision ON THIS
     *  page: the highest number already there is read once per kind and counted up from. */
    #nextId(prefix, kind) {
        this.ids ??= {};
        if (this.ids[prefix] == null) {
            let n = 0;
            for (const g of this.svg.querySelectorAll(`g[data-ocd="${kind}"]`)) {
                const m = new RegExp(`^${prefix}(\\d+)$`).exec(g.id || '');
                if (m) n = Math.max(n, +m[1]);
            }
            this.ids[prefix] = n;
        }
        return prefix + (++this.ids[prefix]);
    }

    /** The run id, in the engine's tN scheme — the text-layer tooling addresses runs by exactly it. */
    #nextRunId() { return this.#nextId('t', 'run'); }

    /** Local glyph defs so external fonts.svg# uses render live — data-ui, stripped at build. */
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
        // A wrapper is chrome that holds REAL CONTENT: removing it would take the content with it, so it
        // is unwrapped — children lifted into its place, the wrapper dropped. Augment's animation groups
        // are this: they wrap runs to carry a CSS animation, and marking them plain `data-ui` would have
        // deleted six runs from the page. Measured before the rule existed: 6 `data-px-anim` wrappers
        // written straight into the member by the next tool that persisted the page.
        c.querySelectorAll('[data-ui="wrap"]').forEach(e => {
            while (e.firstChild) e.parentNode.insertBefore(e.firstChild, e);
            e.remove();
        });
        c.querySelectorAll('[data-ui]').forEach(e => e.remove());     // UI strata never ship
        for (const u of c.querySelectorAll('use')) {                  // #fN-gid → fonts.svg#fN-gid
            const href = u.getAttribute('href');
            if (href?.startsWith('#')) u.setAttribute('href', 'fonts.svg' + href);
        }
        const st = c.querySelector(':scope > style'); if (st && !st.textContent.trim()) st.remove();
        const de = c.querySelector(':scope > defs');  if (de && !de.children.length) de.remove();
        return pageShell(new XMLSerializer().serializeToString(c), this.w, this.h, n);
    }
}

/** THE page member shell: a serialized page <svg> wrapped into its stored xhtml.
 *  Shared by OcdPage.xhtml() and book.persist() — one shell, everywhere. */
export function pageShell(svgSrc, w, h, n = 1) {
    // NO DOCTYPE — and this is not a nicety. The engine's reader is XXE-hardened (JxXml, DOCTYPE
    // disallowed outright, FORMAT §A7), so a page wrapper carrying one is REFUSED: any container
    // holding a page this function wrote — an edit, an augment, a crop — died on the next export with
    // «DOCTYPE is disallowed», with the tool blamed for the writer's byte. It is optional in XHTML5
    // and the engine's own writer never emits it; matching it is the contract.
    return '<?xml version="1.0" encoding="UTF-8"?>\n'
        + '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">\n<head>\n'
        + `<meta charset="utf-8"/>\n<title>Page ${n}</title>\n`
        + `<meta name="viewport" content="width=${F(w)}, height=${F(h)}"/>\n</head>\n<body style="margin:0;padding:0">\n`
        + svgSrc + '\n</body>\n</html>\n';
}

export const xhref = (el) => el.getAttributeNS?.(XLINK_NS, 'href') || el.getAttribute('xlink:href') || el.getAttribute('href') || '';
