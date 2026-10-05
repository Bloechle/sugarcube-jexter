// ocr.js — "OCR" (dev): what the recognizer read, made visible.
//
// An OCR layer is INVISIBLE by construction — words in render mode 3 laid over the scan, searchable and
// selectable, painting nothing. That is right for a reader and useless for whoever has to judge the
// recognition: nothing on screen says what was read, where, or how it was grouped. This tool draws it:
// the recognized text in VECTOR, set in each word's own box (over the image to judge the alignment, or
// alone to read what the engine read), and the boxes of the three levels the analysis built from the
// word boxes — words, lines, paragraphs. The pane lists the page's text in reading order, paragraph by
// paragraph and line by line, and says what a clicked word or line IS.
//
// It reads the page as it is stored and changes nothing: everything it draws is `data-ui` chrome, gone
// on leave and stripped by persist. A word's box comes from `runBox` (ocd.js) — its matrix and its font —
// because `getBBox()` measures ink, and an OCR word has none.
//
// Rules to recognize field values (a plate, a chassis number) will build on the same reading: the
// model below — paragraphs → lines → words, each with its box — is what they will match against.
import { SVG_NS, inReadingOrder, readingRuns, runBox } from '/shared/js/ocd.js';
import { book } from '/shared/js/book.js';

const P = window.prism;
const TOOL = 'ocr';
const COL = { word: '#d97706', line: '#059669', para: '#2563eb', doubt: '#dc2626' };
// Below this, a word is DOUBTFUL: outlined in red, counted in the pane. The engine's own scale (FORMAT §B4
// data-conf), so the threshold means the same thing whichever engine read the page.
const DOUBT = 0.8;
const st = { text: true, image: true, words: true, lines: false, paras: false, sel: null };   // sel: { idx, kind, key }

const isOn = () => P.tool?.() === TOOL;
// An OCR word is a run in render mode 3: text with no ink, over the picture it was read from.
const isOcrRun = (r) => r.getAttribute('data-render') === '3';
const wordText = (r) => (r.getAttribute('data-text') || '').replace(/\s+$/, '');
// The recognizer's confidence (FORMAT §B4), or null when the page did not state one.
const wordConf = (r) => r.hasAttribute('data-conf') ? +r.getAttribute('data-conf') : null;
const doubtful = (w) => w.conf != null && w.conf < DOUBT;

/* -- The page as the recognizer left it: paragraphs → lines → words, each with its box ----------- */

// A matrix from an element's own user space to the page root's, so every box lands in ONE space
// whatever wraps the run (a clip carrier, a layer). Identity when nothing does — the common case.
// Taken out of the FRAME's realm as a plain DOMMatrix of this one: the frame hands back an SVGMatrix,
// whose multiply() refuses a DOMMatrix built here ("parameter 1 is not of type 'SVGMatrix'").
function toRoot(svg, el) {
    try { return DOMMatrix.fromMatrix(svg.getScreenCTM().inverse().multiply(el.getScreenCTM())); }
    catch { return new DOMMatrix(); }
}
function mapBox(m, b) {
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    for (const [x, y] of [[b.x, b.y], [b.x + b.width, b.y], [b.x + b.width, b.y + b.height], [b.x, b.y + b.height]]) {
        const p = new DOMPoint(x, y).matrixTransform(m);
        x0 = Math.min(x0, p.x); x1 = Math.max(x1, p.x); y0 = Math.min(y0, p.y); y1 = Math.max(y1, p.y);
    }
    return { x: x0, y: y0, width: x1 - x0, height: y1 - y0 };
}
function union(boxes) {
    const bs = boxes.filter(Boolean);
    if (!bs.length) return null;
    const x0 = Math.min(...bs.map(b => b.x)), y0 = Math.min(...bs.map(b => b.y));
    const x1 = Math.max(...bs.map(b => b.x + b.width)), y1 = Math.max(...bs.map(b => b.y + b.height));
    return { x: x0, y: y0, width: x1 - x0, height: y1 - y0 };
}

/** The page's OCR reading. A paragraph's lines are its `data-ocd="line"` children; a run the analysis
 *  never segmented (structure off) stands as a paragraph of one line of its own. Only OCR words count:
 *  a page with none is a page this tool has nothing to say about. */
