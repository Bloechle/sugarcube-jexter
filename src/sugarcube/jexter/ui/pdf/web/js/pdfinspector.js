/**
 * pdfinspector.js — PDF Inspector, standalone, in the Prism look.
 *
 * What comes straight out of PDFBox (no OCD):
 *   • Viewer    — the page raster (footer picks the rasterizer: PDFBox | Direct | OCD | OCD↺)
 *   • Structure — the ONE document COS tree (trailer → catalog → pages → page → contents/resources).
 *                 Selecting a page in the list jumps to its node; the detail panel shows the
 *                 node's Info · Decoded · Image, plus the current page's Operators · Text.
 *
 * Self-contained: the shell glue (open / pages / zoom-pan / tabs / theme / save / drop) lives here
 * directly on the qry stack — no shared inspector shell. Server REST:
 *   POST /api/open?name=…  → meta            GET /api/page?i&dpi[&src] → PNG
 *   GET  /api/tree?root=doc → [node…]         GET /api/content?page · /api/stream?obj · /api/render?obj · /api/text?page
 */
import { boot, theme, icons, toast, makeTabs, makeRow, sortableTable }
    from 'https://cdn.jsdelivr.net/gh/Bloechle/qry-js@1.3.0/qry-kit.js';
import { loadOcd, esc } from '/shared/js/ocd.js';   // the OCD-EPUB carries the fonts (pages/f.svg, the single representation)

const R = Math.round;
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
const J = (p, opt) => fetch(p, opt).then(r => r.json());


// ── state ──────────────────────────────────────────────────────────────────
let docMeta = null, cur = 0, tab = 'page', docVersion = 0;
let renderSrc = null;                          // null = server default (PDFBox)
let tabsCtrl = null, detail = null, structBuilt = false;
let fontV = -1, fontDoc = null;            // font inspector cache, keyed by docVersion
let resV = -1, resData = null;             // resources (images) cache, keyed by docVersion
const cache = { ops: {}, text: {} };

const RENDER_SOURCES = [
    { id: 'pdfbox',     label: 'PDFBox',     icon: 'box',        tip: 'Apache PDFBox native rasterizer \u2014 the reference' },
    { id: 'pdf-direct', label: 'Direct',     icon: 'pen-tool',   tip: 'Our renderer straight from the PDF (PdfRenderer) \u2014 no OCD model' },
    { id: 'ocd',        label: 'OCD',        icon: 'box-select', vector: true, tip: 'Our OCD model rendered as vector SVG (same client renderer as Jexter Lab) \u2014 selectable text, crisp at any zoom' },
    { id: 'ocd-file',   label: 'OCD \u21BA', icon: 'file-check', vector: true, tip: 'OCD model after .ocd write + re-read, rendered as vector SVG (serialization round-trip)' },
];
const isVectorSrc = (s) => RENDER_SOURCES.some(r => r.id === s && r.vector);

// ── status ───────────────────────────────────────────────────────────────────
function status(text, busy) { $('#status').html(`<i data-lucide="${busy ? 'loader' : 'scan-search'}"></i> ${text}`); icons(); }

// ── render stage (iframe) bridge ───────────────────────────────────────────────
// The page raster + zoom now live in an isolated iframe (stage.html → js/stage.js), exactly like
// Jexter Lab. The chrome just hands the current page over; the stage owns zoom, DPI and the blur-up.
const frame    = () => document.getElementById('stage-frame');
// Named access makes the frame's <div id="stage"> shadow window.stage until stage.js
// has installed the API — so the element, not the API, comes back during boot. Only
// hand out the real thing: it quacks (relayout) or it is null.
const stageApi = () => { const s = frame()?.contentWindow?.stage; return typeof s?.relayout === 'function' ? s : null; };
function applyPageToStage() {
    const p = docMeta && docMeta.pages[cur]; if (!p) return;
    stageApi()?.setPage({ i: cur, w: p.w, h: p.h, version: docVersion, src: renderSrc, vector: isVectorSrc(renderSrc) });
}

