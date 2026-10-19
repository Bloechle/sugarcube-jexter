---
name: "sugarcube-jexter"
description: "Dev + fidelity + release skill for the Sugarcube jexter document engine — one model, OCD, the Open Canonical Document (PDF ↔ OCD-EPUB / EPUB / HTML / SVG / Markdown / DocTags, PDFBox 3.x, JDK 21 floor, repo Bloechle/sugarcube-jexter v1.0.0). DEV — bootstrap a JDK in the JRE-only sandbox (JDK 21 = the CI gate), compile at 0/0, run Prism and the CLI/API via nimbus. UI — the Prism tool architecture, rendering contracts, the Playwright bench (references/ui-seam.md). CONTAINER — the OCD-EPUB grammar, its rename traps, the load-bearing graphic splice, the measured cost of every lightening idea (references/ocd-container.md). FIDELITY & RELEASE — write(read(x)) byte-stability, reproducibility, epubcheck, XXE hardening, pixel-compare vs PDFBox, the stress corpus, the release recipe. Use when working on jexter, the OCD model, the analysis pipeline, write.* projections, Jx*/OCD* classes, the client OCD API, or any fidelity / round-trip / release-gate question. Lives in the repo, updated by commit."
---

# jexter — development + fidelity workflow

Sugarcube jexter (artifactId **sugarcube-jexter**, **JDK 21 floor** — `--release 21` since
2026-09-02, it ran at 25 for ten days for no gain; vendored Apache PDFBox 3.x) converts PDF ↔ OCD-EPUB / EPUB / HTML /
SVG / Markdown / DocTags through **one** normalized model, **OCD** — the **Open
Canonical Document**:

```
PDF ─[convert.PdfImporter]→ OCDDocument ─[analysis]→ structured OCDDocument
                            (the model)        │
                                               └─[write.*]→ PDF · EPUB · EPUB-reflow · HTML · SVG · MD · DocTags · OCD-EPUB
                                                  ocd.render.OCDRenderer → raster
```

PDFBox is both the PDF **parser** and the **single reference rasterizer** — its
`PDFRenderer` feeds the raster fallback *and* the fidelity check, so reference and
fallback agree by construction. This skill has two facets over one engine:

- **DEV** (§0–§4): bootstrap → build → run. Get a JDK, compile, know the map, run it.
- **FIDELITY** (§5–§6): the verify loop — GT, three probe points, two axes, stage
  isolation. You drive the converter by repeatedly asking *"does the output still
  match the ground truth?"*, and the failing edge names the stage to fix.

> **This skill lives in the repo** (`.claude/skills/sugarcube-jexter/`, since 2026-10-15) and is
> updated BY COMMIT, never shipped as a `.skill` file — see *Maintaining this skill* at the end.
> The repo is PUBLIC (the card's banner): the private notes (market audit, who consumes the
> library, customer contacts) stay in the account skill `repo-skills`, never here.

For any **frontend / Prism UI** task (book.js seam, tools, persist, benches), read
**`references/ui-seam.md`** first — it carries the tool architecture, the hard
rules (never splice page members; tN run ids; data-ui contract; commit order) and
the three sandbox gates including the real-Chromium Playwright recipe.

The project's own status, design decisions and current fidelity numbers live in
**`README.md`** plus the four normative documents under **`doc/`** —
**`FORMAT.md`** (the OCD-EPUB container + the SVG-OCD page/font grammar, Part A /
Part B), **`ANALYSIS.md`** (the geometry-first recovery pass), **`FRONTEND.md`**
(the convert contract + the Prism tool architecture), **`TRACER.md`** (the Potrace
raster→vector tracer) — and the card **`AGENTS.md`** at the root. Read those first when you get the project. Where a document and
the source disagree, **the source is right**: `ocd/analysis/Analysis.java` is the
authority on the pipeline order, `write/Conversion.java` on the target registry.

---

## Already built — find it here before writing a new one

A session once opened with "Building a renderer to compare PDF outputs visually". That tool exists,
has for months, and is §5c — it was not found because the command documented there had rotted
(`sugarcube.jexter.convert.PdfImporter`, a package renamed `pdfimport` weeks earlier, answered
`ClassNotFoundException`). **Before building a harness, look here; if a command below fails, fix the
command, do not replace the tool.**

| you want to… | it exists | where |
|---|---|---|
| see a candidate against the PDFBox reference, pixel for pixel, with a heatmap | `scripts/compare.py` (+ `RenderPdf.java`, `RenderOcd.java`, `render_html.py`) | §5c |
| raster the MODEL, from a PDF or from an `.ocd.epub` | `RenderOcd.java` · `pdfimport.PdfImporter` main (`<pdf> [page0] [out.png] [dpi]`) | §5c |
| every functional gate (repro, byte + model round-trip, read tiers, straddle, clips, hostile) over the whole fixture corpus — what CI runs | `node nimbus-jexter.cjs --run=test` → `test/Gates.java` (in the repo, not here) | §5c |
| which container member drifted, in context | `test/ContainerDiff.java` | §5c |
| links, fields, annotations, layers, outline — before and after a trip | `Rich.java` + the `rich` fixture (`test/Fixtures.java`) | §5 |
| drive the real Prism in Chromium against the real engine | `scripts/bench/prism_bench.mjs` + the scenarios | §4 · bench/README |
| a text/structure comparison against docling | the `docling` skill (`.claude/skills/docling/`, in this repo) | — |
| a customer batch: source vs DELIVERED vs NEW, per reported page, two engines, + an all-pages "made worse" sweep | `scripts/batch_score.py` | §5c, press batch 2026-09-21 |

Measured 2026-09-21 on the `rich` fixture, corrected commands: `compare.py` ran end to end on both a
PNG and an `.ocd.epub` candidate (diff 0.0158), and the image showed real divergences worth a look —
the OCD renderer paints the fixture's HIDDEN layer, which PDFBox honours as off.

## 0 · Setup (the sandbox ships a JRE only)

`javac` is absent and `apt`/`sudo` are unavailable, so the JDK is extracted from
the Ubuntu OpenJDK `.deb` by hand (rootless `dpkg-deb -x`). Dead ends and detail
in **`references/jdk-sandbox.md`**. The project targets **`release 21`** (CI runs on Temurin 21,
the floor: what compiles and doclints there runs on 21 and after), so the bootstrapped
**JDK 21** IS the gate — `--release 21 -Xlint:all -Werror` exactly as the workflow. One command does the whole toolchain:

```bash
bash scripts/setup.sh      # JDK 21 + python(reportlab,pypdf,pikepdf,svglib,Pillow,pymupdf)
                           # + qpdf/poppler + wkhtmltoimage/xvfb + DejaVu/IPA fonts
export JAVA_HOME=/opt/jdk21/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
```

**PDFBox's own source is readable from the sandbox**, and it is often the fastest way to settle
"is this us or is this PDFBox?". `raw.githubusercontent.com` is an allowed domain:

```bash
B=https://raw.githubusercontent.com/apache/pdfbox/trunk/pdfbox/src/main/java/org/apache/pdfbox
curl -fsSL "$B/rendering/PageDrawer.java" -o /tmp/pd.java              # the reference renderer
curl -fsSL "$B/contentstream/PDFGraphicsStreamEngine.java" -o /tmp/pgse.java
curl -fsSL "$B/contentstream/operator/graphics/EndPath.java"           # any operator
```

Measured, so you do not rediscover it: **`trunk` works, a version path like `.../pdfbox/3.0.7/...`
returns 404**, and **`api.github.com` answers 403** (no commit listing, no tag browsing) — fetch raw
files by path instead. What this settled in one session: `clip()`/`endPath()` are ABSTRACT in
`PDFGraphicsStreamEngine`, so the subclass — us, and `PageDrawer` — owns clip application entirely;
`PageDrawer.clip` carries the PDFBOX-4949 guard "don't clip if `W n` only"; `linePath.reset()`
happens in `endPath`, `fillPath` and `strokePath` and NOWHERE else. Reading those forty lines turned
a clip diagnosis on its head: the model was faithful, the consumer was wrong.

**JDK 25** is also fetchable directly — github release assets are network-allowed:
`https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25%2B36/OpenJDK25U-jdk_x64_linux_hotspot_25_36.tar.gz`
→ `tar xzf` → `/tmp/jdk-25+36/bin/javac --release 21 -Xlint:all -Werror`. It is Jean-Luc's
desktop JDK and sees the **JDK 22+ lints** (`dangling-doc-comments`…) that a 21 javac cannot;
keep the tree clean under both. Note the Temurin 25 tarball has no `jmods/` (jpackage
needs a wrapper in the sandbox — nimbus skill, pitfalls).

Each `bash_tool` call is a fresh shell — **re-export `JAVA_HOME`/`PATH` every
time**, or use absolute paths. Use `bash`, not `sh`/dash.

Pool filenames move with security updates — list before fetching (the recipe in
`references/jdk-sandbox.md` does), never hardcode a version. Playwright Chromium
is ALREADY in the sandbox at `/opt/pw-browsers/chromium-1194/chrome-linux/chrome`;
only `playwright-core` needs installing (npm is allowed, the browser download CDN
is not).

**Maven Central is blocked; GitHub is not — so a Java API can be COMPILED FROM ITS SOURCE here**
(measured 2026-09-07, working on the cloud consumer). `codeload.github.com` is on the allowlist, so
`jakartaee/servlet` at tag `6.1.0-RELEASE` unpacks to 912 files that `javac --release 21` compiles
standalone with no dependency — which is how servlet code gets a real compile in this sandbox
instead of hand-written stubs. The same door opens any dependency whose source builds without a
build tool. When it does NOT (Kotlin, a Gradle build, an SDK), the fallback that still beats
guessing is to fetch the sources anyway and **read the signatures**, then type-check against stubs
copied verbatim from them: for the Javalin 7 adapter that caught a `config.jetty.defaultHost` that
does not exist (the field is `jetty.host`) while confirming `config.routes.get/post`,
`config.http.maxRequestSize` (default **1 MB**, a trap for document uploads) and
`HandlerType.name()`. State plainly which one you did — a stub compile is a type-check, not a build.

**`java Foo.java` (single-file source mode) cannot touch package-private members of classes on the
classpath.** The launcher compiles the file into its own `MemoryClassLoader`, so its runtime package
differs from the identically-named one on the app loader, and the first package-private call throws
`IllegalAccessError` — at RUN time, after a clean compile. jexter's own harnesses are unaffected
today (they only use public API), but any of them that reaches for a package-private helper must be
compiled into the same output tree instead of launched from source.

## 1 · Get the project

The repo IS the hand-off: this card (AGENTS.md) and this skill travel with every clone.

```bash
git clone --depth 1 https://github.com/Bloechle/sugarcube-jexter   # public; ~15 MB with lib/
```

Attach it first (`add_repo`, owner `Bloechle`, repo `sugarcube-jexter`, access `push`), clone once,
then read **`AGENTS.md`** (the card), **`README.md`** and the four documents under **`doc/`** —
package map, conventions, fidelity numbers — and open sources on demand. Every path under
`scripts/` and `references/` in this file is relative to **this skill's folder**,
`.claude/skills/sugarcube-jexter/` in the clone: run them as
`python .claude/skills/sugarcube-jexter/scripts/compare.py …` from the repo root, or `cd` there. The
functional gate is NOT here: it is `test/` at the repo root (`node nimbus-jexter.cjs --run=test`, any OS), and CI runs it.

## 2 · Build

```bash
# the project VENDORS both jars in lib/ — prefer them; the uploads path is only a fallback
# when you were handed a bare source tree without lib/
export PDFBOX_JAR=$(ls lib/pdfbox-app-3.0.7.jar 2>/dev/null || echo /mnt/user-data/uploads/pdfbox-app-3.0.7.jar)
bash scripts/build.sh <SRC_DIR> [OUT_DIR]      # bootstraps the JDK if needed, compiles
# or directly — "lib/*" picks up jbig2-imageio too:
javac -cp "lib/*" -d build/out $(find <SRC_DIR> -name "*.java")
export JEXTER_CP=build/out                      # the OCD facts/render scripts need this
```

**The project is AGPL-3.0, dual-licensed** (a commercial licence is available on
request) — `LICENSE` + `NOTICE` at the root, and the README says so. Do NOT infer the
project's licence from its dependencies: the vendored jars are Apache-2.0, the engine is
not, and getting that backwards in a commit message or a release note misstates the terms
the code ships under.

Two vendored jars, both Apache-2.0: **`pdfbox-app-3.0.7.jar`** (shaded — PDFBox +
FontBox + commons-logging, so one `-cp` entry) and **`jbig2-imageio-3.0.5.jar`**
(JBIG2 image decoding — absent, JBIG2 scans decode to nothing and the pixel gate
blames the renderer). BouncyCastle is runtime-only, for encrypted PDFs.

**Reproducing the CI gate exactly** — the workflow runs on Temurin **21**: `javac --release 21
-Xlint:all -Werror -cp "lib/*"`, then `javadoc -quiet -Xdoclint:all,-missing`, then
`node --check nimbus-jexter.cjs` and a link-resolution pass over `src/**/*.html` + `README.md`.
The JDK 21 bootstrap reproduces it byte-for-byte. Also compile once under JDK 25 (§0) before
delivering: its extra lints (`dangling-doc-comments`, JDK 22+) are the ones Jean-Luc's desktop
will show, and the rule is 0/0 everywhere.

**Why 21 (2026-09-02)**: the tree compiles at `--release 21` with 0/0 and uses no post-21 API;
the jar built that way was RUN on a Java 21 runtime — `Jexter welcome.pdf` normalizes, `Conversion
→ .epub` writes 13 members. Java 21 is the LTS everyone has; a jar sent by e-mail must run on the
JDK the recipient already has. The 25 requirement had been a flag, not a need. Five homes state the
floor — `.env RELEASE`, `build.yml`, `pom.xml`, README, AGENTS.md (+ the typst overview, the
release notes) — change all or none.

**Why doclint is a gate and not a nicety.** `javac` reports a *dangling* doc comment
(one that attaches to nothing). It cannot report a **misattached** one: delete a method
and its comment silently re-attaches to whatever declaration follows, then lies — a
`rect → grid box` helper's doc ended up describing a percentage formatter, green on
every gate for as long as nobody read it. doclint also resolves every `{@link}` /
`{@value}`, so a renamed method breaks the build instead of rotting into a dead
reference. `-missing` is excluded on purpose: an undocumented member is a choice, a
dangling reference is a defect. Both gates run clean on v1.0.0 — keep them there.

**A Maven consumer gets the same gate for one line, no javadoc plugin.** `javac` carries doclint
itself, so `-Xlint:all -Xdoclint:all,-missing -Werror` in `maven-compiler-plugin`'s `<compilerArgs>`
is the whole thing — no extra phase, no report. Added to `sugarcube-cloud` on 2026-09-07 and
verified in BOTH directions: the tree passes, and re-injecting a `{@link #applyMeta}` that names
nothing fails the build with `error: reference not found`. It found two dead references there that
had sat for months, because that build had always been silent. Tell a consumer to turn it on rather
than discovering their rot from our side.

The tree is **100% JavaFX-free** (the apps are web-only — `ui.prism` / `ui.pdf` /
`ui.jexter`). To sanity-check a model change in isolation, compile just `core` +
`ocd/model` + `font` (self-contained, no PDFBox dependency once built).

## 3 · Project map

Refactored, public layout — all under `sugarcube.jexter`:

- **`core`** — the `Jx*` toolkit AND `ConvertOptions` + `JxProgress` (moved here 2026-09-05): `JxStroke` (the one pen type — `OCDPath.pen()` and `OCDText.pen()`; cap/join enums whose ordinals are the PDF and AWT codes and whose names are the SVG keywords; `awt()` owns the dash rules), `JxPath` (the single path type, `Path2D`
  subclass, SVG parse/emit, cubic→quadratic), `JxTransform`/`JxRect`/`JxPoint`
  (immutable `double`), `JxColor` (sRGB `int` argb), `JxStringer` (streaming JSON
  builder) + `JxJson` (JSON parser), `JxName`/`JxNum`/`JxText`, `JxZip`, and `JxLog`
  (a thin `System.Logger` facade — host-bindable, zero-dependency).
