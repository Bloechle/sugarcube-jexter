// prism_bench.mjs — run a REAL Prism in Chromium, with a REAL engine behind /api/convert.
//
// Why: every client defect worth the name in this project was invisible to `node --check`, to the
// container gates and to reading the code — an iframe's initial about:blank, a native <img> drag
// hijacking a pointer gesture, a stretched grid column, a cache key that did not say what it cached.
// They speak here and nowhere else.
//
// Setup (once), all from allowed domains:
//
//   mkdir -p /tmp/bench/vendor && cd /tmp/bench/vendor
//   curl -sSLO https://raw.githubusercontent.com/Bloechle/qry-js/1.3.0/qry.js
//   curl -sSLO https://raw.githubusercontent.com/Bloechle/qry-js/1.3.0/qry-ui.css
//   curl -sSLO https://raw.githubusercontent.com/Bloechle/qry-js/1.3.0/qry-kit.js
//   npm pack lucide@1.17.0 && tar xzf lucide-1.17.0.tgz && cp package/dist/umd/lucide.min.js .
//   npm pack @shoelace-style/shoelace@2.20.1 && mkdir sl && tar xzf shoelace-*.tgz -C sl
//   npm pack fflate@0.8.2 && tar xzf fflate-0.8.2.tgz && cp package/esm/browser.js fflate.js
//   cd /tmp/bench && npm i playwright-core
//   cp -r <repo>/src/sugarcube/jexter/ui/prism/web site
//   mkdir -p site/shared && cp -r <repo>/src/sugarcube/jexter/ui/shared/web/* site/shared/
//   cp <a book>.ocd.epub site/sample.ocd.epub
//   # a fixture with something to redact and pages the viewer TURNS (pikepdf: page.Rotate = 90 on a
//   # PORTRAIT MediaBox — reportlab's setPageRotation swaps the box instead, a different case)
//   # then rewrite the CDN URLs in site/index.html and the modules to /vendor/… (jsdelivr is blocked)
//
// Run:   PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node prism_bench.mjs [scenario]
//
// The server implements /api/convert by invoking the engine, so Apply-style round trips are real:
// the browser posts the book, the JVM reshapes it, the browser re-opens the answer.

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { chromium } from 'playwright-core';

const ROOT = process.env.BENCH_ROOT || '/tmp/bench';
// BENCH_SITE, so the same scenario can be run against two builds (an A/B is the only way to say a
// change caused what you are looking at). Hardcoding ROOT/site here once made two "before" runs
// silently measure the "after" tree.
const SITE = process.env.BENCH_SITE || path.join(ROOT, 'site');
const JAVA = process.env.JAVA_BIN || '/opt/jdk21/usr/lib/jvm/java-21-openjdk-amd64/bin/java';
const CP = process.env.JEXTER_CP_FULL || '/home/claude/jx/SugarcubeJexter/lib/*:/home/claude/out';
const CHROME = process.env.CHROME_BIN || '/opt/pw-browsers/chromium-1194/chrome-linux/chrome';
const PORT = +(process.env.BENCH_PORT || 8110);

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.xhtml': 'application/xhtml+xml', '.json': 'application/json',
  '.epub': 'application/epub+zip', '.jpg': 'image/jpeg', '.png': 'image/png', '.woff2': 'font/woff2',
  '.opf': 'application/oebps-package+xml', '.ncx': 'application/x-dtbncx+xml',
};

