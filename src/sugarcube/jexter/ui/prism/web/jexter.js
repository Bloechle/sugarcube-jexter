// jexter.js — the engine seam of Prism. The chassis (prism.js) reads any fixed-layout
// EPUB off the original tree; this module makes it the jexter workbench:
//
//   • PDF import        — %PDF drops route through the local engine (/api/convert?to=ocd)
//                         and the resulting OCD-EPUB opens in place.
//   • Text layer        — an OCD-EPUB's SVG pages carry NO <text> (glyph outlines only);
//                         search text, in-page highlights and read-aloud come from the
//                         pages themselves (grammar v2: each run carries data-u — exact
//                         text, spaces included), addressed by run id in the page DOM.
//   • Structure rail    — the structures.json trees (AUTO / PDF / AI / MANUAL pills),
//                         headings jump to their referenced content, boxed by id.
//   • Export as…        — the current container bytes (augmentations included) re-export
//                         through the engine to PDF / EPUB / HTML / Markdown / DocTags.
//
// Everything degrades gracefully: without jexter/ members Prism is a plain EPUB reader;
// without an engine (/api/health) the PDF and export affordances stay hidden.

import { zipSync } from 'https://cdn.jsdelivr.net/npm/fflate@0.8.2/+esm';
import * as backend from './backend.js';
import { parseFonts as parseFontsSvg, SVG_NS } from '/shared/js/ocd.js';
import { book } from '/shared/js/book.js';

const P = window.prism;                       // the chassis seam (state, hooks, API)
const dec = b => new TextDecoder().decode(b);
const enc = s => new TextEncoder().encode(s);
const JX = 'OEBPS/jexter/';

const jx = { on: false, runs: [], structures: [], defaultId: null };   // runs[i] = [{id, text}]
let engine = false;

/* -- Engine presence: reveal the jx-only affordances ------------------ */
// openPdf AWAITS this probe, so a PDF dropped in the first instants never races it.

/* -- Conversion options (Settings drawer) ------------------------------
   Defaults come from the engine's registry (/api/options); only the DIFFS from
   default are persisted and sent, so the convert URL stays minimal and the
   engine's own defaults remain the single source of truth. The AI switch is the
   same mechanism, gated by aiBound (no key on the engine -> stays disabled). */

const conv = { defs: {}, diffs: JSON.parse(localStorage.getItem('prism_conv') || '{}') };
const convOpts = () => Object.fromEntries(Object.entries(conv.diffs).map(([k, v]) => [k, String(v)]));

async function loadOptions() {
    let d; try { d = await backend.options(); } catch { return; }
    for (const o of d.options || []) conv.defs[o.key] = o.def;
    const ready = window.customElements?.whenDefined
        ? customElements.whenDefined('sl-switch') : Promise.resolve();
    ready.then(() => $.all('.cv-opt').forEach(sw => {
        const k = sw.getAttribute('data-opt');
        sw.checked = k in conv.diffs ? !!conv.diffs[k] : !!conv.defs[k];
        sw.addEventListener('sl-change', e => {
            const v = !!e.target.checked;
            if (v === !!conv.defs[k]) delete conv.diffs[k]; else conv.diffs[k] = v;
            localStorage.setItem('prism_conv', JSON.stringify(conv.diffs));
        });
    }));
    const st = $.opt('#ai-status'), sw = $.opt('#ai-refine');
    if (d.aiBound) {
        if (st) st.textContent = `Model: ${d.aiModel}${d.aiProvider ? ' (' + d.aiProvider + ')' : ''}`;
        if (sw) sw.removeAttribute('disabled');
    } else {
        if (st) st.textContent = 'No LLM key configured on the engine \u2014 AI refine is unavailable.';
        if (sw) { sw.setAttribute('disabled', ''); delete conv.diffs.refineStructure; }
    }
}

const engineReady = (async () => {
    try { engine = (await backend.health()) === 'ok'; } catch { engine = false; }
    if (engine) {
        $.all('.jx-only').forEach(el => el.removeAttribute('hidden'));
        loadOptions();
        // Heartbeat: the desktop server's lifetime is driven by this SSE stream —
        // without a connected client its watchdog exits after 30 s ("No window
        // connected"), killing every later conversion mid-session.
        if (backend.aliveUrl) try { const es = new EventSource(backend.aliveUrl); es.onerror = () => {}; } catch { }
    }
    wire();
    return engine;
})();

/* -- Hook: %PDF → engine → OCD-EPUB → open in place ------------------ */

P.hooks.openPdf = async (buffer, name) => {
    if (!await engineReady) { P.toast('This is a PDF and no conversion engine is running here.', 'warning'); return; }
    P.setStatus?.('<sl-spinner></sl-spinner> Converting PDF\u2026');
    P.toast('Converting PDF…', 'primary');
    try {
        const art = await backend.convert(buffer, 'ocd', convOpts());
        // the UPLOAD's name is the document's name — the engine only ever saw bytes,
        // its Content-Disposition is a generic fallback
        await book.open(art.bytes, name
            ? name.replace(/\.pdf$/i, '') + '.ocd.epub'
            : art.filename || 'document.ocd.epub');
    } catch (e) { P.toast(`Conversion failed: ${e.message || e}`, 'danger'); P.setStatus?.('<i data-lucide="sparkles"></i> Ready'); }
};

/* -- Hook: book opened — detect jexter/ members, take the text layer -- */

