---
name: jexter
description: >
  Sugarcube jexter — a Java engine (Apache PDFBox 3.x, JDK 21+) that lifts a PDF
  into one normalized in-memory model, the Open Canonical Document (OCD), and
  projects it to PDF, fixed-layout EPUB3, reflowable HTML, SVG, Markdown, DocTags,
  or the self-contained OCD-EPUB (.ocd.epub) working format. Use this card when
  working on the OCD model, the PDF→OCD import (pdfimport), the analysis pass
  (geometry-first structure recovery, nav-guided alignment), the projections
  (write.*), font compilation (GlyfOtf/CffOtf), the Prism workbench, or the
  convert contract it shares with the conversion service (doc/FRONTEND.md).
  Read this card first, then the skill .claude/skills/sugarcube-jexter/SKILL.md,
  then the four documents under doc/ (FORMAT, ANALYSIS, FRONTEND, TRACER), then
  open source files on demand.
---

# jexter — what it is, and how to work on it

`jexter` opens a PDF and lifts it into **one** normalized model — the **OCD**
(Open Canonical Document) — then projects that single model to every output.
Normalize once, export many; a round-trip stays faithful by construction.

```
PDF ─[pdfimport.PdfImporter]→ OCDDocument ─[analysis]→ structured OCDDocument
                            (the model)        │
                                               └─[write.*]→ PDF · EPUB · HTML · SVG · MD · DocTags · OCD-EPUB
                                                  ocd.render.OCDRenderer → raster
```

PDFBox is both the **parser** and the **single reference rasterizer**, so the raster
fallback and the fidelity check agree by construction, not by luck — and it is blind where
we are blind (see Gotchas: a pixel gate against it cannot see those defects). The model carries
two layers kept strictly apart: a **presentation** layer (what paints) and a **logical**
`OCDStructNode` layer (what it means) that only ever *references* content by id — it never
moves a pixel.

## Package map (the contract of each area)

