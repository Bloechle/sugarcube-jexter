// ============================================================================
//  jexter — Sugarcube : a technical overview
//  Typeset with Typst. Figures are hand-authored SVG embedded via image().
// ============================================================================

#let ink     = rgb("#1c1e1b")
#let soft    = rgb("#5f6360")
#let faint   = rgb("#9aa09a")
#let hair    = rgb("#e6e7e2")
#let paper   = rgb("#fcfcfa")
#let green   = rgb("#4e8f1f")
#let greenD  = rgb("#2f5e12")
#let greenP  = rgb("#eef6df")
#let indigo  = rgb("#3b5b7a")
#let indigoP = rgb("#e7edf4")
#let amber   = rgb("#b8801f")
#let amberP  = rgb("#f6ecd7")

#set document(title: "jexter — Sugarcube", author: "Sugarcube Information Technology Sàrl")
#set page(
  paper: "a4",
  margin: (top: 2.4cm, bottom: 2.2cm, x: 2.3cm),
  fill: paper,
  header: context {
    if counter(page).get().first() > 1 [
      #set text(size: 8.5pt, fill: faint, font: "Poppins")
      #grid(columns: (1fr, 1fr),
        align(left)[#box(baseline: 25%, image("fig/jexter-mark.svg", height: 10pt)) #h(4pt) Sugarcube jexter],
        align(right)[The OCD pipeline],
      )
      #v(-6pt)
      #line(length: 100%, stroke: 0.5pt + hair)
    ]
  },
  footer: context {
    if counter(page).get().first() > 1 [
      #set text(size: 8.5pt, fill: faint, font: "Poppins")
      #line(length: 100%, stroke: 0.5pt + hair)
      #v(-2pt)
      #grid(columns: (1fr, 1fr),
        align(left)[© 2026 Sugarcube Information Technology Sàrl],
        align(right)[#counter(page).display() / #context counter(page).final().first()],
      )
    ]
  },
)

#set text(font: "Lora", size: 10.5pt, fill: ink, lang: "en")
#set par(justify: true, leading: 0.72em, spacing: 1.35em, first-line-indent: 0pt)

// ---- headings ----------------------------------------------------------------
#set heading(numbering: "1.1")
#show heading: set text(font: "Poppins", fill: ink)
#show heading.where(level: 1): it => block(above: 1.7em, below: 0.9em, sticky: true)[
  #set text(size: 16pt, weight: 600)
  #grid(columns: (auto, 1fr), gutter: 10pt, align: horizon,
    box(fill: greenP, inset: (x: 7pt, y: 3pt), radius: 5pt)[
      #set text(fill: greenD, size: 12pt)
      #counter(heading).display("1")
    ],
    it.body,
  )
]
#show heading.where(level: 2): it => block(above: 2.0em, below: 0.95em, sticky: true)[
  #set text(size: 12pt, weight: 600, fill: greenD)
  #counter(heading).display("1.1") #h(6pt) #it.body
]

// ---- inline code / raw -------------------------------------------------------
#show raw.where(block: false): it => box(
  fill: rgb("#f3f4f0"), inset: (x: 3pt, y: 0pt), outset: (y: 2pt), radius: 3pt,
)[#text(font: "DejaVu Sans Mono", size: 8.8pt, fill: rgb("#34403a"))[#it]]

// ---- block code (dark terminal) ----------------------------------------------
#show raw.where(block: true): it => block(
  width: 100%, fill: rgb("#1c1e1b"), radius: 6pt, inset: (x: 14pt, y: 12pt),
)[
  #set text(font: "DejaVu Sans Mono", size: 8.4pt, fill: rgb("#cfe6b0"))
  #set par(leading: 0.82em, justify: false)
  #it
]

// ---- figures -----------------------------------------------------------------
#set figure(numbering: "1")
#show figure.caption: it => block(width: 86%)[
  #set text(size: 8.8pt, fill: soft, font: "Poppins")
  #text(fill: green, weight: 600)[#it.supplement #context it.counter.display()] #h(4pt) #it.body
]
#let fig(path, cap, w: 100%) = figure(
  block(width: w, inset: 0pt, image(path, width: w)),
  caption: cap,
  gap: 0.7em,
)
#let figpin(path, cap, w: 100%) = figure(
  block(width: w, inset: 0pt, image(path, width: w)),
  caption: cap,
  gap: 0.7em,
)

