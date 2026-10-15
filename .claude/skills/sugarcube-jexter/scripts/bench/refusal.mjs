// A drawn zone over a box the document already carries: the engine refuses, correctly. What does the
// READER see — a Java sentence, or the next move?
// 2026-09-14: the verdict now runs BY ITSELF and writes nothing; `Re-check` asks again and `Repair` is
// the one verb that turns findings into work. The old `Audit` did both — so it is `Re-check` + `Repair`
// here, and a check that only wants the verdict presses `Re-check` alone.
import { serve, openPrism, drag } from './prism_bench.mjs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism({ book: '/scan.ocd.epub' });
const slot = '#ribbon [data-ribbon="redact"] ';
// Toasts are transient: watch for them as they appear, or a later look finds an empty document and
// reports "no message" for a message that was shown and gone.
await page.evaluate(() => {
  window.__toasts = [];
  new MutationObserver(ms => { for (const m of ms) for (const n of m.addedNodes)
    if (n.tagName === 'SL-ALERT') window.__toasts.push({ text: n.textContent.trim(), variant: n.getAttribute('variant') });
  }).observe(document.body, { childList: true, subtree: true });
});
await page.evaluate(() => window.prism.setTool('redact'));
await page.waitForTimeout(800);
const at = (x, y) => page.evaluate(([x, y]) => {
  const f = document.querySelector('.prism-page[data-index="0"] .prism-frame');
  const p = window.prism.frameToClient(f, x, y); return { x: p.x, y: p.y };
}, [x, y]);
const click = (l) => page.evaluate(([s, l]) => [...document.querySelectorAll(s + '.px-rb-btn')].find(b => b.textContent.trim() === l).click(), [slot, l]);
// a zone drawn right over the fake redaction box (page y ~583..607 → svg y ~235..259)
await drag(page, await at(88, 236), await at(200, 256));   // only the LEFT HALF of the box
await page.waitForTimeout(400);
const drawn = await page.evaluate(() => document.querySelectorAll('#rd-list li:not(.rd-group)').length);
await click('Apply');
await page.waitForTimeout(9000);
const out = await page.evaluate(() => ({ toasts: window.__toasts, itemsKept: document.querySelectorAll('#rd-list li:not(.rd-group)').length }));
out.drawn = drawn;
// and the path the message names must actually work
await click('Re-check'); await page.waitForTimeout(8000); await click('Repair'); await page.waitForTimeout(600);
await click('Apply');
await page.waitForFunction(() => {
  const d = document.querySelector('.prism-page[data-index="0"] .prism-frame')?.contentDocument;
  return d && d.querySelector('image');
}, null, { timeout: 40000 });
await page.waitForTimeout(2000);
out.thenRepaired = await page.evaluate(() => window.__toasts.slice(-1)[0]);
console.log(JSON.stringify({ ...out, errors }, null, 1));
await browser.close(); srv.close(); process.exit(0);
