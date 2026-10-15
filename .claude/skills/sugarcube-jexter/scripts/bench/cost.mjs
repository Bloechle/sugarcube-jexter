// What the backdrop walk costs: the page's paths are scanned once, then each run is a containment test.
import { serve, openPrism } from './prism_bench.mjs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const rows = [];
for (const book of ['/sample.ocd.epub', '/banner.ocd.epub', '/scan.ocd.epub', '/heavy.ocd.epub']) {
  const { browser, page, errors } = await openPrism({ book });
  await page.evaluate(() => window.prism.setTool('redact'));
  await page.waitForTimeout(800);
  const m = await page.evaluate(() => {
    const d = document.querySelector('.prism-page[data-index="0"] .prism-frame').contentDocument;
    const runs = d.querySelectorAll('g[data-ocd="run"]').length, paths = d.querySelectorAll('path').length;
    const btn = [...document.querySelectorAll('#ribbon [data-ribbon="redact"] .px-rb-btn')].find(b => b.textContent.trim() === 'Reveal');
    const t0 = performance.now(); btn.click(); const on = performance.now() - t0;
    const t1 = performance.now(); btn.click(); const off = performance.now() - t1;
    return { runs, paths, on: Math.round(on), off: Math.round(off) };
  });
  rows.push(`${book.padEnd(20)} runs=${String(m.runs).padStart(3)} paths=${String(m.paths).padStart(3)}  reveal on ${m.on} ms · off ${m.off} ms · errors ${errors.length}`);
  await browser.close();
}
console.log(rows.join('\n')); srv.close(); process.exit(0);
