// icons.mjs — every icon a ribbon or the chrome asks for, actually drawn.
// lucide.createIcons() REPLACES the placeholder; a name that does not exist in the vendored set leaves
// the <i data-lucide="…"> sitting there, and a missing glyph in a row of line icons reads as a layout
// wobble rather than as a typo. Visit every tool so every ribbon is declared at least once.
import { serve, openPrism } from './prism_bench.mjs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism({ book: '/fake.ocd.epub', dev: true });
const tools = await page.$$eval('.px-tooltab', b => b.map(x => x.dataset.tool));
const dead = {};
for (const t of tools) {
  await page.click(`.px-tooltab[data-tool="${t}"]`);
  await page.waitForTimeout(700);
  const miss = await page.evaluate(() => [...document.querySelectorAll('[data-lucide]')]
      .filter(e => e.tagName !== 'svg' && !e.querySelector('svg'))
      .map(e => e.getAttribute('data-lucide')));
  for (const m of miss) (dead[m] ||= []).push(t);
}
console.log(JSON.stringify({ tools, iconsThatDidNotDraw: dead, errors }, null, 1));
await browser.close(); srv.close(); process.exit(Object.keys(dead).length ? 1 : 0);
