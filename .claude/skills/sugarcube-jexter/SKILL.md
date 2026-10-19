---
name: "sugarcube-jexter"
description: "Dev + fidelity + release skill for the Sugarcube jexter document engine — one model, OCD, the Open Canonical Document (PDF ↔ OCD-EPUB / EPUB / HTML / SVG / Markdown / DocTags, PDFBox 3.x, JDK 21 floor, repo Bloechle/sugarcube-jexter). DEV — bootstrap a JDK in the JRE-only sandbox, reproduce the CI gate, run the CLI, the apps and the functional gates. UI — Prism's tool architecture and the Chromium bench (references/ui-seam.md). CONTAINER — the OCD-EPUB grammar and its traps (references/ocd-container.md). FIDELITY — the probe points, the surfaces no gate covers, pixel and text comparison against PDFBox, poppler and MuPDF, the engine traps already paid for. RELEASE — the recipe and the commands for Jean-Luc's machine. Use when working on jexter, the OCD model, the analysis pipeline, write.* projections, Jx*/OCD* classes, the client, or any fidelity / round-trip / release question. Lives in the repo, updated by commit."
---

# jexter — development + fidelity workflow

The card **`AGENTS.md`** (package map, conventions, gotchas), **`README.md`** and the four documents
under **`doc/`** — `FORMAT.md` (the OCD-EPUB container and the SVG-OCD grammar), `ANALYSIS.md` (the
geometry-first pass), `FRONTEND.md` (the convert contract, the Prism tool architecture), `TRACER.md` —
say what the project IS. This skill says how to WORK on it: the procedures and the traps. It does
not restate the card. Where any document and the source disagree, the source is right:
`ocd/analysis/Analysis.java` owns the stage order, `Conversion.java` the target registry, `Gates.java`
the gate list.

> **PUBLIC REPOSITORY.** No customer document, name, contact or price, no private host or service, no
> market note — in code, docs, commit messages or this skill. A customer case is named by what it
> shows ("a press cover", doc-A…). The private notes (market audit, consumers and their contacts) live
> in the account skill `repo-skills`.

For any **Prism / client** task, read **`references/ui-seam.md`** first. Before touching the
**container**, read **`references/ocd-container.md`**.

## Already built — look here before writing a harness

A session once set out to build "a renderer to compare PDF outputs visually": it existed, but its
documented command had rotted. **If a command below fails, fix the command, never replace the tool.**
Paths under `scripts/` and `references/` are relative to this skill's folder,
`.claude/skills/sugarcube-jexter/`.

| you want to… | it exists |
|---|---|
| every functional gate over the fixture corpus — what CI runs | `node nimbus-jexter.cjs --run=test` → `test/Gates.java` |
| which container member drifted, in context | `test/ContainerDiff.java` |
| a candidate against the PDFBox reference, pixel for pixel, with a heatmap | `scripts/compare.py` (+ `RenderPdf.java`, `RenderOcd.java`, `render_html.py`) |
| raster the MODEL, from a PDF or an `.ocd.epub` | `scripts/RenderOcd.java` |
| text read-out vs PDFBox's extractor: characters lost AND invented | `scripts/TextFid.java` |
| links, fields, annotations, layers, outline across a trip | `scripts/Rich.java` + the `rich` fixture |
| a customer batch: source vs delivered vs new, poppler + MuPDF, per page | `scripts/batch_score.py` |
| the real Prism in Chromium against the real engine | `scripts/bench/` (`prism_bench.mjs` + the scenarios, its `README.md`) |
| one container page in a real browser, overlaid on the reference | `scripts/ocd_page_bench.mjs` |
| model / source probes: fonts, glyphs in a band, images, colours, placement | `FontProbe` · `OcdFontProbe` · `MapProbe` · `GlyphDump` · `DrawProbe` · `AreaProbe` · `ImgProbe` · `ColorProbe` · `PlaceProbe` |
| PNG predictor rows of every image (Up/Average/Paeth → exit 1) | `scripts/predictor_rows.py` |
| text/structure comparison against docling, DocTags, OCD-AI | the `docling` skill (`.claude/skills/docling/`) |

## 1 · Setup (the sandbox ships a JRE only)

```bash
bash scripts/setup.sh      # JDK 21 + python libs + qpdf/poppler + wkhtmltoimage + DejaVu/IPA fonts
export JAVA_HOME=/opt/jdk21/usr/lib/jvm/java-21-openjdk-amd64; export PATH="$JAVA_HOME/bin:$PATH"
```

Detail and dead ends: `references/jdk-sandbox.md`. Each shell call is fresh: re-export, or use
absolute paths; `bash`, not `sh`.

