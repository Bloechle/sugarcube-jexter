// The heaviest branch of Augment: a dialog with five fields, four keyframe sets, per-element stagger.
// Does it record, and does it bake into the delivered EPUB — as CSS, since no JS may ship?
import { serve, openPrism, drag, bookBytes } from './prism_bench.mjs';
import fs from 'node:fs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism({ dev: true, book: '/dossier.ocd.epub', viewport: { width: 1500, height: 950 } });
const slot = '#ribbon [data-ribbon="augment"] ';
await page.evaluate(() => window.prism.setTool('augment'));
await page.waitForTimeout(900);
await page.evaluate(s => [...document.querySelectorAll(s + '.px-rb-btn')].find(b => b.textContent.trim() === 'Animate')?.click(), slot);
await page.waitForTimeout(400);
const at = (x, y) => page.evaluate(([x, y]) => { const f = document.querySelector('.prism-page[data-index="0"] .prism-frame');
  const p = window.prism.frameToClient(f, x, y); return { x: p.x, y: p.y }; }, [x, y]);
await drag(page, await at(50, 90), await at(420, 260));       // over the heading + first rows
await page.waitForTimeout(900);
const dlg = await page.evaluate(() => ({ open: !!document.querySelector('#anim-dialog')?.open }));
await page.evaluate(() => document.querySelector('#anim-ok')?.click());
await page.waitForTimeout(1800);
const rec = await page.evaluate(() => {
  const aug = window.prism.state.aug?.pages || {};
  const k = Object.keys(aug)[0];
  const d = document.querySelector('.prism-page[data-index="0"] .prism-frame').contentDocument;
  return { entries: k ? aug[k].length : 0, kinds: k ? aug[k].map(e => e.type) : [],
           els: k ? (aug[k][0].els || []).length : 0,
           onPage: d.querySelectorAll('[data-px-anim]').length };
});
const bytes = await bookBytes(page);
fs.writeFileSync(`${process.env.BENCH_FIX || '/tmp/fix'}/anim.ocd.epub`, bytes);
console.log(JSON.stringify({ dlg, rec, errors }, null, 1));
await browser.close(); srv.close(); process.exit(0);
