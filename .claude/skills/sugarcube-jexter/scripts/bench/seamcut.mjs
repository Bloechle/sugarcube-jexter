// The cut: analysis.js imports jx / engine / pageIndexOf from engine.js. Each of the three
// has to be LIVE across the boundary — `engine` in particular flips after /api/health answers.
import { serve, openPrism } from './prism_bench.mjs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism({ dev: true, book: '/hidden.ocd.epub' });
const out = {};
await page.waitForTimeout(800);
out.seam = await page.evaluate(() => ({ jxBook: document.body.classList.contains('jx-book'),
                                        structures: !!document.querySelector('#structure-body'),
                                        exportBtns: document.querySelectorAll('#export-dialog .export-opt[data-to]').length }));
await page.evaluate(() => window.prism.setTool('analysis')); await page.waitForTimeout(900);
// Bounds overlay: drawn INSIDE the page, from the model the seam loaded
await page.evaluate(() => [...document.querySelectorAll('#ribbon [data-ribbon="analysis"] .px-rb-btn')].find(b => b.textContent.trim() === 'Bounds').click());
await page.waitForTimeout(700);
out.overlays = await page.evaluate(() => {
  const d = document.querySelector('.prism-page[data-index="0"] .prism-frame').contentDocument;
  return { ov: d.querySelectorAll('[data-px-ov]').length };
});
// the node tree comes from the members the seam parsed
out.tree = await page.evaluate(() => document.querySelectorAll('#inspect-drawer [data-tree-row], #analysis-drawer [data-tree-row]').length
                                 || document.querySelectorAll('.jx-tree-row, .tree-row').length);
// the structure rail (seam side) must have the container's trees
out.rail = await page.evaluate(() => document.querySelectorAll('#structure-body *').length);
// a stage overlay needs backend.stages(book.bytes()) — the cross-boundary call
await page.evaluate(() => { const b = [...document.querySelectorAll('#ribbon [data-ribbon="analysis"] .px-rb-btn')].find(x => x.textContent.trim() === 'Clean' || x.textContent.trim() === 'Lines' || x.textContent.trim() === 'Blocks'); if (b) b.click(); });
await page.waitForTimeout(9000);
out.stage = await page.evaluate(() => {
  const d = document.querySelector('.prism-page[data-index="0"] .prism-frame').contentDocument;
  return d.querySelectorAll('[data-px-ov]').length;
});
console.log(JSON.stringify({ ...out, errors }, null, 1));
await browser.close(); srv.close(); process.exit(0);
