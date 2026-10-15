import { serve, openPrism } from './prism_bench.mjs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism({ viewport: { width: 1500, height: 950 } });
const slot = '#ribbon [data-ribbon="redact"] ';
await page.evaluate(() => window.prism.setTool('redact'));
await page.waitForTimeout(900);
const state = () => page.evaluate((s) => {
  const b = [...document.querySelectorAll(s + '.px-rb-btn')].find(x => x.textContent.trim() === 'Metadata');
  const icon = b?.querySelector('svg');
  const r = b?.getBoundingClientRect();
  return { present: !!b, active: b?.classList.contains('active'), iconPaths: icon?.querySelectorAll('path, circle, line, rect').length || 0,
           box: r && { w: Math.round(r.width), h: Math.round(r.height), top: Math.round(r.top) },
           switches: document.querySelectorAll(s + 'sl-switch').length };
}, slot);
const click = (l) => page.evaluate(([s, l]) => [...document.querySelectorAll(s + '.px-rb-btn')].find(b => b.textContent.trim() === l).click(), [slot, l]);
const out = { off: await state() };
await click('Metadata'); await page.waitForTimeout(300);
out.on = await state();
// it must survive a re-render caused by something else entirely
await click('Block'); await page.waitForTimeout(300);
out.afterRerender = await state();
await click('Metadata'); await page.waitForTimeout(300);
out.offAgain = await state();
out.ribbonH = await page.evaluate(() => Math.round(document.getElementById('ribbon').getBoundingClientRect().height));
await page.evaluate((s) => [...document.querySelectorAll(s + '.px-rb-btn')].find(x => x.textContent.trim() === 'Metadata').click(), slot);
await page.waitForTimeout(300);
await page.screenshot({ path: '/tmp/bench/meta.png', clip: { x: 330, y: 38, width: 780, height: 82 } });
console.log(JSON.stringify({ ...out, errors }, null, 1));
await browser.close(); srv.close(); process.exit(0);
