// The Jexter app (the PDF normalizer): drop a PDF, let the engine convert, check the result offered.
import { chromium } from 'playwright-core';
const FIX = process.env.BENCH_FIX || '/tmp/fix';   // make_bench_fixtures.py writes here
const browser = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome', args: ['--no-sandbox'] });
const page = await (await browser.newContext({ viewport: { width: 1400, height: 950 } })).newPage();
const errors = [];
page.on('pageerror', e => errors.push('PAGEERROR ' + e.message));
page.on('console', m => { const t = m.text(); if (m.type() === 'error') errors.push(t.slice(0, 130)); });
page.on('requestfailed', r => errors.push('FAILED ' + r.url().slice(0, 90)));
await page.goto('http://127.0.0.1:7347/', { waitUntil: 'load' });
await page.waitForTimeout(2500);
const shell = await page.evaluate(() => ({ title: document.title,
  buttons: [...document.querySelectorAll('button')].map(b => b.textContent.trim()).filter(Boolean).slice(0, 10),
  options: document.querySelectorAll('input[type="checkbox"], select, .qry-seg').length,
  text: document.body.innerText.replace(/\s+/g, ' ').slice(0, 120) }));
const input = await page.$('input[type="file"]');
await input.setInputFiles(FIX + '/hidden.pdf');
// Shoelace CUSTOM ELEMENTS, not <button>: querySelectorAll('button') finds none of this app's controls.
const clicked = await page.evaluate(() => {
  const all = [...document.querySelectorAll('button')];
  const b = all.find(x => /Normalize all/i.test(x.textContent));
  if (b) { b.click(); return b.tagName; } return 'NOT FOUND: ' + all.map(x => x.textContent.trim()).join(' | ');
});
await page.waitForTimeout(20000);
const after = await page.evaluate(() => ({ text: document.body.innerText.replace(/\s+/g, ' ').slice(0, 200),
  links: [...document.querySelectorAll('a[download], a[href^="blob:"]')].length }));
console.log(JSON.stringify({ shell, clicked, after, errors }, null, 1));
await browser.close(); process.exit(0);