function readPage(doc) {
    const svg = doc?.querySelector('svg[data-ocd="page"]') || doc?.querySelector('svg');
    if (!svg) return null;
    const fonts = book.fonts();
    const word = (r) => {
        const b = runBox(r, fonts);
        return b && { el: r, id: r.id, text: wordText(r), conf: wordConf(r), box: mapBox(toRoot(svg, r.parentNode), b) };
    };
    const paras = [];
    const seen = new Set();
    for (const p of inReadingOrder(svg.querySelectorAll('[data-ocd="paragraph"]'))) {
        const lineEls = [...p.querySelectorAll(':scope > [data-ocd="line"]')];
        const groups = lineEls.length ? lineEls.map(l => readingRuns(l)) : [readingRuns(p)];
        const lines = groups.map(rs => rs.filter(isOcrRun).map(r => { seen.add(r); return word(r); }).filter(Boolean))
                            .filter(ws => ws.length)
                            .map((ws, i) => ({ key: `${p.id}:${i}`, words: ws, box: union(ws.map(w => w.box)) }));
        if (lines.length) paras.push({ id: p.id, lines, box: union(lines.map(l => l.box)) });
    }
    for (const r of readingRuns(svg)) {                                  // runs outside any paragraph
        if (!isOcrRun(r) || seen.has(r)) continue;
        const w = word(r); if (!w) continue;
        paras.push({ id: r.id, lines: [{ key: `${r.id}:0`, words: [w], box: w.box }], box: w.box });
    }
    const lines = paras.reduce((n, p) => n + p.lines.length, 0);
    const words = paras.reduce((n, p) => n + p.lines.reduce((k, l) => k + l.words.length, 0), 0);
    const doubts = paras.reduce((n, p) => n + p.lines.reduce((k, l) => k + l.words.filter(doubtful).length, 0), 0);
    // Provenance, as the document states it (FORMAT §B4b): the recognition layer the words live in names the
    // engine that read them. Null for invisible text that came from elsewhere (a scanned PDF's own layer).
    const layerIds = new Set([...svg.querySelectorAll('[data-ocd="layer"][data-layer]')].map(g => g.getAttribute('data-layer')));
    const engines = recognitions().filter(l => layerIds.has(l.id)).map(l => l.ocr.engine + (l.ocr.prep ? ` on ${l.ocr.prep}` : ''));
    return { svg, paras, lines, words, doubts, engine: engines.join(', ') || null };
}

/** The document's recognition layers (FORMAT §B4b): registry entries that carry `ocr`. */
function recognitions() {
    try {
        const raw = book.get(book.member('ocd/meta.json'));
        const m = raw ? JSON.parse(new TextDecoder().decode(raw)) : {};
        return (m.layers || []).filter(l => l.ocr?.engine);
    } catch { return []; }
}

const pageText = (rd) => rd.paras.map(p => p.lines.map(l => l.words.map(w => w.text).join(' ')).join('\n')).join('\n\n');

/* -- On the page: the text in vector, the boxes, the selection — all data-ui chrome -------------- */

const CSS = '[data-px-ocr] .w { cursor: pointer; } [data-px-ocr] .w:hover { fill: rgba(217,119,6,.18); }'
          + ' [data-px-ocr] .l { cursor: pointer; }';
function setCss(doc, on) {
    let s = doc.getElementById('px-ocr-css');
    const css = CSS + (st.image ? '' : ' svg[data-ocd="page"] image { opacity: 0; }');
    if (on) {
        if (!s) {
            s = doc.createElementNS('http://www.w3.org/1999/xhtml', 'style');
            s.id = 'px-ocr-css'; s.setAttribute('data-ui', '1');
            (doc.head || doc.documentElement).appendChild(s);
        }
        s.textContent = css;
    } else s?.remove();
}

function clean(doc) {
    doc.querySelectorAll('[data-px-ocr]').forEach(n => n.remove());
    setCss(doc, false);
}

const el = (doc, tag, attrs) => {
    const n = doc.createElementNS(SVG_NS, tag);
    for (const [k, v] of Object.entries(attrs)) n.setAttribute(k, v);
    return n;
};
const rect = (doc, b, stroke, cls, extra = {}) => el(doc, 'rect', {
    x: b.x, y: b.y, width: b.width, height: b.height, fill: 'transparent',
    stroke, 'stroke-width': '.6', 'vector-effect': 'non-scaling-stroke', class: cls, ...extra,
});

