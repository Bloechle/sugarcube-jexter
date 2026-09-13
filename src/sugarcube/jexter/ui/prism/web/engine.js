// engine.js — the ENGINE SEAM of Prism: what a local jexter makes possible.
//
// The chassis (prism.js) reads any fixed-layout EPUB off the original tree and knows nothing about an
// engine. This module is the seam: it detects one, routes %PDF drops through it, takes the text layer
// out of an OCD-EPUB's own pages, feeds search and read-aloud from it, shows the fonts the container
// carries, lists the structures it declares, and exports the bytes back through the engine.
//
// It was one file with the ANALYSIS tool until 2026-09-07, and 841 lines doing two jobs — a seam every
// tool leans on, and one tool's own surface. They shared five names between them, which is what said
// the cut was real rather than tidy. Analysis lives in `analysis.js` now and imports what it needs
// from here; nothing here reaches back into it.
//
// Exported for that tool, and for it alone: `jx` (what the open container carries), `engine` (is one
// there), `bookBytes` (the container as it stands) and `pageIndexOf`.

import { zipSync } from 'https://cdn.jsdelivr.net/npm/fflate@0.8.2/+esm';
import * as backend from './backend.js';
import { parseFonts as parseFontsSvg, SVG_NS } from '/shared/js/ocd.js';
import { book } from '/shared/js/book.js';

const P = window.prism;                       // the chassis seam (state, hooks, API)
const dec = b => new TextDecoder().decode(b);
const enc = s => new TextEncoder().encode(s);
const JX = 'OEBPS/ocd/';

export const jx = { on: false, runs: [], structures: [], defaultId: null };   // runs[i] = [{id, text}]
// A LIVE binding on purpose: it is false until /api/health answers, and ESM gives importers the
// binding rather than a copy — `analysis.js` sees it flip without asking again.
export let engine = false;

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

// One vocabulary, two sources. The transport reports what only it can see (the bytes going out, the bytes
// coming back); the engine reports what only it can see (pages opened, imported, analysed, written). Both
// speak {stage, done, total, detail}, so this renderer never has to know which side a step came from.
const STAGES = {
    upload:   { label: 'Uploading',  unit: 'bytes' },
    open:     { label: 'Opening'  },
    fonts:    { label: 'Checking fonts' },
    import:   { label: 'Importing' },
    analysis: { label: 'Analyzing' },
    write:    { label: 'Writing'  },
    download: { label: 'Receiving', unit: 'bytes' },
    unpack:   { label: 'Unpacking' },
};

const mb = n => (n / 1048576).toFixed(1) + ' MB';

function showStep(p) {
    const s = STAGES[p.stage];
    if (!s) return;
    // The page count and page-one's size arrive with "open", about a tenth of a second in: enough to lay
    // the book out for real and stop showing an empty stage.
    if (p.stage === 'open' && p.total) {
        const [w, h] = String(p.detail || '').split('x').map(Number);
        P.placeholders?.(p.total, w || 595, h || 842);
    }
    let of = '';
    if (p.total && s.unit === 'bytes') of = ` ${mb(p.done)} / ${mb(p.total)}`;
    else if (p.total)                 of = ` ${Math.max(1, p.done)}/${p.total}`;
    P.setStatus?.(`<sl-spinner></sl-spinner> ${s.label}${of}\u2026`);
}

/** Subscribe to the engine's side for the duration of one conversion. Returns a stop function. */
function watchProgress() {
    if (!backend.progressUrl) return () => {};
    let es;
    try { es = new EventSource(backend.progressUrl); } catch { return () => {}; }
    es.onerror = () => {};
    es.onmessage = ev => { try { showStep(JSON.parse(ev.data)); } catch { } };
    return () => { try { es.close(); } catch { } };
}

