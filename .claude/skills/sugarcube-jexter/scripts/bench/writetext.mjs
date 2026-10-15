// The affordance, driven like a reader would: type the text, add a font file, click Place, click the page.
import { serve, openPrism } from './prism_bench.mjs';
import fs from 'node:fs';
const FIX = process.env.BENCH_FIX || '/tmp/fix';   // make_bench_fixtures.py writes here
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism({ dev: true, book: '/dossier.ocd.epub', viewport: { width: 1500, height: 980 } });
const slot = '#ribbon [data-ribbon="edit"] ';
await page.evaluate(() => window.prism.setTool('edit'));
await page.waitForTimeout(800);
const out = {};
out.row = await page.evaluate((s) => [...document.querySelectorAll(s + '.px-rb-group')].map(g => ({
  name: g.querySelector('.px-rb-lbl')?.textContent,
  items: [...g.querySelectorAll('.px-rb-btn, .px-rb-field')].map(e => (e.querySelector('span:not(.px-rb-unit)')?.textContent || e.querySelector('input')?.type || '').trim()) })), slot);
// type the text and the size
await page.evaluate((s) => {
  const t = document.querySelector(s + 'input[type="text"]'); t.value = 'Écrit dans Prism'; t.dispatchEvent(new Event('input', { bubbles: true }));
  const n = document.querySelector(s + 'input[type="number"]'); n.value = '28'; n.dispatchEvent(new Event('change', { bubbles: true }));
}, slot);
// the font file, through the input the command opens
const [chooser] = await Promise.all([
  page.waitForEvent('filechooser'),
  page.evaluate((s) => [...document.querySelectorAll(s + '.px-rb-btn')].find(b => b.textContent.trim() === 'Font…').click(), slot),
]);
await chooser.setFiles('/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf');
await page.waitForTimeout(6000);
out.afterFont = await page.evaluate(() => [...document.querySelectorAll('sl-alert')].map(a => a.textContent.trim().slice(0, 90)).pop());
// Place, then click on the page
await page.evaluate((s) => [...document.querySelectorAll(s + '.px-rb-btn')].find(b => b.textContent.trim() === 'Place').click(), slot);
await page.waitForTimeout(400);
const at = await page.evaluate(() => { const f = document.querySelector('.prism-page[data-index="0"] .prism-frame');
  const p = window.prism.frameToClient(f, 70, 520); return { x: p.x, y: p.y }; });
await page.mouse.click(at.x, at.y);
await page.waitForTimeout(1500);
out.written = await page.evaluate(() => {
  const d = document.querySelector('.prism-page[data-index="0"] .prism-frame').contentDocument;
  const t = [...d.querySelectorAll('[data-ocd="run"]')].find(g => (g.getAttribute('data-text') || '').startsWith('Écrit'));
  return t ? { u: t.getAttribute('data-text'), fs: t.getAttribute('data-size'), f: t.getAttribute('data-font'),
               uses: t.querySelectorAll('use').length, handles: d.querySelectorAll('[data-gz]').length } : null;
});
const b64 = await page.evaluate(() => { const u = new Uint8Array(window.prism.bookBytes()); let s=''; const C=8192;
  for (let i=0;i<u.length;i+=C) s += String.fromCharCode(...u.subarray(i,i+C)); return btoa(s); });
fs.writeFileSync(FIX + '/ui.ocd.epub', Buffer.from(b64, 'base64'));
console.log(JSON.stringify({ ...out, errors }, null, 1));
await browser.close(); srv.close(); process.exit(0);
