# ANALYSIS — the geometry-first structure recovery pass

## One-page orientation
The analysis layer turns the flat nodes read out of a PDF (text runs, glyphs, paths, images) into a
structured, projectable document — **by pure geometry**, render-orthogonal (it sets node roles, reading
order and an `OCDStruct` tree *by reference*; the renderer still paints by `z`, so `Fid2 = 0.0`).

## Geometry first, then spaces — one authority per concern

The spine is **geometry-first**: the *line* is reconstructed and frozen before any text signal is read,
and each concern has a single owner.

1. **`Cleaner`** — flatten the document to its simplest base: dissolve prior paragraphs, drop every blank
   and `OCDBreak`, return pure-ink runs. Idempotent (re-analysis re-derives everything).
2. **`Liner`** — the **line authority**, pure geometry. Bands runs by **vertical overlap alone** (≥ 1/3 of
   the smaller band), so a raised/shrunk **sub/superscript naturally belongs to its line** — no size gate,
   no lexicon, OCR-robust. `lines(·, true)` cuts columns at a >1 em gutter (proto-lines for XY-Cut);
   `lines(·, false)` keeps the whole band (clean final lines inside a leaf).
3. **`XYCut`** (XY-Cut++) — one engine: `segment` (grouped leaves) for blocks, `order` (flattened) for
   reading order. Cross-layout masking, relative-depth valleys, adaptive thresholds.
4. **`Spacer`** — the **space authority**. The moment a clean line is rebuilt, it is **frozen once**: runs
   ordered left→right (reading order is intrinsic to a frozen line), word spaces inferred, the line sealed
   on a word boundary. Every space — intra-glyph, inter-run, inter-line, inter-block same-line — is a
   render-neutral **sentinel** (gid −1, `" "`). One uniform rule per seam: the advance slack `step/size −
   advance` past half the font space width (the style) is a space. `endLine` seals the boundary unless the
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

Every change is held to gates, in order: **javac 0/0** → **structure counts** (`HEADING / PARAGRAPH`
against a baseline) → **spacing/geometry normalized** → **render-neutral** (`Fid2 = 0.0`, raster-diff vs
the baseline build) → **idempotent** (`pdf→doctags == ocd→doctags`). Measure before committing; revert on
the evidence.

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

The live sequence is orchestrated in `convert/Analysis.java`. Each stage is gated by a
`ConvertOptions` flag and is a no-op when disabled.

```
Cleaner.clean                          normalize to the shared ink base; mints leaf ids
  → Paragrapher.recompose              Segmenter ─ Liner ─ Spacer.freeze ─ splitLeaf ─ OCDBreak
  → Furniture.detect          [HEADERS] running heads/feet → roles, before the font profile
  → GraphicClusterer.cluster [GRAPHICS] paths → OCDGraphic, before structure (a FIGURE may wrap one)
  → OutlineAligner.align    [STRUCTURE] nav is ground truth: re-cut/merge title blocks, before ids
  → IdStamper.fill                     mint the wrapper ids created above
  → buildOutline()          [STRUCTURE] BookmarkStructureBuilder | StructureBuilder (see below)
  → HeadingRoles.project    [STRUCTURE] best structure → page-level heading roles
  → Refiner.refine               [LLM]  optional, grounded, no-op on failure
```

**`convert/Analysis.java` is the authority on this order** — it carries the sequence
*and* the reason for each position as an end-of-line comment. When this document and
that file disagree, the file is right; fix the document.

The physical layer is **line-first**: `Paragrapher` drives `Segmenter`, which reconstructs each clean
line with `Liner` (overlap-only banding) and has **`Spacer` freeze it on the spot** — reading order +
word spaces, sealed once — *before* the frozen lines are grouped into blocks (`splitLeaf`) and wrapped
into paragraphs with `OCDBreak` markers. `Furniture` then runs on the reconstructed, spaced lines.

`LanguageDetector` is **not** a pipeline stage — it runs at export (HTML / EPUB) as a language fallback.

It groups into four phases:

| Phase | Stages | Role |
|---|---|---|
| Normalize | Cleaner | clean to a shared ink base; Cleaner mints deterministic leaf ids |
| Physical layer | Paragrapher (Segmenter + Liner + Spacer), Furniture, GraphicClusterer | reconstruct + freeze lines, segment paragraphs, running furniture, graphics |
| Logical structure | OutlineAligner, IdStamper(fill), structure builders, HeadingRoles | align on the nav, mint wrapper ids, label text + titles, project heading roles |
| Enrich | Refiner | optional LLM refinement |


### 1. Cleaner — normalize to the shared base

The one normalization every later pass starts from, so detection reads the same input whatever the
source (a freshly imported PDF, an OCD-EPUB read back, a re-analysis). It is fidelity-exact — it only
removes content that reaches no pixel and unwraps render-neutral wrappers, in **one flattening pass**:

