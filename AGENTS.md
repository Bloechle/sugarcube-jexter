---
name: jexter
description: >
  Sugarcube jexter — a Java engine (Apache PDFBox 3.x, JDK 21+) that lifts a PDF
  into one normalized in-memory model, the Open Canonical Document (OCD), and
  projects it to PDF, fixed-layout EPUB3, reflowable HTML, SVG, Markdown, DocTags,
  or the self-contained OCD-EPUB (.ocd.epub) working format. Use this bundle when
  working on the OCD model, the PDF→OCD import (convert), the analysis pass
  (geometry-first structure recovery, nav-guided alignment), the projections
  (write.*), font compilation (GlyfOtf/CffOtf), the Prism workbench, or the
  sugarcloud.ch HTTP API. Read this card first, then the four documents under
  doc/ (FORMAT, ANALYSIS, FRONTEND, TRACER), then open source files on demand
  via the Files table in the overview.
---

# jexter — what it is, and how to read this bundle

`jexter` opens a PDF and lifts it into **one** normalized model — the **OCD**
(Open Canonical Document) — then projects that single model to every output.
Normalize once, export many; a round-trip stays faithful by construction.

```
PDF ─[convert.PdfImporter]→ OCDDocument ─[analysis]→ structured OCDDocument
                            (the model)        │
                                               └─[write.*]→ PDF · EPUB · HTML · SVG · MD · DocTags · OCD-EPUB
                                                  ocd.render.OCDRenderer → raster
```

PDFBox is both the **parser** and the **single reference rasterizer**, so the raster
fallback and the fidelity check agree by construction, not by luck. The model carries
two layers kept strictly apart: a **presentation** layer (what paints) and a **logical**
`OCDStruct` layer (what it means) that only ever *references* content by id — it never
moves a pixel.

## Package map (the contract of each area)

- `ocd.model` — the sealed node types (`OCDText/Path/Image/Group/Media/Break`), fonts/glyphs, page, document. Y-up, em/unit-square geometry, derived bounds, 1-based ids.
- `convert` — the **only PDFBox-coupled import layer**: `PdfStreamEngine` walks the content stream into primitive OCD nodes; `FontExtractor` rebuilds fonts; `TaggedStructureBuilder` lifts PDF/UA tags (skippable via `ignoreTags`).
- `ocd.analysis` — the **geometry-first** pass: `Cleaner → Paragrapher (Segmenter ─ Liner ─ Spacer.freeze) → Furniture → GraphicClusterer → OutlineAligner → IdStamper → buildOutline (BookmarkStructureBuilder | StructureBuilder) → HeadingRoles → Refiner` — **`convert/Analysis.java` is the authority on this order**; `Liner`/`XYCut`/`Spacer` are inside `Paragrapher`, not stages. One authority per concern. The nav (bookmarks/outline) is ground truth: `OutlineAligner` re-cuts/merges title blocks on it and tags `heading-N`; `HeadingRoles` projects the best structure to page-level roles.
- `ocd.io` — `OCDReader`, the OCD-EPUB reader (three tiers: skeleton / one page / full model; fonts parse from `pages/f.svg`); `OCDVocab`.
- `write` — the projections (`SvgWriter`, `PdfWriter`, `EpubWriter`, `ReflowEpubWriter`, `HtmlWriter`, `MarkdownWriter`, `DocTagsWriter`); `OcdEpubWriter` + `SvgOcdWriter` + `OcdMembers` emit the OCD-EPUB; `OCDIndex` is the read-out; `Conversion` is the one entry point + target registry.
- `font` — `GlyfOtf` (glyf, PDFBox-embeddable) / `CffOtf` (CFF) recompile an embeddable font on demand from the model.
- `ui` — local zero-dependency `WebApp`s sharing `/shared/` logo+icon: `Prism` (the document workbench: reader, editor, Analysis inspector, fonts inspector, F2 dual console), `PDFInspector` (raw PDFBox), `Jexter` (PDF normalizer — also headless CLI + `--hotfolder` daemon via `JexterCli`).

## Conventions (load-bearing)

- **DRY / KISS / SOTA** — "simple mais béton". No overengineering, no verbosity.
- **Fid2 = 0 is sacred** — the analysis layer is *additive*: it sets roles, reorders the content array into reading order, and builds an `OCDStruct` tree by reference. It **never repaints**. Reading order ≠ paint order (`z`); the round-trip image can never regress.
- **Geometry-first** — the visual line is reconstructed and frozen before any text/font signal is read. No lexicon, no regex in the heuristic pass (OCR-robust). Nav-guided alignment is self-reference, not a lexicon: the matched strings come from the document itself.
- **One authority per concern** — one pass owns lines (`Liner`), one owns spaces (`Spacer`), one owns reading order (`XYCut`), one owns furniture (`Furniture`), one owns nav alignment (`OutlineAligner`), one owns heading roles (`HeadingRoles`).
- **One representation per data** — fonts live once in `pages/f.svg` (paint + model); pages are self-contained data (`data-u`, `data-o`, lines, links, roles); capabilities travel as data, behaviors live in viewers — **no JS is ever embedded in an EPUB**.
- **Name by contract, not mechanism.** English code/comments/docs; French conversation.
- **Detect the obvious, defer the ambiguous** — headings + reading order are heuristic/nav; figures/tables/captions/lists/code are deferred to PDF/UA or the optional LLM `Refiner`.
- **`_` prefix = never committed.** `_prod/` holds the deliverables (`target/` is the intermediate scratch — the two are kept apart on purpose), `_private/` and `_ai-*` never leave the machine; every one of them is ignored, without exception. `nimbus.cjs` at the root is the build orchestrator — tracked, because it is the documented way to build the project. Everything else under `src/` is the engine.

## Session setup (what the generic project instructions defer to this card)

- **Skills** — read `jexter` at `start` (dev, fidelity, release gates); `nimbus` for the
  build deck, the AI bundle or these instructions; `docling` when the task touches DocTags
  / OCD-AI; `qry-js` when it touches Prism, JexLab or any web UI.
- **Repo** — `github.com/Bloechle/sugarcube-jexter` (public). When this bundle predates the
  question, fetch the file from `main` rather than answering from the bundle.
- **Build** — `node nimbus.cjs` (deck) or `node nimbus.cjs --run=jar|build|context <abs path>`;
  CI is `.github/workflows/build.yml`: `javac --release 21 -Xlint:all -Werror` + doclint +
  `node --check` + link check. `mvn -q package` is an IDE convenience, not the build.

## Gotchas

- Word spaces are **not** trusted from the source — they are re-derived by `Spacer` from glyph geometry; `Cleaner` strips every blank glyph first.
- Ligatures fold to letters only at **read-out** (`OCDIndex` NFKC); the model and the OCD-EPUB keep the source codepoint; SVG/PDF paint by glyph id, unaffected.
- The OCD-EPUB is **TrueType-free** — every font lives once in `pages/f.svg` (outlines + metrics + cmap, inkless glyphs keep their advance); an embeddable OTF is recompiled on demand.
- Model descents may be **negative** (PDF convention) — take the magnitude when sizing.
- A heading is *elevated AND recurs* (≥ 2 pages or ≥ 3 times); only the single largest elevated style (the title) is exempt from recurrence. Nav-tagged headings (`OutlineAligner`) win over projections.
- The `convert` import path is PDFBox-coupled; `write.PdfWriter` also uses PDFBox for PDF **export**.
- `.ocd.epub` naming: the last suffix says the TYPE (a valid EPUB), the `.ocd.` prefix says the FLAVOR (the model container) — the `.kepub.epub` pattern.

