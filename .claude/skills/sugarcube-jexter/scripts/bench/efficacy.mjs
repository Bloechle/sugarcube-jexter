// "No console error" is not "it works". For every command of every tool: does anything OBSERVABLE
// change — the page DOM, the container members, the ribbon's own state, a toast? A command that moves
// nothing is either broken or a no-op nobody would miss.
import { serve, openPrism } from './prism_bench.mjs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism({ dev: true, book: '/hidden.ocd.epub', viewport: { width: 1500, height: 950 } });
await page.waitForTimeout(1200);

const snap = () => page.evaluate(() => {
  const d = document.querySelector('.prism-page[data-index="0"] .prism-frame')?.contentDocument;
  const files = window.prism.state.files || {};
  let bytes = 0; for (const k of Object.keys(files)) bytes += (files[k]?.length || 0);
  return {
    dom: d ? d.documentElement.outerHTML.length : 0,
    ui: d ? d.querySelectorAll('[data-ui]').length : 0,
    members: Object.keys(files).length, bytes,
    ribbon: document.querySelector('#ribbon')?.innerHTML.length || 0,
    drawer: document.querySelector('.px-drawer:not([hidden])')?.innerHTML.length || 0,
    toasts: document.querySelectorAll('sl-alert').length,
  };
});
const diff = (a, b) => Object.keys(a).filter(k => a[k] !== b[k]);

const out = {};
for (const tool of ['augment', 'analysis', 'edit', 'redact', 'pages', 'home']) {
  await page.evaluate(t => window.prism.setTool(t), tool);
  await page.waitForTimeout(700);
  const cmds = await page.evaluate((t) => [...document.querySelectorAll(`#ribbon [data-ribbon="${t}"] .px-rb-btn`)]
    .filter(b => !b.hasAttribute('disabled')).map(b => b.textContent.trim()), tool);
  const rows = [];
  for (const c of cmds) {
    const before = await snap();
    await page.evaluate(([t, l]) => [...document.querySelectorAll(`#ribbon [data-ribbon="${t}"] .px-rb-btn`)]
      .find(b => b.textContent.trim() === l && !b.hasAttribute('disabled'))?.click(), [tool, c]);
    await page.waitForTimeout(1500);
    const after = await snap();
    const moved = diff(before, after);
    rows.push(`${c}${moved.length ? ' → ' + moved.join(',') : '  ⟨RIEN⟩'}`);
    await page.keyboard.press('Escape');
    await page.waitForTimeout(200);
  }
  out[tool] = rows;
}
console.log(JSON.stringify({ ...out, errors }, null, 1));
await browser.close(); srv.close(); process.exit(0);
