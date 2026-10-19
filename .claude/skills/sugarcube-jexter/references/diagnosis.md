# Diagnosis playbook — reading a side-by-side comparison

Once `compare.py` gives you SOURCE | GENERATED, scan for divergences and map
each symptom to its likely root cause. This list is distilled from debugging a
real PDF→EPUB converter; the causes generalize to any HTML/SVG renderer fed by
an extractor.

Work *outside-in*: first decide whether the bug is in the **renderer**, the
**emitted markup** (SVG/HTML/CSS), or the **extractor** that produced it. The
fastest way to localize a text bug is `glyph_probe.py` (below).

## Text looks crammed / glyphs overlap

The single most common and most misleading symptom. Localize before fixing:

1. **Is the renderer honoring per-glyph positions?** `glyph_probe.py` renders a
   system font with an explicit `x` list. If those glyphs land at the requested
   x, the renderer is fine. (Old engines sometimes ignore multi-value `x`.)
2. **Is the emitted markup correct?** Check the `<text x="...">`: there should be
   one x per character, matching the source's glyph positions. If counts match
   and values are right, the markup is fine.
3. **Then it's the embedded font.** `glyph_probe.py` measures embedded vs system
   glyph widths at the same size. Ratio ≈ 2 ⇒ **outline units-per-em mismatch**:
   the extractor scaled glyph outlines by a hardcoded 1000 while the font program
   is 2048 units/em (common for TrueType substitutes). Tell-tale: a monospace
   font that *happens* to be 1000/em (e.g. Courier) renders fine while
   proportional 2048/em fonts cram.
   **Fix:** scale outline coordinates by the font matrix (`1/unitsPerEm`), not a
   constant. Keep advances and ascent/descent in 1000-unit text space — those
   come from `getWidth`/the descriptor and are already correct.

## An image is missing, mispositioned, or not rotated/skewed

The emitter is placing images by axis-aligned bounding box and ignoring the
image's transform (CTM). A rotated/sheared image then lands wrong or collapses.
**Fix:** place a unit (1×1) image and apply the full CTM as a transform. For a
Y-up source rendered into a Y-down SVG page, the transform is
`pageFlip · CTM · imageRowFlip`, e.g.
`matrix(1 0 0 -1 0 H) matrix(a b c d e f) translate(0 1) scale(1 -1)` on
`<image x=0 y=0 width=1 height=1 preserveAspectRatio="none">`.
Verify it reduces to the old axis-aligned placement for an upright image (CTM =
`scale(w,h)·translate(x,y)`) so you don't regress the common case.

## A shape that should be clipped renders whole

A circle "clipping" a rectangle shows the full rectangle. The clip region was
never captured. Renderers support `clip-path`/`<clipPath>`, but nothing populates
it. **Fix (extractor side):** maintain a clip stack synced with save/restore
(`q`/`Q`); when a clip operator fires, capture the current path as the active
clip and attach it to subsequently emitted nodes until restored.

### …only in the WRITTEN page, and only for TEXT (paths clip fine)

Hidden text reappears — a masthead's display type over its own small logo box —
after `pdf → ocd.epub`, while the in-memory model renders correctly. Two causes,
both in the page, both invisible to the Java reader:

1. **The clip id was renamed.** The `<defs>` say `clip4` (a presentation id
   minted per entry and deduped on geometry) while the node says `c7` (the model
   id). Read back, the clip table holds `clip0..clipN` and every model-side
   reference dangles. Symptom census: the CLIPS gate (`test/Gates.java`) names
   every reference absent from the table — that reference is the bug. Only
   documents with two clips of EQUAL geometry expose it; elsewhere the renaming
   is the identity.
2. **The clip was never native.** The node carries only `data-clip="c7"`. The
   Java reader honours it, no SVG engine does — so the page is right through
   `ocd → pdf` and wrong in Prism. Paths escaped because `SvgWriter` also wrote
   them a real `clip-path`; text runs had nothing.

Fix both at once: the emitted id IS the model id, and every clipped node is
wrapped in a native `clip-path` carrier (SKILL §7). Prove it with
`scripts/ocd_page_bench.mjs` — NOT with `ocd → pdf`, which cannot see it.

### A field is wrong after `pdf → ocd → pdf`, but every pixel gate is green

