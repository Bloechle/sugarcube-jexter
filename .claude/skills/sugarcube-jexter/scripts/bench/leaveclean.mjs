// Every tool must leave the page as it found it: enter, dress it, go Home, count what is left.
import { serve, openPrism } from './prism_bench.mjs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism({ dev: true, book: '/hidden.ocd.epub', viewport: { width: 1500, height: 950 } });
const rows = [];
const count = () => page.evaluate(() => {
  let ui = 0, styles = 0, cls = new Set();
  for (const f of document.querySelectorAll('.prism-frame')) {
    const d = f.contentDocument; if (!d) continue;
    ui += d.querySelectorAll('[data-ui]').length;
    styles += d.querySelectorAll('head style').length;
    for (const e of d.querySelectorAll('[class]')) for (const c of e.classList)
      if (/^(rd-|pg-|trc-|px-aug|jx-)/.test(c)) cls.add(c);
    for (const s of d.querySelector('svg')?.classList || []) cls.add('svg.' + s);
  }
  return { ui, styles, marked: [...cls] };
});
const base = await count();
for (const t of ['augment', 'analysis', 'edit', 'redact', 'pages']) {
  await page.evaluate(x => window.prism.setTool(x), t); await page.waitForTimeout(700);
  // exercise it a little: click its first two enabled commands
  await page.evaluate((x) => [...document.querySelectorAll(`#ribbon [data-ribbon="${x}"] .px-rb-btn`)]
    .filter(b => !b.hasAttribute('disabled')).slice(0, 2).forEach(b => b.click()), t);
  await page.waitForTimeout(600);
  const during = await count();
  await page.evaluate(() => window.prism.setTool('home')); await page.waitForTimeout(700);
  const after = await count();
  rows.push(`${t.padEnd(9)} during: ui=${during.ui} styles=${during.styles}  →  after Home: ui=${after.ui} styles=${after.styles} ${after.marked.length ? 'LEFT ' + after.marked.join(',') : ''}`);
}
console.log(`baseline ui=${base.ui} styles=${base.styles}\n` + rows.join('\n') + `\nconsole errors: ${errors.length}`);
await browser.close(); srv.close(); process.exit(0);
