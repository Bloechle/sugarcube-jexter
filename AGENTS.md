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

> **PUBLIC REPOSITORY (AGPL-3.0).** Everything committed here is public: code, docs, this
> card, the skill and every commit message. Never write into it: hosting, servers, private
> URLs, keys or tokens; customers (names, documents, contacts, prices) or market notes; the
> content or names of private repos and services; personal data of anyone. Those stay
> private, outside this repo. When in doubt, leave it out and ask Jean-Luc.

`jexter` opens a PDF and lifts it into **one** normalized model — the **OCD**
(Open Canonical Document) — then projects that single model to every output.
Normalize once, export many; a round-trip stays faithful by construction.

```
PDF ─[pdfimport.PdfImporter]→ OCDDocument ─[analysis]→ structured OCDDocument
                            (the model)        │
                                               ├─[write.*]→ PDF · EPUB · HTML · SVG · MD · DocTags
                                               └─[ocd.io]→ OCD-EPUB ─[ocd.io.OCDReader]→ OCDDocument
                                                  ocd.render.OCDRenderer → raster
```

PDFBox is both the **parser** and the **single reference rasterizer**, so the raster
fallback and the fidelity check agree by construction, not by luck — and it is blind where
we are blind (see Gotchas: a pixel gate against it cannot see those defects). The model carries
two layers kept strictly apart: a **presentation** layer (what paints) and a **logical**
`OCDStructNode` layer (what it means) that only ever *references* content by id — it never
moves a pixel.

## Package map (the contract of each area)

