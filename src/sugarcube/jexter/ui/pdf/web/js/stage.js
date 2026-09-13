// stage.js — PDF Inspector render stage, inside the isolated iframe (page render + zoom).
//
// Mirrors the Jexter Lab stage so the two tools share ONE zoom model and the SAME vector path:
//   • OCD-backed sources (OCD, OCD↺) render as SVG — resolution-independent, crisp at any zoom,
//     no blur-up — exactly like Jexter Lab renders its OCD model.
//   • Rasterizer sources (PDFBox, Direct) stay PNG with blur-up (DPI re-fetch on zoom settle), since
//     they have no vector form.
// The page is sized in CSS (width = pageWidthPt × zoom); Ctrl + wheel zooms toward the pointer; +/-/0
// and Fit behave the same. The chrome (pages list, COS tree, detail, profiler, footer) lives in the
// parent; the two talk by DIRECT same-origin calls — the parent reaches window.stage, the stage calls
// back into the parent's window.shell.

const shell = () => { try { return (window.parent && window.parent !== window) ? window.parent.shell : window.shell; } catch { return null; } };
const call  = (m, ...a) => { const s = shell(); if (s && typeof s[m] === 'function') s[m](...a); };

// The OCD-backed sources display the STORED page SVG verbatim (loadOcd hands each page's
// markup straight from the OCD-EPUB — the file is the render; there is no client renderer).
import { loadOcd, pageViewport } from '/shared/js/ocd.js';

const MAX_DPI = 600, THUMB_DPI = 24, DPI_STEP = 24;
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));

const state = { page: null, zoom: 1, compare: { on: false, mode: 'diff', value: 0.5 } };  // page = { i, w, h, version, src, vector, vw, vh }
const ocd = { doc: null, key: null };           // cached OCD model, keyed by `${src}@${version}`

const stageEl  = () => document.getElementById('stage');
const canvasEl = () => document.getElementById('canvas');
const imgEl    = () => document.getElementById('page');
const pageBEl  = () => document.getElementById('page-b');
const cmpEl    = () => document.getElementById('cmp');
const vecEl    = () => document.getElementById('vec');

// page display size in CSS px: the OCD viewBox (vw/vh) for vector, else the PDF point size.
function dims() { const p = state.page; return (p && p.vw && p.vh) ? { w: p.vw, h: p.vh } : { w: p?.w || 612, h: p?.h || 792 }; }

// ── URLs ──────────────────────────────────────────────────────────────────────
function renderDpi() {                           // raster only: DPI from the on-screen CSS width
    const w = dims().w;
    const cssW = (canvasEl()?.clientWidth) || (w * state.zoom);
    const need = cssW * (window.devicePixelRatio || 1) * 72 / w;
    return clamp(Math.ceil(need / DPI_STEP) * DPI_STEP, 72, MAX_DPI);
}
function pageUrl(dpi, src) {                      // src defaults to the page's source; pass 'pdfbox'/'ocd' to override (compare)
    const p = state.page; if (!p) return '';
    const s = src === undefined ? p.src : (src === 'pdfbox' ? null : src);
    return `/api/page?i=${p.i}&dpi=${dpi}&v=${p.version}${s ? '&src=' + encodeURIComponent(s) : ''}`;
}

