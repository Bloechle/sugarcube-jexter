# production-audit — the pre-release bench (what was run for v1.0.0, rerunnable)

Seven passes over a stress corpus; every one must be green before a public ship.
`scripts/production_audit.sh` drives A–G end to end; H (clients) is the Playwright
recipe below. Findings history: this bench caught the NCX playOrder bug (14/14
EPUBs failed epubcheck while README claimed "epubcheck-validated") and confirmed
the FP-residue svg gap as benign.

## The stress corpus (scripts/make_corpus.py)

Seven PDFs, one risk each: `rotated` (0/45/90/180/270°), `columns` (2 columns +
French accents/ligatures œ æ ç û), `graphics` (alpha overlap + clipped axial
gradient + dashed rule), `images` (PNG-alpha checker + JPEG), `landscape`
(42 pt + 4.5 pt + external link), `book` (2 pages, 3-level outline → exercises
the bookmark structure path), plus the real `welcome.pdf` from the repo
(mixed fonts, gradients, image — the worst fidelity case, keep it in).

## The passes

- **A · Matrix** — 7 docs × 7 targets (`md doctags html svg epub ocd.epub pdf`),
  rc=0 and non-trivial output. Caution: a size floor misfires on near-empty
  docs — `graphics.md` is legitimately 52 bytes (its whole text). Judge content,
  not bytes.
- **B · Round-trip** — `x = rt2 = rt3` (double round-trip), all members
  byte-identical via `diff -rq` on the unzipped trees.
- **C · Idempotence** — `pdf→X ≡ pdf→ocd→X` for md/doctags/html byte-exact;
  svg is the known-benign FP-residue exception (see SKILL §7).
- **D · Validity** — re-exported PDFs reopen under PDFBox (pages + text; the
  DEFAULT export is the outline facsimile ⇒ extracted text is EMPTY by design —
  gate text through `--selectable`, and check the accents survive:
  Première/œcuménique/garçon/dûment). SVG: strict XML parse. HTML: doctype +
  single body + charset. EPUB: OCF (mimetype first, STORED) + spine members
  present + official epubcheck «No errors or warnings» on all 14.
- **E · Pixel fidelity** — PDFBox arbitrates both camps: render original and
  re-exported PDF at 96 dpi, count pixels with channel Δ>8. v1.0.0 baseline:
  0.000–0.044 % (worst: welcome, gradients), maxΔ ≤ 29. Regressions of an
  order of magnitude are real bugs.
- **F · Reproducibility** — `SOURCE_DATE_EPOCH=…` pinned, two runs, all 49
  outputs `cmp`-identical.
- **G · Hostile inputs** — not-a-pdf, empty file, truncated pdf ⇒ rc≠0 and NO
  output file; the XXE-forged ocd.epub dies with «DOCTYPE is disallowed».

## H · Client bench (Prism + PDFInspector under real Chromium)

The sandbox blocks `cdn.jsdelivr.net`, and WebApp rewrites the stack to its
`/cdn/` proxy (server-side fetch → also blocked → 502). **Seed the proxy's disk
cache instead** — it serves cached files without fetching:

