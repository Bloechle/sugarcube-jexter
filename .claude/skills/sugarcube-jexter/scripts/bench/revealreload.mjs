// Reveal is ON, then a page RELOADS (Apply patches the members and refreshes the frame). Does the view
// survive, or does the button keep saying "on" over a page that has gone back to hiding things?
// 2026-09-14: the verdict now runs BY ITSELF and writes nothing; `Re-check` asks again and `Repair` is
// the one verb that turns findings into work. The old `Audit` did both — so it is `Re-check` + `Repair`
// here, and a check that only wants the verdict presses `Re-check` alone.
import { serve, openPrism, drag } from './prism_bench.mjs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism({ book: '/hidden.ocd.epub' });
const out = {}; const slot = '#ribbon [data-ribbon="redact"] ';
await page.evaluate(() => window.prism.setTool('redact'));
await page.waitForTimeout(700);
const look = () => page.evaluate(() => {
  const d = document.querySelector('.prism-page[data-index="0"] .prism-frame').contentDocument;
  const red = [...d.querySelectorAll('g[data-ocd="run"]')].filter(g => getComputedStyle(g).fill === 'rgb(229, 72, 77)').map(g => g.id);
  const btn = [...document.querySelectorAll('#ribbon [data-ribbon="redact"] .px-rb-btn')].find(b => b.textContent.trim() === 'Reveal');
  return { red, style: !!d.getElementById('rd-rv-style'), buttonActive: btn?.classList.contains('active'),
           runs: d.querySelectorAll('g[data-ocd="run"]').length };
});
const at = (x, y) => page.evaluate(([x, y]) => {
  const f = document.querySelector('.prism-page[data-index="0"] .prism-frame');
  const p = window.prism.frameToClient(f, x, y); return { x: p.x, y: p.y };
}, [x, y]);
const click = (label) => page.evaluate(([s, l]) => [...document.querySelectorAll(s + '.px-rb-btn')].find(b => b.textContent.trim() === l).click(), [slot, label]);

await click('Re-check'); await page.waitForTimeout(8000); await click('Repair'); await page.waitForTimeout(600);
await click('Reveal'); await page.waitForTimeout(600);
out.revealOn = await look();

// a zone over the WHITE line (page y ≈ 700, so svg y ≈ 142), then Apply → the frame reloads
await drag(page, await at(55, 130), await at(360, 152));
await page.waitForTimeout(400);
await click('Apply');
await page.waitForFunction(() => {
  const d = document.querySelector('.prism-page[data-index="0"] .prism-frame')?.contentDocument;
  return d && d.querySelectorAll('g[data-ocd="run"]').length > 5;
}, null, { timeout: 40000 });
await page.waitForTimeout(2000);
out.afterApply = await look();
out.textLeft = await page.evaluate(() => {
  const d = document.querySelector('.prism-page[data-index="0"] .prism-frame').contentDocument;
  const t = [...d.querySelectorAll('[data-text]')].map(n => n.getAttribute('data-text')).join(' ');
  return { white: t.includes('CH93'), underbox: t.includes('756.1234'), invisible: t.includes('F32.1') };
});
console.log(JSON.stringify({ ...out, errors }, null, 1));
await browser.close(); srv.close(); process.exit(0);
