# FORMAT — the OCD-EPUB working format

> The single working format of jexter: a valid fixed-layout **EPUB 3** that is also
> the lossless serialization of the OCD model. `OcdEpubWriter` writes it,
> `OCDReader` reads it back to the exact in-memory model. Any capable EPUB reader
> displays it faithfully with zero font dependency.
>
> **Part A** specifies the container — layout, manifest, and the `jexter/` JSON
> members, field by field. **Part B** specifies the page & font grammar: the
> strongly-typed SVG dialect the pages are written in.

Extension: **`.ocd.epub`** · media type `application/epub+zip` · no backward
compatibility — the grammar evolves in place under gates, `data-v="2"`.

Status: **v2 — implemented and gated** (`SvgOcdWriter` · `OcdMembers` ·
`OcdEpubWriter` · `OCDReader`). Standing gates: `write(read(v2)) = v2`
byte-identical (60/60 Fedlex pages + `f.svg`), `pdf→doctags ≡ (pdf→ocd)→doctags`
byte-identical, fonts reread exactly (glyphs / cmap / weights), `ocd→pdf`
selectable and `ocd→epub` from a reread container, `javac` 0 errors / 0 warnings.

---

# Part A — the container

## A1. Layout

```
mimetype                          stored first, uncompressed (OCF)
META-INF/container.xml            → OEBPS/content.opf
OEBPS/
  content.opf                     manifest + spine + metadata
  nav.xhtml                       EPUB nav (from the outline)
  toc.ncx                         EPUB 2 fallback nav
  pages/
    page-001.xhtml …              SVG-OCD v2 pages (one spine item each)
    f.svg                         THE font representation (shared, see §B6)
  images/<ref>                    shared raster resources (page <image> hrefs)
  media/<ref>                     audio/video payloads (referenced by a media node, §B3)
  images/cover.png                optional cover (rendered page 1)
  jexter/
    meta.json                     document identity & metadata
    outline.json                  navigation tree (page + y targets)
    structures.json               logical structure trees (by reference) — when any
    annots.json                   PDF annotations & form fields — sparse, when any
```

Everything under `jexter/` is a declared publication resource (manifest items
`jx-meta`, `jx-outline`, `jx-structures`, `jx-annots`; `f.svg` is item `glyphs`,
media type `image/svg+xml`). A generic EPUB reader ignores `jexter/*`; jexter
tooling requires `meta.json` to treat the file as a model (a foreign EPUB is a
book, not a model).

## A2. `jexter/meta.json`

```jsonc
{
  "format": "ocd-epub", "version": "2",
  "id": "…",                              // document id, when set
  "analysis": {                            // only when analysis ran
    "textSegmented": true, "headingsDetected": true },
  "layers": [ { "id", "name", "visible", "order" } ],   // PDF OCGs, when any
  "title": "…", "authors": ["…"], "subject": "…",
  "keywords": ["…"], "creator": "…", "producer": "…", "language": "fr-CH",
  "created": "…", "modified": "…",                      // language: PDF /Lang or detected (BCP-47)
  "custom": { "…": "…" }                                // XMP custom fields, when any
}
```

Empty fields are omitted. `language` is the DOCUMENT language; each page also
carries its own detected `xml:lang` (see §B2).

## A3. `jexter/outline.json`

The navigation tree, one node per entry:

```jsonc
{ "bookmarks": [ { "title": "Art. 10 Traitement médical",
                   "page": "page-006", "y": 640.2,      // target page id + y (page space)
                   "children": [ … ] } ] }
```

Source priority at import: PDF/UA tag tree > PDF bookmarks > heuristic headings
(`generateOutline` can force the heuristic in parallel). `nav.xhtml` and
`toc.ncx` are projections of this member.

## A4. `jexter/structures.json`

Logical structure **by reference** — trees of `OCDStruct` nodes that point at
content by `page` + node `id`, and never move a pixel:

```jsonc
{ "default": "pdf",                  // doc.defaultStructureId, when set
  "structures": [ {
    "id": "pdf", "label": "…",
    "source": "pdf_ua|heuristic|manual|llm",
    "by": "…", "at": 1720000000,     // epoch, when set
    "how": "…", "purpose": "…",
    "root": { "type": "document", "kids": [
      { "type": "h1", "level": 1, "text": "…", "lang": "…", "alt": "…",
        "refs": [ … ],               // content references (page + node id)
        "kids": [ … ] } ] }          // tables add colspan/rowspan/ordered/header
} ] }
```

