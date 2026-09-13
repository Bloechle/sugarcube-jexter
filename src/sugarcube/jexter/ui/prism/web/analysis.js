// analysis.js — "Analysis": what the model says about the page in front of you.
//
// The overlays (bounds, reading flow, a pipeline stage), the click-to-inspect join between the DOM and
// the members, the page's node tree, and Re-analyze. Trace — the real-geometry pointer — is `trace.js`;
// this tool only flips its flag, and that module turns itself off when the tool changes.
//
// Split out of `jexter.js` on 2026-09-07: the file registering this tool was also Prism's engine seam,
// and a newcomer looking for either found both. The seam is `engine.js`, and everything this file needs
// from it is imported by name.
import { jx, engine, bookBytes, pageIndexOf, activeStructure } from './engine.js';
import { SVG_NS } from '/shared/js/ocd.js';        // the namespace every overlay element is created in
import * as backend from './backend.js';
import { book } from '/shared/js/book.js';

const P = window.prism;

/* -- Analysis mode: click-to-inspect (DOM + member join, per the spec) --
   The stored SVG carries identity on the elements (id, data-ocd, data-f/-fs);
   the member carries the text. Inspect = read BOTH, reconstruct nothing. */

// 'graphic' n'apparaît PAS dans une page : le groupement vectoriel vit dans le modèle, pas
// dans le conteneur — ses chemins n'ont pas une plage de z contiguë (voir SvgOcdWriter).
const KIND = { run: 'text run', paragraph: 'paragraph', line: 'line', group: 'group', media: 'media', page: 'page' };
const SEL_FILL = 'rgba(59,130,246,.28)';

// In analysis mode a run is clickable over its whole BOX, not just its glyph ink
// (SVG2 pointer-events:bounding-box — Chromium-native; on ink it still works everywhere).
const AN_CSS = 'g[data-ocd="run"], g[data-ocd="media"] { pointer-events: bounding-box; }'
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
    const p = svg.querySelector('[data-ocd="paragraph"], [data-ocd="media"]');
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
    const st = activeStructure();
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
    for (const t of p.querySelectorAll(':scope > [data-ocd="run"]')) {
        if (hmap[t.id]) return hmap[t.id];
        const r = t.getAttribute('data-role');
        if (r === 'page-header') return 'header';
        if (r === 'page-footer') return 'footer';
    }
    return 'p';
}
const roleColor = (r) => r[0] === 'h' && r !== 'header' ? '#d97706'
    : (r === 'header' || r === 'footer') ? '#8b8b8b' : '#33699f';

