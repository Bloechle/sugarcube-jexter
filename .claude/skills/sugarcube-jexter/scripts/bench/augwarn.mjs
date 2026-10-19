import { serve, openPrism, drag, watchToasts } from './prism_bench.mjs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism({ dev: true, book: '/dossier.ocd.epub', viewport: { width: 1500, height: 950 } });
const toasts = await watchToasts(page);
let asked = null;
page.on('dialog', d => { asked = d.message().slice(0, 150); d.dismiss(); });
const at = (x, y) => page.evaluate(([x, y]) => { const f = document.querySelector('.prism-page[data-index="0"] .prism-frame');
  const p = window.prism.frameToClient(f, x, y); return { x: p.x, y: p.y }; }, [x, y]);
await page.evaluate(() => window.prism.setTool('augment'));
await page.waitForTimeout(800);
await page.evaluate(() => [...document.querySelectorAll('#ribbon [data-ribbon="augment"] .px-rb-btn')].find(b => b.textContent.trim() === 'Reveal')?.click());
await page.waitForTimeout(300);
await drag(page, await at(60, 300), await at(400, 380));
await page.waitForTimeout(1200);
const before = await page.evaluate(() => Object.values(window.prism.state.aug?.pages || {}).reduce((n, l) => n + l.length, 0));
// The export saves through qry's saveFile: headless, the native picker is refused at once and the file
// falls back to a download — nothing to stub.
const clicked = await page.evaluate(() => {
  const b = document.querySelector('#export-dialog .export-opt[data-to="epub"]');
  b.hidden = false; b.removeAttribute('hidden');
  b.click();
  return { hiddenNow: b.hidden };
});
await page.waitForTimeout(3000);
const toast = (await toasts()).map(t => t.text.slice(0, 80)).pop();
console.log(JSON.stringify({ before, clicked, asked, toast, errors }, null, 1));
await browser.close(); srv.close(); process.exit(0);
