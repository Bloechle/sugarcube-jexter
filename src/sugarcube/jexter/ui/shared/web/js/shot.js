// shot.js — ONE small picture of a page, for every surface that needs one.
//
// The rail's thumbnails, the Pages light table, and any panel that wants to show a page without
// opening it all want the same thing, and the obvious implementation is the expensive one: mount the
// page in an iframe. A page frame is a whole DOCUMENT — its DOM, its styles, and the book's glyph
// library — so a rail scrolling through a long book spins up and tears down documents by the dozen,
// and any surface that re-renders its list navigates them all again. That is a picture's job, not a
// document's.
//
// So: the page member (already in memory) is turned into a STANDALONE svg and drawn once into a
// canvas. What the caller gets back is a `blob:` URL for an `<img>`.
//
// THE FONTS ARE THE WHOLE DIFFICULTY, and it is worth being precise about why. The format shares them
// on purpose: they live once in `pages/fonts.svg` and pages reference glyphs externally, so a reader
// fetches the library once for the whole book. But an SVG loaded as an IMAGE is sandboxed — it may not
// reach outside itself — so a raster has to carry its glyphs inside. Carrying the WHOLE library is
// what makes that expensive: measured on a 4.1 MB library, 270 ms per page, against 10 ms for the same
// page carrying only the 28 glyphs it actually uses — identical ink, 62 370 pixels either way. And
// building that subset by scanning the library per page costs 41 ms, more than the raster itself, so
// the library is INDEXED ONCE per book (id → def) and a page's subset is a handful of lookups.
//
//   import { shots } from '/shared/js/shot.js';
//   const url = await shots.url(idx, { w, h, rot, width: 150 });   // blob: URL, cached
//   shots.drop(path);   shots.clear();
//
// The cache is keyed by MEMBER PATH and dropped when that member is written (`book.onChange`), so a
// crop or a rotation refreshes its own page and nothing else. It is bounded: the least recently used
// pictures are evicted and their blob URLs revoked, because two surfaces sharing one cache over a
// 600-page book is exactly where an unbounded Map stops being a cache and becomes a leak.

import { book } from './book.js';
import { pageSvg } from './ocd.js';

const CAP = 240;                                   // pictures kept; ~a few KB each
// Keyed by member path AND the geometry it was drawn with. Keying on the path alone let a picture
// drawn from a page's OLD shape satisfy a request for its new one: a rotation writes the member, the
// rail's onChange watcher repaints from state that has not been updated yet, that stale picture goes
// back into the cache, and the correct repaint a moment later finds a hit and shows it. A cache key
// has to state what the picture IS, not merely which page it is of.
const cache = new Map();                           // path|WxH@rot/width → Promise<blob url>   (insertion order = LRU)
const keyOf = (path, g) => `${path}|${g.w}x${g.h}@${g.rot || 0}/${g.width}`;
const MIME = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp' };
const MAX_INLINE = 8 * 1024 * 1024;                // an image bigger than this is skipped, not base64'd
const dec = (b) => new TextDecoder().decode(b);

let glyphs = null;                                 // id → "<path …/>", built once per book
let glyphSource = null;                            // the fonts.svg bytes it was built from

/** The glyph library, indexed. Built on first use and rebuilt only when `pages/fonts.svg` itself changes —
 *  a page write must not cost a re-index of the whole book. */
function index() {
    const bytes = book.get(book.member('pages/fonts.svg'));
    if (glyphs && glyphSource === bytes) return glyphs;
    glyphs = new Map();
    glyphSource = bytes;
    if (!bytes) return glyphs;
    for (const m of dec(bytes).matchAll(/<path\s[^>]*id="([^"]+)"[^>]*\/>/g)) glyphs.set(m[1], m[0]);
    return glyphs;
}