function drawOverlays(doc, idx) {
    const w = contentRootOf(doc); if (!w) return;
    doc.querySelectorAll('[data-px-ov]').forEach(n => n.remove());
    if (!an.bounds && !an.flow && !an.stage) return;
    const flip = isFlipped(w);
    const g = doc.createElementNS(SVG_NS, 'g');
    g.setAttribute('data-px-ov', '1'); g.setAttribute('pointer-events', 'none');
    const hmap = headingMap(idx);
    const blocks = [...w.querySelectorAll(':scope > [data-ocd="paragraph"], :scope > image, :scope > [data-ocd="media"]')];
    const boxes = [];
    for (const b of blocks) {
        let bb; try { bb = b.getBBox(); } catch { continue; }
        if (!bb.width && !bb.height) continue;
        const isP = b.getAttribute('data-ocd') === 'paragraph';
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
const STAGE_COLOR = { runs: '#94a3b8', lines: '#38bdf8', leaves: '#a78bfa', blocks: '#33699f' };
const LABEL_COLOR = { H: '#d97706', P: '#33699f', F: '#8b8b8b' };
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
    if (an.stage === 'labeled') for (const e of pg.labeled || []) put(e.b, LABEL_COLOR[e.k] || '#33699f', e.i);
    else for (const b of pg[an.stage] || []) put(b, STAGE_COLOR[an.stage] || '#33699f');
}

const refreshOverlays = () => { if (P.tool?.() === 'analysis') eachFrameDoc((d, i) => drawOverlays(d, i)); };

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
            const kids = el.getAttribute?.('data-ocd') === 'paragraph' ? [...el.querySelectorAll(':scope > [data-ocd="run"]')] : [];
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
            const label = ocd === 'paragraph' ? truncate([...el.querySelectorAll('[data-ocd="run"]')].map(t => runText(idx, t.id)).join(' ').trim() || 'paragraph', 34)
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
    if (P.tool?.() === 'analysis') { setAnalysisCss(doc, true); drawOverlays(doc, idx); }
    doc.addEventListener('click', e => {
        if (P.tool?.() !== 'analysis') return;
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
    if (ocd === 'run') {
        kv('font', el.getAttribute('data-font'));
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
    if (ocd === 'paragraph' || ocd === 'group' || ocd === 'graphic') kv('children', el.children.length);
    let text = '';
    if (ocd === 'run') text = runText(idx, id);
    else if (ocd === 'paragraph') text = [...el.querySelectorAll('[data-ocd="run"]')].map(t => runText(idx, t.id)).join(' ').trim();
    host.innerHTML = `<div class="nd-type">${P.esc(kind)}</div><div class="nd-rows">${rows.join('')}</div>`
        + (text ? `<div class="nd-text">${P.esc(text)}</div>` : '');
}

// experimental: hidden unless dev mode is on (Ctrl/Cmd+Shift+D) — see registerTool in prism.js.
// Everything below stays live; shipping the tab is a one-flag change.
// The ribbon: what Analysis DOES. The drawer keeps what it SHOWS — the node tree and the inspector,
// which used to start below two rows of controls.
const PIPELINE = [
    ['',        'Off',     'circle-slash'],
    ['runs',    'Runs',    'type'],
    ['lines',   'Lines',   'align-left'],
    ['leaves',  'Leaves',  'list'],
    ['blocks',  'Blocks',  'square'],
    ['labeled', 'Labeled', 'tags'],
];

function anRibbon() {
    // Every overlay here draws ON a document and every stage is read FROM one: with none open there is
    // nothing to arm. Disabled, not silently inert — the rule the rest of the chassis follows.
    const has = book.isOpen();
    P.ribbon('analysis', [
        { group: 'Overlay', items: [
            { icon: 'frame',      label: 'Bounds', title: 'Draw the geometry of every node', active: an.bounds, disabled: !has, on: () => { an.bounds = !an.bounds; anRibbon(); refreshOverlays(); } },
            { icon: 'git-branch', label: 'Flow',   title: 'Draw the reading order',          active: an.flow, disabled: !has, on: () => { an.flow = !an.flow;   anRibbon(); refreshOverlays(); } },
            // Reveal, folded in as what it always was: another way to look at the same page. It traces the
            // REAL geometry — glyph outlines, the path's own `d` — where the rest of Analysis draws the box
            // the model reasons about. Owned by trace.js; this only flips its flag.
            { icon: 'scan-search', label: 'Trace', title: 'Point at anything and it answers for itself — click to pin, Esc to release',
              active: !!P.trace?.on, disabled: !has, on: () => { P.trace?.toggle(); anRibbon(); } },
        ]},
        { group: 'Pipeline', items: PIPELINE.map(([id, label, icon]) => ({
            icon, label, title: id ? `Show the ${label.toLowerCase()} stage` : 'No stage overlay', disabled: !has,
            active: an.stage === id,
            on: () => { an.stage = id; anRibbon(); if (id && !stagesData) ensureStages(); refreshOverlays(); },
        }))},
        { group: 'Rebuild', items: [
            { icon: 'refresh-cw', label: 'Re-analyze', title: 'Rebuild the structure — 100% heuristic', disabled: !has, on: reanalyze },
        ]},
    // Trace's whole interaction is the pointer, and a tooltip is not discoverable. Say it while it is on
    // — and say it through the declaration: the ribbon is re-declared on every change of state anyway,
    // so a line appended after it had to be removed by hand on the next render.
    ], P.trace?.on ? 'Point at anything on the page — click to pin, Esc to release.' : '');
}

P.registerTool({
    id: 'analysis', label: 'Analysis', icon: 'crosshair', drawer: 'Inspect', title: 'Analysis — inspect the document', experimental: true,
    onEnter() {
        anRibbon();
        eachFrameDoc((d, i) => { setAnalysisCss(d, true); drawOverlays(d, i); });
        buildTree(Math.max(0, P.state.current));
    },
    // (Trace turns itself off: trace.js subscribes to `tool` and cuts out for any id but this one. A
    // second `onLeave` key here said so too — and a duplicated key in an object literal is simply the
    // last one, so it never ran. The rule was never lost, only claimed twice.)
    onLeave() {
        eachFrameDoc(d => { setAnalysisCss(d, false); d.querySelectorAll('[data-px-ov]').forEach(n => n.remove()); });
        $.all('.prism-frame').forEach(fr => { try { fr.contentDocument?.querySelectorAll('[data-px-sel]').forEach(n => n.remove()); } catch {} });
    },
});
P.on('page', (idx) => { if (P.tool?.() === 'analysis') buildTree(idx); });

async function reanalyze() {
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
}

