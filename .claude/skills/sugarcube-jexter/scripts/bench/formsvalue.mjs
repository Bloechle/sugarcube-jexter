// formsvalue.mjs — the Value control of Forms, after the chassis stopped captioning wide controls.
// The name must not be lost: a wide control WEARS its label, as its placeholder. And the row it sits
// in must be a row like any other.
import { serve, openPrism } from './prism_bench.mjs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism({ book: process.env.BENCH_BOOK || '/rich.ocd.epub', dev: true });
await page.evaluate(() => window.prism.setTool('forms'));
await page.waitForTimeout(900);
const field = (n) => page.evaluate((n) => {
  const a = document.querySelectorAll('#fm-list [data-fm]')[n]; if (!a) return null; a.click(); return true;
}, n);
const read = () => page.evaluate(() => {
  const slot = document.querySelector('#ribbon [data-ribbon="forms"]');
  const i = slot.querySelector('input[type="search"]');
  return { placeholder: i?.placeholder, value: i?.value,
           captions: [...slot.querySelectorAll('.px-rb-cap')].map(e => e.textContent),
           ribbon: Math.round(document.getElementById('ribbon').getBoundingClientRect().height),
           fieldBox: Math.round(slot.querySelector('.px-rb-field').getBoundingClientRect().height),
           btn: Math.round(slot.querySelector('.px-rb-btn').getBoundingClientRect().height) };
});
const list = await page.$$eval('#fm-list [data-fm]', a => a.map(x => x.textContent.trim().slice(0, 30)));
await field(0); const first = await read();
await page.evaluate(() => { const i = document.querySelector('#ribbon [data-ribbon="forms"] input[type="search"]');
  i.focus(); i.value = 'Jean-Luc Bloechle (typed)'; i.dispatchEvent(new Event('change', { bubbles: true })); });
await page.waitForTimeout(500);
const written = await page.evaluate(() => {
  const a = document.querySelectorAll('#fm-list [data-fm]')[0];
  return { row: a.textContent.trim().slice(0, 40) };
});
await field(1); const second = await read();
console.log(JSON.stringify({ list, first, afterTyping: written, second,
  nameIsShown: !!first.placeholder && first.placeholder !== 'Value',
  nameFollowsSelection: first.placeholder !== second.placeholder,
  fieldIsAsTallAsACommand: first.fieldBox === first.btn, errors }, null, 1));
await browser.close(); srv.close(); process.exit(0);
