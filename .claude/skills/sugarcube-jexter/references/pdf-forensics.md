# PDF forensics — five independent engines, and how to bisect what only Acrobat sees

PDFBox is our parser AND our reference rasteriser, so it agrees with us by construction. When a
reader complains about a file we wrote, PDFBox is the last witness to trust. This is the toolbox
that gives a second, third, fourth and fifth opinion — all installable in the sandbox from ALLOWED
domains, all used on a real hunt (ANLI, 2026-09-11).

## The engines, and what each one caught

| engine | install | what it gives |
|---|---|---|
| **PDFBox** | `lib/` | per-page render + its own log warnings |
| **poppler** | already present (`pdftoppm`, `pdfimages`, `pdffonts`, `pdfinfo`) | a second renderer, image/font inventories |
| **qpdf 12** | `pikepdf` (already present) | the only STRUCTURAL check: xref, object streams |
| **MuPDF** | Ubuntu deb, below | a third renderer, very vocal about fonts |
| **Ghostscript 10.02** | Ubuntu deb, below | the PostScript-heritage interpreter, closest cousin to Acrobat |
| **pdf.js** | `npm i pdfjs-dist` | Mozilla's parser, warnings per page |

### MuPDF

```bash
base=http://archive.ubuntu.com/ubuntu/pool/universe/m/mupdf
pkg=$(curl -sS "$base/" | grep -oE 'mupdf-tools_[^"]*_amd64\.deb' | sort | tail -1)
curl -fsSL -o /tmp/$pkg "$base/$pkg" && dpkg-deb -x /tmp/$pkg /opt/mupdf
# it needs two libs: libjbig2dec0 (pool/main/j/jbig2dec) and libopenjp2-7 (pool/main/o/openjpeg2)
export LD_LIBRARY_PATH=/opt/mupdf/usr/lib/x86_64-linux-gnu
/opt/mupdf/usr/bin/mudraw -F png -o /dev/null -r 20 file.pdf     # 1.7 has mudraw, NOT `mutool draw`
```

### Ghostscript

```bash
# TAKE THE NOBLE BUILD: the current one (10.07) needs GLIBC 2.43 and will not start here.
base=http://archive.ubuntu.com/ubuntu/pool/main/g/ghostscript
for p in ghostscript_10.02.1~dfsg1-0ubuntu7_amd64.deb \
         libgs10_10.02.1~dfsg1-0ubuntu7_amd64.deb \
         libgs10-common_10.02.1~dfsg1-0ubuntu7_all.deb; do
  curl -fsSL -o /tmp/$p "$base/$p" && dpkg-deb -x /tmp/$p /opt/gs
done
# plus libijs-0.35, liblcms2-2, libidn12, libpaper2, libjbig2dec0, libopenjp2-7 from their pools
export LD_LIBRARY_PATH=/opt/gs/usr/lib/x86_64-linux-gnu
/opt/gs/usr/bin/gs -dBATCH -dNOPAUSE -dNODISPLAY -sDEVICE=nullpage file.pdf
```

Never mix library trees from two Ghostscript versions in one prefix — the loader picks the newer
`libgs.so.10.*` and fails on GLIBC.

### qpdf, through pikepdf (no CLI needed)

```python
pdf = pikepdf.open(f, attempt_recovery=False)   # strict: raises on a broken xref
print(pdf.check_pdf_syntax())                   # qpdf --check, as a list
```

**Measured on 33 delivered outputs: every single one carries
`xref entry for the xref stream itself is missing` — and so does a plain PDFBox
load-and-save of the same source.** It is PDFBox's writer, not ours, and
`d.save(f, CompressParameters.NO_COMPRESSION)` clears it for +3% size. It is NOT what makes
Acrobat complain (proved: a file with and a file without both opened cleanly).

### pdf.js

```bash
npm i pdfjs-dist && node -e "…getDocument({data, verbosity:1}); page.getOperatorList()"
```
Capture `console.warn` / `console.error` around the call — that is where its complaints go.

## Font forensics (fontTools, already present)

```python
TTFont(io.BytesIO(data), checkChecksums=2)   # 2 = RAISE on a bad table checksum
```
Plus, per font: `loca` monotonic, `endPtsOfContours` vs point count, `maxp.maxPoints/maxContours`
not under-declared, `head.magicNumber`, `numberOfHMetrics ≤ numGlyphs`, `/Length1` = stream length,
descriptor keys present AND non-zero (`StemV`, `CapHeight`, `Ascent`, `Descent`), `FontBBox` not
degenerate, `/CIDToGIDMap` length vs `numGlyphs` with no gid past the end, `/ToUnicode` with at
least one `bfchar`/`bfrange`. All of it passed on the failing document — record the negatives, they
are what makes the next hypothesis cheap.