// ── paint: vector (OCD model → SVG, Jexter-Lab renderer) or raster (PNG blur-up) ──
let tok = 0;
function paintRaster() {
    const img = imgEl(), vec = vecEl(), p = state.page; if (!img || !p) return;
    if (vec) vec.innerHTML = '';
    img.style.display = '';
    const t = ++tok, full = pageUrl(renderDpi());
    img.classList.add('is-loading');
    img.setAttribute('src', pageUrl(THUMB_DPI));     // instant low-res, then swap to the crisp raster
    const pre = new Image();
    pre.onload  = () => { if (t === tok) { img.setAttribute('src', full); img.classList.remove('is-loading'); } };
    pre.onerror = () => { if (t === tok) img.classList.remove('is-loading'); };
    pre.src = full;
}
async function ensureOcd() {                         // load (and cache) the .ocd for the current source
    const p = state.page, key = `${p.src}@${p.version}`;
    if (ocd.key === key && ocd.doc) return ocd.doc;
    const res = await fetch(`/api/ocd?src=${encodeURIComponent(p.src)}&v=${p.version}`);
    if (!res.ok) throw new Error('ocd ' + res.status);
    const doc = await loadOcd(await res.blob());               // the OCD-EPUB: stored SVG + members
    ocd.doc = doc; ocd.key = key;
    return doc;
}
async function paintVector(doFit) {
    const vec = vecEl(), img = imgEl(), p = state.page; if (!vec || !p) return;
    const t = ++tok;
    if (img) { img.style.display = 'none'; img.removeAttribute('src'); }
    try {
        const doc = await ensureOcd(); if (t !== tok) return;
        const pg = doc.pages[p.i]; if (!pg) throw new Error('no page ' + p.i);
        const v = pageViewport(pg); p.vw = v.vw; p.vh = v.vh;    // real (rotation-aware) dims from the stored page
        (doFit ? fit() : resize());
        vec.innerHTML = pg.svg;                                  // the STORED page, verbatim (image hrefs → blob:)
        const el = vec.querySelector('svg');                     // fill the zoom-sized canvas; viewBox keeps it crisp
        if (el) { el.removeAttribute('width'); el.removeAttribute('height'); el.style.width = '100%'; el.style.height = '100%'; el.style.display = 'block'; }
    } catch { if (t === tok) { vec.innerHTML = ''; if (img) img.style.display = ''; } }
}
function paint(doFit) { const p = state.page; if (!p) return; state.compare.on ? paintCompare(doFit) : (p.vector ? paintVector(doFit) : (doFit && fit(), paintRaster())); }
let rt = 0;
function repaintSoon() { clearTimeout(rt); rt = setTimeout(() => { if (!state.page) return; if (state.compare.on) paintCompare(false); else if (!state.page.vector) paintRaster(); }, 140); }

// ── compare: PDFBox raster (base) vs OCD raster (overlay), same DPI → a true pixel diff ──
// Modes: diff = mix-blend-mode:difference (identical pixels → black, Fid2 made visible); fade = OCD
// opacity slider; wipe = OCD clipped to the left fraction. Both layers fill the zoom-sized canvas so
// they scale together. OCD raster (not the SVG) is used on purpose — the browser's SVG rasterizer
// would differ sub-pixel from PDFBox and muddy the diff.
let ctok = 0;
function paintCompare(doFit) {
    const cv = canvasEl(), a = imgEl(), b = pageBEl(), p = state.page; if (!cv || !a || !b || !p) return;
    cv.classList.add('compare');
    const vec = vecEl(); if (vec) vec.innerHTML = '';
    a.style.display = '';
    doFit ? fit() : resize();
    const t = ++ctok, dpi = renderDpi();
    a.classList.add('is-loading');
    const baseUrl = pageUrl(dpi, 'pdfbox'), ovUrl = pageUrl(dpi, 'ocd');
    const pa = new Image(); pa.onload = () => { if (t === ctok) { a.src = pa.src; a.classList.remove('is-loading'); } }; pa.onerror = () => { if (t === ctok) a.classList.remove('is-loading'); }; pa.src = baseUrl;
    const pb = new Image(); pb.onload = () => { if (t === ctok) b.src = pb.src; }; pb.src = ovUrl;
    applyCompareStyle();
}
function applyCompareStyle() {
    const cmp = cmpEl(); if (!cmp) return;
    const { mode, value } = state.compare;
    cmp.classList.remove('diff', 'fade', 'wipe'); cmp.classList.add(mode);
    cmp.style.opacity = mode === 'fade' ? String(value) : '1';
    cmp.style.clipPath = mode === 'wipe' ? `inset(0 ${Math.round((1 - value) * 100)}% 0 0)` : 'none';
}

// ── zoom ─────────────────────────────────────────────────────────────────────
function resize() {                              // CSS-size the page surface to the current zoom
    const c = canvasEl(), p = state.page; if (!c || !p) return;
    const d = dims();
    c.style.width  = (d.w * state.zoom) + 'px';
    c.style.height = (d.h * state.zoom) + 'px';
    call('onZoom', Math.round(state.zoom * 100));
}
function applyZoom() { resize(); repaintSoon(); }   // raster re-fetches crisp on settle; vector scales for free
function fit() {
    const p = state.page, s = stageEl(); if (!p || !s) return;
    const w = (s.clientWidth || 800) - 40;
    state.zoom = clamp(w / dims().w, 0.1, 3);
    resize();
    s.scrollTop = 0; s.scrollLeft = 0;
}
// Set zoom, optionally keeping a content point (px,py) under the cursor (cx,cy) — "zoom toward pointer".
function setZoom(z, toward) {
    const s = stageEl(); const old = state.zoom;
    state.zoom = clamp(z, 0.1, 8);
    applyZoom();
    if (s && toward) { const k = state.zoom / old; s.scrollLeft = toward.px * k - toward.cx; s.scrollTop = toward.py * k - toward.cy; }
}

