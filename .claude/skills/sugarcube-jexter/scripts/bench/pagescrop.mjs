import { serve, openPrism, drag } from './prism_bench.mjs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism({ dev: false });
const out = {};
await page.evaluate(() => window.prism.setTool('pages'));
await page.waitForTimeout(1500);
await page.evaluate(() => [...document.querySelectorAll('#ribbon [data-ribbon="pages"] .px-rb-btn')].find(b => b.textContent.trim() === 'Page').click());
await page.waitForTimeout(700);
const at = (x, y) => page.evaluate(([x, y]) => { const f = document.querySelector('.prism-page[data-index="0"] .prism-frame'); const p = window.prism.frameToClient(f, x, y); return { x: p.x, y: p.y }; }, [x, y]);
const st = () => page.evaluate(() => {
  const d = document.querySelector('.prism-page[data-index="0"] .prism-frame').contentDocument;
  const r = d.getElementById('pg-crop');
  return { handles: d.querySelectorAll('[data-gz]').length, box: r && ['x','y','width','height'].map(k => Math.round(+r.getAttribute(k) * 10) / 10) };
});
// draw a crop rectangle well inside the page, then pull its SE handle
await drag(page, await at(80, 80), await at(400, 500));
await page.waitForTimeout(400);
out.drawn = await st();
const b = out.drawn.box;
await drag(page, await at(b[0] + b[2], b[1] + b[3]), await at(b[0] + b[2] - 100, b[1] + b[3] - 80));
await page.waitForTimeout(400);
out.resized = await st();
// and the drawer numbers
await page.evaluate(() => { const f = document.getElementById('pg-w'); f.value = '250'; f.dispatchEvent(new Event('change', { bubbles: true })); });
await page.waitForTimeout(300);
out.typed = await st();
console.log(JSON.stringify({ ...out, errors }, null, 1));
await browser.close(); srv.close(); process.exit(0);