function draw(doc, idx) {
    clean(doc);
    if (!isOn()) return;
    const rd = readPage(doc);
    if (!rd) return;
    setCss(doc, true);
    // At the ROOT, last: SVG paints in document order, and anything drawn before the page's own content
    // is covered by it — boxes invisible AND unclickable.
    const g = el(doc, 'g', { 'data-px-ocr': '1', 'data-ui': '1' });
    const sel = st.sel?.idx === idx ? st.sel : null;
    for (const p of rd.paras) {
        if (st.paras) g.appendChild(rect(doc, p.box, COL.para, 'p', { 'pointer-events': 'none', 'stroke-dasharray': '3 2' }));
        for (const l of p.lines) {
            const lineSel = sel?.kind === 'line' && sel.key === l.key;
            if (st.lines || lineSel) g.appendChild(rect(doc, l.box, COL.line, 'l', {
                'data-line': l.key, 'pointer-events': st.words ? 'none' : 'all',
                ...(lineSel ? { fill: 'rgba(5,150,105,.16)', 'stroke-width': '1.4' } : {}) }));
            for (const w of l.words) {
                const wordSel = sel?.kind === 'word' && sel.key === w.id;
                if (st.text) g.appendChild(vectorText(doc, rd.svg, w));
                if (st.words || wordSel || doubtful(w)) g.appendChild(rect(doc, w.box, doubtful(w) ? COL.doubt : COL.word, 'w', {
                    'data-word': w.id, 'pointer-events': st.words ? 'all' : 'none',
                    ...(wordSel ? { fill: 'rgba(217,119,6,.22)', 'stroke-width': '1.4' } : {}) }));
            }
        }
    }
    rd.svg.appendChild(g);
}

/** The recognized word, set in vector in its own box: the run's matrix (em space, y up) turned right
 *  way up, the text stretched to the run's measured extent. Over the image it shows whether the read and
 *  the picture line up; alone, it IS the page as the engine read it. */
function vectorText(doc, svg, w) {
    const m = /matrix\(([^)]+)\)/.exec(w.el.getAttribute('transform') || '');
    const [a, b, c, d, e, f] = m ? m[1].trim().split(/[\s,]+/).map(Number) : [1, 0, 0, -1, 0, 0];
    const M = toRoot(svg, w.el.parentNode).multiply(new DOMMatrix([a, b, c, d, e, f])).scale(1, -1);
    const k = Math.hypot(a, b) || 1;                       // the run's horizontal scale: page units per em
    const t = el(doc, 'text', {
        transform: `matrix(${M.a} ${M.b} ${M.c} ${M.d} ${M.e} ${M.f})`,
        x: 0, y: 0, 'font-size': 1, 'font-family': 'sans-serif', 'pointer-events': 'none',
        textLength: w.box.width / k, lengthAdjust: 'spacingAndGlyphs',
        fill: st.image ? 'rgba(220,38,38,.85)' : '#0f172a',
    });
    t.textContent = w.text;
    return t;
}

function drawAll() { book.eachFrame((d, i) => draw(d, i)); }

/* -- Selection: a word or a line, from the page or from the pane --------------------------------- */

function select(idx, kind, key, centre) {
    st.sel = { idx, kind, key };
    drawAll();
    drawer();
    if (!centre) return;
    const doc = book.frameDoc(idx);
    const n = doc?.querySelector(kind === 'word' ? `[data-word="${key}"]` : `[data-line="${key}"]`);
    if (n) P.centreOn?.(idx, n);
}

P.on('frame', (f, idx) => {
    let doc; try { doc = f.contentDocument; } catch { return; }
    if (!doc) return;
    if (!doc.__pxOcr) {
        doc.__pxOcr = true;
        doc.addEventListener('click', e => {
            if (!isOn()) return;
            const hit = e.target.closest?.('[data-word], [data-line]');
            if (!hit) return;
            e.preventDefault(); e.stopPropagation();
            const pi = +f.closest('.prism-page')?.getAttribute('data-index');
            if (hit.hasAttribute('data-word')) select(pi, 'word', hit.getAttribute('data-word'));
            else select(pi, 'line', hit.getAttribute('data-line'));
        }, true);
    }
    // The chassis replays `frame` on the way OUT of a tool too: dress only while this one is up.
    if (isOn()) draw(doc, idx); else clean(doc);
});
P.on('page', () => { if (isOn()) drawer(); });
P.on('book', () => { st.sel = null; });
P.on('close', () => { st.sel = null; });

/* -- The pane: what the page holds, in reading order; what the selection IS ---------------------- */