P.hooks.openPdf = async (buffer, name) => {
    if (!await engineReady) { P.toast('This is a PDF and no conversion engine is running here.', 'warning'); return; }
    P.setStatus?.('<sl-spinner></sl-spinner> Converting PDF\u2026');
    P.toast('Converting PDF…', 'primary');
    const stop = watchProgress();
    try {
        const art = await backend.convert(buffer, 'ocd', convOpts(), showStep);
        showStep({ stage: 'unpack', done: 0, total: 0 });
        // the UPLOAD's name is the document's name — the engine only ever saw bytes,
        // its Content-Disposition is a generic fallback
        await book.open(art.bytes, name
            ? name.replace(/\.pdf$/i, '') + '.ocd.epub'
            : art.filename || 'document.ocd.epub');
    } catch (e) { P.toast(`Conversion failed: ${e.message || e}`, 'danger'); P.setStatus?.('<i data-lucide="sparkles"></i> Ready'); }
    finally { stop(); }
};

/* -- Hook: book opened — detect ocd/ members, take the text layer -- */

P.on('book', (state) => {
    jx.on = !!state.files?.[JX + 'meta.json'];
    jx.runs = []; jx.structures = []; jx.defaultId = null;
    document.body.classList.toggle('jx-book', jx.on);
    if (!jx.on) { renderStructure(); return; }

    // per-page text layer: the v2 pages are self-contained — every run carries its
    // exact unicode (spaces included) in data-text; document order is reading order
    const XMLU = { '&quot;': '"', '&amp;': '&', '&lt;': '<', '&gt;': '>' };
    const unesc = s => s.replace(/&(?:quot|amp|lt|gt);/g, m => XMLU[m]);
    const RUN = /<g id="(t\d+)" data-ocd="run"[^>]*? data-text="([^"]*)"/g;
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
        P.markRect(doc, el, 'data-px-hl', 'rgba(51,105,159,.32)');
        if (!first) first = el;
    }
    return first;
};

/* -- Fonts drawer: ONE font at a time + a glyph inspector ------------- */

let fxFonts = [], fxCur = 0, fxSel = -1;

