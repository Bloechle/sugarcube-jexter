// icons.mjs — every icon a ribbon or the chrome asks for, actually drawn.
// qry's icons.watch (boot() starts it) draws each <i data-icon> as it enters the page: the <i> stays and an
// <svg class="qry-icon"> goes inside. A name the set (shared/web/js/icons.js) does not register draws a
// DASHED SQUARE (`.qry-icon.is-missing`) and logs one console error — a hole in a row of line icons reads
// as a layout wobble rather than as a typo. Visit every tool so every ribbon is declared at least once.
import { serve, openPrism } from './prism_bench.mjs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism({ book: '/fake.ocd.epub', dev: true });
const tools = await page.$$eval('.px-tooltab', b => b.map(x => x.dataset.tool));
const dead = {}, names = new Set();
const sweep = async (where) => {
  const r = await page.evaluate(() => {
    const hosts = [...document.querySelectorAll('[data-icon]')];
    return { names: hosts.map(e => e.dataset.icon),
             miss: hosts.filter(e => !e.querySelector(':scope > svg.qry-icon') || e.querySelector('.is-missing')).map(e => e.dataset.icon) };
  });
  r.names.forEach(n => names.add(n));
  for (const m of r.miss) (dead[m] ||= []).push(where);
};
await sweep('home');
for (const t of tools) {
  await page.click(`.px-tooltab[data-tool="${t}"]`);
  await page.waitForTimeout(700);
  await sweep(t);
}
console.log(JSON.stringify({ tools, names: names.size, iconsThatDidNotDraw: dead, errors }, null, 1));
await browser.close(); srv.close(); process.exit(Object.keys(dead).length || errors.length ? 1 : 0);