export function serve() {
  return http.createServer((q, r) => {
    if (q.url.startsWith('/api/convert')) {
      const u = new URL(q.url, 'http://x');
      const chunks = [];
      q.on('data', c => chunks.push(c));
      q.on('end', () => {
        // The OUTPUT EXTENSION is how Conversion infers the target, so a route that always wrote
        // _out.ocd.epub answered `to=audit` with an OCD container: give each target its own name and
        // pass --to as well. audit / zones / patch are the redaction routes and all three are used.
        const ext = { ocd: 'ocd.epub', patch: 'patch.zip', audit: 'audit.json', zones: 'zones.json' };
        const to = u.searchParams.get('to') || 'ocd';
        const inF = path.join(ROOT, '_in.ocd.epub'), outF = path.join(ROOT, '_out.' + (ext[to] || to));
        fs.writeFileSync(inF, Buffer.concat(chunks));
        const args = ['-cp', CP, 'sugarcube.jexter.write.Conversion', inF, outF, '--to=' + to];
        for (const [k, v] of u.searchParams) if (k !== 'to') args.push(`--${k}=${v}`);
        try {
          execFileSync(JAVA, args, { stdio: 'pipe' });
          const b = fs.readFileSync(outF);
          r.writeHead(200, { 'Content-Type': 'application/epub+zip',
                             'Content-Disposition': 'attachment; filename="document.ocd.epub"' });
          r.end(b);
        } catch (e) {
          // WebApp.error answers {"error": <exception MESSAGE>} — match it, or a toast read here is a
          // reading of the harness rather than of the product.
          const err = String(e.stderr || e);
          const m = /(?:Exception|Error)[^:]*:\s*([^\n]+)/.exec(err);
          r.writeHead(500, { 'Content-Type': 'application/json' });
          r.end(JSON.stringify({ error: (m ? m[1] : err).trim().slice(0, 300) }));
        }
      });
      return;
    }
    if (q.url.startsWith('/api/font')) {                    // a TTF in, the container's font block out
      const u = new URL(q.url, 'http://x'); const chunks = [];
      q.on('data', c => chunks.push(c));
      q.on('end', () => {
        const inF = path.join(ROOT, '_in.ttf'); fs.writeFileSync(inF, Buffer.concat(chunks));
        try {
          // The characters go through a FILE, never a process argument: a JVM decodes argv with the
          // platform charset, and an accented character arrives as U+FFFD — measured, an É silently
          // dropped out of an added font while the real route (URLDecoder, UTF-8) handled it fine.
          const charsF = path.join(ROOT, '_chars.txt');
          fs.writeFileSync(charsF, u.searchParams.get('chars') || '', 'utf8');
          const svg = execFileSync(JAVA, ['-cp', CP, path.join(ROOT, 'FontBlock.java'), inF,
                                          '@' + charsF, u.searchParams.get('id') || 'f0']);
          r.writeHead(200, { 'Content-Type': 'image/svg+xml; charset=utf-8' }); r.end(svg);
        } catch (e) { r.writeHead(500, { 'Content-Type': 'application/json' }); r.end(JSON.stringify({ error: String(e.stderr || e).slice(0, 200) })); }
      });
      return;
    }
    // `/api/health` must answer exactly `ok`: engine.js keys `engine` on it, and `exportAs` returns
    // immediately when it is false — so answering JSON here silently put the WHOLE export path out of
    // reach, jx-only buttons included, and every export test passed by never running.
    // TWO EventSources, not one: the watchdog heartbeat (/api/alive) AND the F2 log stream (/api/log).
    // Both must be answered as text/event-stream, or every run logs a MIME error that looks like the
    // app's — the second one cost a whole pass of "is this ours or theirs?".
    if (q.url.startsWith('/api/alive') || q.url.startsWith('/api/log') || q.url.includes('/alive')) {
      r.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
      return r.write(': hello\n\n');
    }
    if (q.url.startsWith('/api/health')) { r.writeHead(200, { 'Content-Type': 'text/plain' }); return r.end('ok'); }
    if (q.url.startsWith('/api/')) { r.writeHead(200, { 'Content-Type': 'application/json' }); return r.end('{"targets":["ocd","pdf"],"options":[]}'); }
    let u = decodeURIComponent(q.url.split('?')[0]);
    if (u === '/') u = '/index.html';
    const f = u.startsWith('/vendor/') ? path.join(ROOT, u) : path.join(SITE, u);
    if (!fs.existsSync(f) || fs.statSync(f).isDirectory()) { r.writeHead(404); return r.end('404 ' + u); }
    r.writeHead(200, { 'Content-Type': MIME[path.extname(f)] || 'application/octet-stream',
                       'Service-Worker-Allowed': '/' });   // the reader IS a Service Worker
    fs.createReadStream(f).pipe(r);
  });
}

/** Open Prism on a book, with the errors surfaced — a silent page is the usual failure. */
export async function openPrism({ dev = false, book = '/sample.ocd.epub', viewport = { width: 1500, height: 1000 } } = {}) {
  const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });
  const page = await (await browser.newContext({ viewport })).newPage();
  const errors = [];
  page.on('pageerror', e => errors.push('PAGEERROR ' + e.message));
  page.on('console', m => {
    const t = m.text();
    if (m.type() === 'error' && !t.includes('jsdelivr') && !t.includes('ERR_FAILED')) errors.push(t.slice(0, 160));
  });
  await page.goto(`http://127.0.0.1:${PORT}/?epub=${book}${dev ? '&dev=1' : ''}`);
  await page.waitForFunction(() => document.body.classList.contains('has-book'), null, { timeout: 30000 });
  await page.waitForTimeout(900);
  return { browser, page, errors };
}

/** Click a ribbon command by its EXACT label — two commands whose labels share a prefix is a real
 *  hazard, and a driver that matches loosely will click the wrong one and report a silent failure. */
export const ribbon = (page, tool, label) => page.evaluate(([t, l]) => {
  const b = [...document.querySelectorAll(`#ribbon [data-ribbon="${t}"] .px-rb-btn`)].find(x => x.textContent.trim() === l);
  if (!b) throw new Error('no ribbon command ' + l);
  b.click();
}, [tool, label]);

/** A pointer drag — never `dragAndDrop`: this UI uses pointer events with capture, and synthetic
 *  HTML5 dnd does not fire over its content. */
export async function drag(page, from, to, steps = 8) {
  await page.mouse.move(from.x, from.y);
  await page.mouse.down();
  await page.mouse.move((from.x + to.x) / 2, (from.y + to.y) / 2, { steps });
  await page.mouse.move(to.x, to.y, { steps });
  await page.mouse.up();
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const srv = serve();
  await new Promise(d => srv.listen(PORT, d));
  const { browser, page, errors } = await openPrism({ dev: true });
  await page.evaluate(() => window.prism.setTool('pages'));
  await page.waitForFunction(() => document.querySelectorAll('#pages-table .px-tile-img.ready').length >= 3,
                             null, { timeout: 20000 });
  console.log(JSON.stringify({
    tabs: await page.evaluate(() => [...document.querySelectorAll('.px-tooltab')].map(b => b.getAttribute('data-tool'))),
    tiles: await page.evaluate(() => document.querySelectorAll('#pages-table .px-tile').length),
    railIframes: await page.evaluate(() => document.querySelectorAll('#page-list iframe').length),
    errors,
  }, null, 1));
  await browser.close();
  srv.close();
}