// ── pages list ─────────────────────────────────────────────────────────────────
function buildPageList() {
    const list = $('#pagelist').empty();
    docMeta.pages.forEach((p, i) => {
        const row = $.create('button', { class: 'pagerow', 'data-i': String(i), role: 'listitem' });
        row.attr('title', `Page ${i + 1} \u00B7 ${R(p.w)}\u00D7${R(p.h)}${p.rot ? ' \u21BB' + p.rot + '\u00B0' : ''}`);
        row.html(`<img class="pagerow-thumb" alt="" src="/api/page?i=${i}&dpi=36&v=${docVersion}">`
            + `<span class="pagerow-num">${i + 1}</span>`);
        list.add(row);
    });
    $('#page-count').text(`${docMeta.pages.length}`);
}
function selectPage(i) {
    if (!docMeta || i < 0 || i >= docMeta.pages.length) return;
    cur = i;
    const p = docMeta.pages[i];
    $.all('#pagelist .pagerow').forEach(r => r.cls(+r.attr('data-i') === i ? '+is-sel' : '-is-sel'));
    $('#page-dims').text(`Page ${i + 1}/${docMeta.pages.length}  \u00B7  ${R(p.w)} \u00D7 ${R(p.h)} pt${p.rot ? '  \u00B7  \u21BB' + p.rot + '\u00B0' : ''}`);
    applyPageToStage();
    if (tab === 'struct') { ensureStructure().then(() => structureJumpTo(i)); }
}

// ── open a document ─────────────────────────────────────────────────────────────
async function openFile(file) {
    if (!file) return;
    if (!/\.pdf$/i.test(file.name) && file.type !== 'application/pdf') {
        toast('PDF Inspector opens PDF files (PDFBox can\u2019t read .ocd \u2014 that\u2019s Jexter Lab).', 'warning'); return;
    }
    status(`Loading ${file.name}\u2026`, true);
    let meta;
    try { meta = await J(`/api/open?name=${encodeURIComponent(file.name)}`, { method: 'POST', body: file }); }
    catch { status('Load failed'); toast('Load failed', 'danger'); return; }
    if (meta.error) { status('Load failed'); toast(meta.error, 'danger'); return; }
    docMeta = meta; cur = 0; docVersion++;

    structBuilt = false; cache.ops = {}; cache.text = {}; $.opt('#tree')?.empty(); detail?.reset(); fontV = -1; fontDoc = null; resV = -1; resData = null;
    fillMeta(meta); buildPageList(); buildTables(meta);
    tabsCtrl?.select('page'); selectPage(0);
    status(`Loaded ${meta.name}`);
}

// ── doc drawer content ──────────────────────────────────────────────────────────
function fillMeta(meta) {
    const dm = $('#doc-meta').empty();
    dm.add(makeRow('Name', meta.name));
    dm.add(makeRow('PDF version', meta.version));
    dm.add(makeRow('Pages', String(meta.pages.length)));
    dm.add(makeRow('Fonts', String(meta.fonts)));
    dm.add(makeRow('Encrypted', meta.encrypted ? 'yes' : 'no'));
    const im = $('#doc-info-meta').empty(), info = meta.info || {};
    const rows = [['Title', info.title], ['Author', info.author], ['Creator', info.creator], ['Producer', info.producer]].filter(([, v]) => v && v.length);
    if (rows.length) rows.forEach(([k, v]) => im.add(makeRow(k, v)));
    else im.add($.create('div', { class: 'jx-empty', text: 'No metadata.' }));
}
function buildTables(meta) {
    const pages = meta.pages.map((p, i) => ({ i: i + 1, w: R(p.w), h: R(p.h), rot: p.rot, fonts: p.fonts, xobjects: p.xobjects }));
    $('#pages-table').empty().add(sortableTable([
        { key: 'i', label: 'Page', num: 1 }, { key: 'w', label: 'Width', num: 1 }, { key: 'h', label: 'Height', num: 1 },
        { key: 'rot', label: 'Rotation', num: 1, fmt: v => (v ? v : 0) + '\u00B0' },
        { key: 'fonts', label: 'Fonts', num: 1 }, { key: 'xobjects', label: 'XObjects', num: 1 },
    ], pages, { sort: { key: 'i', dir: 'asc' } }));
    const fonts = meta.fontList || [];
    $('#fonts-table').empty().add(fonts.length ? sortableTable([
        { key: 'name', label: 'Font' }, { key: 'subtype', label: 'Subtype' }, { key: 'type', label: 'Type' },
        { key: 'embedded', label: 'Embedded', fmt: v => v ? '\u2713' : '\u2014' },
    ], fonts, { index: true, sort: { key: 'name', dir: 'asc' } }) : $.create('div', { class: 'jx-empty', text: 'No fonts.' }));
    ensureStructure();
}