- **JDK 25** (Jean-Luc's desktop JDK; it sees the JDK 22+ lints): `https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25%2B36/OpenJDK25U-jdk_x64_linux_hotspot_25_36.tar.gz`
  → `tar xzf` → `/tmp/jdk-25+36/bin/javac`. Its tarball has no `jmods/` (jpackage: see the `nimbus` skill).
- **PDFBox's source settles "us or PDFBox?"** — `https://raw.githubusercontent.com/apache/pdfbox/trunk/pdfbox/src/main/java/org/apache/pdfbox/…`
  (`rendering/PageDrawer.java` is the reference renderer). `trunk` works, a version path 404s,
  `api.github.com` answers 403. Read `PageDrawer` line by line before reasoning about what a renderer
  "should" do: it tells a bug from a deliberate guard (PDFBOX-4949: "don't clip if `W n` only").
- **Maven Central is blocked, GitHub is not**: a Java dependency whose source builds without a build
  tool can be compiled from `codeload.github.com`; otherwise fetch the source and type-check against
  stubs copied from it — and say which one you did.
- **Chromium** is in the sandbox (`/opt/pw-browsers`); only `playwright-core` needs installing.
- **`java Foo.java` cannot reach package-private members** of classpath classes (its own class
  loader → `IllegalAccessError` at RUN time). A harness that needs one is compiled into the tree's
  output instead.

## 2 · Get, build, gate

```bash
git clone --depth 1 https://github.com/Bloechle/sugarcube-jexter     # public; the clone IS the hand-off
node nimbus-jexter.cjs --run=test                                    # compile + corpus + every gate, ~40 s
```

The two vendored jars are in `lib/` (`pdfbox-app-3.0.7.jar`, shaded; `jbig2-imageio` — without it a
JBIG2 scan decodes to nothing and the pixel gate blames the renderer). The engine is **AGPL-3.0,
dual-licensed**; the jars are Apache-2.0 — never infer the project's licence from its dependencies.

**Reproduce CI before every push** (`.github/workflows/build.yml` is the authority):

```bash
find src -name '*.java' > /tmp/srcs
javac --release 21 -Xlint:all -Werror -encoding UTF-8 -cp "lib/*" -d target/classes @/tmp/srcs      # whole tree, jj2000 included
grep -v '^src/jj2000/' /tmp/srcs > /tmp/ours
javadoc -quiet -Xdoclint:all,-missing -encoding UTF-8 -cp "lib/*" -sourcepath src -d /tmp/doc @/tmp/ours
/tmp/jdk-25+36/bin/javac --release 21 -Xlint:all -Werror -encoding UTF-8 -cp "lib/*" -d /tmp/c25 @/tmp/srcs
```

plus the workflow's client steps (every `src/**/*.js` parses as a module, no temporal dead zone, every
`data-tab` resolves, local links resolve), the functional gates and the skill scripts' compile.

- **doclint is a gate, not a nicety**: it resolves every `{@link}`, so a rename breaks the build
  instead of rotting. What no tool sees is a MISATTACHED comment — delete a method and its doc
  silently describes the next declaration. Read the comment above anything you delete.
- **A file-header comment is `/*`, never `/**`**: JDK 23+'s `dangling-doc-comments` cannot be
  suppressed, and its flag is invalid on JDK 21.
- **A colon followed by a space in an unquoted YAML step name opens a mapping**: the workflow file
  becomes invalid and CI validates NOTHING, in silence. Explanations go in a comment above the step;
  parse the file before pushing a workflow edit.
- **The JDK floor has five homes** — `.env RELEASE`, `build.yml`, `pom.xml`, README, the card. All or none.
- **`javac` only reads `.java`**: a stray non-Java file under `src/` rides into every jar as a
  resource. Before a release, look at what `src/` holds that the compiler never saw.

The gates (`test/Gates.java`, one JVM, `SOURCE_DATE_EPOCH` pinned by the deck — Gates refuses to run
without it): REPRO · DOCTAGS · STABLE · MODEL · TIERS · NAV · TAGS · PIXELS · FURNITURE · AGAIN ·
STRADDLE · CLIPS · COPY · DRAWN · EDITED · HOSTILE, each described in the file's header. **A change is done
when they are green; a new defect class gets its check there.** Every check reports through the exit
code — a gate that prints a report someone greps can be wired never to go red, and two once were.

- A red **STABLE** says a byte moved: `test/ContainerDiff.java` says which member, in context.
- A red **MODEL** with a green STABLE is the interesting case: a field written wrong and read back
  wrong the same way. Reach for MODEL whenever a field is DERIVED rather than read.
