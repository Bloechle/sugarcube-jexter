// The functional sweep: every tool, every ribbon command, twice — with no document and with one.
// A command that throws, or that leaves the app without its tabs, is the thing we are looking for.
import { serve, openPrism } from './prism_bench.mjs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const report = [];
for (const withBook of [false, true]) {
  const { browser, page, errors } = await openPrism({ dev: true, book: withBook ? '/hidden.ocd.epub' : '/hidden.ocd.epub', viewport: { width: 1500, height: 950 } });
  if (!withBook) { await page.evaluate(() => window.prism.closeBook()); await page.waitForTimeout(600); }
  const tools = await page.evaluate(() => [...document.querySelectorAll('.px-tooltab')].map(b => b.getAttribute('data-tool')));
  for (const t of tools) {
    await page.evaluate(x => window.prism.setTool(x), t); await page.waitForTimeout(500);
    const cmds = await page.evaluate((x) => [...document.querySelectorAll(`#ribbon [data-ribbon="${x}"] .px-rb-btn`)]
      .map(b => ({ label: b.textContent.trim(), disabled: b.hasAttribute('disabled') })), t);
    const before = errors.length;
    for (const c of cmds) {
      if (c.disabled) continue;
      await page.evaluate(([x, l]) => {
        const b = [...document.querySelectorAll(`#ribbon [data-ribbon="${x}"] .px-rb-btn`)].find(e => e.textContent.trim() === l);
        if (b && !b.hasAttribute('disabled')) b.click();
      }, [t, c.label]);
      await page.waitForTimeout(350);
      await page.keyboard.press('Escape');
    }
    const alive = await page.evaluate(() => ({ tabs: document.querySelectorAll('.px-tooltab').length,
                                               ribbon: !!document.querySelector('#ribbon'), body: document.body.className.length > 0 }));
    report.push(`${withBook ? 'book ' : 'empty'} ${t.padEnd(9)} ${String(cmds.length).padStart(2)} cmds (${cmds.filter(c => c.disabled).length} disabled) · new errors ${errors.length - before} · tabs ${alive.tabs}`);
  }
  report.push(`${withBook ? 'book ' : 'empty'} —— console errors total: ${errors.length}` + (errors.length ? '\n    ' + errors.slice(0, 6).join('\n    ') : ''));
  await browser.close();
}
console.log(report.join('\n')); srv.close(); process.exit(0);
