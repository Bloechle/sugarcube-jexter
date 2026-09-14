/* compare.js — "Compare": what changed between two documents.
 *
 * Everyone compares PDFs by PIXELS, because a page is what a PDF hands you. Pixels answer the wrong
 * question: re-flowing a paragraph, changing a font or shifting a margin lights up the whole page, and
 * a single altered figure in a table of numbers lights up eight pixels nobody sees. The people who
 * need this — legal, publishing, standards — are asking about the CONTENT, and the model is the only
 * thing in the pipeline that holds it separately from its ink.
 *
 * So the comparison runs on the engine's own reading-order text projection (`to=md`), for BOTH sides:
 * the open document and the one you point at. Same producer, same order, same heading structure —
 * a diff between them is a diff of the documents, not of two extractors.
 *
 * Word-level, not line-level: a line diff on reflowed text reports every line of a paragraph in which
 * one word moved. The unit a reader argues about is the word.
 *
 * What this does NOT do yet, said plainly rather than implied: it does not highlight the change ON the
 * page. The section it belongs to is named; locating the run inside it is the next step.
 */
import { book } from '/shared/js/book.js';
import * as backend from './backend.js';

const P = window.prism;
const TOOL = 'compare';

const st = { on: false, other: '', a: null, b: null, hunks: [], sel: -1, busy: false, ctx: false };

/* -- getting the two sides ---------------------------------------------------------------------- */

const text = async (bytes) => new TextDecoder().decode((await backend.convert(bytes, 'md')).bytes);

/** The open document is one side; the file you pick is the other. Both go through the SAME projection,
 *  which is the whole point: comparing this engine's output with another tool's extraction would
 *  measure the two extractors, not the two documents. */
async function against(file) {
  const mine = P.bookBytes?.();
  if (!mine) { P.toast('Open a document first.', 'warning'); return; }
  st.busy = true; st.other = file.name; ribbon(); drawer();
  try {
    const them = new Uint8Array(await file.arrayBuffer());
    const [a, b] = await Promise.all([text(mine), text(them)]);
    st.a = a; st.b = b;
    st.hunks = diff(a, b);
    st.sel = -1;
    P.toast(st.hunks.length ? `${st.hunks.length} difference(s).` : 'No textual difference.',
            st.hunks.length ? 'warning' : 'success');
  } catch (e) {
    st.hunks = []; st.a = st.b = null;
    P.toast(`Compare failed: ${reason(e)}`, 'danger');
  } finally { st.busy = false; drawer(); ribbon(); }
}
const reason = (e) => { try { return JSON.parse(e.message).error || e.message; } catch { return e.message || String(e); } };

/* -- the diff ----------------------------------------------------------------------------------- */

// Tokens keep their own whitespace so a rebuilt line reads like the document, and headings keep their
// marker so a change can be named by the section it happened in.
const tokens = (s) => s.split(/(\s+)/).filter(t => t !== '');
const isWord = (t) => /\S/.test(t);

/** Myers-style LCS on words, computed over the common prefix/suffix only — two versions of one document
 *  share nearly everything, and trimming what is identical at both ends turns a table nobody can afford
 *  into one that costs nothing. */
function diff(a, b) {
  const A = tokens(a), B = tokens(b);
  let s = 0, ea = A.length, eb = B.length;
  while (s < ea && s < eb && A[s] === B[s]) s++;
  while (ea > s && eb > s && A[ea - 1] === B[eb - 1]) { ea--; eb--; }
  const x = A.slice(s, ea), y = B.slice(s, eb);
  // A guard, not a limit: beyond this the quadratic table is the wrong algorithm, and saying so beats
  // freezing the tab.
  if (x.length * y.length > 4_000_000) {
    return [{ kind: 'too-big', removed: x.filter(isWord).length, added: y.filter(isWord).length,
              section: sectionAt(a, s), context: '' }];
  }
  const lcs = table(x, y);
  const ops = walk(x, y, lcs);
  return group(ops, a, s, A);
}

function table(x, y) {
  const m = x.length, n = y.length;
  const t = new Uint32Array((m + 1) * (n + 1));
  for (let i = m - 1; i >= 0; i--)
    for (let j = n - 1; j >= 0; j--)
      t[i * (n + 1) + j] = x[i] === y[j] ? t[(i + 1) * (n + 1) + j + 1] + 1
                                         : Math.max(t[(i + 1) * (n + 1) + j], t[i * (n + 1) + j + 1]);
  return t;
}