- The corpus is `test/Fixtures.java` (PDFBox only): one PDF per trait, plus `rich` (links, fields
  with values, annotations, two OCG layers, a two-level outline) and `tagged` (a StructTreeRoot whose
  last `/P` holds TWO MCIDs — the paragraph a per-block mapping splits). Catalog: `references/features.md`.
  A new trait is a new method there.

## 3 · Run

```bash
CP="target/classes:lib/*"
# THE conversion CLI: target from the output extension or --to=; any ConvertOptions key as --key=value;
# the input is SNIFFED from its bytes (%PDF, PK, an image signature), never its name
java -cp "$CP" sugarcube.jexter.Conversion in.pdf out.ocd.epub
java -cp "$CP" sugarcube.jexter.Conversion out.ocd.epub out.md
java -cp "$CP" sugarcube.jexter.Conversion in.pdf page.svg --page=0
# normalize (PDF → OCD → PDF), headless; --hotfolder, --done, --suffix, --threads: the help
java -cp "$CP" sugarcube.jexter.ui.jx.Jexter in.pdf out.pdf
# rasters: the PDFBox reference, and the MODEL (from a PDF or a container) — both PAGE 1-based
java -cp "lib/*" scripts/RenderPdf.java in.pdf 1 ref.png 144
java -cp "$CP" scripts/RenderOcd.java in.pdf 1 ocd.png 144
```

In code: `PdfImporter.convert(file[, opts])` → `OCDDocument`; `OCDReader.read` / `readMembers` /
`readPage` / `pageCount` (the three tiers); `OCDRenderer.render(page, doc, dpi)`;
`Conversion.convert(bytes, target, opts)` → bytes. A pageless document is refused («Document has no
pages», exit ≠ 0, no file); one empty page is a valid document.

**Bases differ, and the error does not say so**: `--page=` (SVG target) is 0-based; `--redact=1:x,y,w,h`
and `--pages=` are 1-based like the rasters. `--redactFill` takes `#rrggbb` or `#rrggbbaa` (alpha last).

**The apps** — Prism 7345 · PDFInspector 7346 · Jexter 7347. To smoke-test routes here:

```bash
nohup timeout 25 java -cp "$CP" sugarcube.jexter.ui.prism.Prism --serve --port 7330 \
      --web src/sugarcube/jexter/ui/prism/web > /tmp/srv.log 2>&1 &
sleep 7; curl -s -o /tmp/out.pdf -w "%{http_code} %{size_download}\n" --data-binary @in.pdf \
      "http://127.0.0.1:7330/api/convert?to=pdf"
```

Redirect the JVM to a file, or the server inherits the tool's pipe and the call returns −1.
`/api/alive` is SSE (curl hangs) — probe `/` or a JSON route. Every route is guarded: a foreign
`Host`/`Origin` or `Sec-Fetch-Site: cross-site` gets 403 — the guard working, not the route failing.
**Never `pkill -f <pattern>` on the same command line that started the server**: the pattern matches
the shell itself and the call dies with no output. Stop servers by their saved PID.

## 4 · Verify — where a divergence comes from

```
   GT ── DECLARED : the fixture corpus (each method states its truth)
      └─ DERIVED  : a real PDF → the PDFBox reference
   (1) OCD virtual (PdfImporter)  →  (2) OCD reread (OcdEpubWriter → OCDReader)  →  (3) a projection (write.*)
```

`GT↔1` fails → the importer (`pdfimport`); `1↔2` → the codec (`ocd.io`); `2↔3` → that writer. Start
narrow (one fixture that isolates the suspected trait), then widen to a corpus: one document says
nothing about a class of defect. **Split import from writer first**: if `RenderOcd` (the model, no
writer) already shows the defect, the writer is innocent.

### The surfaces the gates do not cover

- **Text that is RIGHT, not just surviving** — `TextFid.java`. Three divergences are EXPECTED: NFKC
  folding at read-out (`²`→`2`, NBSP→space: the model keeps the codepoint, only `OCDIndex` folds), CJK/RTL
  word grouping (the geometry abstains from inventing word spaces outside LTR space-parted scripts), and
  PDFBox's own column mush on a `/Rotate` page. A `U+0000` "missing" is PDFBox emitting NUL for an
  unmapped glyph. Anything else is real.
- **The tracks** — links, fields, annotations, layers, outline ride outside the content tree, so a whole
  track can vanish with every paint and text gate green. `Rich.java` on `target/corpus/rich.pdf`. What
  each target keeps: ocd and pdf keep all (pdf drops the LAYERS), fixed epub drops external links,
  reflow/html/md/doctags keep link text only. Re-measure before promising a target.
