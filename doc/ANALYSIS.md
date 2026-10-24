# ANALYSIS — the geometry-first structure recovery pass

## One-page orientation
The analysis layer turns the flat nodes read out of a PDF (text runs, glyphs, paths, images) into a
structured, projectable document — **by pure geometry**, render-orthogonal (it sets node roles, reading
order and an `OCDStructNode` tree *by reference*; the renderer still paints by `z`, so `Fid2 = 0.0`).

## Geometry first, then spaces — one authority per concern

The spine is **geometry-first**: the *line* is reconstructed and frozen before any text signal is read,
and each concern has a single owner.

1. **`Cleaner`** — flatten the document to its simplest base: dissolve prior paragraphs, drop every blank
   and `OCDBreak`, return pure-ink runs. Idempotent (re-analysis re-derives everything).
2. **`Liner`** — the **line authority**, pure geometry. Bands runs by **vertical overlap alone** (≥ 1/3 of
   the smaller band), so a raised/shrunk **sub/superscript naturally belongs to its line** — no size gate,
   no lexicon, OCR-robust. `lines(·, true)` cuts columns at a gap over ½ em (proto-lines for XY-Cut);
   `lines(·, false)` keeps the whole band (clean final lines inside a leaf).
3. **`XYCut`** (XY-Cut++) — one engine: `segment` (grouped leaves) for blocks, `order` (flattened) for
   reading order. Cross-layout masking, relative-depth valleys, adaptive thresholds.
4. **`Spacer`** — the **space authority**. The moment a clean line is rebuilt, it is **frozen once**: runs
   ordered left→right (reading order is intrinsic to a frozen line), word spaces inferred, the line sealed
   on a word boundary. Every space — intra-glyph, inter-run, inter-line, inter-block same-line — is a
   render-neutral **sentinel** (gid −1, `" "`). One uniform rule per seam: the advance slack `step/size −
   advance` is gated on the line's own slack distribution (valley, floored at the style) is a space. `endLine` seals the boundary unless the
   line ends in a hyphen (left painted for read-out).
5. **`Segmenter`** — re-segments the **frozen lines** into blocks by typography (**size + weight**) and by
   **interline** leading; it never touches the lines. Markers/enumerators open their own block
   (over-segmentation is the safe bias — `endLine` keeps the text correct across the cut).
6. **`Paragrapher`** — wraps blocks into `OCDParagraph` and inserts the `OCDBreak` line markers.
   `OCDBreak` is a **layout flag only**, never a spacing mechanism.
7. **`Furniture`** — running heads/feet by recto/verso stack stability (whole-line Levenshtein on
   same-side neighbours) + page-edge contiguity. `isRunning()` is the single authority.
8. **`FontProfile` / `StructureBuilder`** — headings by document-wide typographic recurrence (a style
   elevated *and* recurring becomes a heading; everything else is a paragraph). No lexicon, no enumerator
   regex.

At read-out, **`OCDIndex`** is a plain concatenation of run text (every space already lives in the runs);
the only transform is the **soft-hyphen rejoin** (the line-break hyphen stays painted, so it can only be
resolved at read time).

**Figures, tables, captions, lists and code are deferred to the optional `Refiner` layer** — the
geometric pass no longer guesses them. Enumerator-bold heading motifs (bold lead, long regular title)
are likewise deferred to a future custom-rules re-pass; the whole-line-bold guard stays. Ambiguous numbering and unusual layouts are left to the human in
the interactive structure editor.

## Three sources of structure, one vocabulary

A document can carry several `OCDStructure`s side by side, and they coexist rather than compete —
a consumer, or Prism, chooses which to trust:

| Id | Source | Built by |
|---|---|---|
| `pdf` | the native PDF/UA tag tree, when the PDF is tagged | `TaggedStructureBuilder`, at import |
| `outline` | the PDF bookmark tree | `BookmarkStructureBuilder` |
| `heuristic` | pure geometry — this layer | `StructureBuilder` |