- cache root `~/.jexter/cdn/<rel>` + sibling `<rel>.ct` content-type files;
- text assets must be stored POST-rewrite (`https://cdn.jsdelivr.net/` → `/cdn/`);
- sources: qry-js from `raw.githubusercontent.com/Bloechle/qry-js/<tag>/…`,
  Shoelace/lucide/@fontsource/fflate as npm tarballs from `registry.npmjs.org`
  (seed the ENTIRE shoelace `cdn/` dir — the autoloader imports chunks; fflate's
  jsdelivr `+esm` ≈ the tarball's `esm/browser.js`);
- `scripts/seed_cdn_cache.py` does all of it.

Then: launch the app from the jar **inside the same python process**
(`subprocess.Popen` + kill at the end — backgrounded shells die between
bash_tool calls), parse the port from its log, drive Playwright: open a corpus
PDF via `set_input_files`, flip tabs/sources, listen to `pageerror`, and probe
the DOM (glyph `<use>` counts + `getBoundingClientRect().width > 0` proves
glyphs actually paint). Screenshots: judge by PIXEL SAMPLING (`PIL.getpixel`),
not by eyeballing the downscaled preview — a dark theme once looked "white" in
preview while the pixels read (19,28,45).

## Client-side rendering contracts (hard-won)

- **Stored pages reference glyphs EXTERNALLY** (`<use href="f.svg#fN-gid">`).
  Prism's Service Worker serves pages at real URLs → relative resolution works.
  ANY consumer that injects the page svg inline (`innerHTML`) has no base URL →
  `f.svg` hits the server root → 404 → text invisible while graphics render.
  `ocd.js pageSvg()` therefore localizes refs (`f.svg#` → `#`) and embeds the
  `pages/fonts.svg` `<defs>` into the page string — keep that behavior; images get
  the same treatment via blob: URLs, and for the same reason.
- **Named access shadows APIs** — a frame's `<div id="stage">` IS
  `contentWindow.stage` until stage.js assigns the real object. Guards must
  duck-type (`typeof s?.relayout === 'function'`), in the ONE accessor
  (`stageApi()`), never per call site.

## Release-flow facts (repo side)

Single-commit public history: fixes fold in via `git commit --amend` +
`push --force-with-lease` + delete/re-push the `v1.0.0` tag. `_` prefix = never
committed (no exceptions; `nimbus-jexter.cjs` is tracked and unprefixed). The compiled
overview PDF is a GitHub release asset, never committed. pom version must match
the tag (no `-SNAPSHOT` under a release tag).


## Wild-corpus pass (77 real-world PDFs — the diversity gate)

`scripts/fetch_wild_corpus.sh` assembles ~77 PDFs / 23 MB from four reader test
suites: py-pdf/sample-files (curated: forms, Arabic, CCITT/LZW/ASCII85, CMYK,
PDF/A, passwords, XMP), pdf-association/pdf20examples (true PDF 2.0: offset
start, incremental save), openpreserve pdfCabinetOfHorrors (encrypted, corrupt,
JP2K, embedded video, signed 3D, non-embedded fonts), and targeted mozilla/pdf.js
files (Type3, CID, DeviceN, patterns, transparency — many repo entries are
`.link` stubs, ALWAYS verify the `%PDF-` magic). GitHub tree pages list real
filenames when the API is rate-limited.

Pass structure and v1.0.0 results:
- **Pass 1 — convert all** (PDF→ocd.epub, 30 s timeout each). Triage: converted /
  clean refusal / CRASH-or-partial. v1.0.0: 73 / 4 / **0**. A refusal is
  legitimate when the robustness reference agrees — pypdf also rejects
  `unreadablemetadata.pdf` (its page tree root is invalid, not just its XMP).
- **Pass 2 — round-trip the converted** with three-way triage: byte-stable /
  benign drift / broken. *Benign drift* = members differ but `md(x) == md(rt)`
  (semantic reading order intact) AND rt=rt2 (read is the fixed point): the
  same laundering class as the FP residue — in-memory import isn't canonical,
  one cycle is. v1.0.0: 66 byte-stable · 5 benign drifts (GeoTopo + habibi×3 +1)
  · 0 broken.
- **Pass 3 — epubcheck sample** across codec/feature diversity. v1.0.0: 24/24
  clean (after regenerating pass-1-era files with the fixed jar).
- **Pass 4 — pinned determinism sample.** v1.0.0: 8/8.

### Bugs this corpus caught (both fixed at ONE authority)

- **XML-invalid unicode from CID fonts.** Fonts without a usable ToUnicode map
  yield U+0000 (habibi/Arabic) and even U+FFFF (arial_unicode_ab) — the writer
  emitted them raw into `data-u`/run text/OPF `dc:title`, and the strict reader
  (and epubcheck) died on the produced file: write(x) unreadable = the hardest
  invariant break. Fix: `JxText.xmlChar` (the XML-1.0 predicate) +
  `JxText.sanitize` (invalid → U+FFFD, **length-preserving** — the glyph↔char
  alignment of runs must survive), applied at the model boundary
  (`PdfStreamEngine.showGlyph` + font capture) with a skip-invalid safety net in
  `JxText.esc` for every other serialized string (that net alone caught the OPF
  title NUL). Never sanitize by DELETING chars in run text: that desyncs
  glyph↔char and turns benign drift into instability.
- **data-o index drift** (GeoTopo class): import-time in-memory content lists
  differ from read-back lists (group flattening), so first-write `data-o`
  indices shift by a constant after one cycle. Reading order is SEMANTICALLY
  preserved (md identical) and read is a fixed point → benign, same future fix
  as the FP residue (canonicalize import), never a drive-by.


## Wild round 2 (108 heteroclites: pikepdf, safedocs, qpdf, veraPDF, pdf.js v2)

v1.0.0 results: pass 1 — 98 converted · 10 clean refusals (encrypted incl.
safedocs' unicode-SASLprep AES-256 password tests; qpdf's deliberately damaged
files) · **0 crashes/timeouts/partials**. Pass 2 — 92 byte-stable · 6 benign
drifts (md identical) · 0 broken. epubcheck 13/14 → the failure was a real bug
(below). Determinism 6/6. Semantic probes: vertical Japanese text extracts in
order, cyclic outline does not loop, JBIG2 decodes to PNG, Type3-inside-Type3
renders.

### Bug caught: pageless documents produced invalid shells

A PDF whose page tree is cyclic/broken (pikepdf `cyclic-toc`, `newline-buffer-
test`, headerless decoys) parses to **0 pages**, and every writer happily
emitted a structurally invalid husk (empty spine, nav pointing at a page that
doesn't exist — epubcheck RSC-005/RSC-007). Fix at the ONE load authority
(`Conversion.load`): a pageless document is refused with «Document has no
pages» — rc≠0, no output — while a 1-page document with empty contents
(safedocs `PDF-NoPageContents`) still converts and validates. Sweep trick: scan
every produced ocd.epub for the absence of `pages/page-*` members to find all
shells at once.


## Feature matrix (systematic rendering coverage — scripts/make_feature_matrix.py)

Wild corpora give opportunistic coverage; the feature matrix is systematic: 11
hand-crafted minimal PDFs, one rendering feature each (see the script header for
the list). Protocol: (0) gate every generated PDF with PDFBox first — opens AND
renders non-blank ink (this caught the mesh-coordinate encoding trap); then per
feature: conversion rc=0, round-trip byte-stability, and pixel fidelity
(original vs re-export, PDFBox arbitrating both at 96 dpi).

v1.0.0 baselines (% pixels Δ>8): blend-modes 0.004 · colorspaces 0.011 ·
font-encodings 0.055 · font-type3 0.004 · image-masks 0.006 · shading3-radial
0.003 · shading4-mesh 0.63 · smask-luminosity 0.82 · text-state 1.42 (facsimile
glyph AA — benign) · vector-ops 0.013 · pattern-tiling 10.6 at 96 dpi **but
0.203 at 144 dpi**: judge raster fallbacks at the raster's NATIVE dpi — on a
high-frequency checkerboard, 144→96 resampling alone reads ~10% while geometry
is exact.

### The four bugs this matrix caught (all fixed)

- **Type3 glyphs vanished entirely** (2.7% → 0.004; real-world franz.pdf text
  restored). PDFBox's DEFAULT `showGlyph` is the type3 DISPATCHER (`instanceof
  PDType3Font → showType3Glyph`); overriding `showGlyph` severs that route
  silently. Fix: re-dispatch explicitly at the top of the override, and
  `showType3Glyph` processes charprocs through THIS engine (`flushText()` first
  for z order) — the glyph marks land as regular vector content. Trade: Type3
  letters are ink, not extractable text (documented).
- **Stencil images painted black** (image-masks 8% → 0.006): an ImageMask has
  no colours — use `pdImage.getStencilImage(new Color(fillArgb(), true))`, not
  `getImage()`.
- **Tiling patterns fell to the gray-128 fallback** (64% → correct) and
  **ExtGState /SMask was ignored** (50% → 0.8): both now route through ONE
  reference-raster fallback — `needsReferenceRaster()` (tiling pattern fill, or
  any paint under a soft mask) → `emitFromReference(area)` crops the lazily
  PDFBox-rendered page raster (2 px/unit, 4 MP cap — the mesh-shading budget)
  clipped to fill∩clip and emits an OCDImage. Same principle as mesh shadings:
  "PDFBox is the reference rasteriser". Z order preserved (later content still
  covers); content painted BELOW a semi-transparent fallback region gets baked
  in — the documented trade.

Verified green elsewhere without changes: blend modes, CalRGB/Lab/Separation/
Indexed, WinAnsi/MacRoman/Differences encodings, image SMask + 16-bit
(`PDImageXObject.getImage()` handles both), full text state incl. Tr1/Tr2
stroke text, even-odd fill, caps/joins/miter, v/y curves.


## Staged rendering bench ("test every stage, like PDF Inspector")

Fidelity on `pdf→pdf` alone can hide symmetric losses. The staged bench renders
FOUR representations of the same document and cross-compares them all:
A = PDFBox(original) · B = PDFBox(direct pdf→pdf) · **C = Chromium render of the
STORED page-001.xhtml svg** (localize `f.svg#` refs + inline the f.svg defs like
ocd.js, size the div at pt×96/72 px) — the PDF Inspector OCD view, mechanised ·
D = PDFBox(pdf→ocd.epub→READ→pdf). A↔D proves the full write+read chain; B↔D=0
proves write/read is a lossless pair; A↔C isolates the stored-SVG projection
(expect 1-3% Chromium-vs-PDFBox AA on thick strokes — judge structure by eye).

Three subtlety stress docs drive it (400×400, uncompressed streams): caps
butt/round/square, joins miter/round/bevel, miter-limit 1.5 (forces bevel),
dasharray simple/complex/**non-zero phase**/on-curve, 0-width + 0.25-width
hairlines; W and W* clips on a two-subpath ring, NESTED rect∩circle clips,
bezier-only clip; self-intersecting star f vs f*, double-square B vs B*, open
path b. v1.0.0: attribute audit all-present (class-based CSS: linecap/linejoin/
miterlimit/dasharray/dashoffset), write/read styles byte-identical, stages
A↔B=A↔D=0.00-0.16%, B↔D=0.00%.

Two findings worth their own lines:
- **Clip rules are resolved geometrically at import** (java.awt.Area): a W* ring
  stores as a two-subpath clipPath, a same-winding nonzero "ring" as the full
  disk — correct by construction, no clip-rule attribute needed. Don't "fix"
  its absence.
- **PDF 0-width = thinnest device line**, but SVG width-0 paints nothing and
  the CSS default 1 over-inks. ONE import convention (`PdfStreamEngine.pen()`,
  PdfStreamEngine): width < 0.05 → 0.75 pt (≈1 device px at 96 dpi), applied at
  all four stroke sites so every projection agrees and B↔D stays 0.


## OCD-EPUB as a real fixed-layout EPUB + the pdf return (epub side of the bench)

Two additions close the question "did you verify the epub output and the return
to pdf?":

**Reader-readiness test (mandatory — and serve it over HTTP):** unzip the
ocd.epub, `python3 -m http.server` in the unzip dir, open
`OEBPS/pages/page-001.xhtml` in Chromium and probe
`use.getBoundingClientRect().width > 0`. v1.0.0 result: **1339/1339 glyphs
painted with fully SHARED fonts** — modern Chromium (Interop 2023, verified on
141) resolves external `<use href="f.svg#…">` targets same-origin over HTTP,
which is how every web-engine EPUB reader serves members (Thorium/Readium =
internal localhost). The shared `pages/fonts.svg` library IS the design: one copy
of every glyph, `glyphPath(...)` the one glyph-markup emitter, zero per-page
duplication.

⚠ FALSE-NEGATIVE TRAP: the same probe over `file://` paints **0/1339** — not a
missing feature but Chromium's opaque-origin isolation (every file:// file is a
distinct origin, so the f.svg fetch is cross-origin). Never conclude from a
file:// harness; a per-page glyph-subset "fix" was built and REVERTED on this
mistake. The one legitimate inline-defs path remains ocd.js `pageSvg()` for
innerHTML consumers (no base URL there), guarded by `data-ui="fonts"` absence.

