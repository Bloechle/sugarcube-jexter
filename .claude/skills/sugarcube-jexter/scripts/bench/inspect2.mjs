// Drive the Inspector on a real PDF: the four rasterizers, the COS tree, the fonts, the text.
import { chromium } from 'playwright-core';
const FIX = process.env.BENCH_FIX || '/tmp/fix';   // make_bench_fixtures.py writes here
const browser = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome', args: ['--no-sandbox'] });
const page = await (await browser.newContext({ viewport: { width: 1500, height: 980 } })).newPage();
const errors = [];
page.on('pageerror', e => errors.push('PAGEERROR ' + e.message));
page.on('console', m => { const t = m.text(); if (m.type() === 'error' && !t.includes('welcome.pdf')) errors.push(t.slice(0, 130)); });
page.on('requestfailed', r => { const u = r.url(); if (!u.includes('welcome.pdf')) errors.push('FAILED ' + u.slice(0, 90)); });
await page.goto('http://127.0.0.1:7346/', { waitUntil: 'load' });
await page.waitForTimeout(2500);
const input = await page.$('input[type="file"]');
await input.setInputFiles(FIX + '/hidden.pdf');
await page.waitForTimeout(9000);
const out = {};
out.afterOpen = await page.evaluate(() => ({
  status: document.querySelector('#status, .px-status')?.textContent?.trim().slice(0, 60),
  pages: document.querySelectorAll('#page-list li, .page-thumb, [data-page]').length,
  rasterButtons: [...document.querySelectorAll('button')].map(b => b.textContent.trim()).filter(t => /PDFBox|Direct|OCD|Compare/.test(t)),
}));
for (const tab of ['Structure', 'Fonts', 'Resources', 'Viewer']) {
  await page.evaluate((t) => [...document.querySelectorAll('.tab-btn, [data-tab]')].find(b => b.textContent.trim() === t)?.click(), tab);
  await page.waitForTimeout(1800);
  out[tab] = await page.evaluate(() => document.body.innerText.replace(/\s+/g, ' ').slice(0, 110));
}
// the four rasterizers, one after the other
for (const r of ['PDFBox', 'Direct', 'OCD']) {
  await page.evaluate((t) => [...document.querySelectorAll('button')].find(b => b.textContent.trim() === t)?.click(), r);
  await page.waitForTimeout(4000);
}
out.rasterInk = await page.evaluate(() => {
  const c = document.querySelector('canvas, img#page-img, .stage img');
  return c ? { tag: c.tagName, w: c.width || c.naturalWidth, h: c.height || c.naturalHeight } : null;
});
console.log(JSON.stringify({ ...out, errors }, null, 1));
await browser.close(); process.exit(0);
