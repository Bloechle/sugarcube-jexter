// Augment's Reveal (a tap-to-lift cover), which records a ZONE only — nothing to do with selectEls.
import { serve, openPrism, drag, bookBytes } from './prism_bench.mjs';
import fs from 'node:fs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism({ dev: true, book: '/dossier.ocd.epub', viewport: { width: 1500, height: 950 } });
const at = (x, y) => page.evaluate(([x, y]) => { const f = document.querySelector('.prism-page[data-index="0"] .prism-frame');
  const p = window.prism.frameToClient(f, x, y); return { x: p.x, y: p.y }; }, [x, y]);
await page.evaluate(() => window.prism.setTool('augment'));
await page.waitForTimeout(800);
await page.evaluate(() => [...document.querySelectorAll('#ribbon [data-ribbon="augment"] .px-rb-btn')].find(b => b.textContent.trim() === 'Reveal')?.click());
await page.waitForTimeout(300);
await drag(page, await at(60, 300), await at(400, 380));
await page.waitForTimeout(1500);
const rec = await page.evaluate(() => {
  const aug = window.prism.state.aug?.pages || {}; const k = Object.keys(aug)[0];
  return { entries: k ? aug[k].length : 0, kinds: k ? aug[k].map(e => e.type) : [] };
});
// then let another tool persist the page: does anything of the cover reach the member?
await page.evaluate(() => window.prism.setTool('pages'));
await page.waitForTimeout(800);
await page.evaluate(() => [...document.querySelectorAll('#ribbon [data-ribbon="pages"] .px-rb-btn')].find(b => b.textContent.trim() === 'Right')?.click());
await page.waitForTimeout(2000);
const member = await page.evaluate(() => {
  const files = window.prism.state.files || {};
  const k = Object.keys(files).find(x => x.endsWith('page-001.xhtml'));
  const t = new TextDecoder().decode(files[k]);
  return { reveal: (t.match(/px-reveal/g) || []).length, ui: (t.match(/data-ui/g) || []).length,
           runs: (t.match(/data-ocd="run"/g) || []).length };
});
const bytes = await bookBytes(page);
fs.writeFileSync(`${process.env.BENCH_FIX || '/tmp/fix'}/rev.ocd.epub`, bytes);
console.log(JSON.stringify({ rec, member, errors }, null, 1));
await browser.close(); srv.close(); process.exit(0);