// ---- callout -----------------------------------------------------------------
#let callout(title, body, accent: green, bg: greenP) = block(
  width: 100%, fill: bg, radius: 6pt, inset: (x: 12pt, y: 10pt),
  stroke: (left: 2.5pt + accent),
)[
  #set text(size: 9.6pt)
  #text(font: "Poppins", weight: 600, fill: accent.darken(15%), size: 9.8pt)[#title] \
  #body
]

// ============================================================================
//  COVER
// ============================================================================
#v(2.3cm)
#align(center)[
  #image("fig/jexter-mark.svg", width: 62pt)
  #v(0.3cm)
  #text(font: "Poppins", size: 12pt, fill: green, weight: 500)[Sugarcube]
  #v(0.08cm)
  #text(font: "Poppins", size: 52pt, weight: 700, fill: ink)[jexter]
  #v(0.1cm)
  #block(width: 13cm)[
    #set text(font: "Lora", size: 13pt, fill: soft, style: "italic")
    #set par(justify: false, leading: 0.6em)
    A faithful, structured document engine built on one normalized model — the Open Canonical Document.
  ]
]
#v(1.0cm)
#align(center, box(width: 15cm, image("fig/pipeline.svg", width: 100%)))
#v(1.1cm)
#align(center)[
  #set text(font: "Poppins", size: 9.5pt, fill: faint)
  PDF ↔ OCD ↔ EPUB · HTML · SVG · Markdown · DocTags · PDF #h(10pt)|#h(10pt) Apache PDFBox 3.x · JDK 21+
  #v(0.3cm)
  A technical overview — with a tour of the analysis pass
]
#v(1fr)
#align(center)[
  #set text(font: "Poppins", size: 8.5pt, fill: faint)
  © 2026 Sugarcube Information Technology Sàrl · dual-licensed AGPL-3.0 / commercial
]
#pagebreak()

// ============================================================================
= What jexter is

Built on Apache PDFBox, *jexter* opens a PDF and lifts it into a single in-memory
model called the *Open Canonical Document* (OCD), then writes that
model back out to PDF, fixed-layout EPUB3, reflowable HTML, vector SVG, Markdown, or
LLM-oriented DocTags. The same engine can also serialize the model itself to a self-contained
*OCD-EPUB* (`.ocd.epub`) — a valid fixed-layout EPUB that is also the lossless
model container — and re-open it later, unchanged.

The point of the design is the model in the middle. Rather than translating each
input directly to each output — an $N times M$ tangle of converters — jexter
normalizes *once* into OCD, and every exporter reads from that one representation.
Positions, fonts, colours, clipping and reading order are decided a single time and
survive intact into every projection. A round-trip stays faithful by construction:
what you see on the page is what reaches the EPUB or the HTML.

Beyond faithful presentation, jexter recovers a *logical structure layer*. Reading
order and headings come from pure geometry; the fuller role set — lists, figures and
captions, tables, code — comes from a tagged (PDF/UA) ground truth when one is present,
or from the optional LLM-refine layer. Crucially, PDFBox is both the
parser *and* the reference rasterizer, so the raster fallback and the fidelity check
agree by definition rather than by luck.

#callout("Two layers, kept apart")[
  A document carries a *presentation* layer (what paints) and a *logical* layer (what
  it means). They never mix: the logical tree references content by id and never moves
  a pixel. This separation is what lets the same source become both a pixel-faithful
  replica *and* a clean, reflow-friendly structure.
]

// ============================================================================
= The Open Canonical Document

At the centre sits a small, sealed model. Everything that paints is an `OCDNode` —
one of `OCDText`, `OCDPath`, `OCDImage`, `OCDGroup`, `OCDMedia`, or `OCDBreak`.
Container subtypes give it structure: `OCDParagraph` (a text block with line breaks),
`OCDLayerContent` (bound to an optional-content layer), and `OCDGraphic` (a clustered
vector drawing). Fonts live as `OCDFont` + `OCDGlyph` with em-normalized outlines;
media as `OCDVideo` / `OCDAudio`; the page and document tie it together.

