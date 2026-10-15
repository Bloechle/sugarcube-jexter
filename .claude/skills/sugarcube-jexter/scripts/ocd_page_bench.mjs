// ocd_page_bench.mjs — screenshot page 1 of an OCD-EPUB in REAL Chromium.
//
// The Java reader and an SVG engine are different surfaces: a page can render
// correctly through `ocd -> pdf` and wrongly in a browser (anything carried only as
// a data-* attribute). This is the surface Prism shows, so it is the one that
// settles a rendering claim about the container.
//
//   cd /tmp/pw && PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers npm i playwright-core
//   mkdir book && (cd book && unzip -q out.ocd.epub)
//   node ocd_page_bench.mjs ./book shot.png 1689      # px width = pageWidthPt * dpi/72
//
// Served over HTTP, never file:// — opaque origins break the shared f.svg <use> refs
// and report a falsely empty page. Compare a BAND against the PDFBox reference, using
// each raster's OWN pt->px scale; a whole newspaper page differs by ~7 on antialiasing
// alone and buries a missing clip.
import { chromium } from 'playwright-core';
import http from 'node:http'; import fs from 'node:fs'; import path from 'node:path';

const [root, out, w] = process.argv.slice(2);
const MIME = { '.xhtml':'application/xhtml+xml', '.svg':'image/svg+xml', '.png':'image/png',
               '.jpg':'image/jpeg', '.html':'text/html', '.css':'text/css', '.js':'text/javascript' };
const srv = http.createServer((q, r) => {
  const p = path.join(root, decodeURIComponent(q.url.split('?')[0]));
  if (!fs.existsSync(p) || fs.statSync(p).isDirectory()) { r.writeHead(404); return r.end(); }
  r.writeHead(200, { 'Content-Type': MIME[path.extname(p)] || 'application/octet-stream' });
  fs.createReadStream(p).pipe(r);
});
await new Promise(res => srv.listen(8099, res));

const browser = await chromium.launch({
  executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome', args: ['--no-sandbox'] });
const page = await browser.newPage({ viewport: { width: +w, height: 900 }, deviceScaleFactor: 1 });
await page.goto('http://127.0.0.1:8099/OEBPS/pages/page-001.xhtml', { waitUntil: 'networkidle' });
await page.evaluate((W) => {                       // page fills the viewport width, nothing else
  const s = document.querySelector('svg');
  s.setAttribute('width', W); s.removeAttribute('height');
  document.documentElement.style.margin = document.body.style.margin = '0';
}, +w);
await page.waitForTimeout(400);
const box = await page.evaluate(() => { const b = document.querySelector('svg').getBoundingClientRect();
  return { w: Math.round(b.width), h: Math.round(b.height) }; });
await page.setViewportSize({ width: box.w, height: box.h });
await page.screenshot({ path: out, clip: { x: 0, y: 0, width: box.w, height: box.h } });
console.log(JSON.stringify(box));
await browser.close(); srv.close();