P.on('book', (state) => {
    jx.on = !!state.files?.[JX + 'meta.json'];
    jx.runs = []; jx.structures = []; jx.defaultId = null;
    document.body.classList.toggle('jx-book', jx.on);
    if (!jx.on) { renderStructure(); return; }

    // per-page text layer: the v2 pages are self-contained — every run carries its
    // exact unicode (spaces included) in data-u; document order is reading order
    const XMLU = { '&quot;': '"', '&amp;': '&', '&lt;': '<', '&gt;': '>' };
    const unesc = s => s.replace(/&(?:quot|amp|lt|gt);/g, m => XMLU[m]);
    const RUN = /<g id="(t\d+)" data-ocd="t"[^>]*? data-u="([^"]*)"/g;
    const pages = Object.keys(state.files)
        .filter(p => /^OEBPS\/pages\/page-\d+\.xhtml$/.test(p)).sort();
    pages.forEach((path, i) => {
        const xml = dec(book.get(path));
        const runs = [];
        let m; while ((m = RUN.exec(xml))) runs.push({ id: m[1], text: unesc(m[2]) });
        jx.runs[i] = runs;
        if (state.pages[i]) state.pages[i].text = runs.map(r => r.text).join(' ').replace(/\s+/g, ' ').trim();
    });

    try {
        const ss = JSON.parse(dec(book.get(JX + 'structures.json') || enc('{}')));
        jx.structures = ss.structures || []; jx.defaultId = ss.default || jx.structures[0]?.id || null;
    } catch { }
    renderStructure();
});

P.on('close', () => { jx.on = false; jx.runs = []; jx.structures = []; renderStructure(); document.body.classList.remove('jx-book'); });

/* -- Hook: in-page search highlight, by run id ------------------------ */

P.hooks.highlight = (doc, q, idx) => {
    if (!jx.on) return undefined;                            // generic EPUB → chassis fallback
    const runs = jx.runs[idx] || [], nq = P.fold(q);
    let first = null;
    for (const r of runs) {
        if (!P.fold(r.text).includes(nq)) continue;
        const el = doc.getElementById(r.id);
        if (!el) continue;
        P.markRect(doc, el, 'data-px-hl', 'rgba(78,143,31,.32)');
        if (!first) first = el;
    }
    return first;
};

/* -- Fonts drawer: ONE font at a time + a glyph inspector ------------- */

let fxFonts = [], fxCur = 0, fxSel = -1;

function parseFonts() {
    const raw = P.state.files?.[P.state.opfDir + 'pages/f.svg'];
    if (!raw) return [];
    // the shared OCD API owns the f.svg grammar — the drawer just reshapes for display
    return parseFontsSvg(new TextDecoder().decode(raw)).map(f => ({
        name: f.name || f.id || f.safe, weight: f.weight, style: f.style, embedded: f.embedded,
        // A PDF routinely carries the SAME BaseFont as several distinct font objects, each subset to
        // the glyphs its own section uses — a 600-page legal book: 119 objects for 47 names, 42 of
        // them holding a single glyph. They must stay separate (different encodings, different gids),
        // so the picker needs the writer's disambiguated id or the rows are indistinguishable.
        safe: f.safe, id: f.id,
        asc: f.ascent || .75, desc: Math.abs(f.descent || .25),   // PDF convention: may be negative
        cap: f.cap, xh: f.xh, sp: f.space,
        cmapCount: f.cmap.size, glyphs: f.glyphs,
    }));
}

function buildFontsDrawer() {
    const body = document.getElementById('fonts-body'); if (!body) return;
    fxFonts = parseFonts(); fxCur = 0; fxSel = -1;
    if (!fxFonts.length) {
        body.innerHTML = '<div class="search-info">No font data — this book has no <code>pages/f.svg</code> (generic EPUB?).</div>';
        return;
    }
    const esc = P.esc;
    body.innerHTML = `
      <sl-select id="fx-pick" size="small" value="0" hoist>
        ${fxFonts.map((f, i) => {
            // Same name several times = several font OBJECTS in the PDF. Show the writer's
            // disambiguating suffix (Arial-BoldMT-2) so the rows can be told apart, and the one
            // glyph a single-glyph subset holds — that is what makes such an entry legible.
            const dup = fxFonts.filter(o => o.name === f.name).length > 1;
            const tag = dup && f.safe && f.safe !== f.name ? ` · ${esc(f.safe)}` : '';
            const only = f.glyphs.length === 1 && f.glyphs[0].u && f.glyphs[0].u.trim()
                ? ` ‘${esc(f.glyphs[0].u)}’` : '';
            return `<sl-option value="${i}">${esc(f.name)}${f.weight === 'bold' ? ' — bold' : ''}${f.style === 'italic' ? ' italic' : ''}${tag} (${f.glyphs.length}${only})</sl-option>`;
        }).join('')}
      </sl-select>
      <div id="fx-inspect"></div>
      <div id="fx-meta" class="fx-metrics"></div>
      <div id="fx-grid" class="fx-grid"></div>`;
    document.getElementById('fx-pick').addEventListener('sl-change', e => {
        fxCur = +e.target.value; fxSel = -1; renderFont();
    });
    renderFont();
}

