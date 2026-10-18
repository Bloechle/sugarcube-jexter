# REDACTION, PDF/UA, COMPARISON — the engineering notes (public half)

Split out of the market audit on 2026-10-15, when the skill moved into this public repo: what
buyers pay and the product decision stay PRIVATE (account skill `repo-skills`,
`references/market.md`); what binds the CODE is here. `convert/` in the older lines is today's
`pdfimport/` (renamed 2026-09-07).

## Engineering notes (binding — the gaps not to hide)

- **PDF/UA writing lives in `PdfWriter`** and is the exact inverse of
  `convert/TaggedStructureBuilder`: wrap each paragraph's text in `BDC /P <</MCID n>>`
  via `PDPageContentStream.beginMarkedContent`, build `PDStructureTreeRoot` from
  `OCDStructNode` (`H1..H6`, `P`, `Document`), mark furniture `/Artifact`, set
  `MarkInfo /Marked true`, `/Lang` from the model, XMP `pdfuaid:part`, and write the
  outline from headings (already done, `PdfWriter` ≈ l.490).
- **`outline` text mode is inaccessible by construction** (glyphs painted as paths).
  PDF/UA needs the real-text mode with the recompiled OTF (`GlyfOtf`/`CffOtf`) and a
  **complete ToUnicode for every glyph** — ligatures kept as source codepoints in the
  model must map explicitly; inkless glyphs still need an entry.
- **Deferred in ANALYSIS.md = the human/LLM third**: tables, figures, captions, lists,
  code, alt-text. Do not claim full conformance from the heuristic pass alone: tables and
  figures are reviewed in Prism (or proposed by `Refiner`), and veraPDF/PAC validate.
- **Validation gate**: veraPDF (PDF/UA-1 profile) is scriptable and free — make it a
  CI gate like epubcheck; PAC 2024 is Windows-only, keep it as the manual final check.
- **Comparison**: align `OCDParagraph` text across the two documents (LCS on
  normalized read-out), emit an SVG per page with changed runs classed, ignore nodes
  `Furniture` tagged. Page-number drift is expected, never a diff.
- **Redaction checker heuristic**: fill luminance below a threshold, path is an
  axis-aligned rect (or near), covers ≥ 1 glyph bbox at ≥ 90 % — report page, rect,
  the covered text (that is the proof). Also flag redaction annotations never applied.

## Done 2026-09-02 — step (a), the redaction checker AND the redactor (public side)

`redact/RedactionAudit.java` — `audit(OCDDocument) → Report`, the `audit` target
(`--to=audit`, so Prism/API have it via /api/convert); CLI `tool/Redact audit in [--json]`,
exit 1 when anything is recoverable. Model change: `OCDAnnotation.Markup.REDACT` (+ the
`"Redact"` case in `PdfImporter.markup`, FORMAT.md annots vocabulary); `Conversion.load`
made public (the one load authority, now reused by tools).

The test, and why it has no false positives: an opaque (fill α × node α ≥ 0.9), box-shaped
(shoelace area / bounds area ≥ 0.6 — rectangles 1, circles 0.785, glyphs and logos far
below), ≥ 2 pt on each side path whose **z is above** a text run or image — paint order is
the whole test; a background drawn *before* the text (table cell, highlight) never fires.
Glyphs are sampled at (x + 0.25 em, 0.35 em) through the run transform; the finding carries
the covered glyphs verbatim (the proof). Invisible OCR text (Tr 3) counts — the scan case.

Measured on the wild corpus (178 PDFs, 165 convert): **162 clean, 3 flagged, 0 false
positives** — GeoTopo.pdf really does hide `a`/`b` labels under white squares (render
confirms), js_personwithdog.pdf has tiny shapes over 1 % of an illustration (reported as
IMAGE_UNDER_BOX 0.01, true but harmless — the report does not rank; a UI should sort by
kind then covered fraction). Fixtures: `scripts/make_redaction_fixtures.py` (5 cases,
expected results in its header). Gates: CI 21 + JDK 25 lints 0/0, doclint 0, gates.sh
ALL GREEN on pending.pdf (the enum value round-trips through annots.json).

Not done (next): the removal side — delete the nodes, re-crop covered images, purge
XMP/bookmarks/fields, rewrite via `PdfWriter`; and the Prism surface for the checker.

### The redactor (`redact/Redactor.java`, same day)

`Redactor.apply(doc, Zones, fill) → Report` — removes, then **proves**: it re-runs the audit and
throws `IllegalStateException` when the result is not clean, so nothing is ever written half-done.
Wired as the `redact` option in `Conversion.convert` — BEFORE any projection, so PDF/EPUB/MD/DocTags
of a redacted document all agree. `Redact apply in out [--redact=audit|page:x,y,w,h;…|<audit JSON>]
[--redactFill=#rrggbb]`; `apply` defaults to `--redact=audit` + `--selectable`.

What a zone removes: glyphs under it (`OCDText.copyState` keeps the run's id and paint state, only the
surviving glyphs); filled paths by `Area.subtract`; stroke-only paths are OUTLINED first
(`BasicStroke.createStrokedShape`, width × `scaleX`, hairline → 0.1) and become a fill of the stroke
colour minus the zone; image PIXELS painted in the bytes (ImageIO → PNG under a fresh `newImageRef`,
never the shared source; undecodable image = IOException, never silent); annots/links/fields whose
rect touches; `OCDStructNode.text` cleared where a touched node is referenced, refs to removed nodes dropped.
Then the mark is painted UNDER everything (z = page min − 1) — a background to the audit, not a cover.