// ── input ──────────────────────────────────────────────────────────────────
function wire() {
    stageEl().addEventListener('wheel', e => {       // Ctrl + wheel → zoom toward the pointer
        if (!e.ctrlKey || !state.page) return;
        e.preventDefault();
        const s = stageEl(), r = s.getBoundingClientRect();
        const cx = e.clientX - r.left, cy = e.clientY - r.top, px = s.scrollLeft + cx, py = s.scrollTop + cy;
        setZoom(state.zoom * (e.deltaY < 0 ? 1.12 : 1 / 1.12), { px, py, cx, cy });
    }, { passive: false });

    window.addEventListener('keydown', e => {
        if (!state.page) return;
        if (e.key === '+' || e.key === '=') { setZoom(state.zoom + 0.15); e.preventDefault(); }
        else if (e.key === '-') { setZoom(state.zoom - 0.15); e.preventDefault(); }
        else if (e.key === '0') { fit(); e.preventDefault(); }
    });
    window.addEventListener('resize', () => { if (state.page) resize(); });   // chrome layout resized the iframe

    const hasFiles = e => [...(e.dataTransfer?.types || [])].includes('Files');
    let depth = 0;
    window.addEventListener('dragenter', e => { if (!hasFiles(e)) return; e.preventDefault(); if (depth++ === 0) call('veil', true); });
    window.addEventListener('dragover',  e => { if (hasFiles(e)) e.preventDefault(); });
    window.addEventListener('dragleave', e => { if (!hasFiles(e)) return; if (--depth <= 0) { depth = 0; call('veil', false); } });
    window.addEventListener('drop',      e => { if (!hasFiles(e)) return; e.preventDefault(); depth = 0; call('veil', false); const f = e.dataTransfer.files?.[0]; if (f) call('openDropped', f); });
}

// ── public API (called directly by the parent, same-origin) ──────────────────
window.stage = {
    // Show a page. { i, w, h, version, src, vector } — w/h in points; vector → render the OCD model as SVG.
    setPage(p) { state.page = { ...p }; stageEl()?.classList.remove('empty'); paint(true); call('onPageReady', p.i); },
    // Switch the rasterizer / vector source and re-render the current page in place (keeping the zoom).
    setSource(src, vector) { if (state.page) { state.page.src = src || null; state.page.vector = !!vector; state.page.vw = state.page.vh = null; paint(false); } },
    pageUrl(dpi) { return pageUrl(dpi); },        // the chrome uses this for "Save page image"
    fit,
    setZoom(z) { setZoom(z); },
    zoomBy(f) { setZoom(state.zoom * f); },
    zoomStep(d) { setZoom(state.zoom + d); },
    relayout() { if (state.page) resize(); },     // chrome nudge after a layout change
    setTheme(dark) { const h = document.documentElement; h.classList.toggle('dark', !!dark); h.setAttribute('data-theme', dark ? 'dark' : 'light'); },

    // Compare mode — overlay the PDFBox and OCD rasters of the current page.
    setCompare(on) {
        state.compare.on = !!on;
        if (on) paintCompare(true);
        else { canvasEl()?.classList.remove('compare'); pageBEl()?.removeAttribute('src'); paint(true); }   // restore the normal source
    },
    setCompareMode(mode) { state.compare.mode = mode; applyCompareStyle(); },
    setCompareValue(v) { state.compare.value = clamp(+v || 0, 0, 1); applyCompareStyle(); },

    clear() {
        state.page = null; ocd.doc = null; ocd.key = null; state.compare.on = false;
        canvasEl()?.classList.remove('compare'); pageBEl()?.removeAttribute('src');
        stageEl()?.classList.add('empty'); imgEl()?.removeAttribute('src'); const v = vecEl(); if (v) v.innerHTML = '';
    },
};

wire();
call('onStageReady');