**One real finding from this pass**: PDFBox derives the six-letter subset tag from
`gidToCid.hashCode()` alone (`TrueTypeEmbedder.getTag`), so two subsets of the same face with the
same glyph mapping get the SAME tag — 447 subsets shared 133 names, one name covering 39 different
programs. Fixed by naming the compiled font after the model's unique font ID (`OtfBuilder.psName`).

## Bisecting a defect only Acrobat sees

The engines above will all say the file is fine. The only instrument left is the person with
Acrobat, so spend their clicks carefully:

1. **Split by page first.** `PDDocument.importPage` into one file per page. One page failing out of
   24 turns 447 fonts into 33.
2. **Cut by feature, with the switch the engine already has.** `Jexter <in.pdf> --outline`
   writes every glyph as an outline: text without a single embedded font. Clean file + failing file
   = the defect is in the text path.
3. **Then bisect inside the feature** with a temporary env switch in `PdfWriter.loadFont`
   (`JX_FONTS=id,id,…` embeds only those, the rest fall back to outlines). Quarters, then thirds,
   then singles.
4. **ALWAYS ship a control built by the same pipeline.** A bisect that extracts one page and
   re-normalises it is NOT comparable to a whole-document conversion; a "zero fonts, same path"
   control tells you whether the pipeline itself is the trigger. Skipping it cost a full round.
5. **Swap the suspect for a third-party one.** `JX_TESTFONT=/usr/share/fonts/.../DejaVuSans.ttf`
   embeds a font nobody wrote here. Still failing ⇒ our font compiler is innocent and the defect is
   in how the text is EMITTED (one `Tm`+`Tj` per glyph, `q/Q` around each node, `/CIDToGIDMap` as a
   stream) or in PDFBox's Type0 embedding path.
6. **Shrink to a reproducer.** A 4 KB page whose whole content stream fits on one screen is worth
   more than a 24-page newspaper; build one with PDFBox directly, normalise it, and compare the two
   content streams side by side.

None of these switches ever ships: they live in the working tree for one session and the delta is
checked with `grep -c JX_` before packaging.

## The one it found (2026-09-11)

Fifteen documents across three batches reported "error on opening" in Acrobat. Five engines said
the files were clean — cleaner than their sources. The bisect above, run to the end, narrowed it to
**a `W n` emitted with no current path**: `PdfWriter` wrote the clip operator for any node carrying
a clipId, even when the stored clip path had no geometry. Undefined by the spec, guarded against by
PDFBox's own renderer, ignored by every engine in this toolbox, refused by Acrobat.

The ladder that got there, in order — each step cost the user ONE round of clicks:
page 1 of 24 → fonts vs outlines → any font (even DejaVu) → text only → first 64 runs of 84 →
read the 52 KB content stream by eye. The decisive move was step 6: a file small enough to print
the whole stream and look at it.

## Still open: the doc-A masthead double (2026-09-11)

A press cover draws its wordmark TWICE — 17 glyphs at 73.64 pt, then 20 at 38.64 pt, the small one
painted LAST, right on top. PDFBox and poppler both show only the large one; our output shows both,
and the duplicate is visible over the masthead.

Eliminated, each by measurement, for BOTH copies: clip (`[0 0 709 x 992]`, the whole page),
rendering mode (`FILL`), constant alpha (1.00), soft mask (none), blend mode (Normal), paint order
(green box z=4, large z=11/21, small z=31/41 — nothing covers it after), optional content (the
document has no `/OCProperties` at all). The import is faithful: the bytes really do draw it.

So a reader applies a hiding rule we have not identified. Next moves, in order: instrument
`PageDrawer` itself (does it paint those 20 glyphs, and where do they land?), and if it does,
pixel-diff the masthead area before and after those glyphs to see whether they fall exactly under
the large ones. Do NOT add another "drop it" heuristic first — one was already written and pulled:
judging a node outside its clip cost 0.36% of real ink against 0.05% of ghost, because a node
inside a baked group has its bounds in the GROUP's space while the live clip is in the page's.
Any future version of that rule starts by converting the two into one frame.

## Chromium as a measuring instrument, not just a viewer

Playwright + Chromium are installed (`/home/claude/.npm-global/lib/node_modules/playwright`,
`chromium.launch()` works). Two uses beyond screenshots:

**Geometry.** `getBoundingClientRect()` on the node in question turns "it looks wrong" into a
number — that is how a transposed matrix was found (`115×230` where the model said `229×115`) and
how a broken paint order was localised (`elementsFromPoint` gave the path sitting over the text).

**Cost.** CDP `Performance.getMetrics` gives `Nodes`, `RecalcStyleDuration`, `LayoutDuration`,
`JSHeapUsedSize`. Alternate the variants and take a median of three — on the same page, node counts
and style times swap between runs, so one measurement proves nothing.

**Serving.** Pages with external `<use>` need an HTTP origin; `python3 -m http.server` from the
container's `OEBPS/` is enough. Launch it with `setsid … &` and give it 4-5 seconds, or the first
`goto` races the socket.
