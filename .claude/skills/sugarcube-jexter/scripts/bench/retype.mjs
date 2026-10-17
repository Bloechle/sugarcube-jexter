// Pick the font from the document's own list, write, then select an EXISTING run and retype it —
// and check one Ctrl+Z puts the old one back.
import { serve, openPrism, watchToasts } from './prism_bench.mjs';
import fs from 'node:fs';
const FIX = process.env.BENCH_FIX || '/tmp/fix';   // make_bench_fixtures.py writes here
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism({ dev: true, book: '/withfont.ocd.epub', viewport: { width: 1500, height: 980 } });
const toasts = await watchToasts(page);
const slot = '#ribbon [data-ribbon="edit"] ';
const out = {};
await page.evaluate(() => window.prism.setTool('edit'));
await page.waitForTimeout(900);
out.fontOptions = await page.evaluate((s) => [...document.querySelectorAll(s + 'select option')].map(o => o.value), slot);
// choose DejaVu from the list, type, place
await page.evaluate((s) => {
  const sel = document.querySelector(s + 'select'); sel.value = 'DejaVu_Serif'; sel.dispatchEvent(new Event('change', { bubbles: true }));
  const t = document.querySelector(s + 'input[type="text"]'); t.value = 'Premier jet'; t.dispatchEvent(new Event('input', { bubbles: true }));
}, slot);
await page.evaluate((s) => [...document.querySelectorAll(s + '.px-rb-btn')].find(b => b.textContent.trim() === 'Place').click(), slot);
await page.waitForTimeout(300);
const at = await page.evaluate(() => { const f = document.querySelector('.prism-page[data-index="0"] .prism-frame');
  const p = window.prism.frameToClient(f, 70, 520); return { x: p.x, y: p.y }; });
await page.mouse.click(at.x, at.y);
await page.waitForTimeout(1200);
const read = () => page.evaluate((s) => {
  const d = document.querySelector('.prism-page[data-index="0"] .prism-frame').contentDocument;
  const runs = [...d.querySelectorAll('[data-ocd="run"]')].map(g => g.getAttribute('data-text'));
  return { last: runs[runs.length - 1], count: runs.length,
           field: document.querySelector(s + 'input[type="text"]')?.value,
           retype: !![...document.querySelectorAll(s + '.px-rb-btn')].find(b => b.textContent.trim() === 'Retype' && !b.hasAttribute('disabled')) };
}, slot);
out.placed = await read();
// the run is selected: change the text and Retype
await page.evaluate((s) => { const t = document.querySelector(s + 'input[type="text"]'); t.value = 'Second jet, plus long'; t.dispatchEvent(new Event('input', { bubbles: true })); }, slot);
await page.evaluate((s) => [...document.querySelectorAll(s + '.px-rb-btn')].find(b => b.textContent.trim() === 'Retype').click(), slot);
await page.waitForTimeout(1200);
out.retyped = await read();
out.warned = (await toasts()).map(t => t.text.slice(0, 150)).pop();
await page.keyboard.press('Control+z');
await page.waitForTimeout(900);
out.undone = await read();
const b64 = await page.evaluate(() => { const u = new Uint8Array(window.prism.bookBytes()); let s=''; const C=8192;
  for (let i=0;i<u.length;i+=C) s += String.fromCharCode(...u.subarray(i,i+C)); return btoa(s); });
fs.writeFileSync(FIX + '/retype.ocd.epub', Buffer.from(b64, 'base64'));
console.log(JSON.stringify({ ...out, errors }, null, 1));
await browser.close(); srv.close(); process.exit(0);