**The pdf return is stage D** (`pdf→ocd.epub→READ→pdf`), and running it on
welcome (a page whose paint order ≠ reading order, i.e. with data-o tags)
exposed a REAL open bug the subtlety docs could not see (their B↔D=0.00 because
paint==reading there):

- Ruling (Jean-Luc): for the pdf return, PAINT order wins over reading order —
  which is already the format contract ("paint order = document order").
- Symptom: welcome A↔D 1.318% — icon connectors covered by their tile, the
  gradient banner painted as a flat dark bar. RESOLVED to A↔D 0.045% /
  B↔D 0.002% (equal to the direct chain) by THREE fixes:
  1. **Writer: transparent `gr` wrappers are SPLICED** (`spliceGraphics` in
     SvgOcdWriter.children). A registration-order probe on the read model
     proved the reader innocent — the WRITER had emitted analysis OCDGraphic
     wrappers as atomic `<g>` blocks at the group's z, silently inverting the
     paint interleave IN THE STORED FILE (visible in Chromium too, not just
     the return). Now: render-transparent graphics (identity transform, no
     clip/blend, opaque) emit their children individually at their own z —
     exactly like `OCDNode.inPaintOrder` paints; groups carrying a paint
     context stay atomic; `data-o` indexes the same spliced list on both
     sides. Analysis grouping is import-internal and not serialized.
  2. **Reader: `spreadMethod` honoured** — `pad` (or absent, the SVG default)
     maps to PDF Extend [true true]; it was hardcoded false/false. The writer
     always emits pad; visually neutral for non-extended sources because the
     import already clips the fill AREA to the axis span.
  3. **Reader: gradient paths carry their flat fallback** —
     `p.fill(g.flatArgb()).fillGradient(g)`, mirroring the importer. Without
     it the flat fill stayed 0x00000000 and PdfWriter's
     `fillA = alpha × a(fill)` emitted `/ca 0` — the gradient painted fully
     transparent (the "flat dark bar" was the band BEHIND showing through).
