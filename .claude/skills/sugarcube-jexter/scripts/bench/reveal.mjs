// 2026-09-14: the verdict now runs BY ITSELF and writes nothing; `Re-check` asks again and `Repair` is
// the one verb that turns findings into work. The old `Audit` did both — so it is `Re-check` + `Repair`
// here, and a check that only wants the verdict presses `Re-check` alone.
import { serve, openPrism } from './prism_bench.mjs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism({ book: '/hidden.ocd.epub' });
const out = {}; const slot = '#ribbon [data-ribbon="redact"] ';
await page.evaluate(() => window.prism.setTool('redact'));
await page.waitForTimeout(700);

/** What the reader can actually SEE: per run, its painted colour and whether it has ink. */
const seen = () => page.evaluate(() => {
  const d = document.querySelector('.prism-page[data-index="0"] .prism-frame').contentDocument;
  return [...d.querySelectorAll('g[data-ocd="run"]')].map(g => {
    const cs = getComputedStyle(g);
    return { id: g.id, u: (g.getAttribute('data-text') || '').slice(0, 12), fill: cs.fill, op: cs.fillOpacity };
  });
});
const boxFill = () => page.evaluate(() => {
  const d = document.querySelector('.prism-page[data-index="0"] .prism-frame').contentDocument;
  const v = d.getElementById('v1');
  return v && { fill: getComputedStyle(v).fill, stroke: getComputedStyle(v).stroke };
});

out.beforeReveal = { runs: await seen(), box: await boxFill() };
// the audit first, so the "under a box" case has its ids
for (const l of ['Re-check', 'Repair']) { await page.evaluate(([s, l]) => [...document.querySelectorAll(s + '.px-rb-btn')].find(b => b.textContent.trim() === l).click(), [slot, l]); await page.waitForTimeout(l === 'Re-check' ? 8000 : 600); }
await page.waitForTimeout(8000);
out.audit = await page.evaluate(() => document.querySelectorAll('#rd-list li:not(.rd-group)').length);
await page.evaluate((s) => [...document.querySelectorAll(s + '.px-rb-btn')].find(b => b.textContent.trim() === 'Reveal').click(), slot);
await page.waitForTimeout(700);
out.afterReveal = { runs: await seen(), box: await boxFill() };
out.toast = await page.evaluate(() => [...document.querySelectorAll('sl-alert')].map(a => a.textContent.trim().slice(0, 130)).pop());
// off again: everything back
await page.evaluate((s) => [...document.querySelectorAll(s + '.px-rb-btn')].find(b => b.textContent.trim() === 'Reveal').click(), slot);
await page.waitForTimeout(500);
out.offAgain = { runs: await seen(), box: await boxFill(),
  style: await page.evaluate(() => !!document.querySelector('.prism-page[data-index="0"] .prism-frame').contentDocument.getElementById('rd-rv-style')) };
console.log(JSON.stringify({ ...out, errors }, null, 1));
await browser.close(); srv.close(); process.exit(0);