/** The defs a page needs: its own glyphs, and no others. */
function subset(xhtml) {
    const lib = index();
    if (!lib.size) return '';
    const want = new Set();
    for (const m of xhtml.matchAll(/(?:xlink:)?href="(?:[^"]*#)?(f\d+-\d+)"/g)) want.add(m[1]);
    let out = '';
    for (const id of want) { const d = lib.get(id); if (d) out += d; }
    return out ? '<defs>' + out + '</defs>' : '';
}

/** The images a page places, as data URIs — an image-loaded SVG cannot fetch them either.
 *
 *  Inlined AS THEY ARE, deliberately. Downscaling them first for a thumbnail is the obvious idea and
 *  the measurement refused it: on a real page carrying a 65 KB photograph, inlining it whole cost 2 ms
 *  of encoding and 56 KB of string for a 56 ms raster, while decoding and re-encoding it at thumbnail
 *  size cost 16 ms to save those 56 KB — 65 ms against 56. The pictures a document carries are already
 *  compressed, and base64 of them is cheap next to parsing the page's glyph outlines, which is where
 *  the time actually goes (measured on a heavy CJK page: 32 ms to decode the SVG, 11 ms to draw, 11 ms
 *  to index the whole library once). MAX_INLINE stays as the guard against the absurd case. */
async function images(xhtml) {
    const out = {};
    for (const m of xhtml.matchAll(/(?:xlink:)?href="\.\.\/images\/([^"]+)"/g)) {
        const name = m[1];
        if (out[name]) continue;
        const bytes = book.get(book.member('images/' + name));
        if (!bytes || bytes.length > MAX_INLINE) continue;
        const mime = MIME[(name.split('.').pop() || '').toLowerCase()] || 'image/png';
        out[name] = await new Promise((res, rej) => {
            const r = new FileReader();
            r.onload = () => res(r.result);
            r.onerror = rej;
            r.readAsDataURL(new Blob([bytes], { type: mime }));
        });
    }
    return out;
}

/** Draw one page. `w`/`h` are the page's OWN size and `rot` its metadata rotation: the turn is baked
 *  into the canvas, so what comes out is simply a picture of the page as it must be read. */
async function draw(path, { w, h, rot = 0, width = 150 }) {
    const bytes = book.get(path);
    if (!bytes) throw new Error('no member ' + path);
    const xhtml = dec(bytes);
    const svg = pageSvg(xhtml, await images(xhtml), subset(xhtml));
    const src = URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml' }));
    try {
        const img = new Image();
        img.src = src;
        await img.decode();
        const swap = rot === 90 || rot === 270;
        const scale = (width * (window.devicePixelRatio || 1) * 1.4) / (swap ? h : w);
        const cv = document.createElement('canvas');
        cv.width = Math.max(1, Math.round((swap ? h : w) * scale));
        cv.height = Math.max(1, Math.round((swap ? w : h) * scale));
        const g = cv.getContext('2d');
        g.fillStyle = '#fff'; g.fillRect(0, 0, cv.width, cv.height);
        g.scale(scale, scale);
        if (rot === 90) { g.translate(h, 0); g.rotate(Math.PI / 2); }
        else if (rot === 180) { g.translate(w, h); g.rotate(Math.PI); }
        else if (rot === 270) { g.translate(0, w); g.rotate(-Math.PI / 2); }
        g.drawImage(img, 0, 0, w, h);
        const blob = await new Promise(res => cv.toBlob(res, 'image/png'));
        return URL.createObjectURL(blob);
    } finally { URL.revokeObjectURL(src); }
}

function evict() {
    while (cache.size > CAP) {
        const [oldest, pending] = cache.entries().next().value;
        cache.delete(oldest);
        Promise.resolve(pending).then(u => u && URL.revokeObjectURL(u), () => {});
    }
}

export const shots = {
    /** The picture of page `idx`, made once and kept. Geometry comes from the caller because the page
     *  record is the chassis's, not this module's. */
    url(idx, geom) {
        const path = book.pagePath(idx);
        const key = keyOf(path, geom);
        let p = cache.get(key);
        if (p) { cache.delete(key); cache.set(key, p); return p; }     // touch: most recent last
        p = draw(path, geom).catch(() => null);
        cache.set(key, p);
        evict();
        return p;
    },
    /** Forget every picture of one page — its member changed, so all of its shapes are stale. */
    drop(path) {
        for (const key of [...cache.keys()]) {
            if (!key.startsWith(path + '|')) continue;
            const p = cache.get(key);
            cache.delete(key);
            Promise.resolve(p).then(u => u && URL.revokeObjectURL(u), () => {});
        }
    },
    /** Forget everything — another book, or none. */
    clear() {
        for (const p of cache.values()) Promise.resolve(p).then(u => u && URL.revokeObjectURL(u), () => {});
        cache.clear();
        glyphs = null; glyphSource = null;
    },
    get size() { return cache.size; },
};

// A page member is written: its picture is stale, and only its picture.
book.onChange(path => shots.drop(path));