- **dissolve prior paragraphs** — splice render-neutral wrappers (identity `OCDParagraph` /
  `OCDGraphic`; render-bearing `OCDGroup`s are kept and recursed into), flattening their runs up;
- **drop `OCDBreak`s** (line markers paint nothing; restructuring regenerates them);
- **strip every blank glyph** from each run — a real space glyph *and* any prior space sentinel both
  paint nothing — so runs become **pure ink**; a run emptied by stripping is dropped. The word spaces
  are not trusted from the source: they are re-derived later from glyph geometry by **`Spacer`** and
  materialised as sentinels. Re-analysis dissolves and strips inside former paragraphs too, so prior
  sentinels never accumulate;
- **keep inked runs, groups, paths and images intact**, reassign paint order, and rewrite structure
  refs so a spliced wrapper's references follow to its content nodes.

It is idempotent (re-running is a no-op) and structure-preserving — the native `pdf` (PDF/UA) tag tree
survives untouched. `clean` runs first in `Analysis.run`, so a re-analysis is simply `run` again
(there is no separate re-analysis entry point; `restructureText` / `restructureHierarchy` are the two
PARTIAL re-runs Prism offers, each a documented subset of `run`).

*(Ligature / NFKC normalization is not a pipeline pass; it is handled at the glyph/model and writer
level so the painted glyph shapes are never altered.)*

### 2. IdStamper — the single id authority

Mints deterministic ids for nodes. Every structure reference points at a node by id, so this is the
one place ids come from. It runs once early (`stamp`) to id the original content, and again later
(`fill`, idempotent) to id the synthetic nodes — paragraphs and graphics — that the structure will
reference, **without ever disturbing an id that is already referenced**.

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
later `FIGURE` can wrap a finished graphic.

Crucially, graphics are clustered **separately** from the text cut — not fed into the same XY-Cut.
This is a measured architectural fact, not a convenience (see *Why graphics are handled apart*).

### 6. StructureBuilder — the logical layer (text + titles)

Reads the paragraphs back (via `Segmenter.fromParagraph`, so it trusts the segmenter rather than
re-segmenting), orders the blocks with the shared `XYCut`, and labels each into an `OCDStruct` tree.

**Scope: text + titles only.** This pass emits exactly two element types — **HEADING** and
**PARAGRAPH** — under a `DOCUMENT` root. Figures, tables, captions, lists and code are *deferred to the
downstream LLM-refine layer*; the heuristic pass commits only what pure typography can establish.

- **Headings are typographic, not lexical.** A block is a heading only when `FontProfile` — the
  document-wide style alphabet — ranks its signature (half-point size + whole-block bold) as an
  *elevated, recurring* style, and the block is short (≤ 2 lines, ≤ 160 chars: a heading is a label,
  not a flowing paragraph). No local size-ratio guess, no enumerator regex.
- Everything else is a **PARAGRAPH**.

Recurrence is the load-bearing discriminant: a one-off large decoration or a figure's internal label
never becomes a heading because its style does not recur.

### 7. Reading order — `XYCut.order`

Reading order is **not a separate class**; it is `XYCut.order` — the same XY-Cut++ engine the
segmenter uses — applied in two places: `Paragrapher` orders the page `content` array (so the array
order *is* the reading order), and `StructureBuilder` orders the structure blocks. Running furniture
is excluded via `Furniture.isRunning`. Paint order is carried separately by each node's `z`.

### 8. Refiner — optional logical refinement

An optional, grounded LLM pass (page-windowed, with a document profile and ordered-triplet context,
cancellable). It refines the logical structure — and is where the deferred figures / tables / captions
/ lists / code will be recovered. It is a no-op when no model is bound and never touches painted
content. `BlockSignals` harvests the signals it reads (see below).

Page-windowing has one cost, and one pass pays it: structured in isolation, a page can only guess
heading depth **locally** — a level 1 on page 3 need not mean what a level 1 on page 47 means.
`StructureReconciler` owns that one decision a per-page model cannot make, re-deriving a coherent
global hierarchy deterministically. It is a normalization, not a second opinion.

### At export — LanguageDetector

