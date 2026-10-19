// Same as the sweep: the tool is entered AFTER four others have run on the same book.
import { serve, openPrism } from './prism_bench.mjs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page } = await openPrism({ dev: true, book: '/hidden.ocd.epub', viewport: { width: 1500, height: 950 } });
const bad = [];
page.on('requestfailed', r => bad.push(r.url().replace(/^http:\/\/[^/]+/, '').slice(0, 110) + '  ← ' + (r.failure()?.errorText || '')));
for (const t of ['home', 'augment', 'analysis', 'edit', 'redact', 'pages']) {
  await page.evaluate(x => window.prism.setTool(x), t);
  await page.waitForTimeout(500);
  const cmds = await page.evaluate((x) => [...document.querySelectorAll(`#ribbon [data-ribbon="${x}"] .px-rb-btn`)]
    .filter(b => !b.hasAttribute('disabled')).map(b => b.textContent.trim()), t);
  for (const c of cmds) {
    const n = bad.length;
    await page.evaluate(([x, l]) => [...document.querySelectorAll(`#ribbon [data-ribbon="${x}"] .px-rb-btn`)]
      .find(b => b.textContent.trim() === l && !b.hasAttribute('disabled'))?.click(), [t, c]);
    await page.waitForTimeout(350);
    await page.keyboard.press('Escape');
    if (bad.length > n) console.log(`  ${t} / "${c}" →`, bad.slice(n).join(' | '));
  }
}
console.log('  total :', bad.length);
await browser.close(); srv.close(); process.exit(0);