function renderFont() {
    const f = fxFonts[fxCur]; if (!f) return;
    if (fxSel < 0 && f.glyphs.length) {                       // default: the font's first inked glyph
        fxSel = f.glyphs.findIndex(g => g.d);
        if (fxSel < 0) fxSel = 0;
    }
    const esc = P.esc;
    document.getElementById('fx-meta').textContent =
        `asc ${f.asc} · desc ${f.desc} · cap ${f.cap} · x ${f.xh} · sp ${f.sp} · ${f.glyphs.length} glyphs · cmap ${f.cmapCount}${f.embedded ? ' · embedded' : ''}`;
    const grid = document.getElementById('fx-grid');
    grid.innerHTML = f.glyphs.map((g, k) => {
        const w = Math.max(g.adv, .3);
        const tile = g.d
            ? `<svg viewBox="-0.04 ${-f.asc - .04} ${w + .08} ${f.asc + f.desc + .08}"><g transform="scale(1 -1)"><path d="${esc(g.d)}" fill="currentColor"/></g></svg>`
            : `<svg viewBox="0 ${-f.asc} ${w} ${f.asc + f.desc}"><rect x=".02" y="${-f.asc + .02}" width="${w - .04}" height="${f.asc + f.desc - .04}" fill="none" stroke="currentColor" stroke-width=".02" stroke-dasharray=".05 .05"/></svg>`;
        return `<div class="fx-cell${g.d ? '' : ' inkless'}${k === fxSel ? ' sel' : ''}" data-k="${k}">${tile}<div class="fx-cap">${g.u ? esc(g.u) : '·'} ${g.gid}</div></div>`;
    }).join('');
    grid.querySelectorAll('.fx-cell').forEach(c => c.addEventListener('click', () => {
        fxSel = +c.getAttribute('data-k');
        grid.querySelectorAll('.fx-cell.sel').forEach(x => x.classList.remove('sel'));
        c.classList.add('sel');
        renderInspector();
    }));
    renderInspector();
}

// The selected glyph, large, over its metric grid — LINES ONLY (no labels: the
// info panel carries the numbers): baseline green and strong, ascender/descender
// solid neutral, cap-height/x-height dashed, advance as the two verticals.
function renderInspector() {
    const box = document.getElementById('fx-inspect'); if (!box) return;
    const f = fxFonts[fxCur];
    const g = fxSel >= 0 ? f.glyphs[fxSel] : null;
    if (!g) { box.innerHTML = '<div class="fx-hint">Click a glyph below to inspect it.</div>'; return; }
    const esc = P.esc;
    const W = Math.max(g.adv, .6), padL = .22, padR = .22;
    const top = -f.asc - .1, H = f.asc + f.desc + .2;
    const hline = (y, color, width, dash) =>
        `<line x1="${-padL}" y1="${-y}" x2="${W + padR}" y2="${-y}" stroke="${color}" stroke-width="${width}"${dash ? ` stroke-dasharray="${dash}"` : ''}/>`;
    const vline = (x) =>
        `<line x1="${x}" y1="${top + .03}" x2="${x}" y2="${f.desc + .07}" stroke="var(--sl-color-neutral-400)" stroke-width=".009"/>`;
    let s = `<svg class="fx-big" viewBox="${-padL} ${top} ${W + padL + padR} ${H}">`;
    s += hline(f.asc, 'var(--sl-color-neutral-300)', .009);
    if (f.cap) s += hline(f.cap, 'var(--sl-color-neutral-300)', .007, '.025 .02');
    if (f.xh) s += hline(f.xh, 'var(--sl-color-neutral-300)', .007, '.025 .02');
    s += hline(0, 'var(--jx-brand)', .014);                              // the baseline
    s += hline(-f.desc, 'var(--sl-color-neutral-300)', .009);
    s += vline(0) + vline(g.adv);                                        // the advance box
    if (g.d) s += `<g transform="scale(1 -1)"><path d="${esc(g.d)}" fill="currentColor"/></g>`;
    else s += `<rect x=".05" y="${-f.asc + .05}" width="${Math.max(g.adv - .1, .1)}" height="${f.asc + f.desc - .1}" fill="none" stroke="currentColor" stroke-width=".014" stroke-dasharray=".05 .05" opacity=".45"/>`;
    // the pen: ORIGIN (filled) → ADVANCE = the next glyph's origin (hollow), on the baseline
    s += `<circle cx="0" cy="0" r=".026" fill="var(--jx-brand)"><title>origin (0, 0)</title></circle>`;
    s += `<path d="M${g.adv - .045} -.03 L${g.adv - .008} 0 L${g.adv - .045} .03" fill="none" stroke="var(--jx-brand)" stroke-width=".013" stroke-linecap="round" stroke-linejoin="round"/>`;
    s += `<circle cx="${g.adv}" cy="0" r=".026" fill="var(--sl-color-neutral-0, #fff)" stroke="var(--jx-brand)" stroke-width=".013"><title>advance ${g.adv} — the next glyph's origin</title></circle>`;
    s += '</svg>';
    const info = [
        ['char', g.u ? g.u : '—'],
        ['unicode', g.u ? [...g.u].map(c => 'U+' + c.codePointAt(0).toString(16).toUpperCase().padStart(4, '0')).join(' ') : '—'],
        ['gid', g.gid], ['advance', g.adv],
        ['name', g.gname || '—'], ['outline', g.d ? g.d.length + ' chars' : 'inkless'],
    ].map(([k, v]) => `<div class="fx-kv"><span>${k}</span><b>${esc(String(v))}</b></div>`).join('');
    box.innerHTML = `<div class="fx-inspector">${s}<div class="fx-info">${info}</div></div>`;
}

/* -- Hook: read-aloud over the text layer ----------------------------- */