`LanguageDetector` is **not** a pipeline stage; the HTML and EPUB writers call it to fill the document
language when the source declared none (never overriding an explicit `/Lang`). Script first (kana → ja,
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
OCDStruct  (heading · paragraph)
```

### Liner — lines without metadata

`Liner.lines` groups runs into baseline lines by **vertical overlap alone**: two runs share a line when
their vertical bands overlap by at least **1/3 of the smaller band**. Overlap *is* the discriminant —
same-line runs overlap strongly, tight-leading neighbours only ≈0.1–0.3 — so a raised or shrunk
**sub/superscript naturally joins its line** with no special case and no size gate (an earlier size
guard orphaned superscripts: a raised, small run seeded its own band and the larger body run was then
rejected — removed). It anchors on geometry, not on font metrics or unicode, so it works identically on
born-digital PDF and on OCR output. `Liner` carries **no text and no spaces** — a line is `(yc, runs)`;
the word spaces are `Spacer`'s job (below).

The `splitColumns` flag is the only variation: `true` cuts a band at any >1 em gutter (proto-lines, so a
margin number / second column stands alone for XY-Cut); `false` keeps the whole band (the clean final
lines inside a confirmed leaf, where an enumerator parted by an indent rejoins its text).

### XY-Cut++ — the single cutter

`XYCut` is the project's one layout engine, used for **both** segmentation (`XYCut.segment`, returning
the tree's leaves) and reading order (`XYCut.order`, flattening the same tree). It is XY-Cut++
(after Nagy & Seth 1984; Liu et al., *Advanced Layout Ordering via Hierarchical Mask Mechanism*,
arXiv:2504.10258, 2025), which fixes plain recursive XY-cut on three points:

- **Cross-layout masking** — an element wider than `β·medianWidth` overlapping ≥2 others is pulled
  out before the cut and re-inserted by vertical position, so a spanning banner or figure never
  erases a column gutter (the *L-shape problem*).
- **Relative-depth valleys** — cuts run on projection profiles and a valley is scored by its depth
  relative to the surrounding peaks (with a noise floor), so a near-empty corridor still cuts and a
  page margin never does.
- **Adaptive thresholds** — the minimum gap per axis derives from the document's own median block
  dimensions, not a fixed constant.

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
   and inter-run alike), the advance slack `step/size − advance` past **half the font space width** (the
   style) is a word space. One uniform rule, no per-line tuning, robust whatever the line holds (one
   glyph, one word, two words, a tab);
3. **line end** (`endLine`) — the line ends on a word boundary: a trailing sentinel, unless it ends in a
   hyphen (left painted, for read-out to rejoin the split word or keep a compound). This seals the
   inter-line *and* inter-block same-line seam — a `Titre 2` marker block to its rubric block.

Every space is a render-neutral **sentinel** glyph (gid −1, unicode `" "`): outline-less, so every render
path skips it (`font.glyph(gid) == null`), yet `text()` / extraction / structure read the `" "`. Because
the sentinel lives in the run, the whole downstream reads spaced text with no inter-run logic.

`OCDBreak` is a **layout flag only** — it marks visual line boundaries for reflow and is **never**
consulted for spacing. At read-out, `OCDIndex` is a plain concatenation of run text; the only transform
is the **soft-hyphen rejoin** (the line-break hyphen stays painted, so it is resolved only at read time).
`Cleaner` strips the sentinels at the head of every analysis, so the pass is idempotent.

### FontProfile — headings by recurrence

`FontProfile.of` reads the whole document once and builds a typographic alphabet: per page (excluding
running furniture) it clusters lines and accumulates, per type-style signature (half-point size +
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
clean cutter owns the text; the graphic path owns the drawings; `StructureBuilder` joins them. This is
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

**Deferred to the LLM-refine layer** — figures, tables, captions, lists and code. The heuristic pass no
longer guesses these: they need either a texture/grid opinion or a lexical pattern that geometry alone
cannot commit safely, so `Refiner` recovers them downstream (grounded, optional).

**Deferred to the human** — ambiguous numbering (a bare "§ 4", a lone "1"), unusual layouts, anything
not obvious. Left as plain content for the interactive structure editor.


## Two read-only passes that are not stages

Neither mutates the model, and neither sits in `Analysis.run` — mistaking them for pipeline stages is
the classic misreading of this layer:

- **`BlockSignals`** walks the flow in reading order and emits one `Block` per content unit — grid box,
  dominant typography, density, alignment, colour, background, per-column position. It is the signal
  harvest the LLM pass consumes; pure read-only analysis.
- **`AnalysisStages`** is the `stages` projection: it exposes the geometry of every stage
  (`runs · lines · leaves · blocks · labeled`) so Prism's analysis layer can scrub the pipeline on the
  rendered page. Lines and leaves are transient inside `Segmenter.segment` and never reach the OCD, so
  the projection **recomputes them with the same `Liner`/`XYCut`/`Segmenter`** — which is why the dump
  always tracks the current heuristics instead of drifting from them.

`LanguageDetector` is likewise not a stage: it runs at export (HTML / EPUB) as a language fallback.

## Verification discipline

Every change is held to two gates before it is kept:

1. **Render-neutral (Fid2 = 0.0)** — convert with structure off vs on, raster-diff every page; the
   mean per-channel difference must be `0.0000`. A structural change that moves a pixel is a bug.
2. **Structure counts** — the recovered `HEADING / PARAGRAPH` counts are tracked against a known
   baseline (Fedlex: 1 / 128 / 1344 document·heading·paragraph; overview: 1 / 20 / 285); an unexplained
   shift is investigated, not waved through.

Changes are measured before they are committed. When a "cleaner" idea regresses a gate, it is reverted
on the evidence rather than rationalized.