- KEY logical traps, both proven here: (a) `write(read(x)) = x` byte-stability
  does NOT prove read-model == import-model — unserialized import state
  serializes identically from both sides; stage D on a doc whose paint order ≠
  reading order (welcome) is the only probe that sees it. (b) A "reader bug"
  can be a writer bug: probe the REGISTRATION ORDER of the read model before
  blaming the traversal.
- After: subtleties B↔D 0.000% ×3, synthetics A↔D 0.000-0.045% with md
  idempotence 7/7, wild sample 8 stable + 6 benign drifts + 0 broken,
  epubcheck 3/3, reader HTTP probe 1339/1339. PdfWriter's group case also
  pushes `g.transform()` around children (correct, kept).


## Press corpus: word-space derivation (the "fonts & encoding" bug that wasn't)

A real 2010 newspaper (La Liberté, 40 pages, 591 subset fonts, condensed press
faces) glued words everywhere ("Testerdesmédicaments"). Extraction quality is
judged against PDFBox's PDFTextStripper as vocabulary reference, measured in
BOTH failure directions: glued (our words ≥11 chars unknown to the reference)
and lost/over-split (reference words ≥8 absent from ours). ⚠ harness traps:
the reference must cover ALL pages, and PDFTextStripper output must be written
as UTF-8 by Java itself (a C-locale console turns every accent into '?', which
silently empties the accented vocabulary). Baseline: 547 glued / 250 lost.