function walk(x, y, t) {
  const n = y.length, ops = [];
  let i = 0, j = 0;
  while (i < x.length && j < n) {
    if (x[i] === y[j]) { ops.push(['=', x[i], i]); i++; j++; }
    else if (t[(i + 1) * (n + 1) + j] >= t[i * (n + 1) + j + 1]) { ops.push(['-', x[i], i]); i++; }
    else { ops.push(['+', y[j], i]); j++; }
  }
  while (i < x.length) { ops.push(['-', x[i], i]); i++; }
  while (j < n) { ops.push(['+', y[j], i]); j++; }
  return ops;
}

/** Adjacent changes are ONE difference — a replaced phrase is not four deletions and three insertions,
 *  and a reader counting differences counts phrases. Runs of equality shorter than a few words do not
 *  break a hunk apart either. */
function group(ops, a, offset, A) {
  const out = [];
  let cur = null, gap = 0;
  for (const [k, tok, at] of ops) {
    if (k === '=') {
      if (!cur) continue;
      if (isWord(tok)) gap++;
      if (gap > 3) { out.push(cur); cur = null; gap = 0; } else cur.same.push(tok);
      continue;
    }
    if (!cur) { cur = { removed: [], added: [], same: [], at: offset + at }; gap = 0; }
    else if (gap) { gap = 0; }
    (k === '-' ? cur.removed : cur.added).push(tok);
  }
  if (cur) out.push(cur);
  return out.map(h => ({
    kind: h.removed.length && h.added.length ? 'changed' : h.added.length ? 'added' : 'removed',
    removed: h.removed.join('').trim(),
    added: h.added.join('').trim(),
    section: sectionAt(a, h.at),
    context: A.slice(Math.max(0, h.at - 12), h.at).join('').trim().slice(-70),
  })).filter(h => h.removed || h.added);
}

/** The nearest Markdown heading ABOVE the change — the engine's own structure, used as the address a
 *  human can act on: "in 4.2 Liability" says more than "at word 3 184". */