// Speech text is SHAPED here, straight from the page DOM (grammar v2):
//   • furniture paragraphs (header/footer roles) are never read;
//   • soft hyphens (U+00AD) vanish;
//   • a line-final "-" rejoins its word when the next line starts lowercase
//     ("préven-" + "tion" → "prévention"), and stays (joined, no space) before a
//     capital — a compound broken at its own hyphen ("Saint-" + "Gall");
//   • paragraphs come as separate groups: the chassis speaks one utterance each,
//     with a breath in between.
P.hooks.ttsNodes = (doc, idx) => {
    if (!jx.on) return null;                                 // generic EPUB → chassis fallback
    const paras = [];
    for (const pEl of doc.querySelectorAll('svg [data-ocd="p"]')) {
        const runs = [...pEl.querySelectorAll('[data-ocd="t"]')];
        if (!runs.length) continue;
        const role = runs[0].getAttribute('data-role');
        if (role === 'page-header' || role === 'page-footer') continue;
        const lineEls = [...pEl.querySelectorAll(':scope > [data-ocd="l"]')];
        const lines = lineEls.length ? lineEls.map(l => [...l.querySelectorAll('[data-ocd="t"]')]) : [runs];
        const nodes = [];
        lines.forEach((line, li) => {
            line.forEach((el, ri) => {
                let text = (el.getAttribute('data-u') || '').replace(/\u00AD/g, '').replace(/\s+/g, ' ');
                const lastOfLine = ri === line.length - 1, lastLine = li === lines.length - 1;
                if (lastOfLine) {
                    text = text.replace(/\s+$/, '');
                    if (!lastLine && /-$/.test(text)) {
                        const next = lines[li + 1]?.[0]?.getAttribute('data-u') || '';
                        if (/^[\p{Ll}]/u.test(next.trimStart())) text = text.slice(0, -1);   // rejoined word
                        // capital next → keep the hyphen, join without a space (compound)
                    } else if (!lastLine) text += ' ';
                }
                if (text) nodes.push({ el, text });
            });
        });
        if (nodes.length) paras.push(nodes);
    }
    return paras;
};

/* -- Analysis mode: click-to-inspect (DOM + member join, per the spec) --
   The stored SVG carries identity on the elements (id, data-ocd, data-f/-fs);
   the member carries the text. Inspect = read BOTH, reconstruct nothing. */

const KIND = { t: 'text run', p: 'paragraph', g: 'group', gr: 'graphic', media: 'media', page: 'page' };
const SEL_FILL = 'rgba(59,130,246,.28)';

// In analysis mode a run is clickable over its whole BOX, not just its glyph ink
// (SVG2 pointer-events:bounding-box — Chromium-native; on ink it still works everywhere).
const AN_CSS = 'g[data-ocd="t"], g[data-ocd="media"], g[data-ocd="gr"] { pointer-events: bounding-box; }'
    + ' svg[data-ocd="page"] * { cursor: crosshair; }';
function setAnalysisCss(doc, on) {
    try {
        let st = doc.getElementById('jx-an-css');
        if (on && !st) {
            st = doc.createElementNS('http://www.w3.org/1999/xhtml', 'style');
            st.id = 'jx-an-css'; st.textContent = AN_CSS;
            (doc.head || doc.documentElement).appendChild(st);
        } else if (!on && st) st.remove();
    } catch { }
}
const eachFrameDoc = book.eachFrame;               // the seam's iterator — one authority

/* -- Overlays (Prism's Display bounds / Display flow), drawn INSIDE the page --
   Transient <g data-px-ov> appended to the page's flip wrapper (same user space as
   the content, so getBBox coordinates land exactly); text counter-flips locally. */

const an = { bounds: false, flow: false, stage: '' };   // stage: '' | runs | lines | leaves | blocks | labeled
// The reading blocks' PARENT is the overlay's coordinate space — in the stored pages
// paragraphs sit directly under <svg> (each run carries its own flip matrix), but this
// stays correct should a rotation/flip wrapper ever appear: we follow the paragraphs.
const contentRootOf = (doc) => {
    const svg = doc.querySelector('svg'); if (!svg) return null;
    const p = svg.querySelector('[data-ocd="p"], [data-ocd="gr"], [data-ocd="media"]');
    return p ? p.parentNode : svg;
};
// Mirrored space? (a flipped wrapper would mirror plain <text>) — determinant of the CTM.
const isFlipped = (el) => { try { const m = el.getScreenCTM(); return !!m && (m.a * m.d - m.b * m.c) < 0; } catch { return false; } };
const ovText = (doc, flip, x, y, str, fill, size, anchor) => {
    const t = doc.createElementNS(SVG_NS, 'text');
    t.setAttribute('transform', flip ? `translate(${x} ${y}) scale(1 -1)` : `translate(${x} ${y})`);
    t.setAttribute('font-size', size); t.setAttribute('fill', fill);
    t.setAttribute('font-family', 'sans-serif'); t.setAttribute('pointer-events', 'none');
    if (anchor) t.setAttribute('text-anchor', anchor);
    t.textContent = str;
    return t;
};

// Role of a paragraph: heading level from the active structure, else the runs' data-role.
function headingMap(idx) {
    const map = {};
    const st = activeStruct || jx.structures.find(s => s.id === jx.defaultId) || jx.structures[0];
    if (!st) return map;
    (function walk(n) {
        if (n.type === 'heading') for (const r of n.refs || [])
            if (pageIndexOf(r.page) === idx) map[r.node] = 'h' + Math.min(6, Math.max(1, n.level || 1));
        (n.children || []).forEach(walk);
    })(st.root);
    return map;
}
function paraRole(p, hmap) {
    const own = p.getAttribute('data-role');
    if (own && own.startsWith('heading-')) return 'h' + own.slice(8);
    for (const t of p.querySelectorAll(':scope > [data-ocd="t"]')) {
        if (hmap[t.id]) return hmap[t.id];
        const r = t.getAttribute('data-role');
        if (r === 'page-header') return 'header';
        if (r === 'page-footer') return 'footer';
    }
    return 'p';
}
const roleColor = (r) => r[0] === 'h' && r !== 'header' ? '#d97706'
    : (r === 'header' || r === 'footer') ? '#8b8b8b' : '#4e8f1f';