#fig("fig/ocd-hub.svg")[The OCD model has three facets — presentation, logical, and
annotations — around one format-neutral core, governed by a few strict invariants.]

The invariants are what keep the model honest across so many projections. Page user
space is *Y-up*. Geometry is local plus a per-node transform — glyphs in em units,
images and media in the unit square, paths in page space. Colours are sRGB `int`
argb with alpha folded in. Bounding boxes are *derived*, never stored, so they cannot
drift out of sync. Identifiers are 1-based with `0` reserved as a sentinel. And the
logical `OCDStruct` tree only ever *references* content — it cannot alter what paints.

#callout("Native text without embedded fonts", accent: indigo, bg: indigoP)[
  The OCD-EPUB container is deliberately TrueType-free: every font lives once in a
  shared `pages/f.svg` — outlines, metrics and cmap in one SVG, the single font
  representation — and the pages reference glyphs from it. An embeddable
  `.ttf`/`.otf` is recompiled on demand — by `GlyfOtf` (glyf) or `CffOtf` (CFF) —
  when a PDF or EPUB is written, so outputs carry real, searchable text rather than
  traced shapes, while the model stays compact.
]

// ============================================================================
= From PDF to model

On the import path, the only PDFBox-coupled layer is `convert`. `PdfStreamEngine` walks each content
stream and emits primitive OCD nodes: glyph runs (with faces, sizes, colours, TJ
kerning, rotation and z-order), vector paths (fills, strokes,
dashes, caps/joins, béziers, opacity, blend modes, even-odd winding), images (inline
and XObject, including JBIG2), and deferred clip regions. `FontExtractor` rebuilds
each font; corrupt embedded TrueType `post` tables are repaired so the real glyphs
render instead of falling back to Arial. If the PDF is tagged, `TaggedStructureBuilder`
ingests the PDF/UA tree as logical ground truth.

#fig("fig/import.svg")[`PdfStreamEngine` walks the content stream and emits primitive
OCD nodes — text runs, vector paths, images, and the clip regions that bound them.]

What lands at the end of import is a faithful but *flat* OCD document: every mark is in
place and paints correctly, but it has no notion of paragraphs, headings, columns,
tables, or reading order yet. Recovering that meaning is the job of the analysis pass.

// ============================================================================
= The analysis pass

The analysis layer turns the flat, paint-ordered document into a structured one. It is
a fixed sequence of small, single-purpose passes, each gated by `ConvertOptions` and
each operating purely on the OCD model. The whole pass runs on *any* in-memory
document, whether it came from a PDF or was read back from an OCD-EPUB.

Two principles hold the layer together. It is *geometry-first*: the visual line is
reconstructed and frozen before any text or font signal is read, so detection is
OCR-robust and never leans on a vocabulary. And it keeps *one authority per concern* —
one pass owns lines, one owns spaces, one owns reading order, one owns running furniture,
one owns headings — so each rule lives in exactly one place.

#fig("fig/analysis-chain.svg")[The analysis pipeline, in order. Each pass is
render-neutral by construction: it sets roles, reorders the content into reading order, and
builds a structure tree by reference, and never touches geometry, z-order, glyphs, paths, or colours.]

The single most important property is in that caption: *nothing here moves a pixel.*
Every pass sets `OCDNode.role`, reorders the page `content` into reading order, or builds an
`OCDStruct` tree by reference. The renderer still paints by `z` — reading order *is* the content
array order, paint order is `z`, and the two are independent. So the sacred round-trip fidelity
bar cannot regress no matter how aggressive the structure heuristics become — a freedom
that lets the analysis be bold without risk.

A second design choice runs through the whole layer: there is *one* layout engine, `XYCut`.
`XYCut.segment` groups leaves into blocks; `XYCut.order` flattens them into reading order.
Segmentation and reading-order recovery share the same code, so tuning happens in a single place.