Reproducibility: every timestamp that reaches output bytes — structure provenance (`at`), zip
entry times, EPUB `dcterms:modified` fallbacks, PDF dates and `/ID` — flows through one clock
authority honoring the standard `SOURCE_DATE_EPOCH` environment variable. Pin it and two
conversions of the same input are byte-identical on every target; unset, timestamps are real
provenance. The document id is content-addressed (SHA-256 of the source), so identity never
depends on the clock.

Multiple structures coexist (a tagged PDF's native tree next to the heuristic
one); the consumer — or Prism — chooses which to trust. Written by
`OcdMembers.structuresJson`, only when the document has any.

## A5. `jexter/annots.json` (sparse)

Non-paintable page payloads that have no SVG home. **Links are NOT here** — they
are native `<a>` in the pages (§B5).

```jsonc
{ "pages": { "page-004": {
    "annots": [ { "type": "highlight|note|…", "rect": [x,y,w,h],
                  "color": "#rrggbb", "author": "…", "modified": "…",
                  "contents": "…", "quads": [ … ] } ],
    "fields": [ { "name": "…", "type": "text|checkbox|…", "rect": [x,y,w,h],
                  "value": "…", … } ] } } }
```

Only pages that carry any appear; the member is omitted entirely when none do.

## A6. Reading contract

`OCDReader` reads in three tiers, all off the zip:

1. **Skeleton** — OPF spine → page list; `jexter/` members → meta, outline,
   structures, annots; `pages/f.svg` → the complete fonts (identity, weight,
   metrics, cmap, every glyph — inkless included). Images and media register
   lazily by name.
2. **One page** — parse a single `pages/page-NNN.xhtml` into OCD nodes (used by
   page-at-a-time consumers).
3. **Full model** — all pages; `write(read(x)) = x` byte-for-byte is the gate.

Parsing details that belong to the grammar: runs resolve their font via `data-f`;
the glyph id is the token after the last `-` in the `<use>` href (prefix-agnostic);
`children()` walks content and deliberately skips `<defs>/<style>`; the font parser
iterates by namespace. Anything the reader does not recognize is ignored, never
round-tripped blindly — what `write(read(x))` preserves is the MODEL, byte-for-byte.

Input sniffing everywhere in the pipeline: `%PDF` ⇒ import, `PK` ⇒ this
container. `to=ocd` on a PDF is *open*; on an OCD-EPUB it is *re-export*.

## A7. Determinism & gates

- One number formatter, sorted font aliases / gids / cmaps, document-stable ids:
  the same model serializes to identical bytes, so containers diff cleanly.
- Standing gates: page byte-stability (60/60 on Fedlex), `f.svg` byte-stability,
  doctags idempotence (`pdf→doctags ≡ pdf→ocd→doctags`), fonts reread exactly,
  `ocd→pdf` (selectable text extractable) and `ocd→epub` from a reread container,
  `javac` 0 errors / 0 warnings.

## A8. What this container is not

- **Not the distribution flavor** — that is the generic EPUB export (`to=epub`):
  native text, compiled OTF, no `jexter/` members, universal readers.
- **Not scripted** — no JS in pages or members; viewers own behavior.
- **Not versioned by suffix** — `data-v="2"` is the grammar's identity, evolved
  in place under gates, never bumped per tweak.

---

# Part B — the page & font grammar (SVG-OCD v2)

## B1. Principles

1. **EPUB-compliant, OCD-typed.** The container is OCF; the pages are valid
   XHTML+SVG — but the SVG is not free-form: it is the canonical serialization of
   the OCD model, emitted and consumed only by jexter tooling. Grammar over
   convention: every node carries a stable id and a typed `data-ocd` kind.
2. **The page is self-contained data.** Since v2 the page carries its text stream
   (`data-u`), reading order (`data-o`), line structure, links and page boxes.
   There is no page JSON member. Capabilities travel as data; behaviors live in
   viewers — **no JavaScript is ever embedded**.
3. **Fonts have ONE representation: `pages/f.svg`.** Paint (outlines) and model
   (metrics, cmap, advances) live in the same shared file; pages reference glyphs
   externally. `fonts/*.json` does not exist.
