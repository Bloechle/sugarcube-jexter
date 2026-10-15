// What the reading-order index costs at open. The seam decodes every page anyway; the question is what
// re-sorting adds. `data-order` is emitted ONLY where reading order differs from paint order, so a page
// that does not contain the string needs no parse at all — which is nearly every page.
import { serve, openPrism } from './prism_bench.mjs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const out = {};
for (const book of ['/big.ocd.epub', '/order.ocd.epub']) {
  const { browser, page, errors } = await openPrism({ book, dev: true });
  await page.waitForTimeout(1200);
  out[book] = await page.evaluate(() => {
    const paths = Object.keys(window.prism.state.files).filter(p => /^OEBPS\/pages\/page-\d+\.xhtml$/.test(p)).sort();
    const dec = (b) => new TextDecoder().decode(b);
    const xmls = paths.map(p => dec(window.prism.state.files[p]));
    const stated = xmls.filter(x => x.includes('data-order')).length;
    const t = (fn) => { const t0 = performance.now(); fn(); return +(performance.now() - t0).toFixed(1); };
    const RUN = /<g id="(t\d+)" data-ocd="run"[^>]*? data-text="([^"]*)"/g;
    const regexMs = t(() => { for (const x of xmls) { RUN.lastIndex = 0; while (RUN.exec(x)); } });
    const p = new DOMParser();
    const domMs = t(() => { for (const x of xmls) p.parseFromString(x, 'application/xml'); });
    return { pages: paths.length, pagesStatingAnOrder: stated, regexMs, domIfEveryPageMs: domMs,
             indexedChars: window.prism.state.pages.reduce((n, q) => n + (q.text || '').length, 0) };
  });
  out[book].errors = errors;
  await browser.close();
}
console.log(JSON.stringify(out, null, 1));
srv.close(); process.exit(0);