function drawOverlays(doc, idx) {
    const w = contentRootOf(doc); if (!w) return;
    doc.querySelectorAll('[data-px-ov]').forEach(n => n.remove());
    if (!an.bounds && !an.flow && !an.stage) return;
    const flip = isFlipped(w);
    const g = doc.createElementNS(SVG_NS, 'g');
    g.setAttribute('data-px-ov', '1'); g.setAttribute('pointer-events', 'none');
    const hmap = headingMap(idx);
    const blocks = [...w.querySelectorAll(':scope > [data-ocd="p"], :scope > image, :scope > [data-ocd="gr"], :scope > [data-ocd="media"]')];
    const boxes = [];
    for (const b of blocks) {
        let bb; try { bb = b.getBBox(); } catch { continue; }
        if (!bb.width && !bb.height) continue;
        const isP = b.getAttribute('data-ocd') === 'p';
        const role = isP ? paraRole(b, hmap) : (b.tagName === 'image' ? 'img' : 'fig');
        boxes.push({ el: b, bb, isP, role, furniture: role === 'header' || role === 'footer' });
    }
    if (an.bounds) {
        for (const { bb, isP, role } of boxes) {
            const col = isP ? roleColor(role) : '#7c3aed';
            const r = doc.createElementNS(SVG_NS, 'rect');
            r.setAttribute('x', bb.x - 1.5); r.setAttribute('y', bb.y - 1.5);
            r.setAttribute('width', bb.width + 3); r.setAttribute('height', bb.height + 3);
            r.setAttribute('fill', col + '14'); r.setAttribute('stroke', col); r.setAttribute('stroke-width', '.7'); r.setAttribute('rx', '1.5');
            g.appendChild(r);
            g.appendChild(ovText(doc, flip, bb.x - 3.5, bb.y + bb.height / 2 + 2.2, role, col, 6.5, 'end'));
        }
    }
    if (an.flow) {
        const flow = boxes.filter(b => !b.furniture);        // headers/footers are OUT of the reading flow
        const pts = flow.map(({ bb }) => ({ cx: Math.max(8, bb.x - 11), cy: bb.y + bb.height / 2 }));
        pts.forEach((p, i) => {
            const c = doc.createElementNS(SVG_NS, 'circle');
            c.setAttribute('cx', p.cx); c.setAttribute('cy', p.cy); c.setAttribute('r', '6'); c.setAttribute('fill', '#2563eb');
            g.appendChild(c);
            if (i < pts.length - 1) {                        // directional tip on the rim, aimed at the NEXT bullet
                const n = pts[i + 1];
                const deg = Math.atan2(n.cy - p.cy, n.cx - p.cx) * 180 / Math.PI;
                const tip = doc.createElementNS(SVG_NS, 'path');
                tip.setAttribute('d', 'M 6.4 -2.9 L 10.8 0 L 6.4 2.9 Z');
                tip.setAttribute('fill', '#2563eb');
                tip.setAttribute('transform', `translate(${p.cx} ${p.cy}) rotate(${deg})`);
                g.appendChild(tip);
            }
            g.appendChild(ovText(doc, flip, p.cx, p.cy + 2.2, String(i + 1), '#fff', 6.5, 'middle'));
        });
    }
    if (an.stage) drawStage(g, doc, idx);
    w.appendChild(g);
}
/* -- Pipeline stage scrubber: to=stages geometry over the page ---------- */
const STAGE_COLOR = { runs: '#94a3b8', lines: '#38bdf8', leaves: '#a78bfa', blocks: '#4e8f1f' };
const LABEL_COLOR = { H: '#d97706', P: '#4e8f1f', F: '#8b8b8b' };
let stagesData = null, stagesLoading = false;

async function ensureStages() {
    if (stagesData || stagesLoading || !engine) return;
    stagesLoading = true;
    P.toast('Computing pipeline stages…', 'primary');
    try { stagesData = await backend.stages(bookBytes()); }
    catch { stagesData = null; }
    stagesLoading = false;
    if (!stagesData) { P.toast('Stages unavailable.', 'warning'); return; }
    refreshOverlays();
}

// stage boxes come in PAGE user space (Y-up, origin = the page box) — map to viewBox space
function drawStage(g, doc, idx) {
    const pg = stagesData?.pages?.[idx]; if (!pg) return;
    const [bx, by, , bh] = pg.box;
    const put = (b, color, label) => {
        const [x0, y0, x1, y1] = b;
        const r = doc.createElementNS(SVG_NS, 'rect');
        r.setAttribute('x', x0 - bx); r.setAttribute('y', (by + bh) - y1);
        r.setAttribute('width', x1 - x0); r.setAttribute('height', y1 - y0);
        r.setAttribute('fill', 'none'); r.setAttribute('stroke', color);
        r.setAttribute('stroke-width', '.7'); r.setAttribute('pointer-events', 'none');
        g.appendChild(r);
        if (label != null)
            g.appendChild(ovText(doc, false, x0 - bx + 1.5, (by + bh) - y1 + 6, String(label), color, 5.5));
    };
    if (an.stage === 'labeled') for (const e of pg.labeled || []) put(e.b, LABEL_COLOR[e.k] || '#4e8f1f', e.i);
    else for (const b of pg[an.stage] || []) put(b, STAGE_COLOR[an.stage] || '#4e8f1f');
}