The classic shape of an INFERRED value. Something is stored as a product and
recovered by factoring it back out; the product paints correctly, so no raster
comparison can see the error, and it only shows up downstream — as a bad analysis
signal, a heading that stops being detected, a font size that drifts. Measured
case: `fontSize` recovered as `√|det|` of the run matrix, which conflates it with
an anisotropic run (`Tz`, a squeezed CTM). Run the MODEL gate (`test/Gates.java`): it compares
the model field by field across the round-trip and names the field. Then STATE the
value in the grammar instead of deriving it.

### A rotated caption swallows the column beside it, and loses its own spaces

Both symptoms have ONE cause: writing direction was nowhere in the geometry.
`Liner` banded on boxes alone, so a caption running down a margin - a tall, narrow
box - overlapped every band of the column and welded heading, body and caption
into one paragraph. `Spacer` measured word gaps in page X, and a run turned a
quarter turn advances along page Y, so every gap measured zero and the caption
read LEGENDEVERTICALEDELACOLONNE.

The fix is geometry, not a special case: a line IS a baseline and a baseline has a
direction, so a run only joins a line written along the same axis (`Liner`, one
open accumulator per axis); a paragraph runs along one baseline too, so a change
of axis is a hard cut (`Segmenter.splitLeaf`); and a glyph's position is its page
position PROJECTED on the writing direction (`OCDText.glyphPageX`), which is page
X exactly for upright text.

Trap, measured: project the ABSOLUTE page position, never an offset from the run's
own origin. The spacer compares glyphs ACROSS the runs of a line, so they must
answer in one shared frame - measuring per-run origin silently doubled the
soft-hyphens a newspaper recovered (12 to 34) while the rotated case looked fixed.

### Words weld in the read-out: "contienttous", "117centrale"

Two runs on DIFFERENT visual lines were recorded as one line, so no OCDBreak marks
the boundary and Spacer left no trailing space. Chain to walk, with TextFid as the
gate that surfaces it at all:

1. Dump the paragraph's children - no BREAK between the welded runs.
2. Dump their geometry - disjoint y bands, same x. They ARE separate lines.
3. Replay `Liner.lines(runs, false)` on the REGION, not the paragraph: a taller
   neighbouring column (a sudoku solution grid at 9.2pt beside its caption at
   6.8pt) has each of its runs straddling two caption bands and chains them.

`Liner` is doing exactly what it documents (vertical overlap >= 1/3, no size
gate). The fault is upstream: XY-Cut did not split the two columns, and inside a
confirmed leaf there are no columns by contract. Fixing it means improving the
cut, not weakening the line authority.

`OCDIndex.join` refuses the weld as a last line of defence - on one line a run
always advances strictly, so a run starting at or left of the previous one is a
line boundary whatever the physical layer recorded. That is a read-out guard, NOT
a second line authority: it never re-decides the lines, it only declines to
concatenate two runs that cannot be adjacent.

### Text is spaced out, glyphs narrower than their advances, line breaks identical

The font is not embedded. PDFBox scales each glyph horizontally to the width the
PDF declares (`PageDrawer.drawGlyph`); a substituted program's own advance has no
reason to match `/Widths`. Reconcile at import in `FontExtractor.widthStretch` —
the model stores the PDF's advance, so it must store an outline consistent with
it, or every surface re-derives the correction.

## Gradient/image-filled text renders as solid boxes (tofu) in Direct, fine in PDFBox

Letters filled with an image or gradient (e.g. a banner title: `Tj` in **text render
mode 7** = clip-only, then an image/shading painted *through* the glyph clip) come out
as solid filled rectangles — one per glyph — in the OCD/Direct renderer, while the
reference PDFBox render is correct. Tell-tale in the model: each letter is a `mode="7"`
`<text>` (one glyph) immediately followed by an `<image>` (or shading raster) whose
`clip` resolves to a **4-point rectangle** ≈ the glyph cell, not the glyph outline.

Root cause is extractor-side: the base `PDFGraphicsStreamEngine` does **not** accumulate
the text clip from `Tr` modes 4–7 (that lives in PDFBox's `PageDrawer`, which jexter does
not extend). So at `Do`/shading time the active clip is only the rectangular `W n` cell,
and the glyph shape is lost — the following image fills the whole cell. (The Direct
renderer correctly treats `mode 7` as paint-nothing via `OCDText.isInvisible()`, so the
box comes entirely from the un-masked image, not from the text.)