- `Conversion` (the package root, beside `WriterCli`, its drop-a-file window) — the one entry point: the target registry, the content sniffing (PDF · image · OCD-EPUB, never the name), the CLI.
- `ocd.model` — the sealed node types (`OCDText/Path/Image/Group/Media/Break`), fonts/glyphs, page, document. Y-up, em/unit-square geometry, derived bounds, 1-based ids. `OCDIndex` is the read-out: a node's or a structure element's text, in reading order, ligatures folded.
- `pdfimport` — the **only PDFBox-coupled import layer**: `PdfStreamEngine` walks the content stream into primitive OCD nodes (`PdfColors` its paint conversions); `PdfCatalog` reads what lies outside the streams — metadata, outline and links (named destinations resolved), layers, output intent, annotations, forms, and each page's frame (`page`, shared with `PdfRenderer`); `FontExtractor` rebuilds fonts (`TrueTypeRepair` first fixes what FontBox cannot parse); `TaggedStructureBuilder` lifts PDF/UA tags by their STANDARD type (RoleMap followed, `LBody` folded into its item, MCIDs keyed by their content stream) — skippable via `ignoreTags`. Paint the model cannot vectorise (soft masks, tiling patterns) is cropped from `ReferenceRaster` — PDFBox drawing only that paint — and `SoftMasks` is the one mask rule of the importer and the direct renderer. Colour: the model paints in sRGB; a CMYK source colour rides beside it (`JxCmyk`, `data-cmyk`), `DeviceCMYK` converts through the document's own output intent when it has one (`OutputIntentCmyk`), and the page's CMYK blending space and the intent itself are carried and written back (`OCDPage.cmykBlending`, `OCDOutputIntent`).
- `imageimport` — `ImageImporter`: a scan or a photo (PNG, JPEG, multi-page TIFF) → one page per frame, the frame stored byte for byte and painted full-page, and the words of the bound `core.OcrClient` laid over it in a RECOGNITION LAYER (FORMAT §B4b: an `OCDLayer` whose `recognition()` names the engine and the preparation, one `OCDLayerContent` per page over the picture) as invisible runs (render mode 3) that fill their recognized boxes, every word of an engine line at that line's height, each carrying its confidence (`OCDText.confidence`, §B4 `data-conf`) — then the same `Analysis` as any document, which builds the layer's paragraphs inside it. No engine bound = the image alone. Engines are host bindings in `bind/` (`AzureOcrClient` — Document Intelligence `prebuilt-read`, endpoint and key from `JEXTER_AZURE_OCR_ENDPOINT` / `JEXTER_AZURE_OCR_KEY`, never in the repo — and `TesseractOcrClient`), chosen by `JEXTER_OCR=azure|tesseract` and bound by `OcrEngines.bindFromEnv()`, the one call every host makes (Prism, or any service embedding the engine) — `LlmEngines.bindFromEnv()` is its twin for the language model of `Refiner`; `Conversion` sniffs the image signature, never the name.
- `ocd.analysis` — the **geometry-first** pass: `Cleaner → Paragrapher (Segmenter ─ Liner ─ Spacer.freeze) → Furniture → GraphicClusterer → OutlineAligner → IdStamper → buildOutline (BookmarkStructureBuilder | StructureBuilder) → HeadingRoles → Refiner` — **`ocd/analysis/Analysis.java` is the authority on this order**; `Liner`/`XYCut`/`Spacer` are inside `Paragrapher`, not stages. One authority per concern. The nav (bookmarks/outline) is ground truth: `OutlineAligner` re-cuts/merges title blocks on it and tags `heading-N`; `HeadingRoles` projects the best structure to page-level roles.
- `ocd.io` — the whole OCD-EPUB codec, read and write side by side: `OCDReader` reads the container (three tiers: skeleton / one page / full model; the spine; the JSON members) and `SvgOcdReader` one page or the font sheet `pages/fonts.svg`, the inverse of `OcdEpubWriter` + `SvgOcdWriter` + `OcdMembers`. `SvgPaint` is the neutral SVG paint of a node (path, image, clip, group compositing, pen), shared with `write.SvgWriter`; `EpubPackage` the EPUB3 package the three EPUBs share (OCF, cover, nav, NCX, OPF, the one media-type table). `OCDVocab` — the serialization vocabulary: the format version, the member names (`OPF`, `MEMBERS`, `pageFile`/`pageHref`/`pagePath`/`pageLink`, `isPageMember`), page ids and the cover key, which the EPUB writers and the reader all ask for rather than spelling.
- `write` — the projections (`SvgWriter`, `PdfWriter`, `EpubWriter`, `ReflowEpubWriter`, `HtmlWriter`, `MarkdownWriter`, `DocTagsWriter`), one `Projection` contract each.
- `bind` — the host bindings: `AzureOcrClient`, `TesseractOcrClient`, `HttpLlmClient`, and the two env binders `OcrEngines` / `LlmEngines`. `cli` — `Redact`, the audit/apply command line with exit codes. Every command line parses through `core.Args`.
- `jj2000` (at the src root, NOT under `sugarcube.jexter`) — the **vendored** JJ2000 JPEG 2000 decoder: 159 files, the decode path only — no encoder, no image writers. PDFBox cannot read `JPXDecode` without the JAI tools and lost the image in silence; `pdfimport.JpxImage` is OUR pilot over it — codestream → raw samples, colour left to PDFBox, so DeviceN/Separation/Indexed/CMYK behave as for every other codec. Upstream code kept as it is — only its dangling `/**` license headers became `/*`, comments only (the 177 compiled classes are byte-identical): its warning-carrying classes each say so in one `@SuppressWarnings`, and doclint is scoped to our own files. Its licence rides along under either of ours — see `NOTICE` and `LICENSE-JJ2000`.
- `sugarcube.zigzag` (beside `sugarcube.jexter`, not under it) — ZigZag, the background-removal / binarization algorithm (DocEng 2024), VENDORED from `github.com/Bloechle/zigzag`: `ports/ZigZag.java` plus its package line and nothing else, so parity with the upstream's other ports is a `diff`. Change it upstream (its parity harness is the gate), then re-copy. Version 1.3.5 (2026-10-17: integer box sums, about 2× faster than the 1.1-era copy it replaced, same output). jexter uses it as the OCR engine's INPUT: `ConvertOptions.OCR_PREP` = `zigzag-gray`, ZigZag's automatic window (`ZigZag.AUTO`: `clamp(floor(diag/45 + 0.5), 20, 250)`, the rule now upstream's), despeckle OFF — or `zigzag-gray-despeckle`, the same with ZigZag's automatic despeckle (two readings, two names: `zigzag-gray` keeps meaning the reading it always named), never upsampled so the boxes land on the stored picture — and states it on the recognition layer (`ocr.prep`).
- `font` — `GlyfOtf` (glyf, PDFBox-embeddable) / `CffOtf` (CFF) recompile an embeddable font on demand from the model; `Foundry` builds one from drawn bitmaps, `pdfimport.TtfImporter` loads an existing one into the same request shape (the two halves of one loop — `font` stays FontBox-free, so they are composed by the caller, never by an import).
- `ui/*/web` — the CLIENT is half the product, and each file is named for what it registers: `prism.js` (chassis, with its parts `events.js` · `tools.js` (the registry, ribbon, keys) · `console.js` (F2) · `search.js`) · `engine.js` (the engine seam) · `backend.js` (the transport seam) · `analysis.js` · `ocr.js` (dev: the OCR layer made visible) · `augment.js` · `editor.js` · `redact.js` · `compare.js` · `forms.js` · `pages.js` · `trace.js` · `tts.js` · `gizmo.js`, over `shared/web/js/` (`ocd.js` the grammar, `book.js` the document authority, `shot.js` one picture of a page). `prism/web/reader.html` is Prism's READER EDITION: the chassis and read aloud only, for a host that lets people read and nothing more.
- `ui` — local zero-dependency `WebApp`s sharing `/shared/` (the mark, `sugarcube.css`, `js/icons.js` (the icon set), the client grammar, and `vendor/`: qry 3, fflate, Poppins — nothing is fetched, the apps work offline): `Prism` (the document workbench: reader, Augment, Edit, Redact, Pages, Analysis + its Trace overlay, fonts inspector, F2 dual console), `PDFInspector` (raw PDFBox), `Jexter` (PDF normalizer — also headless CLI + `--hotfolder` daemon via `JexterCli`: `--done[=<name|path>]`, `--suffix=`; a source is known by size + mtime, so a re-delivered PDF is normalized again; the window's Settings are not read by the CLI).

## Conventions (load-bearing)

- **DRY / KISS / SOTA** — "simple mais béton". No overengineering, no verbosity.
- **Fid2 = 0 is sacred** — the analysis layer is *additive*: it sets roles, reorders the content array into reading order, and builds an `OCDStructNode` tree by reference. It **never repaints**. Reading order ≠ paint order: in the MODEL the content array is in reading order and the renderer paints by `z`; the round-trip image can never regress. **Rendering first, structure second**: a serialized page is stored in PAINT order, so a consumer *draws in DOM order and reads in `data-order`* — search, read-aloud and any text extraction re-sort first (`ocd.js` `inReadingOrder`).
- **Geometry-first** — the visual line is reconstructed and frozen before any text/font signal is read. No lexicon, no regex in the heuristic pass (OCR-robust). Nav-guided alignment is self-reference, not a lexicon: the matched strings come from the document itself.
- **One authority per concern** — one pass owns lines (`Liner`), one owns spaces (`Spacer`), one owns reading order (`XYCut`), one owns furniture (`Furniture`), one owns nav alignment (`OutlineAligner`), one owns heading roles (`HeadingRoles`).
- **The format is VERSIONED, never patched.** OCD is the canonical document model: a grammar change is a new version, designed first in `doc/FORMAT.md` (*Versions*), stated from ONE place per side (`OCDVocab.VERSION`, `ocd.js` `OCD_VERSION`), free to break backward compatibility — and a reader REFUSES a version it does not know. Never an attribute slipped in beside the others. v4 (2026-10-19) is the current one, and the reader takes it ONLY: an older container is reconverted from its source, never half-read (the project is in full development, no backward compatibility).
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
| Prism, PDFInspector, Jexter or any web UI | skill `qry`, in `Bloechle/Qry` (`.claude/skills/qry/`) |

Skill < this card < the source: a disagreement is a signal — fix the skill in the same commit.

## Session setup

- **Repo** — `git clone --depth 1 https://github.com/Bloechle/sugarcube-jexter` (public). The clone
  (this card + the skill) is the hand-off. Push straight to `main`; every push runs the CI
  gate below, so reproduce it first. Write LF. Public: the banner above applies to every commit.
- **Build** — `node nimbus-jexter.cjs` (deck) or `node nimbus-jexter.cjs --run=jar|build <abs path>`;
  CI is `.github/workflows/build.yml`: `javac --release 21 -Xlint:all -Werror` over the WHOLE tree
  (`src/jj2000` included) + doclint over **our** files only (`grep -v '^src/jj2000/'`, with
  `-sourcepath src` so vendored types still resolve) + `node --check` + link check + the **functional gates**.
  `mvn -q package` is an IDE convenience, not the build. The jar carries its identity — version, git
  commit (`-dirty` when the tree had changes), build time — written by the deck at `BUILD_INFO` (`.env`) and
  read by `core.JxBuild` (`Conversion --version`), so a service embedding the jar can say which build it runs.
- **Test** — `node nimbus-jexter.cjs --run=test` (what CI runs, any OS): compiles into `target/classes`, then
  `test/Gates.java` writes the fixture corpus into `target/corpus` (`test/Fixtures.java`, PDFBox only) and gates it — REPRO, DOCTAGS, STABLE, MODEL, TIERS,
  NAV, TAGS, PIXELS (the model's render against PDFBox's and against the direct render), AGAIN (a second analysis
  changes nothing), FURNITURE, STRADDLE, CLIPS, COPY, DRAWN, WORDS, EDITED, HOSTILE, one JVM, ~40 s. A red STABLE names the container: `test/ContainerDiff.java` shows
  which member moved. A change is done when this is green; a new defect class gets its check here.
  JDK 21 is the gate, and CI also compiles under JDK 25 (its lints are stricter) — which is what `nimbus --run=jar` uses on a
  machine with a newer JDK. JDK 23+ added `dangling-doc-comments`, which no `@SuppressWarnings` can reach and JDK 21
  does not know as a flag: keep file-header comments `/*`, never `/**`.

## Gotchas

- Word spaces are **not** trusted from the source — they are re-derived by `Spacer` from glyph geometry; `Cleaner` strips every blank glyph first.
- Ligatures fold to letters only at **read-out** (`OCDIndex` NFKC); the model and the OCD-EPUB keep the source codepoint; SVG/PDF paint by glyph id, unaffected.
- The OCD-EPUB is **TrueType-free** — every font lives once in `pages/fonts.svg` (outlines + metrics + cmap, inkless glyphs keep their advance); an embeddable OTF is recompiled on demand.
- A font's **descent is negative** (Y-up, the PDF convention): `OCDFont.descent(v)` stores `-|v|`, whatever a producer hands it.
- A heading is *elevated AND recurs* (≥ 2 pages or ≥ 3 times); only the single largest elevated style (the title) is exempt from recurrence. Nav-tagged headings (`OutlineAligner`) win over projections.
- The `pdfimport` path is PDFBox-coupled; `write.PdfWriter` also uses PDFBox for PDF **export**.
- `.ocd.epub` naming: the last suffix says the TYPE (a valid EPUB), the `.ocd.` prefix says the FLAVOR (the model container) — the `.kepub.epub` pattern.
- **PDFBox, our reference, ignores** a page's `/Group /CS` blending space, the `/OutputIntents` profile and the anisotropic pen (it averages a stroke's width over both axes), and paints a radial shading OUTSIDE an end circle that does not extend (poppler, and the spec, leave it empty) — the PIXELS fixtures avoid that case rather than learn PDFBox's answer. A colour or stroke question is scored with **poppler AND MuPDF** too, and on the **whole** file: a pikepdf page extract drops the catalog, hence the output intent.
- A group's blend mode or opacity acts on the group **as one** (`OCDGroup.compositesAsOne`): `PdfWriter` emits a transparency-group form, the SVG writers a styled `<g>`, the renderer a layer — never the blend folded onto each child.
- **Invisible text is text.** A run in render mode 3 (a scan's OCR layer) is written by every text projection as real text with no ink — `PdfWriter` in BOTH modes as `Tr 3`, `SvgWriter` at zero opacity. Dropping it repaints the page identically and silently de-OCRs it: no pixel gate can see that, only `pdftotext` can.
- **The local apps serve only themselves.** `WebApp` registers every route through one guard (`routes(Routes)`,
  never `HttpServer.createContext`): foreign `Host`, foreign `Origin` or a cross-site fetch → 403, methods other
  than GET/HEAD/POST → 405, bodies through `body(x)` (512 MB cap → 413). Any web page can POST to 127.0.0.1;
  these routes read files by path and bind an LLM key. `nimbus-jexter.cjs` guards its `/run` the same way.
- A path that paints nothing is **not written**: its operators with no painting operator survive `Q` as the current path, and the next `S` strokes them.
- **The three web apps are qry 3, vendored** (`ui/shared/web/vendor/qry/`: `qry.js`, `qry-web.js`, `qry-app.js`, `qry-ui.css`, copied as they are — refresh with Qry's `nimbus-qry.cjs --run=vendor --app --to=<that dir>`, which copies those four). A module imports the APP tier, `/shared/vendor/qry/qry-app.js` (it re-exports `qry-web.js`, the website tier) — never `qry-web.js` directly. Native `<dialog>` (`makeDialog`; a side panel is `.qry-dialog.sheet`), popover menus (`makeMenu`), `.qry-seg` radios, `.qry-switch` checkboxes — no web components. Icons are qry's ONE workflow (Qry 962154e): the set is `/shared/js/icons.js`, a module that ONLY calls `icons.add({ name: '<inner 24×24 SVG>' })` (Lucide's drawings, ISC — `vendor/LICENSE-lucide.txt`) and that each app's entry module imports first; the markup is `<i data-icon="name"></i>`, drawn by qry's `icons.watch()` — which `boot()` starts — as soon as it enters the document and redrawn when `data-icon` changes; in a string, `icon(name, { size, label, class })`. The `<i>` STAYS and an `<svg class="qry-icon">` goes inside it: qry-ui.css makes the `<i>` `display: contents` (the svg is the box, as when the `<i>` was replaced); `sugarcube.css` sets `.qry-icon` to 1.15em on the baseline; a size or colour rule targets `X .qry-icon` (a child rule `X > i > .qry-icon`), never the `<i>`, never `svg`. An unknown name draws qry's dashed square and logs ONE console.error — the benches fail on it (the skill's `scripts/bench/icons.mjs`). Server-sent streams (the `/api/alive` heartbeat, the F2 log, the conversion progress) are qry's `makeEvents`; zips stay fflate (Prism unzips books to binary members, synchronously too — qry's `zip()` writes only). The look is `/shared/sugarcube.css` — an app's sheet never restates the brand.