const refreshOverlays = () => { if (P.appMode?.() === 'analysis') eachFrameDoc((d, i) => drawOverlays(d, i)); };

/* -- Page DOM tree (Prism's node tree, from the stored SVG + member text) -- */

let treeIdx = -1;
const truncate = (t, n) => t.length > n ? t.slice(0, n - 1) + '\u2026' : t;
function buildTree(idx) {
    const host = $.opt('#page-tree'); if (!host) return;
    treeIdx = idx;
    P.whenFrameReady(idx, doc => {
        if (treeIdx !== idx) return;                         // page changed while loading
        const w = contentRootOf(doc); if (!w) return;
        host.innerHTML = '';
        const row = (el, depth, label, id) => {
            const kids = el.getAttribute?.('data-ocd') === 'p' ? [...el.querySelectorAll(':scope > [data-ocd="t"]')] : [];
            const item = document.createElement('div'); item.className = 'nt-item' + (kids.length ? ' collapsed' : '');
            const r = document.createElement('div'); r.className = 'nt-row'; r.setAttribute('data-nid', id);
            r.style.paddingLeft = (4 + depth * 12) + 'px';
            r.innerHTML = (kids.length ? '<span class="nt-caret">\u203a</span>' : '<span class="nt-dot"></span>')
                + `<span class="nt-lbl">${P.esc(label)}</span><span class="nt-id">${P.esc(id)}</span>`;
            r.addEventListener('click', e => {
                if (kids.length && e.target.classList.contains('nt-caret')) { item.classList.toggle('collapsed'); return; }
                selectInPage(idx, id);
            });
            item.appendChild(r);
            if (kids.length) {
                const kd = document.createElement('div'); kd.className = 'nt-kids';
                for (const k of kids) kd.appendChild(row(k, depth + 1, truncate(runText(idx, k.id) || 'run', 34), k.id));
                item.appendChild(kd);
            }
            return item;
        };
        for (const el of w.children) {
            if (el.hasAttribute?.('data-px-ov') || el.tagName === 'defs' || el.tagName === 'style') continue;
            const ocd = el.getAttribute?.('data-ocd') || '';
            const id = el.getAttribute?.('id') || '';
            if (!id) continue;
            const label = ocd === 'p' ? truncate([...el.querySelectorAll('[data-ocd="t"]')].map(t => runText(idx, t.id)).join(' ').trim() || 'paragraph', 34)
                : ocd ? (KIND[ocd] || ocd)
                : el.tagName === 'image' ? 'image' : el.tagName === 'path' ? 'path' : el.tagName.toLowerCase();
            host.appendChild(row(el, 0, label, id));
        }
    });
}
function selectInPage(idx, id) {
    P.whenFrameReady(idx, doc => {
        const el = doc.getElementById(id); if (!el) return;
        $.all('.prism-frame').forEach(fr => { try { fr.contentDocument?.querySelectorAll('[data-px-sel]').forEach(n => n.remove()); } catch {} });
        P.markRect(doc, el, 'data-px-sel', SEL_FILL);
        P.centreOn(idx, el);
        renderInspect(el, idx);
        markTreeRow(id);
    });
}
function markTreeRow(id) {
    const host = $.opt('#page-tree'); if (!host) return;
    host.querySelectorAll('.nt-row.active').forEach(r => r.classList.remove('active'));
    const r = host.querySelector(`.nt-row[data-nid="${(window.CSS && CSS.escape) ? CSS.escape(id) : id}"]`);
    if (r) { r.closest('.nt-item.collapsed')?.classList.remove('collapsed');
        const p = r.closest('.nt-kids')?.closest('.nt-item'); if (p) p.classList.remove('collapsed');
        r.classList.add('active'); r.scrollIntoView({ block: 'nearest' }); }
}

P.on('frame', (f, idx) => {
    let doc; try { doc = f.contentDocument; } catch { return; }
    if (!doc || doc.__jxInspect) return;
    doc.__jxInspect = true;
    if (P.appMode?.() === 'analysis') { setAnalysisCss(doc, true); drawOverlays(doc, idx); }
    doc.addEventListener('click', e => {
        if (P.appMode?.() !== 'analysis') return;
        const el = e.target.closest?.('[data-ocd], path[id], image, [id^="i"], [id^="d"]');
        const svg = doc.querySelector('svg');
        if (!el || !svg || el === svg) return;
        e.preventDefault(); e.stopPropagation();
        $.all('.prism-frame').forEach(fr => { try { fr.contentDocument?.querySelectorAll('[data-px-sel]').forEach(n => n.remove()); } catch {} });
        P.markRect(doc, el, 'data-px-sel', SEL_FILL);
        renderInspect(el, idx);
        markTreeRow(el.getAttribute('id') || '');
    }, true);
});

function runText(idx, id) { return (jx.runs[idx] || []).find(r => r.id === id)?.text || ''; }

