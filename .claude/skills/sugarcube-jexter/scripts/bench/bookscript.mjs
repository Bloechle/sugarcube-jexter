// bookscript.mjs — a book's own script never runs as Prism.
// A page member is somebody else's file, served from Prism's origin into frames the tools reach into. A
// <script> in a member ran on open, and a javascript: link ran on click — AS Prism, able to call its /api.
// The worker serves every member with script off (prism-sw.js BOOK_CSP); this opens a book carrying both,
// clicks the link, and fails if either ran or if the policy is not what refused them.
import { serve, openPrism } from './prism_bench.mjs';
import { unzipSync, zipSync, strToU8, strFromU8 } from '../../../../../src/sugarcube/jexter/ui/shared/web/vendor/fflate.js';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = process.env.BENCH_ROOT || '/tmp/bench';
const SITE = process.env.BENCH_SITE || path.join(ROOT, 'site');
const files = unzipSync(fs.readFileSync(path.join(SITE, 'sample.ocd.epub')));
const PAGE = Object.keys(files).find(n => n.endsWith('page-001.xhtml'));
const at = (s, i) => s.slice(0, i);
let xhtml = strFromU8(files[PAGE]);
const end = xhtml.lastIndexOf('</svg>');
xhtml = at(xhtml, end)
  + '<g data-ocd="links"><a href="javascript:top.document.title=\'LINK-RAN\'"><rect x="0" y="0" width="400" height="400" style="fill-opacity:0"/></a></g>'
  + xhtml.slice(end);
xhtml = xhtml.replace('</body>', '<script>top.document.title = "SCRIPT-RAN";</script></body>');
files[PAGE] = strToU8(xhtml);
fs.writeFileSync(path.join(SITE, 'bookscript.ocd.epub'), zipSync(files));

const srv = serve(); await new Promise(d => srv.listen(8110, d));
const { browser, page, errors } = await openPrism({ book: '/bookscript.ocd.epub' });
const refused = [];
page.on('console', m => { if (/Content Security Policy/.test(m.text())) refused.push(m.text().slice(0, 80)); });
const frame = await (await page.$('.prism-page[data-index="0"] iframe.prism-frame')).contentFrame();
const csp = await frame.evaluate(async () => (await fetch(location.href)).headers.get('content-security-policy'));
const box = await (await frame.$('a')).boundingBox();
await page.mouse.click(box.x + 20, box.y + 20);
await page.waitForTimeout(1200);
const title = await page.title();
const ok = !/RAN/.test(title) && /script-src 'none'/.test(csp || '');
console.log(JSON.stringify({ title, csp, refusedAfterOpen: refused.length, errors: errors.filter(e => !/Content Security Policy/.test(e)) }, null, 1));
await browser.close(); srv.close();
process.exit(ok ? 0 : 1);
