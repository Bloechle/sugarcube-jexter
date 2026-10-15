// groupstyle.mjs — every pane that groups its rows uses ITS OWN heading class, and every one of those
// classes is styled. compare.js emitted `au-group`, the Audit pane's, so it was styled only for as long
// as that pane existed — and `cmp-group`, declared for it, had no user at all. Computed styles, not a
// screenshot: a heading that lost its rule still shows its text.
import { serve, openPrism } from './prism_bench.mjs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism({ book: '/fake.ocd.epub', dev: true });
const r = await page.evaluate(() => {
  const probe = (cls) => {
    const li = document.createElement('li'); li.className = cls; li.textContent = 'x';
    (document.querySelector('#rd-list') || document.body).appendChild(li);
    const cs = getComputedStyle(li); const out = { transform: cs.textTransform, size: cs.fontSize, weight: cs.fontWeight };
    li.remove(); return out;
  };
  return { 'rd-group': probe('rd-group'), 'cmp-group': probe('cmp-group'), 'fm-group': probe('fm-group'),
           'au-group (must be unstyled now)': probe('au-group') };
});
const emitted = await page.evaluate(async () => {
  const src = await (await fetch('/compare.js')).text();
  return [...src.matchAll(/class="([a-z-]*group)"/g)].map(m => m[1]);
});
console.log(JSON.stringify({ computed: r, compareEmits: emitted, errors }, null, 1));
await browser.close(); srv.close(); process.exit(0);
