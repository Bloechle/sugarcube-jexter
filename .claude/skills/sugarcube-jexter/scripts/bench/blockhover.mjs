// Block mode: does the page say what a click will take, before the click — and does the modifier move it?
import { serve, openPrism } from './prism_bench.mjs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism({ book: '/sample.ocd.epub' });
const slot = '#ribbon [data-ribbon="redact"] ';
const out = {};
await page.evaluate(() => window.prism.setTool('redact'));
await page.waitForTimeout(700);
await page.evaluate((s) => [...document.querySelectorAll(s + '.px-rb-btn')].find(b => b.textContent.trim() === 'Block').click(), slot);
await page.waitForTimeout(300);

const at = (x, y) => page.evaluate(([x, y]) => {
  const f = document.querySelector('.prism-page[data-index="0"] .prism-frame');
  const p = window.prism.frameToClient(f, x, y); return { x: p.x, y: p.y };
}, [x, y]);
const hov = () => page.evaluate(() => {
  const d = document.querySelector('.prism-page[data-index="0"] .prism-frame').contentDocument;
  const r = d.querySelector('.rd-hov-box');
  return r && { box: ['x','y','width','height'].map(k => Math.round(+r.getAttribute(k) * 10) / 10),
                title: r.querySelector('title')?.textContent };
});
// where a paragraph actually is: ask the page
// Aim at a RUN inside a multi-run block: the centre of a block often falls between two lines, where
// there is no run at all — and then Shift correctly changes nothing, which looks like a broken modifier.
const target = await page.evaluate(() => {
  const d = document.querySelector('.prism-page[data-index="0"] .prism-frame').contentDocument;
  const p = [...d.querySelectorAll('[data-ocd="paragraph"]')].find(e => e.querySelectorAll('[data-ocd="run"]').length > 1);
  const runs = [...p.querySelectorAll('[data-ocd="run"]')];
  const r = runs[Math.floor(runs.length / 2)];
  const rb = r.getBBox(), m = r.transform?.baseVal?.consolidate()?.matrix;
  const pt = { x: rb.x + rb.width / 2, y: rb.y + rb.height / 2 };
  const q = m ? { x: m.a * pt.x + m.c * pt.y + m.e, y: m.b * pt.x + m.d * pt.y + m.f } : pt;
  const pb = p.getBBox();
  return { block: p.id, run: r.id, runs: runs.length, blockBox: [Math.round(pb.width), Math.round(pb.height)], cx: q.x, cy: q.y };
});
out.target = target;
const c = await at(target.cx, target.cy);
await page.mouse.move(c.x, c.y); await page.waitForTimeout(250);
out.onBlock = await hov();
await page.keyboard.down('Shift'); await page.waitForTimeout(250);
out.withShift = await hov();
await page.keyboard.up('Shift'); await page.waitForTimeout(250);
out.shiftReleased = await hov();
// click → the zone must land on the SAME box, with handles
await page.mouse.click(c.x, c.y); await page.waitForTimeout(400);
out.afterClick = await page.evaluate(() => {
  const d = document.querySelector('.prism-page[data-index="0"] .prism-frame').contentDocument;
  const z = d.querySelector('.rd-zone');
  return { zones: d.querySelectorAll('.rd-zone').length, handles: d.querySelectorAll('[data-gz]').length,
           hover: !!d.querySelector('.rd-hov-box'),
           box: z && ['x','y','width','height'].map(k => Math.round(+z.getAttribute(k) * 10) / 10),
           fields: ['x','y','w','h'].map(k => document.getElementById('rd-' + k).value) };
});
// leaving block mode takes the highlight with it
await page.mouse.move(c.x + 4, c.y + 4); await page.waitForTimeout(200);
await page.evaluate((s) => [...document.querySelectorAll(s + '.px-rb-btn')].find(b => b.textContent.trim() === 'Zone').click(), slot);
await page.waitForTimeout(300);
out.afterLeavingBlock = await hov();
console.log(JSON.stringify({ ...out, errors }, null, 1));
await browser.close(); srv.close(); process.exit(0);
