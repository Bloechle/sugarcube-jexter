# The OCD-EPUB container — grammar, traps, and what the numbers actually say

Everything here is measured on real documents (doc-A, a French press cover of 823 nodes;
MARSEILL; GeoTopo, 117 pages) with the tools in `pdf-forensics.md`. Read it before touching
`SvgOcdWriter`, `OCDReader`, `ocd.js`, or `doc/FORMAT.md` — those four move together or not at all.

## 1. The vocabulary (2026-09, v2)

```
data-ocd :  page · paragraph · line · run · group · layer · media · clip · links · fonts
attributs:  data-font data-size data-text data-order data-blanks data-chars data-render
            data-matrix data-clip data-layer data-src data-role data-flow data-name
            data-alpha data-blend data-fill data-mediabox data-bleedbox data-trimbox
            data-artbox data-rotate data-version data-dpi
membre   :  OEBPS/ocd/{meta,outline,structures}.json · OEBPS/pages/fonts.svg
```

Three rules produced it, and they are the ones to keep applying:

- **A name never repeats its context.** `line` lives inside a `paragraph` and holds only runs;
  `textline` would say "text" three times. A graphic line is a `<path>`, so the word is free.
- **One relation, one name.** `data-ref` used to mean three things — clip id, layer id, media
  payload — and a consumer that picked the wrong element read the wrong table with no syntax error.
- **Never name a `data-*` like an SVG attribute beside it.** `data-tr` next to `transform` is the
  trap that cost a quarter-turn on a rotated veil. It is `data-matrix`.

`run` and not `token`: OOXML calls a same-paint-state glyph sequence a *run*, ALTO and hOCR both
name the three levels block/line/word. A token implies a lexical granularity the model does not
have — word segmentation is a reading, not a structure.

## 2. `spliceGraphics` is LOAD-BEARING — do not "restore" the graphic wrapper

`GraphicClusterer` groups contiguous vector paths into `OCDGraphic`, and `SvgOcdWriter` DISSOLVES
that wrapper when it paints nothing of its own. That looks like a lost analysis result. It is not:

```
graphic z=287   children z: 287 225 255 316
other page nodes z: 287 225 255 316 131 131 196 283 221 330 317 318 …
```

**A drawing's paths do not occupy a contiguous z range** — other content paints between them.
Nesting them in one `<g>` forces a single depth slot, and the page repaints wrong: measured in
Chromium, a path landed over the text and a logo plus four italic lines vanished. I broke this
twice in one session before measuring it.

If a client needs to pick a drawing as a unit, membership must travel as an ATTRIBUTE
(`data-graphic` on each spliced child), never by containment — and then the reader has to rebuild
the wrapper RECURSIVELY (members nested inside groups are not in `page.content()`; that is the bug
that failed both attempts). Until someone does that properly, the client must not query
`[data-ocd="graphic"]`: the selectors were removed from `augment.js` and `analysis.js` for exactly
this reason.

## 3. Renaming the vocabulary: the two traps a script cannot see

- **`OCDReader`'s outer `switch (tag)` is on the SVG TAG NAME**, not on `data-ocd`. Renaming
  `case "g"` to `case "group"` silently destroys the container: 823 nodes written, **40** read back.
- **Bare string comparisons.** The line-break reconstruction tests
  `"l".equals(c.getAttribute("data-ocd"))`; nothing in a `data-ocd="l"`-shaped regex matches it.
  Symptom: 61 `OCDBreak` written, 0 read back.

Both were caught in seconds by the round-trip bench below, and neither by compilation.

## 3bis. A rename sweep that greps for STRINGS misses three shapes

The v2 rename compiled clean, passed every gate, and still shipped two live bugs. Both hid in shapes
a `"data-u="`-style search cannot see:

**Escaped regexes.** `ocd.js` rewrites the page's external glyph refs into local ones for the
thumbnail path, and matched `/…f\.svg#/` — the file name with a backslash in the middle. After the
`f.svg` → `fonts.svg` rename the substitution stopped firing, the thumbnail blob kept references to
a file it did not carry, and **every rail thumbnail lost all its text** while the main view stayed
perfect. Two occurrences, one of them `/^f\.svg#(.+)-(\d+)$/`. Sweep with a pattern that tolerates
the backslash: `f\\?\.svg`.

**Single-quoted attribute reads.** The client asks `el.getAttribute('data-u')`, `[data-fs]`,
`+(g.getAttribute('data-rm') || 0)` — **24 occurrences across six modules** still on the old names
after the writer had moved. Java round-trip stayed byte-stable (writing and reading are both Java),
so nothing flagged it; Trace showed no text, Redact saw no render mode, and `ocd.js`'s authoring API
still MINTED runs as `data-ocd="t" data-f data-fs data-u` — text added in Prism would have vanished
on reload.

**Comments.** `// data-fs is STATED, never inferred` and friends. Harmless to run, lethal to the
next reader, and the reason the final sweep must include `.java` and `.js` prose.

The sweep that actually closed it, run over code AND docs:

```python
for pat in [r'\bdata-tr\b', r'\bdata-ref\b', r'\bdata-fs\b', r'\bdata-u\b', r'\bdata-cl\b',
            r'\bdata-rm\b', r'\bdata-adv\b', r'\bdata-gname\b', r'f\\?\.svg\b',
            r'data-ocd="[tlpg]r?"']:
```