`Analysis.buildOutline()` picks: a tagged PDF keeps its own tree; otherwise bookmarks are resolved
immediately; otherwise the heuristic is the fallback. The heuristic can also be forced in parallel.
Bookmarks whose titles match no text at all (a layout program's file names, one per spread) are
navigation only: they build no `outline` structure, and the heuristic takes over — a block reached by
its position alone is a guess with nothing under it. A title with no word (« 4 », « 1-19 ») is never
matched as text. Likewise a tag tree that marks no content (a skeleton written with tagging off) builds
no `pdf` structure.

**The navigation is ground truth, not a lexicon.** A bookmark title is a string *quoted from the page
itself*, so wherever the geometric segmentation disagrees with it, the navigation wins:
`OutlineAligner` merges a title split across blocks, splits a title fused with its following body, and
tags the resulting paragraph `heading-N` (N = the bookmark's tree depth). It runs line-grained and
render-neutral, and *before* ids are minted. This is self-reference — the matched strings come from the
document — so it does not break the no-lexicon rule.

`BookmarkStructureBuilder` supplies what a bookmark lacks: the link to the *content*. A bookmark has a
title, a depth and a destination, but not which runs on the page **are** that heading; the pass anchors
each bookmark to the page block that best matches it, which is what makes the tree projectable to
HTML/EPUB. The bookmarks give the skeleton only, and the writers project the structure tree as their
sole content source — so the pass then attaches every remaining text block in reading order as a
PARAGRAPH under the heading that precedes it, through the same wrapping authority as the heuristic
pass (`StructureBuilder.addParagraph`).

**`HeadingRoles` is the single projector.** Whatever the source, every paragraph referenced by a
`HEADING` struct gets `data-role="heading-N"` on the page. One vocabulary in all three worlds, so a
consumer that only reads pages — overlays, TTS, exports — never needs to know where the headings came
from.

## Verification

Every change is held to the functional gates (`node nimbus-jexter.cjs --run=test`, `test/Gates.java`):
the ones that speak for this layer are **PIXELS** (the analysis never repaints: the model still renders
what PDFBox renders), **AGAIN** (a second `Analysis.run` writes the same container), **DOCTAGS**
(`pdf→doctags == pdf→ocd→doctags`), **NAV**, **TAGS**, **FURNITURE** and **STRADDLE**. Beyond them,
compare structure counts (`HEADING / PARAGRAPH`) against a baseline build on a real corpus. Measure
before committing; revert on the evidence.

---
How a flat PDF page becomes a structured, projectable document — stage by stage.

This document describes the `sugarcube.jexter.ocd.analysis` layer: the pass that takes the
primitive nodes read out of a PDF (text runs, glyphs, vector paths, images) and recovers the
document's text structure (headings and paragraphs) and its reading order — by pure geometry, with no
machine learning and no hard-coded vocabulary. Richer logical structure (figures, tables, captions,
lists, code) is deferred to an optional downstream LLM-refine layer.


## Two ideas hold the whole thing together

**1. Render-neutrality is the contract.** Every analysis step changes only *structure* —
node roles, references, grouping — and never a single painted pixel. The proof is mechanical: render
the page with analysis off and with analysis on, diff the two rasters, and the mean per-channel
difference must be exactly `0.0000`. Structure is layered *over* the page; it never edits the page.
This is what lets the conversion be lossless and the structure be a free, detachable annotation.

**2. Detect the obvious; defer the ambiguous.** The analysis only asserts structure it can establish
by high-confidence geometry — a heading whose type style *recurs* across the document, a column the
whitespace cleanly *cuts*, running furniture that is *stable* across the recto/verso stack. Anything
genuinely ambiguous (a bare section number, an unusual layout) or lexical (figures, tables, captions,
lists) is left to the LLM-refine layer or to a human in the interactive structure editor, rather than
guessed at. Fewer, correct facts beat many fragile ones. A wrong structure is worse than no structure.

A consequence worth stating up front: the document ends up carrying **two structures**. The
`heuristic` structure is what this analysis layer recovers. The `pdf` structure is the document's
own native PDF/UA tag tree, when it has one. They coexist; a consumer (or Prism) chooses which to
trust.


## The pipeline, in order

The live sequence is orchestrated in `ocd/analysis/Analysis.java`. Each stage is gated by a
`ConvertOptions` flag and is a no-op when disabled.

```
Cleaner.clean                          normalize to the shared ink base; mints leaf ids
  → Paragrapher.recompose              Segmenter ─ Liner ─ Spacer.freeze ─ splitLeaf ─ OCDBreak
  → Furniture.detect          [HEADERS] running heads/feet → roles, before the font profile
  → GraphicClusterer.cluster [GRAPHICS] paths → OCDGraphic, before structure (a FIGURE may wrap one)
  → OutlineAligner.align    [STRUCTURE] nav is ground truth: re-cut/merge title blocks, before ids
  → IdStamper.fill                     mint the wrapper ids created above
  → buildOutline()          [STRUCTURE] BookmarkStructureBuilder | StructureBuilder (see below)
  → Figures.place           [STRUCTURE] the body's pictures → FIGUREs, in reading order
  → HeadingRoles.project    [STRUCTURE] best structure → page-level heading roles
  → Refiner.refine               [LLM]  optional, grounded, no-op on failure
```

**`ocd/analysis/Analysis.java` is the authority on this order** — it carries the sequence
*and* the reason for each position as an end-of-line comment. When this document and
that file disagree, the file is right; fix the document.

The physical layer is **line-first**: `Paragrapher` drives `Segmenter`, which reconstructs each clean
line with `Liner` (overlap-only banding) and has **`Spacer` freeze it on the spot** — reading order +
word spaces, sealed once — *before* the frozen lines are grouped into blocks (`splitLeaf`) and wrapped
into paragraphs with `OCDBreak` markers. `Furniture` then runs on the reconstructed, spaced lines.

`LanguageDetector` runs once, last in `Analysis.run`, as a language fallback (never at export).

It groups into four phases:

| Phase | Stages | Role |
|---|---|---|
| Normalize | Cleaner | clean to a shared ink base; Cleaner mints deterministic leaf ids |
| Physical layer | Paragrapher (Segmenter + Liner + Spacer), Furniture, GraphicClusterer | reconstruct + freeze lines, segment paragraphs, running furniture, graphics |
| Logical structure | OutlineAligner, IdStamper(fill), structure builders, Figures, HeadingRoles | align on the nav, mint wrapper ids, label text + titles, project heading roles |
| Enrich | Refiner | optional LLM refinement |


### 1. Cleaner — normalize to the shared base

The one normalization every later pass starts from, so detection reads the same input whatever the
source (a freshly imported PDF, an OCD-EPUB read back, a re-analysis). It is fidelity-exact — it only
removes content that reaches no pixel and unwraps render-neutral wrappers, in **one flattening pass**:

- **dissolve prior paragraphs** — splice render-neutral wrappers (identity `OCDParagraph` /
  `OCDGraphic`; render-bearing `OCDGroup`s are kept and recursed into), flattening their runs up;
- **drop `OCDBreak`s** (line markers paint nothing; restructuring regenerates them);
- **strip every blank glyph** from each run — a real space glyph *and* any prior space sentinel both
  paint nothing — so runs become **pure ink**; a run emptied by stripping is dropped. **Blank is
  decided by the FONT, not by the text**: `toUnicode` is the producer's claim about meaning, and a
  wrong one erases ink. Press subsets exist whose only mapping for a capital is U+0020; trusting it
  deleted that letter from every word, advance and all. A glyph goes only when its outline is
  absent or empty. The word spaces
  are not trusted from the source: they are re-derived later from glyph geometry by **`Spacer`** and
  materialised as sentinels. Re-analysis dissolves and strips inside former paragraphs too, so prior
  sentinels never accumulate;
- **keep inked runs, groups, paths and images intact**, reassign paint order, and rewrite structure
  refs so a spliced wrapper's references follow to its content nodes.

It is idempotent (re-running is a no-op) and structure-preserving — the native `pdf` (PDF/UA) tag tree
survives untouched. `clean` runs first in `Analysis.run`, so a re-analysis is simply `run` again
(there is no separate re-analysis entry point; `restructureText` / `restructureHierarchy` are the two
PARTIAL re-runs Prism offers, each a documented subset of `run` — `restructureText` re-cuts on the nav
and re-projects the heading roles, which ride the paragraphs it replaces). The `AGAIN` gate holds all of
it: a second `run` writes the same container, the `stages` export edits nothing, `restructureText`
keeps every heading role.

*(Ligature / NFKC normalization is not a pipeline pass; it is handled at the glyph/model and writer
level so the painted glyph shapes are never altered.)*

### 2. IdStamper — the single id authority

Mints deterministic ids for nodes. Every structure reference points at a node by id, so this is the
one place ids come from, through one idempotent call, `fill`: `Cleaner` calls it to id the leaves, and
the pipeline calls it again after the physical layer to id the synthetic nodes — paragraphs and
graphics — that the structure will reference, **without ever disturbing an id that is already
referenced** (each per-kind sequence continues from the highest id present).

### 3. Furniture — running furniture by recto/verso stack stability

Detects running heads, feet and page numbers and tags those runs with a role
(`page-header` / `page-footer`). The method is **stack stability at the page edge**, one idea, no
lexicon, no global threshold:

- odd and even pages are **two stacks** (recto / verso): a running line reappears on the *next page of
  the same side*, whether or not the two sides carry the same text;
- a candidate edge line is furniture when its **whole raw string** (upper-cased, whitespace-collapsed,
  digits kept) is on average **similar ≥ 0.60** to the same edge-position line on its same-side
  neighbours `p±2`, by normalised Levenshtein (`1 − dist/maxLen`; a missing line counts as 0). If the
  same-side stack is inconclusive (a chapter whose single recto/verso page is flanked by two others),
  it falls back to the immediate `p±1` neighbours;
- **page-extremity stays the orthogonal filter**: recurring body (a boilerplate legal footnote repeated
  verbatim) is just as stable as the folio, so stability alone cannot reject it — but it is not at the
  very edge. The header is the contiguous run descending from the top, the footer the run rising from
  the bottom, accumulated inward while block-contiguous (gap ≤ 2.0× the line height) **and** stable.

This is geometric and corpus-agnostic — no `"page \d+ of \d+"` regex. A title page falls out for free
(its single neighbour pair is unstable), and the folio→footnote gap cuts the footer before it reaches
the footnote block. `Furniture.isRunning(node)` is the single authority every later stage
consults to exclude running furniture from the document body.

### 4. Paragrapher — the physical layer (runs → paragraphs)

Groups a page's flat text runs into `OCDParagraph`s — visual lines separated by `OCDBreak` tokens.
It is a thin, render-neutral projection over the shared segmenter in `Segmenter`: it walks the
content tree (recursing into graphical groups so a paragraph never crosses a group boundary),
isolates each maximal span of sibling text runs, asks `Segmenter.segment` for that span's blocks,
and wraps each block's nodes — *in their original paint order* — into one transparent `OCDParagraph`,
inserting an `OCDBreak` at each visual-line change. Because blocks are disjoint regions and nodes are
never reordered, the page paints byte-identically.

The lines are already **frozen and spaced** by the time blocks form: `Segmenter` rebuilds each clean
line with `Liner` and has `Spacer.freeze` seal it (reading order + word-space sentinels) before
`splitLeaf` groups the lines — so `Paragrapher` only wraps and marks breaks, never touches a line.
`OCDBreak` is a layout flag; it carries no spacing. The real work is in `Segmenter` — see *The core*.

### 5. GraphicClusterer — vector paths → graphics

Grows `OCDGraphic` entities from vector paths by single-linkage, accreting nearby paths and contained
text/images by bounding-box containment and contiguous paint order. It runs **before** structure so a
later `FIGURE` can wrap a finished graphic. A column of copy (`Figures.isCopy`: body text of four lines
or more) is never engulfed — it ends the run: a lead set in a mosaic of tiles left every text target.

Crucially, graphics are clustered **separately** from the text cut — not fed into the same XY-Cut.
This is a measured architectural fact, not a convenience (see *Why graphics are handled apart*).

### 6. StructureBuilder — the logical layer (text + titles)

Reads the paragraphs back (via `Segmenter.fromParagraph`, so it trusts the segmenter rather than
re-segmenting), takes the blocks in the content's order (the reading order `Paragrapher` set), and labels
each into an `OCDStructNode` tree.

**Scope: text + titles only.** This pass emits exactly two element types — **HEADING** and
**PARAGRAPH** — under a `DOCUMENT` root. Figures are placed next, by geometry (`Figures`, 6b); tables,
captions, lists and code are *deferred to the downstream LLM-refine layer*; the heuristic pass commits
only what pure typography and geometry can establish.

- **Headings are typographic, not lexical.** A block is a heading only when `FontProfile` — the
  document-wide style alphabet — ranks its signature (half-point size + whole-block bold) as an
  *elevated, recurring* style, and the block is short (≤ 2 lines, ≤ 160 chars: a heading is a label,
  not a flowing paragraph). No local size-ratio guess, no enumerator regex.
- Everything else is a **PARAGRAPH** — except a block that carries on the paragraph a page or a column
  break cut (`StructureBuilder.Paragraphs`), which joins it. Geometry decides: the paragraph's last line
  is full by its own measure (the shortest line it wrapped, never under ¾ of the column: ragged text
  wraps short), it wrapped at least once, and the block's first line is not indented; the block is the
  first text of the next page, in the same column or one as wide, or opens the next column of the same
  page (to the right, less than a column away, starting higher, in the same size).

Recurrence is the load-bearing discriminant: a one-off large decoration or a figure's internal label
never becomes a heading because its style does not recur.

### 6b. Figures — the body's pictures

A structure built from text alone drops every picture: the reflowable EPUB of a paper lost all its
figures. `Figures.place` gives each picture the body shows a FIGURE in the `outline` / `heuristic`
structure (never in a tagged PDF's `pdf` tree: an untagged picture there is an artifact by PDF/UA),
at the place of the first thing it holds in reading order. Pure geometry, no lexicon:

- **What shows**: a picture or graphic with ink (a white shape is paper, `OCDNode.ink`; a stroke
  reaches half its pen), at least 24 pt on a side, not running furniture, not repeated on most pages,
  not a page-sized background under text — and not the frame of a text: a shape that holds body copy,
  or a heading and nothing else (a banner), is that text's frame.
- **What is one drawing**: pieces grow together while their ink is within 12 pt *and* the box they
  would make holds no body text — the XY-Cut idea run bottom-up: a figure is a zone free of body copy.
  Loose paths bind pieces (an arrow, an axis); a short label touching two pieces binds them, touching
  one it may be a caption and stays text; last, pieces whose joint zone holds no body text at all are
  one drawing (a figure on a page of its own, a chart and its legend).
- **Body or label**: text of 100 characters or more is body copy — unless at least half of what its
  lines span lies on the drawing (code set on coloured chips is a label). A column of copy (four lines or
  more) stays body whatever lies under it — a magazine sets its articles over photographs — and text set
  larger than the document's body is a title, never a label.
- Labels within the drawing (its ink, or overlapping it) join the FIGURE; a paragraph wholly inside is
  replaced by it. The writers draw a FIGURE of several nodes as the page paints that region
  (`OCDRenderer.renderRegion`: the shapes and pictures there, its own labels, no other text).

The `FIGURES` gate holds it: every body picture is in a FIGURE, every `<figure>` of the reflow book
holds its `<img>`.

### 7. Reading order — `XYCut.order`

Reading order is **not a separate class**; it is `XYCut.order` — the same XY-Cut++ engine the
segmenter uses — applied ONCE: `Paragrapher` orders the page `content` array (so the array order *is*
the reading order), and `StructureBuilder` reads its blocks in that order. A second cut over the
structure blocks alone was blind to the photos that screen a row cut, and read a column running on past
one out of order. Running furniture
is excluded via `Furniture.isRunning`: a block made of it alone never enters a logical tree (the
`FURNITURE` gate). Paint order is carried separately by each node's `z`. The read-out (`OCDIndex`)
follows the content's order too — never the ids, which a restructure leaves behind.

### 8. Refiner — optional logical refinement

An optional, grounded LLM pass (page-windowed, with a document profile and ordered-triplet context,
cancellable). It refines the logical structure — and is where the deferred figures / tables / captions
/ lists / code will be recovered. It is a no-op when no model is bound and never touches painted
content. `BlockSignals` harvests the signals it reads (see below).

Page-windowing has one cost, and one pass pays it: structured in isolation, a page can only guess
heading depth **locally** — a level 1 on page 3 need not mean what a level 1 on page 47 means.
`StructureReconciler` owns that one decision a per-page model cannot make, re-deriving a coherent
global hierarchy deterministically. It is a normalization, not a second opinion.

### Last — LanguageDetector

`LanguageDetector` runs once, at the end of `Analysis.run`, to fill the document language when the
source declared none (never overriding an explicit `/Lang`); the writers only read it. Script first (kana → ja,
hangul → ko, han → zh, …), then Latin-script stopword scoring with a frequency floor and a margin gate;
it abstains when unsure.


## The core: atoms → structure

The heart of the analysis is the text path inside `Segmenter`, plus the document-wide heading
labeller. The data flows through four states:

```
text runs + glyphs (pure ink)
   │  Liner            — cluster into baseline lines by vertical overlap alone (sub/superscripts join)
baseline lines
   │  XY-Cut++         — cut into geometric leaves (relative-depth valleys, L-shape masking)
geometric leaves       (already in reading order)
   │  Liner(clean) + Spacer.freeze   — rebuild each clean line, freeze it once (reading order + word spaces)
frozen lines
   │  splitLeaf        — segment by size / weight / enumerator / interline (never font name)
style blocks
   │  FontProfile      — label by document-wide typographic recurrence
OCDStructNode  (heading · paragraph)
```

### Liner — lines without metadata

`Liner.lines` groups runs into baseline lines by **vertical overlap alone**: two runs share a line when
their vertical bands overlap by at least **1/3 of the smaller band**. Overlap *is* the discriminant —
same-line runs overlap strongly, tight-leading neighbours only ≈0.1–0.3 — so a raised or shrunk
**sub/superscript naturally joins its line** with no special case and no size gate (an earlier size
guard orphaned superscripts: a raised, small run seeded its own band and the larger body run was then
rejected — removed). It anchors on geometry, not on font metrics or unicode, so it works identically on
born-digital PDF and on OCR output. A line's band is the band of its **tallest run**, never the union of
its runs: a union grows with each run it takes, and two columns set on offset baselines then chain — each
line overlaps the half-line beside it, the band slides down a step at a time, and a whole paragraph of one
column becomes one "line" with the other column's lines in it (measured on a two-column paper: its abstract
read as one line, with no word space left). `Liner` carries **no text and no spaces** — a line is `(yc, runs)`;
the word spaces are `Spacer`'s job (below).

The `splitColumns` flag is the only variation: `true` cuts a band at any gap over half an em of the smaller
run (proto-lines, so a margin number / second column stands alone for XY-Cut — a newspaper sets its
columns about one em apart, and a cut at a wide word space costs nothing, XY-Cut only cuts where every
line leaves the gap); `false` keeps the whole band (the clean final
lines inside a confirmed leaf, where an enumerator parted by an indent rejoins its text).

### XY-Cut++ — the single cutter

`XYCut` is the project's one layout engine, used for **both** segmentation (`XYCut.segment`, returning
the tree's leaves) and reading order (`XYCut.order`, flattening the same tree). It is XY-Cut++
(after Nagy & Seth 1984; Liu et al., *Advanced Layout Ordering via Hierarchical Mask Mechanism*,
arXiv:2504.10258, 2025), which fixes plain recursive XY-cut on three points:

- **Cross-layout masking** — an element wider than `β·medianWidth` overlapping ≥2 others is pulled
  out before the cut and re-inserted by vertical position, so a spanning banner or figure never
  erases a column gutter (the *L-shape problem*). A region no projection can cut — a title set tight
  over a two-column list, which covers the gutter and leaves no row valley under it — is cut once its
  wide items, standing wholly above (or below) the rest, are set aside and their absence opens a gutter:
  they are read first (last), then each column. And in `Paragrapher` only text cuts the page
  (`order(items, bounds, cuts)`): a photo or a box spanning two columns rides by its vertical position
  instead of filling the gutter, which sorted the whole page by `y`.
- **Relative-depth valleys** — cuts run on projection profiles and a valley is scored by its depth
  relative to the surrounding peaks (with a noise floor), so a near-empty corridor still cuts and a
  page margin never does.
- **Adaptive thresholds** — the minimum gap per axis derives from the page's own items, from the TEXT's
  line (the low decile of item heights): a horizontal cut at 0.8 of it (or of the median item height, the
  smaller), a vertical one at half of it,
  the way poppler and pdftotext++ bound a gutter by the font size — not from the block width, which made a
  newspaper or two-column gutter (11–22 pt) narrower than any cut it allowed. A gutter that narrow cuts
  only with a column on each side, taller than two lines: a label and its value apart on one line stay
  together. The projection weighs each item by its ink extent, not by count, so a column set as one tall
  paragraph is as deep as thirteen stacked ones.

Pure geometry, deterministic, no ML.

### splitLeaf — segment the frozen lines into blocks

A geometric leaf is a tight unit (the whitespace has already done its work), so the only splits left
are typographic and vertical. Within a leaf, a new block opens on a **size jump** (≥ 1.20×), a **weight
(bold) change**, an **enumerator lead** (a >1 em head-gap after a leading token — `a.`, `b.`, `Art. N`)
or a **raised lead-number** (an alinéa/footnote ¹²³); then a recursive **interline** split cuts each
segment at an abnormal leading gap. Size and weight are the discriminants — **font name is deliberately
not a cut signal** (an italic emphasis word or a subset-font glyph mid-sentence would over-segment a
paragraph). This is what separates a heading line from the body it sits against, so `FontProfile` can
label each. Over-segmentation is the safe bias: a marker opens its own block, and because every line was
sealed on a word boundary (`Spacer.endLine`) the text stays correct across the cut.

### Spacer — the space authority

Runs reach `Spacer` as pure ink. The moment `Segmenter` rebuilds a clean line (`Liner`, band only),
`Spacer.freeze` seals it **once** and it is never touched again:

1. **reading order** — the line's runs are ordered left→right (intrinsic to a frozen line);
2. **inner spaces** (`spaceLine`) — the line is read as one glyph stream and, at every seam (intra-run
   and inter-run alike), the advance slack `step/size − advance` is read as a **ratio of the face's space
   width**, so a line that mixes sizes still compares. **The line names its own gate**: walking its sorted
   ratios up from the bottom, the first value that stands twice above the tight cluster under it (the
   letter seams, ~0 in body text, the tracking on a tracked line), across a jump at least as wide as that
   cluster is spread, opens the word spaces. The FIRST valley, not the widest: a justified press column
   mixes a tight word space (0.36 of the face's space) with a loose one (1.2), and a results table mixes
   word spaces with column gaps (1.0 against 7.2) — the widest jump lies between the spaces there, and
   every tighter space was glued ("Leconseil", "YoungBoys"). The gate never falls under the style
   (`SPACE_FRACTION`, 0.28) on a line that has a gap past it — a looser letter pair in a display face is
   letter fitting — nor under the kerning floor (`MIN_GAP`, 0.10) on a line whose gaps all sit under the
   style, where the face's space width says more than its setting. A line with no valley says nothing
   rather than guess (one tracked word and a line of isolated letters look alike); under `MIN_SEAMS` seams
   there is no distribution and the style decides;
3. **line end** (`endLine`) — the line ends on a word boundary: a trailing sentinel, unless it ends in a
   hyphen (left painted, for read-out to rejoin the split word or keep a compound). This seals the
   inter-line *and* inter-block same-line seam — a `Titre 2` marker block to its rubric block. The
   read-out (`OCDIndex`) joins the split word again, never with a space, and decides the hyphen by
   self-reference, no lexicon: it stays when the document writes the compound whole on one line
   (`allers-retours`) and never the joined form, it goes when the document writes the word
   (`TraceMonkey`); with no evidence a lowercase continuation is a syllable break (rejoined, as poppler
   does) and a capital one a compound (`Saint-Gall`). A dash ending a line (`client -`) splits no word and
   gets its space back. The client's read-aloud and search index apply the same rule over the whole book
   (`ocd.js` `keepsHyphen`; the bench `hyphens.mjs` holds the two sides together). Wider evidence — both halves being words the document writes — was
   measured and refused: it kept as many syllable breaks (`inter-face`, `rassemble-ment`) as compounds.

Every space is a render-neutral **sentinel** glyph (gid −1, unicode `" "`): outline-less, so every render
path skips it (`font.glyph(gid) == null`), yet `text()` / extraction / structure read the `" "`. Because
the sentinel lives in the run, the whole downstream reads spaced text with no inter-run logic.

Belonging to no face is what makes the sentinel render-neutral, and it is also the one thing every text
*writer* must handle by name: a reverse cmap cannot answer for gid −1. `write.PdfWriter` names it U+0020
explicitly — but only when the face maps a space itself. That guard is not merely defensive (showing a
space a face cannot encode leaves PDFBox subsetting a font whose every shown glyph is `.notdef`, and
`PDDocument.save` throws *The font has no glyphs*): forcing an inkless U+0020 into every compiled face was
measured on the press corpus and traded 8 welds for 85 over-splits, because a face that never painted a
space is exactly where the geometry's word breaks are least reliable.

A rotated line is a known limit of the *readback*, not of the model: the model reads a 90° table header
correctly, while re-extracting it from any PDF text layer — the source's as much as ours — yields isolated
letters. Content set at an angle should be read from the model (Markdown / DocTags / OCD), never
re-extracted from the projection.

`OCDBreak` is a **layout flag only** — it marks visual line boundaries for reflow and is **never**
consulted for spacing. At read-out, `OCDIndex` is a plain concatenation of run text; the only transform
is the **soft-hyphen rejoin** (the line-break hyphen stays painted, so it is resolved only at read time).
`Cleaner` strips the sentinels at the head of every analysis, so the pass is idempotent.

### FontProfile — headings by recurrence

`FontProfile.of` reads the whole document once and builds a typographic alphabet: per page (excluding
running furniture, INCLUDING invisible text — a scan's words are its OCR layer) it clusters lines and accumulates, per type-style signature (half-point size +
whole-line bold), the total ink, the number of pages it appears on, and its occurrence count. The
**body** is the signature with the most ink. The canonical rule for a heading:

> a style is a heading only if it is **elevated** (larger than the body, or bold at body size)
> **and it recurs** (appears on ≥2 pages or ≥3 times).

Recurrence is the load-bearing discriminant. Without it, a one-off large decoration or a figure's
internal label would masquerade as a heading; with it, only real heading styles survive. Elevated
recurring styles are ranked by size into H1…HN; the single largest elevated style above everything is
the title.

**Known, accepted limit** — an enumerator-bold line with a long regular title ("**Art. 10**
Traitement médical") stays a paragraph: the whole-line-bold guard (`BOLD_LINE`) is deliberate and is
not weakened for this pattern. Such motifs are deferred to a future **custom-rules re-pass**
(user-defined heading rules applied on top of the heuristic), not to the geometric pass.


## Why graphics are handled apart

It is tempting to feed text *and* graphics into one XY-Cut and let it sort everything out. Measurement
says no. On the test document, cutting text-only gives clean paragraph-grain leaves (21 / 23 / 12
leaves on three sample pages); adding the page's graphics into the same cut collapses it (8 / 9 / 10
leaves) — a graphic spans across the text and bridges the whitespace valleys, so the cuts vanish.

So the architecture is principled, not incidental: **graphics are clustered separately
(`GraphicClusterer`) and placed by spatial overlap**, while `XY-Cut++` runs on text only. The single
clean cutter owns the text; the graphic path owns the drawings; `StructureBuilder` joins them. In the
ORDER a graphic counts once, and only as a screen: it never cuts a column, and no row cut runs through it. This is
why the analysis is not one monolithic "cut everything" routine.


## The two-layer graphic architecture

A drawing exists at two levels, and conflating them produces noise (every header logo and callout
banner promoted to a figure):

- **`OCDGraphic`** — the presentation entity: a visual drawing on the page, produced by
  `GraphicClusterer`. Always present.
- **`FIGURE`** — the logical struct: reserved for captioned or obviously significant content only.

The heuristic pass stops at `OCDGraphic`; promoting a graphic to `FIGURE` (by caption-anchoring and a
texture opinion) is **deferred to the LLM-refine layer**, so the geometric pass never over-promotes a
logo or banner.


## What is detected vs deferred

The doctrine, made concrete. The heuristic pass commits only what pure geometry establishes with high
confidence; everything else is deferred to the LLM-refine layer or the human.

**Detected — high-confidence geometry**

- lines — vertical overlap, overlap-only banding (`Liner`)
- spaces — re-derived from glyph geometry, materialised as sentinels (`Spacer`); `OCDBreak` is a layout flag
- segmentation + reading order — `XY-Cut++`
- running furniture — recto/verso stack stability (`Furniture`)
- headings — typographic recurrence (`FontProfile`); everything else is a paragraph
- figures — pictures the body shows, grown into zones free of body copy (`Figures`)

**Deferred to the LLM-refine layer** — tables, captions, lists and code. The heuristic pass no
longer guesses these: they need either a texture/grid opinion or a lexical pattern that geometry alone
cannot commit safely, so `Refiner` recovers them downstream (grounded, optional).

**Deferred to the human** — ambiguous numbering (a bare "§ 4", a lone "1"), unusual layouts, anything
not obvious. Left as plain content for the interactive structure editor.


## Two read-only passes that are not stages

Neither mutates the model, and neither sits in `Analysis.run` — mistaking them for pipeline stages is
the classic misreading of this layer:

- **`BlockSignals`** walks the flow in reading order and emits one `BlockFeatures` per content unit — grid box,
  dominant typography, density, alignment, colour, background, per-column position. It is the signal
  harvest the LLM pass consumes; pure read-only analysis.
- **`AnalysisStages`** is the `stages` projection: it exposes the geometry of every stage
  (`runs · lines · leaves · blocks · labeled`) so Prism's analysis layer can scrub the pipeline on the
  rendered page. Lines and leaves are transient inside `Segmenter.segment` and never reach the OCD, so
  the projection **recomputes them with the same `Liner`/`XYCut`/`Segmenter`** — which is why the dump
  always tracks the current heuristics instead of drifting from them.

The `stages` export segments with `Segmenter.preview` — the same blocks, no line frozen — so it never
edits the document it describes. Its labelled stage asks `StructureBuilder.isHeading`, the builder's own
verdict, and the builder reads a block's style with `FontProfile.styleOfLine`, the profile's own rule.

## Verification discipline

Every change is held to two gates before it is kept:

1. **Render-neutral (Fid2 = 0.0)** — convert with structure off vs on, raster-diff every page; the
   mean per-channel difference must be `0.0000`. A structural change that moves a pixel is a bug.
2. **Structure counts** — the recovered `HEADING / PARAGRAPH` counts are tracked against a known
   baseline (Fedlex: 1 / 128 / 1344 document·heading·paragraph; overview: 1 / 20 / 285); an unexplained
   shift is investigated, not waved through.

Changes are measured before they are committed. When a "cleaner" idea regresses a gate, it is reverted
on the evidence rather than rationalized.