Three root causes, three authorities (final: **315 / 206**, all headline and
Tz cases fixed, remaining = agate columns + letterspaced caps + line-join
class, logged for later):

1. **`OCDFont.spaceWidth` was never derived at import** — every font sat on
   the 0.25 em model default. Now `FontExtractor.capture` reads the face's
   real space (`PDFont.getSpaceWidth()/1000`, present in /Widths even for
   subsets), and `FontExtractor.finish()` (called once after the page loop)
   re-derives implausible values — PDFBox falls back to average/missing width
   (0.5-1.0 em: threshold so high nothing ever splits) — from the face's own
   median glyph advance: 0.5×median clamped to [0.12, 0.30] em.
2. **`Spacer.SPACE_FRACTION` 0.5 → 0.28, read off the data**: the full-corpus
   slack/sw histogram (244k seams) shows the kerning cluster dying at +0.05,
   a valley in [0.075, 0.10], and ~2100 true word gaps in [0.10, 0.40] that
   0.5 (and even 0.4) left glued. Justified columns compress word spaces well
   below the face's nominal.
3. **PDF Tz (horizontal scaling) broke the slack scale** — press headlines run
   at Tz 93-97%; dividing horizontal x-deltas by the VERTICAL font size
   under-reads every gap by exactly Tz (measured 0.045 vs 0.062 em: just under
   the gate, whole titles glued). The run transform carries the anisotropy
   whatever its normalisation (unit-em at import: [0.93 0 0 1]; full matrix
   after read), so the scale-invariant ASPECT |T·ex|/|T·ey| corrects
   `fontSize` on both sides. Naively using |T·ex| alone as the scale mistakes
   the unit-em form for points and over-splits catastrophically (8 glued /
   5125 lost — always measure the OTHER direction).