// ── COS structure (built once per document) ───────────────────────────────────
async function ensureStructure() {
    if (!docMeta || structBuilt) return;
    try {
        const nodes = await J(`/api/tree?root=doc&v=${docVersion}`);
        if (!Array.isArray(nodes)) { $('#tree').empty().add($.create('li', { class: 'jx-empty', text: (nodes && nodes.error) || 'No structure.' })); return; }
        buildTree($('#tree'), nodes); structBuilt = true;
    } catch { $('#tree').empty(); }
}
function onNodeSelect(n) { detail.mount(n); if (n && n.page != null && n.page !== cur) selectPage(n.page); }
function structureJumpTo(i) {
    const tree = $.opt('#tree'); if (!tree) return;
    const li = tree.querySelector(`li[data-page="${i}"]`); if (!li) return;
    $.all('#tree .trow').forEach(r => r.cls('-sel'));
    const row = li.querySelector(':scope > .trow'); row?.classList.add('sel');
    let anc = li.parentElement?.closest('li');
    while (anc) {
        if (anc.classList.contains('collapsed')) { anc.classList.remove('collapsed'); anc.querySelector(':scope > .trow > .tcaret')?.setAttribute('data-lucide', 'chevron-down'); }
        anc = anc.parentElement?.closest('li');
    }
    icons(); row?.scrollIntoView({ block: 'center' }); detail.mount(li._node || null);
}
function buildTree(rootEl, nodes) {
    rootEl.empty(); const rid = rootEl.id;
    const rec = (list, parent) => {
        for (const n of list) {
            const li = $.create('li'); li._node = n;
            if (n.page != null) li.attr('data-page', String(n.page));
            const kids = n.children && n.children.length;
            const row = $.create('div', { class: 'trow' });
            const caret = $.create('i', { class: `tcaret${kids ? '' : ' leaf'}`, 'data-lucide': kids ? 'chevron-right' : 'dot' });
            row.add(caret);
            row.add($.create('span', { class: `tcos tcos-${n.type || 'x'}`, text: n.label || n.type || 'node' }));
            row.on('click', () => {
                $.all(`#${rid} .trow`).forEach(r => r.cls('-sel')); row.cls('+sel');
                onNodeSelect(n);
                if (kids) { const c = li.cls('?collapsed'); li.cls(c ? '-collapsed' : '+collapsed'); caret.attr('data-lucide', c ? 'chevron-down' : 'chevron-right'); icons(); }
            });
            li.add(row);
            if (kids) { li.cls('+collapsed'); const ul = $.create('ul'); rec(n.children, ul); li.add(ul); }
            parent.add(li);
        }
    };
    rec(Array.isArray(nodes) ? nodes : [], rootEl); icons();
}

// ── detail panel: Info · Decoded · Image · Operators · Text ──────────────────
function makeDetail(host) {
    host.empty();
    const head = $.create('div', { class: 'pi-detail-head' });
    const title = $.create('span', { class: 'pi-detail-title', text: '\u2014' });
    const views = $.create('div', { class: 'render-src' });
    head.add(title).add(views);
    const body = $.create('div', { class: 'pi-detail-body' });
    const pre = $.create('pre', { class: 'pi-pre' });
    const rend = $.create('div', { class: 'pi-render' });
    const img = $.create('img', { alt: 'image XObject' }); rend.add(img);
    body.add(pre); host.add(head).add(body);

    let node = null;
    const DEFS = [
        { id: 'info', label: 'Info', icon: 'info' }, { id: 'decoded', label: 'Decoded', icon: 'file-code-2' },
        { id: 'image', label: 'Image', icon: 'image' }, { id: 'ops', label: 'Operators', icon: 'braces' },
        { id: 'text', label: 'Text', icon: 'type' },
    ];
    const btns = {};
    DEFS.forEach(d => {
        const b = $.create('button', { class: 'srcbtn', 'data-view': d.id, type: 'button', title: d.label });
        b.html(`<i data-lucide="${d.icon}"></i><span>${d.label}</span>`);
        b.on('click', () => { if (!b.hasAttribute('disabled')) setView(d.id); });
        views.add(b); btns[d.id] = b;
    });
    icons();
    const showPre = () => { rend.remove(); if (!pre.parentNode) body.add(pre); };
    const showImg = () => { pre.remove(); if (!rend.parentNode) body.add(rend); };
    const enable = (id, on) => { const b = btns[id]; if (b) b.attr('disabled', on ? null : ''); };
    const active = id => Object.keys(btns).forEach(k => btns[k].cls(k === id ? '+is-on' : '-is-on'));

    async function setView(v) {
        active(v);
        if (v === 'info') { showPre(); pre.text(node ? (node.detail || node.label || node.type || '') : '\u2014  select a node'); return; }
        if (v === 'decoded') {
            showPre(); pre.text('\u2026');
            try { const r = await J(`/api/stream?obj=${node.obj}`); pre.text(r.error ? r.error : `object ${r.obj} \u00B7 ${r.encoding}${r.truncated ? ' \u00B7 truncated' : ''}\n` + '\u2500'.repeat(46) + '\n' + (r.text || '')); }
            catch { pre.text('(stream decode failed)'); } return;
        }
        if (v === 'image') { showImg(); img.attr('src', `/api/render?obj=${node.obj}&t=${Date.now()}`); return; }
        if (v === 'ops')  { showPre(); pre.text('\u2026'); pre.text(await loadOps()); return; }
        if (v === 'text') { showPre(); pre.text('\u2026'); pre.text(await loadText()); return; }
    }
    function mount(n) {
        node = n;
        title.text((n ? (n.label || n.type || 'node') : 'nothing selected') + (n && n.page != null ? `  \u00B7  page ${n.page + 1}` : ''));
        enable('info', !!n); enable('decoded', !!n && n.type === 'stream' && n.obj != null);
        enable('image', !!n && !!n.img && n.obj != null); enable('ops', true); enable('text', true);
        setView(n ? 'info' : 'ops');
    }
    return { mount, reset() { mount(null); } };
}
async function loadOps() {
    const i = cur; if (cache.ops[i] != null) return cache.ops[i];
    try {
        const r = await J(`/api/content?page=${i}`), ops = r.ops || [];
        if (!ops.length) return (cache.ops[i] = '(no content operators on this page)');
        const w = ops.reduce((m, o) => Math.max(m, o.op.length), 2);
        return (cache.ops[i] = ops.map(o => `${o.op.padEnd(w)}  ${o.args || ''}`.trimEnd()).join('\n') + (r.truncated ? '\n\u2026 (truncated)' : ''));
    } catch { return '(content parse failed)'; }
}
async function loadText() {
    const i = cur; if (cache.text[i] != null) return cache.text[i];
    try { const r = await J(`/api/text?page=${i}`), t = (r.text || '').replace(/\s+$/g, ''); return (cache.text[i] = t.length ? t : '(no extractable text on this page)'); }
    catch { return '(text extraction failed)'; }
}

