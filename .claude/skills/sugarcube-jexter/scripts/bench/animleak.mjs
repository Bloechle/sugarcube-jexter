// applyAnim wraps real page content in a <g data-px-anim>. It is VIEWER chrome — the augmentation
// lives in the sidecar — so it must not reach the member. But it carries no data-ui, and the tools
// that persist a page strip only that. Animate, then make another tool write the page.
import { serve, openPrism, drag } from './prism_bench.mjs';
import fs from 'node:fs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism({ dev: true, book: '/dossier.ocd.epub', viewport: { width: 1500, height: 950 } });
const at = (x, y) => page.evaluate(([x, y]) => { const f = document.querySelector('.prism-page[data-index="0"] .prism-frame');
  const p = window.prism.frameToClient(f, x, y); return { x: p.x, y: p.y }; }, [x, y]);
await page.evaluate(() => window.prism.setTool('augment'));
await page.waitForTimeout(800);
await page.evaluate(() => [...document.querySelectorAll('#ribbon [data-ribbon="augment"] .px-rb-btn')].find(b => b.textContent.trim() === 'Animate')?.click());
await page.waitForTimeout(300);
await drag(page, await at(50, 90), await at(420, 260));
await page.waitForTimeout(800);
await page.evaluate(() => document.querySelector('#anim-ok')?.click());
await page.waitForTimeout(1500);
const onPage = await page.evaluate(() => document.querySelector('.prism-page[data-index="0"] .prism-frame').contentDocument.querySelectorAll('[data-px-anim]').length);
// now a tool that WRITES the page: Pages' rotate
await page.evaluate(() => window.prism.setTool('pages'));
await page.waitForTimeout(900);
await page.evaluate(() => [...document.querySelectorAll('#ribbon [data-ribbon="pages"] .px-rb-btn')].find(b => b.textContent.trim() === 'Right')?.click());
await page.waitForTimeout(2000);
const member = await page.evaluate(() => {
  const files = window.prism.state.files || {};
  const k = Object.keys(files).find(x => x.endsWith('page-001.xhtml'));
  const t = new TextDecoder().decode(files[k]);
  return { anim: (t.match(/data-px-anim/g) || []).length, ui: (t.match(/data-ui/g) || []).length };
});
console.log(JSON.stringify({ onPage, member, errors }, null, 1));
await browser.close(); srv.close(); process.exit(0);