4. **Paint order = document order (z), as ever.** Reading order is carried by
   `data-o` and never moves a pixel. Everything OCD-specific rides on `data-*`
   attributes, stripped by the generic export.
5. **Byte-determinism.** Sorted font aliases, sorted gids, sorted cmaps, one
   number formatter: the same model always serializes to the same bytes.

## B2. The page — `pages/page-NNN.xhtml`

Each page is an XHTML document (EPUB 3 spine item, `properties="svg"`) whose body
is one `<svg>`:

```xml
<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"
     viewBox="0 0 595.3 841.9" width="595.3" height="841.9"
     data-ocd="page" data-v="2" xml:lang="fr"
     data-media="0 0 595.3 841.9" [data-crop|data-bleed|data-trim|data-art]
     [data-dpi] [data-rot]>
```

- **viewBox** — the effective box; Y-up→SVG flip exactly as `SvgWriter` (same
  audited math). A page with `/Rotate ≠ 0` wraps its content in
  `<g data-ocd="rot" transform="…">` — typed, so it is matched by name and never
  confused with another transformed group.
- **`xml:lang`** — the page language, detected at write time from the page's own
  text (`LanguageDetector`: dominant non-Latin script first, Latin stopwords
  second, abstains when unsure), falling back to the document language. Drives
  per-page TTS voices; deterministic, so the round-trip stays byte-stable.
- **`data-media/crop/bleed/trim/art`** — the PDF page boxes (`x y w h`), emitted
  only when present and distinct.

Inside, in order: `<style>` (deduped paint classes `s0, s1…`), `<defs>` (clip
paths, gradients), the content tree, then links.

Each `<clipPath>` carries the **model** clip id (`c1, c2…`), with the page flip
folded into its geometry like everything else in the page. The reader unfolds it
(the flip is self-inverse) and the round-trip is byte-exact because paths are
double-precision throughout — in float it was not, and that alone once forced a
second, self-cancelling wrapper. One id per clip, minted once at import; a
presentation id minted per `<defs>` entry would be a second naming authority and
every reference written from the model side would dangle.

A clip is applied **natively**, never as a data attribute — pages carry no script,
so a `data-clip` a viewer must interpret is not a clip at all. Every clipped node,
whatever its kind, is wrapped:

```xml
<g data-ocd="clip" data-ref="c7" clip-path="url(#c7)">
  <g id="t42" data-ocd="t" …>…</g>       <!-- byte-identical to its unclipped form -->
</g>
```

A node's own matrix is in SVG space and folds in whatever it likes (a run folds in
the font size and its CTM), so the clip cannot ride on the node — it rides on a
wrapper. The wrapper needs no transform of its own: the `<clipPath>` geometry is
stored in **SVG space**, the same space the wrapper sits in, so the two agree
without either moving. The wrapper is a paint carrier, spliced on read.

## B3. Content nodes

| OCD node | SVG | Notes |
|---|---|---|
| `OCDParagraph` | `<g id="pN" data-ocd="p" [data-o] [data-role] [data-flow]>` | roles: `heading-1..6`, `page-header`, `page-footer` |
| line (split on `OCDBreak`) | `<g data-ocd="l">` | pure grouping, render-neutral, children in CONTENT order |
| `OCDText` run | `<g id="tN" data-ocd="t" data-f data-u [data-cl] [data-b] [data-o] [data-rm] class transform>` | see §B4 |
| `OCDPath` | `<path id d class transform>` | `SvgWriter`'s audited emitter |
| `OCDImage` | `<image id xlink:href="../images/…" transform>` | shared resources |
| `OCDGroup` / graphic / layer | `<g id data-ocd="g\|gr\|layer" [data-tr] [data-ref]>` | `data-ref` = the layer id (§B4b) |
| `OCDMedia` | `<g id data-ocd="media" data-kind="video\|audio" data-ref [data-poster] [data-tr] [data-controls] [data-autoplay] [data-loop] [data-muted]>` | payload in `media/<ref>`; a poster `<image>` inside is a paint copy, not a node |
| clip carrier | `<g data-ocd="clip" data-ref="cN" clip-path transform><g transform>…</g></g>` | not a node — see below |