// ── save page raster (native Save As where available) ─────────────────────────
async function savePageImage() {
    if (!docMeta) { toast('Open a document first.', 'warning'); return; }
    const base = (docMeta.name || 'document').replace(/\.[^.]+$/, '');
    const url = `/api/page?i=${cur}&dpi=300${renderSrc ? '&src=' + encodeURIComponent(renderSrc) : ''}`;
    const filename = `${base}-p${cur + 1}.png`;
    if (window.showSaveFilePicker) {
        let handle;
        try { handle = await window.showSaveFilePicker({ suggestedName: filename, types: [{ description: 'PNG image', accept: { 'image/png': ['.png'] } }] }); }
        catch (e) { if (e && e.name === 'AbortError') return; }
        if (handle) { status(`Saving ${filename}\u2026`, true); try { const res = await fetch(url); const w = await handle.createWritable(); await w.write(await res.blob()); await w.close(); status(`Saved ${filename}`); } catch { status('Save failed'); toast('Save failed', 'danger'); } return; }
    }
    status(`Saving ${filename}\u2026`, true);
    try { const res = await fetch(url); const u = URL.createObjectURL(await res.blob()); const a = document.createElement('a'); a.href = u; a.download = filename; document.body.appendChild(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(u), 1000); status(`Saved ${filename}`); }
    catch { status('Save failed'); toast('Save failed', 'danger'); }
}

// ── render-source switch (footer) ─────────────────────────────────────────────
function buildRenderSrc() {
    const host = $.opt('#render-src'); if (!host) return;
    let activeId = 'pdfbox';
    RENDER_SOURCES.forEach(s => { const b = $.create('button', { class: 'srcbtn' + (s.id === activeId ? ' is-on' : ''), 'data-src': s.id, title: s.tip, type: 'button' }); b.html(`<i data-lucide="${s.icon}"></i><span>${s.label}</span>`); host.add(b); });
    host.delegate('.srcbtn', 'click', function () {
        const id = this.attr('data-src'); if (id === activeId) return; activeId = id;
        $.all('#render-src .srcbtn').forEach(x => x.cls(x.attr('data-src') === id ? '+is-on' : '-is-on'));
        renderSrc = id === 'pdfbox' ? null : id; stageApi()?.setSource(renderSrc, isVectorSrc(renderSrc));
    });
}

// ── compare (footer): overlay PDFBox vs OCD rasters of the current page ─────────
const cmp = { on: false, mode: 'diff', value: 0.5 };
function setCompare(on) {
    cmp.on = !!on;
    $('#cmp-toggle').cls(on ? '+is-on' : '-is-on');
    $('#cmp-ctl').cls(on ? '+open' : '-open');
    $.opt('#render-src')?.cls(on ? '+locked' : '-locked');   // source is fixed while comparing (class toggle = reliable)
    updateCmpSlider();
    stageApi()?.setCompare(on);
}
function setCmpMode(mode) {
    cmp.mode = mode;
    $.all('#cmp-ctl .cmp-mode').forEach(x => x.cls(x.attr('data-mode') === mode ? '+is-on' : '-is-on'));
    updateCmpSlider();
    stageApi()?.setCompareMode(mode);
}
function updateCmpSlider() { const s = $.opt('#cmp-slider'); if (s) s.style.display = (cmp.on && cmp.mode !== 'diff') ? '' : 'none'; }

