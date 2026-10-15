// Jexter settings + hot folder, driven for real: the Java server, the real page, and OPFS directories
// standing in for the user's folders (real FileSystemDirectoryHandles: cloneable into IndexedDB,
// with getFileHandle / createWritable / removeEntry — everything the page uses).
import { chromium } from 'playwright-core';
import fs from 'node:fs';
const URL_ = process.env.URL || 'http://127.0.0.1:7349/';
const pdfA = fs.readFileSync('/tmp/syn/text_base14.pdf').toString('base64');
const pdfB = fs.readFileSync('/tmp/syn/color_rgb.pdf').toString('base64');
const b = await chromium.launch({ executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
const ctx = await b.newContext();
await ctx.addInitScript(() => {
  window.__picks = 0;
  window.showDirectoryPicker = async () => {       // hands out OPFS folders in the order the test queued them
    const root = await navigator.storage.getDirectory();
    const name = (window.__queue || []).shift() || 'x';
    window.__picks++;
    return root.getDirectoryHandle(name, { create:true });
  };
});
const page = await ctx.newPage();
const errs = []; page.on('pageerror', e => errs.push(e.message)); page.on('console', m => { if (m.type()==='error') errs.push(m.text()); });
await page.goto(URL_); await page.waitForTimeout(2500);

// 1 · Settings › Output folder › Choose, twice
await page.evaluate(() => localStorage.setItem('jexter.cfg', JSON.stringify({ mode:'folder' })));
await page.reload(); await page.waitForTimeout(2500);
await page.evaluate(() => { window.__queue = ['out1', 'out2']; document.querySelector('#cfg-drawer').open = true; });
await page.waitForTimeout(800);
for (let i = 0; i < 2; i++) { await page.locator('#cfg-pick').click(); await page.waitForTimeout(600); }
const r1 = await page.evaluate(() => ({ picks: window.__picks, label: document.querySelector('#cfg-outdir').textContent }));
console.log('choose twice  ->', JSON.stringify(r1));

// 2 · restart: the chosen folder is remembered
await page.reload(); await page.waitForTimeout(2500);
const r2 = await page.evaluate(() => document.querySelector('#cfg-outdir').textContent);
console.log('after reload  -> output folder label:', r2);

// 3 · hot folder: first delivery, same-name re-delivery, Done
await page.evaluate(() => { const c = JSON.parse(localStorage.getItem('jexter.cfg') || '{}');
  Object.assign(c, { mode:'source', interval:1, moveDone:true, doneName:'Done' }); localStorage.setItem('jexter.cfg', JSON.stringify(c)); });
await page.reload(); await page.waitForTimeout(2500);
const put = async (b64) => page.evaluate(async (b64) => {
  const root = await navigator.storage.getDirectory(), inbox = await root.getDirectoryHandle('inbox', { create:true });
  const w = await (await inbox.getFileHandle('a.pdf', { create:true })).createWritable();
  await w.write(Uint8Array.from(atob(b64), c => c.charCodeAt(0))); await w.close();
}, b64);
const list = () => page.evaluate(async () => {
  const out = [], walk = async (d, p) => { for await (const e of d.values()) {
    if (e.kind === 'file') { const f = await e.getFile(); out.push(p + e.name + ' ' + f.size); } else await walk(e, p + e.name + '/'); } };
  await walk(await (await navigator.storage.getDirectory()).getDirectoryHandle('inbox', { create:true }), 'inbox/'); return out.sort();
});
await put(pdfA);
await page.evaluate(() => { window.__queue = ['inbox']; });
await page.locator('#hot').click();
await page.waitForTimeout(9000);
const s1 = await list(); console.log('first delivery ->', JSON.stringify(s1));
await put(pdfB);                                                        // the customer delivers a.pdf again
await page.waitForTimeout(9000);
const s2 = await list(); console.log('re-delivery    ->', JSON.stringify(s2));
console.log('hot stat       ->', await page.evaluate(() => document.querySelector('#hot-stat').textContent));
console.log('errors         ->', errs.length ? errs : 'none');
await b.close();
