import { serve, openPrism } from './prism_bench.mjs';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const out = {};
for (const book of ['/sample.ocd.epub', '/hidden.ocd.epub']) {
  const { browser, page, errors } = await openPrism({ book });
  out[book] = { title: await page.title(),
                name: await page.evaluate(() => window.prism.state.name),
                dcTitle: await page.evaluate(async () => {
                  const opf = new TextDecoder().decode(window.prism.state.files[window.prism.state.opfPath]);
                  return (/<dc:title[^>]*>([^<]*)</.exec(opf) || [])[1] || '';
                }) };
  // and what closing gives back
  await page.evaluate(() => document.getElementById('m-close').click());
  await page.waitForTimeout(500);
  out[book].afterClose = await page.title();
  out[book].errors = errors;
  await browser.close();
}
console.log(JSON.stringify(out, null, 1));
srv.close(); process.exit(0);
