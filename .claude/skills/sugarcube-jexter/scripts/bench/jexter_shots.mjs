// shots.mjs <prefix> — main window, Settings drawer, Java API drawer, in light and dark
import { chromium } from 'playwright-core';
const pre = process.argv[2] || 'now';
const b = await chromium.launch({ executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
const errs = [];
for (const dark of [false, true]) {
  const ctx = await b.newContext({ viewport:{ width:1000, height:707 }, colorScheme: dark ? 'dark' : 'light' });
  const page = await ctx.newPage();
  page.on('pageerror', e => errs.push(e.message));
  await page.goto('http://127.0.0.1:7349/'); await page.waitForTimeout(2500);
  if (dark) await page.evaluate(() => { document.querySelector('#btn-theme')?.click(); });
  await page.waitForTimeout(600);
  const t = dark ? 'dark' : 'light';
  await page.screenshot({ path:`/tmp/jxb/${pre}-${t}-main.png` });
  await page.evaluate(() => { const d = document.querySelector('#cfg-drawer'); d.open = true; });
  await page.waitForTimeout(900); await page.screenshot({ path:`/tmp/jxb/${pre}-${t}-settings.png` });
  await page.evaluate(() => { const b = document.querySelector('#cfg-drawer').shadowRoot.querySelector('[part=body]'); b.scrollTop = b.scrollHeight; });
  await page.waitForTimeout(400); await page.screenshot({ path:`/tmp/jxb/${pre}-${t}-settings2.png` });
  await page.evaluate(() => { localStorage.setItem('jexter.cfg', JSON.stringify({ mode:'folder', suffix:'' })); });
  await page.reload(); await page.waitForTimeout(2200);
  if (dark) await page.evaluate(() => { if (document.documentElement.dataset.theme !== 'dark') document.querySelector('#btn-theme')?.click(); });
  await page.evaluate(() => { document.querySelector('#cfg-drawer').open = true; }); await page.waitForTimeout(900);
  await page.screenshot({ path:`/tmp/jxb/${pre}-${t}-settings3.png` });
  await page.evaluate(() => { localStorage.removeItem('jexter.cfg'); document.querySelector('#cfg-drawer').open = false; });
  await page.evaluate(() => { document.querySelector('#cfg-drawer').open = false; document.querySelector('#api-dialog').open = true; });
  await page.waitForTimeout(900); await page.screenshot({ path:`/tmp/jxb/${pre}-${t}-api.png` });
  await page.evaluate(() => { const b = document.querySelector('#api-dialog').shadowRoot.querySelector('[part=body]'); b.scrollTop = b.scrollHeight; });
  await page.waitForTimeout(400); await page.screenshot({ path:`/tmp/jxb/${pre}-${t}-api2.png` });
  await ctx.close();
}
console.log('errors', errs);
await b.close();