The other consequence is doctrinal: the pass *detects the obvious and defers the ambiguous.*
It commits only structure that pure geometry establishes with high confidence — a type style
that recurs, a column the whitespace cleanly cuts, furniture stable across the recto/verso
stack. Anything genuinely ambiguous (a bare section number, an unusual layout) or lexical
(figures, tables, captions, lists, code) is left to the optional LLM-refine layer or to a human
in the interactive editor, rather than guessed at. A wrong structure is worse than no structure.
A document therefore ends up carrying *two* structures side by side: the `heuristic` tree this
layer recovers, and the `pdf` tree — the document's own PDF/UA tag tree, when it has one.

== Cleaning to the ink base — `Cleaner`

Before anything is interpreted, the document is flattened to the simplest base every later
pass can share — a pure change of representation, fidelity-exact, that removes only
non-painting noise. `Cleaner` does four things. It keeps every *inked* run and its glyphs
intact — the geometric evidence — since the run boundary the PDF emitted is never trusted as a
word boundary here. It drops whitespace-only runs and strips every blank glyph from a run — a
real space glyph *and* any prior space sentinel alike — so runs become *pure ink*; the word
spaces are not trusted from the source but re-derived later from glyph geometry. It drops the
`OCDBreak` line markers (they paint nothing and are regenerated downstream). And it splices
away render-neutral paragraph and graphic wrappers, while keeping render-bearing groups (Form
XObjects, transparency groups) and recursing into them. It is *structure-preserving* — a
spliced wrapper's references are rewritten onto its content nodes through the single
`IdStamper` authority, so the PDF/UA ground truth survives untouched — and idempotent:
re-running on an already-clean document is a no-op.

#fig("fig/cleaning.svg")[Cleaning is a change of representation, not of pixels: render-neutral
paragraph/graphic wrappers are spliced, line-break tokens dropped and blank glyphs stripped,
leaving flat *pure-ink* runs (render-bearing groups are kept and recursed into). The image is
identical; the model is lighter.]

== Lines and spaces — `Liner` + `Spacer`

The geometry-first heart of the pass. `Liner` is the *line authority*, pure geometry: it bands
runs into lines by *vertical overlap alone* — a run joins a line when it overlaps it by at least
a third of the smaller band — with no size gate, so a raised or shrunk sub/superscript naturally
belongs to its line. A gutter wider than one em splits columns into proto-lines for the cut. No
font, no text, no lexicon is consulted, which is exactly what makes it OCR-robust.

The moment a clean line is rebuilt, `Spacer` — the *space authority* — freezes it *once* and it
is never touched again. The line's runs are ordered left-to-right (reading order is intrinsic to a
frozen line); word spaces are inferred by one uniform rule, where the advance slack past half the
font's space width is a space; and the line is sealed on a word boundary (`endLine`), leaving a
trailing hyphen painted so a split word can be rejoined at read time. Every space — intra-run,
inter-run, inter-line — is a render-neutral *sentinel* glyph (`gid −1`, unicode `" "`): outline-less,
so every render path skips it, yet extraction and structure read the `" "`. `OCDBreak` stays a
layout flag only, marking visual line boundaries for reflow; it is *never* consulted for spacing.

Extraction-quality text needs no analysis pass either: a presentation-form ligature keeps its
single outline (`gid`) *and* its source codepoint, so the page — and the OCD-EPUB — stay unchanged.
The folding happens only at *read-out*. `OCDIndex` applies NFKC as it assembles the text, so a
compatibility ligature (the single `U+FB03` glyph) reads as `ffi`; _“office”_ stored with one
`ffi` glyph extracts as searchable `office`, for the reflowable, searchable projections
(Markdown · DocTags · HTML · EPUB-reflow · search). SVG and PDF paint by glyph id and are
untouched, and the model keeps the source codepoint — so fidelity is never affected.

#fig("fig/canon.svg")[A ligature is one glyph in the font. Its outline (`gid`) and source
codepoint are kept, so the page and the OCD-EPUB are unchanged; only the read-out folds it with
NFKC, so the extracted text reads _Office_.]

== Physical recomposition — `Segmenter` + `Paragrapher`