- **`ocd.model`** — the model (27 classes): `OCDNode` (sealed → `OCDText`/
  `OCDPath`/`OCDImage`/`OCDGroup`/`OCDMedia`/`OCDBreak`), `OCDGroup` (non-sealed
  container → `OCDParagraph` (text block; lines split by `OCDBreak` tokens),
  `OCDLayerContent` (content bound to an OCG layer, `<layer ref>`), `OCDGraphic`
  (clustered vector drawing)), `OCDMedia` (sealed → `OCDVideo`/`OCDAudio`),
  `OCDGradient` (linear/radial paint on an `OCDPath`), `OCDFont`/`OCDGlyph`
  (gid-keyed + cmap, `JxPath` outlines), `OCDClip`, `OCDLayer`, `OCDPage`,
  `OCDDocument`, the annotation layer `OCDLink`/`OCDAnnotation`/`OCDFormField`, and
  the logical layer `OCDMeta`/`OCDOutline`/`OCDStructNode` (a structure-tree node) +
  `OCDStructure` (a named, provenance-carrying tree; a document may hold several —
  e.g. a PDF/UA tagged tree *and* a heuristic one, with one default). Each page's
  `content` array is in **reading order**; every node carries a `z` **paint-order** key
  and the renderer paints by `z`, independent of array order (there is no `ro` field).
  Self-contained — no PDFBox dependency once built.

  **Page surgery (merge / reorder / delete / insert) — what the model already gives you.**
  `pages()` is a mutable `List<OCDPage>`, so the list half is trivial. Two things are NOT:

  - **Node ids are PAGE-LOCAL by contract** (`IdStamper`: *"the same id string may legitimately
    recur on other pages — that is by design: it keeps ids short and unaffected by page
    reordering"*). So merging needs **no id prefixing and no re-stamp**: `(page, nodeId)` stays
    unique once the pages are distinct, and reorder/delete do not touch ids at all.
  - **Document-scoped resources DO collide**: `fonts` (name → font), `images` (ref → bytes),
    `layers` (id → layer). Two PDFs each have an "F1" or an `img_0001`, different. Merging must
    prefix those three maps *and every use of them* — the font name on a run,
    `OCDImage.resourceRef`, `OCDLayerContent.layerId` — or a page silently paints the wrong image.
  - **Page INDEXES must be remapped**: `OCDStructNode.Ref(int page, …)`, `OCDOutline.pageIndex()`,
    `OCDLink.pageIndex()`. A merge shifts the second document by k; reorder and delete need a full
    old→new table. This is the same fix-up for all four operations, which is the argument for ONE
    authority (a `Pages` class) rather than four features each rebuilding the table — and forgetting
    one of the three reference kinds.
  - Open question, format not code: a merge yields two `OCDStructure` trees. Concatenate under one
    root, or keep both named with their provenance? The model allows either.
- **`ocd.io`** — **`OCDReader`** (the OCD-EPUB reader) + `OCDVocab` (the shared
  serialization vocabulary — canonical page ids, render-mode/cap/join names).
  **There is no `OCDWriter` and no `OCDJson`: the legacy JSON `.ocd` container is
  gone** (`Conversion.load` says so in as many words). The working format is the
  **OCD-EPUB** (`.ocd.epub`) — a *valid fixed-layout EPUB 3* that is also the
  lossless model container, written from `write` by `OcdEpubWriter` (container) +
  `SvgOcdWriter` (the SVG-OCD v2 pages) + `OcdMembers` (the `jexter/` JSON members),
  and read back here. Normative spec: **`doc/FORMAT.md`**, Part A container /
  Part B grammar, `data-version="2"`.
  - **Reads in three tiers**, all off the zip: `readMembers` (skeleton — OPF spine,
    `jexter/` members, and the complete fonts from `pages/fonts.svg`), `readPage(file, i)`
    (one page), `read(file)` (the full model). `pageCount(file)` probes without parsing.
  - **Fonts have ONE representation**: `pages/fonts.svg` carries outlines *and* model
    (metrics, cmap, advances, inkless glyphs with `d=""` keeping their advance);
    pages reference glyphs externally via `<use href="fonts.svg#fN-gid">`. No `fonts/*.json`;
    an embeddable OTF is recompiled on demand by `font/GlyfOtf`.
  - **`jexter/` members**: `meta.json` (identity, language, layer registry),
    `outline.json` (nav tree), `structures.json` (every `OCDStructure`, by reference),
    `annots.json` (annotations + form fields, sparse). Links are **not** here — they
    are native `<a>` in the pages.
  - **Liberal but never silent**: unknown attributes/elements ignored, an unresolvable
    reference DROPPED *and reported*, a malformed value refused with its context, and
    **no DOCTYPE accepted** (see XXE hardening, §7).
- **Shipping a jar to a consumer is a CONTRACT check, not a build.** A package rename under `ui/`
  changes the documented launch line; `java -cp` answers a missing class, never a missing file, and
  the consumer will suspect the jar. Read the consumer notes (account skill `repo-skills`,
  private) before any delivery mail, and put the new command at the TOP of it.
- **Before a release commit, sweep for what the compiler cannot see.** `javac` only reads `.java`:
  a vendored `.javaold` (82 KB of a dead package) sat in `src/` for months AND rode into every jar,
  because nimbus stages non-`.java` files as resources. Same class of leftover: a `jj2000/j2k/image/output`
  that the decoder trim had removed and a working copy had restored — four files, invisible to every
  gate, caught only by counting `.java` in the index against the known 275.
- **Before touching the container, read `references/ocd-container.md`.** It carries the v2
  vocabulary (`paragraph`/`line`/`run`, `data-matrix`, `data-clip`/`data-layer`/`data-src`,
  `pages/fonts.svg`), the two rename traps that a script cannot see (`OCDReader`'s outer switch is
  on the SVG TAG, and the bare `"l".equals(...)` line-break test — 823 nodes written, 40 read back),
  why `spliceGraphics` is LOAD-BEARING (a drawing's paths have no contiguous z range, so nesting
  them repaints the page wrong — measured, twice), the HTTP-only rule for `<use>`, and every
  "make it lighter" idea with its measured cost. Writer, reader, `ocd.js` and `doc/FORMAT.md` move
  together or not at all.
- **The rendering comes first, the structure second — and an arbitration rule falls out of it.**
  OCD-EPUB is a FAITHFUL replica that also carries a logical layer; never the other way round. So
  any change that REMOVES content must prove that the content is invisible, and invisibility is
  demonstrated, not assumed: the reference engines (PDFBox, poppler, and MuPDF/Ghostscript when it
  is close) must all paint nothing there, and the ink measurement must show a gain, not a trade.
  Three outcomes from one session make the rule concrete. A glyph whose `toUnicode` claims a space
  but whose FONT carries an outline: keeping it is a win, the text signal loses (0.119% → 0.001% of
  ink lost). A node whose clip has NO AREA: dropping it is a win, nothing paints there in any
  reader (0.936% → 0.047% of ghost ink, nothing lost). A node whose clip sits ELSEWHERE on the
  page: the rule was written, measured — 0.36% of REAL ink erased against 0.05% of ghost — and
  PULLED, because a node inside a baked group has its bounds in the group's frame while the live
  clip is in the page's. And the doc-A masthead duplicate stays visible: the file really draws it
  twice, no clip, no mask, no alpha, nothing covers it, and until the hiding rule the readers apply
  is identified, deleting it would be betting on structure against rendering.
- **`ocd.render`** — `OCDRenderer` (OCD model → Java2D raster) + `BlendComposite`.
- **`ocd.analysis`** — the structuring passes, all **render-orthogonal**: they set roles,
  reorder the `content` array into reading order and build the `OCDStructNode` tree *by
  reference*; the renderer still paints by `z`, so `Fid2 = 0.0` holds by construction.
  **`ocd/analysis/Analysis.java` is the authority on the stage order** — it carries the sequence
  *and* the reason for each position as an end-of-line comment; the reasoning and the
  measurements live in **`doc/ANALYSIS.md`**. Read the file, not a copy of it. Live order:

  ```
  Cleaner → Paragrapher(Segmenter ─ Liner ─ Spacer.freeze) → Furniture → GraphicClusterer
          → OutlineAligner → IdStamper.fill → structure() → HeadingRoles → Refiner
  ```

  What the skill adds on top of that document — the traps:
  - **One authority per concern**, and violating it is how the bugs got in: `Liner` owns
    lines (overlap ≥ 1/3 of the smaller band, **no size gate** — a sub/superscript joins its
    line), `Spacer` owns word spaces (frozen once, sentinels gid −1), `XYCut` owns both
    segmentation (`segment`) and reading order (`order`), `Furniture.isRunning` owns running
    furniture, `HeadingRoles` owns the page-role projection. `OCDBreak` is a **layout flag
    only**, never a spacing mechanism; `OCDIndex` is concatenation + soft-hyphen rejoin.
  - **`Liner`/`XYCut`/`Spacer` are inside `Paragrapher`, not stages** — mistaking them for
    pipeline steps is the classic misreading. So are `BlockSignals` and `AnalysisStages`
    (read-only projections), and `LanguageDetector` (runs at export).
  - **Font name is deliberately not a cut signal** in `Segmenter` — an italic emphasis word
    would over-segment a paragraph. Size, weight, enumerator lead, interline only.
  - **Three structures coexist**, priority `pdf` (PDF/UA, `TaggedStructureBuilder`) >
    `outline` (bookmarks, `BookmarkStructureBuilder`) > `heuristic` (`StructureBuilder`);
    `generateOutline` forces the heuristic **in parallel** without removing the native one.
    Nav-as-ground-truth is **self-reference, not a lexicon** — a bookmark title is a string
    quoted from the page itself.
  - **`Cleaner` is idempotent and source-agnostic**, so re-analysis is just `run` again;
    `restructureText` / `restructureHierarchy` are documented PARTIAL subsets, not a second
    pipeline.
  - Figures, tables, captions, lists and code are **deferred to `Refiner`** — the geometric
    pass no longer guesses them. Do not re-add a heuristic for them.
- **`convert`** — `PdfImporter` (PDF → `OCDDocument`) + `FontExtractor` +
  `ConvertOptions` (**now in `core`**, see §6b — introspectable conversion settings: UIs iterate
  `ConvertOptions.ALL`, exchange values via `fromMap`/`toMap`; e.g.
  `mergeGlyphClips`). The only PDFBox-coupled layer.
- **`font`** — font compilers: `GlyfOtf` (TrueType/glyf, default via
  `JxFont.toOtf`, embeddable by PDFBox as CIDFontType2 + by browsers/e-readers),
  `CffOtf` (native cubic CFF via `JxFont.toCffOtf`, for web/EPUB — **PDFBox cannot
  embed CFF**), `JxFont` (facade + `reverseCmap` gid→codepoint).
- **`write`** — output writers: `PdfWriter` (outline facsimile OR **selectable** =
  exact outlines + real visible text via embedded OTF + reverse cmap), `EpubWriter`
  (EPUB3 **fixed-layout** replica — inline SVG fac-simile per page, `@font-face` OTF,
  + a lossless data sidecar), `ReflowEpubWriter` (**reflowable** EPUB3 from the
  structure tree — chapters split at top-level headings, images/media as resources,
  reader-chosen typography; validated structurally + `epubcheck`, not on the visual
  axis), both sharing OCF container / cover / Dublin Core + a11y metadata / nav+NCX /
  OPF skeleton via `EpubPackage`; `SvgWriter` (page → selectable SVG), `HtmlWriter`
  (reflowable HTML5 from the OCD structure tree — also the content engine behind
  `ReflowEpubWriter` via `body()`/`chapters()` + an `Assets` sink that externalizes
  images/media or inlines them as data URIs), `DocTagsWriter` (Docling/SmolDocling **DocTags** markup — block tags + `<loc_>` boxes on a 0–500 grid + OTSL tables; LLM/RAG ingestion, exact geometry not ML-inferred). `MarkdownWriter` (clean LLM/RAG Markdown — reading-order content view, GFM tables; the readable companion to DocTags' grounded view).
  The **OCD-EPUB** working format is written here too — `OcdEpubWriter` (container) +
  `SvgOcdWriter` (SVG-OCD v2 pages: paths only, `data-text`/`data-order`/`data-size`, native `<a>`
  links, shared `pages/fonts.svg`) + `OcdMembers` (the `jexter/` JSON members) — and read back
  by `ocd.io.OCDReader`. **`Conversion` is the one entry point and the single target
  registry** (`svg · pdf · epub · epub-reflow · html · md · doctags · ocd · stages`): every
  writer shares `write(doc, OutputStream, ConvertOptions)` (`Projection`), the target is
  inferred from the output extension or forced with `--to=`, and the **source is sniffed,
  never declared** (`%PDF` ⇒ import + export, `PK` ⇒ OCD-EPUB re-export). `OCDIndex` is the
  read-out: plain concatenation of run text + soft-hyphen rejoin, nothing else.
  A **pageless document is refused** at `Conversion.load` («Document has no pages»).
- **`tool`** — runnable entry points built on the library. `PdfNormalizer`
  (PDF → OCD → PDF) regenerates a *normalized* PDF — repaired fonts, dropped glyph
  self-clips, regular structure — with a selectable text layer or outlined glyphs;
  its CLI auto-exposes every `ConvertOptions` setting as `--key=value`.
- **`ui`** — local browser-driven apps (zero-dep JDK `com.sun.net.httpserver`;
  **100% JavaFX-free**). **`JexLab` is gone** — the workbench is now **`ui.prism.Prism`**.
  `WebApp` is the shared base: single-instance static-file server with a `/shared/` root,
  CDN proxy + offline cache, sidecar MIME map, and the `/api/*` plumbing. `ui/shared/web/`
  serves the jexter mark, the shared base font library (`fonts/base.f.svg`) and the two
  client-side authorities `js/ocd.js` (the
  grammar) and `js/book.js` (the document authority). Front-end stack VENDORED under
  `shared/web/vendor/` (nothing is fetched): qry 3 (`qry/`: `qry.js` the classic core, `qry-ui.css`,
  `qry-web.js` the website tier, `qry-app.js` the app tier that re-exports it — every module imports
  `/shared/vendor/qry/qry-app.js`; refresh with Qry's `nimbus-qry.cjs --run=vendor --app --to=<dir>`),
  fflate (unzip + zipSync), Poppins; the line icons are Lucide drawings, the apps' ONE set in `js/icons.js` — it only
  calls qry's `icons.add`, and each app's entry module imports it before `boot()` (which starts `icons.watch()`).
  Icons (2026-10-17, Qry 461e820): `<i data-icon="name">` STAYS and gets an `<svg class="qry-icon">` inside, redrawn
  when `data-icon` changes; `i[data-icon] { display: contents }` + `.qry-icon { 1.15em, baseline }` in `sugarcube.css`
  keep the look the replaced `svg.lucide` had (A/B: every icon's box and stroke identical on 23 screens of the three
  apps). Size by context with `X .qry-icon` — never `X i` / `X svg`; a class on the `<i>` (`.tcaret`, `.ic`) reaches
  the drawing as `.tcaret > .qry-icon`. An unknown name: qry's dashed square + one console.error. Before, a caret kept
  by reference (`$.create('i')`) was the DETACHED placeholder once drawn, so PDF Inspector's tree chevrons never turned;
  the `<i>` staying fixed that, and `.tcaret`'s .8rem — dead under `svg.lucide` — was dropped to keep 1.15em.
  qry 3's names (2026-10-17): `toast(msg, { tone, duration })` called as it is — no wrapper since
  2026-10-18 (the three apps each had one): tones `ok` / `warn` / `danger`, none for a plain notice;
  plain TEXT (never escaped HTML); a long step is a toast that stays (`{ duration: 0 }`) and is
  `close()`d in a `finally`; a question is qry's `confirm` / `prompt` (awaited), never the browser's. `boot({ theme: { persist } })` +
  `theme.mode(m)`, `makeTabs('.jx-tab', { panels, attribute })` + `.selected(name)`,
  `makeTable(columns, rows, { numbered, sort }).element` (columns `numeric`, `format`),
  `makeDropZone({ overlayLabel })`, `makeDialog().isOpen()`, and every SSE stream is
  `makeEvents(url)` (`.on('message', fn)` gets the JSON-parsed data). **Three apps:**
    - **`ui.prism.Prism`** — the document workbench (assets `ui/prism/web/`: `index.html` ·
      `prism.css` · `prism.js` the chassis · `prism-sw.js` the Service Worker · `engine.js`
      the engine seam · `analysis.js` · `augment.js` the augmentations tool · `editor.js` ·
      `redact.js` · `pages.js` · `forms.js` · `compare.js` · `trace.js` · `tts.js` ·
      `gizmo.js` · `backend.js` the environment seam — over `shared/web/js/` `ocd.js` ·
      `book.js` · `shot.js`). Open a PDF (the engine lifts it) or an OCD-EPUB (served as-is); **pages render
      natively** — the book is unzipped in the browser and served to iframes by the Service
      Worker, nothing is uploaded. Reader (scroll / page / spread, anchored zoom), page-scoped
      search, read-aloud, an edit mode, and an **Analysis** mode (bounds / flow overlays +
      bidirectional page DOM tree). Exports: PDF · EPUB PRISM · EPUB FL · HTML · Markdown ·
      DocTags. **F2** opens the dual log console (client + server `JxLog` over SSE).
    - **`ui.pdf.PDFInspector`** — raw PDFBox, nothing in between: the **COS** object tree, the
      reference raster with four rasterizers side by side (**PDFBox · Direct · OCD ·
      OCD-reread**), and text extraction.
    - **`ui.jexter.Jexter`** — the standalone PDF normalizer (crude PDF in, clean selectable
      PDF out): a drag-and-drop `WebApp` **and** headless via **`JexterCli`** — single file,
      folder batch, and a `--hotfolder` daemon (`--recursive`, `--threads`, atomic writes).
  An app opens a chromeless Chrome **app** window (**Chrome preferred over Edge** — Edge
  Tracking Prevention blocks CDN storage); `--serve --port <N> --web <dir>` prints the URL
  only (dev/headless). In window mode the page **drives the lifecycle**: when it closes, the
  SSE heartbeat drops and the server self-exits in ~3-4 s (watchdog).
  Render DPI is automatic (displayed width × `devicePixelRatio`), no slider.
  The HTTP **convert contract** (shared `/api/convert` · `/api/options` · `/api/targets` ·
  `/api/health`, the desktop-only routes, the `backend.js` seam and the capability gating)
  is specified in **`doc/FRONTEND.md`** — read it before touching any tier.

**Model invariants:** page user space **Y-up**; **local geometry + per-node
transform** (glyphs in em, images in the unit square, paths in page space);
**sRGB `int` argb, alpha folded** (fill alpha = `ca`, stroke alpha = `CA`); `z` is
a `float`; **bounds are derived, never stored**; **content tree + logical tree** (the
content tree groups geometry by `OCDGroup` subtype (`OCDParagraph`/`OCDLayerContent`/`OCDGraphic`), with an
orthogonal `role` semantic tag available on any node; the separate **`OCDStructNode`** tree
— built by `ocd.analysis`, referencing content nodes by id — is the logical structure
where headings/lists/tables live);
**raster fallback via PDFBox** for anything the primitives can't express.

## 4 · Run

```bash
# convert + render a page with the converter's own CLI → PNG (PAGE is 0-based here)
java -Djava.awt.headless=true -cp "$PDFBOX_JAR:$JEXTER_CP" \
     sugarcube.jexter.pdfimport.PdfImporter in.pdf 0 out.png 144

# reference raster (PDFBox = source of truth); single-file source program, PAGE 1-based
java -cp "$PDFBOX_JAR" scripts/RenderPdf.java in.pdf 1 ref.png 144
java -cp "$PDFBOX_JAR" scripts/RenderPdf.java in.pdf count

# rasterize an OCD-EPUB with the jexter OCDRenderer (PAGE 1-based, RGB white bg)
java -cp "$JEXTER_CP:$PDFBOX_JAR" scripts/RenderOcd.java doc.ocd.epub 1 ocd.png 144

# THE conversion CLI — one entry point, every target. Target inferred from the output
# extension (or --to=pdf|epub|epub-reflow|html|md|doctags|ocd|svg|stages); any
# ConvertOptions key passes through as --key=value. Source is SNIFFED (%PDF vs PK),
# so the same command opens a PDF or re-exports an OCD-EPUB.
java -cp "$JEXTER_CP:$PDFBOX_JAR" sugarcube.jexter.write.Conversion in.pdf out.ocd.epub
java -cp "$JEXTER_CP:$PDFBOX_JAR" sugarcube.jexter.write.Conversion out.ocd.epub out.md
java -cp "$JEXTER_CP:$PDFBOX_JAR" sugarcube.jexter.write.Conversion in.pdf page.svg --page=0

# normalize a PDF through OCD (PDF → OCD → PDF); --outline = vectorized glyphs,
# --<key>=<value> for any ConvertOptions setting (e.g. --mergeGlyphClips=false)
java -cp "$JEXTER_CP:$PDFBOX_JAR" sugarcube.jexter.tool.PdfNormalizer in.pdf out.pdf
```

In code: `PdfImporter.convert(file)` (or `convert(file, ConvertOptions)`) →
`OCDDocument`; `OCDReader.read(file)` / `readMembers` / `readPage(file, i)` →
`OCDDocument` (the three tiers); `OCDRenderer.render(page, doc, dpi)` →
`BufferedImage`; `Conversion.convert(bytes, target, opts)` → bytes.

### The browser bench — `scripts/prism_bench.mjs`

For anything in Prism, exercising HTTP routes is not enough: run the real chassis in Chromium with a
real Service Worker and a real engine behind `/api/convert`. `scripts/prism_bench.mjs` is the server
+ the driver helpers (open, click a ribbon command by EXACT label, pointer-drag — never
`dragAndDrop`), with the vendoring recipe in its header. Every client defect this project has found
was invisible without it; the list is in `references/ui-seam.md`.

`serve` is EXPORTED: a scenario file imports it, listens, drives and closes in ONE process — a second
server script beside it would be a second authority for the same route, and the `/api/convert` route is
where the target-extension rule lives (Conversion infers the target from the output NAME, so `audit` /
`zones` / `patch` each need theirs; a server that always wrote `_out.ocd.epub` answered an audit with a
container). One shell per run: servers do not survive between tool calls.

**A GREEN BENCH CAN HAVE MEASURED NOTHING.** Three times in one session (2026-09-13) the bench was the
thing that was wrong, and each time it read as a verdict about the product:

- its engine is `JEXTER_CP_FULL`, defaulting to `/home/claude/out` — **the build you compiled ten
  minutes ago is not the one behind `/api/convert`** unless you say so. An export came back without
  the model key the session had just added; the code was right, the server was a build old. Export
  `JEXTER_CP_FULL="…/lib/*:$JEXTER_CP"` with every scenario.
- copying a client file into the bench site **without re-running the CDN rewrite** leaves the page
  without qry.js: the boot fails with `$ is not defined`, which looks exactly like a refactor that
  broke the chassis. Rewrite after EVERY copy, and `grep -c jsdelivr` the site as the check.
- a synthetic `dispatchEvent` on an element **bypasses hit-testing and focus**. A click that "worked"
  in the bench did nothing for the user, because the real pointer never reached the element and the
  real focus went elsewhere. Drive with `page.mouse.move/down/up` and `page.keyboard.type` — always.

And two ways to aim a real pointer at the wrong place, both of which produce "the tool does not
respond" with zero errors:

- **`getBoundingClientRect()` inside a frame ignores that frame's CSS transform.** On a turned page,
  frame-local coordinates plus the frame's offset land nowhere. Use the chassis' own
  `window.prism.frameToClient(frameEl, x, y)`; it is the only thing that knows the turn.
- a handle sitting exactly on the frame's edge is one pixel from outside it. A full-page crop puts all
  eight there; draw a SMALLER rectangle first, then grab.


### Exercise a WebApp's HTTP routes (sandbox)

`WebApp` apps (`ui.prism.Prism` · `ui.pdf.PDFInspector` · `ui.jexter.Jexter`) serve
`/api/*` over a local `HttpServer`. To smoke-test the routes here, **redirect the
JVM's stdout+stderr to a file** — otherwise the long-running server inherits the
tool's pipe, the pipe never closes, the call blocks, and the tool returns `-1`:

```bash
nohup timeout 25 java -cp "$JEXTER_CP:$PDFBOX_JAR" \
     sugarcube.jexter.ui.prism.Prism --serve --port 7330 --web src/sugarcube/jexter/ui/prism/web \
     > /tmp/srv.log 2>&1 &
sleep 7
curl -s -o /tmp/idx.html -w "GET / -> http=%{http_code} bytes=%{size_download}\n" http://127.0.0.1:7330/
# a bytes->bytes converter route (normalizer-style apps): PDF body in, normalized PDF out
curl -s -o /tmp/out.pdf -w "convert -> http=%{http_code} bytes=%{size_download}\n" \
     --data-binary @in.pdf -H "Content-Type: application/pdf" \
     "http://127.0.0.1:7330/api/convert?to=pdf&selectable=true&structure=true"
tail -6 /tmp/srv.log
```

Rules: the trailing `-1` is just the bounded JVM being reaped — the `curl` outputs
are authoritative. Every route is GUARDED (`WebApp.guarded`, doc/FRONTEND.md): curl's default Host
(`127.0.0.1:<port>`) passes; a request sent with another `Host`, a foreign `Origin` or
`Sec-Fetch-Site: cross-site` gets 403 — that is the guard working, not the route failing. A harness that
serves Prism from ANOTHER origin (the Chromium bench's own node server) does not go through it at all. **Always redirect to a file**; never begin a cell with
`pkill`/`sleep` on a still-live server (same pipe-wait → `-1`). `--serve` skips the
window (so no shutdown watchdog); pass `--web <dir>` in dev so the disk assets
resolve. `/api/alive` is SSE (curl would hang) — probe `/` or an app `/api/*` JSON
route instead.

Engine note: `Conversion.convert(byte[] pdf, "pdf", opts)` is the bytes→bytes path
behind a `/api/convert` route (and `PdfNormalizer`) — it imports the PDF *with*
`opts`, then re-exports, so `ConvertOptions` toggles genuinely take effect.

---

## 5 · Verify (the fidelity facet)

**Two sources of GT, three probe points, two axes.** When the output diverges,
the failing **edge** names the stage:

```
   GT ─┬─ DECLARED : the fixture corpus (test/Fixtures.java — each method states its truth)
       └─ DERIVED  : real PDF → PDFBox reference          (for arbitrary inputs)
                              │
   probe points ─────────────┼─────────────────────────────────────────────┐
        (1) OCD virtual       (2) OCD reread (.ocd.epub write->parse)      (3) EPUB
        convert.PdfImporter   write.OcdEpubWriter -> ocd.io.OCDReader   write.EpubWriter
                              │
   STRUCTURAL  facts(text, fonts+emb, colorspaces, Separation, images, geometry) -diff-> GT
   VISUAL      render(OCDRenderer | WebKit) -pixel-compare-> PDFBox reference
```

- `GT ↔ (1)` fails → bug in **`convert.PdfImporter` / `FontExtractor`** (converter).
- `(1) ↔ (2)` fails → bug in the **OCD-EPUB serialization** — `write.OcdEpubWriter` /
  `SvgOcdWriter` / `OcdMembers` on the write side, `ocd.io.OCDReader` on the read side.
- `(2) ↔ (3)` fails → bug in the **`write.EpubWriter`** projection.

### 5a · Get / make the ground truth

**Fixture corpus** — one PDF per feature, each isolating ONE trait, written by PDFBox
(`test/Fixtures.java`): 28 features across fonts / text / vector / color / image / page / doc,
plus `rich` and `tagged`. Catalog in **`references/features.md`**.

```bash
node nimbus-jexter.cjs --run=test     # (repo root) writes every fixture into target/corpus, then gates it
# one fixture = one method in test/Fixtures.java, registered in FEATURES; a new trait is a new entry
```

**Real-world corpus** — diagnostic PDFs from public GitHub corpora (pdf.js, etc.);
sources (incl. manual-download) in **`references/corpora.md`**.

```bash
python scripts/fetch_corpus.py --curated --out out/corpus
python scripts/fetch_corpus.py --source pdfjs --pick rotation.pdf cmykjpeg.pdf
```

**The PDF/UA path has a fixture too — the gate runs it.** The `tagged` fixture (`test/Fixtures.java`) is
a PDF with a real `StructTreeRoot`, a ParentTree and `MarkInfo/Marked`. Its
point is the LAST element: **one `/P` holding TWO MCIDs**, a paragraph split over two
lines. A builder that maps one element per block passes every easy tag and still splits
that one — which is the whole reason to honour a stated structure instead of re-deriving
it. Measured: the `pdf` structure comes back with that paragraph carrying **two refs**
(`t4`, `t5`) as ONE paragraph, `doc.defaultStructureId() == "pdf"`, and `StructDump`
reports `tagged_head=2 heur_head=0` — the heuristic correctly finding nothing, since four
elements have no recurring style.

> **Structure-axis GT for free — tagged PDFs and outlines.** A tagged (PDF/UA)
> document carries the author's structure tree, so one file yields *both* surfaces at
> once: `PdfImporter.convert` builds the PDF/UA tree (`TaggedStructureBuilder`, id
> `pdf`) **and** the heuristic tree (`StructureBuilder`, id `heuristic`), so
> `doc.structures()` lets you diff recovery against ground truth. **But on legal /
> government corpora the tag tree is a poor heading GT** — Swiss **Fedlex** law PDFs are
> deeply nested (Titre › Chapitre › Section › Art.) yet their StructTreeRoot files most
> `Art. N` headers as plain `/P` (the LAA: 57 tagged headings vs ~192 real ones) and
> many `cc-*` ordinances carry **no** heading tags at all. The usable heading GT is the
> **outline (bookmarks)**, which carries the full hierarchy with page anchors. The
> dedicated structure-recovery harness (§5d) scores the heuristic tree against the
> outline by **enumerator recall**. Probe trees with the accessors in
> **`references/ocd-facts.md`**.

### 5b · Structural axis (facts vs GT)

`scripts/verify_pipeline.py` extracts normalized **facts** from any surface and
diffs them against the GT (or across stages). Same schema for every backend, so
the comparator is surface-agnostic:

```json
{ "stage":"...", "pages":[ { "size_pt":[w,h], "rotation":0, "text":"...",
  "fonts":[{"name","embedded","type","upem"}], "images":[{"colorspace","bpc"}],
  "colorspaces":[...], "separations":[...] } ] }
```

Backends (all working): `pdf` (poppler+pypdf — reference/GT-derivation),
`epub`|`xhtml` (unzip+parse), `ocd-virtual` and `ocd-reread` (Java
`scripts/OcdFacts.java`, needs `$PDFBOX_JAR:$JEXTER_CP`).

```bash
# derive GT from the source PDF, check the EPUB projection (stage 3)
python scripts/verify_pipeline.py --source book.pdf --gt-from-pdf --stage epub --candidate book.epub

# serialization integrity: OCD virtual vs OCD reread (stages 1 vs 2)
python scripts/verify_pipeline.py --source book.pdf --roundtrip ocd-virtual ocd-reread --ocd book.ocd.epub

# the OCD backend directly
java -cp "$PDFBOX_JAR:$JEXTER_CP" scripts/OcdFacts.java --pdf in.pdf    # 1 virtual
java -cp "$PDFBOX_JAR:$JEXTER_CP" scripts/OcdFacts.java --ocd doc.ocd.epub  # 2 reread
```

Honest OCD limits (by design — OCD is a normalized sRGB pivot): colours flatten to
sRGB (so `separations` is empty, CMYK resolved away — a real surfaced gap, not a
bug), glyphs are em-normalized (`upem`=1000). Facts contract + accessor mapping in
**`references/ocd-facts.md`**.

### 5c · Visual axis (render + pixel-compare)

Every candidate render is compared to the **PDFBox reference** at the same dpi, so
panels overlay pixel-for-pixel:

```bash
python scripts/compare.py SOURCE.pdf CANDIDATE -o cmp.png [--page N | --all] [--dpi 144] [--heatmap]
```

`CANDIDATE` auto-detects: `.png` → as-is (e.g. an OCDRenderer output); `.ocd.epub` →
`RenderOcd.java` (OCDReader+OCDRenderer, needs `$JEXTER_OUT`); `.epub`/`.xhtml`/
`.html` → WebKit (`render_html.py`, whole page so `@font-face`/CSS/paths resolve).
For the **OCD-virtual** surface, render straight from the PDF then compare:

```bash
java -cp "$PDFBOX_JAR:$JEXTER_CP" sugarcube.jexter.pdfimport.PdfImporter in.pdf 0 ocd.png 144
python scripts/compare.py in.pdf ocd.png -o cmp.png
```

Then **actually look at the image** and describe concrete matches/divergences. Map
each symptom → cause with **`references/diagnosis.md`** (covers both the WebKit/EPUB
and the OCDRenderer/Java2D paths). Crammed/overlapping glyphs shortcut:

```bash
python scripts/glyph_probe.py --font <Font>.otf --family <Font>   # width ratio ~ 2 => units-per-em bug
```

**One command runs every functional gate, and CI runs it on every push**: `node nimbus-jexter.cjs
--run=test` compiles, then `test/Gates.java` builds the corpus (the 28-feature matrix, `rich`, `tagged`) into `target/corpus`, then runs
every check over it plus `welcome.pdf`, in one JVM (~20 s; the old one-JVM-per-probe shell took 4 min).
The deck pins `SOURCE_DATE_EPOCH` (an unpinned clock alone fails REPRO; Gates refuses to run without it). Every check reports
its verdict through the exit code — a gate that reads a report it prints can be wired never to go red,
and two of the old ones were (ModelRt's verdict was grepped out of French prose; Clips never failed).
**Sweep the whole matrix, not one file**: one document tells you nothing about a class of defect.

| | |
|---|---|
| **REPRO** | every target, converted twice, byte-identical |
| **DOCTAGS** | `pdf→doctags ≡ pdf→ocd→doctags` |
| **STABLE** | `write(read(x)) == x` in bytes |
| **MODEL** | `read(write(m)) == m` field by field, paint rank kept — what bytes cannot see |
| **TIERS** | `readMembers` / `readPage` / `pageCount` agree with `read`, page for page |
| **STRADDLE** | no paragraph wraps a paint span another node sits inside |
| **CLIPS** | every clip reference resolves, on the imported model AND the model read back |
| **COPY** | every page projects identically to its `copy()`, and editing the copy leaves the page alone |
| **EDITED** | a path a viewer moved (the move composed before the page flip) reads back moved |
| **HOSTILE** | xxe, billion, badmat refused; badfont, badclip, nofonts refused or warned; trunc refused; a silent read or a hang fails |

A red STABLE says a byte moved; `test/ContainerDiff.java` then says WHICH member and shows the
divergence in context. A red MODEL with a green STABLE is the interesting case: a field
written wrong and read back wrong the same way. `SigDump` is the sixth, run by
hand: it dumps the LLM signal harvest, one line per block, to diff two builds —
a refactor must produce zero differing lines. And `ocd_page_bench.mjs` is the
seventh surface, the only one that speaks for a browser.

**NO GATE LOOKS AT PIXELS — and that is where a whole defect class hides.** The five
container gates check bytes, model and structure; `compare.py` exists but nothing runs it.
A `CropBox` that does not start at (0,0) — a trimmed print-ready page — was re-stated by
`PdfWriter` as `PDRectangle(w, h)`, i.e. a page at (0,0), while model coordinates stay
MEDIA-ABSOLUTE. Every node then shifted by the crop origin: 28.35 pt on a real 600-page
book, a visibly displaced cover, 29% of the page's pixels. **Every gate was green**, the
OCD render was pixel-correct (the SVG path handles the origin), and `page_mediabox_cropbox`
had sat in the synthetic matrix all along with a 42.5 pt crop origin — nobody ever
rasterized it. The fix is to keep the origin: `PDRectangle(minX, minY, w, h)`. After it,
LawBook normalizes at **mean 0.0064% / worst 0.042% over all 600 pages**, maxDelta ≤ 25,
113 pages pixel-identical — the residue is sub-pixel antialiasing on glyph edges, checked
by eye at 150 dpi. **Run a pixel compare on any writer change**: render the same page from
both PDFs with the SAME rasterizer (PDFBox both sides) so the only variable is what jexter
did, and ignore deltas ≤ 8 as antialiasing.

**Performance: measure the phases, then PROFILE — the phase split does not name the
hotspot.** Warm baselines on a 600-page book (137,956 nodes, 121 fonts): import 11–13 s
(~20 ms/page, ~85 µs/node), of which **~60% extraction+base and ~39% optional analysis**
(`detectHeaders`/`graphics`/`structure` off ⇒ that third is saved outright, which is the
lever for a text-only RAG caller). **The writers are noise** — the OCD writer is ~60 ms on
a page, so optimising them is wasted effort. PDFBox's own `PDFTextStripper` on the same
book is 2.9 s, so a glyph-level model costs ~2.8× a text dump. Client side (Chromium, real
pages): 128 KB, ~3,200 DOM nodes and ~2,700 `<use>` per page, **131 ms load+render**; the
cold cost is the monolithic `fonts.svg` (4.3 MB fetched whole before ONE glyph paints, then
cached for the whole book). Model heap ~157 MB for 600 pages — the number that matters for
a concurrent server, more than speed.
- **`Bench.java` lies by a factor of 16 if you read it raw.** It times targets in list
  order from a cold VM, so the FIRST one absorbs class-loading of the entire write path:
  `ocd` measured 972 ms on a 15-node page, and 60 ms once every writer is warmed. Warm each
  target before timing it, and report medians.
- **A phase split is not a profile.** «Import dominates» is true and useless. A JFR run
  (`-XX:StartFlightRecording=settings=profile`, then `jfr print --events
  jdk.ExecutionSample`) named the real hotspot: 13% of samples inside `java.util.Formatter`,
  from `PdfStreamEngine.sig` — a `String.format("%.3f,%.3f,%.3f,%.3f")` building the
  run-continuation key ONCE PER GLYPH. Replacing it with hand-written fixed-3-decimal
  output cut **~11% off the whole import** (11,898 → 10,636 ms median, A/B on one machine)
  with output **byte-identical on 30 documents**. Verify such a change by formatting
  equivalence FIRST (400k random values + the edge cases against `%.3f`), because `sig`
  feeds run grouping: any rounding drift silently re-splits runs.
- **The clip path was a red herring, and measuring said so.** `currentClipId` runs 1.38 M
  times on that book and 54% of those return null through `coversPage`, which looks like an
  obvious win — it is 0.7% of import (`Area` caches its bounds), and the whole method is
  5.9%. Count first, then TIME: a call count is not a cost.
- **The pipeline is single-threaded per document ON PURPOSE — do not "fix" it.** The
  per-page stages (`Cleaner`, `Paragrapher`, `GraphicClusterer`, extraction) do loop over
  independent pages, so intra-document parallelism looks like the obvious next win. It is
  the wrong axis: concurrency belongs at the DOCUMENT level (N conversions on N cores),
  which already saturates a stateless server, and threading inside one document would only
  contend for those same cores. It also walks straight into PDFBox's `PDDocument`, which is
  not thread-safe for concurrent page access — the document-level split sidesteps that
  hazard instead of fighting it. **Decided; revisit only if a single-document latency
  target ever appears.**
- **Document-level concurrency is WIRED, and the engine is safe for it by construction.**
  `JexterCli` runs a fixed pool over the batch and the hotfolder; `WebApp` serves on a cached
  pool; the Jexter web client converts in BOUNDED lanes. **Three concurrent documents is the house
  ceiling** (`MAX_LANES`), half the cores below that: it is a HARD clamp in the browser (a
  dropped folder can queue a hundred files and nothing else bounds what they fire at the
  engine) and the DEFAULT for `--threads`, which stays overridable for a server with the heap
  for it. Verified: a stored setting of 12 lanes produced a measured peak of 3. Verified: **zero static non-final fields
  in the whole tree**, so nothing is shared between concurrent documents — and 6 documents
  converted at `--threads=1` and `--threads=4` came out **byte-identical**. A browser run with
  4 lanes reached a measured peak of 4 simultaneous `/api/convert` requests. The ceiling is a memory
  decision, not a CPU one — see below.
- **A `const` used by module-init code must be declared ABOVE it.** `MAX_LANES` added next to
  `defaultLanes()` sat below `const cfg = loadCfg()`, which calls it at init: temporal dead
  zone, ReferenceError, blank app. `node --check` passes it happily — only loading the page
  catches it, which is why every UI change ends in a real browser run.
- **At document-level concurrency the binding constraint is MEMORY, not CPU.** Measured
  model heap: ~157 MB for 600 pages / 137,956 nodes, ~41 MB for 117 pages / 49,491 nodes —
  about **1.1 KB per node**, and the peak also carries PDFBox's own structures on top. Size
  the worker pool off the heap and the largest expected document, not off the core count.

**Round-trip gates prove the text SURVIVES, never that it is RIGHT.**
`scripts/TextFid.java` compares the read-out against PDFBox's own extractor on the
same PDF: characters lost, characters invented, and the words that disappeared or
appeared - the part that decides whether a document is readable. It is how the
welded-word defect was found at all. Read it with judgement: on a `/Rotate` page
PDFBox itself emits column-wise mush (`01234`, `56789frchf`), so a large word gap
there is the REFERENCE failing, not the engine - check the rotated page alone
before believing a multi-page number.

**Three divergence classes are EXPECTED — measured on the synthetic matrix, do not
chase them.** (i) **NFKC folding at read-out**: `OCDIndex` normalizes, so `²`→`2`,
`…`→`...`, `™`→`TM` and **NBSP→space** show as "missing" against PDFBox (`text_unicode`:
3 missing / 6 added, 0 words lost; a real form: 17 NBSP "missing" while the model
round-trips every one of them — the fold is in the READ-OUT, not the container). The model and the container keep the source codepoint — only the
read-out folds. (ii) **CJK and RTL word grouping**: 0 chars missing and 0 invented, but
the word counts differ (`text_cjk_cid` 14→13, `text_rtl` 14→13). The geometry
deliberately abstains from inferring word spaces outside LTR space-parted scripts
(`JxText.isLtrWordScript`, FORMAT.md) — PDFBox splits anyway, so the two disagree by
design. (iii) the `/Rotate` reference mush above. A character loss OUTSIDE these three
is real: `text_base14` and `text_tiny_leading` sit at exactly 0/0/0/0. A fourth reading:
PDFBox emits `U+0000` for glyphs it cannot map (5 on `habibi`), so a NUL reported as
"missing" means jexter declined to invent a character — the reference is the one at fault.
**Read TextFid against the MODEL gate**: if `ModelRt` is 0 and TextFid is not, the gap is
in the read-out or the reference, never in the container.

**Links, fields, annotations, layers and the outline ride on their OWN tracks** -
page lists and jexter/ members, not the SVG content tree - so no paint or text
gate touches them: a whole track can vanish with every other gate green.
`scripts/Rich.java` compares those tracks across a round-trip. Feed it a document
that HAS them; most corpus pages have none, and it will report a cheerful zero —
**the synthetic matrix carries no fields, annotations or layers at all**, so run
`node nimbus-jexter.cjs --run=test` first and take `target/corpus/rich.pdf` (2 links · 2 fields with values ·
2 annotations · 2 OCG layers, one on one off · a two-level outline). Measured on it:
every track survives the OCD-EPUB round-trip intact, field values included.

A form field is deliberately LIVE DATA, not baked ink: the widget's PDF appearance
stream is not imported, and OCDRenderer draws a synthetic preview instead. So a
pixel comparison against PDFBox on a form document shows a large, permanent
divergence CONFINED to the widget rectangles - expected, not a regression. Check
the divergence bbox against the field rects before chasing it.

Measured on `make_rich.py`'s fixture — the tracks do NOT survive equally, and the
numbers are worth knowing before you promise a target:

| target | external link | internal link | field value | annotation |
|---|---|---|---|---|
| **ocd** | yes | yes | yes | yes |
| epub (fixed) | **no** | yes | yes | yes |
| epub-reflow | no | no | no | no |
| html · md · doctags | no (the link TEXT stays, the anchor goes) | no | no | no |
| **pdf** | **yes** | **yes** | **yes** | **yes** |

The OCD-EPUB keeps everything (real anchors, the internal link resolved to the page
file, fields in `annots.json`). **The PDF row changed on 2026-09-13** — `write/PdfForms`
states the model's fields back as a real, fillable AcroForm and `write/PdfAnnots` does the
same for links and review markup, both called from `PdfWriter.write`. Re-measured
2026-09-14 on `make_rich.py`'s fixture, source → `--to=pdf`, with `scripts/Rich.java`:
`links=2 fields=2 annotations=2` on both sides, field VALUES included. What the PDF export
still drops is **layers**: `layers=2 → 0`, the OCGs do not survive. Know which projection you
are promising before you promise it — and re-measure this table rather than quoting it.

**Byte-stability is not fidelity.** `write(read(x)) == x` proves the writer is
deterministic; it proves nothing about a field dropped on BOTH sides — write it
wrong, read it back wrong the same way, and the bytes agree while the model has
quietly changed. The MODEL gate (`test/Gates.java`) is what catches that: it compares
`read(write(x))` to `x` node by node, field by field. Run it on any new corpus
page before believing a round-trip claim. It reports three things and only the
first is a defect:

| | |
|---|---|
| **ecarts** | a field that changed — must be **0**. The render-transparent `OCDGraphic` wrapper splice is counted APART (`+N wrapper(s) … attendu`), inside `ModelRt` itself, because it is expected on any document carrying a drawing: counted as an ecart it reddened 8 of the 27 synthetic features and taught you to ignore the number. `0` means `0`. Measured: **27/27 features at 0** |
| **transform** | deviation under tolerance — must sit at export precision (4 decimals, ~6e-5) |
| **ordre** | `z` is re-derived from document order, so its VALUES change; the RANK must not |

It found the font size being inferred from the run matrix determinant — silent,
because the product still paints correctly, and wrong only later, as a bad size in
the analysis signals.

**`trace.js` (ex-`reveal.js`, renamed 2026-09-07) — the DocEng demo surface, Analysis' *Trace* overlay.** Point at anything on the page and it answers for
itself: the highlight matches WHAT THE THING IS. A vector path gets its real geometry stroke-traced (its
own `d`, dash-animated over its measured length) — that is the mark nobody else can draw. A hovered run is
PROMOTED to its block — a run is a fragment, often half a sentence, while the block is what the
analysis produced and what a reader means by "that paragraph"; one box, and the caption aggregates
(lines, runs, characters, glyphs, fonts, role). The block gets its BOUNDS: tracing 70 glyph outlines at 9 pt merges into a solid mass that hides the very
words it points at, and the box is what the model reasons about anyway. Fill 13%, stroke 92% — both the brand green, the outline reading clearly while the fill
stays light enough to read the words through — a highlight that hides its subject has
failed, however clever. A caption follows the pointer with facts only this model
has: characters vs glyphs painted vs blank sentinels, the font subset, the size, the role, the
baseline angle. Click pins it, Escape releases. **Never draw the highlight INSIDE the content** — inserting it into the hovered run repaints that
run on every pointer move and changes the very bounding box `pointer-events:bounding-box` is
hit-testing against: the text visibly blinks. One `<g>` at the `<svg>` root, emptied and refilled,
with each shape carrying the element→root matrix (`svg.getScreenCTM().inverse().multiply(el
.getScreenCTM())`) so the geometry stays in element space. **A pointer event inside a CSS-scaled
frame is in UNSCALED coordinates** while the frame's rect is in screen pixels — mapping one to the
other without the factor made the caption drift further the more you zoomed. Take the factor from
`rect.width / frame.offsetWidth`, not from `--zoom`: one source, always in sync. Measured stable at
+18/+17 px from x1 to x1.7. **An OCD `<image>` is a UNIT SQUARE placed by its matrix** — its `width`/`height` attributes are
1 by construction, so reporting them says nothing. Measure the element→root CTM's basis vectors
(`hypot(a,b)`, `hypot(c,d)`) times the element's own box: the placing matrix may sit on an
ancestor, not on the image. **Animate a property you are not also styling**: `.rv-wash` set
`opacity:.10` while the fade animated `opacity` to 1 with `both`, which simply overwrote it and
washed the image solid green — use `fill-opacity` for the style. **And never put a backtick inside
a CSS template literal**: it ends the stylesheet mid-rule, the browser reports "Unexpected
identifier", and `node --check` does NOT surface it — only loading the page does.
**`pointer-events:bounding-box` on BOTH `[data-ocd="run"]` and `[data-ocd="paragraph"]`
is load-bearing** (as `trace.js` and `redact.js` state it today) — glyph outlines are the only painted geometry, so hit-testing
loses the pointer in EVERY gap: between letters inside a run, and between runs inside a block
(leading, run boundaries). Either miss tears the highlight down and rebuilds it. Boxing the run
alone fixes the first and leaves the second, which is what «the block appears then disappears when
I move» looks like. Verify by SWEEPING: 25 points woven across a multi-line block, counting how
often the highlight is lost — it must be zero. Registered `experimental`,
behind dev mode (Ctrl/Cmd+Shift+D). Read-only: `onLeave` removes the layer, the caption and the
stylesheet. An earlier scripted animation (`compose.js`) was dropped for this — a canned sequence
plays once, an interactive surface answers questions.

**Prism's footer is a DOCUMENT toolbar — it has no business existing without one.** With no book
open it still showed a pager over nothing (`< / – >`), read-aloud with nothing to read, view modes
with nothing to view and a zoom over empty space: four groups of dead controls. Only the app status
stays; the bar collapses to a status line and the drop card carries the screen. Keyed on
`body.has-book`, the flag the stage already uses — check that flag before adding anything to the
footer, and check the EMPTY state of any surface whose controls act on a document.

**A regex parser must DECODE what an XML parser would.** `parseFonts` reads attributes with a
regex, so it returned raw markup: the writer correctly stores `data-text="&amp;"` for the ampersand
glyph, and the value came back as the five-character string `&amp;`. Visible as literal `&quot;`
and `&amp;` under PDFInspector's glyph specimens, and silently wrong for any comparison of `u`
against a real character. Decoded at the single accessor, in ONE pass — a sequential replace turns
`&amp;lt;` into `<`. Same family as the earlier `d`/`id` substring collision: a hand-rolled parser
owes you everything the DOM would have done. **Both font panels ride the same `ocd.js`**
(`loadOcd` → `parseFonts`), so a fix there lands in Prism and PDFInspector at once — measured
190/190 real outlines and 0 entity labels in the Inspector after it.

**Centred content that can outgrow its box must be `safe center`.** A flex `align-items:center`
centres an item WIDER than the container by overflowing it on both sides — and the left overflow
is not scrollable. Zoomed past the viewport, Prism's scroll mode put the page 175 px off-screen at
`scrollLeft 0`, with `scrollWidth` (804) SMALLER than the page (979): that column simply did not
exist to scroll to. The paged modes already used `safe center` — only the default rule was missed,
which is the shape of this bug class: one variant fixed, its siblings not. Measure it, don't eyeball
it: compare the page's `getBoundingClientRect().left` against the scroller's at `scrollLeft = 0`,
and check `scrollWidth >= pageWidth`. Verify every view mode (`scroll` / `page` / `spread`) after
touching any of them.

**Fix the CLASS, not the instance the user showed you.** This is the single most expensive
habit to lose. Every UI defect this session came back a second time because the first fix was
correct but partial: bounds for the run without extending to the block; `bounding-box` on the
run without the block, so the highlight flickered in the gaps BETWEEN runs; the image measured
from its own transform without looking at an ancestor's. The same shape as `safe center`, where
the paged modes were already fixed and the default one was missed. When a symptom is reported,
enumerate every sibling of the thing reported — every view mode, every node kind, every nesting
level — and check them all before delivering. A user reporting the same bug three times in
different clothes is the signal that a class was treated as an instance.

**A dangling `$('#id')` warns on every boot — sweep them, don't fix one.** `$()` is the loud
form (`$.opt()` is the optional one), so a selector whose element was deleted shouts into the
console forever. Prism carried `$('#m-theme')` from a menu that no longer exists while `#b-theme`
already owned the toggle AND its icon — dead, and worse than dead if the element ever came back:
two authorities for one control, one of which leaves the icon stale. Check the whole surface at
once — parse the ids out of `index.html` and diff every hard `$('#…')` in the modules against
them; Prism had exactly one, and now none. **Note the console-capture caveat**: `installLogCapture`
wraps `console.*`, so the browser attributes every message to the wrapper line (`prism.js:1075`),
not the real call site — read the message, never the reported line number.

**Prism's visual direction: the document is the only lit object.** The chrome had four tinted
planes (an accent radial on the body, another on the stage, gradients on header and footer), every
surface outlined, and a gradient wordmark — boxes in boxes, and coloured ambient washes are the
clearest dated tell. The refinement block at the END of `prism.css` (appended last on purpose, so
deleting it whole restores the previous look) drops every wash, recesses the reading field BELOW
the rail so the page floats, gives the page the app's only elevation (1px ring + contact + ambient
shadow), and spends the accent solely on state — active tab, hover, focus. It also carries the
quality floor: a `:focus-visible` ring on every control and `prefers-reduced-motion` honoured.
**Verify with computed styles, not screenshots alone** — a filled tint reads as an outline at
screenshot scale, and `getComputedStyle` settles whether a rule actually won.

**An unfinished Prism tab is HIDDEN, never removed.** A mode may declare `experimental: true`
in the registry: `renderModeBar` filters the button and nothing else changes — the module still
loads, still registers, still runs. `setAppMode` refuses an experimental id outside dev mode, so
a stale persisted mode cannot strand a user on a hidden tab. **Dev mode**: `Ctrl/Cmd+Shift+D`
toggles it (remembered in `localStorage` under `prism.dev`), `?dev=1` or `#dev` turns it on for a
link; the bar then shows a `DEV` badge and an amber dot per unfinished tab, so it is never
mistaken for shipped UI nor left on unnoticed. Today `edit` and `analysis` carry the flag —
**shipping either is deleting one word**.

**The browser surface found a defect no model gate could see** — measured, and the
reason this surface exists. `PdfImporter` discovered layers lazily from `/OC … BDC` in
the content stream, so every `OCDLayer` was born `visible = true` and the document's own
`/OCProperties /D /OFF` was never read: a hidden stratum painted on every surface. The
model gates are blind to it (the layer COUNT round-trips either way, `Rich` reports
`layers=2 -> 2`, `ModelRt` sees no changed field), and only a Chromium render laid
against the PDFBox reference shows the extra text. The registry is document-level
presentation metadata, so it is now read at document level — `extractLayers`, beside
`PdfCatalog.read` — which also gives `order` a real value instead of 0.
`beginLayer` keeps its fallback for an OCG referenced by content but never declared.

**The written page needs a REAL browser — this is a separate surface, not a
detail.** `RoundTrip`/`ModelRt` prove the Java reader; they say nothing about what an SVG
engine does with the page, and the two can disagree completely (a model attribute
the reader honours and a browser ignores renders perfectly one way and wrongly the
other). Chromium ships in the sandbox; `scripts/ocd_page_bench.mjs` serves an
unzipped container over HTTP (never `file://`) and screenshots page 1 at the
source's own pixel scale, so it overlays the PDFBox reference directly:

```bash
cd /tmp/pw && PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers npm i playwright-core
mkdir book && (cd book && unzip -q out.ocd.epub)
node ocd_page_bench.mjs ./book shot.png 1689      # width in px = pt * dpi/72
```

Compare a BAND, not the page: a newspaper page differs by ~7 mean channel from
PDFBox on antialiasing alone, which buries a whole missing clip. Crop the bbox of
the suspect node (page space → px through each raster's OWN scale, never resize
one to the other — a 1 px page-height difference slides every crop) and score ink
coverage: reference 6.08 % vs 16.32 % is unmissable where 7.38 vs 7.21 says
nothing.

### 5d · Structure-recovery axis (headings vs outline GT)

The third surface: does the heuristic `OCDStructNode` tree recover the document's heading
hierarchy? Scored against the **outline** (bookmarks), not the tag tree (§5a). The
metric is **enumerator recall** — each outline title and each heuristic HEADING is
reduced to its leading enumerator (`Titre N`, `Chapitre N`, `Section N`, `Art. N[a|bis]`,
`§ N`); recall = fraction of GT enumerators that surface in some heading — plus **merge
errors** (one HEADING carrying ≥2 enumerator kinds: a Titre glued to the next Chapitre)
and heading counts (heuristic vs tagged). It is a proxy — per-doc numbers carry ±a few
points of regex noise; the **corpus aggregate and the failure clusters are the signal**.

```bash
# dump one doc's heuristic headings (level TAB text) + a SUMMARY line (heur/tagged/LIST/ITEM)
javac -cp "$PDFBOX_JAR:$JEXTER_CP" -d "$JEXTER_CP" scripts/StructDump.java
java -Djava.awt.headless=true -cp "$PDFBOX_JAR:$JEXTER_CP" \
     sugarcube.jexter.write.StructDump in.pdf out.tsv
# score a whole corpus of outline-bearing PDFs (per-doc + aggregate recall / merges)
FEDLEX_DIR=/path/to/fedlex python3 scripts/score_outline.py    # needs pikepdf; honours $PDFBOX_JAR/$JEXTER_CP
```

The reference corpus is **Fedlex** — hundreds of `cc-*` Swiss federal law PDFs, GT =
their outlines (see **`references/corpora.md`**). Because heading detection is now
**typographic recurrence** (`FontProfile`, no lexicon), this axis validates that a style
elevated *and recurring* across pages is recovered as a heading while a one-off large
decoration is not. The `FontProfile` recurrence thresholds (min pages / min runs that make
a style count as recurring) are the knobs to turn against real failure clusters.

## 6 · The loop

1. Produce/choose GT (a synthetic feature, or a real PDF).
2. Run the structural check at the relevant stage; if renderable, run the visual
   compare too.
3. Read the divergence → the failing edge (`GT↔1`, `1↔2`, `2↔3`) names the
   component to fix.
4. Fix → regenerate the surface → re-run the same check. Iterate until green.

Start narrow (one synthetic feature exercising the suspected bug — `color_spot`
for Separation loss, `text_cjk_cid` for CID embedding, `image_cmyk_jpeg` for CMYK,
`page_rotate` for orientation), then widen to the corpus.

### The copy gate — COPY in `test/Gates.java`

Every page must project identically to its copy (`SvgOcdWriter.render`), and the copy must be independent (emptying it leaves
the original's projection unchanged). This is what makes `OCDNode.copy()` safe to extend: a field that
reaches the output and was not copied shows up here immediately.

### Gotchas
- Keep dpi identical on both sides (`compare.py` enforces it) for pixel-aligned overlays.
- `RenderPdf.java` / `RenderOcd.java` are **1-based**; the `PdfImporter` CLI is **0-based**.
- This `wkhtmltoimage` (0.12.6) ignores `--zoom`/`--disable-smart-width`; it renders
  **1 CSS px = 1 px**, i.e. at the page's *point* width. `compare.py` therefore renders
  the candidate at `--width = ref_width·72/dpi` (the CSS page width), not the high-dpi
  ref width — passing the latter shrinks the page to ~half in a too-wide canvas and
  inflates the diff with a pure scale artifact. Rolling your own loop: render the ref at
  72 dpi and pass that width. It honors `@font-face` `url()` and multi-value SVG `x`.
- `glyph_probe.py` uses DejaVu Sans as the system reference — only the ratio matters.
- PDFBox **cannot embed CFF** (`PDType0Font.load` rejects CFF outlines) — use
  `GlyfOtf` for the PDF path; `CffOtf` is web/EPUB only.

### Redaction — the fill takes an ALPHA, and the page is 1-based

`redactFill` accepts `#rrggbb` **or `#rrggbbaa` — alpha LAST** (`JxColor.ofHex` reads 8 digits as
RRGGBBAA). It always did; only the help text said `#rrggbb`, which hid the feature — **fixed
2026-09-07**: `ConvertOptions.REDACT_FILL` now states the alpha and why it is safe, so every UI that
reads the registry can offer it, and Prism's Redact ribbon does (colour + opacity). Measured:
`--redactFill=#ff000080` emits `fill:#ff0000;fill-opacity:0.502`, `#ff0000` emits `fill:#ff0000`,
`#00000000` paints nothing. A help text is not documentation: it is the UI.

A translucent or invisible box is **safe**, and that is the point of offering it: the content
underneath already left the model before any projection — glyphs dropped from the run, paths cut by
area subtraction, image pixels repainted in the bytes. The box is a visual mark, so its alpha changes
what a reader SEES and never what the file CONTAINS. It is how you show a zone is empty.

Gotcha: `--redact=1:x,y,w,h` is **1-BASED**, while the SVG target's `--page` is 0-based. Passing 0
fails with `no page 0`, which does not name the cause. Documented rather than changed — changing the
base would break existing callers.

For debugging a redaction visually (checking that nothing survives under a box), the right tool is a
Prism overlay that dims opaque paths — an injected stylesheet like `redact.js`/`trace.js` already
use, writing nothing. NOT a model change: the box is an ordinary `OCDPath` and nothing marks it as a
redaction mark. Marking it (`data-redact` in SVG-OCD) would enable more — counting, hiding per
projection, an audit that says *we* redacted this — but it is a format decision.

### `ConvertOptions` lives in `core` (2026-09-05) — a BREAKING change for consumers

It sat in `convert`, which forced `Refiner` and `AnalysisStages` to import upward: the only two
backward edges in the graph. Moving it to `core` removed both — the package graph now reads
`ocd.analysis | ocd.model · core`, nothing else.

**But jexter is a LIBRARY with consumers**, and moving a public class between packages breaks every
one of them. `sugarcube-cloud`'s `ConvertServlet` stopped compiling, and it was found by a failed
`mvn package` on the consumer's machine, not here. Before any such move: `grep -rn "sugarcube.jexter"`
across the consuming repos you know of, and say so in the release notes. Purity inside is not worth a
silent break outside unless you can name who breaks.

`JxProgress` (also `core`) is the progress channel: a record `(Stage, done, total, detail)` with
`Stage ∈ {OPEN, FONTS, IMPORT, ANALYSIS, WRITE}`, and a `Sink` bound **per conversion on
ConvertOptions** — never static, which is what lets N documents convert side by side without their
progress crossing. Stages that cannot count (ANALYSIS, WRITE) must never be given a fraction.

### The page as an editable unit (2026-09-06) — grammar, order, copy

Four authorities were added or moved so that "edit a page" is expressible. Each replaced something
that worked by accident.

**Geometry is stated on the ROOT, never in the elements.** The page's coordinate frame is the MEDIA
box: `OCDPage.toSvg()` / `fromSvg()` is the one flip authority (`OCDReader` had four hand-rolled
copies of it, one re-applying the FORWARD flip as its own inverse — correct only while the box starts
at (0,0)). Over that frame:

- **crop = the root `viewBox`** (`x y w h`). `data-crop` is gone. Anchoring the flip on the crop
  instead folded its origin into every node's matrix: measured, nudging a crop by 10 pt rewrote 10 of
  a 36-line page; now it rewrites 1.
- **rotation = `data-rotate`**, page metadata exactly like PDF's `/Rotate`. (It was `data-rot` on
  2026-09-06 and was renamed; the CLIENT was not, and the split cost a page that never turned, a
  rotation dropped on export and a redaction box of `{0,0,0,0}`. The page root states `data-rotate`,
  `data-mediabox` and `data-version` — nothing else, on either side.) The `<g data-ocd="rot">`
  carrier is gone from the OCD flavour — the stored page is the UNROTATED document and the viewer
  turns it. The presentation flavours keep baking it in, because they must render anywhere:
  `SvgWriter` (standalone `.svg`, fixed-layout EPUB) keeps the visual viewBox and the carrier,
  `PdfWriter` sets `/Rotate`. Since v4 a page written the old way is not read at all: the container
  states version 3 and is refused (reconvert it).
- Byte cost of the change: **27 of 29** documents (the synthetic matrix + a 6-page fixture) serialize
  byte-identically; only `page_mediabox_cropbox` and `page_rotate` move, once.
- `OCDPage.cropBox(null)` — or the media box — now CLEARS the crop. The old guard made "back to full
  page" a silent no-op.

**Page order is the OPF SPINE** (`OCDReader`), not the sorted member names. Two defects closed: a
client that reorders the spine is believed (before: a 6-page book whose spine said 1,5,3,4,2,6
exported as 1..6 and round-tripped byte-identical to the original), and a document of **≥1000 pages**
comes back in order — `page-1000` sorts between `page-100` and `page-101`, so a 1001-page book read
99, 100, 1000, 1001, 101 and `write(read(x)) == x` FAILED (694 493 → 695 036 B). No corpus document
had ever reached four digits.

**`ocd.model.Pages` owns the page set.** The spec states the RESULT — `"1,4-6,2"`, `+` for a blank —
so omission deletes, order reorders, repetition duplicates, a subset extracts; 1-based like `redact`.
It holds the old→new table and applies it to the three positional reference kinds AT ONCE
(`OCDStructNode.Ref`, `OCDOutline.pageIndex`, `OCDLink.pageIndex`), drops what points at a page that is
gone *and reports it*, and re-sorts the logical tree so the reflowable projections follow. A bookmark
to a deleted page keeps its title and loses its destination. Without it, a bare `pages().remove(i)`
left a bookmark and a link on index 5 of a five-page document and both writers emitted that without a
word. Exposed as `ConvertOptions.PAGES` (`--pages=` / `?pages=`), applied AFTER redaction (so a
redaction spec always names SOURCE pages) and before any projection: `--pages=1-3 --to=pdf` is an
extract, `?to=ocd&pages=…` is what a page organiser commits.

**`OCDNode.copy()` is abstract** — the compiler names any type that forgets it — and
`OCDPage.copy(id)` copies geometry, the content subtree, clips, links, annotations and fields while
SHARING the document resources (fonts, image bytes, media, layers). `JxPath` is a `Path2D`, so path
and clip geometry are duplicated, never shared. **The gate is byte equality of the projection**: a
page and its copy must render to the same SVG-OCD (the COPY gate in `test/Gates.java`, run on every push; measured 10/10 pages over
four documents, plus an independence check). `Pages` copies from a repeat's second occurrence on;
before, a repeat was one object twice, so editing page 4 silently changed page 9.

**`OcdMembers` keys `annots.json` by POSITION.** It keyed by the page's own id, which is minted
positionally, so after a reorder a page's annotations and form fields were written onto whichever page
then carried that id. Measured on the rich fixture reversed: two fields and two annotations on the
wrong page while the link on the same page was right.

**A page that appears twice appears once in the STRUCTURE** — decided and stated in `doc/FORMAT.md`,
not an accident: two members and two spine entries (PDF and EPUB carry both) while the logical tree
keeps one subtree, because a structure reference names a position and that position is the first
occurrence. The tree is the document's CONTENT: repeating a page repeats its presentation without
adding anything to say, whereas reordering DOES move the tree because it changes the reading sequence.
So md/doctags/html show a duplicate once and a reorder in its new order.

### `addFont` — a font the document did not carry (2026-09-07)

Two doors, one implementation: `--addFont=<path> --addFontChars="…"` for the CLI (a path is enough) and
`POST /api/font` for a browser, which only has bytes — `/api/convert`'s body is already the container,
so a second payload needs a route of its own. The route answers a one-font `pages/fonts.svg` from the SAME
emitter (`SvgOcdWriter.glyphsSvg` on a document holding just that font), and `book.addFont()` splices
the `<g>` into the library before `</defs>`. `scripts/FontBlock.java` is that answer for the bench.
Measured from the browser: alias `f2` minted, 14 glyphs, a run written with it, and the PDF prints
`Bonjour, Jean-Luc!` in DejaVu Serif. **The alias is not stable across an engine write** — `Fonts`
re-derives `fN` from a TreeSet of names, so the client's `f2` came back `f0`; `data-f` is the authority
for which font, and the client already keys on it.


`--addFont=<ttf> --addFontChars="…"` puts an existing font's outlines into the MODEL, so
`pages/fonts.svg` is written from them exactly like every imported font and nothing downstream learns a
special case. It is `Conversion`'s, applied before the page set (a font is a document-scoped resource);
`convert.TtfImporter` reads the outlines, and the default character set is printable ASCII — a few KB
against a megabyte, because a font is added for the characters you will type.

Measured end to end: DejaVu Serif + `"Bonjour, Jean-Luc!"` → **14 glyphs**, written in `fonts.svg` in the
same `<g id data-f data-cmap>` shape as the imported ones, `write(read(x)) == x`, and a hand-authored
run referencing them **prints in DejaVu Serif** through the PDF writer. With the option absent: 186/186
targets byte-identical, gates green — a new option that changes nothing when unused.

**Which projections carry authored text, and why.** `svg` · fixed-layout `epub` · `pdf` paint it (they
follow `z`); `md` · `doctags` · reflowable `html` do NOT, because they project the LOGICAL tree and
nothing references the new run — `structures.json` never mentions it. That is the two-layer contract
working, not a bug: a client that wants the content projections to see its text must also place it in
the structure.

**Every character must be accounted for.** A run's `data-text` is aligned against its painted glyphs, so a
character that is neither a `<use>` nor a `data-blanks` entry TRUNCATES the run's tail: `Bonjour, Jean-Luc!`
came back `Bonjour, Jean-Luc` because the unmapped space was silently skipped. The engine-side version
of this (NBSP) is in the format notes; this is the same trap from the AUTHORING side, and it is silent.

### Delivery hygiene: `zip` ADDS, and the shell has no brace expansion (2026-09-07)

Two traps that put junk in a delivered archive, both mine, both silent:

**`zip -qr out.zip dir` appends to an existing `out.zip`.** A first attempt that failed halfway still
wrote an archive; the retry added its files to it, and the delivery went out carrying the debris of the
attempt. **Always `rm -f` the archive before building it**, and list the result before shipping.

**The shell running these commands does not expand braces.** `mkdir -p a/{b,c}` creates a directory
literally named `{b,c}` — which then got zipped. Use a loop, or one `mkdir -p` per path.

Both are invisible unless the archive is opened, which is why the check is now: rebuild from nothing,
`unzip -l`, and `cmp` every entry against the tree.

### Commands for Jean-Luc's machine: PowerShell, one line, no backticks (2026-09-14)

He works on **Windows, in PowerShell**, and pastes what he is given. Every rule below was paid for in
one session.

**One command per line. NEVER a backtick continuation.** A multi-line `git add ... \`` pasted into
PowerShell fragmented, and the shell turned the leftover fragments of git's own help text into FILES:
seven of them at the repo root, 15 043 bytes each, named `as control characters.`,
`in parentheses indicate the behavior if N is given.`, `t all.` … If a command is too long for one line,
**split it into several commands** — `git add` is cumulative, the index keeps everything until the
commit. Never hand him a line that can survive being cut in half.

**And they were hard to delete, which is the second half of the trap.** Windows strips trailing dots
when it normalises a path, so `Remove-Item` on a file whose name ends with `.` looks for a name without
it and reports `PathNotFound`. The way out is the `\\?\` prefix, through .NET rather than the provider:

```powershell
Get-ChildItem C:\path -File | Where-Object { $_.Name -match ' ' } | ForEach-Object { [System.IO.File]::Delete('\\?\' + $_.FullName) }
```

**Any destructive command gets a dry run FIRST, as its own line**: the same pipeline ending in
`Select-Object Name, Length` so he reads the list before anything is removed. Never pipe a
`Get-ChildItem` straight into `Remove-Item` in the first message.

**Never make him type one of those names.** They carry spaces, apostrophes, commas, a leading dash.
Match them with a filter and pipe the objects — bound as `-LiteralPath`, so nothing is re-interpreted.

**Kill the pager before any `git diff` / `git log`.** A `git diff nimbus-jexter.cjs` (1720 lines) filled
his console and cost him the terminal. `git config --global core.pager cat` once, and prefer
`git diff --stat` when the point is the size of a change rather than its content.

**Absolute paths, and say which project.** He has several repos open and was in `C:\Projects\IntroProg`
while cleaning `C:\Projects\SugarcubeJexter`. A relative path in a pasted command is a different folder
than the one meant.

**A commit plan states every file, and flags the ones that are not mine.** `git status` showed
`nimbus-jexter.cjs` and `RedactionAudit.java` modified by HIM, before the session — they go in his
commit, or in a named one, never silently inside mine. And a NEW file shows as `??`: it is the one an
incomplete `git add` drops without any error at all.

### PASSENGERS — the container carries what the model does not own (2026-09-07)

An OCD round trip used to be lossy for everything but the model: `prism/augment.json` was dropped,
so the client BYPASSED the engine for `to === 'ocd'` whenever augmentations existed. That exception
had a second, worse effect — the bypass also skipped the **redaction pass**, so the one export that
must never be an exception was one.

`OCDDocument.passengers()` is the fix: any container member the model does not own (a viewer sidecar,
a house file) is read verbatim and written back at the same path, and DECLARED in the manifest or
epubcheck refuses the result. `OCDReader.isPassenger` excludes everything the writer rebuilds —
`pages/`, `images/`, `media/`, `jexter/`, the OPF, nav, NCX — because carrying a copy of something that
is rebuilt writes it twice and the two drift.

Measured: augmentation intact through a round trip · `write(read(x)) == x` with a passenger ·
**epubcheck 0/0/0** with the manifest entry `<item id="px-1" href="prism/augment.json"
media-type="application/json"/>` · a container carrying BOTH a passenger and a pending redaction comes
out with the augmentation kept and the redaction applied (the covered title gone from the read-out) ·
**186/186 byte-identical** for documents with no passenger.

The client's bypass is gone: ONE export path. The warning now fires only for targets that really lose
the augmentations (EPUB/PDF/HTML), and NOT for OCD — both verified in Chromium.

(An earlier claim in this file that "the link is baked into the delivered EPUB" was WRONG: the
`example.ch` found in the output was the fixture's own body text, not the augmentation. Augmentations
are still not baked into any deliverable — that remains a feature, not a fix.)

### Augment spoke the OLD grammar, and its wrapper leaked (2026-09-07)

`selectEls` looked for `<text>`, `<path>`, `<image>`. An OCD-EPUB page carries **none of the first two**:
a run is `g[data-ocd="t"]` over `<use>`s of the shared library (measured: 0 `<text>`, 0 `<path>`, 822
`<use>`, 18 runs). So Animate answered *"No SVG content in that zone"* over a page covered in content
and never opened its dialog — not a regression, it had never worked on this format. The selectors are
now the v2 ones, and they must stay SELECTORS because `applyAnim` re-applies them with
`querySelectorAll(t)[i]`.

**Then the repair exposed the real defect.** `applyAnim` wraps runs in a `<g>` to carry the CSS, and
that wrapper went **straight into the member** the moment another tool persisted the page — six of them.
Marking it `data-ui` would have been worse: `xhtml()` REMOVES those, children included, deleting six
runs. Hence one new rule in the one serializer: **`data-ui="wrap"` is unwrapped, not removed** —
children lifted, wrapper dropped. Measured: 0 wrappers in the member, 18 runs and 822 `<use>` before and
after, and the engine still reads the text.

The animation itself is right by the architecture: **capability as DATA in the container** (the sidecar),
**behaviour in the viewer**. The engine's own writers emit no `<script>` in any target today — that was
measured, not assumed.

### Two names that had to change (2026-09-07)

**`convert` → `pdfimport`.** A newcomer looked for `Conversion` — the single entry point from a source
document to any artifact — inside a package called `convert`, which is in fact the PDFBox-coupled PDF
IMPORT layer and nothing else. It is nothing else only since the same day: `Analysis`, the pipeline
orchestrator, lived there beside the import it had been factored out of while importing nothing from
it and nothing from PDFBox. It now sits in `ocd.analysis`, the package whose stages it drives, and
`convert/Analysis.java` in every doc became `ocd/analysis/Analysis.java`.

**`OCDStruct` → `OCDStructNode`.** A node of the logical tree (297 mentions) and `OCDStructure`, the
named provenance-carrying tree over it (54), differed by three letters. The pair reads by itself now.
The serialized format did NOT move: no JSON key, no `data-*` attribute, no member name contains either
identifier, and the byte A/B says so.

**A rename with "no consumers" had one: this skill.** `gates.sh` went red on `ModelRt`, `Straddle` and
`Clips` — ten scripts here import the engine. The verification harness is a consumer like any other,
and it is the one that will notice first.

Proof it was a rename and not a change: **186/186 targets byte-identical**, gates green, epubcheck
0/0/0, `write(read(x)) == x`, doclint 0 (it resolves every `{@link}`, so a moved class breaks the build
rather than rotting — three dangling links surfaced that way and were fixed).

### Writing text in Prism — the affordance (2026-09-07)

Edit's *Write* group: the text, its size, a **font picker fed by the document** (`select`, the ribbon
control kind whose value set is the document's), **Place** (arms ONE click, taken in the capture phase
so it writes where you point instead of picking what is there) and **Font…** (a file → `/api/font` →
`book.addFont`). A placed run is selected immediately, so the gizmo already bound to the page adjusts
it; selecting a run puts its text in the field, and **Retype** rewrites it in place — a remove plus a
write on ONE undo entry, so a single Ctrl+Z puts the old one back (measured: 19 runs before, 19 after,
19 after the undo, with the first text restored).

It belongs to Edit and not to Augment: Augment attaches a sidecar baked at export, a run is page
CONTENT. And a container's font is a **SUBSET** — writing *Premier jet* in a font imported for
*Bonjour, Jean-Luc!* prints `re erje`, correctly and in silence. The tool now names the characters that
will not paint; without that the reader sees a bug where the format is behaving exactly as specified.

### The font pair: two halves of one loop, deliberately not joined (2026-09-07)

`convert.TtfImporter` (TTF → per-glyph em outlines, FontBox-coupled) emits exactly the shape
`font.Foundry`'s request accepts as its "already vectorized" branch. They are the load and the build of
one loop — and **Foundry must not import TtfImporter**: `font` is deliberately FontBox-free so the
compilation side parses without a font library, and `font → convert` would be the dependency graph's
only backward edge (there are none). The caller composes them; `scripts/FontLoop.java` is that caller,
and the loop is measured. Writing text in Prism with an arbitrary font would use the same half, plus an
engine route that adds the OCDFont to the container's `pages/fonts.svg` — a feature, not a wiring.

### Two public classes with no caller in the tree (2026-09-07)

`JxMedia` (mp4/mp3 probe) and `Foundry` (drawn bitmaps → an OpenType font) are named by no other file.
**Kept, deliberately** — they are capabilities the README advertises — but a capability nobody calls is a
promise nobody has checked, so each now has a caller under `scripts/` and both were exercised: JxMedia
agrees with ffprobe to the millisecond, Foundry's output embeds in PDFBox and sets text correctly.
(`JxProps` — a typed view over a string map, superseded by `ConvertOptions.fromMap` and named nowhere —
was deleted instead: 116 → 115 files. `TtfImporter` was deleted too and then RESTORED: it is not an
orphan, it is the half above.)

### The scan path, measured through the client (2026-09-07)

Repairing a badly redacted SCAN is the main case, and two things about it can only be checked from the
browser: the repainted pixels must reach the client's container, and the image member **keeps its name**,
so the same URL has to serve different bytes (a cached response would leave the old pixels on screen over
a clean file). Measured on a fixture whose box covers a passport number: after Audit → Apply the client's
own `images/img_0001.png` has a uniform-black band where the number was, the reader shows black there,
and a second audit comes back EMPTY — the repair proves itself.

**A drawn zone over a pre-existing box: it depends whether the BOX survives the zone.** The audit is
GEOMETRIC — an opaque box above an image is a leak whatever the pixels now hold — and `Redactor.apply`
scopes its proof to the zones the caller named. Measured on one fixture and one box: a zone covering
**half** the box is refused (`redaction incomplete — still recoverable: IMAGE_UNDER_BOX`), because the
surviving fragment goes on hiding; a zone covering the **whole** box removes it, and the result is proven
clean. So the honest advice is "cover the whole box, or audit it", not "drawing can never repair" — a
wrong explanation in a warning is worse than the raw exception, which at least admits it does not know.
Prism reads the page and the kind out of the engine's answer and says exactly that.

### The `patch` projection must return EVERY member the edit changed (2026-09-07)

`Conversion` calls `Redactor.purgeMeta` when `redactMeta` is set, but `OcdEpubWriter.patch` returned
only pages, images, `structures.json` and `annots.json` — so the purge ran on the server's model and was
DISCARDED: Prism's container kept the title, the authors and the keywords that quote the very text the
zones removed, and exported them. Measured A/B through the engine (off: title intact; on, before the
fix: identical; after: empty). The lesson generalises to any future page-scoped tool: **no gate looks at
a member a projection declines to write**, so the only proof is to ask the client's container what it
holds after the round trip. `patch` now emits `meta.json` exactly when the purge ran.

### Checking a redaction is a VIEW, not an option (2026-09-07)

**`redactFill`'s alpha is not the answer to "did the text go?"** Offering the mark's opacity in the
workbench meant answering a viewing question by changing the DOCUMENT — you would ship a see-through
mark to be able to look through it. The alpha stays an engine option (CLI/API, now stated in the
registry); the workbench has **Reveal**, which takes the paint away and writes nothing.

Every way of hiding text looks identical to a reader, and they need different detectors:

| how it hides | what states it | Reveal's answer |
|---|---|---|
| text under an opaque box | paint order over the model — **`RedactionAudit`'s**, not the client's | the finding's `box` id outlined, its `node` id painted |
| an IMAGE under a box (every "redacted" scan) | the same findings, kind `image_under_box` | the box outlined — the pixels were never gone. **Count the FINDINGS, not the runs**: walking `g[data-ocd="t"]` made the tool announce "nothing hidden" while outlining the box that hid a passport number |
| ink the same colour as what is UNDER it | computed `fill` vs the last opaque path preceding it in paint order that contains it (else the page: white) | painted in the signal colour. **Not "is it white"**: white on a black banner is ordinary design and was flagged as a leak — measured; and the colour test could not even ask about black on a black band, which this one catches. Cost: the opaque shapes are collected once per page, and with none the backdrop IS the page, so the per-run box is skipped — 6–7 ms ordinary, 139 ms on 190 runs with a banner |
| zero alpha | computed `fill-opacity` 0 | idem |
| invisible render mode (every OCR layer) | `data-rm` 3 or 7, class `fill:none` | idem |
| a run whose glyphs have NO outline | 0 `<use>`, characters still in `data-text`, positions in `data-blanks` | a marker — there is no fill to change, and the run measures **0 px wide** |

- **A view mode may not set an attribute on a content node.** `persist()` strips `data-ui` ELEMENTS;
  an attribute rides into the member. Reveal is therefore one generated stylesheet keyed on the ids the
  page already states (`#t3 { fill:… }`, `#v1 { fill:none; stroke:… }`) plus a `data-ui` overlay.
- **A view mode must be re-applied to a RELOADED frame.** `book.refreshPage` (every Apply) gives the
  page a new document, and the `frame` replay dressed it with the overlay and the handles but not with
  the view: measured, after an Apply the reveal stylesheet was gone and **0 runs were flagged while the
  button still read `active`** — a checker asserting a page is clean because it had stopped looking.
  Any tool holding a view state owes the `frame` handler a line.
- **Do not re-derive what a box hides in the client.** It is a question about paint order over the
  model and the engine owns it; Reveal uses the audit's findings and SAYS so when there are none.
- Measured on a five-case fixture: 4 hidden runs found by their real reason, 9 ordinary runs untouched,
  toggling off restores every fill. Fixture note: the PDF text render mode is content-stream state and
  **reportlab does not re-emit `0 Tr`** for a new text object it believes is already at 0 — a `Tr 3`
  leaks into every later `drawString`, so the invisible run goes LAST (where a real OCR layer sits) or
  the whole page comes out invisible and the count means nothing.

### The Audit tab is gone: a tool that only LOOKS may not queue work (2026-09-14)

`audit.js` is merged into `redact.js` and deleted; the tab it registered is gone and Redact — still
`experimental: true`, so dev mode — carries the whole thing. What the merge had to get right is not the
tabs, it is WHERE a finding lives.

**Two lists, and the wall between them is the architecture.** `st.report` is the VERDICT: the
`to=audit` answer, which now runs BY ITSELF on entering the tool and on every new document, because a
verdict is not a command you go hunting for — you opened the document to know. `st.items` is the WORK:
what Apply removes. They may not be one list, and the reason is mechanical: `persist()` writes
`st.items` into `OEBPS/ocd/redact.json`, and `engine.js` passes that member to `--redact` on **every
export**. A verdict that flowed into the item list would mean that opening a tab and exporting repairs
the document nobody asked to repair. **Repair** is the one verb that crosses. Generalise it: anything a
tool does without being asked writes nothing; anything that writes is a verb the reader presses.

Measured in Chromium against a real engine (`scripts/bench/redactaudit.mjs`, on the `fake.pdf` fixture):
on entry, `2 findings · 1 page affected` with 2 `.rd-find` on the page and **no `ocd/redact.json` in the
container**; Repair → 2 items, 0 loose findings, and still **2 rectangles, not 4** (an adopted finding is
drawn once, by identity `page|kind|rect` — the box id cannot be the key, a finding with no covering box
has none); Apply → the engine patches, and the verdict is asked AGAIN and comes back `Clean · 1 page
checked`. Leaving the tool leaves `data-ui` at 0. Zero console errors.

What was dropped from `audit.js` on purpose: its text-on-a-plate overlay (Reveal is the better proof —
the real glyphs, in place — and the words are already in the list and the tooltip) and its Prev/Next
(a click on a list row already goes there).

### The client and the engine had drifted on THREE attribute names (2026-09-14)

Same class as `data-rot`/`data-rotate`, and found while merging the tools. The engine writes and reads
the v2 grammar; `ocd.js`'s AUTHORING path still spoke v1:

- **`data-b` vs `data-blanks`** — the one with teeth. `ocd.js` recorded a run's inkless glyphs as
  `data-b`; `OCDReader` reads `data-blanks`. So every sentinel a client authored was invisible to the
  engine, which then aligned N−1 glyphs against N characters and truncated the tail — the exact failure
  both files' comments describe as fixed. **Measured, A/B on one page**: typing `Bonjour, Jean-Luc!` and
  reading the container back through `Conversion` gave `Bonjour, Je` before and `Bonjour, Jean-Luc!`
  after. `trace.js` (twice) and `redact.js` read the dead name too — Trace's blank count was always 0 and
  Reveal's "no glyph" marker was never drawn while the toast counted it.
- **`data-ocd="p"` / `"l"` vs `"paragraph"` / `"line"`** — fixed the same day, with its own measurement.
  `OCDReader` switches on `data-ocd`; a value it does not know falls into the DEFAULT branch, which is
  the pure paint carrier — children walked straight through, parent unchanged. So an authored run was
  **spliced out of its paragraph on the first engine read**: measured on an authored page, `p` and `l`
  present before the round trip, gone after, the run reparented to the page root while its text stayed
  perfect. No text gate sees that; the kinds the re-written page states do. The paragraph id now follows
  the engine's `pN` scheme (next free on the page) for the reason the run id already followed `tN` — a
  structure reference names a node by id, and a page mixing two id schemes has ids nobody can predict.
  After: `paragraph 1→2`, `line 1→2`, ids `p1 p2 / t1 t2`, and `write(read(x)) == x` byte-identical
  (14 078 B twice, `SOURCE_DATE_EPOCH` pinned).
- **Every FONT METRIC, and it had been 0 all along.** `parseFonts` read `data-asc` · `data-desc` ·
  `data-cap` · `data-x` · `data-sp`; `SvgOcdWriter` writes `data-ascent` · `data-descent` ·
  `data-capheight` · `data-xheight` · `data-space` (FORMAT §B6). A regex parser asked for a name that is
  not there returns nothing and nothing complains: the page renders, every gate passes, and **both** font
  panels — Prism's and PDFInspector's, one `ocd.js` — silently dropped their cap-height and x-height
  guides and fell back to .75/.25 for the baseline, while `f.space` (the advance an authored run gives a
  character the face cannot paint) was never the face's. Measured on Helvetica, client vs the member:
  `0/0/0/0/0` → `0.718 / −0.207 / 0.718 / 0.523 / 0.278`. `scripts/bench/fontmetrics.mjs` is that
  comparison, and it asserts the client's numbers ARE the container's.

**The standing check this earns**, and it is mechanical — run it after any grammar change:

```bash
python3 - <<'EOF'
import re, glob
eng = set()
for f in ['src/sugarcube/jexter/write/SvgOcdWriter.java','src/sugarcube/jexter/write/SvgWriter.java',
          'src/sugarcube/jexter/write/OcdEpubWriter.java','src/sugarcube/jexter/ocd/io/OCDReader.java',
          'src/sugarcube/jexter/ocd/io/OCDVocab.java']:
    eng |= set(re.findall(r'data-[a-z][a-z0-9-]*', open(f, encoding='utf-8').read()))
for f in glob.glob('src/sugarcube/jexter/ui/**/*.js', recursive=True):
    for t in set(re.findall(r'data-[a-z][a-z0-9-]*', open(f, encoding='utf-8').read())) - eng:
        print(f'{t:16} {f}')      # chrome (data-ui, data-z, data-i…) is expected; a GRAMMAR name here is a bug
EOF
```

It is what found the font metrics — three names nobody would have thought to doubt, because the panels
that use them had always looked plausible. A hand-rolled client and a Java writer are two authors of one
format, and nothing in the tree makes them agree.

### Two ways this harness had measured nothing (2026-09-14)

Both found by running them, not by reading them — and both had been green for as long as they existed:

- **`bench/authortext.mjs` read `data-u` and `data-b`**, two names that died with v2, so it printed
  `null, null` and asserted nothing at all. It now closes the loop: author, persist, re-export the
  container with the engine, and compare the run the engine RE-WRITES against what was typed. Note the
  probe: `to=md` is the wrong one — md and doctags project the LOGICAL tree and nothing references an
  authored run, so they report an empty line whatever the reader did.
- **`make_redaction_fixtures.py`'s `legit.pdf` never was the clean case.** It sets the fill to yellow for
  the highlight rectangle and never resets it, so reportlab draws the text in yellow ON yellow: the audit
  reports `invisible_text` and is RIGHT. The docstring said CLEAN, so the false-positive case had never
  been exercised. With `setFillColorRGB(0, 0, 0)` restored before the `drawString`: `clean: true,
  findings: []`.

### The ribbon's height belongs to the CHASSIS, not to a CSS comment (2026-09-14)

Forms' ribbon stood 84 px against 72 on every other tool — a visible jolt of the stage when you switch
to it. The cause is one line of a declaration: a WIDE control (`input[type=search|text]`) is 1.9rem
where a small one is 1.15rem — the icon's height — so it fills the box on its own, and `prism.css` says
so in as many words ("small controls are captioned, wide ones speak for themselves"). Forms gave its
Value field a `label` as well; caption + 1.9rem input = 55 px against a command's 43, and the row grew.

**Stating a rule is not enforcing it.** The fix is in `control()`, not in Forms: a wide control now takes
its `label` as its PLACEHOLDER and renders no caption, so the name is not lost and no tool can grow the
row by declaring one. Measured after: 72 px on all eight tools at 1500 / 1000 / 800 px, the field box
exactly a command's 43 px, and Forms' Value field still showing the selected field's name and writing
through (`scripts/bench/formsvalue.mjs`).

`scripts/bench/heights2.mjs` is why it survived: it PRINTED `forms ribbon=84px` next to seven 72s and
asserted nothing. It now fails, names the offending tool and prints the tallest thing inside its row —
which is always what grew it.

### Draw in DOM order · read in `data-order` (2026-09-14)

**The invariant, and it has a direction.** A page is written in PAINT order because the RENDERING is the
priority and may never regress — the DOM *is* the paint, and drawing the children in the order they
appear reproduces the document exactly, with no structure involved. The logical layer is recovered
SECOND, on top of a rendering that is already right, and it is purely additive: it states things by
reference and never moves a pixel. `data-order` is that statement for reading order, emitted ONLY where
the two orders disagree. So a consumer splits in two — **anything that paints, hit-tests or measures
takes the DOM as it stands; anything that READS the content re-sorts first.** Getting it backwards is
silent: the page still looks perfect and only the words come out wrong, on exactly the pages where the
order was worth stating. Now written in FORMAT §B3, FRONTEND, the README, and once in code as
`ocd.js`'s `inReadingOrder` / `readingRuns`.

Prism had it backwards in three places at once, and every gate was green:

| what | walked | consequence |
|---|---|---|
| `hooks.ttsNodes` (engine.js) | `querySelectorAll('[data-ocd="paragraph"]')` | read-aloud speaks the page in paint order |
| the SEARCH index (engine.js) | a regex over the raw XML, under a comment saying "document order is reading order" | a phrase spanning two blocks stops matching; the snippet reads backwards |
| the **Flow** overlay (analysis.js) | the same DOM walk | an overlay whose entire subject is the reading order drew the arrows of a page the model does not have |

Measured on a page painted lower-block-first: read-aloud AND search both said `SECOND · FIRST` while the
engine's own Markdown said `FIRST · SECOND`. Also fixed: the block labels in the analysis tree, which
quote a paragraph's runs.

**And the performance half, which is where the first fix was wrong.** Parsing every page to re-sort it
cost **352 ms for 60 pages** against **1.8 ms** for the regex it replaced — twelve seconds at open on a
500-page book, to fix the order of pages that have none. But `data-order` is emitted only where the two
orders differ, so *a page that does not contain the string needs no parse at all*: the seam keeps the
regex, tests the decoded text for the substring, and parses only the pages that say otherwise. On the
whole fixture corpus that is zero pages. `scripts/bench/indexcost.mjs` keeps both numbers.

Hit-testing, picking and measuring were checked and are right to walk the DOM (`trace.js`, `redact.js`,
`editor.js` all resolve from a pointer event). Still unchecked, and worth a scenario the day it matters:
selection and copy.

### The SKILL rots like the code — and a rotted skill sends the next session off to rebuild (2026-09-21)

Everything in the rot sweep below was run over the REPO. Run over this skill a week later, the same
grep found the same class of defect in the instructions themselves — and these are worse than a stale
comment in the tree, because a new session TRUSTS them:

| what | consequence |
|---|---|
| §5c documented `sugarcube.jexter.convert.PdfImporter` twice | `ClassNotFoundException`; the next session concluded the visual-compare tool did not exist and set out to build one |
| `RenderOcd.java` imported every input as a PDF | an `.ocd.epub` candidate died in PDFBox's parser — the CONTAINER path of `compare.py` could not run at all. It now reads a container with `OCDReader` and a PDF with `PdfImporter` |
| `make_hostile.py` injected into `data-u`, `data-f` and a member ending in `f.svg` | **4 of 7 hostile fixtures were the healthy file**: an XXE never referenced, a billion-laughs bomb never detonated, a bad font never named, the fonts member never dropped — every gate fed with them passed and proved nothing about the reader. The script now targets `data-text` / `data-font` / `pages/fonts.svg`, COUNTS each payload's hits and fails on a dud (badclip is *n/a*, not a dud, on a page that does not clip — most don't); with real payloads the reader refuses xxe, billion, badmat and trunc and reads badclip, badfont and nofonts with the warning the contract demands |
| `gates.sh` gate 5 (Hostile) could never go red | any failure of the generator printed "(no good.ocd.epub to mutate)" and moved on, never touching `fail`; a variant read in SILENCE passed as well. It now fails on either — and the first time it could, it caught a stale `badclip` left in `$OUT/hostile` by an earlier run, so the corpus is emptied before each build |
| 10 bench scenarios selected `data-ocd="t"`, `data-u`, `data-fs`, `data-f`, `pages/f.svg` | each selected NOTHING and measured nothing — 24 names moved to v2 |
| 5 scenarios pressed `Audit`, a button this session renamed | they crashed on `undefined.click()`; the old `Audit` did verdict + adoption, so it is `Re-check` + `Repair` now, and a check that wants only the verdict presses `Re-check` |
| the Prism file map (`jexter.js`, `edit.js`), `convert/Analysis.java`, `OCDStruct`, `node nimbus.cjs`, `pages/f.svg` | wrong paths, class names and commands in the current-tense text of SKILL.md and four references |

**The rule this earns: a rename is not done until the SKILL folder is swept too** — `SKILL.md`, every
`references/*.md`, every script — and at least one documented command per section is RUN. Dated
narration of a rename ("X became Y on …") stays as written; a current-tense instruction naming X is a
defect. `authortext.mjs` names the dead v1 attributes on purpose, to catch a regression — the one file
the sweep must skip.

### The systematic rot sweep, and what a full pass costs (2026-09-14)

Run these six greps on the whole tree after any rename — the whole sweep is minutes, and every one of
them found something the day it was first run:

1. **Renamed identifiers, everywhere including comments and docs.** `sugarcube.jexter.convert` ·
   `convert/Analysis` · `OCDStruct` · `JexLab` · `jexter.js` · `edit.js` · `reveal.js` · `fonts.json` ·
   `OCDWriter` · `JxProps` · `pages/f.svg` · `data-rot` · `nimbus.cjs`. Found: eight comments still
   sending the reader to `jexter.js`, split into `engine.js` + `analysis.js` a week earlier; a
   `prism.css` header naming `pages/f.svg`; `.env`, `pom.xml` and `.gitignore` all printing
   `node nimbus.cjs`, which is not the name of the deck any more — pom.xml's is the line a newcomer
   types; three `OCDStruct` in `doc/typst/jexter.typ`, which is what the released overview PDF is built
   from; and **`OCDStructNode.toString()` still introducing itself as `OCDStruct[`**, so every debug
   line and every exception message said the old name.
2. **The engine's own comments against its own code.** `SvgOcdWriter` explained the blanks partition
   three times in terms of `data-b` while the line between them emitted `data-blanks`. A comment is read
   more often than the statement under it — that is where a client learns a name.
3. **Every `data-*` the client uses, against the grammar the writers state** (the script is in the
   client/engine section above).
4. **Every `$('#id')` / `getElementById` against the ids `index.html` declares** — clean today, once
   the dead tool was gone. Beware two false positives: ids built inside a template literal
   (`engine.js`'s `fx-*`) and classes built by concatenation (`fm-${status}`, `lv-${level}`,
   `'+tool-' + id`).
5. **Classes declared in `prism.css` with no user, and classes used with no rule.** That pair is what
   caught `compare.js` borrowing `au-group`.
6. **CI parity, reproduced locally**: `javac --release 21 -Xlint:all -Werror` over the whole tree
   (277 files, 0/0), `javadoc -Xdoclint:all,-missing` over ours only (rc=0), `node --check` on every
   module, and the local-link resolution pass over `src/**/*.html` + `README.md`.

**Prove a comment-only change is comment-only.** The Java edits above touch a `toString` and three
comments, so the artifacts must not move: five targets converted with `SOURCE_DATE_EPOCH` pinned, on the
build before and the build after — `ocd · epub · pdf · md · doctags` all byte-identical. That is the
cheap gate for any edit claimed to be cosmetic.

**Not defects, checked and dismissed** — so nobody spends the hour again: `audit.json` is the audit
TARGET's file name and matches a grep for `audit.js`; `pom.xml` is CRLF in the working copy, which is
what `* text=auto` in `.gitattributes` is for; the five homes of the JDK floor (`.env RELEASE`,
`pom.xml`, `build.yml`, `README`, AGENTS card) all still say 21; there is not one hardcoded list of
tool ids left in the CSS or the JS.

### FORMAT.md stated five things the writers do not do (2026-09-14)

A normative document is read by someone writing a consumer, and every one of these would have made
theirs wrong in a way no gate of ours would notice. Found by reading the spec AGAINST the members a real
conversion produces, which is the only way to read it:

| §A | the doc said | the container says | authority |
|---|---|---|---|
| A1 | `images/cover.png` | `images/cover.jpg` (Part B said so three pages later) | `EpubPackage.COVER_HREF` |
| A3 | `"page": "page-006"` | `"page": "p6"` | `OCDVocab.pageId` |
| A5 | `"pages": { "page-004": … }` | `"pages": { "p4": … }` | `OcdMembers.annotsJson` |
| A4 | `"source": "pdf_ua|heuristic|manual|llm"` | `pdf|heuristic|model|manual|other` | `OCDStructure.Source` |
| A4 | `"at": 1720000000` — "epoch" | epoch **millis** (`1789371763139`) | `JxClock.millis` |

The page-id ones are the expensive pair: the MEMBER is `pages/page-006.xhtml` and the ID is `p6`, and a
document that shows the file name where the id goes invites a consumer to write the one the reader
silently ignores. **Re-read the spec's examples against a produced container before a release**, the way
the doc-vs-source audit (`nimbus` skill, `scripts/doc_refs.py`) already does for names in prose.

### The press batch (2026-09-21) — eight causes, and why the reference saw none of two

Ten reports over eight press PDFs, the delivered outputs (`-OUT.pdf`) beside the sources. Re-normalized with
the build of the day, the outputs were within KB of the delivered ones: nothing since had touched any of it.
The workflow that worked: `batch_score.py` on the reported pages → crop the hot tile → extract the page with
pikepdf → bisect the content stream (drop one `Do`, keep only one) → read the operators. Eight causes:

| # | defect | cause | where |
|---|---|---|---|
| 1 | orange frames round stars and photos, fat coloured rules | a path neither filled nor stroked (zero-alpha fill) was WRITTEN — `m l l h` then `Q`; the path is not graphics state, it survives `Q`, and the next `S` strokes it | `PdfWriter.paintPath` returns early |
| 2 | a whole page red, logo doubled (63 %) | a Multiply group's blend was applied to each CHILD — PDF composites the group first | `OCDGroup.compositesAsOne` · `PdfWriter` writes a `/Group /Transparency` form (an `PDAppearanceStream`: PDFBox 3 opens a content stream on no other form, `PDAbstractContentStream` is package-private) · `OCDRenderer.paintAsOne` |
| 3 | yellow drop shadows came out dark grey | `BlendComposite` blended against a transparent pixel as if it were black — PDF §11.3.6 weights B(cb,cs) by the backdrop alpha | `BlendComposite.compose` |
| 4 | a translucent blue became an opaque slab over a photo | `sh` and gradient fills under an ExtGState SMask took their own vector route and dropped the mask | `beyondVectors` covers them |
| 5 | a cover more violet, a translucent layer too strong | the page's `/Group /CS /DeviceCMYK` was dropped: the page composites in CMYK, which gamut-maps every RGB photo on it | `OCDPage.cmykBlending` · `data-blending="cmyk"` · written back as the page `/Group` |
| 6 | a white halo behind a headline | a WHITE Multiply shadow, baked in isolation, is white; on the page it is invisible | `PdfImporter.liftBlend` lifts a uniform leaf blend onto the baked image |
| 7 | chart gridlines as black bars | pen under `0 -6.9853 0.14316 0 cm`: `transformWidth` averages both axes (11.8 pt), the ellipse is 0.34 pt across | `PdfStreamEngine.strokeOutline` — the exact outline, only when the CTM is anisotropic |
| 8 | phone digits dropped by 1.8 pt | a glyph 0.096 em off the run's baseline was kept in the run (tolerance 0.2 em) — a run has ONE baseline | tolerance 0.01 em; TextFid identical on all ten pages |

Plus, on the side: a CMYK colour is now CARRIED (`JxCmyk`, `data-cmyk` / `data-cmyk-stroke`, `"c m y k
#rrggbb"`) and written back as `k`/`K` while its `#rrggbb` still matches the node's paint. The conversion
itself was measured first and left alone: on 16 swatches MuPDF and PDFBox agree to a level (`1 .5 0 .2` →
0,94,157 vs 0,94,158), poppler does not — so the honest fix is to let each reader convert the source's own
colour, not to pick a profile. Also fixed on the way: `OCDRenderer` restored the clip BEFORE the transform
(a clip set under the node's matrix came back scaled by it — latent until the canvas carried a clip); both
SVG writers dropped a group's blend and alpha (`SvgWriter.groupStyle`); `ColorDodge` was written
`colordodge`, which no CSS knows and the reader turned into `Colordodge` (`SvgWriter.cssBlend`).

**A ninth, found only on the WHOLE file: the output intent.** doc-B's blue stayed "very slightly
different" after the CMYK was carried as `k`. The source's catalog holds `/OutputIntents` with Coated
FOGRA39 (`GTS_PDFX`), which Acrobat and MuPDF apply to every `DeviceCMYK`; our output had none, so the
same `k` went through the reader's default profile (0,94,157 instead of 0,89,154). It had been missed
because every probe ran on a **pikepdf page extract — which drops the catalog, and with it the intent,
the OCProperties, the outline**. Rule: a colour question is checked on the COMPLETE source, and a probe
that extracts a page says so. Now: `OCDOutputIntent` on the document, read at import, written back by
`PdfWriter`, carried in `ocd/meta.json` + `ocd/output-intent.icc` (`application/vnd.iccprofile`), and —
when the profile is CMYK — used for `DeviceCMYK` → sRGB at import (`pdfimport.OutputIntentCmyk`, one per
document, cached). Measured on 8 swatches: `ICC_ColorSpace.toRGB` through the intent lands within 1–2
levels of MuPDF-with-intent; a `ColorConvertOp` over the SAME profile lands on PDFBox's numbers instead —
use `toRGB`. Still not covered: CMYK IMAGES (decoded by PDFBox with its default profile) and the PDFBox
reference raster (`emitFromReference`), which ignores the intent too.

**Two of the eight are invisible to PDFBox, our reference.** It ignores a page group's colour space (5) and
strokes with the averaged width like we did (7): a pixel gate against PDFBox is green on exactly those
pages. Score a batch with poppler AND MuPDF (`batch_score.py` does both) — and read a disagreement between
them as information, not noise (poppler converts CMYK naively; MuPDF honours the blending space).

**Customer page numbers lie.** "page 3" was PDF page 37 — grep the text of every page for what the report
names before cropping anything.

**Result**: 235 pages, mean MuPDF diff 0.517 % → 0.137 %; 3 pages +0.05–0.10 pt, all sub-pixel AA on
inspection. Synthetic A/B: 138/145 byte-identical, the 7 that move (`color_cmyk`, `color_spot`,
`vector_transforms`) each closer to the source. epubcheck 0/0/0 × 3 flavours. `--to=audit` on doc-C p1
goes 68 → 87 findings: the reference-raster crops of cause 4 are IMAGES sitting under the ad's opaque
shapes (`image_under_box`), a truthful count, not a concealment.

**JDK 25 — FIXED the same evening.** `nimbus --run=jar` compiles with the machine's JDK (25 on Jean-Luc's),
`-Xlint:all -Werror`, and stopped on 61 `dangling-doc-comments` in `src/jj2000` — a lint JDK 23+ added. It
cannot be `@SuppressWarnings`'d (the comment attaches to nothing) and `-Xlint:-dangling-doc-comments` is an
INVALID FLAG to JDK 21, the CI gate. Fix: the 61 file-header `/**` → `/*` (58 files), comments only — proven
by the 177 jj2000 classes compiling byte-identical. Rule: a file-header comment is `/*`, never `/**`; and
before shipping, compile under BOTH JDK 21 and the newest JDK the user runs.

**Pre-existing, found, NOT fixed** (both red on the untouched build too): `ModelRt` reads a run's leading
TAB back as a space (doc-D p20: `[\t?]/[ ?]`); on doc-B the `badmat` hostile payload lands where the reader
never looks, so it is read in silence and gate 5 goes red.

### A downstream reader is a reference engine too: write images every decoder gets right (2026-10-15/16)

A press customer feeds the normalized PDF to its own PDF→OCD converter (not ours: a different "OCD").
Pages that Acrobat, PDFBox and poppler showed perfectly came out of it with flat colours as drifting
cyan/magenta STRIPES, while the SOURCE PDF converted fine. Its output container holds the images as it
decoded them, which is what settled it — compare those, not screenshots:

- **The cause, proven bit for bit.** Its PNG-predictor decoder reads the UPPER-LEFT neighbour as 0 at
  byte index == bpp (an off-by-one: `i > bpp` where `i >= bpp` is meant). Only Paeth uses that
  neighbour, so only Paeth rows go wrong, only at the first byte of the second pixel — the RED channel —
  and the error then runs right (via `a`) and down (via `b`): red-only streaks. A simulator with exactly
  that bug reproduces the converter's images **100.00 % of pixels, max diff 0**, on three documents;
  a correct decoder matches 6–49 %. Where the left margin is uniform (`a = b = c`) the bug is silent,
  which is why one large image survived and threw the first guess off.
- **Why the source was fine.** iText writes Sub on every row; the model stores lossless images as PNG
  and `PdfWriter` used to hand those bytes to `createFromByteArray`, which keeps the PNG encoder's
  per-row choice — on one batch **Paeth 15 350 · Up 4 418 · Average 251 · Sub 449**. 73 images over 5
  documents were corrupted by the converter.
- **The fix**: `write/PdfImages` writes PNG images itself — 8-bit DeviceRGB/DeviceGray samples, alpha as
  a separate `/SMask` (only when a pixel is translucent), Flate + Predictor 15 with **Sub on every row**,
  `DecodeParms` inline. Grey is read from the RASTER, never `getRGB` (Java's grey is linear: getRGB
  lightens it). A 1-bit PNG (every bilevel scan, every TIFF G4 page, which `imageimport` re-encodes as
  PNG) is written 1-bit DeviceGray, Flate, NO predictor — as before: the first version of the fix sent it
  through the 8-bit path and a black-and-white scan came out 8-bit RGB, 24× the data, every gate green
  (caught only by converting a 1-bit PNG and reading the XObject). "Bilevel" is decided from the PNG's
  colour model, never the pixels: an RGB image that merely looks black and white stays RGB, because with
  a CMYK output intent DeviceGray (black only) and DeviceRGB (rich black) print differently. 16-bit and
  non-PNG bytes keep PDFBox's path (no predictor; 16-bit comes out 8-bit RGB, as it always did); JPEG
  stays DCT. Normalizing to Sub is now part of the normalization contract. After it, the simulator
  finds **0 corrupted images** on the same 5 documents. Pixel A/B: 0 differing pixels in poppler AND
  PDFBox on the customer files, the 27-feature matrix and a grey/grey+alpha/palette/16-bit fixture;
  byte-reproducible; +9 % on a photo-heavy file.
- **The gate**: `python3 -I scripts/predictor_rows.py out.pdf` counts the row filters and exits 1 on any
  Up/Average/Paeth row. No pixel gate can see this defect — every engine we have decodes Paeth right.
- The lesson for any writer change: the gates prove the PDF is RIGHT; a customer's reader proves it is
  ORDINARY. Prefer the encoding the source producer used over the cleverest legal one. And when a
  consumer's output container is available, diff its decoded assets against ours before theorising —
  the first guess here (a signed-byte Paeth) was wrong.
- Still open, and the bigger win: a source JPEG with a soft mask is DECODED at import (`passThrough`
  refuses masked images), so it comes back as Flate — 2.6 MB in, 12 MB out on one file. Keeping the DCT
  bytes with a separate mask needs the model to carry the mask beside the image.

### Jexter's hot folder and its folders, driven for real (2026-09-21)

A press customer's report, three defects, each reproduced on the ORIGINAL build before being fixed:

- **Settings › Output folder › Choose worked once.** The button was wired to `ensureOutDir`, which
  returns the folder already chosen — every later click did nothing. `pickOutDir` always opens the
  picker; `ensureOutDir` only falls back to it.
- **A PDF re-delivered under the same name was never normalized.** Both the page (`hot.seen`, a Set of
  names never pruned) and the headless daemon (`j.out().exists()` → «already normalized») remembered a
  NAME. Both now key a source by `size:lastModified`, forget what disappears, and treat an existing
  output as «done» on the FIRST pass only (output newer than source = an earlier session's work).
- **The output folder was forgotten at restart.** localStorage stores strings; a
  `FileSystemDirectoryHandle` lives in IndexedDB only. Restored at boot; permission is (re)asked from
  a live gesture (`Normalize all`, `Hot folder…`) because a timer tick cannot prompt.
- New: `--done[=name]` (CLI) and «Move originals to» (Settings) move a source into `<inbox>/Done`
  after its output is written — the watcher never reads that sub-folder. On the CLI a FULL PATH works
  too (`--done=D:\Archive`, sub-tree mirrored): `root.resolve(abs)` returns `abs` — it did so before it
  was documented; the window keeps a sub-folder NAME (a folder elsewhere would need a second browser
  permission the hot-folder tick cannot ask for).
- New: `--suffix=<s>` — the daemon names outputs like the window's Settings field (one sanitizing rule,
  `Jexter.suffix`: no path/reserved characters, ≤ 40). An EMPTY suffix makes the output the source's
  name, so it is refused unless the output folder differs. Changing the suffix on a folder that holds
  outputs of the OLD suffix makes them sources again — by design (the window does the same).

**The window's UI, reworked the same day** (screens: `scripts/bench/jexter_shots.mjs`, light + dark, main /
Settings top and bottom / folder mode with an empty ending / Java API): a Settings gear in the header;
a «Saved as ‹name›-normalized.pdf next to each original · Change» line under the actions; Settings
explains every option behind a **?** button beside it (click shows the explanation under its row,
tinted with it; one open at a time; `aria-expanded` — `scripts/bench/jexter_help.mjs` drives them) —
never a hover-only tooltip, which a touch screen and a keyboard never see. Trap met: a container rule
like `.opt-sec-head svg{ color:brand }` also hit the ?'s own svg, so an OPEN section ? (white-on-brand
by design) drew a brand icon on brand — a plain blue disc; `.opt-q svg{ color:inherit }` and head rules
scoped to `> svg`. The bench now reads the computed icon/background colours of an open ?. Sections read
as sections: a tinted icon badge, the title in text colour, a rule to the right edge, 1.9rem between
them, and the CLI note set apart below a dashed rule — it belongs to no section — and exposes the one option that had no UI
(`cfg.lanes`, «PDFs converted at once»); the footer says Close, not «Done» (the Done folder is an
option). Two defects met: an EMPTY name ending matched every PDF as «an output» (`'' + '.pdf'`) — so
nothing dropped was accepted — and would have written the result over the original next to it; both
guarded. A `const` read by `applyCfgUI` must sit ABOVE `boot()` (TDZ: `MODE_HELP` threw twice at load,
only `pageerror` showed it). The API drawer now uses the jar name nimbus builds (`JAR_NAME` in `.env`,
`sugarcube-jexter.jar`), lists every CLI option checked against `JexterCli.Opts.parse`, and says the
command line does not read the window's Settings.

**How to test File System Access in the sandbox**: `scripts/bench/jexter_hotfolder.mjs` stubs
`showDirectoryPicker` to hand out **OPFS** directories (`navigator.storage.getDirectory()`) — real
`FileSystemDirectoryHandle`s, cloneable into IndexedDB, with `getFileHandle` / `createWritable` /
`removeEntry` — against the real Java server (`Jexter --serve --port 7349 --web <site>`, the CDN URLs
rewritten to `/vendor` by `jexter_site.sh`). A/B: old build `picks:1 label:out1 / none after reload /
1 normalized`, new `picks:2 label:out2 / out2 / 2 normalized, source in Done, output replaced`.
**Never `pkill -f 'Jexter --serve'` from the same command line** — the pattern matches the shell running
it, and the tool call dies with rc −1 and no output.

### Two client-side rots a browser finds in one call each (2026-09-14)

Both cheap, both now scenarios, both had been wrong for as long as they existed:

- **`compare.js` styled its list headings with `au-group`** — the Audit pane's class, not its own. Its
  own `cmp-group` was declared in `prism.css` and used by nobody. A borrowed class is a dependency on
  another tool that nothing states, and it came due the moment Audit was merged away and the class was
  renamed: the pane went bare. `scripts/bench/groupstyle.mjs` probes the three heading classes by
  COMPUTED style (a heading that lost its rule still shows its text) and checks `compare.js` emits its
  own.
- **An icon name that does not exist must not ship.** A hole in a row of line icons reads as a layout
  wobble rather than as a typo. Today (qry's icons) an unknown name draws a dashed square
  (`.qry-icon.is-missing`) and logs one console.error; `scripts/bench/icons.mjs` visits every tool and
  reports every `[data-icon]` without a drawn `.qry-icon` or with the square, and fails on any console
  error — 81 names, 0 dead (2026-10-17). Run it after adding a command.

**And a stub that pretended to be a fallback**: `engine.js` resolved a structure reference as
`doc.getElementById(id) || doc.getElementById(paraOf(pi, id))` with `const paraOf = () => ''` — so the
second lookup was `getElementById('')`, always null. Its own comment said why no fallback is needed
(a ref names a run or the paragraph around it, and `SvgOcdWriter` writes `id` on both). Removed: one
lookup, and the comment kept as the reason.

### A redaction zone is an object (2026-09-07)

Redact's zones are drawn AND adjusted: a click puts `gizmo.js`'s eight handles on one, rotation off (a
zone is an axis-aligned rectangle in page space — exactly `page:x,y,w,h`). Same component as the crop,
so the three space traps live in one place. What this taught, beyond the geometry:

- **The rect on the page is CHROME; the model is the item list.** The selection is therefore an
  INDEX, re-attached after every redraw — an element reference points at a node the next draw removes.
  And every operation that reshuffles the list (audit, find, apply, undo, clear) must DROP the
  selection: an index kept across a reshuffle names whatever moved into that slot, which is how a
  reader deletes a zone they never picked.
- **A tool's own picking must stand aside for the gizmo.** One guard does it —
  `e.target.closest('[data-ui]')` covers the handles AND the overlay's own rects, since the layer
  carries `data-ui`. Without it, a drag starting on a zone moved it *and* drew a band across it, and a
  click on a zone in the block-pick mode added a block underneath.
- Measured: draw → 8 handles at once; move +40/+30 exact; SE handle → the anchor holds to the unit;
  a number typed in the drawer moves the same rectangle; leaving the tool leaves **0 `data-ui`**
  elements; 0 console errors over the whole run.

### Forms, and the two hardcoded lists behind one feature (2026-09-13)

A field is LIVE DATA: no appearance stream is imported, nothing bakes one into a page, so
`ocd/annots.json` is the ONLY home of a form — and it held too little to state that form again.
`OCDFormField` now carries what a widget IS (`/MK /R`, `/Q`, the `/DA` size and colour, `/MK /BC` and
`/BG`, `/BS /W`, `/MaxLen`, hidden), `write/PdfForms` states it back as a real AcroForm, and
`write/PdfAnnots` does the same for links and review markup — both were dropped in silence before,
because an annotation is not content and a projection that walks content never sees it.

Two PDFBox traps, both silent, both costing a half-built object:

- **`PDAppearanceStream` is born without `/Resources`**, and `PDPageContentStream.setFont` writes
  straight into them → `NullPointerException`. The field is abandoned mid-construction (rect and
  border, no `/AP`, no value) and a check box quietly stops being one. `s.setResources(new PDResources())`.
- **ZapfDingbats has no glyph for `U+0034`.** The check mark is `U+2714`, which PDFBox *encodes* to
  byte `0x34` — Acrobat's own code. Name the CODEPOINT, never the code.
- PDFBox 3 removed the `SUB_TYPE_*` constants and made `PDAnnotationTextMarkup` abstract: each markup
  kind is its own class. And `/QuadPoints` order is upper-left, upper-right, lower-left, lower-right —
  the specification says otherwise and is wrong; follow it and nothing paints.

**The recurring defect of this codebase is a hardcoded list of tool ids**, and it comes in pairs. The
right pane could not be resized on Pages or Forms. The JS resolver named `augment`-or-`analysis`;
fixing it changed nothing, because a SECOND list in `prism.css` hid `#split-right` for every tool but
four. Both predated the tools that ship today. Grep for `tool-<id>` in css AND js before believing a
tool-specific behaviour has one cause. The cure is structural: the right pane is now ONE host
(`#pane-right`) with a slot per tool, the rail is the same `.px-pane` object with its own side
modifier, and every condition is a STATE (`body.has-drawer`) rather than a name.

Three more, each a one-line cause with a whole-feature effect:

- **`display:block` to re-show a `flex`.** `body.tool-pages.tool-pages-page #epub-stage { display:block }`
  un-hid the stage and dropped its centring and its gap: pages flush against the rail, touching. A rule
  that re-shows something must RESTORE what it was.
- **Focus given in the pointerdown capture phase is taken back** by the browser's default action,
  which moves it to the nearest focusable ancestor of what was clicked (an SVG rect has none → the
  body). `preventDefault()` on the grip, focus on the NEXT task — and focus the iframe itself, because
  focusing an element inside one does not make that frame the focused frame.
- **`getScreenCTM` stops at the SVG's own viewport.** A viewer that turns a page turns the FRAME, in
  CSS, and the gizmo never sees it: every resize arrow was named in page space and lied on any
  document stating `/Rotate`. Read a handle's cursor from WHERE IT LANDS (eight 45° sectors), with the
  host's turn composed in — never from the handle's name, which is also why a mirrored box showed east
  arrows on its west side.

And one design rule that fell out: a command must not fabricate a degenerate object. `Crop` proposed a
rectangle at the page's current window, which on an uncropped page IS the page — a crop that crops
nothing, with all eight handles on the bezel. It now arms the drawing instead, and only hands back a
window that exists.

## 6b · Build hygiene (NON-NEGOTIABLE)

**A colon followed by a space inside an unquoted YAML scalar opens a mapping.** `.github/workflows/build.yml`
carried an explanation inside a step name — `- name: Set up JDK 21 — the floor: what compiles …` — and
GitHub answered `Invalid workflow file … line 18`. The gate then validates NOTHING, on every push, in
silence: check the date of the last successful run before trusting anything merged since. Explanations
go in a comment above the step; em dashes are fine, the colon is not. Parse it locally
(`python3 -c "import yaml;yaml.safe_load(open('.github/workflows/build.yml'))"`) before pushing a
workflow edit.
Verify touched classes in the shipped jar with `javap -cp _prod/sugarcube-jexter.jar
-p <Class>` (new signatures must appear) — cheap insurance against ANY stale artefact.
(The historical "incremental stage" cause is gone: nimbus `prepare()` now rmrf's
`target/jar-stage`, the jar, and the app-image on every `jar`/`build` — verified
2026-09-01. `rm -rf target` is therefore harmless but no longer necessary.)

**What is jexter-specific about the build (the tool itself is the `nimbus` skill's):**
- `.env`: `JAR_MAIN` = `ui.prism.Prism` (so `java -jar` starts Prism), `LAUNCHERS` =
  Prism (primary → `SugarcubeJexter.exe`: jpackage names the main launcher after `--name`,
  whatever the LAUNCHERS label says), Jexter, PDFInspector; `KEEP_FILES` = the two vendored
  jars. `--run=jar` deletes the app-image; `--run=…` wants an absolute path. Nimbus's
  deliverables report prints `SugarcubeJexter/Prism` for the primary — that is the label,
  not the file (fixed in nimbus 1.3); the `.cmd` twins do carry the label (`Prism.cmd`).
  Since nimbus 1.3 `build` also leaves `_prod/SugarcubeJexter-1.0.0-win.zip` — the release
  asset, no `Compress-Archive` step. Measured 2026-09-02: jar 13.5 MB, app-image 136.9 MB,
  zipped 58 MB.
- `Jexter.main` is dual (GUI, or headless conversion / `--hotfolder` on a positional);
  the `.exe` is javaw → use `Jexter.cmd` (bundled runtime, real console) headless.
- Ports: Prism **7345** · PDFInspector **7346** · Jexter **7347** (was 7346, colliding
  with PDFInspector — `WebApp` then silently moved one of them to an ephemeral port).
- BouncyCastle is declared in the pom but not in `lib/` → the nimbus jar/app-image
  cannot open encrypted PDFs; Maven builds can. Drop the jars in `lib/` if the product
  needs it (then `KEEP_FILES`).

**Consumers:** the private consumer notes (account skill `repo-skills`) — jexter is a LIBRARY first; `sugarcube-cloud` compiles
against `write.Conversion`, `core.ConvertOptions`, `core.JxProgress`, `OCDReader`, `PdfImporter` and
`OCDMeta`. Read it before renaming or moving anything public; the CI compiles jexter, not its
consumers, so a break surfaces on their machine.

**Neighbouring skills:** `nimbus` (the build deck family, `scripts/doc_refs.py` — the doc-vs-source audit that found `structure()`
and `JDK 21+` in the card on 2026-09-01); `docling` (DocTags grammar as docling-core
parses it, the interop/text/structure gates, and the **OCD-AI** spec — jexter's DocTags
superset with ids + typography, `references/ocdai.md` there — read it before touching
`DocTagsWriter` or an OCD-AI writer).

## 7 · Production invariants (v1.0.0 public release — gate before shipping)

The repo (github.com/Bloechle/sugarcube-jexter) went **public on 2026-08-23** with a
deliberate tabula-rasa history: a single `v1.0.0` commit, the pre-release work squashed
away. `nimbus-jexter.cjs` at root builds the jar with zero deps (`node nimbus-jexter.cjs --run=jar`).
Root carries `LICENSE`, `NOTICE`, `README.md`, `CONTRIBUTING.md`, `SECURITY.md`,
`AGENTS.md`, `pom.xml`, and `doc/` holds the four normative documents PLUS `doc/typst/`
(the Typst source and 15 SVG figures). What is NOT in git: every `_*` path (`_artifacts/`,
`_private/`), `target/`, and the customer corpora — ask for a file when a task needs it.

It holds invariants that every engine change must keep. Gate them with
`scripts/production_audit.sh` (full recipe in `references/production-audit.md`):

- **Reproducibility** — one clock authority `core/JxClock` honors
  `SOURCE_DATE_EPOCH`: pinned ⇒ every target byte-identical across runs (zip
  entry times via `JxZip.entry()`, structure `at`, EPUB `dcterms:modified`
  fallback, PDF dates + trailer `/ID` via `PdfWriter.stamp()`). Unset = wall
  clock (real provenance is the correct default). `doc.id()` is
  content-addressed (SHA-256/8 of the source, `PdfImporter.contentId`) —
  identity never depends on the clock; `EpubPackage.uniqueId` is the ONE
  identifier for all three EPUB flavors (name-UUID over `jexter:<docId>`).
- **XXE hardening** — `core/JxXml.secureBuilder()` is the ONE XML parser
  (DOCTYPE rejected outright). Consequence: OCD-EPUB page wrappers carry **no**
  `<!DOCTYPE html>` (optional in XHTML5; OCDReader re-parses them). A forged
  `ocd.epub` with an internal-subset `SYSTEM` entity must die with «DOCTYPE is
  disallowed»; presentation HTML/EPUB keep their doctype (never re-parsed).
- **epubcheck — verified, and here is the exact recipe.** The jar is a GitHub release
  asset, so it is fetchable in the sandbox:
  ```bash
  cd /tmp && curl -sL -o ec.zip \
    https://github.com/w3c/epubcheck/releases/download/v5.1.0/epubcheck-5.1.0.zip
  unzip -q ec.zip && java -jar epubcheck-5.1.0/epubcheck.jar book.epub
  ```
  Demand «0 fatals / 0 errors / 0 warnings» on **all three flavours** (`--to=ocd`,
  `--to=epub`, `--to=epub-reflow`) — the ocd container is a valid EPUB too, and it is the
  one people forget to check. **Measured: 12/12 clean** (4 documents × 3 flavours, incl.
  the tagged, the rich-track and the unicode fixtures). The trap it guards is NCX
  `playOrder` (RSC-005): navPoints sharing a target must carry the SAME playOrder —
  several headings land on ONE fixed-layout page. `EpubPackage.Ncx` is the one numbering
  authority (playOrder = first-seen per href, ids stay `np-N`); verified on the tagged
  fixture, whose H1 and H2 both sit on page 1: `np-1 playOrder="1"`, `np-2 playOrder="1"`.
- **Bookmark path emits body** — `BookmarkStructureBuilder` anchors HEADINGs
  *and* wraps every other block as PARAGRAPH in reading order via
  `StructureBuilder.addParagraph` (the one wrapping authority). A bookmarked
  PDF's md/doctags must contain body text, not just titles.
- **Cover is a resource** — `OCDDocument.cover()` round-trips verbatim;
  `EpubPackage.cover` re-rasterizes only when absent. `write(read(x)) = x`
  must hold on EVERY member, cover included. Two guards keep it true: a model edit of page 1
  calls `dropCover()` (Redactor, Pages), and `meta.json` `cover` (v4) is `OCDVocab.coverKey` of the
  page-1 member, so the reader drops a cover whose page was edited outside the engine (EDITED gate).
  A patch that redacts page 1 carries the fresh cover and its `meta.json`.
- **Fonts** — `font/OtfBuilder` (abstract sealed) owns the shared sfnt tables;
  `CffOtf`/`GlyfOtf` keep only format-specific ones (`head`/`maxp`/cmap
  records/`OS/2`/`post` — metric constants are hand-tuned per upem grid, do
  NOT unify them). Any font-compiler change must pass the FontGate byte gate
  (dump both builders per font, cmp vs pre-change reference).
- **Model-boundary text is XML-clean** — `JxText.xmlChar` / `JxText.sanitize`
  (U+0000, U+FFFF, lone surrogates → U+FFFD, length-preserving) guard every
  unicode entering the model (`PdfStreamEngine`), with a skip net in
  `JxText.esc`. write(x) must ALWAYS be readable — CID fonts without ToUnicode
  are the classic source (Arabic corpus, arial_unicode_ab).
- **`JxTransform` IS the PDF/SVG order — `(a b c d) = (m00 m10 m01 m11)`.** It was not: it stored
  the row-major reading `(m00 m01 m10 m11)`, and PDF's `cm`, SVG's `matrix()` and Java2D's
  `AffineTransform(…)` all mean the other one. Every author who wrote the six numbers by habit
  wrote them in PDF order against a record that meant the transpose — invisible on a translation or a scale, a quarter
  turn wrong on a rotation, a mirrored slant on an italic shear. It bit twice in one day: a diagonal
  gradient re-emitted by `PdfWriter` (12.77% of a press page off, 0.12% once fixed), and a rotated
  soft-mask veil in the OCD-EPUB that every browser drew 115×230 where the model said 229×115 —
  visible in Prism, invisible in PDFBox and poppler because the Java side never round-trips through
  the text form. `JxTransform.toMatrix6` / `fromMatrix6` are now the ONE authority for that order,
  used by the SVG writers, `OCDReader` and `PdfWriter` alike; nothing else formats those six
  numbers by hand. The record was realigned rather than patched at each call site, and that ALONE
  fixed four silent bugs nobody had reported: `OCDRenderer` built its gradient `AffineTransform`
  from `(a b c d)`, `Spacer` measured scale with `hypot(a,b)`/`hypot(c,d)` (row norms where column
  norms were meant), and `Segmenter`/`Liner` read a block's angle as `atan2(b, a)`. All four were
  written in PDF order; they became correct the moment the record agreed with them. `rotate()` too:
  it emitted `(cos, sin, -sin, cos)`, which under the old layout turned by MINUS theta.
  Verification after the realignment: 27 synthetic fixtures — one container differs by a single
  byte (`text_rotated`), pixel-identical; 45 wild documents — three containers differ, all
  byte-for-byte the same length, all pixel-identical (0.000% ink lost, 0.000% colour); the veil
  measures 230×115 on the photo in Chromium.
- **The OCD-EPUB format owes NO backward compatibility — Jean-Luc, 2026-09-12.** It is not in
  official use anywhere: consumers take PDF. So when a stored form turns out to be wrong, FIX THE
  FORM; do not carry a reader that understands both, do not add a version flag, do not migrate.
  Containers written before a fix are regenerated from their source, which is always the PDF. This
  is a standing licence to keep the format clean, and it expires the day a third party stores
  `.ocd.epub` of their own — ask before assuming it still holds.
- **Chromium is available in the sandbox** (`node -e "require('playwright')"`, `chromium.launch()`
  works) — so a client-side rendering bug can be MEASURED, not guessed: open the container's page
  XHTML over `file://` and read `getBoundingClientRect()` on the node in question. That is what
  turned "Prism draws the veil in the wrong place" into "the emitted matrix is transposed", in one
  call.
- **NEVER write `W n` with no current path.** `PdfWriter.paint` set the clip as soon as a node
  carried one, without checking that the stored path had geometry — so an `OCDClip` with no area
  produced `q q W n BT`, a clip operator with nothing to clip. The spec leaves that undefined;
  PDFBox guards against the exact shape in its own renderer (PDFBOX-4949, "don't clip if W n only");
  **Acrobat refuses the page** — "An error exists on this page" — while PDFBox, poppler, MuPDF,
  Ghostscript AND pdf.js all read it without a word. That is the whole lesson: five engines
  agreeing means nothing when the sixth is the one your customer uses. Measured on a press cover:
  10 pathless clips on page 1 → 0; 3182 clips across the document, none pathless. It closed a
  family of FIFTEEN documents that had been reported as "error on opening" since the first batch.
  A clip with no geometry is now simply not written — the node is already dropped at import
  (see the entry below), and the writer no longer emits the operator either.
- **A node clipped to NOTHING must not be emitted.** A press producer leaves a stray rectangle on
  the path stack; a later `W` seizes it, and the block's own clip is then intersected against a
  rectangle that lies elsewhere on the page — the area is nil. PDFBox does exactly the same (its
  `PageDrawer.clip` even carries the PDFBOX-4949 guard "don't clip if W n only", which does not
  apply here because the path is NOT empty), and both PDFBox and poppler then simply paint nothing.
  We, faithfully, stored the empty clip in the model — where a downstream consumer helpfully
  IGNORES a clip with no area, and the hidden content reappears: an advertisement photo spilling
  over the masthead, ghost text over the title. Measured on the doc-A cover: 22 of 66 clips with no
  area; dropping those nodes at import took the page from 11.72% to 1.87% of pixels differing and
  0.252% to 0.060% of ink lost. The rule lives in ONE predicate (`clippedOut()`) used by both
  `emit` and `flushText` — a text run is committed by its own route and would otherwise be the one
  node type that survives a clip of nothing.
- **Pattern paint: sample the ramp, do not paint grey.** `PDPattern.toRGB` throws, so `resolveArgb`
  flattened every pattern fill to rgb(128,128,128) — which is not a colour, it is the fallback
  showing through: a mauve headline filled with an axial shading came out grey over 4457 pixels of
  a press page. A SHADING pattern has a ramp: it is sampled at 9 points and averaged
  (`shadingArgb`). Paths still carry the real `OCDGradient` and only land here for mesh shadings;
  text has one int of paint and this is the closest honest value. A TILING pattern has no ramp —
  it is a drawing — and stays neutral. doc-F page 39: 4457 → 179 fallback pixels.
- **JPEG 2000 hides in two places, and the declared filter lies.** Besides an image that IS a
  codestream, an ORDINARY image can carry a JPX SOFT MASK: `getImage()` then dies on the mask and
  the whole image is lost — a portfolio page lost both its images with a plain `/FlateDecode` base.
  `JpxImage.rescue` covers both shapes (opaque base from PDFBox + mask decoded here), and `isJpx`
  now checks the BYTES (JP2 signature / SOC marker) because that corpus image declares
  `/FlateDecode` and is handed to PDFBox's JPX filter all the same. 1.21% → 0.43% of pixels.
- **A glyph is blank when the FONT says so, not when `toUnicode` does.** `Cleaner` dropped every
  glyph whose unicode was whitespace — and a press subset whose capital maps to U+0020 is not a
  space, it is a letter about to be deleted, chasse included. Measured on a legal-notices
  page: `GEPNFI+GillSans-Bold` gid 7, `uni=" "`, advance 0.905, a full cap-height outline — "FIN DE
  LOCATION GERANCE" rendered "FI DE LOCATIO GERA CE". The test is now the outline
  (`OCDGlyph.isSpace()`), and a glyph with ink survives whatever the producer claims it means.
  Ink lost on that page 0.119% → 0.001%. Corpus cost: ONE document of 206 changes, an OCR layer
  that gains invisible glyphs, pixel-identical.
- **PDFBox cannot read JPEG 2000, and used to lose the image without a word.** No JAI tools in
  `lib/` → `MissingImageReaderException` inside `drawImage` → a full-page advertisement gone, with
  whatever sat under it showing through (doc-A, 12 JPX images of 64, ink lost 5.49% → 0.036%).
  The JJ2000 decoder is now VENDORED AS SOURCE in `src/jj2000/` (not a jar: an unprovenanced binary
  in the repo is worse), driven by `pdfimport.JpxImage`, which produces raw component SAMPLES and
  leaves the colour to PDFBox (`PDColorSpace.toRGBImage`) — DeviceN, Separation, Indexed, CMYK then
  behave as for every other codec. Three things learned the hard way: the parameter list must be
  built by asking EVERY module for its table (a hand-written subset dies on `Cer` in
  `createEntropyDecoder`); the soft mask must be applied by hand, because `getImage()` would have;
  and upstream (`jai-imageio/jai-imageio-jpeg2000`) holds exactly ONE real decoder fix —
  `setmask = (3 << bp) >> 1` overflows the sign bit at bp >= 30, three sites, taken. The rest of the
  upstream divergence is ImageIO plumbing (`Point` vs `Coord`, `J2KImageReadParamJava`), and its
  `FileFormatReader` reads JP2 boxes into `J2KMetadata` we have no use for — 53 of 147 files are
  identical once comments and formatting are normalised.
- **The gate survives vendored code without being weakened.** 64 of the 216 jj2000 files carry one
  `@SuppressWarnings({...}) // vendored JJ2000` — so `javac --release 21 -Xlint:all -Werror` still
  passes over the WHOLE tree in one command (331 files, 0/0). Doclint is the exception: 101 errors
  in 2001-era javadoc (`<tt>`, `@see` to packages renamed before vendoring), so `build.yml` lints
  `grep -v '^src/jj2000/' sources.txt` with `-sourcepath src`. Rewriting an upstream codec's prose
  to satisfy our linter would be maintaining a fork of its comments.
- **Score the batch, page by page, before and after.** A customer's report names pages; render
  exactly those, from the source, from the delivered output and from the new build, and put the two
  numbers that matter side by side — ink present in the source and absent from the output, and
  pixels beyond a colour threshold. On the 21 pages of one press batch the totals went 6.19% → 0.33%
  of ink lost and 43.0% → 8.7% of colour difference, and the table named a page nobody had
  diagnosed (doc-E 53, fixed by the empty-clip rule without ever being opened). Aggregates hide
  small defects: a missing letter or an absent QR code is 0.02% of a page, so ALWAYS localise the
  worst tile and crop source/old/new into one strip before believing a green number.
- **Five engines, not one — and Acrobat is a sixth we do not have.** PDFBox is both our parser and
  our reference rasteriser, so it agrees with us by construction; poppler was the only second
  opinion until this session added **MuPDF**, **Ghostscript 10.02** and **pdf.js**, plus qpdf's
  structural check through pikepdf. On a document Acrobat refused, all five said the file was
  cleaner than its source — which is itself the finding: the defect is in something only Adobe
  verifies. Install recipes, the exact library traps (Ghostscript 10.07 needs a GLIBC this sandbox
  does not have; MuPDF 1.7 has `mudraw`, not `mutool draw`), the font-forensics checklist and the
  Acrobat bisecting protocol are in **`references/pdf-forensics.md`**. Read it before spending a
  single one of the user's Acrobat clicks — they are the scarcest instrument in the workshop.
- **When a symptom could be ours or PDFBox's, read PDFBox.** Its source is one `curl` away (see
  the setup section for the exact base URL and the two paths that do NOT work). Comparing our
  engine against `PageDrawer` line by line is cheaper than any amount of reasoning about what a
  renderer "should" do — and it is the only way to know whether a behaviour is a bug, a deliberate
  guard with a JIRA number on it, or a semantic we simply never implemented.
- **Two rasterisers can both be blind.** On a 13-document press batch, PDFBox AND poppler rendered
  the old outputs within 0.03–0.14% of the source on 20 of 21 reported pages — while the customer
  saw missing frames, colour shifts and Acrobat errors. A green pixel gate is not a correct page:
  when a report and the rasterisers disagree, instrument the MODEL (`OcdFontProbe`, `AreaProbe`,
  `RunProbe`) and the SOURCE stream (`DrawProbe`, `ImgProbe`), not the picture.
- **Split import from writer before hunting.** `RenderOcd` rasters the model straight from
  `PdfImporter` + `OCDRenderer`. If the model render already shows the defect the writer is
  innocent — that one call cut a day of the legal-notices hunt to an afternoon.
- **The post repair must never take the glyph NAMES away.** `TrueTypeRepair.repair`
  downgraded a truncated `post` to version 3.0 so the program would parse. On a simple
  non-symbolic TrueType with **no `cmap`** — a subset a press producer emits by the dozen — the
  post names ARE the code→glyph mapping: the repaired font then loads, maps every code to
  `.notdef` and paints NOTHING, while the text layer, the widths and every gate stay perfect.
  Measured on a regional-newspaper page (2026-09-11): `PoAntennaCond-Bold` 68 codes and
  `PoAntennaCond-Light` 73 codes, **0 inked** — whole obituary frames blank in the OCD render
  while PDFBox and Direct were right, because neither repairs. It is strictly worse than doing
  nothing: unrepaired, PDFBox substitutes and at least paints. The repair now REBUILDS post 2.0
  keeping every name that survived (a subset is ordered by first use, so the names the document
  needs are the ones still there), appended at the end of the program with the directory entry
  re-pointed — no other table moves. The 3.0 downgrade stays as the last resort and only when a
  `cmap` exists. After the fix: 33/33 faces inked, page pixel diff vs the source 4.22% → 2.45%
  of pixels over 32, residual uniform (median tile 2.2%) and 0.09% of ink missing — glyph edges,
  not text. **`scripts/FontProbe.java`** is the measurement: per font, per code, how many
  outlines come back empty, with and without the repair (`java -cp "$PDFBOX_JAR:$JEXTER_CP"
  sugarcube.jexter.pdfimport.FontProbe in.pdf [repair]`). Run it on any page reported as
  "missing text" — a whole face at 0 inked is this class of defect, and no model gate sees it.
- **Type3 dispatch lives in showGlyph** — PDFBox's default `showGlyph` routes
  `PDType3Font` to `showType3Glyph`; any override MUST re-dispatch first or
  every Type3 glyph silently vanishes. Charprocs are processed by the engine
  itself (marks become vector content; not extractable text).
- **One reference-raster fallback** — paints the model cannot vectorise
  (tiling patterns, anything under an ExtGState /SMask: fill, stroke, glyph,
  image, `sh`) go through `PdfStreamEngine.beyondVectors()` →
  `emitFromReference()`: crop of the lazy `ReferenceRaster` (2 px/unit, 4 MP
  cap) — PDFBox drawing ONLY those paints, on a transparent page, without
  annotations, unrotated, the same predicate on both sides. A masked glyph keeps
  its run as invisible text (mode 3) and its ink comes from the crop at ET.
  Stencils paint via `getStencilImage(fill colour)`, never `getImage()`.
- **Paint order rules the file** — render-transparent OCDGraphic wrappers are
  spliced at write (children emit at their own z, like `inPaintOrder`); only
  groups with a paint context stay atomic. Gradient paths always carry flat
  fallback + gradient on BOTH sides of the round-trip; `spreadMethod="pad"` ↔
  Extend [true true]. Verify with stage D on welcome (A↔D ≈ A↔B).
- **Standalone targets embed everything** — the `.svg` target inlines
  `data:font/otf` @font-faces AND `data:image/*` (sentinel-driven in
  SvgWriter); external refs in a lone file are invisible-font / vanished-image
  bugs. Non-embedded fonts get PDFBox-parity per-glyph width-stretch at
  capture (`FontExtractor.widthStretch`, mirroring `PageDrawer.drawGlyph`: only
  when non-embedded AND non-vertical AND non-standard-14 AND `/Widths` declares
  the code; skip zero PDF widths — PDFBOX-5611 — and zero glyph-program widths).
  A substituted program's advance has no reason to match `/Widths`, and the
  advance we STORE is the PDF's: reconcile once at import, in the model, or every
  surface has to re-derive it. Symptom when missing: text is spaced out, glyphs
  narrower than their advances, line breaks identical.
- **Fonts are SHARED** — one `pages/fonts.svg` glyph library, pages reference it
  via external `<use href="fonts.svg#fN-gid">`; modern Chromium resolves this over
  HTTP (readers serve members that way). Test readers over HTTP, never
  file:// (opaque origins ⇒ false 0-painted). ocd.js still inlines defs for
  innerHTML consumers only. Verify the pdf RETURN with stage D on a doc whose
  paint order ≠ reading order (welcome) — byte-stable round-trips cannot see
  unserialized import state (see production-audit.md; the z-order case is
  RESOLVED via the writer-side gr splice).
- **Word spaces are derived, and the derivation has three authorities** —
  real per-face space width at capture + median-advance sanitising in
  `FontExtractor.finish()`; `SPACE_FRACTION = 0.28` (histogram-measured on a
  244k-seam press corpus); slack scale = fontSize × transform aspect
  |T·ex|/|T·ey| (PDF Tz). Judge extraction against PDFTextStripper in BOTH
  directions (glued AND lost) — see production-audit.md, press corpus.
- **Zero-width strokes** map to 0.75 pt at import (`PdfStreamEngine.pen()`, the one
  convention) — SVG cannot say "thinnest device line". Clip winding rules are
  resolved by Area at import (no clip-rule in storage — intentional).
- **The format states, it never infers.** A value that can be *reconstructed* from
  what is stored is not thereby stored: the run matrix is `fs·flip·tr`, and
  recovering `fs` as `√|det|` holds only when the run's own matrix has `|det| = 1`
  — PDF `Tz` horizontal scaling or a squeezed CTM makes size and matrix
  indistinguishable. The product still paints correctly, so the error never shows
  in a pixel gate; it surfaces as a wrong `fontSize` in the analysis signals on the
  OCD→OCD path. `data-size` costs 1% of a page and removes the guess. Reach for
  `ModelRt` whenever a field is derived rather than read.
- **Paths are DOUBLE, everywhere** — geometry, the SVG tokenizer, the formatter.
  Float is enough to paint and not enough to survive a transform round-trip: the
  page stores a clip already flipped into SVG space, reading it back flips again,
  and in float `164.007` returns `164.0069` and breaks `write(read(x)) == x`. That
  single limitation once forced a second, self-cancelling `<g>` around every
  clipped node; in double the wrapper is bare and the page lost 388 tags and 32 KB.
  Precision is the invariant, memory is not.
- **A clip has ONE id and ONE carrier, and the carrier is native.**
  `SvgWriter.clipRef` is the id authority: the `<clipPath>` id IS the model clip
  id (`c7`), with the model's geometry in page space, verbatim. A presentation id
  minted per `<defs>` entry (`"clip" + clips.size()`, deduped on geometry) is a
  SECOND naming authority — the defs then say `clip4` while the nodes say `c7`,
  every reference from the model side dangles, and the clipped content repaints
  in full. Nothing may be clipped by a `data-*` attribute either: pages carry no
  script (§B7), so `data-clip` is not a clip — it renders correctly through the
  Java reader and wrongly in every browser, which is why this survived two
  fixes. Every clipped node, whatever its kind, is wrapped:
  `<g data-ocd="clip" data-ref="c7" clip-path="url(#c7)" transform="pageFlip"><g
  transform="pageFlip">…node…</g></g>` — the def lives in page space so the
  referencing element must too, and the self-inverse flip is undone at once so
  the node inside serializes byte-identically to its unclipped form. Both `<g>`s
  are paint carriers, spliced on read (`register(…, wrapClip)` pushes the clip
  down; the inner `<g>` inherits it).
- **Never transform stored geometry to save a wrapper.** Folding the page flip
  into the `<clipPath>` lets a bare wrapper carry it — and breaks byte-stability:
  `JxPath` is float-backed, so fold→write→read→unfold returns `164.007` as
  `164.0069`. Page geometry == model geometry is what makes `write(read(x)) = x`
  hold; an extra `<g>` is cheaper than that invariant. Measured, reverted.
- **A paragraph wraps a CONTIGUOUS paint span.** `SvgOcdWriter` stores an
  `OCDParagraph` atomically (the page needs `<g data-ocd="p">`), so a wrapper
  straddling a foreign node's `z` serializes that node after the WHOLE paragraph
  and inverts the interleave — a knockout label vanishing under its own tile.
  `Paragrapher` therefore decides everything in **z**, never in flow order (the
  flow is reading order as soon as one analysis has run; `Cleaner` preserves what
  it is given), and cuts a block into maximal z-contiguous fragments. Fragments
  share an `OCDParagraph.flow()`, emitted as `data-flow` only when a split
  happened, so an unfragmented document is byte-identical; `StructureBuilder`
  rejoins one flow into one PARAGRAPH (`Segmenter.fromParagraphs`, lines rebuilt
  by `Liner` over the union) so md/doctags/HTML/reflow never see the split.
  Cutting on z is a fixed point across re-analysis; cutting on the flow index is
  not — that is what made an earlier gather-everything form look necessary.
- **Pageless documents are refused** at `Conversion.load` («Document has no
  pages», rc≠0, no output) — a cyclic/broken page tree must never yield an
  empty-spine shell. One empty PAGE is still a valid document. Verified: garbage in
  ⇒ exit 1 and no file; the CLI never reports success on a refusal.
- **The input kind is read from the CONTENT, never the name.** `PK\x03\x04` ⇒ OCD-EPUB,
  else PDF import (FORMAT.md §A6). `Conversion.load` used to sniff `.endsWith(".epub")`
  while the `byte[]` entry point sniffed magic bytes — two authorities for "what is this
  input", disagreeing in both directions: a correct container named `.ocd` got a PDFBox
  parse error for a diagnostic, and a foreign EPUB went to `OCDReader` on the strength of
  its name. Both paths now ask `isOcd(head(file))`.
- **Reproducibility is a REAL gate, verified both ways** — a REPRO check would pass just
  as green if the clock never reached the bytes, so test the other direction: pinning two
  different `SOURCE_DATE_EPOCH` values must give DIFFERENT bytes (measured on ocd/epub/pdf
  — it does), unset must give two differing runs (wall clock, the correct default), and
  `doc.id()` must stay identical across all three (content-addressed: `ba5f7869` under
  every clock). Same-epoch-twice being identical is only half the proof.
- **`parseFonts`' attr() matched attribute names as SUBSTRINGS** — `ocd.js` built
  `new RegExp(name + '="([^"]*)"')`, so asking for `d` found `id="f0-32"` first and
  returned `"f0-32"`. Every glyph got the tail of its own id as path data. Symptom in
  Prism: the whole glyph grid renders blank while the metrics line, the cmap and the
  glyph COUNT are all correct — and the inspector reports «outline 5 chars» for a space,
  which is the giveaway (5 = `len("f0-32")`, not a contour). It bit further than the
  drawer: `OcdDoc.text()` gates its `<use>` on `g.d` being truthy — always true — and
  `glyphDef` writes that `d` into the page's local defs, so text CREATED in the edit mode
  was written invisible and saved that way. Fixed by anchoring the name on a preceding
  space: `'(?:^|\\s)' + name + '="([^"]*)"'`. Any name that ends another one has the same
  trap (`d`/`id`, `x`/`data-x`, `u`/`data-text`).
- **The client had NO syntax gate** — CI checked `nimbus.cjs` and nothing else, while
  `ocd.js` is the grammar authority and `book.js` the document authority. The build step
  now runs `node --check` over every `src/**/*.js` (all 9 parse today). A parse error in
  the client ships silently: the browser just stops.
- **One predicate per partition, or a glyph falls between two.** `data-blanks` recorded the
  glyphs that are *unicode-blank* (`isBlank()`) while the `<use>` loop skipped the ones
  with *no outline* (`glyphDef == null`) — two predicates splitting the same set. A glyph
  that is inkless but NOT unicode-blank fell through both: **NBSP (U+00A0) is not
  whitespace to `Character.isWhitespace`**, by design. It was painted nowhere and recorded
  nowhere while `data-text` still carried its char, so the reader aligned N−1 glyphs against N
  chars and **TRUNCATED the run's tail**. Measured on a real VA form: `VA\u00A0FORM` came
  back `VA\u00A0FOR`, and a run holding a lone NBSP came back EMPTY. `glyphDef == null` is
  now the single predicate — it already subsumes the Spacer's sentinel (gid −1), an unknown
  gid and an inkless glyph. The same fix repaired three Arabic documents: same root cause.
  **No synthetic feature caught this**; the 178-PDF wild corpus did, on the first sweep.
- **A non-painting render mode must STATE its fill** — `SvgOcdWriter.textCss` writes
  `fill:none` whenever `rm` paints no fill (`STROKE`=1, `INVISIBLE`=3, the clip
  variants), which is correct paint and leaves the colour with no home in the page:
  it read back as 0 while byte-stability stayed green and every pixel matched. Only
  `ModelRt` saw it (`t4 fill FFB31A1A/00000000`). Fixed the way the format already
  fixes `data-size`: **the format states, it never infers** — `data-fill="#rrggbbaa"`
  is emitted *only* when the CSS drops a real colour, so ordinary runs stay
  byte-identical and the paint classes are untouched. `statesFill(t)` is the single
  predicate (`textCss` and the emitter both ask it); the reader lets `data-fill` win
  over the class. It matters when a mode is flipped: an OCR layer (`3`, every scanned
  PDF) made visible in the editor must not come back colourless.
- **Known-benign, do not chase** — `pdf→svg` vs `pdf→ocd→svg` differ by FP
  import residue in the last decimal (`206.9999` vs `207`, ±1e-4 pt): the em
  storage grid launders residue the direct writer prints. md/doctags/html ARE
  byte-idempotent. The real fix (quantize at import) rewrites every golden —
  schedule it deliberately, never as a drive-by.

## 8 · Release recipe (done for v1.0.0 on 2026-09-02 — reuse, do not reinvent)

The public history is a deliberate **tabula rasa**: one commit, tagged, force-pushed. That
was done twice (2026-08-23, then redone 2026-09-02 with the post-release delta folded in);
a THIRD rewrite of a public history is not free any more — clones and forks exist. From
now on a release is a commit ON TOP of `main` plus a new tag; the orphan recipe below is
kept for the record and for a not-yet-public repo.

**1. Start from `main`.** Since 2026-10-15 the work happens in a clone and is pushed, so the
tree IS `main` — there is no delta to rebuild. Still check line endings on files Jean-Luc edited on Windows: on
2026-09-02 `.gitattributes` came in **CRLF**, and `file` (not `grep $'\r'`, which misses it) is
the detector; renormalize to LF.

**2. Reproduce the CI gate on `main` + delta before anything touches git** (§2, with
Temurin 25+36): javac 111 files 0/0 · doclint rc=0 · `node --check` ×10 · links. Then a
throwaway `git init && git add -A` on the staged tree says what would be tracked —
**166 files** = 165 + `.env` (tracked since the `_*` rule), no `_*` path.

**3. The orphan + tag move** (PowerShell, `_release-1.0.0.ps1`, `_` = never committed):
```powershell
git bundle create ..\jexter-before-reset.bundle --all     # the ONLY copy of the old history
git checkout --orphan v1; git add --renormalize .; git add -A   # renormalize FIRST: .gitattributes changed
git commit -m $msg; git branch -D main; git branch -M v1 main
git push --force origin main
git tag -d v1.0.0; git push origin :refs/tags/v1.0.0        # move the tag, both sides
git tag -a v1.0.0 -m "Sugarcube jexter 1.0.0"; git push origin v1.0.0
```
`[new tag]` on the last push proves the remote delete worked (a stale tag would answer
«already exists»). Then park the bundle in `_private/`.

**If a release already hangs on the tag** (second reset, 2026-09-02): deleting the remote tag
turns the release into a **draft** (a release cannot stay published without its tag), and a
draft refuses `--latest` (HTTP 422 «Latest release cannot be draft or prerelease»). Right after
the retag: `gh release edit v1.0.0 --draft=false --tag v1.0.0 --notes-file $f`, THEN
`gh release edit v1.0.0 --latest` — two calls, in that order. Assets survive; `--clobber` them
anyway so they match the new commit. Verify with `--json isDraft,tagName,targetCommitish`
(`isLatest` is not a field).

**Console paste limit**: a here-string with 400-char lines gets cut mid-line by PSReadLine and
PowerShell waits forever on `>>` — Ctrl+C, then paste the notes wrapped at ~80 columns
(Markdown joins the lines of a paragraph).

**4. Commit title convention** — the project's own rule applies to a commit as to a
sentence: never open with lowercase `jexter`. The v1.0.0 title is
`Sugarcube jexter 1.0.0 — one document model to build on`; the body carries the package
map, the build, the CI gate and the licence line. The pitch is the MODEL as a foundation
(viewers, analysis tools), not the conversion.

**5. The GitHub release** — `gh` is the only terminal path with assets. Assets and where
they come from: `node nimbus-jexter.cjs --run=build <abs path>` → `_prod/sugarcube-jexter.jar`
(copy to `sugarcube-jexter-1.0.0.jar`) and `_prod/SugarcubeJexter-1.0.0-win.zip` (nimbus ≥ 1.3
zips the app-image itself; before that, `Compress-Archive` on `_prod/SugarcubeJexter/`); `typst compile doc/typst/jexter.typ jexter-overview.pdf`
(the PDF is gitignored at root — a release asset by design, `doc/typst/README.txt`).
Notes go through a FILE written UTF-8 without BOM (`[IO.File]::WriteAllText` with
`UTF8Encoding(false)`) so `—` and backticks survive; inside a PS here-string a backtick is
written ``` `` ```.
```powershell
gh release create v1.0.0 $assets --title "Sugarcube jexter 1.0.0" --notes-file $f --latest --verify-tag
# if the release already exists (created from the web UI, typically WITHOUT its assets):
gh release upload v1.0.0 $assets --clobber
gh release edit   v1.0.0 --title "Sugarcube jexter 1.0.0" --notes-file $f --latest
gh release view   v1.0.0 --json assets --jq '.assets[]|[.name,(.size/1048576|floor)]'
```
Expected: `jexter-overview.pdf 0`, `sugarcube-jexter-1.0.0.jar 13`, `SugarcubeJexter-1.0.0-win.zip 58`.

**PowerShell 5.1 traps met on the way** (all cosmetic, none stopped the run): git's
stderr is shown as a red `NativeCommandError` even on success (`$ErrorActionPreference =
'Stop'` does not fire on it — read git's own output); the console renders a UTF-8 `—` as
`ΓÇö` (CP437 display, the commit is fine — check on GitHub); an argument holding embedded
`"` is split on spaces before reaching `gh` («accepts at most 1 arg(s), received 3») —
keep `--jq` filters quote-free; `1MB` is a PowerShell literal, not jq.

## 9 · Roadmap (engineering half)

The market audit (2026-09-02: who pays for PDF/UA retro-tagging, comparison and redaction, the
legal deadlines, the prices) is PRIVATE: account skill `repo-skills`, `references/jexter-market.md`.
Read it before proposing or prioritising a product feature. What binds the code is here:

- **Chosen sequence**: redaction checker (demo) → PDF/UA writer + veraPDF CI gate → a
  tables/figures pass in Prism. Done: the `redact` package (audit target + `redact` option,
  `tool/Redact` CLI) — the audit-derived zone is CEILED at its covering box's z (only what the box
  hid goes; without it, repairing a TikZ figure erased the labels sitting under their own white
  backgrounds) and the mark goes to the bottom of the paint order (or the geometric audit still
  sees IMAGE_UNDER_BOX on a scan whose pixels were painted). Full notes:
  **`references/redaction.md`**.
- **PDF/UA output** is the inverse of `TaggedStructureBuilder`, inside `PdfWriter`; it needs the
  real-text mode plus a complete ToUnicode — the `outline` mode is inaccessible by construction.
  Tables, figures and alt-text stay a human/LLM step.
- **Page-scoped edits**: `OCDReader.readPages` (placeholders keep indices) + `--to=patch` return
  only the changed members; the client puts them and `book.refreshPage`s — the pattern for any
  future per-page tool.
- **The boundary (binding)**: projections of the model (PDF/UA writer, redaction checker and
  removal, comparison) belong in this AGPL repo; product workflows built on the engine live
  outside it — never in the repo, never in `doc/`, never in this skill.

## Conventions

DRY, KISS, no overengineering; English in code; minimal comments; OO where it
earns its place. Confirm a convention before generating a lot of code. Code, docs and this
skill go to `main` by commit (see *Git*); a one-off product (a scored batch, a PDF for a customer)
is delivered as a file, never committed.

```
references/
  jdk-sandbox.md      — rootless JDK extraction (why + how), dead ends
  features.md         — the fixture corpus catalog (test/Fixtures.java)
  corpora.md          — real-PDF corpus sources (GitHub + manual) + the Fedlex outline-GT corpus
  ocd-facts.md        — facts JSON contract + OcdFacts accessor mapping (both stages)
  diagnosis.md        — symptom → cause map for the visual axis (WebKit + Java2D)
  ui-seam.md          — the Prism tool architecture + the three UI gates (READ FIRST for frontend work)
  production-audit.md — the v1.0.0 release bench: corpus, 7 passes, client bench, seeded /cdn cache
  redaction.md        — the redaction checker + redactor as built, the PDF/UA writer and comparison
                        notes: the engineering half of the market audit
  (private, in the account skill sugarcube-jexter: market.md — the market audit; consumers.md —
   who links or launches the library, and their contacts)

scripts/  — toolchain
  setup.sh                — JDK 21 + python/qpdf/poppler/fonts, one command
  build.sh                — bootstraps the JDK if needed, compiles the tree
scripts/  — rasterizers (the two reference surfaces)
  RenderPdf.java          — PDFBox reference raster (1-based page)
  RenderOcd.java          — OCD-EPUB candidate raster via OCDReader + OCDRenderer (1-based)
scripts/  — ground truth
  make_feature_matrix.py  — 11 hand-crafted single-feature PDFs (systematic rendering gate)
  make_corpus.py          — the release-bench corpus
  fetch_corpus.py         — curated diagnostic PDFs from public corpora
  fetch_wild_corpus.sh    — real-world PDFs from 9 reader test suites (the diversity gate).
                            All sources are codeload/raw.githubusercontent — ALLOWED domains, so this
                            runs in the sandbox. Measured: 178 fetched, 165 convert, 9 encrypted
                            (BouncyCastle is runtime-optional), 4 that PDFBox itself cannot open or
                            reports as pageless. RUN THIS: it found a text-truncation defect that all
                            27 synthetic features missed
test/ (in the REPO, not this skill) — the functional gate CI runs
  Gates.java              — builds the corpus into target/corpus (no PDF named), then REPRO · DOCTAGS · STABLE · MODEL · TIERS · STRADDLE · CLIPS · COPY · EDITED · HOSTILE
  ContainerDiff.java      — WHICH member diverged, shown in context (run after a red STABLE)
  Fixtures.java           — the corpus, PDFBox only: 28 single-trait features, `rich` (links, fields
                            with values, annotations, two OCG layers, a two-level outline) and `tagged`
                            (StructTreeRoot + MCIDs; one P holding TWO MCIDs)
scripts/  — the surfaces no gate covers
  TextFid.java            — read-out vs PDFTextStripper: characters lost AND invented
  Rich.java               — links / fields / annotations / layers / outline across a round-trip
  SigDump.java            — the LLM signal harvest, one line per block (a refactor must diff to zero)
  ocd_page_bench.mjs      — the only surface that speaks for a BROWSER (Chromium over HTTP)
  prism_bench.mjs         — the real CHASSIS in Chromium, engine behind /api/convert: exports serve()
                            + openPrism/ribbon/drag. The only place a tool's gestures can be measured
  Bench.java              — where the time goes: import + analysis, then each target.
                            Warms up twice first — a cold page render costs 440 ms, warm 113
scripts/  — structural + visual axes
  verify_pipeline.py      — unified GT ↔ facts + round-trip (both OCD stages)
  OcdFacts.java           — the OCD facts backend (--pdf virtual | --ocd reread)
  StructDump.java         — one doc's heuristic headings + a SUMMARY line
  score_outline.py        — corpus-wide enumerator recall vs the outline GT
  compare.py              — render + pixel-compare a candidate against the PDFBox reference
  batch_score.py          — a customer batch: source vs delivered vs new, poppler + MuPDF, per reported
                            page, and --all for the pages a new build made worse
  render_html.py          — WebKit render of .epub/.xhtml/.html
  glyph_probe.py          — width-ratio probe (~2 ⇒ units-per-em bug)
  MapProbe.java           — which substitute PDFBox mapped for each absent font
  OcdFontProbe.java       — the same question asked of the MODEL: which glyphs landed with no outline
  PlaceProbe.java         — page CTM vs model transform per image, plus the live clip on each side.
                            The one tool that shows an image placed right and clipped to nothing
  ColorProbe.java         — every colour space of a page and whether a tint of it converts to RGB
  AreaProbe.java          — every model text run inside a page-space rectangle, glyph by glyph
  GlyphDump.java          — one font as the model holds it: gid, unicode, advance, outline bounds
  RenderOcd.java          — raster the MODEL straight from PdfImporter + OCDRenderer: splits
                            import from writer in one call
  DrawProbe.java          — every glyph the page really draws in a band: font, code, and whether PDFBox
                            hands back an outline. The ground truth the importer sees
  ImgProbe.java           — every image XObject: filter, colour space, and why PDFBox cannot decode it
  predictor_rows.py       — the PNG-predictor row filters of every image in a PDF; exit 1 on Up/Average/
                            Paeth (a downstream converter decodes Paeth wrong — see §5c, "downstream reader")
  FontProbe.java          — per font, how many glyph outlines come back EMPTY, with and without
                            the post repair — the only surface that sees a face that paints nothing
scripts/bench/  — THE CHROMIUM SCENARIOS (22), the other half of the gates
  README.md               — what each scenario holds, how to run them, and the three ways this harness
                            has lied (a dismissed toast, sl-button vs <button>, text through argv)
  prism_bench.mjs         — the harness: serves the site, answers /api/convert and /api/font
  make_bench_fixtures.py  — the six documents they are measured against (in ../)
  Nothing in a Java gate can see a dark tool, a run written at size zero, or chrome leaking into a
  container. These can. Run them after ANY client change; `sweep` and `leak` alone catch most of it.

scripts/  — the two capabilities the tree itself never calls (KEPT on purpose, 2026-09-07)
  ProbeMedia.java         — JxMedia on a real mp4/mp3. Measured against ffprobe: 640x360 / 2.520 s and
                            3.239 s, to the millisecond. `ffmpeg -f lavfi -i testsrc` / `-i sine` makes
                            the fixtures
  FontLoop.java           — the loop the two font halves describe and nothing closes: a real TTF →
                            TtfImporter.extract → Foundry's own request shape → a rebuilt TTF. Measured on
                            DejaVu Sans: 9 of 10 codepoints outlined (space has no contour), rebuilt at
                            DejaVu's proportions (o 612 · l 278 · H 752 per 1000 em) and embeddable
  Forge.java              — Foundry: three drawn bitmaps on shared guides → an embeddable TTF. Verified
                            end to end by EMBEDDING it with PDFBox and setting text: widths 592/243/592
                            per 1000 em, and "Hollo" renders with the l taller than the o. FontBox warns
                            "No PostScript name data" — that is `post` version 3.0, which carries no glyph
                            names by design; legal, common, and not a defect to chase

scripts/  — release
  make_redaction_fixtures.py — the 5 RedactionAudit cases (fake / legit / real / scan / pending)
  production_audit.sh     — the v1.0.0 release bench
  seed_cdn_cache.py       — seed the offline /cdn cache for the client bench
```

## Git

The clone is the workplace (2026-10-15, same regime as IIP and WebForge): work in it and push
straight to `main`; Jean-Luc runs `git pull` before touching his copy (`C:\Projects\SugarcubeJexter`).

- **Every push runs the CI gate** (`.github/workflows/build.yml`: javac 21 `-Xlint:all -Werror`,
  doclint over our files, `node --check`, link check). Reproduce it locally before pushing (§2) —
  a red `main` is a red badge on a public repo.
- **The repo is PUBLIC.** No customer document, name, contact or price ever enters it — not in
  code, docs, commit messages or this skill. Customer cases are named by what they show ("a press
  cover", doc-A…); the private notes live in the account skill `repo-skills`.
- A shallow clone pushes a thin pack only after `git fetch origin main` (else HTTP 413).
- Write LF (his Windows checks out CRLF; `.gitattributes` has `* text=auto`).
- Commit titles follow §8: never open with lowercase `jexter`. A skill change is committed with
  the change it records.
- Produced files never enter git (`_*`, `target/`); `.gitignore` already says so.

## Maintaining this skill

It lives in the repo since 2026-10-15 (the account index `repo-skills` keeps the pointer and the private notes). Update
it by commit, once per session when a session taught something durable (a trap, a gate, a
convention, a measured number) — diff each claim against the code first, and put a correction
where the wrong claim was. The frontmatter `description` stays under 1024 characters. Dated
narration ("X became Y on …") stays as written; a current-tense instruction that names something
gone is a defect (see *The SKILL rots like the code*).