// ── splitter (drag the left pages width) ──────────────────────────────────────
function wireSplitter() {
    const split = $.opt('#split'), appEl = $.opt('#app'); if (!split || !appEl) return;
    let dragging = false;
    const onMove = e => { if (!dragging) return; const r = appEl.getBoundingClientRect(); let w = e.clientX - r.left; w = Math.max(150, Math.min(Math.min(420, window.innerWidth * 0.5), w)); appEl.style.setProperty('--jx-left-w', w + 'px'); };
    split.on('pointerdown', e => { dragging = true; e.preventDefault(); document.body.classList.add('col-resizing'); split.cls('+dragging'); });
    window.on('pointermove', onMove);
    window.on('pointerup', () => { if (dragging) { dragging = false; document.body.classList.remove('col-resizing'); split.cls('-dragging'); stageApi()?.relayout(); } });
}

// ── whole-window drop ───────────────────────────────────────────────────────────
function wireDrop() {
    const zone = $('#dropzone'); let depth = 0;
    const hasFiles = e => e.dataTransfer && Array.from(e.dataTransfer.types || []).includes('Files');
    window.on('dragenter', e => { if (!hasFiles(e)) return; e.preventDefault(); if (depth++ === 0) zone.cls('+show'); });
    window.on('dragover', e => { if (hasFiles(e)) e.preventDefault(); });
    window.on('dragleave', e => { if (!hasFiles(e)) return; if (--depth <= 0) { depth = 0; zone.cls('-show'); } });
    window.on('drop', e => { if (!hasFiles(e)) return; e.preventDefault(); depth = 0; zone.cls('-show'); const f = e.dataTransfer.files && e.dataTransfer.files[0]; if (f) openFile(f); });
}

// ── default document: open the bundled welcome.pdf ─────────────────────────────
async function openWelcome() {
    if (docMeta) return;
    try { const resp = await fetch('welcome.pdf', { cache: 'no-store' }); if (!resp.ok) return; const blob = await resp.blob(); if (!blob.size || docMeta) return; await openFile(new File([blob], 'welcome.pdf', { type: 'application/pdf' })); }
    catch { /* offline / missing → drop-zone */ }
}

// ── font inspector (the OCD model's fonts + glyph specimens) ───────────────────
// We render from the OCD model (PdfImporter output), which carries every font with its glyph
// outlines (em-space paths, Y-up) and, when a TTF shipped, an @font-face for native selectable text.
async function ensureFonts() {
    const wrap = $('#fonts-wrap');
    if (!docMeta) { wrap.html('<div class="pi-empty">Open a document to inspect its fonts.</div>'); return; }
    if (fontV !== docVersion || !fontDoc) {
        wrap.html('<div class="pi-empty">Loading fonts\u2026</div>');
        try {
            const res = await fetch(`/api/ocd?src=ocd&v=${docVersion}`);
            if (!res.ok) throw new Error('HTTP ' + res.status);
            fontDoc = await loadOcd(await res.blob());        // also registers @font-face for the native specimens
            fontV = docVersion;
        } catch (e) { wrap.html(`<div class="pi-empty">Couldn\u2019t load fonts${e && e.message ? ' \u2014 ' + e.message : ''}.</div>`); return; }
    }
    drawFonts(fontDoc);
}

