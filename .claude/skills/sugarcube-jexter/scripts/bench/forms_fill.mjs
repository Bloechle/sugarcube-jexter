import { serve, openPrism } from "./prism_bench.mjs";
import { promises as fs } from "node:fs";

const srv = serve();
await new Promise(d => srv.listen(+(process.env.BENCH_PORT || 8110), d));
const { browser, page, errors } = await openPrism({});
await page.waitForTimeout(1200);
await page.evaluate(() => window.prism.setTool("forms"));
await page.waitForTimeout(900);

const at = (n) => page.evaluate((n) => {
  const f = document.querySelector('.prism-page[data-index="0"] .prism-frame');
  const r = [...f.contentDocument.querySelectorAll(".fm-box")][n].getBBox();
  const p = window.prism.frameToClient(f, r.x + r.width / 2, r.y + r.height / 2);
  return { x: p.x, y: p.y };
}, n);

const texts = [
  "Devant la porte, samedi 14h",
  "Et si la porte etait restee ouverte ?",
  "Anxiete 80% - je dois verifier",
  "Je retourne verifier trois fois",
  "Soulagement court, puis le doute revient",
];

let focused = null;
for (let n = 0; n < 5; n++) {
  const p = await at(n);
  await page.mouse.click(p.x, p.y);
  await page.waitForTimeout(320);
  if (focused === null) focused = await page.evaluate(() => {
    const d = document.querySelector('.prism-page[data-index="0"] .prism-frame').contentDocument;
    const a = d.activeElement;
    return a ? a.tagName + (a.hasAttribute("data-fm-edit") ? " [editor]" : "") : "none";
  });
  await page.keyboard.type(texts[n]);
  await page.waitForTimeout(200);
}
await page.waitForTimeout(400);
await page.screenshot({ path: "/tmp/bench/prism-forms-edit.png" });

const member = await page.evaluate(async () => {
  const m = await import("/shared/js/book.js");
  return new TextDecoder().decode(m.book.get(m.book.member("ocd/annots.json")));
});
await fs.writeFile("/tmp/bench/annots2.json", member);
const ribbonValue = await page.evaluate(() => document.querySelector('#ribbon [data-ribbon="forms"] input')?.value);
const uiLeft = await page.evaluate(async () => { const m = await import("/shared/js/book.js"); await m.book.persist(0, true);
  const t = new TextDecoder().decode(m.book.get(m.book.pagePath(0)));
  return { foreignObject: (t.match(/foreignObject/g) || []).length, dataUi: (t.match(/data-ui/g) || []).length, runs: (t.match(/data-ocd="run"/g) || []).length }; });
console.log(JSON.stringify({ focusedAfterFirstClick: focused, ribbonValue, uiLeft, errors }, null, 1));
await browser.close();
srv.close();
process.exit(0);