function sectionAt(md, wordIndex) {
  const upto = tokens(md).slice(0, Math.max(0, wordIndex)).join('');
  const heads = upto.match(/^#{1,6} .+$/gm);
  return heads && heads.length ? heads[heads.length - 1].replace(/^#+\s*/, '') : '';
}

/* -- ribbon and pane ---------------------------------------------------------------------------- */

function pick() {
  const input = document.getElementById('cmp-file');
  if (input) { input.value = ''; input.click(); }
}

function ribbon() {
  const none = !st.hunks.length;
  P.ribbon(TOOL, [
    { group: 'Documents', items: [
      { icon: 'git-compare', label: st.busy ? 'Comparing…' : 'Choose file',
        title: 'Pick the other document — PDF or .ocd.epub', disabled: st.busy, on: pick },
    ] },
    { group: 'Difference', items: [
      { icon: 'chevron-up', label: 'Previous', title: 'Previous difference', disabled: none,
        on: () => select(st.sel <= 0 ? st.hunks.length - 1 : st.sel - 1) },
      { icon: 'chevron-down', label: 'Next', title: 'Next difference', disabled: none,
        on: () => select(st.sel >= st.hunks.length - 1 ? 0 : st.sel + 1) },
      { icon: 'align-left', label: 'Context', title: 'Show the words leading up to each change',
        active: st.ctx, disabled: none, on: () => { st.ctx = !st.ctx; drawer(); ribbon(); } },
    ] },
    { group: 'Report', items: [
      { icon: 'clipboard-copy', label: 'Copy', title: 'Copy the differences as text',
        disabled: !st.other, on: copy },
    ] },
  ], help());
}

function help() {
  if (st.busy) return 'Reading both documents through the same projection…';
  if (!st.other) return 'Compare this document with another — by its text and its structure, not by its pixels.';
  if (!st.hunks.length) return `No textual difference with ${st.other}. The ink may differ; the content does not.`;
  const c = count();
  return `${st.hunks.length} difference(s) with ${st.other} — ${c.added} word(s) added, ${c.removed} removed.`;
}

const count = () => st.hunks.reduce((n, h) => ({
  added: n.added + (h.added ? h.added.split(/\s+/).length : 0),
  removed: n.removed + (h.removed ? h.removed.split(/\s+/).length : 0),
}), { added: 0, removed: 0 });

function select(i) {
  st.sel = i >= 0 && i < st.hunks.length ? i : -1;
  drawer();
  const el = document.querySelector(`#cmp-list [data-cmp="${st.sel}"]`);
  el?.scrollIntoView({ block: 'nearest' });
}

function copy() {
  const c = count();
  const head = [`Compare — ${P.state?.name || 'document'} vs ${st.other}`,
                `${new Date().toISOString().slice(0, 19).replace('T', ' ')} · text and structure, not pixels`,
                `${st.hunks.length} difference(s) · +${c.added} word(s) · −${c.removed}`, ''];
  const body = st.hunks.map((h, i) =>
    `${i + 1}. ${h.section ? '[' + h.section + '] ' : ''}${h.kind}\n`
    + (h.removed ? `   − ${h.removed}\n` : '') + (h.added ? `   + ${h.added}\n` : ''));
  navigator.clipboard.writeText([...head, ...body].join('\n'))
    .then(() => P.toast('Differences copied.', 'success'))
    .catch(() => P.toast('Could not reach the clipboard.', 'warning'));
}

function drawer() {
  const info = document.getElementById('cmp-info');
  const list = document.getElementById('cmp-list');
  if (!info || !list) return;
  info.textContent = !book.isOpen?.() ? 'Open a document first.'
                   : st.busy ? 'Comparing…'
                   : !st.other ? 'No other document chosen yet.'
                   : st.hunks.length ? `${st.hunks.length} difference(s) with ${st.other}`
                   : `Identical in text to ${st.other}`;
  let sec = null;
  const rows = [];
  st.hunks.forEach((h, i) => {
    if (h.kind === 'too-big') {
      rows.push(`<li class="cmp-note">Too large to diff word by word (${h.removed} vs ${h.added} words).</li>`);
      return;
    }
    // `cmp-group`, this pane's OWN heading class — declared in prism.css beside the other panes'. It
    // used to emit `au-group`, the Audit pane's, and was styled only for as long as that class existed:
    // a borrowed class is a dependency on another tool nobody states. (Found when Audit was merged into
    // Redact and its class was renamed: this list went bare, and `cmp-group` had been dead all along.)
    if (h.section !== sec) { sec = h.section; rows.push(`<li class="cmp-group">${esc(sec || 'Document')}</li>`); }
    rows.push(`<li><a class="nav-link${i === st.sel ? ' active' : ''}" data-cmp="${i}">`
      + (st.ctx && h.context ? `<span class="cmp-ctx">…${esc(h.context)}</span>` : '')
      + (h.removed ? `<span class="cmp-del">${esc(h.removed)}</span>` : '')
      + (h.added ? `<span class="cmp-add">${esc(h.added)}</span>` : '')
      + `</a></li>`);
  });
  list.innerHTML = rows.join('');
  list.querySelectorAll('[data-cmp]').forEach(a =>
    a.addEventListener('click', () => select(+a.getAttribute('data-cmp'))));
}

const esc = (s) => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

/* -- the tool ----------------------------------------------------------------------------------- */

P.registerTool({
  id: TOOL, label: 'Compare', icon: 'git-compare', drawer: 'Differences',
  title: 'Compare — what changed between two documents',
  // Not finished: HIDDEN, never removed. The module still loads, still registers, still runs, and
  // dev mode (Ctrl/Cmd+Shift+D, or ?dev=1 for a link) shows it with its amber dot. Shipping it is
  // deleting one word.
  experimental: true,
  onEnter() { st.on = true; ribbon(); drawer(); wireInput(); },
  onLeave() { st.on = false; },
});

function wireInput() {
  const input = document.getElementById('cmp-file');
  if (!input || input.__cmp) return;
  input.__cmp = true;
  input.addEventListener('change', () => { const f = input.files?.[0]; if (f) against(f); });
}

// A new document invalidates the comparison it was not part of.
P.on('book',  () => { st.other = ''; st.hunks = []; st.a = st.b = null; st.sel = -1; if (st.on) { ribbon(); drawer(); } });
P.on('close', () => { st.other = ''; st.hunks = []; st.a = st.b = null; st.sel = -1; if (st.on) { ribbon(); drawer(); } });
