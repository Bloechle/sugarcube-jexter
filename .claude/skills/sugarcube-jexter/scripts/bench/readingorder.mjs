// readingorder.mjs — the client must read a page in READING order, not in paint order.
//
// An OCD page is written in PAINT order and states the reading index in `data-order` wherever the two
// differ (FORMAT §B3: "a reader re-sorts by data-order to recover reading order"). Prism IS a reader.
// Anything that walks the DOM — read-aloud above all — speaks a two-column page, a knockout label or
// any page whose paint order was not its reading order in the wrong order, silently.
import { serve, openPrism } from './prism_bench.mjs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism({ book: '/order.ocd.epub', dev: true });
await page.waitForTimeout(900);
const r = await page.evaluate(() => {
  const doc = document.querySelector('.prism-frame').contentDocument;
  const paras = [...doc.querySelectorAll('svg [data-ocd="paragraph"]')];
  const head = (p) => (p.querySelector('[data-ocd="run"]')?.getAttribute('data-text') || '').trim().split(' ')[0];
  const dom = paras.map(head);
  const stated = paras.map(p => +(p.getAttribute('data-order') ?? -1));
  const reading = paras.map((p, i) => ({ i, o: stated[i] })).sort((a, b) => a.o - b.o).map(x => head(paras[x.i]));
  const tts = (window.prism.hooks.ttsNodes?.(doc, 0) || []).map(g => {
    const n = Array.isArray(g) ? g[0] : g;
    return (n?.text || n?.el?.getAttribute?.('data-text') || n?.getAttribute?.('data-text') || '').trim().split(' ')[0];
  });
  // the SEARCH index the engine seam builds for this page, and the Flow overlay's block order
  const search = (window.prism.state.pages[0].text || '').trim().split(/\s+/)
    .filter(w => /^(FIRST|SECOND)$/.test(w));
  return { dom, stated, reading, tts, search };
});
// the Flow overlay draws "the reading order" — check the arrows it lays down, in the page
await page.evaluate(() => window.prism.setTool('analysis'));
await page.waitForTimeout(700);
const flow = await page.evaluate(() => {
  const btn = [...document.querySelectorAll('#ribbon .px-rb-btn')].find(b => b.textContent.trim() === 'Flow');
  btn?.click();
  const doc = document.querySelector('.prism-frame').contentDocument;
  const pts = [...doc.querySelectorAll('[data-px-ov] polyline, [data-px-ov] path, [data-px-ov] circle')].length;
  return { drawn: pts > 0 };
});
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const bad = [];
if (!same(r.tts, r.reading))    bad.push('read-aloud follows the DOM: ' + r.tts.join(' '));
if (!same(r.search, r.reading)) bad.push('the search index follows the DOM: ' + r.search.join(' '));
console.log(JSON.stringify({ ...r, flowOverlayDrawn: flow.drawn,
  ttsFollowsReadingOrder: same(r.tts, r.reading),
  searchIndexFollowsReadingOrder: same(r.search, r.reading), errors }, null, 1));
if (bad.length) console.log('FAIL\n  ' + bad.join('\n  '));
await browser.close(); srv.close(); process.exit(bad.length ? 1 : 0);
