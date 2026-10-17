import { serve, openPrism, drag } from './prism_bench.mjs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism();
const out = {};
await page.evaluate(() => window.prism.setTool('redact'));
await page.waitForTimeout(500);

const frame = () => page.evaluate(() => {
  const f = document.querySelector('.prism-page[data-index="0"] .prism-frame');
  const r = f.getBoundingClientRect();
  return { x: r.x, y: r.y, w: r.width, h: r.height };
});
/** A point of the page, in chassis coordinates — through the chassis's own one mapping. */
const at = (x, y) => page.evaluate(([x, y]) => {
  const f = document.querySelector('.prism-page[data-index="0"] .prism-frame');
  const p = window.prism.frameToClient(f, x, y);
  return { x: p.x, y: p.y };
}, [x, y]);
const state = () => page.evaluate(() => {
  const d = document.querySelector('.prism-page[data-index="0"] .prism-frame').contentDocument;
  const z = d.querySelector('.rd-zone');
  const box = z && { x: +z.getAttribute('x'), y: +z.getAttribute('y'), w: +z.getAttribute('width'), h: +z.getAttribute('height') };
  return {
    zones: d.querySelectorAll('.rd-zone').length,
    handles: d.querySelectorAll('[data-gz]').length,
    box: box && Object.fromEntries(Object.entries(box).map(([k, v]) => [k, Math.round(v * 100) / 100])),
    transform: z?.getAttribute('transform') || null,
    fill: z?.getAttribute('fill'), alpha: z?.getAttribute('fill-opacity'),
    fields: ['x', 'y', 'w', 'h'].map(k => document.getElementById('rd-' + k).value),
    disabled: document.getElementById('rd-x').disabled,
    zoneInfo: document.getElementById('rd-zone-info').textContent.slice(0, 60),
  };
});

const b = await frame();
// draw one zone over the AVS line
await drag(page, { x: b.x + 90, y: b.y + 88 }, { x: b.x + 330, y: b.y + 114 });
await page.waitForTimeout(300);
out.drawn = await state();

// 1 · click empty space → deselected
const empty = await at(450, 700);
await page.mouse.click(empty.x, empty.y);
await page.waitForTimeout(200);
out.deselected = await state();

// 2 · click the zone → selected again
const c = out.drawn.box;
const centre = await at(c.x + c.w / 2, c.y + c.h / 2);
await page.mouse.click(centre.x, centre.y);
await page.waitForTimeout(200);
out.reselected = await state();

// 3 · MOVE the body by +40 / +30 page units
const to = await at(c.x + c.w / 2 + 40, c.y + c.h / 2 + 30);
await drag(page, centre, to);
await page.waitForTimeout(300);
out.moved = await state();

// 4 · RESIZE from the SE handle: the NW corner must not move (the anchor)
const m = out.moved.box;
const se = await at(m.x + m.w, m.y + m.h);
const se2 = await at(m.x + m.w + 60, m.y + m.h + 20);
await drag(page, se, se2);
await page.waitForTimeout(300);
out.resized = await state();

// 5 · a number typed in the drawer moves the same rectangle
await page.evaluate(() => { const f = document.getElementById('rd-w'); f.value = '120'; f.dispatchEvent(new Event('change', { bubbles: true })); });
await page.waitForTimeout(300);
out.typed = await state();

// 6 · the mark's colour reaches the preview (the opacity control is gone on purpose: checking a
//     redaction is Reveal's job, not a see-through mark's)
await page.evaluate(() => {
  const s = '#ribbon [data-ribbon="redact"] ';
  const c = document.querySelector(s + 'input[type="color"]'); c.value = '#ff0000'; c.dispatchEvent(new Event('input', { bubbles: true }));
});
await page.waitForTimeout(300);
out.mark = await state();

// 7 · Delete removes the selected zone
await page.keyboard.press('Delete');
await page.waitForTimeout(300);
out.deleted = await state();

console.log(JSON.stringify({ ...out, errors }, null, 1));
await browser.close(); srv.close(); process.exit(0);