function renderInspect(el, idx) {
    const host = $.opt('#inspect-body'); if (!host) return;
    const id = el.getAttribute('id') || '';
    const ocd = el.getAttribute('data-ocd') || '';
    const kind = KIND[ocd] || (el.tagName === 'path' ? 'path' : el.tagName === 'image' ? 'image' : el.tagName.toLowerCase());
    const rows = [];
    const kv = (k, v) => { if (v !== '' && v != null) rows.push(`<div class="nd-k">${P.esc(k)}</div><div class="nd-v">${P.esc(String(v))}</div>`); };
    kv('id', id || '\u2014');
    kv('page', 'p' + (idx + 1));
    let bb = null; try { bb = el.getBBox(); } catch {}
    if (bb && P.toUnit) {
        kv('pos', `${P.toUnit(bb.x)}, ${P.toUnit(bb.y)}`);
        kv('size', `${P.toUnit(bb.width)} \u00d7 ${P.toUnit(bb.height)}`);
    }
    if (ocd === 't') {
        kv('font', el.getAttribute('data-f'));
        // v2: the font size lives in the run matrix — sqrt|det|
        const tm = /matrix\(([^)]+)\)/.exec(el.getAttribute('transform') || '');
        if (tm) {
            const [a2, b2, c2, d2] = tm[1].trim().split(/[\s,]+/).map(Number);
            const fs = Math.sqrt(Math.abs(a2 * d2 - b2 * c2));
            if (fs) kv('size', fs.toFixed(2).replace(/\.00$/, '') + ' pt');
        }
    }
    if (el.tagName === 'image') kv('href', (el.getAttribute('href') || el.getAttribute('xlink:href') || '').split('/').pop());
    if (ocd === 'media') kv('kind', el.getAttribute('data-kind'));
    if (ocd === 'p' || ocd === 'g' || ocd === 'gr') kv('children', el.children.length);
    let text = '';
    if (ocd === 't') text = runText(idx, id);
    else if (ocd === 'p') text = [...el.querySelectorAll('[data-ocd="t"]')].map(t => runText(idx, t.id)).join(' ').trim();
    host.innerHTML = `<div class="nd-type">${P.esc(kind)}</div><div class="nd-rows">${rows.join('')}</div>`
        + (text ? `<div class="nd-text">${P.esc(text)}</div>` : '');
}

// experimental: hidden unless dev mode is on (Ctrl/Cmd+Shift+D) — see registerMode in prism.js.
// Everything below stays live; shipping the tab is a one-flag change.
P.registerMode({
    id: 'analysis', label: 'Analysis', icon: 'crosshair', title: 'Analysis — inspect the document', experimental: true,
    onEnter() {
        eachFrameDoc((d, i) => { setAnalysisCss(d, true); drawOverlays(d, i); });
        buildTree(Math.max(0, P.state.current));
    },
    onLeave() {
        eachFrameDoc(d => { setAnalysisCss(d, false); d.querySelectorAll('[data-px-ov]').forEach(n => n.remove()); });
        $.all('.prism-frame').forEach(fr => { try { fr.contentDocument?.querySelectorAll('[data-px-sel]').forEach(n => n.remove()); } catch {} });
    },
});
P.on('page', (idx) => { if (P.appMode?.() === 'analysis') buildTree(idx); });

/* -- Structure rail: the structures.json trees ------------------------ */

let activeStruct = null;

function renderStructure() {
    const host = $.opt('#structure-body'); if (!host) return;
    host.innerHTML = '';
    const has = jx.on && jx.structures.length > 0;
    host.toggleAttribute('hidden', !has);                    // Contents tab: structures when the
    $.opt('#toc-list')?.toggleAttribute('hidden', has);      // book carries them, EPUB TOC otherwise
    if (!has) return;

    activeStruct = jx.structures.find(s => s.id === activeStruct?.id)
        || jx.structures.find(s => s.id === jx.defaultId) || jx.structures[0];

    const FLAG = { pdf: 'PDF', model: 'AI', manual: 'MANUAL', heuristic: 'AUTO' };
    const pills = document.createElement('div'); pills.className = 'jx-pills';
    for (const s of jx.structures) {
        const b = document.createElement('button');
        b.className = 'jx-pill' + (s === activeStruct ? ' active' : '');
        b.textContent = s.label || s.id;
        b.title = [FLAG[s.source] || s.source, s.by && 'by ' + s.by, s.how].filter(Boolean).join(' · ');
        b.onclick = () => { activeStruct = s; renderStructure(); };
        pills.appendChild(b);
    }
    host.appendChild(pills);

    const ol = document.createElement('ol'); ol.className = 'nav-list nav-tree';
    walkHeads(activeStruct.root, ol);
    host.appendChild(ol);
}

function walkHeads(node, into) {
    for (const c of node?.children || []) {
        if (c.type === 'heading') {
            const li = document.createElement('li');
            const a = document.createElement('a');
            a.textContent = headTitle(c) || '(untitled)';
            a.style.paddingLeft = (0.4 + 0.7 * Math.min(5, (c.level || 1) - 1)) + 'rem';
            a.onclick = () => jumpToStruct(c);
            li.appendChild(a); into.appendChild(li);
        }
        walkHeads(c, into);
    }
}

const pageIndexOf = (pageId) => {                            // "p1" → 0 (the writer's page ids)
    const m = /^p(\d+)$/.exec(pageId || ''); return m ? +m[1] - 1 : -1;
};

function headTitle(n) {
    const parts = [];
    for (const r of n.refs || []) {
        const pi = pageIndexOf(r.page);
        const run = (jx.runs[pi] || []).find(x => x.id === r.node);
        if (run) parts.push(run.text);
    }
    return parts.join(' ').replace(/\s+/g, ' ').trim();
}