With lines frozen and spaced, the page's runs are grouped into `OCDParagraph`s. `Paragrapher` is
a thin, render-neutral projection over `Segmenter`: `Segmenter` rebuilds each clean line with
`Liner`, has `Spacer.freeze` seal it, then `splitLeaf` groups the frozen lines into blocks by
*typography* — half-point size and whole-line weight — and by *interline* leading judged
adaptively against the region's own median baseline step, so loosely- and tightly-set paragraphs
both segment correctly. `Paragrapher` then wraps each block's nodes — in their original paint
order — into one transparent `OCDParagraph` and inserts an `OCDBreak` at each visual-line change.
Nodes are never reordered, so the page paints byte-identically.

`Segmenter` deliberately *over-segments*: a marker or enumerator line always opens its own block,
so a heading is never glued to the next heading or to its body. Over-segmentation is the safe bias
because every line was already sealed on a word boundary by `Spacer.endLine`, so the text stays
correct across the cut. A light dust-absorption step folds a detached accent or an isolated stray
mark into the block that contains it, leaving drop caps and list markers intact.

#fig("fig/recompose.svg")[Independent show-text runs are grouped into paragraph blocks
with line-break tokens. A heading or marker line always opens its own block, so it is
never glued to its body.]

== Reading order — XY-Cut++

Plain recursive XY-cut (Nagy & Seth, 1984) splits a region along its widest empty
whitespace band and recurses. It fails on the *L-shape problem*: a full-width element —
a banner, a spanning figure — crosses the column gutter and erases the valley, so the
columns collapse into one mis-ordered run.

#fig("fig/xycut.svg")[The L-shape problem and its fix. A spanning banner erases the
gutter, so plain XY-cut reads across the columns; XY-Cut++ masks the banner out before
the cut and re-inserts it by vertical position.]

XY-Cut++ (Liu et al., _Advanced Layout Ordering via Hierarchical Mask Mechanism_,
arXiv:2504.10258, 2025) fixes this with three moves. *Cross-layout masking* pulls out elements wider
than $beta dot.op "median"$ that overlap two or more others, then re-inserts them by
vertical position, so they never block a gutter. *Relative-depth valleys* score a cut
by its depth against the surrounding peaks (with a 5% noise floor), not by raw width,
so a near-empty corridor still cuts and page margins never do. *Adaptive thresholds*
derive the minimum gap per axis from the document's own median block dimensions. The cut runs
on *text only* — graphics are clustered apart and placed by overlap — because folding a page's
drawings into the same cut bridges the whitespace valleys and collapses the leaves. Pure
geometry, deterministic, no machine learning.

== Headings — `FontProfile` + `StructureBuilder`

Headings are *discovered, not matched against a vocabulary.* `FontProfile` reads the whole
document once and builds a typographic alphabet: per type-style signature — half-point size plus
whole-line bold — it accumulates the total ink, the number of pages the style appears on, and its
occurrence count, excluding running furniture. The *body* is the signature with the most ink. The
canonical rule for a heading is a single line:

#callout("The heading rule")[
  A style is a heading only if it is *elevated* — larger than the body, or bold at body size —
  *and it recurs*, appearing on at least two pages or at least three times.
]

Recurrence is the load-bearing discriminant. Without it a one-off large decoration or a figure's
internal label would masquerade as a heading; with it, only real heading styles survive. The
elevated, recurring styles are ranked by size into H1…HN, and the single largest elevated style
above everything is the title. There is *no lexicon and no enumerator regex* — a bare `§ 4`, a lone
`1`, an alinéa number are all genuinely ambiguous, so they are left as plain content for a human in
the interactive editor rather than guessed at. Running heads and feet are excluded through the one
authority for that, `Furniture` (via `Furniture.isRunning`).

== Vector clustering — `GraphicClusterer`

A drawing — a logo, an icon, a chart body — is several vector paths that form one visual
unit, but the PDF gives it no marker, so the clustering is heuristic and prone to
swallowing page furniture.

#fig("fig/graphicizer.svg")[Only a contiguous run of paths in paint order is wrapped
into an `OCDGraphic`, so painting is never reordered. Full-page backgrounds, frames,
uniform grids and lone paths are filtered out as furniture.]

