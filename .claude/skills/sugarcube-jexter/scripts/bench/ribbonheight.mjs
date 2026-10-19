// ribbonheight.mjs — the ribbon is ONE row: the same height on every tool and at every width.
//
// It used to PRINT the height and assert nothing, so `forms ribbon=84px` sat in its output next to
// seven 72s for as long as anybody ran it (found 2026-09-14). It now fails: the heights must all be
// equal, and when they are not it names the offender AND the tallest thing inside its row, which is
// always what grew it.
//
// The cause that day is worth knowing, because the rule it broke is stated in prism.css and was not
// enforced anywhere: a WIDE control (input[type=search|text]) is 1.9rem where a small one is 1.15rem —
// the icon's height — so it fills the box on its own. Give it a caption as well and the control is
// taller than a command, and the row grows to fit. The chassis now takes a wide control's `label` as
// its PLACEHOLDER and renders no caption.
import { serve, openPrism } from './prism_bench.mjs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const BOOK = process.env.BENCH_BOOK || '/sample.ocd.epub';
const rows = [], bad = [];
for (const w of [1500, 1000, 800]) {
  const { browser, page, errors } = await openPrism({ dev: true, book: BOOK, viewport: { width: w, height: 900 } });
  const tools = await page.evaluate(() => [...document.querySelectorAll('.px-tooltab')].map(b => b.getAttribute('data-tool')));
  const seen = [];
  for (const tool of tools) {
    await page.evaluate((t) => window.prism.setTool(t), tool);
    await page.waitForTimeout(600);
    const m = await page.evaluate((t) => {
      const slot = document.querySelector(`#ribbon [data-ribbon="${t}"]`);
      if (!slot) return null;
      const h = (e) => Math.round(e.getBoundingClientRect().height);
      // what a row is made of, tallest first — the item that set the height
      const tall = [...slot.querySelectorAll('.px-rb-btn, .px-rb-field, .px-rb-row, input, select')]
        .map(e => ({ what: e.tagName.toLowerCase() + '.' + (e.className || '').toString().split(' ')[0], h: h(e) }))
        .sort((a, b) => b.h - a.h).slice(0, 2);
      // a label that wrapped to two lines is the other way a row grows
      const wraps = [...slot.querySelectorAll('.px-rb-btn span, .px-rb-lbl, .px-rb-help')]
        .filter(e => h(e) > 22).map(e => e.className + ':' + h(e));
      return { h: h(document.getElementById('ribbon')), tall, wraps };
    }, tool);
    if (!m) { rows.push(`${w}px ${tool.padEnd(9)} (no ribbon)`); continue; }
    seen.push({ tool, ...m });
    rows.push(`${w}px ${tool.padEnd(9)} ribbon=${String(m.h).padStart(3)}px  ${m.tall.map(x => x.what + '=' + x.h).join(' ')}`
            + (m.wraps.length ? '  WRAPS ' + m.wraps.join(', ') : ''));
  }
  // The row height is whatever most tools agree on; any tool that differs is the defect.
  const tally = {};
  for (const s of seen) tally[s.h] = (tally[s.h] || 0) + 1;
  const norm = +Object.entries(tally).sort((a, b) => b[1] - a[1])[0][0];
  for (const s of seen) if (s.h !== norm)
    bad.push(`${w}px: ${s.tool} ribbon=${s.h} against ${norm} — tallest inside: ${s.tall.map(x => x.what + '=' + x.h).join(' ')}`);
  if (errors.length) bad.push(`${w}px console: ${errors.join(' | ')}`);
  await browser.close();
}
console.log(rows.join('\n'));
console.log(bad.length ? '\nFAIL\n  ' + bad.join('\n  ') : '\nOK — one row height on every tool, at every width');
srv.close(); process.exit(bad.length ? 1 : 0);