function parseFonts() {
    const raw = P.state.files?.[P.state.opfDir + 'pages/fonts.svg'];
    if (!raw) return [];
    // the shared OCD API owns the fonts.svg grammar — the drawer just reshapes for display
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
        body.innerHTML = '<div class="search-info">No font data — this book has no <code>pages/fonts.svg</code> (generic EPUB?).</div>';
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
// info panel carries the numbers): baseline blue and strong, ascender/descender
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
    for (const pEl of doc.querySelectorAll('svg [data-ocd="paragraph"]')) {
        const runs = [...pEl.querySelectorAll('[data-ocd="run"]')];
        if (!runs.length) continue;
        const role = runs[0].getAttribute('data-role');
        if (role === 'page-header' || role === 'page-footer') continue;
        const lineEls = [...pEl.querySelectorAll(':scope > [data-ocd="line"]')];
        const lines = lineEls.length ? lineEls.map(l => [...l.querySelectorAll('[data-ocd="run"]')]) : [runs];
        const nodes = [];
        lines.forEach((line, li) => {
            line.forEach((el, ri) => {
                let text = (el.getAttribute('data-text') || '').replace(/\u00AD/g, '').replace(/\s+/g, ' ');
                const lastOfLine = ri === line.length - 1, lastLine = li === lines.length - 1;
                if (lastOfLine) {
                    text = text.replace(/\s+$/, '');
                    if (!lastLine && /-$/.test(text)) {
                        const next = lines[li + 1]?.[0]?.getAttribute('data-text') || '';
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

/* -- Structure rail: the structures.json trees ------------------------ */

let activeStruct = null;

/** WHICH structure the reader is looking at: the one picked in the rail, else the container's default,
 *  else the first. The rail and the Analysis overlays both need it and both used to spell the fallback
 *  chain — one accessor, on the side that owns `jx.structures`. */
export const activeStructure = () =>
    jx.structures.find(s => s.id === activeStruct?.id)
    || jx.structures.find(s => s.id === jx.defaultId) || jx.structures[0] || null;

function renderStructure() {
    const host = $.opt('#structure-body'); if (!host) return;
    host.innerHTML = '';
    const has = jx.on && jx.structures.length > 0;
    host.toggleAttribute('hidden', !has);                    // Contents tab: structures when the
    $.opt('#toc-list')?.toggleAttribute('hidden', has);      // book carries them, EPUB TOC otherwise
    if (!has) return;

    activeStruct = activeStructure();

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

export const pageIndexOf = (pageId) => {                            // "p1" → 0 (the writer's page ids)
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
            P.markRect(doc, el, 'data-px-hl', 'rgba(51,105,159,.30)');
            if (!first) first = el;
        }
        if (first) P.centreOn(pi, first);
    });
}

// A struct ref may point at a run inside a paragraph group — either id exists in the page.
const paraOf = () => '';

/* -- Export as… : the container bytes through the engine -------------- */

export function bookBytes() {
    const files = P.state.files; if (!files) return null;
    const out = { mimetype: [files['mimetype'] || enc('application/epub+zip'), { level: 0 }] };
    for (const [p, b] of Object.entries(files)) if (p !== 'mimetype') out[p] = b;
    return zipSync(out);
}

P.bookBytes = bookBytes;                                // the open book as bytes — what every tool posts to the engine

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
    // pending redactions (the Redact tool's member) are applied by the engine on every export —
    // the export is what leaves the machine, so it is where the proof must hold
    try { const rd = P.state.files?.[JX + 'redact.json']; const items = rd ? JSON.parse(dec(rd)) : [];
          if (items.length) opts = { ...opts, redact: JSON.stringify(items) }; } catch { /* no member, no redaction */ }

    // This used to bypass the engine for `ocd` whenever augmentations were present, because the round
    // trip dropped `prism/augment.json` — a member the model did not own. The ENGINE now carries what it
    // does not own (OCDDocument passengers), so the bypass is gone and there is ONE export path.
    //
    // That matters beyond tidiness: the bypass also skipped the redaction pass above, so an augmented
    // document exported to OCD kept its pending redactions UNAPPLIED — the one export that must never
    // be the exception. Measured on the engine side: a container carrying both comes out with the
    // augmentation intact and the redaction applied (the covered title is gone from the read-out).

    // Augmentations live in the sidecar and NOTHING bakes them: every target but `ocd` goes through the
    // engine, which does not know the member and drops it. Measured: a container carrying a reveal zone
    // exported to EPUB comes out with no trace of it. That is a defensible state — an augmentation is a
    // Prism capability, and the deliverable is the document — but losing work in silence is not, so the
    // export says it once and asks. (A real bake — anchors, covers, the animation CSS written into the
    // members before conversion — is a feature, not this line.)
    // OCD carries them now (passengers), so only the OTHER targets lose them — and only those ask.
    const augs = to === 'ocd' ? 0 : Object.values(P.state.aug?.pages || {}).reduce((n, l) => n + l.length, 0);
    if (augs && !confirm(`This document carries ${augs} augmentation${augs > 1 ? 's' : ''} — links, `
                       + `reveal zones, animations, media. They live in Prism's own container and `
                       + `${to.toUpperCase()} cannot carry them: the export will not contain them.\n\n`
                       + `Export to OCD instead to keep them re-editable. Continue anyway?`)) {
        P.toast('Export cancelled — the augmentations are still there.', 'neutral');
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
    $.all('#export-dialog .export-opt[data-to]').forEach(b => b.addEventListener('click', () => {
        $.opt('#export-dialog')?.hide();
        const opts = convOpts();
        if (b.hasAttribute('data-selectable')) opts.selectable = 'true';
        if (b.getAttribute('data-page') === 'cur') opts.page = String(Math.max(0, P.state.current));
        exportAs(b.getAttribute('data-to'), opts);
    }));
}
