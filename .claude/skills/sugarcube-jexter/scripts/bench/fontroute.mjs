// The whole chain, from the browser: a font file → /api/font → the container's library → a run that
// uses it → the engine → a PDF. Nothing here knows a second grammar.
import { serve, openPrism, bookBytes } from './prism_bench.mjs';
import fs from 'node:fs';
const FIX = process.env.BENCH_FIX || '/tmp/fix';   // make_bench_fixtures.py writes here
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism({ book: '/dossier.ocd.epub' });
await page.waitForTimeout(1200);
const ttf = fs.readFileSync('/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf').toString('base64');
const out = await page.evaluate(async (b64) => {
  const { book } = await import('/shared/js/book.js');
  const bin = Uint8Array.from(atob(b64), c => c.charCodeAt(0));
  const alias = book.freeAlias();
  const r = await fetch(`/api/font?chars=${encodeURIComponent('Bonjour, Jean-Luc!')}&id=${alias}`,
                        { method: 'POST', body: bin });
  if (!r.ok) return { error: 'route ' + r.status };
  const f = await book.addFont(await r.text());
  const p = book.page(0);
  const run = p.text({ text: 'Bonjour, Jean-Luc!', x: 60, y: 300, size: 22, font: f.safe });
  await book.persist(0, true);
  return { alias, font: f.safe, glyphs: f.glyphs.length,
           uses: run.run.querySelectorAll('use').length, dataB: run.run.getAttribute('data-blanks') || '—',
           libFonts: [...new TextDecoder().decode(window.prism.state.files[Object.keys(window.prism.state.files).find(k => k.endsWith('pages/fonts.svg'))]).matchAll(/<g id="(f\d+)" data-font="([^"]*)"/g)].map(m => m[1] + ':' + m[2]) };
}, ttf);
const bytes = await bookBytes(page);
fs.writeFileSync(FIX + '/route.ocd.epub', bytes);
console.log(JSON.stringify({ ...out, errors }, null, 1));
await browser.close(); srv.close(); process.exit(0);