- `ocd.model` — the sealed node types (`OCDText/Path/Image/Group/Media/Break`), fonts/glyphs, page, document. Y-up, em/unit-square geometry, derived bounds, 1-based ids.
- `pdfimport` — the **only PDFBox-coupled import layer**: `PdfStreamEngine` walks the content stream into primitive OCD nodes; `FontExtractor` rebuilds fonts; `TaggedStructureBuilder` lifts PDF/UA tags (skippable via `ignoreTags`). Colour: the model paints in sRGB; a CMYK source colour rides beside it (`JxCmyk`, `data-cmyk`), `DeviceCMYK` converts through the document's own output intent when it has one (`OutputIntentCmyk`), and the page's CMYK blending space and the intent itself are carried and written back (`OCDPage.cmykBlending`, `OCDOutputIntent`).
- `imageimport` — `ImageImporter`: a scan or a photo (PNG, JPEG, multi-page TIFF) → one page per frame, the frame stored byte for byte and painted full-page, and the words of the bound `core.OcrClient` laid over it in a RECOGNITION LAYER (FORMAT §B4b: an `OCDLayer` whose `ocr()` names the engine, one `OCDLayerContent` per page over the picture) as invisible runs (render mode 3) that fill their recognized boxes, every word of an engine line at that line's height, each carrying its confidence (`OCDText.confidence`, §B4 `data-conf`) — then the same `Analysis` as any document, which builds the layer's paragraphs inside it. No engine bound = the image alone. Engines are host bindings in `tool/` (`AzureOcrClient` — Document Intelligence `prebuilt-read`, endpoint and key from `JEXTER_AZURE_OCR_ENDPOINT` / `JEXTER_AZURE_OCR_KEY`, never in the repo — and `TesseractOcrClient`), chosen by `JEXTER_OCR=azure|tesseract`; `Conversion` sniffs the image signature, never the name.
- `ocd.analysis` — the **geometry-first** pass: `Cleaner → Paragrapher (Segmenter ─ Liner ─ Spacer.freeze) → Furniture → GraphicClusterer → OutlineAligner → IdStamper → buildOutline (BookmarkStructureBuilder | StructureBuilder) → HeadingRoles → Refiner` — **`ocd/analysis/Analysis.java` is the authority on this order**; `Liner`/`XYCut`/`Spacer` are inside `Paragrapher`, not stages. One authority per concern. The nav (bookmarks/outline) is ground truth: `OutlineAligner` re-cuts/merges title blocks on it and tags `heading-N`; `HeadingRoles` projects the best structure to page-level roles.
- `ocd.io` — `OCDReader`, the OCD-EPUB reader (three tiers: skeleton / one page / full model; fonts parse from `pages/fonts.svg`); `OCDVocab` — the serialization vocabulary: page ids AND the page member's name (`pageFile`/`isPageMember`), which the two EPUB writers and the reader all ask for rather than spelling.
- `write` — the projections (`SvgWriter`, `PdfWriter`, `EpubWriter`, `ReflowEpubWriter`, `HtmlWriter`, `MarkdownWriter`, `DocTagsWriter`); `OcdEpubWriter` + `SvgOcdWriter` + `OcdMembers` emit the OCD-EPUB; `OCDIndex` is the read-out; `Conversion` is the one entry point + target registry.
- `jj2000` (at the src root, NOT under `sugarcube.jexter`) — the **vendored** JJ2000 JPEG 2000 decoder: 159 files, the decode path only — no encoder, no image writers. PDFBox cannot read `JPXDecode` without the JAI tools and lost the image in silence; `pdfimport.JpxImage` is OUR pilot over it — codestream → raw samples, colour left to PDFBox, so DeviceN/Separation/Indexed/CMYK behave as for every other codec. Upstream code kept as it is — only its dangling `/**` license headers became `/*`, comments only (the 177 compiled classes are byte-identical): its warning-carrying classes each say so in one `@SuppressWarnings`, and doclint is scoped to our own files. Its licence rides along under either of ours — see `NOTICE` and `LICENSE-JJ2000`.
- `sugarcube.zigzag` (beside `sugarcube.jexter`, not under it) — ZigZag, the background-removal / binarization algorithm (DocEng 2024), VENDORED from `github.com/Bloechle/zigzag`: `ports/ZigZag.java` plus its package line and nothing else, so parity with the upstream's other ports is a `diff`. Change it upstream (its parity harness is the gate), then re-copy. jexter uses it as the OCR engine's INPUT, in ONE form: `ConvertOptions.OCR_PREP` = `zigzag-gray`, window sized from the image (`ImageImporter.zigzagSize`: `clamp(floor(diag/45 + 0.5), 20, 250)`, the rule agreed for ZigZag's automatic size, to hand over to the upstream once it ships it), never upsampled so the boxes land on the stored picture — and states it on the recognition layer (`ocr.prep`).
- `font` — `GlyfOtf` (glyf, PDFBox-embeddable) / `CffOtf` (CFF) recompile an embeddable font on demand from the model; `Foundry` builds one from drawn bitmaps, `pdfimport.TtfImporter` loads an existing one into the same request shape (the two halves of one loop — `font` stays FontBox-free, so they are composed by the caller, never by an import).
- `ui/*/web` — the CLIENT is half the product, and each file is named for what it registers: `prism.js` (chassis) · `engine.js` (the engine seam) · `analysis.js` · `ocr.js` (dev: the OCR layer made visible) · `augment.js` · `editor.js` · `redact.js` · `compare.js` · `forms.js` · `pages.js` · `trace.js` · `tts.js` · `gizmo.js`, over `shared/web/js/` (`ocd.js` the grammar, `book.js` the document authority, `shot.js` one picture of a page).
- `ui` — local zero-dependency `WebApp`s sharing `/shared/` (the mark, `sugarcube.css`, `js/icons.js` (the icon set), the client grammar, and `vendor/`: qry 3, fflate, Poppins — nothing is fetched, the apps work offline): `Prism` (the document workbench: reader, Augment, Edit, Redact, Pages, Analysis + its Trace overlay, fonts inspector, F2 dual console), `PDFInspector` (raw PDFBox), `Jexter` (PDF normalizer — also headless CLI + `--hotfolder` daemon via `JexterCli`: `--done[=<name|path>]`, `--suffix=`; a source is known by size + mtime, so a re-delivered PDF is normalized again; the window's Settings are not read by the CLI).

## Conventions (load-bearing)

- **DRY / KISS / SOTA** — "simple mais béton". No overengineering, no verbosity.
- **Fid2 = 0 is sacred** — the analysis layer is *additive*: it sets roles, reorders the content array into reading order, and builds an `OCDStructNode` tree by reference. It **never repaints**. Reading order ≠ paint order (`z`); the round-trip image can never regress. **Rendering first, structure second**: a page is stored in PAINT order, so a consumer *draws in DOM order and reads in `data-order`* — search, read-aloud and any text extraction re-sort first (`ocd.js` `inReadingOrder`).
- **Geometry-first** — the visual line is reconstructed and frozen before any text/font signal is read. No lexicon, no regex in the heuristic pass (OCR-robust). Nav-guided alignment is self-reference, not a lexicon: the matched strings come from the document itself.
- **One authority per concern** — one pass owns lines (`Liner`), one owns spaces (`Spacer`), one owns reading order (`XYCut`), one owns furniture (`Furniture`), one owns nav alignment (`OutlineAligner`), one owns heading roles (`HeadingRoles`).
- **The format is VERSIONED, never patched.** OCD is the canonical document model: a grammar change is a new version, designed first in `doc/FORMAT.md` (*Versions*), stated from ONE place per side (`OCDVocab.VERSION`, `ocd.js` `OCD_VERSION`), free to break backward compatibility — and a reader REFUSES a version it does not know. Never an attribute slipped in beside the others. v3 (2026-10-04) made recognized text native by REUSING what existed: a recognition layer over the picture (§B4b), the engine named once on the layer, `data-conf` on each word.
- **One representation per data** — fonts live once in `pages/fonts.svg` (paint + model); pages are self-contained data (`data-text`, `data-order`, lines, links, roles); capabilities travel as data, behaviors live in viewers — **no JS is ever embedded in an EPUB**.
- **One order for a matrix, and it is PDF's.** `JxTransform` is `(a b c d) = (m00 m10 m01 m11)`, the order PDF's `cm`, SVG's `matrix()` and `AffineTransform` all share — `toMatrix6`/`fromMatrix6` are the single authority, nothing formats those six numbers by hand.
- **A change that REMOVES content must prove the content is invisible** — every reference engine paints nothing there, and the ink measurement shows a gain, not a trade. Rendering first, structure second: OCD-EPUB is a faithful replica that also carries a logical layer, never the other way round.
- **Name by contract, not mechanism.** English code/comments/docs; French conversation.
- **Detect the obvious, defer the ambiguous** — headings + reading order are heuristic/nav; figures/tables/captions/lists/code are deferred to PDF/UA or the optional LLM `Refiner`.
- **`_` prefix = never committed.** `_artifacts/` holds the deliverables (`target/` is the intermediate scratch — the two are kept apart on purpose), `_private/` never leaves the machine; every one of them is ignored, without exception. `nimbus-jexter.cjs` at the root is the build orchestrator — tracked, because it is the documented way to build the project. Everything else under `src/` is the engine.

## Skills — read before working

| Where | Skill |
|---|---|
| anything in this repo: dev, fidelity gates, Prism, release | `.claude/skills/sugarcube-jexter/SKILL.md` (in the repo, updated by commit) |
| the build deck family (`nimbus-jexter.cjs`) | account skill `nimbus` |
| DocTags / OCD-AI | `.claude/skills/docling/SKILL.md` |
| Prism, PDFInspector, Jexter or any web UI | account skill `qry-js` |

Private notes (market audit, who consumes the library) stay in the account skill
`sugarcube-jexter`: this repo is public. Skill < this card < the source: a disagreement is a
signal — fix the skill in the same commit.

## Session setup

- **Repo** — `git clone --depth 1 https://github.com/Bloechle/sugarcube-jexter` (public). The clone
  is the hand-off: there is no AI bundle any more. Push straight to `main`; every push runs the CI
  gate below, so reproduce it first. Write LF. No customer document, name or contact ever enters
  the repo.
- **Build** — `node nimbus-jexter.cjs` (deck) or `node nimbus-jexter.cjs --run=jar|build <abs path>`;
  CI is `.github/workflows/build.yml`: `javac --release 21 -Xlint:all -Werror` over the WHOLE tree
  (`src/jj2000` included) + doclint over **our** files only (`grep -v '^src/jj2000/'`, with
  `-sourcepath src` so vendored types still resolve) + `node --check` + link check. `mvn -q package` is an IDE convenience, not the build.
  JDK 21 is the gate, and the whole tree is ALSO clean under JDK 25 — which is what `nimbus --run=jar` uses on a
  machine with a newer JDK. JDK 23+ added `dangling-doc-comments`, which no `@SuppressWarnings` can reach and JDK 21
  does not know as a flag: keep file-header comments `/*`, never `/**`.

## Gotchas

- Word spaces are **not** trusted from the source — they are re-derived by `Spacer` from glyph geometry; `Cleaner` strips every blank glyph first.
- Ligatures fold to letters only at **read-out** (`OCDIndex` NFKC); the model and the OCD-EPUB keep the source codepoint; SVG/PDF paint by glyph id, unaffected.
- The OCD-EPUB is **TrueType-free** — every font lives once in `pages/fonts.svg` (outlines + metrics + cmap, inkless glyphs keep their advance); an embeddable OTF is recompiled on demand.
- Model descents may be **negative** (PDF convention) — take the magnitude when sizing.
- A heading is *elevated AND recurs* (≥ 2 pages or ≥ 3 times); only the single largest elevated style (the title) is exempt from recurrence. Nav-tagged headings (`OutlineAligner`) win over projections.
- The `pdfimport` path is PDFBox-coupled; `write.PdfWriter` also uses PDFBox for PDF **export**.
- `.ocd.epub` naming: the last suffix says the TYPE (a valid EPUB), the `.ocd.` prefix says the FLAVOR (the model container) — the `.kepub.epub` pattern.
- **PDFBox, our reference, ignores** a page's `/Group /CS` blending space, the `/OutputIntents` profile and the anisotropic pen (it averages a stroke's width over both axes). A colour or stroke question is scored with **poppler AND MuPDF** too, and on the **whole** file: a pikepdf page extract drops the catalog, hence the output intent.
- A group's blend mode or opacity acts on the group **as one** (`OCDGroup.compositesAsOne`): `PdfWriter` emits a transparency-group form, the SVG writers a styled `<g>`, the renderer a layer — never the blend folded onto each child.
- **Invisible text is text.** A run in render mode 3 (a scan's OCR layer) is written by every text projection as real text with no ink — `PdfWriter` in BOTH modes as `Tr 3`, `SvgWriter` at zero opacity. Dropping it repaints the page identically and silently de-OCRs it: no pixel gate can see that, only `pdftotext` can.
- A path that paints nothing is **not written**: its operators with no painting operator survive `Q` as the current path, and the next `S` strokes them.
- **The three web apps are qry 3, vendored** (`ui/shared/web/vendor/qry/`: `qry.js`, `qry-web.js`, `qry-app.js`, `qry-ui.css`, copied as they are — refresh with qry2's `nimbus-qry2.cjs --run=vendor --app --to=<that dir>`, which copies those four). A module imports the APP tier, `/shared/vendor/qry/qry-app.js` (it re-exports `qry-web.js`, the website tier) — never `qry-web.js` directly. Native `<dialog>` (`makeDialog`; a side panel is `.qry-dialog.sheet`), popover menus (`makeMenu`), `.qry-seg` radios, `.qry-switch` checkboxes — no web components. Icons are qry's ONE workflow (qry2 10c2067): the set is `/shared/js/icons.js`, a module that ONLY calls `icons.add({ name: '<inner 24×24 SVG>' })` (Lucide's drawings, ISC — `vendor/LICENSE-lucide.txt`) and that each app's entry module imports first; the markup is `<i data-icon="name"></i>`, drawn by qry's `icons.watch()` — which `boot()` starts — as soon as it enters the document and redrawn when `data-icon` changes; in a string, `icon(name, { size, label, class })`. The `<i>` STAYS and an `<svg class="qry-icon">` goes inside it: qry-ui.css makes the `<i>` `display: contents` (the svg is the box, as when the `<i>` was replaced); `sugarcube.css` sets `.qry-icon` to 1.15em on the baseline; a size or colour rule targets `X .qry-icon` (a child rule `X > i > .qry-icon`), never the `<i>`, never `svg`. An unknown name draws qry's dashed square and logs ONE console.error — the benches fail on it (`scripts/bench/icons.mjs`). Server-sent streams (the `/api/alive` heartbeat, the F2 log, the conversion progress) are qry's `makeEvents`; zips stay fflate (Prism unzips books to binary members, synchronously too — qry's `zip()` writes only). The look is `/shared/sugarcube.css` — an app's sheet never restates the brand.
