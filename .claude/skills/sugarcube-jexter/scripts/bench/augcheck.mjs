// Augment has never been driven past a click this session. Its model is a SIDECAR (prism/augment.json)
// baked at export — so the test is: draw one, does it persist, and does it survive an export?
import { serve, openPrism, drag } from './prism_bench.mjs';
import fs from 'node:fs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism({ dev: true, book: '/dossier.ocd.epub', viewport: { width: 1500, height: 950 } });
const slot = '#ribbon [data-ribbon="augment"] ';
await page.evaluate(() => window.prism.setTool('augment'));
await page.waitForTimeout(900);
const out = {};
out.commands = await page.evaluate(s => [...document.querySelectorAll(s + '.px-rb-btn')].map(b => b.textContent.trim()), slot);
// pick "Link" then drag a rectangle on the page
await page.evaluate(s => [...document.querySelectorAll(s + '.px-rb-btn')].find(b => b.textContent.trim() === 'Link')?.click(), slot);
await page.waitForTimeout(400);
const at = (x, y) => page.evaluate(([x, y]) => { const f = document.querySelector('.prism-page[data-index="0"] .prism-frame');
  const p = window.prism.frameToClient(f, x, y); return { x: p.x, y: p.y }; }, [x, y]);
await drag(page, await at(80, 300), await at(320, 360));
await page.waitForTimeout(900);
// The prompt is an sl-dialog IN the page, not a native one: a `dialog` handler never sees it, and the
// interaction stays half-finished — which reads exactly like a tool that records nothing.
out.dialog = await page.evaluate(() => {
  const d = document.querySelector('#ask-dialog');
  return { open: !!d?.open, label: d?.getAttribute('label') };
});
await page.evaluate(() => {
  const i = document.querySelector('#ask-input'); i.value = 'https://example.ch';
  i.dispatchEvent(new Event('sl-input', { bubbles: true }));
  document.querySelector('#ask-ok').click();
});
await page.waitForTimeout(2000);
out.afterDraw = await page.evaluate(() => {
  const files = window.prism.state.files || {};
  const key = Object.keys(files).find(k => k.endsWith('augment.json'));
  const d = document.querySelector('.prism-page[data-index="0"] .prism-frame').contentDocument;
  return { sidecar: key || null,
           sidecarText: key ? new TextDecoder().decode(files[key]).slice(0, 160) : null,
           aug: JSON.stringify(window.prism.state.aug || {}).slice(0, 160),
           onPage: d.querySelectorAll('[data-aug], .aug, [data-ui]').length };
});
const b64 = await page.evaluate(() => { const u = new Uint8Array(window.prism.bookBytes()); let s=''; const C=8192;
  for (let i=0;i<u.length;i+=C) s += String.fromCharCode(...u.subarray(i,i+C)); return btoa(s); });
fs.writeFileSync('/tmp/fix/aug.ocd.epub', Buffer.from(b64, 'base64'));
console.log(JSON.stringify({ ...out, errors }, null, 1));
await browser.close(); srv.close(); process.exit(0);
