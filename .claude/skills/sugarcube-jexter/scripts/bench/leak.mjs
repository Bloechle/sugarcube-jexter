// The contract: anything a tool draws is data-ui and persist() strips it. Three layers were added this
// round (hover, reveal, gizmo handles) — does an exported container still hold none of them?
// 2026-09-14: the verdict now runs BY ITSELF and writes nothing; `Re-check` asks again and `Repair` is
// the one verb that turns findings into work. The old `Audit` did both — so it is `Re-check` + `Repair`
// here, and a check that only wants the verdict presses `Re-check` alone.
import { serve, openPrism, drag, bookBytes } from './prism_bench.mjs';
import fs from 'node:fs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism({ book: '/hidden.ocd.epub' });
const slot = '#ribbon [data-ribbon="redact"] ';
const click = (l) => page.evaluate(([s, l]) => [...document.querySelectorAll(s + '.px-rb-btn')].find(b => b.textContent.trim() === l).click(), [slot, l]);
const at = (x, y) => page.evaluate(([x, y]) => {
  const f = document.querySelector('.prism-page[data-index="0"] .prism-frame');
  const p = window.prism.frameToClient(f, x, y); return { x: p.x, y: p.y };
}, [x, y]);
await page.evaluate(() => window.prism.setTool('redact'));
await page.waitForTimeout(800);
// dress the page every way this round can: a hover, the reveal view, a selected zone with handles
await click('Re-check'); await page.waitForTimeout(8000); await click('Repair'); await page.waitForTimeout(600);
await click('Reveal'); await page.waitForTimeout(600);
await click('Block'); await page.waitForTimeout(200);
const c = await at(200, 150); await page.mouse.move(c.x, c.y); await page.waitForTimeout(400);
await click('Zone'); await page.waitForTimeout(200);
await drag(page, await at(55, 130), await at(360, 152));
await page.waitForTimeout(500);
const dressed = await page.evaluate(() => {
  const d = document.querySelector('.prism-page[data-index="0"] .prism-frame').contentDocument;
  return { dataUi: d.querySelectorAll('[data-ui]').length, handles: d.querySelectorAll('[data-gz]').length,
           revealStyle: !!d.getElementById('rd-rv-style') };
});
// and now what the container would hold
const bytes = await bookBytes(page);
fs.writeFileSync('/tmp/bench/exported.ocd.epub', bytes);
console.log(JSON.stringify({ dressed, bytes: bytes.length, errors }, null, 1));
await browser.close(); srv.close(); process.exit(0);