const GLYPH_CAP = 500;
function drawFonts(doc) {
    const wrap = $('#fonts-wrap').empty();
    const fonts = (doc.fonts || []).slice().sort((a, b) => (a.name || a.id || '').localeCompare(b.name || b.id || ''));
    if (!fonts.length) { wrap.add($.create('div', { class: 'pi-empty', text: 'No fonts in this document.' })); return; }
    const byName = {}; (docMeta.fontList || []).forEach(f => { if (f.name) byName[f.name] = f; });   // PDFBox subtype, when matchable

    for (const f of fonts) {
        const glyphs = f.glyphs || [], drawable = glyphs.filter(g => g.d), meta = byName[f.name] || {};
        const asc = (f.ascent ?? 0.8), desc = (f.descent ?? -0.2);
        const vb = `-0.05 ${(-asc - 0.05).toFixed(3)} 1.1 ${(asc - desc + 0.1).toFixed(3)}`;   // shared em box (Y flipped in <g>)

        const card = $.create('div', { class: 'font-card' });
        const head = $.create('div', { class: 'font-head' });
        head.add($.create('div', { class: 'font-title', text: f.name || f.id || 'font' }));
        const sub = [f.family && f.family !== f.name ? f.family : null, f.weight && f.weight !== 'normal' ? f.weight : null,
            f.style && f.style !== 'normal' ? f.style : null, meta.subtype || null].filter(Boolean);
        if (sub.length) head.add($.create('span', { class: 'font-sub', text: sub.join(' \u00B7 ') }));
        const badges = $.create('div', { class: 'font-badges' });
        badges.add($.create('span', { class: 'fb', text: `${glyphs.length} glyph${glyphs.length === 1 ? '' : 's'}` }));
        badges.add($.create('span', { class: 'fb ' + (f.embedded ? 'on' : 'off'), text: f.embedded ? 'embedded' : 'not embedded' }));
        badges.add($.create('span', { class: 'fb ' + (f.nativeReady ? 'on' : 'off'), text: f.nativeReady ? 'TTF \u00B7 selectable' : 'outlines only' }));
        head.add(badges);
        card.add(head);

        if (f.nativeReady) {                                  // native specimen: the font's OWN chars, in its @font-face
            const chars = [...new Set(glyphs.map(g => g.u).filter(u => u && u.trim()))].slice(0, 80).join('');
            if (chars) { const sp = $.create('div', { class: 'font-specimen' }); sp.html(`<span style="font-family:'${esc(f.css)}',sans-serif">${esc(chars)}</span>`); card.add(sp); }
        }

        const grid = $.create('div', { class: 'glyph-grid' });   // glyph specimen grid from the outline paths
        let html = '';
        for (const g of drawable.slice(0, GLYPH_CAP)) {
            const lbl = g.u && g.u.trim() ? esc(g.u) : ('\u00B7' + g.gid);
            const cp = g.u && g.u.codePointAt(0) ? 'U+' + g.u.codePointAt(0).toString(16).toUpperCase().padStart(4, '0') : '';
            const tip = `gid ${g.gid}${cp ? ' \u00B7 ' + cp : ''}${g.adv != null ? ' \u00B7 adv ' + (+g.adv).toFixed(3) : ''}`;
            html += `<div class="glyph-cell" title="${esc(tip)}"><svg class="glyph-svg" viewBox="${vb}" preserveAspectRatio="xMidYMid meet"><g transform="scale(1,-1)"><path d="${g.d}"/></g></svg><span class="glyph-lbl">${lbl}</span></div>`;
        }
        grid.html(html); card.add(grid);
        if (drawable.length > GLYPH_CAP) card.add($.create('div', { class: 'glyph-more', text: `\u2026 and ${drawable.length - GLYPH_CAP} more glyphs (showing first ${GLYPH_CAP}).` }));
        wrap.add(card);
    }
}