function jumpToStruct(n) {
    const refs = n.refs || []; if (!refs.length) return;
    const pi = pageIndexOf(refs[0].page); if (pi < 0) return;
    const ids = refs.filter(r => r.page === refs[0].page).map(r => r.node);
    P.clearAllHl();
    P.goTo(pi);
    P.whenFrameReady(pi, doc => {
        let first = null;
        for (const id of ids) {
            const el = doc.getElementById(id) || doc.getElementById(paraOf(pi, id));
            if (!el) continue;
            P.markRect(doc, el, 'data-px-hl', 'rgba(78,143,31,.30)');
            if (!first) first = el;
        }
        if (first) P.centreOn(pi, first);
    });
}

// A struct ref may point at a run inside a paragraph group — either id exists in the page.
const paraOf = () => '';

/* -- Export as… : the container bytes through the engine -------------- */

function bookBytes() {
    const files = P.state.files; if (!files) return null;
    const out = { mimetype: [files['mimetype'] || enc('application/epub+zip'), { level: 0 }] };
    for (const [p, b] of Object.entries(files)) if (p !== 'mimetype') out[p] = b;
    return zipSync(out);
}

const EXPORT_EXT = { pdf: '.pdf', ocd: '.ocd.epub', epub: '.epub', html: '.html', md: '.md', doctags: '.doctags' };

async function exportAs(to, opts = {}) {
    if (!engine) return;
    const bytes = bookBytes();
    if (!bytes) { P.toast('Open a document first.', 'warning'); return; }
    // save dialog FIRST (while the click's transient activation is valid):
    // original name, new extension — then convert, then write to the handle
    const name = P.exportName(EXPORT_EXT[to] || '.' + to);
    const handle = await P.pickSave(name);
    if (handle === 'aborted') return;

    // EPUB PRISM with live edits: the in-memory container ALREADY carries them
    // (media/ payloads, patched manifest, prism/augment.json — the edit model).
    // Save it verbatim: the edits stay RE-EDITABLE on reopen. The engine
    // round-trip would drop the augment member (unknown to the OCD model) and
    // orphan the media — so with edits present, the engine is bypassed.
    if (to === 'ocd' && Object.values(P.state.aug?.pages || {}).some(l => l.length)) {
        await P.saveAs(new Blob([bytes], { type: 'application/epub+zip' }), name, handle);
        P.toast(`Exported ${name} — edits included, re-editable`, 'success');
        return;
    }

    P.toast(`Exporting ${to}…`, 'primary');
    try {
        const art = await backend.convert(bytes, to, opts);
        await P.saveAs(art.blob, name, handle);
        P.toast(`Exported ${name}`, 'success');
    } catch (e) { P.toast(`Export failed: ${e.message || e}`, 'danger'); }
}

function wire() {
    $.opt('#an-bounds')?.addEventListener('sl-change', e => { an.bounds = !!e.target.checked; refreshOverlays(); });
    $.opt('#an-flow')?.addEventListener('sl-change', e => { an.flow = !!e.target.checked; refreshOverlays(); });
    $.opt('#m-fonts')?.addEventListener('click', () => { buildFontsDrawer(); document.getElementById('fonts-drawer').show(); });
    // F2 console, server pane: live JxLog events over SSE (EventSource reconnects itself)
    if (engine && backend.logStreamUrl) {
        const es = new EventSource(backend.logStreamUrl);
        es.onmessage = ev => {
            try {
                const e = JSON.parse(ev.data);   // {level, src, msg} — Prism's log bridge shape
                P.logLine(e.level || 'info', (e.src ? e.src + ': ' : '') + e.msg, 'server');
            } catch { /* malformed event — skip */ }
        };
    }
    $.opt('#an-heuristic')?.addEventListener('click', async () => {
        if (!engine) { P.toast('No conversion engine here.', 'warning'); return; }
        const bytes = bookBytes();
        if (!bytes) { P.toast('Open a document first.', 'warning'); return; }
        if (Object.values(P.state.aug?.pages || {}).some(l => l.length)
            && !confirm('Re-analyzing rebuilds the book — your live edits will be dropped. Continue?')) return;
        P.toast('Re-analyzing — 100% heuristic…', 'primary');
        try {
            // the engine clears inherited heading roles, rebuilds the heuristic structure,
            // makes it the default and re-projects it — pure geometry, nothing inherited
            const art = await backend.convert(bytes, 'ocd', { restructureHierarchy: 'true' });
            const name = P.state.name;
            await book.open(art.bytes, name);
            stagesData = null;                                  // stale: recompute on next use
            P.toast('Heuristic analysis applied — heuristic structure is now the default.', 'success');
        } catch (e) { P.toast(`Re-analysis failed: ${e.message || e}`, 'danger'); }
    });
    $.all('#an-stages button').forEach(b => b.addEventListener('click', () => {
        $.all('#an-stages button').forEach(x => x.classList.toggle('active', x === b));
        an.stage = b.getAttribute('data-stage') || '';
        if (an.stage && !stagesData) ensureStages();
        refreshOverlays();
    }));
    $.all('#export-dialog .export-opt[data-to]').forEach(b => b.addEventListener('click', () => {
        $.opt('#export-dialog')?.hide();
        const opts = convOpts();
        if (b.hasAttribute('data-selectable')) opts.selectable = 'true';
        if (b.getAttribute('data-page') === 'cur') opts.page = String(Math.max(0, P.state.current));
        exportAs(b.getAttribute('data-to'), opts);
    }));
}
