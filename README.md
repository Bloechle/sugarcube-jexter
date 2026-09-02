# Sugarcube jexter

**A faithful, *structured* document engine built on one normalized model — OCD.**

Sugarcube jexter (artifactId `sugarcube-jexter`) parses a PDF with
Apache PDFBox, normalizes it into a single in-memory model — the **Open Canonical
Document** (**OCD**) — and writes it back out to PDF, EPUB3 fixed-layout
(**epubcheck-validated**), **reflowable HTML**, SVG, or the self-contained
**OCD-EPUB** (`.ocd.epub`) working format. Beyond faithful presentation it
recovers a **logical structure layer** — reading order, headings and paragraphs by
geometry (richer roles — lists, figures, tables, captions — from tagged-PDF
ingestion or the optional LLM refinement), accessibility text — and embeds
**audio/video** as first-class resources. PDFBox is both the parser
**and** the reference rasterizer, so the raster fallback and the fidelity check
agree by construction. The on-disk format — a valid fixed-layout EPUB that is
also the lossless model container — is specified in
[doc/FORMAT.md](doc/FORMAT.md): Part A the container, Part B the page & font
grammar.

```
PDF ─[convert.PdfImporter]→ OCDDocument ─[write.*]→ PDF · EPUB · HTML · SVG · OCD-EPUB
                            (the model)        │
                                               └─[ocd.render.OCDRenderer]→ raster
```

## Features

- **Faithful conversion** — text (faces, sizes, colours, TJ kerning, rotation,
  z-order; word spaces are re-derived from glyph geometry, not trusted from the
  source), vectors (fills, strokes, dashes,
  caps/joins/miter, bézier, opacity, blend modes, even-odd winding), images
  (RGB/gray, scaling, **rotation**, inline & XObject, JBIG2), and **clipping**
  (deferred clip regions, per-glyph self-clips dropped).
- **Real, selectable text** — glyph outlines are re-emitted as embeddable fonts
  with a reverse cmap, so output PDFs/EPUBs carry searchable text, not just shapes.
  (The OCD-EPUB container stores every font once in `pages/f.svg` — outlines,
  metrics, cmap; an embeddable `.ttf`/`.otf` is recompiled on demand when a
  PDF/EPUB is written.)
- **Structured & accessible** — a logical structure tree (reading order via
  recursive XY-cut, **headings + paragraphs** by geometry; lists, figures, tables
  and captions from **tagged PDF (PDF/UA)** ground truth or the optional LLM
  refinement), carrying alt text and language; projected to EPUB navigation +
  EPUB Accessibility 1.1 metadata.
- **Reflowable HTML** — semantic HTML5 (headings, paragraphs, lists, tables,
  figures, `<video>`/`<audio>`) generated from the structure tree, beyond the
  fixed-layout SVG/EPUB facsimile.
- **Embedded media** — audio (mp3) and video (mp4) as first-class resources and
  content nodes, with manifest dimensions/duration/size/hash.
- **Font compilers** — `GlyfOtf` (TrueType/glyf, embeddable by PDFBox and
  browsers) and `CffOtf` (native cubic CFF, for web/EPUB). Fonts get explicit slug
  ids (`CambriaMath`, `ArialMT-Bold`).
- **Font repair** — corrupt embedded TrueType `post` tables are patched so the
  real glyphs render instead of falling back to Arial.
- **Three web apps** (zero-dependency, JDK HTTP server, no JavaFX) sharing one
  front-end look — glass chrome, gradient wordmark, shared `/shared/` logo + icon,
  on the qry stack + Shoelace + Lucide (all from CDN):
  - `Prism` — the jexter document workbench: open a PDF (the engine lifts it) or an
    OCD-EPUB (served as-is), pages render **natively** — the book is unzipped in the
    browser and served to iframes by a Service Worker; nothing is uploaded. Reader
    (scroll / page / spread, anchored zoom, editable pager), page-scoped **search**
    (case- and accent-folded, click-to-locate), **read-aloud** (per-page language,
    furniture skipped, paragraph prosody), an **edit mode** (media augmentations),
    and an **Analysis** mode — **Display bounds** / **Display flow** overlays (roles
    in the gutter, reading-order arrows) with a bidirectional page DOM tree. A
    **Settings** drawer carries the conversion toggles (applied at import and at
    export) and the key-gated **AI** panel (LLM structure refinement). **Export**
    to PDF · EPUB PRISM (the OCD model) · EPUB FL · HTML · Markdown · DocTags
    (LLM/RAG), each through the browser save dialog under the document's name.
    **F2** opens the log console (client + server `JxLog` over SSE, level filter).
  - `PDFInspector` — see exactly what PDFBox produces: the **COS** object tree,
    the reference raster (with **PDFBox · Direct · OCD · OCD-reread** rasterizers
    side by side), and text extraction — with no Jexter conversion in between.