Debug recipe that cracked it: a read-model probe recomputing per-seam slack
(font, sw, adv, ratio) on a named glued run, then the same numbers printed
from INSIDE Spacer under a sysprop — the two disagreeing on `size` (65 vs
62.7) exposed Tz in one glance.


## Font audit vs PDFBox + the standalone-target contract (press page 1)

**How PDFBox gets it right** (source audit): `GlyphCache → font.getNormalizedPath(code)`
— FontBox parses the EMBEDDED program (Type1/CFF/TTF) into outlines; system fonts
never touch embedded text. Non-embedded → FontMapperImpl substitutes (bundled
Liberation) AND PageDrawer **width-stretches each glyph** to the PDF's declared
advance (`at.scale(pdfWidth/fontWidth, 1)`) so spacing stays exact under
substitution. Our parity: FontExtractor uses the same `getNormalizedPath`;
`stretch()` now applies the same per-glyph factor to the captured outline, once,
so every projection agrees (Utopia non-embedded on the press page renders
perfectly).

**The standalone-target contract**: a lone `.svg` has no `../fonts/` or
`../images/` siblings. Text with `font-family` + per-glyph x arrays falls back
to a SYSTEM face — correct positions, wrong glyph widths ⇒ the "uneven
tracking" artifact; external image hrefs silently VANISH (measured: every photo
of a front page — reported as "text disappearing"). Cure, one authority:
`SvgWriter render(..., inlineFonts)` embeds `data:font/otf` @font-faces
(compiled by the same JxFont.toOtf as EPUB) and `data:image/*` hrefs (the
`\u0000inline` sentinel in `faces` carries the mode to the image emitter).
Packaged pages (EPUB) keep shared external files. Judge with the INK-CELL SCAN:
render S at device_scale_factor 4/3 to match 96 dpi, compare 16-px cell ink
densities vs A — robust to sub-pixel offsets, merciless on vanished content
(1374 cells → 0). Per-pixel diff after a resize reads ~35% on pure resampling:
never conclude from it.