The guards follow the battle-tested pymupdf4llm recipe plus a paint-order contiguity
rule of jexter's own: a path spanning almost a whole page dimension is furniture; a run
whose paths all share one width or height is a grid or set of rules; at least one path
must genuinely occupy the cluster's interior; and only a contiguous run is wrapped, so
the fidelity bar is preserved exactly. A single lone path is never a graphic. Within a
contiguous run, paths group by *scale-adaptive* proximity — a small floor plus a fraction
of the smaller path's own size — so a large, spread-out schematic coalesces into one
graphic while neighbouring drawings never chain, where a fixed tolerance would do neither.

A drawing exists at two levels, and the heuristic pass stops at the lower one. `OCDGraphic`
is the *presentation* entity — the visual drawing on the page — and it is always produced here.
Promoting a graphic to a logical `FIGURE` is reserved for captioned or obviously significant
content and is *deferred to the `Refiner`*, so the geometric pass never over-promotes a header
logo or a callout banner into a figure.

== Running heads & feet — recto/verso stack stability

A running head or foot *is* a line that stays the same from one page to the *next page of the
same side*. Odd and even pages are two separate stacks — recto and verso: a recto header reappears
on the next recto, a verso header on the next verso, whether or not the two sides carry the same
words. `Furniture` reads exactly that — one idea, no lexicon, no global threshold to tune beyond
the page edge itself.

#fig("fig/headerfooter.svg", w: 90%)[For a candidate edge line on page _p_, its whole raw string —
upper-cased, whitespace collapsed, digits kept — is compared to the same edge position on its
same-side neighbours _p−2_ and _p+2_ by a normalised Levenshtein similarity. A line is furniture when
the mean clears `SIM_MIN`; an inconclusive same-side stack falls back to the immediate _p±1_ pair.]

The two stacks are what make it clean. Recto and verso never cancel, so a one-sided running head is
still caught, and a title page falls out for free: a page-1 title or bare law number differs from the
running header on page 3, so its single neighbour pair is unstable and it is never tagged. Stability
is *local* — judged per page against its own neighbours — so a head that changes at a section break is
followed, not lost. Text re-enters only at the end, to *name* what the stack found: a page-number
shape, a header, a footer. `Furniture.isRunning` is then the single authority every later stage
consults to keep furniture out of the document body.

== Deferred enrichment & language — `Refiner`

The geometric pass commits only what pure geometry establishes — lines, spaces, segmentation and
reading order, running furniture, headings. Everything that needs a texture opinion or a lexical
pattern — figures, tables, captions, lists, code — is *deferred*. An *optional* LLM pass (`Refiner`,
off by default) recovers it downstream: it is fed pre-computed perceptual signals — spacing,
indentation, emphasis, colour — rather than page images, runs page-windowed to dodge
lost-in-the-middle degradation, and is grounded so that a failure is a no-op rather than a
corruption. Storing manual corrections and re-injecting them as few-shot exemplars is a planned extension. Separately,
`LanguageDetector` runs at export time — not as a pipeline stage — voting with stopword frequencies
over a sample and tagging the language only when a clear winner clears a margin over the runner-up.

#fig("fig/llm.svg")[The optional LLM pass works one page at a time, in a sliding window:
a static document profile, the neighbouring pages condensed, and the current page in
full detail placed last — so it never gets lost in a long context.]

// ============================================================================
= Projections out

Every writer shares one contract and reads the same model, so the outputs stay mutually
consistent:

