// Specificity: on documents that hide nothing, Reveal must say nothing. A checker that cries wolf on
// ordinary pages is worse than no checker.
import { serve, openPrism, watchToasts } from './prism_bench.mjs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const rows = [];
for (const book of ['/sample.ocd.epub', '/turned.ocd.epub']) {
  const { browser, page, errors } = await openPrism({ book });
  const toasts = await watchToasts(page);
  await page.evaluate(() => window.prism.setTool('redact'));
  await page.waitForTimeout(700);
  await page.evaluate((s) => [...document.querySelectorAll(s + '.px-rb-btn')].find(b => b.textContent.trim() === 'Reveal').click(),
                      '#ribbon [data-ribbon="redact"] ');
  await page.waitForTimeout(800);
  const m = await page.evaluate(() => {
    let red = 0, runs = 0, marks = 0;
    for (const f of document.querySelectorAll('.prism-frame')) {
      const d = f.contentDocument; if (!d) continue;
      for (const g of d.querySelectorAll('g[data-ocd="run"]')) { runs++; if (getComputedStyle(g).fill === 'rgb(229, 72, 77)') red++; }
      marks += d.querySelectorAll('.rd-rv-mark').length;
    }
    return { runs, red, marks };
  });
  const toast = (await toasts()).map(t => t.text.slice(0, 90)).pop();
  rows.push(`${book.padEnd(22)} runs=${String(m.runs).padStart(3)} flagged=${m.red} markers=${m.marks}  errors=${errors.length}\n   toast: ${toast}`);
  await browser.close();
}
console.log(rows.join('\n')); srv.close(); process.exit(0);