- **A form field is live data**: no appearance stream is imported, the renderer draws a preview — a
  pixel diff confined to widget rects is expected.
- **Colour and stroke questions** are scored with **poppler AND MuPDF** too (`batch_score.py` does
  both), on the WHOLE file — a page extract drops the catalog, hence the output intent, the layers, the
  outline. PDFBox, our reference, ignores the page group's colour space, the output intent and the
  anisotropic pen: a PIXELS gate against it is green on exactly those defects. Read a disagreement
  between poppler and MuPDF as information.
- **Acrobat is a sixth engine we do not have**, and the one customers use. Five engines agreeing proved
  nothing on a page Acrobat refused (`W n` with no path). `references/pdf-forensics.md` has the install
  recipes, the font-forensics checklist and the bisecting protocol — read it before spending one of
  Jean-Luc's Acrobat clicks.
- **A downstream reader is a reference engine too**: one decoded Paeth rows wrong, so `PdfImages` writes
  Sub on every row (`predictor_rows.py` is the gate). The gates prove a PDF is RIGHT; a customer's reader
  proves it is ORDINARY — prefer the encoding the source producer used. When a consumer's output is
  available, diff its decoded assets against ours before theorising.
- **The browser** — the written page needs a real SVG engine: the Java reader and Chromium can disagree
  completely. `ocd_page_bench.mjs` serves an unzipped container over HTTP (never `file://`: opaque
  origin, external `<use>` paints nothing) at the source's pixel scale.
- **Structure recovery** — scored against the OUTLINE (bookmarks), not the tag tree (legal corpora
  file most article headers as `/P`): `StructDump.java` per document, `score_outline.py` over a corpus,
  by enumerator recall and merge errors. The corpus aggregate is the signal, a single document is noise.

### Comparing pixels

```bash
python scripts/compare.py SOURCE.pdf CANDIDATE -o cmp.png [--page N | --all] [--dpi 144] [--heatmap]
```

`CANDIDATE` is a `.png`, an `.ocd.epub` (`RenderOcd`) or an `.epub`/`.xhtml`/`.html` (WebKit). Then
LOOK at the image; `references/diagnosis.md` maps symptom → cause. Rules that cost a day each:

- Same rasterizer on both sides when judging a writer (PDFBox vs PDFBox), same dpi; deltas ≤ 8 are AA.
- **Compare a band, not the page**: crop the suspect node's bbox through each raster's OWN scale (never
  resize one to the other) and score ink coverage — 6.08 % vs 16.32 % is unmissable where a whole-page
  mean of 7.38 vs 7.21 says nothing. Aggregates hide a missing letter or QR code: localise the worst
  tile, crop source/old/new into one strip.
- **Customer page numbers lie** ("page 3" was PDF page 37): grep the text for what the report names.
- When a report and the rasterisers disagree, instrument the MODEL (`OcdFontProbe`, `AreaProbe`) and the
  SOURCE stream (`DrawProbe`, `ImgProbe`), not the picture.
- **Prove a cosmetic change is cosmetic**: convert a few documents to `ocd · epub · pdf · md · doctags`
  with `SOURCE_DATE_EPOCH` pinned, before and after — byte-identical, or it was not cosmetic.

### Performance, measured

Import ~20 ms/page, ~60 % extraction and ~40 % optional analysis (turning `detectHeaders` / `graphics`
/ `structure` off saves that share for a text-only caller); the writers are noise. Model heap ≈ 1.1 KB
per node — at document-level concurrency the binding constraint is MEMORY: size a pool off the heap
and the largest document, not the cores. **Profile, do not split phases**: JFR named a per-glyph
`String.format` worth 11 % of the import; a call count is not a cost. Warm every target before timing
(the first one absorbs class loading), report medians. **One document is single-threaded on
purpose** (PDFBox's `PDDocument` is not thread-safe; concurrency belongs at the document level, three
lanes by default) — revisit only for a single-document latency target.

## 5 · Engine traps already paid for

Each line is a defect that shipped once. The fix is in the code; the trap is for the next change.

