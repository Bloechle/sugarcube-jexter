// fontmetrics.mjs — the metrics the client reads out of pages/fonts.svg.
//
// `parseFonts` is a regex parser, so an attribute name it gets wrong returns nothing and no surface
// complains: the font panels simply draw fewer guide lines, and an authored run gives an unpaintable
// character the wrong advance. It read the v1 short names (`data-asc` · `data-desc` · `data-cap` ·
// `data-x` · `data-sp`) while SvgOcdWriter writes `data-ascent` · `data-descent` · `data-capheight` ·
// `data-xheight` · `data-space` (FORMAT §B6) — so every metric of every font was 0. Found 2026-09-14.
//
// The assertion is not "the client has metrics": it is that the client's numbers ARE the container's.
import { serve, openPrism } from './prism_bench.mjs';
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';

const FIX = process.env.BENCH_FIX || '/tmp/fix';
const BOOK = process.env.BENCH_BOOK || '/sample.ocd.epub';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism({ book: BOOK });
await page.waitForTimeout(1200);

const client = await page.evaluate(async () => {
  const { book } = await import('/shared/js/book.js');
  const f = book.fonts()[0];
  return { safe: f.safe, ascent: f.ascent, descent: f.descent, cap: f.cap, xh: f.xh, space: f.space };
});

// What the container itself states, read from the member with no client in between — and read from
// the file the BROWSER opened: the harness serves the book out of the site, so resolving it anywhere
// else is a comparison against a different document.
const rel = BOOK.startsWith('/') ? BOOK : '/' + BOOK;
const where = [process.env.BENCH_SITE || '/tmp/bench/site', FIX].map(d => d + rel).find(p => fs.existsSync(p));
if (!where) { console.log('FAIL — cannot find ' + rel + ' to read back'); process.exit(1); }
const svg = execFileSync('unzip', ['-p', where, 'OEBPS/pages/fonts.svg']).toString();
const head = svg.slice(svg.indexOf('<g id="f0"'), svg.indexOf('>', svg.indexOf('<g id="f0"')) + 1);
const at = (n) => { const m = new RegExp(`(?:^|\\s)${n}="([^"]*)"`).exec(head); return m ? +m[1] : null; };
const member = { ascent: at('data-ascent'), descent: at('data-descent'),
                 cap: at('data-capheight'), xh: at('data-xheight'), space: at('data-space') };

const same = Object.keys(member).every(k => client[k] === member[k]);
console.log(JSON.stringify({ client, member, clientMatchesContainer: same, errors }, null, 1));
if (!same) console.log('FAIL — the client does not read the metrics the container states');
await browser.close(); srv.close(); process.exit(same ? 0 : 1);