**Fix (extractor side, `PdfStreamEngine`):** mirror `PageDrawer`. Buffer the page-space
outline of every clip-mode glyph between `BT`/`ET` (`beginText` starts the buffer;
`showGlyph` appends `trm × fontMatrix × getNormalizedPath(code)` — the same convention as
`FontExtractor`), and at `endText` union them and `getGraphicsState().intersectClippingPath(...)`.
The q/Q stack then scopes it for free, and the following image's captured `clipId` resolves
to the glyph outline. One fix in the shared base → every backend (Direct, inline
`PdfRenderer`, SVG, HTML, EPUB) is correct and matches PDFBox. Reset the clip-dedup cache
(`lastClipArea = null`) after the intersect, since the clip object is mutated in place.
Corpus guard: `text_clip_image` (Tr 7 + image fill) in `generate_matrix.py`.

## A text block vanishes / only the first letter of each line renders

The text nodes *exist* in the model but each carries a tiny clip (~one glyph
wide). Some generators clip every letter to its own outline (`<glyph outline>
W n` per glyph, inside its own `q/Q`) — a visually neutral self-clip. If the
importer merges glyphs that share font/fill/baseline into one run *without*
checking the clip, the whole run inherits the first glyph's ~4 pt clip and is cut
to nothing. **Fix (extractor side):** treat the clip as part of run state — a
clip change starts a new run, captured at run start (not at flush) — so each
self-clipped glyph renders under its own outline. Then optionally neutralize
glyph-sized self-clips (`ConvertOptions.mergeGlyphClips`, default on) so the run
stays merged and no per-letter clip pollutes the model. Signal: a clip whose bbox
is ≤ ~1.6× the point size is a self-clip; real column/box clips are far larger.

## Special glyphs garbled / a fallback face replaces an embedded font (`†`→`Ü`, `-`→`!`)

A subset TrueType embedded with a `post` table v2.0 whose name array is empty
makes FontBox reject the whole font; PDFBox then substitutes a stand-in and the
font's custom code points map to the wrong glyphs. **Fix (extractor side):**
`PdfImporter.repairEmbeddedTrueTypeFonts` rewrites that font's `post` version to
3.0 (header-only, no names) in the in-memory `PDDocument` — `glyf`/`loca`/`cmap`
stay byte-identical, so the author's real outlines render. No-op on healthy
fonts; the file on disk is never touched. This is the one deliberate spot where
jexter mutates PDFBox's loaded model.

## Wrong stacking (z-order) / something drawn over the wrong thing

Emit nodes in source paint order and don't reorder by type. If "text on a box"
shows the box on top, the box was emitted after the text.

## Stroke detail lost (dashes solid, wrong caps/joins, no blend)

The emitter writes fill/stroke/width but drops the rest of the graphics state.
Pull dash array+phase, line cap, line join, miter limit and blend mode from the
state at paint time and map them to CSS (`stroke-dasharray`, `stroke-linecap`,
`stroke-linejoin`, `stroke-miterlimit`, `mix-blend-mode`). Map blend-mode names
from source CamelCase to CSS kebab (`ColorDodge` → `color-dodge`).

## Colors off

Usually a color-space conversion gap (CMYK/ICC/indexed → sRGB) or alpha applied
to the wrong channel (fill alpha `ca` vs stroke alpha `CA` swapped).

## ONLY rotated / sheared content is wrong (everything axis-aligned is perfect)

The most deceptive geometry bug. Axis-aligned text, rects, upright images all
match the reference pixel-for-pixel; a *rotated* text run or a 45°-rotated image
lands in the wrong place or at a mirrored angle — yet its transform's translation
reads correct, and the per-glyph/extent math checks out on paper. Diff shows the
rotated element as a doubled ghost (reference position + wrong position).

**Cause:** the affine-transform helper is internally inconsistent about the two
off-diagonal (shear) terms — `of(AffineTransform)` and `awt()`/`apply()` disagree
on which slot is `m01` vs `m10`, so a round-trip **transposes the linear part**.
A pure scale/translate (`b=c=0`) is unaffected, which is exactly why every
axis-aligned case passes and only rotation/shear exposes it. For a rotation,
transpose = inverse rotation, so the element rotates the wrong way and shifts.