// ── resources (image XObjects) ─────────────────────────────────────────────────
// /api/images lists every image stream (obj, size, colorspace, filter, masks, page usage); each
// thumbnail is /api/render?obj=N (the same rasterizer the detail panel's Image view uses).
async function ensureResources() {
    const wrap = $('#res-wrap');
    if (!docMeta) { wrap.html('<div class="pi-empty">Open a document to list its images.</div>'); return; }
    if (resV !== docVersion || !resData) {
        wrap.html('<div class="pi-empty">Scanning resources\u2026</div>');
        try { const d = await J(`/api/images?v=${docVersion}`); if (d && d.error) throw new Error(d.error); resData = Array.isArray(d) ? d : []; resV = docVersion; }
        catch (e) { wrap.html(`<div class="pi-empty">Couldn\u2019t list images${e && e.message ? ' \u2014 ' + e.message : ''}.</div>`); return; }
    }
    drawResources(resData);
}
function drawResources(list) {
    const wrap = $('#res-wrap').empty();
    if (!list.length) { wrap.add($.create('div', { class: 'pi-empty', text: 'No image XObjects in this document.' })); return; }
    wrap.add($.create('div', { class: 'res-head', text: `${list.length} image${list.length === 1 ? '' : 's'}` }));
    const grid = $.create('div', { class: 'res-grid' });
    for (const im of list) {
        const url = `/api/render?obj=${im.obj}&v=${docVersion}`;
        const card = $.create('div', { class: 'res-card' });
        const fig = $.create('div', { class: 'res-thumb' });
        const img = $.create('img', { alt: `object ${im.obj}` }); img.attr('loading', 'lazy'); img.attr('src', url);
        img.on('click', () => openLightbox(url, im));
        fig.add(img);
        const dl = $.create('button', { class: 'res-dl qry-btn', title: 'Download PNG', type: 'button' }); dl.html('<i data-lucide="download"></i>');
        dl.on('click', (e) => { e.stopPropagation(); downloadImage(im); });
        fig.add(dl);
        card.add(fig);

        const meta = $.create('div', { class: 'res-meta' });
        meta.add($.create('div', { class: 'res-dim', text: `${im.w}\u00D7${im.h}` }));
        const tags = $.create('div', { class: 'res-tags' });
        tags.add($.create('span', { class: 'rt', text: `#${im.obj}` }));
        if (im.cs) tags.add($.create('span', { class: 'rt', text: im.cs }));
        if (im.filter) tags.add($.create('span', { class: 'rt', text: im.filter }));
        if (im.bpc) tags.add($.create('span', { class: 'rt', text: `${im.bpc} bpc` }));
        if (im.smask) tags.add($.create('span', { class: 'rt on', text: 'alpha' }));
        if (im.mask) tags.add($.create('span', { class: 'rt', text: 'mask' }));
        meta.add(tags);
        if (im.pages && im.pages.length) {
            const pages = im.pages.slice(0, 6).map(n => n + 1).join(', ') + (im.pages.length > 6 ? ` +${im.pages.length - 6}` : '');
            const pg = $.create('div', { class: 'res-pages', text: `p. ${pages}` });
            pg.on('click', () => { tabsCtrl?.select('page'); selectPage(im.pages[0]); });
            meta.add(pg);
        }
        card.add(meta);
        grid.add(card);
    }
    wrap.add(grid);
    icons();
}
// ── lightbox: a zoomable / pannable viewer for an image resource ───────────────
const lb = { scale: 1, natW: 1, natH: 1, drag: null };
function lbApply() {
    const img = $('#lightbox-img');
    img.style.width = (lb.natW * lb.scale) + 'px';
    img.style.height = (lb.natH * lb.scale) + 'px';
    $('#lb-pct').text(Math.round(lb.scale * 100) + '%');
}
function lbFit() {
    const s = $('#lb-stage'); const w = (s.clientWidth || 800) - 32, h = (s.clientHeight || 600) - 32;
    lb.scale = Math.max(0.02, Math.min(w / lb.natW, h / lb.natH));
    lbApply();
    s.scrollLeft = (s.scrollWidth - s.clientWidth) / 2; s.scrollTop = (s.scrollHeight - s.clientHeight) / 2;
}
function lbZoom(factor, cx, cy) {
    const s = $('#lb-stage'), old = lb.scale;
    const ax = cx == null ? s.clientWidth / 2 : cx, ay = cy == null ? s.clientHeight / 2 : cy;
    const px = s.scrollLeft + ax, py = s.scrollTop + ay;
    lb.scale = Math.max(0.02, Math.min(40, old * factor));
    lbApply();
    const k = lb.scale / old; s.scrollLeft = px * k - ax; s.scrollTop = py * k - ay;
}
function openLightbox(url, im) {
    $('#lb-info').text(`#${im.obj} \u00B7 ${im.w}\u00D7${im.h}${im.cs ? ' \u00B7 ' + im.cs : ''}`);
    const img = document.getElementById('lightbox-img');
    img.setAttribute('alt', `object ${im.obj}`);
    img.addEventListener('load', () => { lb.natW = img.naturalWidth || im.w || 1; lb.natH = img.naturalHeight || im.h || 1; lbFit(); }, { once: true });
    img.setAttribute('src', url);
    $('#lightbox').cls('+open');
}
function closeLightbox() { $('#lightbox').cls('-open'); $('#lightbox-img').removeAttribute('src'); }
function lbOpen() { return document.getElementById('lightbox').classList.contains('open'); }
async function downloadImage(im) {
    const base = (docMeta && docMeta.name || 'document').replace(/\.[^.]+$/, '');
    const url = `/api/render?obj=${im.obj}&v=${docVersion}`, filename = `${base}-img${im.obj}.png`;
    if (window.showSaveFilePicker) {
        let handle;
        try { handle = await window.showSaveFilePicker({ suggestedName: filename, types: [{ description: 'PNG image', accept: { 'image/png': ['.png'] } }] }); }
        catch (e) { if (e && e.name === 'AbortError') return; }
        if (handle) { try { const res = await fetch(url); const w = await handle.createWritable(); await w.write(await res.blob()); await w.close(); } catch { toast('Download failed', 'danger'); } return; }
    }
    try { const res = await fetch(url); const u = URL.createObjectURL(await res.blob()); const a = document.createElement('a'); a.href = u; a.download = filename; document.body.appendChild(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(u), 1000); }
    catch { toast('Download failed', 'danger'); }
}

