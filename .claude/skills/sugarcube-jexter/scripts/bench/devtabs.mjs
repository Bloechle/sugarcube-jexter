// devtabs.mjs — an unfinished tool is HIDDEN, never removed: the module loads, registers and runs, and
// only the tab is filtered. Dev mode (Ctrl/Cmd+Shift+D · ?dev=1) shows them, each with its amber dot,
// and asking for a hidden tool outside dev must land on home rather than strand the reader.
import { serve, openPrism } from './prism_bench.mjs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const BOOK = process.env.BENCH_BOOK || '/sample.ocd.epub';
const look = async (dev) => {
  const { browser, page, errors } = await openPrism({ dev, book: BOOK });
  await page.evaluate(() => { try { if (!new URLSearchParams(location.search).has('dev')) localStorage.removeItem('prism.dev'); } catch {} });
  if (!dev) { await page.reload(); await page.waitForFunction(() => document.body.classList.contains('has-book'), null, { timeout: 30000 }); await page.waitForTimeout(800); }
  const r = await page.evaluate(() => ({
    tabs: [...document.querySelectorAll('.px-tooltab')].map(b => b.dataset.tool),
    wip: [...document.querySelectorAll('.px-tooltab.px-wip')].map(b => b.dataset.tool),
    registered: !!document.querySelector('#ribbon [data-ribbon="compare"]'),
  }));
  // asking for a hidden tool outside dev mode must not strand anyone
  const landed = await page.evaluate(() => { window.prism.setTool('compare'); return document.body.className.match(/tool-(\w+)/)[1]; });
  await browser.close();
  return { ...r, setToolCompareLandsOn: landed, errors };
};
console.log(JSON.stringify({ dev: await look(true), plain: await look(false) }, null, 1));
srv.close(); process.exit(0);