**Rendering first, structure second.** OCD-EPUB is a faithful replica that also carries a logical layer.
A change that REMOVES content must prove it invisible — every reference engine paints nothing there and
the ink measurement shows a gain, not a trade. Measured both ways: a node clipped to NO area is dropped
(0.94 % → 0.05 % ghost ink, nothing lost); a node whose clip lies elsewhere on the page is kept (dropping
it erased 0.36 % of real ink — its bounds are in a baked group's frame).

Import (`pdfimport`)
- A glyph is blank when the FONT says so (`OCDGlyph.isSpace()`, no outline), never when `toUnicode`
  claims a space: a subset mapped a capital to U+0020.
- One predicate per partition: `glyphDef == null` decides both "not painted" and "recorded blank" —
  NBSP is not `Character.isWhitespace`, and a glyph between two predicates truncated the run's tail.
- `TrueTypeRepair` must never take the `post` NAMES away: on a TrueType with no `cmap` they ARE the code
  → glyph map; a downgraded `post` loads, maps everything to `.notdef` and paints nothing while every
  text gate stays green. `FontProbe.java` counts empty outlines per face, with and without the repair.
- Type3: any `showGlyph` override re-dispatches to `showType3Glyph` first, or every Type3 glyph vanishes.
- Non-embedded fonts are width-stretched at capture like `PageDrawer.drawGlyph` (only when non-embedded,
  non-vertical, non-standard-14, `/Widths` declares the code, both widths non-zero). Symptom when missing:
  text spaced out, glyphs narrower than their advances.
- JPEG 2000 hides in a SOFT MASK too, and the declared filter lies: `JpxImage` checks the BYTES.
- A tagged PDF's grouping element (Div, Sect) INSIDE a heading, paragraph or caption groups nothing: it
  folds into a SPAN (`TaggedStructureBuilder`). Typst writes every heading as H1 › Div › Span: kept as a
  SECTION, the heading read as nothing and every EPUB's contents said « Heading 1 » (TAGS checks it).
- Layers are read at DOCUMENT level (`/OCProperties /D /OFF`), never discovered lazily from content —
  a hidden stratum painted on every surface while the layer COUNT round-tripped.
- Pattern fills: a shading pattern's ramp is sampled; grey (128,128,128) is the fallback showing through.
- Paint the model cannot vectorise (tiling patterns, anything under an ExtGState SMask) goes through ONE
  route, `beyondVectors()` → a crop of `ReferenceRaster`; a masked glyph keeps its run as invisible text.
- Zero-width strokes map to 0.75 pt at import (`PdfStreamEngine.pen()`); clip winding is resolved by
  `Area` at import. An anisotropic CTM strokes the exact outline: PDFBox averages the axes.
- A run has ONE baseline: a glyph off it by more than 0.01 em starts a new run.
- A uniform leaf blend over a baked group is lifted onto the baked image (a white Multiply shadow baked
  in isolation is white).
- Colour: `DeviceCMYK` converts through the document's output intent with `ICC_ColorSpace.toRGB` (a
  `ColorConvertOp` over the same profile lands on PDFBox's numbers instead); the CMYK source colour rides
  beside the sRGB paint and is written back as `k`/`K`.

Model and format (`ocd.model`, `ocd.io`)
- **The format states, it never infers**: `data-size` (a size recovered from `√|det|` is wrong under
  `Tz`), `data-fill` when the render mode drops a real colour (an OCR layer made visible must not come
  back colourless).
- **Paths are DOUBLE everywhere**: float returns `164.007` as `164.0069` and breaks `write(read(x)) == x`.
  Never transform stored geometry to save a wrapper.
- A clip has ONE id (`SvgPaint.clipRef`, the model's) and a native carrier; nothing is clipped by a
  `data-*` attribute (pages carry no script).
- A paragraph wraps a CONTIGUOUS paint span: `Paragrapher` decides in `z`, cuts into z-contiguous
  fragments sharing a `flow`, and `StructureBuilder` rejoins them (STRADDLE).
- `spliceGraphics` is load-bearing; the reader's outer switch is on the SVG TAG — both in
  `references/ocd-container.md`.
- Node ids are PAGE-local; document resources (fonts, images, layers) and page INDEXES are not —
  `ocd.model.Pages` remaps all three positional reference kinds at once. Page order is the OPF spine.
- **`JxTransform` is `(a b c d) = (m00 m10 m01 m11)`**, PDF's order; `toMatrix6`/`fromMatrix6` are the
  only formatters. Realigning the record fixed four silent bugs at once.
- Text entering the model is XML-clean (`JxText.sanitize`); every XML parse is `JxXml.secureBuilder()`
  (DOCTYPE refused); the input kind is sniffed from content, never a name.
- Reproducibility: one clock (`JxClock`, `SOURCE_DATE_EPOCH`); `doc.id()` is content-addressed. Test it
  BOTH ways — two different epochs must give different bytes, or the clock never reached them.
- **The format is VERSIONED, never patched**, and owes no backward compatibility: a container written
  before a fix is regenerated from its source. A grammar change moves the writer, the reader, `ocd.js`
  and `doc/FORMAT.md` together — then run the client/engine name check (§6).
- Known-benign: `pdf→svg` vs `pdf→ocd→svg` differ in the last decimal (em-grid laundering). Quantizing
  at import would rewrite every golden — deliberately, never as a drive-by.

Writers (`write`)
- **Never write `W n` with no current path**: Acrobat refuses the page, five other engines say nothing.
- A path that paints nothing is not written: it survives `Q` as the current path and the next `S`
  strokes it.
- A group's blend or opacity composites the group AS ONE (`compositesAsOne`): a transparency-group form
  in PDF, a styled `<g>` in SVG, a layer in the renderer. A transparent backdrop weights the blend by its
  alpha (PDF §11.3.6), never as black.
- A `CropBox` not at (0,0) keeps its origin (`PDRectangle(minX, minY, w, h)`): model coordinates are
  MEDIA-absolute.
- Images: Sub predictor on every row; grey read from the raster (`getRGB` is linear); 1-bit stays
  1-bit, decided by the colour model, never the pixels.
- Forms: a `PDAppearanceStream` is born without `/Resources` (NPE mid-field); ZapfDingbats' check is
  the CODEPOINT `U+2714`; `/QuadPoints` go UL, UR, LL, LR (the spec's order paints nothing).
- **A glyph the cmap does not name is still drawn by a codepoint**: a ligature ("fi" in one glyph) or a second
  glyph for a letter has no cmap entry, and writing its LETTERS draws other glyphs — « fi » showed as « f » and
  a gap in every replica EPUB. `OCDFont.drawCodepoints()` gives every glyph one (its letter if free, the
  presentation form ﬁ, else the Private Use Area) and the font builders compile `drawCmap()`; gate DRAWN.
- A heading's INLINE children are its words — already in its title: the structure writers walk only its
  block children (they wrote « 1 What jexter is » twice). Reflow chapters split below a wrapper that only
  groups (Document › Sect: one chapter « Content » before; TAGS checks the chapter count).
- PDFBox cannot embed CFF: `GlyfOtf` for PDF, `CffOtf` for web/EPUB. The per-format font table
  constants are tuned per upem grid — do not unify them.
- Invisible text is text: render mode 3 is written by every text projection (`Tr 3` in PDF, zero
  opacity in SVG); dropping it de-OCRs a scan and only `pdftotext` sees it.
- Standalone targets (`.svg`) embed every font and image as `data:` URIs.
- epubcheck 0/0/0 on all THREE flavours (`ocd`, `epub`, `epub-reflow`) —
  `https://github.com/w3c/epubcheck/releases/download/v5.1.0/epubcheck-5.1.0.zip`. NCX navPoints sharing a
  page share their `playOrder` (`EpubPackage`).

Analysis (`ocd.analysis`)
- `Liner`, `XYCut`, `Spacer` are INSIDE `Paragrapher`, not stages; `BlockSignals` and `AnalysisStages`
  are read-only projections. One authority per concern — every bug here was a second authority.
- Font NAME is not a cut signal in `Segmenter` (an italic word would split a paragraph).
- Three structures coexist, priority `pdf` (PDF/UA, built at import) > `outline` > `heuristic`.
  Nav-guided alignment is self-reference: the matched strings come from the document itself.
- Figures, tables, captions, lists, code are deferred to `Refiner` — do not re-add a heuristic.
- Word spaces are judged against PDFBox's extractor in BOTH directions: glued (our long words the reference
  never has) and lost (its long words we never produce). A gate change that improves one and is not
  measured on the other has not been measured. The `text_press` fixture and the WORDS gate hold the press
  shapes (justified spaces, results tables, tracking, Tz, offset columns); a line's band is its tallest
  run, never a growing union; an inkless glyph with no text is a space whose ToUnicode was forgotten.

## 6 · Client traps (Prism, the Inspector, Jexter)

`references/ui-seam.md` is the architecture and the hard rules; `scripts/bench/README.md` the scenarios.
**Run the bench after ANY client change** — every client defect this project found was invisible to the
Java gates. The traps that cost the most:

- **Draw in DOM order, read in `data-order`.** A page is stored in PAINT order; anything that paints,
  hit-tests or measures takes the DOM as it stands, anything that READS (search, read-aloud, Flow,
  extraction) re-sorts first through `ocd.js` `inReadingOrder`. Backwards is silent: only the words
  come out wrong, on exactly the pages where the order was worth stating.
- **The chrome contract**: anything a tool injects into a page carries `data-ui` and is stripped by
  `OcdPage.xhtml()`; a frame stylesheet goes through `book.frameCss`. A view mode never sets an
  ATTRIBUTE on a content node (it rides into the member) and is re-applied to a reloaded frame.
- **A tool that only LOOKS writes nothing**; anything that writes is a verb the reader presses
  (Redact's verdict vs its item list, which every export passes to `--redact`).
- **The client and the engine are two authors of one grammar**, and nothing makes them agree. After any
  grammar change, list every `data-*` the client uses that the writers never state:

  ```bash
  grep -ohE 'data-[a-z][a-z0-9-]*' src/sugarcube/jexter/ocd/io/*.java | sort -u > /tmp/eng
  grep -ohE 'data-[a-z][a-z0-9-]*' $(find src/sugarcube/jexter/ui -name '*.js' -not -path '*vendor*') | sort -u | comm -23 - /tmp/eng
  ```
  Chrome names (`data-ui`, `data-icon`…) are expected; a grammar name there is a bug (once: the
  blanks, the paragraph kinds and every font metric).
- **A regex parser owes you what the DOM would do**: decode entities in ONE pass, anchor attribute names
  (`d` matched inside `id=`).
- **Fix the CLASS, not the instance.** When a symptom is reported, enumerate its siblings — every view
  mode, every node kind, every nesting level, every tool — and check them all. A bug reported three
  times in different clothes is a class treated as an instance.
- **A hardcoded list of tool ids** is this codebase's recurring defect, and it comes in pairs (CSS and
  JS): a condition is a STATE (`body.has-drawer`), never a name.
- A `const` read by module-init code is declared ABOVE it (TDZ; `node --check` passes it, CI's TDZ step
  and a page load do not). Never a backtick inside a CSS template literal.
- Centred content that can outgrow its box is `safe center`. A rule that re-shows an element restores
  what it was (`display:flex`, not `block`).
- Inside a CSS-scaled frame, pointer coordinates are UNSCALED and `getBoundingClientRect` ignores a
  turned frame: map with `prism.frameToClient`. `getScreenCTM` stops at the SVG viewport.
- Focus given in a pointerdown capture is taken back by the default action: `preventDefault()` and
  focus on the next task; focus the iframe itself.
- `pointer-events: bounding-box` on BOTH runs and paragraphs: glyph outlines are the only painted
  geometry, and the gaps lose the pointer. Verify by sweeping points, not one hover.
- A `$('#id')` with no element warns on every boot — diff the modules' ids against `index.html`.
  `installLogCapture` wraps `console.*`: read the message, not the reported line.
- **Verify a style with computed styles**, not screenshots alone; check the EMPTY state of any surface
  whose controls act on a document (the footer hides without a book).
- An unfinished tool is `experimental: true` — hidden outside dev mode (Ctrl/Cmd+Shift+D), never
  removed; shipping it is deleting one word.

**A green bench can have measured nothing.** Say which engine build is behind `/api/convert`
(`JEXTER_CP_FULL`, or `JEXTER_CP` + `PDFBOX_JAR`); drive with `page.mouse` and `page.keyboard`, never a
synthetic `dispatchEvent` (it bypasses hit-testing and focus); a handle on the frame's edge is one pixel
outside it; a scenario that selects nothing must FAIL, not print `null`. Three scenarios once read a
dead attribute and asserted nothing for weeks.

**File System Access in the sandbox**: `jexter_hotfolder.mjs` stubs `showDirectoryPicker` with OPFS
directories — real handles, cloneable into IndexedDB.

## 7 · Commands for Jean-Luc's machine (Windows, PowerShell)

He pastes what he is given. Every rule was paid for.

- Start with `cd C:\Projects\SugarcubeJexter` — absolute paths; he has several repos open.
- **One command per line, never a backtick continuation**: a fragmented paste turned git's help text into
  seven files at the repo root (names ending in `.`, which Windows cannot delete without
  `[System.IO.File]::Delete('\\?\' + $_.FullName)`).
- A destructive command gets a dry run first, as its own line (`… | Select-Object Name, Length`).
- `git config --global core.pager cat` before any `git diff`/`git log`; prefer `--stat`.
- A commit plan states every file and flags the ones that are not this session's; a NEW file (`??`) is
  the one an incomplete `git add` drops without a word.
- PowerShell 5.1: git's stderr shows as a red `NativeCommandError` on success; a UTF-8 `—` displays as
  `ΓÇö` (the commit is fine); an argument with embedded `"` is split before reaching `gh`; a 400-char
  here-string line is cut by PSReadLine. Notes for `gh` go through a file written UTF-8 without BOM.
- Delivered archives: `rm -f` before `zip` (it ADDS), no brace expansion in the sandbox shell, `unzip -l`
  before shipping.

## 8 · Release

A release is a commit on `main` plus a tag — the public history is never rewritten again (clones and
forks exist). The build is the `nimbus` skill's: `node nimbus-jexter.cjs --run=build <abs path>` leaves
`_artifacts/sugarcube-jexter.jar` and `_artifacts/SugarcubeJexter-<v>-win.zip`; `typst compile
doc/typst/jexter.typ jexter-overview.pdf` the overview (gitignored, a release asset). jpackage names the
main launcher after `--name` (`SugarcubeJexter.exe`), the others after their label (`Prism.cmd`); the
`.exe` is javaw — headless work goes through `Jexter.cmd`. Verify a touched class in the shipped jar with
`javap -p -cp _artifacts/sugarcube-jexter.jar <Class>`.

```powershell
git tag -a v1.1.0 -m "Sugarcube jexter 1.1.0"
git push origin v1.1.0
gh release create v1.1.0 $assets --title "Sugarcube jexter 1.1.0" --notes-file $f --latest --verify-tag
gh release view v1.1.0 --json assets --jq '.assets[]|[.name,(.size/1048576|floor)]'
```

A release left as a DRAFT (its tag deleted and recreated) refuses `--latest`: `gh release edit
--draft=false --tag …` first, then `--latest`. Never open a commit title with lowercase `jexter`.

**jexter is a LIBRARY first.** Moving or renaming a public class breaks consumers on THEIR machine (CI
compiles jexter, not them): read the private consumer notes (`repo-skills`) before any such move, and
put the new launch line at the top of any delivery mail. `ConvertOptions` lives in `core`.

## 9 · Boundaries and the roadmap

- Projections of the model (PDF/UA writer, the redaction checker and remover, comparison) belong in
  this repo; product workflows built on the engine live outside it — never in the repo, `doc/` or here.
- Chosen sequence: redaction checker → PDF/UA writer + veraPDF gate → a tables/figures pass in Prism.
  Redaction as built: `references/redaction.md`. PDF/UA output is the inverse of
  `TaggedStructureBuilder` inside `PdfWriter` and needs the real-text mode.
- Page-scoped edits: `OCDReader.readPages` + `--to=patch` return only the members an edit changed —
  EVERY one of them (a purge of `meta.json` once ran on the server and was discarded).
- Open: a source JPEG with a soft mask is decoded at import and comes back Flate (keeping the DCT bytes
  needs the model to carry the mask beside the image); CMYK images still use PDFBox's default profile.

## Git and this skill

Work in the clone and push straight to `main` (Jean-Luc pulls before touching his copy). Every push
runs the CI gate — reproduce it first (§2): a red `main` is a red badge on a public repo. Write LF. A
shallow clone pushes a thin pack only after `git fetch origin main`. Produced files never enter git
(`_*`, `target/`).

**The skill rots like the code, and a rotted skill sends the next session off to rebuild.** A rename is
done when this folder is swept too — `SKILL.md`, `references/*.md`, every script — and at least one
documented command per section has been RUN (CI compiles the Java scripts; nothing runs the Python or the
bench). Commit a skill change with the change it records; put a correction where the wrong claim was;
a current-tense instruction naming something gone is a defect. Keep it to procedures and traps: the
history of how a trap was found belongs in git, not here. `authortext.mjs` names dead v1 attributes on
purpose, to catch their return.

```
references/  jdk-sandbox · features (the fixture catalog) · corpora (real-PDF sources, the outline-GT corpus)
             ocd-facts (facts contract) · diagnosis (symptom → cause) · ui-seam (READ FIRST for client work)
             ocd-container (READ FIRST for format work) · pdf-forensics (the other engines, Acrobat)
             production-audit (the release bench) · redaction
scripts/     setup.sh · build.sh · RenderPdf/RenderOcd · compare.py · render_html.py · batch_score.py
             TextFid · Rich · SigDump (LLM signal harvest: a refactor diffs to zero) · Bench (warm first)
             OcdFacts + verify_pipeline.py (structural facts per stage) · StructDump + score_outline.py
             the probes (§ Already built) · predictor_rows.py · glyph_probe.py
             fetch_corpus.py · fetch_wild_corpus.sh (178 real PDFs from reader test suites: RUN IT — it found
             what every synthetic feature missed) · make_*.py (fixtures) · production_audit.sh
             ProbeMedia · FontLoop · Forge — the callers of JxMedia and Foundry, kept so the capabilities stay checked
             ocd_page_bench.mjs · bench/ (the Chromium scenarios, prism_bench.mjs, README.md)
```