// ── boot ──────────────────────────────────────────────────────────────────────
function syncThemeIcon() { $('#btn-theme').html(`<i data-lucide="${theme.isDark() ? 'sun' : 'moon'}"></i>`); icons(); }

// ── render-stage callbacks (the iframe calls these on the parent) ──────────────
window.shell = {
    onStageReady() { stageApi()?.setTheme(theme.isDark()); if (docMeta) applyPageToStage(); },
    onZoom(pct) { $('#zoom-pct').text(pct + '%'); },
    onPageReady() { /* the page is shown in the stage */ },
    veil(on) { $('#dropzone').cls(on ? '+show' : '-show'); },
    openDropped(file) { openFile(file); },
};

function init() {
    try { new EventSource('/api/alive'); } catch { /* keep server alive */ }
    document.getElementById('brand-logo').src = '/shared/jexter-mark.svg';   // THE jexter mark (served at /shared)

    $('#file').on('change', e => { const f = e.target.files[0]; if (f) openFile(f); e.target.value = ''; });
    wireDrop();
    $('#pagelist').delegate('.pagerow', 'click', function () { selectPage(+this.attr('data-i')); });
    $('#toggle-left').on('click', () => { const a = $('#app'); a.cls(a.cls('?left-collapsed') ? '-left-collapsed' : '+left-collapsed'); stageApi()?.relayout(); });

    detail = makeDetail($('#detail'));
    buildRenderSrc(); wireSplitter();

    $('#cmp-toggle').on('click', () => setCompare(!cmp.on));
    $.all('#cmp-ctl .cmp-mode').forEach(b => b.on('click', () => setCmpMode(b.attr('data-mode'))));
    $('#cmp-slider').on('input', function () { cmp.value = (+this.value || 0) / 100; stageApi()?.setCompareValue(cmp.value); });
    updateCmpSlider();

    $('#zoom-fit').on('click', () => stageApi()?.fit());
    $('#zoom-in').on('click', () => stageApi()?.zoomBy(1.25));
    $('#zoom-out').on('click', () => stageApi()?.zoomBy(1 / 1.25));
    $('#zoom-pct').text('100%');

    tabsCtrl = makeTabs({ tabSel: '.jx-tab', panelSel: '.pi-panel', attr: 'data-tab', activeClass: 'active',
        onChange: name => { tab = name;
            if (name === 'struct') ensureStructure().then(() => structureJumpTo(cur));
            else if (name === 'fonts') ensureFonts();
            else if (name === 'resources') ensureResources();
            else stageApi()?.relayout(); } });

    const drawer = $.opt('#doc-drawer');
    $.opt('#main-menu')?.on('sl-select', e => {
        switch (e.detail.item.value) {
            case 'open': $('#file').click(); break;
            case 'save-image': savePageImage(); break;
            case 'doc': if (!docMeta) toast('Open a document first.', 'warning'); else drawer?.show?.(); break;
        }
    });
    $('#btn-theme').on('click', () => { theme.toggle(); syncThemeIcon(); stageApi()?.setTheme(theme.isDark()); });

    // lightbox controls — close paths first (these must always attach), then the stage pan/zoom.
    $('#lb-in').on('click', () => lbZoom(1.25));
    $('#lb-out').on('click', () => lbZoom(1 / 1.25));
    $('#lb-fit').on('click', lbFit);
    $('#lb-close').on('click', closeLightbox);
    $('#lightbox').on('click', e => { if (e.target.id === 'lb-stage' && !(lb.drag && lb.drag.moved)) closeLightbox(); });
    window.on('keydown', e => { if (e.key === 'Escape' && lbOpen()) closeLightbox(); });

    const lbst = document.getElementById('lb-stage');
    if (lbst) {
        lbst.addEventListener('wheel', e => { e.preventDefault(); const r = lbst.getBoundingClientRect(); lbZoom(e.deltaY < 0 ? 1.15 : 1 / 1.15, e.clientX - r.left, e.clientY - r.top); }, { passive: false });
        lbst.addEventListener('pointerdown', e => { lb.drag = { x: e.clientX, y: e.clientY, sl: lbst.scrollLeft, st: lbst.scrollTop, moved: false }; lbst.setPointerCapture?.(e.pointerId); });
        lbst.addEventListener('pointermove', e => { if (!lb.drag) return; lb.drag.moved = true; lbst.scrollLeft = lb.drag.sl - (e.clientX - lb.drag.x); lbst.scrollTop = lb.drag.st - (e.clientY - lb.drag.y); });
        lbst.addEventListener('pointerup', () => { lb.drag = null; });
    }

    icons(); status('Ready'); openWelcome();
}
boot({ title: 'PDF Inspector', ready: () => { init(); syncThemeIcon(); } });
