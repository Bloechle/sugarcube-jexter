// Unwrapping must keep the CONTENT. Count the runs before animating and after the page is persisted:
// the wrapper goes, the six runs stay, and the text is still readable by the engine.
import { serve, openPrism, drag, bookBytes } from './prism_bench.mjs';
import fs from 'node:fs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism({ dev: true, book: '/dossier.ocd.epub', viewport: { width: 1500, height: 950 } });
const at = (x, y) => page.evaluate(([x, y]) => { const f = document.querySelector('.prism-page[data-index="0"] .prism-frame');
  const p = window.prism.frameToClient(f, x, y); return { x: p.x, y: p.y }; }, [x, y]);
const runsIn = () => page.evaluate(() => {
  const files = window.prism.state.files || {};
  const k = Object.keys(files).find(x => x.endsWith('page-001.xhtml'));
  const t = new TextDecoder().decode(files[k]);
  return { runs: (t.match(/data-ocd="run"/g) || []).length, uses: (t.match(/<use/g) || []).length };
});
const before = await runsIn();
await page.evaluate(() => window.prism.setTool('augment'));
await page.waitForTimeout(800);
await page.evaluate(() => [...document.querySelectorAll('#ribbon [data-ribbon="augment"] .px-rb-btn')].find(b => b.textContent.trim() === 'Animate')?.click());
await page.waitForTimeout(300);
await drag(page, await at(50, 90), await at(420, 260));
await page.waitForTimeout(800);
await page.evaluate(() => document.querySelector('#anim-ok')?.click());
await page.waitForTimeout(1200);
await page.evaluate(() => window.prism.setTool('pages'));
await page.waitForTimeout(800);
await page.evaluate(() => [...document.querySelectorAll('#ribbon [data-ribbon="pages"] .px-rb-btn')].find(b => b.textContent.trim() === 'Right')?.click());
await page.waitForTimeout(2000);
const after = await runsIn();
const bytes = await bookBytes(page);
fs.writeFileSync(`${process.env.BENCH_FIX || '/tmp/fix'}/animkeep.ocd.epub`, bytes);
console.log(JSON.stringify({ before, after, errors }, null, 1));
await browser.close(); srv.close(); process.exit(0);