**Fix:** make `of`, `awt`, `apply`, `concat`, `inverse` share ONE convention
(here `x' = a·x + b·y + tx`, `y' = c·x + d·y + ty`, so `b = ShearX = m01`,
`c = ShearY = m10`). Add a round-trip self-test on a rotation so it can't
regress silently. **Lesson:** when only sheared/rotated content is wrong, suspect
a matrix-convention mismatch in a shared helper, not the converter.

## Upscaled image is blurry (reference is crisp) — interpolation mismatch

A scaled-up checkerboard/icon renders soft/smeared while the PDFBox reference is
sharp; the diff is a regular grid of soft edges around every pixel boundary, and
a few-pixel-tall color-bar image becomes a smooth gradient.

**Cause:** the renderer draws images with bilinear/bicubic interpolation, but PDF
images without `/Interpolate` are nearest-neighbor. **Fix:** set
`KEY_INTERPOLATION = NEAREST_NEIGHBOR` for image draws (restore after), matching
the reference; reserve smoothing for images that actually set `/Interpolate`.

---

## The model is correct but the SVG/EPUB markup is wrong (emit-side geometry)

These bite the SVG/HTML/EPUB *writer* even when the OCD model is faithful — the
OCDRenderer (Java2D) render is fine but the EPUB-via-WebKit render is not. The
shared reference isolates the stage: if `.ocd.epub`→OCDRenderer matches PDFBox and the
EPUB does not, the bug is in the projection, here `SvgWriter`.

### Everything clipped vanishes (gradients, clipped shapes) — a double flip

A page where *only the clipped content* disappears (a gradient rect, a clipped
path) while unclipped siblings (titles, rules) render fine. The clip was captured
correctly (it shows in the model and in `<defs>`), yet the element clips to
nothing. **Cause:** a redundant flip on the `<clipPath>` child. With
`clipPathUnits="userSpaceOnUse"`, the clip geometry is interpreted in the *user
space of the element that references it* — so it already inherits that element's
`transform` (the page flip `matrix(1 0 0 -1 0 H)`). If the `<clipPath>`'s own
child `<path>` *also* carries that flip, the clip is flipped twice and lands off
the element. **Fix:** emit the clip child path with **no transform**
(`<clipPath …><path d="…"/></clipPath>`); the clipped element's own flip is the
only one. Guard: `vector_gradient`, `vector_clipping`.

### MediaBox ≠ CropBox: content shifted by the crop inset

Reader/PDFBox crop to the **CropBox**; if the EPUB places content in **MediaBox**
coordinates the whole page shifts by the inset (the CropBox border draws inside
the candidate, absent in the cropped reference). **Cause:** the page flip assumes
the crop origin is (0,0). **Fix:** subtract the crop origin in the one shared flip
helper — `matrix(1 0 0 -1 -cropX (cropY+cropH))` (reduces to the old
`matrix(1 0 0 -1 0 H)` when the crop origin is 0). Route *every* emitter
(text, path, image, annotations) through that single helper so it can't drift.
Guard: `page_mediabox_cropbox`.

### Image-filled clip text (Tr 7) still fills the whole box after the extractor fix

Even once the extractor captures the glyph-outline clip (see the Tr 4–7 entry
above) and the `<image>` carries a `clipId`, the EPUB can still show the full
image — because the image emitter never wrote a `clip-path`. Adding it naively is
wrong too: an image is placed with a composite transform (`pageFlip · CTM ·
rowFlip`), and a `clip-path` on that element would inherit the **CTM**, warping
the page-space clip. **Fix:** wrap the image in a flip-only group —
`<g transform="matrix(pageFlip)" clip-path="url(#cN)"><image transform="CTM·rowFlip" …/></g>`
— so the clip inherits only the page flip (like a path does) while the image keeps
its CTM. Guard: `text_clip_image`.

### Font file/`@font-face` name has a doubled style token (`Times-Italic-Italic`)

