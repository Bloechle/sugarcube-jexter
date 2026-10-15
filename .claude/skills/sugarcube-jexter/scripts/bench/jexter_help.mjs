import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath:'/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
const errs = [];
for (const dark of [false, true]) {
  const ctx = await b.newContext({ viewport:{ width:1000, height:707 } }); const page = await ctx.newPage();
  page.on('pageerror', e => errs.push(e.message));
  await page.goto('http://127.0.0.1:7349/'); await page.waitForTimeout(2500);
  if (dark) await page.locator('#btn-theme').click();
  await page.locator('#btn-cfg').click(); await page.waitForTimeout(900);          // the new header icon
  await page.screenshot({ path:`/tmp/jxb/q-${dark?'dark':'light'}-closed.png` });
  await page.locator('.opt-q[data-help="cfg-mode-help"]').click(); await page.waitForTimeout(300);
  await page.screenshot({ path:`/tmp/jxb/q-${dark?'dark':'light'}-mode.png` });
  await page.locator('.opt-q[data-help="h-done"]').click(); await page.waitForTimeout(300);   // opening one closes the other
  const st = await page.evaluate(() => [...document.querySelectorAll('#cfg-drawer .opt-help')].map(h => h.id + ':' + (h.hidden ? 'hidden' : 'SHOWN')));
  console.log(dark ? 'dark' : 'light', st.join(' '));
  await page.evaluate(() => { const bd = document.querySelector('#cfg-drawer').shadowRoot.querySelector('[part=body]'); bd.scrollTop = bd.scrollHeight; });
  await page.waitForTimeout(300); await page.screenshot({ path:`/tmp/jxb/q-${dark?'dark':'light'}-done.png` });
  await page.locator('.opt-q[data-help="h-hot"]').click(); await page.waitForTimeout(300);
  await page.screenshot({ path:`/tmp/jxb/q-${dark?'dark':'light'}-hot.png` });
  const px = await page.evaluate(() => { const sv = document.querySelector('.opt-q[data-help="h-hot"] svg');
    return [getComputedStyle(sv).color, getComputedStyle(sv.closest('.opt-q')).backgroundColor]; });
  console.log('section ? open: icon', px[0], 'on', px[1]);
  await page.locator('.opt-q[data-help="h-hot"]').click(); await page.waitForTimeout(200);
  console.log('toggle back:', await page.evaluate(() => document.getElementById('h-done').hidden));
  await ctx.close();
}
console.log('errors', errs); await b.close();
