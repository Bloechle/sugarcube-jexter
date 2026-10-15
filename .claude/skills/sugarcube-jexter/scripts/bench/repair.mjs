// The case the tool exists for: a badly redacted SCAN. Repair turns the box into a repair zone; Apply
// removes what it hid — the PIXELS in the image bytes — and the audit of the result must come back clean.
// 2026-09-14: the verdict now runs BY ITSELF and writes nothing; `Re-check` asks again and `Repair` is
// the one verb that turns findings into work. The old `Audit` did both — so it is `Re-check` + `Repair`
// here, and a check that only wants the verdict presses `Re-check` alone.
import { serve, openPrism } from './prism_bench.mjs';
import fs from 'node:fs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism({ book: '/scan.ocd.epub' });
const slot = '#ribbon [data-ribbon="redact"] ';
const click = (l) => page.evaluate(([s, l]) => [...document.querySelectorAll(s + '.px-rb-btn')].find(b => b.textContent.trim() === l).click(), [slot, l]);
const bytes = () => page.evaluate(() => {
  const p = Object.keys(window.prism.state.files).find(k => k.includes('/images/img'));
  let s = ''; for (const x of window.prism.state.files[p]) s += String.fromCharCode(x);
  return { path: p, b64: btoa(s) };
});
await page.evaluate(() => window.prism.setTool('redact'));
await page.waitForTimeout(800);
const b0 = await bytes();
await click('Re-check'); await page.waitForTimeout(8000); await click('Repair'); await page.waitForTimeout(600);
const found = await page.evaluate(() => [...document.querySelectorAll('#rd-list li:not(.rd-group)')].map(l => l.textContent.trim().slice(0, 30)));
await click('Apply');
await page.waitForFunction(() => {
  const d = document.querySelector('.prism-page[data-index="0"] .prism-frame')?.contentDocument;
  return d && d.querySelector('image');
}, null, { timeout: 40000 });
await page.waitForTimeout(2500);
const toast = await page.evaluate(() => [...document.querySelectorAll('sl-alert')].map(a => a.textContent.trim().slice(0, 160)).pop());
const b1 = await bytes();
// audit again: the repair must PROVE itself
await click('Re-check'); await page.waitForTimeout(8000);   // the verdict only: what is LEFT
const after = await page.evaluate(() => [...document.querySelectorAll('#rd-list li:not(.rd-group)')].map(l => l.textContent.trim().slice(0, 30)));
// and what the reader shows
const box = await page.evaluate(() => {
  const f = document.querySelector('.prism-page[data-index="0"] .prism-frame');
  const im = f.contentDocument.querySelector('image');
  const r = im.getBoundingClientRect(), fr = f.getBoundingClientRect();
  return { x: Math.round(fr.x + r.x), y: Math.round(fr.y + r.y), width: Math.round(r.width), height: Math.round(r.height) };
});
await page.screenshot({ path: '/tmp/bench/scan-onscreen.png', clip: box });
fs.writeFileSync('/tmp/bench/img-before.png', Buffer.from(b0.b64, 'base64'));
fs.writeFileSync('/tmp/bench/img-after.png', Buffer.from(b1.b64, 'base64'));
console.log(JSON.stringify({ found, toast, auditAfter: after, samePath: b0.path === b1.path, sameBytes: b0.b64 === b1.b64, errors }, null, 1));
await browser.close(); srv.close(); process.exit(0);