The emitted `.otf` basename / `font-family` (the font **id** = slug) doubles a
style word: `Times-Italic-Italic`, `Arial-Bold-Bold`. Rendering still works (the
id is consistent across `@font-face`, the `.otf` file and the `<text>`), but the
names are wrong and risk collisions. **Cause:** the slug is `family + styleSuffix`
and the family name *already* contains the style (`Times-Italic`, `Helvetica-Bold`
for non-embedded base-14 whose PostScript name carries the style). **Fix:** only
append a `-Bold`/`-Italic` token the family name does not already contain
(case-insensitive), so `Times-Italic` stays put while `Helvetica` + bold →
`Helvetica-Bold`. Sanity check the whole `OEBPS/fonts/` listing after a font
change, not just the diff.

---

Render the *page*, not an extracted SVG. The page carries the `<head>`, linked
stylesheet and `@font-face` rules, the viewport, and resource paths. A browser
engine (here WebKit via `wkhtmltoimage`) resolves all of that the way a reader
will. An isolated SVG render bypasses the font linking and CSS and will hide or
fake bugs you are specifically trying to catch.

**Scale pitfall (read before trusting a diff).** `wkhtmltoimage` ignores `--zoom`
and `--disable-smart-width`: it renders **1 CSS px = 1 px**, i.e. at the page's
*point* width. So the candidate must be rendered with `--width = the page width in
points` (= the reference width at **72 dpi**), NOT the high-dpi reference pixel
width. Pass the 144-dpi width (e.g. 1224 for a 612 pt page) and the page renders
at ~half scale in a too-wide canvas: the side-by-side shows the candidate shrunk
into the top-left and the mean diff is inflated by a pure scale artifact, masking
or faking real bugs. `compare.py` derives `css_w = W·72/dpi` for exactly this; if
you roll your own loop, render the reference at 72 dpi and pass that width.

---

## OCDRenderer (Java2D) — path-specific symptoms

The OCD model is painted with Java2D (`OCDRenderer`), so divergences map to the
model + paint code rather than HTML/CSS. Common ones:

- **Text overlapping / wrong width** — same units-per-em trap as the EPUB path,
  but here the glyph outline is the `JxPath` in `OCDFont`: confirm outlines are
  em-normalized (1 em = 1) and scaled by `fontSize`, not by a hardcoded 1000.
  Run `glyph_probe.py` on the compiled OTF to localize.
- **Glyphs upside-down** — the outline is Y-up (baseline 0); Java2D is Y-down.
  The page transform must flip Y once (page height), not per glyph. A per-glyph
  flip mirrors each letter in place.
- **Shape not clipped** — the node's `clipId` was not resolved to the page clip
  table, or the clip `JxPath` was not installed as the `Graphics2D` clip `Area`
  before painting. Check the winding rule of the clip path (it is the clip rule).
- **Blend wrong / banding** — `blend` name not mapped to a `BlendComposite` mode,
  or the composite is applied per-node instead of over the `q…Q` subtree. Plain
  opacity uses `AlphaComposite`; named modes need `BlendComposite`.
- **Image rotated / mis-placed** — the unit-square × node transform mapping is
  wrong; verify it reduces to the plain axis-aligned placement when the CTM is a
  pure scale+translate (the upright-image regression guard).
- **Fill vs stroke alpha swapped** — fill alpha is the `OCDPath.fill` argb alpha
  (PDF `ca`), stroke alpha is the `stroke` argb alpha (`CA`); they are not the
  node's scalar `alpha`, which is an extra group multiplier.

If the OCDRenderer PNG matches PDFBox but the EPUB does not (or vice-versa), the
bug is in that projection, not in the model — the shared reference isolates which
stage to fix.

## Validating a written PDF (`PdfWriter` / `tool.PdfNormalizer` output)

Validate with engines *independent* of PDFBox — don't let PDFBox re-read its own
output be the only check: `qpdf --check`, `pdfinfo`, and `pdftoppm` (poppler)
parse and render. Expect one benign `qpdf` warning on **every** PDFBox-saved file:
the trailer `/Size` is off by one (*"reported number of objects N is not one plus
the highest object number"*). It is a PDFBox COSWriter artifact — reproduces on a
one-page blank `new PDDocument().save()` — accepted by all readers; a plain `qpdf`
rewrite normalizes it. Not a jexter bug, nothing to fix in the writer.