**Two modes, one rule that took a real document to learn.** A hand zone removes everything in it. An
audit-derived zone names its covering box: only nodes with `z < box.z` (what the box hid) go; content
drawn ON TOP of the box stays; the box keeps its colour and is sent to the bottom of the paint order —
pixel-identical because nothing it covered survives. Without that ceiling, repairing GeoTopo.pdf p.55
erased a whole TikZ figure whose labels sat under white background squares (the "box" was the figure's
own background). And the mark must go to the bottom, or the audit still sees IMAGE_UNDER_BOX on a
scan whose pixels were painted — the audit is geometric, it cannot see pixels.

Measured: fixtures fake/scan/pending/legit → REDACTED, re-audit CLEAN, glyphs absent from the text
layer, the scan's embedded image bytes black at the zone; GeoTopo.pdf (117 p.) repaired via
`--redact=audit`: normalized→redacted drift 0.00 % p.1, 0.05 % p.23/p.55. NOTE for the fidelity facet:
orig→normalized drift on that file is 1.0 % (p.23) and 5.0 % (p.55) — hatched/tiling PATTERN fills
render differently through the normalizer; unrelated to redaction, worth a diagnosis entry.

Clean pass 2026-09-03: `Report.fromJson` is the inverse of `toJson`, so the `redact` option's JSON
form is exactly `Zones.of(report)` (box + colour travel with it — a JSON zone repairs like an audit
zone); `Report.toText()` is the CLI read-out; each cutter (`trim`/`clip`/`paint`) records what it
altered in a `Touched` set, `cut` only removes/replaces. ~200 lines audit, ~230 redactor, 60 CLI.

Round-trip: the `redaction` role on the new box and the REDACT annot value both survive
write(read(x)); gates.sh ALL GREEN on a redacted output; CI 21 + JDK 25 0/0; doclint 0.

Prism mode done 2026-09-03 (`ui/prism/web/redact.js`, ~170 lines, one `<script>` after reveal.js):
Audit → `backend.convert(P.bookBytes(), 'audit')`, findings drawn as `.rd-find` rects in a `data-ui`
layer appended to the rotation wrapper (or the svg), tooltip = hidden text; drag = zone (rubber band
through `ref(svg).getScreenCTM().inverse()`, kept in PAGE space); Apply → `convert(bytes, 'ocd',
{redact: <findings + zones in the audit's JSON shape; a drawn zone is a finding with box ''>})` then
`book.open`. `P.bookBytes` is now exposed by engine.js (was module-private). Page↔svg: svg (sx, sy)
= (x − bx, by + bh − y − h) from `data-mediabox` (legacy pages: `data-crop`). Seam pitfalls: `g.ownerSVGElement` is
null under linkedom — use `closest('svg')`; the engine refuses a PARTIAL payload (one box of two)
with HTTP 500 `{"error":…}` — that is the gate, not a bug; `reason()` unwraps it for the toast.
The redact option rides the query string: fine for hundreds of findings (Java's HttpServer header
limit ≈ 384 KB), not for thousands — move it to the body if that day comes.

Session 3 (2026-09-03, afternoon): pick by block/run/page/regex + instant preview. `Redactor.match`
(glyph-span zones; skip Spacer blanks — they sit at their predecessor's x — when closing a span, or the
last letter survives), `--to=zones --match=`, `Kind.MATCH`, tolerant `Report.fromJson` (client kinds →
MATCH). UI: one item list, opaque preview, `jexter/redact.json` member, export applies. Measured: a
whole-document OCD round trip is ~4 s cold on 117 pages (read ≈ write ≈ 2 s; redaction ≈ 0) — the reason
Apply cannot be "instant" and why the preview + export-time application is the design, not a workaround.
Session 4: page-scoped Apply. `--to=patch` + `redact=` → `OCDReader.readPages` (Tier 1b: skeleton + the
named pages, EMPTY PLACEHOLDERS for the rest so indices stay real; `resolve`/`prune` must skip unloaded
pages or every structure ref to them is dropped — met, fixed) → `OcdEpubWriter.patch` (only the changed
members; pages byte-identical to the full write because font aliases come from the skeleton). 1.5 s vs
6.2 s cold on 117 pages. Client: unzipSync → `book.put` (+`declare` for images) → `book.refreshPage(idx)`
(new; reloads one frame, drops its adopted model). The proof is scoped to the zones; pre-existing leaks
elsewhere are reported, never block. Pre-existing, unrelated: `gates.sh` STABLE=DRIFT on GeoTopo.pdf
under v1.0.0 as well — pattern/tiling fills; a diagnosis for the fidelity facet.

Audit pass (2026-09-03): cross-run matching (units = paragraph runs joined, `'\n'` at breaks — the
Spacer's trailing blank glyph carries the inter-run space), `purgeMeta` + `redactMeta`, image sweep
237/237 decodable (import stores PNG or JPEG passthrough; JBIG2/CCITT scans redact), Ctrl+Z / Esc in
the drawer. Known, documented limits: nodes inside a TRANSFORMED group use their own bounds (page-space
assumption); outline titles are not purged (they are page content); a regex never matches across
paragraphs; `--to=audit` still reads the whole document (page-scope it like patch if it ever matters).

Not done (next): outline purge (a title or XMP field may quote redacted text — the
audit does not cover them either); the Playwright bench pass on the Redact tab.