### ⚠⚠ THE STALE-JAR TRAP (cost: a full misdiagnosis arc)

`node nimbus-jexter.cjs --run=jar` stages classes incrementally under
`target/jar-stage`; a source edited AFTER its last staging ships STALE without
any warning (found: SvgWriter.class 20:48 vs SvgWriter.java 21:11 — the whole
inline-fonts feature existed in source and was absent from every jar of the
session). Before ANY measurement session: `rm -rf target && node nimbus-jexter.cjs
--run=jar`, then PROVE the surface with
`javap -cp _prod/sugarcube-jexter.jar -p <touched class>`
(check the new method signatures exist). A behavior probe on one edited class
does not clear the others.

### Open, bounded: white knockout text lost on read (press p1)

p1 A↔B 0.001% but A↔D 1.213%: the «SOMMAIRE» white-on-black run vanishes in
the read chain (black tile survives). Reproducer: Pages_from_Liberte p1, crop
y≈1258, B vs D renders. Same hunting kit as the z bug: stream forensics +
model probe on the read side. Rest of the band = barcode/photo AA (benign).


## Absent fonts: PDFBox's resolution chain, and why we already harvest it

**The chain** (FontMapperImpl, source-verified): ① exact PostScript name against
the system FontProvider (PFB/TTF/OTF scan, disk cache `~/.cache/pdfbox`); ② the
substitutes table (Arial↔Helvetica↔LiberationSans families, Courier↔
LiberationMono, Times↔LiberationSerif…); ③ `getFallbackFontName` from
DESCRIPTOR FLAGS — fixedPitch→Courier, serif→Times, else→Helvetica, with
bold/italic guessed from the name ("bold|black|heavy") and flags; ④ ultimate:
`/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf` bundled in the
jar. `scripts/MapProbe.java` interrogates `FontMappers.instance()
.getFontBoxFont(base, descriptor)` per page font and prints the mapped
program + `isFallback()` — run it whenever a doc's absent-font rendering needs
explaining.

**Measured on the press page**: 13/14 fonts embedded; the absent
`Utopia-Regular` mapped to… a REAL Utopia Type1 found on the system
(texlive-fonts-recommended `put*8a.pfb` — Adobe's freely-licensed Utopia ships
with TeX/Ghostscript). Hence PDFBox's perfect body serif — and OURS: 
`FontExtractor.outline → getNormalizedPath` traverses **that same mapping**, so
f.svg holds 69 genuine-Utopia glyph outlines (68 with real contours). On a
system WITHOUT it, ③ yields Times→LiberationSerif and the per-glyph
width-stretch keeps spacing exact.

**"Can't we just grab the font file PDFBox resolved?"** We can (the
FontBoxFont and its source are reachable), but it is the WRONG embed for our
targets: a raw @font-face cannot apply PageDrawer's PER-GLYPH width-stretch
(`scale(pdfWidth/fontWidth,1)` differs glyph by glyph), so `<text>` at our
exact x-arrays over the raw substitute would re-create the uneven-tracking
artifact. Our capture bakes the stretch INTO each outline and recompiles a
used-subset OTF — pixel-what-PDFBox-paints, embeddable everywhere, and it
ships only the glyphs actually used (the same subsetting posture as PDF
producers, kinder on licensing than redistributing whole system font files).