Every node also carries the shared paint state where it departs from the default:
**`data-name`**, **`data-blend`**, **`data-alpha`**, and — on a group or a media
node, whose placement cannot ride in a `transform` the reader would confuse with
the page flip — **`data-tr`** (the node's own matrix, six numbers).

- **`data-o`** — the content (reading) index, emitted only where it differs from
  the paint position. A reader re-sorts by `data-o` to recover reading order.
- **`data-role`** — the analysis verdicts that pages consume directly (furniture,
  headings). Everything richer lives in `jexter/structures.json` by reference.
- **`data-flow`** — a page-scoped text-flow id, emitted **only** on a paragraph
  that is a FRAGMENT. A paragraph must wrap a **contiguous paint span** — stored
  atomically, a wrapper straddling a foreign node's `z` would serialize that node
  after the whole paragraph and invert the painted result (a knockout label
  vanishing under its own tile). So a block whose runs interleave in paint order
  is stored as several fragments carrying the same `data-flow`. The split is a
  presentation fact: `StructureBuilder` rejoins one flow into one `PARAGRAPH`, so
  the projections that read the structure tree (Markdown, DocTags, HTML,
  reflowable EPUB) never see it, and a page-only consumer (selection, TTS,
  search) has what it needs to rejoin. A whole paragraph carries no attribute, so
  an unfragmented document serializes byte-identically.

### A standalone `.svg` carries its own fonts

The `svg` target writes ONE file and nothing beside it, so its `@font-face` sources
are `data:font/otf;base64,…` — the font rides inside. The same renderer feeds the
fixed-layout EPUB, where the pages sit in `OEBPS/pages/` beside a real
`OEBPS/fonts/` and a relative URL is both correct and necessary: repeating the font
bytes on every page of a 60-page book would be ruinous. One renderer, two
containers, and the caller states which (`SvgWriter.FontSrc`). A single file that
links to a directory nobody wrote does not fail loudly — the browser falls back to
a default serif and renders the page in the wrong font with the right glyph
positions, which is exactly the kind of wrongness that survives a pixel gate.

Every `font-family` is a STACK - the face, then the generic it belongs to
(`serif`, `sans-serif`, `monospace`), taken from the font's own descriptor
classification captured at import, never from a guess. A stack of one is a bet
that the face will load, and a face can always fail: bytes that will not compile,
a container written without its fonts, a viewer that refuses the format. The
browser then falls back to ITS default - a serif whatever the document was - and
repaints the page in the wrong family with the right glyph positions, letters
adrift in their own advances. The generic turns that disfigurement into a near
miss.

The cover is `images/cover.jpg`: page 1 rasterised, flattened on white, **600 px
on the long edge for every document**. One size whatever the page — a thumbnail
belongs to the shelf, not to the paper. Resolution is only how that size is
reached, so a small page is rendered at a higher dpi and stays sharp; every cover
then costs the same to make and to store. JPEG, because a rasterised page is a
photograph: lossless PNG cost 482 KB of a 722 KB container for a picture a reader
shows at a couple of hundred pixels. 600 is what a shelf needs — a preview at
~200 CSS px, which a 3x phone renders at exactly 600 — and nothing above it is
visible: 1000 px cost 233 KB against 90 KB for the same title read the same.
A page thumbnail is NOT stored per page: a viewer scales the page itself, which
is vector and therefore sharp at any size and free.

The cover is written for **every** document, including the `ocd` working format.
On a one-page text document it is still the larger half of the container, and it
stays: a library, a shelf, a file picker or an API listing has to show something
without opening the book, and a container that cannot show itself is worse than a
heavy one.

### Which scripts the geometry restructures

The rules that read a horizontal gap as MEANING - the order of runs on a line, the
slack that becomes a word space, the space that seals a line's end - are statements
about **left-to-right text whose words are parted by spaces**. Latin, Greek,
Cyrillic. Applied elsewhere they do not mis-order, they corrupt: a line-ending
space belongs on the rightmost run in Latin and on the leftmost in Hebrew, and
putting it on the wrong one drops a space into the middle of a word.

So outside that case the geometry stands back (`JxText.isLtrWordScript`): a Hebrew,
Arabic or CJK line keeps the runs, the order and the text the producer wrote. For
CJK this is not a limitation but the correct answer - those scripts part no words
with spaces at all, so no gap can be read as one. For right-to-left scripts it is a
deliberate abstention: the word space exists there and could be inferred by
mirroring the rules, but not on the strength of a synthetic test, because a PDF
generator without a bidi shaper lays the codepoints out left to right and does not
reproduce how a real RTL document is set.

A PDF that already carries its own structure is a different matter: that structure
is honoured whatever the script, because it is stated rather than inferred.

### What a reader must do with a broken container

A container can arrive hand-edited, truncated, or written by another tool. The
reader is **liberal but never silent**:

- **Unknown attributes and elements are ignored** — the grammar may grow.
- **A reference that does not resolve is DROPPED and reported**: a `data-ref`
  naming no `<clipPath>`, a `data-f` naming a font the container never carried, a
  structure `Ref` naming no node. A dangling reference is worse than none — it
  survives into the model and silently drops a clip or blanks a page's text while
  every gate reports green. The model handed back is always self-consistent.
- **A malformed value is refused with its context** — which grammar it broke and
  what was read instead, never a bare parse exception.
- **No DOCTYPE is accepted**, so external entities and entity expansion cannot
  reach the parser at all.

### What a round-trip preserves

`read(write(x))` returns the same model, with three stated exceptions — measured
per node, per field, on every corpus page:

- **`z` is re-derived** from document order. It is not stored: document order IS
  paint order, so storing it too would be a second authority. The *values* differ
  after a round-trip; the *order* is identical, and that is the invariant.
- **Coordinates come back at export precision** (4 decimals — max deviation
  measured: 0.00006). This also removes the source's float noise: a run written
  `0.9499999682108561` reads back `0.95`.
- **Render-transparent `OCDGraphic` wrappers are spliced** and do not come back.
  They are analysis clustering, not content; a re-analysis re-derives them.
- **An empty-geometry `OCDPath` is not written back.** A path whose geometry holds no
  segment at all serializes to `d=""`: it paints nothing on any surface (PDF included —
  with butt caps a zero-extent stroke marks nothing) and carries no geometry to restore,
  so the writer drops it. Real documents contain them: a paint state set up by a `q … Q`
  block whose path construction never emitted a segment. Measured on a 117-page survey
  atlas: 43 such paths, every one with zero bounds and no segments.

Everything else — ids, roles, names, clips, alpha, blend, text, font id, font
size, render mode, fills, strokes, path geometry — round-trips exactly.

## B4. Text runs

The font size is **factored into the run matrix** — `transform="matrix(fs·flip·tr)"`
— because glyphs are em-normalized and the matrix has to carry the size to place
them. It is **also stated** as `data-fs`, and that is the one a reader must use:
the matrix alone cannot give it back. Recovering it as `√|det|` holds only when
the run's own matrix has `|det| = 1`, and an anisotropic run — PDF `Tz` horizontal
scaling, a squeezed CTM — makes size and matrix indistinguishable. The product
still paints correctly, so the error is silent, and surfaces only later as a wrong
size in the analysis signals. **The format states, it does not infer.**

```xml
<g id="t42" data-ocd="t" data-f="TimesNewRomanPSMT" data-fs="9" class="s0"
   data-u="Traitement médical "
   transform="matrix(9 0 0 -9 365.1 565.08)">
  <use href="f.svg#f5-49" x="0"/><use href="f.svg#f5-47" x="0.7533"/>…
</g>
```

- **`data-fs`** — the font size, in points. Stated, never inferred (see above).
- **`data-u`** — the unicode of the FULL stream, blanks included, XML-escaped.
  The single text authority for search, TTS, tree views and read-out.
- **`data-cl`** — per-glyph char counts, only when non-uniform (ligatures,
  surrogates).
- **`data-b`** — never-painted sentinels as `at:xem` / `at:gid:xem` tokens
  (blank glyphs the Spacer accounted for but that paint nothing).
- **`data-rm`** — the text render mode (PDF `Tr`), emitted only when it is not
  plain fill: `1` stroke, `2` fill+stroke, `3` invisible, `4..7` the clipping
  variants.
- **`data-fill`** — the run's fill colour as `#rrggbbaa`, emitted **only** when the
  render mode paints no fill (`1`, `3`, the clipping variants) and the run carries a
  real colour. The paint class then says `fill:none`, which is correct — and leaves
  the colour with no home in the page, so it reads back as 0 while the bytes agree
  and every pixel matches. The mode is stated, so the colour that goes with it is
  stated too; the pair is symmetric or it is lossy. It costs nothing on an ordinary
  run (nothing is emitted) and matters the moment a mode is *flipped*: an OCR layer
  (`3`, i.e. every scanned PDF) made visible in an editor must not come back
  colourless. The reader lets it win over the class.
- Glyphs paint by **gid**; unicode never selects a glyph. Inkless glyphs are
  never referenced by `<use>` (nothing to paint) — their advance/unicode live in
  `f.svg`.

## B4b. Layers — full-page content strata

A **layer is a full-page stratum**, never a small localized group: one
`<g id data-ocd="layer" data-ref="LAYER_ID">` per authored layer, a DIRECT child
of the page `<svg>` (read back as `OCDLayerContent`). Stacking = document order;
presentation (name, visibility, order) lives ONCE in the document-level registry,
`jexter/meta.json`:

```json
"layers": [ { "id": "compose", "name": "Composition", "visible": true, "order": 0 } ]
```

**The engine-imported base (PDF → jexter → ocd-epub) is NOT wrapped**: top-level
content outside any layer group IS the implicit **source stratum** — layer zero,
by convention. Rationale: a wrapper would churn every emitted page (byte-stability,
golden outputs) for zero render gain; z-order needs no wrapper either — a layer
inserted BEFORE the base content paints under it, one appended after paints over.
The explicit form exists for content that needs stratification (client-tool
placements, future annotation/media layers); the reader parses it wherever tools
mint it.

## B5. Links — native `<a>`

PDF link annotations become native SVG anchors in a trailing group — clickable in
any reader, no script:

```xml
<g data-ocd="links">
  <a href="page-012.xhtml" data-y="640.2"><rect x=… y=… width=… height=…
     style="fill-opacity:0"/></a>          <!-- internal: sibling page + target y -->
  <a href="https://…"><rect …/></a>        <!-- external -->
</g>
```

## B6. Fonts — `pages/f.svg`, the single representation

One shared sibling file carries every font of the document — paint **and** model:

```xml
<svg xmlns="http://www.w3.org/2000/svg" data-ocd="fonts" data-v="2">
<defs>
<g id="f0" data-f="ArialMT" data-id="ArialMT" [data-name] [data-family]
   [data-weight="bold"] [data-style="italic"] [data-embedded="1"]
   data-asc=".75" data-desc=".25" data-cap=".716" data-x=".519" data-sp=".25"
   data-cmap="40:36 41:37 …">                    <!-- codepoint:gid, SORTED -->
  <path id="f0-36" d="M…Z" data-adv=".556" data-u="("/>
  <path id="f0-3"  d=""    data-adv=".278" data-u=" "/>   <!-- inkless: advance survives -->
</g>
…
</defs>
</svg>
```

- **Aliases are document-stable**: `f0..fN` over the SORTED safe font names —
  independent of page order and first-use order, identical on both sides of the
  round-trip.
- Per font: identity (`data-f` = safe name, `data-id` = verbatim id, optional
  name/family), `weight`/`style` (defaults omitted), `embedded`, metrics
  (ascent/descent/cap-height/x-height/space-width, em-normalized), and the
  explicit **cmap** sorted by codepoint.
- Per glyph: the outline `d` (compact canonical form — no decorative whitespace),
  `data-adv` (advance), `data-u` (unicode), optional `data-gname`. **Every**
  glyph is emitted, inkless ones with `d=""` — spaces keep their advance (the
  Spacer's half-advance rule survives a reload), and PDF export recompiles a real
  OTF (`GlyfOtf`) from exactly this data.
- Pages reference glyphs externally: `<use href="f.svg#f0-36" x="…">` — the same
  pattern as the shared `images/`. Requires external-`<use>` support (Chromium
  ≥ ~126, Firefox, Safari) — the OCD-EPUB targets Prism; the generic EPUB export
  remains the universal flavor.
- Measured on Fedlex (60 pages): glyph defs were 49 % of raw page weight,
  duplicated 27× (7 340 copies of 273 outlines). Shared: pages −47 % raw,
  container −54 % compressed on the pages' share; `f.svg#` short names save
  another ~10 % raw per page (125 218 references).

## B7. What is deliberately NOT in the pages

- **No JavaScript** — ever, for anything (bounds, overlays, selection).
  Reading-system scripting is optional in the EPUB spec and dead in practice;
  viewers own behavior.
- **No fonts, no `<text>`** — glyph outlines only; the generic EPUB export is the
  flavor with native text + compiled OTF.
- **No page JSON member, no thumbnails member** — the page is the single source
  of truth; previews are rendered on demand.