- **`PdfNormalizer`** — regenerate a clean, normalized PDF (repaired fonts,
  regular structure) with a selectable text layer or outlined glyphs.

## Requirements

- **JDK 21 or newer** (the code targets 21; a newer JDK builds and runs it unchanged)
- Two jars in `lib/` (already vendored): `pdfbox-app-3.0.7.jar` (PDFBox + FontBox
  + commons-logging, shaded) and `jbig2-imageio-3.0.5.jar` (JBIG2 image decoding).
- BouncyCastle is only needed at runtime to open encrypted PDFs (optional).

## Build

`nimbus.cjs` is the build — zero npm dependencies, straight `javac`/`jar`/`jpackage`,
the same `-Xlint:all -Werror` gate as CI. It opens a local deck, or runs headless:

```bash
node nimbus.cjs                      # deck: jar · build (jar + app-image) · ai bundle
node nimbus.cjs --run=jar  $(pwd)    # _prod/sugarcube-jexter.jar
node nimbus.cjs --run=build $(pwd)   # + _prod/SugarcubeJexter/ (bundled runtime, one .exe per tool)
```

Or compile directly with the bundled jars (`lib/`):

```bash
javac --release 21 -Xlint:all -Werror -cp "lib/*" -d target/classes $(find src -name "*.java")
```

`mvn -q package` works too (IDE convenience; the `pom.xml` points at the same jars).

> On Windows / PowerShell, use `;` as the classpath separator instead of `:`
> (e.g. `-cp "lib/*;target/classes"`).

## Run

```bash
CP="lib/*:target/classes"

# Convert + rasterize a page (0-based page index, dpi) → PNG
java -cp "$CP" sugarcube.jexter.convert.PdfImporter in.pdf 0 out.png 144

# Convert to any projection — the one CLI for every target. The target is inferred
# from the output extension (or --to=pdf|epub|epub-reflow|html|md|doctags|ocd|svg), and
# any ConvertOptions / export key passes through as --key=value (e.g. --page=2,
# --selectable, --grid=1000, --renderAnnotations=false). No args → a drop-a-file window.
java -cp "$CP" sugarcube.jexter.write.Conversion in.pdf out.epub
java -cp "$CP" sugarcube.jexter.write.Conversion in.pdf page.svg --page=0

# Normalize a PDF through OCD (PDF → OCD → PDF); --outline = vectorized glyphs,
# --<key>=<value> for any ConvertOptions setting (e.g. --mergeGlyphClips=false)
java -cp "$CP" sugarcube.jexter.tool.PdfNormalizer in.pdf out.pdf

# Prism — the document workbench (PRISM reader chassis + engine API); --serve = dev URL only
java -cp "$CP" sugarcube.jexter.ui.prism.Prism [--port N] [--web DIR] [--serve]

# Raw PDFBox inspector (COS tree / reference raster / text)
java -cp "$CP" sugarcube.jexter.ui.pdf.PDFInspector
```

## Project layout

`nimbus.cjs` at the root is the build orchestrator — zero npm dependencies, run
it with `node nimbus.cjs`. Everything else is the engine, all under
`sugarcube.jexter`:

| Package | Responsibility |
|---|---|
| `core` | The `Jx*` toolkit: `JxPath` (single path type), `JxTransform`/`JxRect`/`JxPoint`, `JxColor` (sRGB int argb), `JxStringer` (streaming JSON), `JxJson` (JSON parser), `JxMedia` (mp4/mp3 probe), `JxName`/`JxNum`/`JxText`, `JxZip`, `JxLog` (thin `System.Logger` facade). |
| `ocd.model` | The model (27 classes). *Presentation:* `OCDNode` (sealed → `OCDText`/`OCDPath`/`OCDImage`/`OCDGroup`/`OCDMedia`/`OCDBreak`), container subtypes of `OCDGroup` (`OCDParagraph` — text block with `OCDBreak` line breaks, `OCDLayerContent` — bound to an `OCDLayer`, `OCDGraphic` — clustered vector drawing), `OCDMedia` (sealed → `OCDVideo`/`OCDAudio`), `OCDGradient` (linear/radial fill on `OCDPath`), `OCDFont`/`OCDGlyph`, `OCDClip`, `OCDLayer`, `OCDPage`, `OCDDocument`. *Annotations:* `OCDLink`, `OCDAnnotation` (review markup), `OCDFormField` (AcroForm widgets). *Logical:* `OCDMeta`, `OCDOutline`, `OCDStruct` (a structure-tree node) + `OCDStructure` (a named, provenance-carrying tree; a document holds several). |
| `ocd.io` | `OCDReader` — the OCD-EPUB reader (three tiers: skeleton, one page, full model; SVG-OCD v2 pages are self-contained, fonts parse from `pages/f.svg`), `OCDVocab` (shared serialization vocabulary). |
| `ocd.render` | `OCDRenderer` (model → Java2D raster) + `BlendComposite`. |
| `ocd.analysis` | `Cleaner` (flatten to the ink base — drop blank glyphs/runs and `OCDBreak`s, splice render-neutral wrappers, mint leaf ids) → `Liner` / `Segmenter` (line clustering + recursive XY-cut block segmentation on the shared `XYCut` engine, which also owns reading order via `XYCut.order`) + `Spacer` (the single word-space authority) + `Paragrapher` (one `OCDParagraph` per block, `OCDBreak` line breaks); `StructureBuilder` / `BookmarkStructureBuilder` (heuristic & PDF-bookmark structure trees), `Furniture` (running header/footer — single authority), `GraphicClusterer` (vector clustering → `OCDGraphic`), `FontProfile` (heading typography), `LanguageDetector`, `Refiner` + `StructureReconciler` (optional LLM structure), `AnalysisStages` (pipeline-stage projection for Prism). |
| `convert` | `PdfImporter` + `PdfStreamEngine` (paths, glyph runs, MCID tracking), `FontExtractor`, `TaggedStructureBuilder` (PDF/UA), `PdfRenderer`, `ConvertOptions`. The only PDFBox-coupled **import** layer (`write.PdfWriter` uses PDFBox for PDF export). |
| `font` | `GlyfOtf`, `CffOtf`, `JxFont` (facade + reverse cmap), `Foundry` (build an embeddable font from traced glyph outlines). |
| `trace` | From-scratch **Potrace** raster→vector tracer (`Potrace`, `MedianCut`, `Bitmap`, `TracedShape`, `TraceOptions`, `Tracer`): a raster image → `OCDGraphic`, no PDFBox. See [doc/TRACER.md](doc/TRACER.md). |
| `write` | `Conversion` — the one entry point + target registry (PDF · EPUB · EPUB-reflow · HTML · Markdown · SVG · DocTags) and CLI; every writer shares one contract (`write(doc, OutputStream, ConvertOptions)`). `PdfWriter`, `EpubWriter` (fixed-layout) + `ReflowEpubWriter` (reflowable) over shared `EpubPackage`, `HtmlWriter` (reflowable), `SvgWriter`, `MarkdownWriter` + `DocTagsWriter` (LLM/RAG), `OCDIndex`, `WriterCli` (the drop-a-file launcher). |
| `tool` | `PdfNormalizer` — runnable PDF → OCD → PDF normalizer; `HttpLlmClient` — the LLM client for AI structure refinement (OpenAI / Anthropic wires, thinking minimized so the structure JSON isn't truncated). |
| `ui` | `WebApp` — shared zero-dep HTTP-server base (single-instance, sidecar MIME, CDN proxy); `ui.shared/web` — shared assets served at `/shared/`: the jexter logo + icon SVGs and the client OCD accessor (`js/ocd.js`, used by Prism and PDFInspector). Pages render natively — there is no client renderer. The qry stack, Shoelace and Lucide load from CDN. |
| `ui.prism` | `Prism` — the document workbench: fixed-layout reader (Service-Worker-served pages), editor (media augmentations), Analysis mode (bounds/flow overlays, page DOM tree, Inspect), search, TTS, exports. |
| `ui.pdf` | `PDFInspector` — the raw PDFBox web inspector (standalone, same look as Prism). |
| `ui.jexter` | `Jexter` — a standalone **PDF normalizer** (crude PDF in, clean selectable PDF out): drag-and-drop `WebApp` **and** headless — single file, folder batch, and a `--hotfolder` daemon (`--recursive` mirrors the sub-tree, `--threads` runs in parallel, outputs written atomically). `Jexter` is the engine + server; `JexterCli` owns the headless orchestration. |

**Model invariants:** page user space is Y-up; geometry is local + per-node
transform (glyphs in em, images/media in the unit square, paths in page space);
colours are sRGB `int` argb with alpha folded; bounds are derived, never stored;
identifiers are 1-based with `0` reserved as a sentinel; presentation and the
logical `OCDStruct` tree are separate (the latter references content and never
alters pixels).

## Documentation

Four documents under [`doc/`](doc/), one per subsystem:

- **[doc/FORMAT.md](doc/FORMAT.md)** — the **OCD-EPUB** working format, normative.
  *Part A* is the container (OCF layout, manifest, the `jexter/` JSON members field
  by field); *Part B* is the page & font grammar, the strongly-typed SVG dialect
  the pages are written in (`data-v="2"`).
- **[doc/ANALYSIS.md](doc/ANALYSIS.md)** — the geometry-first structure recovery
  pass: single-authority stages, XY-Cut++ reading order, nav-guided heading
  recovery, and what is deliberately deferred. `convert/Analysis.java` remains the
  authority on the stage order; this document carries the reasoning and the
  measurements behind it.
- **[doc/FRONTEND.md](doc/FRONTEND.md)** — the HTTP **convert contract** shared by
  every tier, the per-environment seam (`backend.js`), capability gating, the
  deploy layout, and the three-layer tool architecture of the Prism workbench.
- **[doc/TRACER.md](doc/TRACER.md)** — the from-scratch Potrace raster→vector
  tracer (`sugarcube.jexter.trace`), with measured rasterize-back fidelity.

The Typst source of the technical overview lives in
[`doc/typst/`](doc/typst/) — see its `README.txt` to build the PDF.

## Third-party

- Apache **PDFBox** & **FontBox** — Apache License 2.0.
- **jbig2-imageio** — Apache License 2.0.
- **BouncyCastle** (optional, runtime) — MIT-style Bouncy Castle license.
- Web front-end uses the qry stack, Shoelace, and Lucide via CDN.

## License

Sugarcube jexter is **dual-licensed**.

- **Open source — AGPL-3.0.** It is free software under the GNU Affero General
  Public License v3.0 — see [LICENSE](LICENSE). You may use, modify, and
  redistribute it freely, **including over a network**, provided your derivative
  works (and any service built on it) are also released under the AGPL-3.0 and
  their source is made available to users.
- **Commercial.** If the AGPL's copyleft does not fit your use case — e.g. a
  closed-source product or a proprietary SaaS — a commercial license is available.
  See [Commercial licensing](#commercial-licensing).

Copyright © 2026 Sugarcube Information Technology Sàrl.

This project bundles Apache PDFBox, FontBox, and jbig2-imageio (Apache License
2.0); their notices must be preserved on redistribution — see [NOTICE](NOTICE).

## Commercial licensing

The AGPL-3.0 requires any software that incorporates jexter — including
software offered as a network/hosted service — to be released under the AGPL-3.0
with its source available. If that does not work for you, **Sugarcube Information
Technology Sàrl** grants commercial licenses that waive the copyleft obligations,
so you can integrate jexter into proprietary or closed-source products.

Contact: **contact [at] sugarcube.ch** — subject `jexter commercial licence`.
Please state your intended use (product, hosted service, internal tool), the
expected scale, and whether you need support or only the licence grant.