function drawer() {
    const info = document.getElementById('ocr-info');
    const det = document.getElementById('ocr-detail');
    const list = document.getElementById('ocr-list');
    if (!info || !list) return;
    const idx = Math.max(0, P.state.current ?? 0);
    const rd = book.isOpen() ? readPage(book.frameDoc(idx)) : null;
    list.innerHTML = '';
    if (!book.isOpen()) { info.textContent = 'Open a document first.'; if (det) det.textContent = ''; return; }
    if (!rd) { info.textContent = `Page ${idx + 1} is not displayed yet.`; if (det) det.textContent = ''; return; }
    if (!rd.words) {
        info.textContent = `Page ${idx + 1} carries no OCR text — no invisible word over an image.`;
        if (det) det.textContent = ''; return;
    }
    info.textContent = `Page ${idx + 1} · ${rd.words} words · ${rd.lines} lines · ${rd.paras.length} paragraphs`
        + (rd.doubts ? ` · ${rd.doubts} doubtful (< ${DOUBT})` : '')
        + ` · ${rd.engine ? 'read by ' + rd.engine : 'engine not stated'}`;
    const rows = [];
    rd.paras.forEach((p, pi) => {
        rows.push(`<li class="ocr-group">Paragraph ${pi + 1}</li>`);
        p.lines.forEach((l, li) => {
            const on = st.sel?.idx === idx && st.sel.kind === 'line' && st.sel.key === l.key;
            rows.push(`<li><a class="nav-link${on ? ' active' : ''}" data-line="${P.esc(l.key)}">`
                + `<b>L${li + 1}</b><span class="hit">${P.esc(l.words.map(w => w.text).join(' '))}</span>`
                + `<span class="px-sub">${l.words.some(doubtful) ? '\u26a0 ' : ''}${l.words.length}</span></a></li>`);
        });
    });
    list.innerHTML = rows.join('');
    list.querySelectorAll('[data-line]').forEach(a =>
        a.addEventListener('click', () => select(idx, 'line', a.getAttribute('data-line'), true)));
    if (det) det.innerHTML = detail(rd, idx);
}

function detail(rd, idx) {
    const s = st.sel?.idx === idx ? st.sel : null;
    if (!s) return 'Click a word or a line — on the page or in this list.';
    const u = (v) => P.toUnit ? P.toUnit(v) : v.toFixed(1);
    const box = (b) => `${u(b.x)}, ${u(b.y)} · ${u(b.width)} × ${u(b.height)}`;
    for (const [pi, p] of rd.paras.entries()) for (const [li, l] of p.lines.entries()) {
        if (s.kind === 'line' && l.key === s.key)
            return `<b>Line ${li + 1}</b> of paragraph ${pi + 1} · ${l.words.length} words<br>${P.esc(box(l.box))}`;
        for (const w of l.words) if (s.kind === 'word' && w.id === s.key)
            return `<b>${P.esc(w.text)}</b> · ${P.esc(w.id)} · line ${li + 1} of paragraph ${pi + 1}<br>${P.esc(box(w.box))}`
                 + ` · ${w.text.length} chars`
                 + (w.conf != null ? ` · confidence ${w.conf.toFixed(3)}${doubtful(w) ? ' \u26a0' : ''}` : '');
    }
    return 'Click a word or a line — on the page or in this list.';
}

/* -- The ribbon: what to show, and the one verb ---------------------------------------------------- */

async function copyText() {
    const idx = Math.max(0, P.state.current ?? 0);
    const rd = readPage(book.frameDoc(idx));
    if (!rd?.words) { P.toast('This page carries no OCR text.', 'warning'); return; }
    try { await navigator.clipboard.writeText(pageText(rd)); P.toast(`Copied ${rd.words} words of page ${idx + 1}.`, 'success'); }
    catch { P.toast('The clipboard refused the text.', 'danger'); }
}

function ribbon() {
    const has = book.isOpen();
    const flip = (k) => () => { st[k] = !st[k]; ribbon(); drawAll(); };
    P.ribbon(TOOL, [
        { group: 'Show', items: [
            { icon: 'type',  label: 'Text',  title: 'Draw the recognized text, in vector, in each word\u2019s box', active: st.text,  disabled: !has, on: flip('text') },
            { icon: 'image', label: 'Image', title: 'Show the scanned image under the text',                          active: st.image, disabled: !has, on: flip('image') },
        ]},
        { group: 'Boxes', items: [
            { icon: 'square-dashed', label: 'Words',      title: 'Outline every recognized word',               active: st.words, disabled: !has, on: flip('words') },
            { icon: 'align-left',    label: 'Lines',      title: 'Outline the lines the analysis rebuilt',      active: st.lines, disabled: !has, on: flip('lines') },
            { icon: 'pilcrow',       label: 'Paragraphs', title: 'Outline the paragraphs the analysis rebuilt', active: st.paras, disabled: !has, on: flip('paras') },
        ]},
        { group: 'Text', items: [
            { icon: 'copy', label: 'Copy', title: 'Copy the page\u2019s text, in reading order', disabled: !has, on: copyText },
        ]},
    ], has ? 'Click a word or a line to inspect it.' : '');
}

// experimental: a dev tab — hidden unless dev mode is on (Ctrl/Cmd+Shift+D).
P.registerTool({
    id: TOOL, label: 'OCR', icon: 'scan-text', drawer: 'Recognized text', title: 'OCR — what the recognizer read', experimental: true,
    onEnter() { ribbon(); drawAll(); drawer(); },
    onLeave() { book.eachFrame(d => clean(d)); },
});
P.on('book', () => { if (isOn()) { ribbon(); drawer(); } });