#block(above: 0.8em, below: 1em)[
  #set text(size: 9.6pt)
  #table(
    columns: (auto, 1fr),
    stroke: none,
    inset: (x: 8pt, y: 6pt),
    fill: (_, row) => if row == 0 { greenP } else if calc.odd(row) { rgb("#f6f7f4") } else { white },
    table.header(
      [#text(font: "Poppins", weight: 600, fill: greenD)[Target]],
      [#text(font: "Poppins", weight: 600, fill: greenD)[Best for]],
    ),
    [EPUB (fixed-layout)], [A page-faithful, epubcheck-validated replica for any e-reader.],
    [HTML (reflowable)], [Semantic HTML5 — headings, lists, tables, figures, media — for the web and accessibility.],
    [SVG], [Resolution-independent vector pages; text stays selectable text.],
    [Markdown], [Lightweight structured text for notes, wikis and pipelines.],
    [DocTags], [A compact tagged form aimed at LLM / RAG ingestion.],
    [PDF (normalized)], [A clean PDF with repaired fonts and a generated outline from detected headings.],
    [OCD-EPUB (`.ocd.epub`)], [The model itself as a valid fixed-layout EPUB — self-contained SVG-OCD pages (text, reading order, lines, links as data), fonts shared in `pages/f.svg`; re-openable, round-trippable, and Prism's native format.],
  )
]

Reflowable HTML and EPUB are generated from the *structure tree*; the fixed-layout EPUB
and SVG are facsimiles of the *presentation* layer. Because both come from one model,
they never disagree about what the document says.

// ============================================================================
= The conversion API — `sugarcloud.ch`

Every projection above is also reachable over HTTP. `sugarcloud.ch` is a stateless front
for the *same* jexter engine: a client sends a PDF (or an `.ocd.epub`) together with a
per-client API key and gets the converted artifact straight back. It is meant for
server-to-server use — each consumer holds its own key and never exposes it to a browser.

#fig("fig/api.svg")[One authenticated `POST` carries the source and a chosen target; the
same engine that produced every projection in this overview answers with the artifact,
its media type and a filename.]

Two endpoints matter to a client. `GET /api/health` is a public probe that returns `ok`.
`POST /api/convert?to=<target>` does the work, where `to` is one of the projection ids —
`ocd`, `svg`, `pdf`, `epub`, `epub-reflow`, `html`, `md`, `doctags` (it defaults to
`svg`). The key travels as `Authorization: Bearer <key>` or `X-API-Key: <key>`; a missing
or invalid key is a `401`. Keys are stored hashed and hot-reloaded, so granting or
revoking a client never needs a restart.

The source is a raw `application/pdf` body or a multipart `file` field; an `.ocd.epub` body is
re-exported without re-parsing. A few writer options ride along as query parameters —
`page` (SVG, 0-based), `selectable` (PDF), `renderAnnotations` (SVG/EPUB), `grid` (DocTags) —
and any other key passes straight through to jexter's `ConvertOptions`. The response
carries jexter's own media type and `Content-Disposition` filename.

```
# health — public, no key
curl https://sugarcloud.ch/api/health                       # -> ok

# PDF -> first page as SVG
curl -H "Authorization: Bearer <key>" \
     -F file=@doc.pdf \
     "https://sugarcloud.ch/api/convert?to=svg&page=0" -o page-0.svg

# PDF -> normalized, selectable PDF (raw body)
curl -H "Authorization: Bearer <key>" -H "Content-Type: application/pdf" \
     --data-binary @doc.pdf \
     "https://sugarcloud.ch/api/convert?to=pdf&selectable=true" -o out.pdf
```

= Fidelity, by construction

The round-trip bar is sacred and measured, not assumed. Two facts make it hold. First,
PDFBox is both the parser and the reference rasterizer, so jexter's own renderer
(`OCDRenderer`) can be pixel-compared against the authority that produced the input.
Second, the entire analysis layer is additive: it annotates and references, never
repaints. The result is an engine that can afford ambitious structure recovery precisely
because none of it can ever damage the faithful image underneath.

#figpin("fig/fidelity.svg")[The same source is rasterized twice — once by PDFBox, once by
jexter's own renderer from the OCD model — and compared pixel for pixel.]

#v(1.2em)
#line(length: 100%, stroke: 0.5pt + hair)
#v(0.5em)
#block[
  #set text(size: 8.6pt, fill: faint, font: "Poppins")
  #grid(columns: (1fr, auto), align: (left, right),
    [Sugarcube jexter — built on Apache PDFBox 3.x & FontBox (Apache-2.0). \
     This overview was typeset with Typst; all figures are hand-authored SVG.],
    [© 2026 Sugarcube IT Sàrl],
  )
]