Word boundaries matter: `\bdata-u\b` does not match `data-unicode`, which is exactly what makes it
find the leftovers and not the new names.

## 4. The bench that must pass after ANY container change

```java
// 1. model parity + text + fixed point, per document
OCDDocument direct = PdfImporter.convert(pdf, opts);
OcdEpubWriter.write(direct, out1, opts);           // container
OCDDocument back = OCDReader.read(out1);
OcdEpubWriter.write(back, out2, opts);             // write(read(x))
// nodes(direct) == nodes(back) modulo the spliced graphics, text identical, out1 == out2 BYTE-WISE
```

Pin `SOURCE_DATE_EPOCH` or nothing is comparable. Then:

- **PDF byte-parity against the previous engine.** A container change must leave every projection
  untouched: `cmp` the PDF/HTML/MD/DocTags/SVG/EPUB outputs, old engine vs new. 30 corpus documents,
  29 identical, 1 refused by both — that is what "nothing else moved" looks like.
- **Spec vs container**: every `data-*` emitted in a page and in `fonts.svg` must appear in
  `doc/FORMAT.md`. Ten lines of Python, and it caught six stale spots the rename script could not
  reach (forms without `=`: `[data-bleed|data-trim|data-art]`, table rows listing bare attributes).
- **Browser**: serve over HTTP and count `<use>` with a non-empty box. See §5.

Known and PRE-EXISTING: GeoTopo page 35 round-trips 221 characters both ways but in a different
ORDER (`x π 2π 3π` vs `x π 2 2π 3π`) — `data-order` does not restore reading order exactly on
math-heavy interleaved runs. Identical on the pre-2026-09 engine; paint is unaffected (PDF is
bit-identical). Do not "fix" it without a measurement showing it is not this.

## 5. A page is SERVED, never opened

Glyphs are placed by `<use href="fonts.svg#…">` — an EXTERNAL reference.

| origin | `<use>` | resolved |
|---|---|---|
| `file://` | 1696 | **0** |
| `http://` same origin | 1696 | **1696** |

A page opened from an unzipped folder shows images and vector paths and **not one letter**. That is
Chromium's policy on external references, and it is what Prism's Service Worker works around. Any
test, any viewer, any conformance check goes through an HTTP origin — a double-click proves nothing.

Fidelity of the universal face, doc-A page 1 in a plain browser against the source PDF:
**0.312 % ink lost, 0.608 % extra**. The 5.5 % pixel difference is text antialiasing, not content.

## 6. What the numbers say about "making it lighter"

Every optimisation idea was measured. Almost all of them are mirages, because an EPUB is a zip and
Deflate erases repetition:

| idea | raw | compressed | verdict |
|---|---|---|---|
| explicit names instead of `p`/`l`/`t`/`g` | +2.8 % | **+0.4 %** | done, clarity is free |
| `fonts.svg` instead of `f.svg` | +4.4 % | **+0.29 %** | done |
| `fnt.svg` as a middle ground | −2.2 % | **−0.05 %** | pointless — abbreviating buys nothing |
| dropping the default `x="0"` | −0.4 % | −0.3 % | done, it was pure redundancy |
| glyph `href` local to the page | −9.8 % | −0.7 % | no — needs the fonts inlined per page |
| one element per run, positions in a list | −27.9 % | −4.1 % | **no**, see below |
| real OTF fonts instead of SVG outlines | −56 % raw | **±0 %** | no — 309 KB vs 311 KB in the zip |

**The DOM is the real cost, and a compact format does not fix it.** A page is 2320 elements and
**10135 DOM nodes**, of which the 1696 `<use>` are 85 %; they cost ~35 ms of style recalc and about
a third of the load time. But a compact per-run representation **must be expanded back into
`<use>` to display** — the nodes reappear, built by JS across the bridge instead of by the native
parser in one pass, which is likely slower. The 85 % is only winnable by NOT mounting nodes: render
visible pages only, use `shot.js` images for thumbnails. That is a viewer decision, not a format
one.

Measuring DOM cost (CDP, via Playwright):

```js
const cdp = await page.context().newCDPSession(page);
await cdp.send('Performance.enable');
const m = Object.fromEntries((await cdp.send('Performance.getMetrics')).metrics.map(x => [x.name, x.value]));
// m.Nodes · m.RecalcStyleDuration · m.LayoutDuration · m.JSHeapUsedSize
```
Alternate the two variants and take a median of three: node counts and style times swap between
runs of the SAME page, so a single measurement proves nothing.

## 7. The format owes no backward compatibility — while that holds

Stated by Jean-Luc, 2026-09-12: no third party stores `.ocd.epub`, consumers take PDF. So when a
stored form is wrong, FIX THE FORM — no dual-reading reader, no version flag, no migration;
containers are regenerated from their source, which is always the PDF. This licence is what made
the v2 rename cheap. **It expires the day someone stores `.ocd.epub` of their own** — ask before
assuming it still holds.

Same reasoning settled `.ocd.epub` vs a house `.ocd` format: a custom container would save a few KB
of EPUB manifest on a 30 MB file and would cost the property that any browser and any reader opens
it, `epubcheck` as a free gate, and — the real loss — a page being self-contained DATA rather than
something an assembler has to be trusted to build.
