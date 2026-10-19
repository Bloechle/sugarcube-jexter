// hyphens.mjs — the client reads a line-final hyphen as the engine does.
//
// Read-aloud and the search index rebuild the text from the page's runs; the engine's read-out (OCDIndex)
// decides each line-final hyphen from the book's own words. Two rules had drifted: the client kept the
// hyphen before any capital ("Trace-Monkey") while the engine put a space after it ("Trace- Monkey"), and
// the document writes "TraceMonkey". The gates' `text_press` fixture holds every case.
import { serve, openPrism } from './prism_bench.mjs';
const WANT = 'les allers-retours entre les deux villes et les allers-retours du soir avec la prévention'
    + ' routière et la prévention des chantiers de collaboration avec le canton'
    + ' et le moteur TraceMonkey du TraceMonkey du soir à Saint-Gall ce jeudi';
const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism({ book: '/text_press.ocd.epub', dev: true });
const r = await page.evaluate(() => {
  const doc = document.querySelector('.prism-frame').contentDocument;
  const tts = (window.prism.hooks.ttsNodes?.(doc, 0) || [])
    .map(g => g.map(n => n.text).join('')).join(' ').replace(/\s+/g, ' ');
  return { tts, search: window.prism.state.pages[0].text || '' };
});
const bad = [];
if (!r.tts.includes(WANT))    bad.push('read-aloud: ' + r.tts.slice(r.tts.indexOf('les allers'), r.tts.indexOf('les allers') + 260));
if (!r.search.includes(WANT)) bad.push('search index: ' + r.search.slice(r.search.indexOf('les allers'), r.search.indexOf('les allers') + 260));
console.log(JSON.stringify({ tts: bad.length ? r.tts : 'ok', search: bad.length ? r.search : 'ok', errors }, null, 1));
if (bad.length) console.log('FAIL\n  ' + bad.join('\n  '));
await browser.close(); srv.close(); process.exit(bad.length || errors.length ? 1 : 0);
