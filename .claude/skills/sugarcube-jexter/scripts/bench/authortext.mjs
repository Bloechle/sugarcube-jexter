// ocd.js can author a run. Does what it writes survive the ENGINE — spaces included?
//
// This scenario used to print `data-u` and `data-b` and stop there. Both names died with grammar v2
// (`data-text`, `data-blanks`), so it reported null/null and asserted nothing — and the defect it was
// written for sat in the tree: ocd.js recorded its blanks as `data-b` while OCDReader reads
// `data-blanks`, so every sentinel a client authored was invisible to the engine and the reader
// aligned N-1 glyphs against N characters and TRUNCATED the run's tail. Found 2026-09-14.
//
// It now closes the loop: author, persist, post the container to the engine, and compare the text the
// engine reads back against what was typed. A scenario that only reports attributes measures the
// client's opinion of itself.
import { serve, openPrism } from './prism_bench.mjs';
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
const JAVA = process.env.JAVA_BIN || '/opt/jdk21/usr/lib/jvm/java-21-openjdk-amd64/bin/java';
const CP = process.env.JEXTER_CP_FULL || '/home/claude/out:/home/claude/jx/SugarcubeJexter/lib/*';
const FIX = process.env.BENCH_FIX || '/tmp/fix';
const TEXT = 'Bonjour, Jean-Luc!';                 // the space is the blank: it is what truncated the tail
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism({ book: process.env.BENCH_BOOK || '/sample.ocd.epub' });
await page.waitForTimeout(1200);

const wrote = await page.evaluate(async (text) => {
  const { book } = await import('/shared/js/book.js');
  const p = book.page(0);
  const font = p.doc.fonts[0]?.safe || p.doc.fonts[0]?.name;
  const r = p.text({ text, x: 60, y: 300, size: 22, font });
  await book.persist(0, true);
  const run = r.run;
  return { font, uses: run.querySelectorAll('use').length,
           // the names the FORMAT states (§B4) — and the dead ones, so a regression names itself
           dataText: run.getAttribute('data-text'), dataBlanks: run.getAttribute('data-blanks'),
           deadU: run.getAttribute('data-u'), deadB: run.getAttribute('data-b') };
}, TEXT);

const b64 = await page.evaluate(() => { const u = new Uint8Array(window.prism.bookBytes()); let s=''; const C=8192;
  for (let i=0;i<u.length;i+=C) s += String.fromCharCode(...u.subarray(i,i+C)); return btoa(s); });
fs.writeFileSync(FIX + '/authored.ocd.epub', Buffer.from(b64, 'base64'));

// THE LOOP: read the container with the engine and write it back out. `to=ocd` is the probe, not
// `to=md` — md and doctags project the LOGICAL tree, and nothing references an authored run, so they
// report an empty line whatever the reader did. What a truncating reader loses shows up in the run
// the engine RE-WRITES: its data-text comes back short and its blanks are gone.
execFileSync(JAVA, ['-cp', CP, 'sugarcube.jexter.Conversion',
                    FIX + '/authored.ocd.epub', FIX + '/authored-rt.ocd.epub'], { stdio: 'pipe' });
const zip = execFileSync('unzip', ['-p', FIX + '/authored-rt.ocd.epub', 'OEBPS/pages/page-001.xhtml']).toString();
const back = (zip.match(/data-text="(Bonjour[^"]*)"/) || [, '(the run is gone)'])[1];
const blanksBack = (zip.match(/data-blanks="([^"]*)"/g) || []).length;
const whole = back === TEXT;
// And it must still be a PARAGRAPH. A wrapper the reader does not know is a paint carrier: its children
// are walked straight through, so a run wrapped in the v1 `p`/`l` came back reparented to the page root
// — the text intact and the structure gone, which no text gate can see. The kinds the page states after
// the round trip are the assertion.
const kinds = {};
for (const m of zip.matchAll(/data-ocd="([a-z]+)"/g)) kinds[m[1]] = (kinds[m[1]] || 0) + 1;
const authoredStillAParagraph = /<g[^>]*data-ocd="paragraph"[^>]*>\s*<g[^>]*data-ocd="line"[^>]*>\s*<g[^>]*data-text="Bonjour/.test(zip.replace(/\n\s*/g, ''))
  || new RegExp('data-ocd="paragraph"[^]{0,400}?Bonjour').test(zip);

console.log(JSON.stringify({ wrote, typed: TEXT, engineReadsItWhole: whole,
  engineRead: back, blankRunsInRewrittenPage: blanksBack,
  kindsAfterRoundTrip: kinds, authoredStillAParagraph, errors }, null, 1));
if (!whole) console.log('FAIL — the engine did not read back what was typed');
if (!authoredStillAParagraph) console.log('FAIL — the authored run lost its paragraph on the round trip');
await browser.close(); srv.close(); process.exit(whole && authoredStillAParagraph ? 0 : 1);
