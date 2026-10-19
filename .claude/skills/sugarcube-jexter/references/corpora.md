# Test corpora — sources

Real-world PDFs complement the synthetic matrix: the matrix gives exact,
single-feature truth; corpora give messy breadth. `scripts/fetch_corpus.py` pulls
from GitHub-hosted corpora (only `github.com` / `raw.githubusercontent.com` /
`api.github.com` needed, so it runs in a GitHub-only sandbox).

```bash
python scripts/fetch_corpus.py --list-corpora
python scripts/fetch_corpus.py --curated --out out/corpus       # hand-verified diagnostic set
python scripts/fetch_corpus.py --source pdfjs --list            # GitHub API listing
python scripts/fetch_corpus.py --source pdfjs --pick rotation.pdf cmykjpeg.pdf
python scripts/fetch_corpus.py --source horrors --grep tag --max 5
```

The GitHub API is rate-limited (60 req/h unauthenticated); set `GITHUB_TOKEN` for
5000/h. The `--curated` set downloads straight from raw URLs (no API), so it works
even when rate-limited.

## Fedlex — structure-recovery GT (outline, not tags)

For the **structure-recovery axis** (SKILL.md §4, "Structure recovery"): a corpus of Swiss federal
law PDFs (Fedlex `cc-*` classified-compilation ordinances + the LAA `Fedlex.pdf`).
Deeply nested (Titre › Chapitre › Section › Art.), so a strong heading-detection
stress test. The GT is the **outline (bookmarks)** — NOT the tag tree, which files
most `Art. N` headers as plain `/P` and is often absent on `cc-*` files.

- Source: https://www.fedlex.admin.ch (not in a GitHub-only sandbox — download /
  upload the PDFs out of band; fetch from outside, then drop them in).
- Layout: point `score_outline.py` at any directory via `FEDLEX_DIR` — it globs
  `*.pdf` recursively and keeps only outline-bearing files.
- Score: enumerator recall + merge errors + heuristic-vs-tagged heading counts, per
  doc and aggregate, with a lowest-recall tail for failure clusters.

```bash
export PDFBOX_JAR=/path/to/pdfbox-app-3.0.7.jar JEXTER_CP=/path/to/build/out
javac -cp "$PDFBOX_JAR:$JEXTER_CP" -d "$JEXTER_CP" scripts/StructDump.java
FEDLEX_DIR=/path/to/_fedlex python3 scripts/score_outline.py
```

The LAA (`Fedlex.pdf`, the 832.20 accident-insurance act) is the canonical single-doc
check: the heuristic recovers headings by **typographic recurrence** (`FontProfile`, no
lexicon) — a style elevated and recurring across pages — against the document's tagged
outline as ground truth. Failure clusters across the corpus are where the recurrence
thresholds get tuned.

## GitHub-hosted (fetchable)

- **pdfjs** — `mozilla/pdf.js` `test/pdfs`. The most useful for a converter:
  real-world breakage accumulated from bug reports, with pixel-level regression
  references upstream. Curated picks map to features: `rotation.pdf`,
  `cmykjpeg.pdf`, `alphatrans.pdf`, `transparent.pdf`,
  `pattern_text_embedded_font.pdf` (embedded font / CFF representative),
  `franz.pdf` (multilingual), `TAMReview.pdf` (complex 23-page report).
- **horrors** — `openpreserve/format-corpus` `pdfCabinetOfHorrors`: deliberately
  nasty / malformed files.
- **qpdf** — `qpdf/qpdf` `qpdf/qtest/qpdf`: structurally unusual PDFs.
- **verapdf** — `veraPDF/veraPDF-corpus` (browse subdirs via `--list`): PDF/A
  conformance (ISO 19005), complements Isartor/Bavaria.
- Index of corpora: **`pdf-association/pdf-corpora`** on GitHub.

## Manual download (outside a GitHub-only sandbox)

- **Isartor** PDF/A-1b suite — deliberately violates PDF/A-1 to "validate the
  validators": https://www.pdfa.org/resource/isartor-test-suite/
- **Bavaria** PDF/A-1b suite — via the PDF Association.
- **digitalcorpora / govdocs1** — ~1M real government docs for volume/fuzzing:
  https://digitalcorpora.org/corpora/files/govdocs1/

## Font-type coverage the synthetic matrix can't give

ReportLab (the matrix generator) only embeds TrueType/glyf, so the `CffOtf` path
and real Type1 need corpus PDFs. Quick picks for a font sweep:
- `pattern_text_embedded_font.pdf` — **Type 1C (CFF)** subset (`SourceSansPro-Bold`):
  exercises `CffOtf`. (It also paints the glyphs through a *tiling pattern*, which
  jexter does not yet support → the text fills solid grey; the font itself is exact.)
- `TAMReview.pdf` — embedded **Type 1** standard-14 + a CJK face, and two distinct
  `Times-Roman` programs → checks font-id dedup (`Times-Roman` + `Times-Roman-2`).
- `issue1905.pdf` — **mixed**: TrueType subset (Impact, Arial, ArialNarrow) + Type 1
  builtin (Vegur); good multi-face / multi-weight check on one page.
Inspect with `pdffonts FILE` (type column: `Type 1`, `Type 1C`, `TrueType`,
`CID TrueType`). After conversion, list `OEBPS/fonts/` and confirm one `.otf` per
face with no doubled style token (`-Italic-Italic`).

## Pages worth keeping as gates

- **`Liberte_30_08_2010_p39.pdf`** (a newspaper page, 844×1290 pt) — **34 clips,
  18 of them sharing geometry with another**, plus a masthead whose big display
  text is clipped down to a small box. This is the shape Fedlex cannot produce:
  with no two clips alike, a geometry-deduping clip renamer is the identity and
  the whole class of clip-id bugs stays invisible while `write(read(x)) = x`
  reports green. It also carries text clipped to nothing, so any lost clip is
  loud rather than subtle. Gate it with `java -cp "target/classes:lib/*" test/Gates.java the.pdf` (CLIPS names every
  reference not in the table, on the import and on the model read back) and
  shoot it with `scripts/ocd_page_bench.mjs`.
- Any page where a block's runs interleave in paint order with another block's
  (mastheads, knockout labels on tiles) exercises the paragraph z-contiguity
  invariant; the STRADDLE gate names each straddling paragraph and the
  intruding node.

Before trusting any corpus page as a round-trip witness, run `java -cp "target/classes:lib/*" test/Gates.java the.pdf`
on it (the MODEL gate): a page with no anisotropic runs, no shared-geometry clips and no rotation
cannot exercise the cases that actually break, and will report green while proving
nothing.

## Using a corpus PDF as GT

Corpus files have no declared manifest, so derive a (looser) GT from the source
with `--gt-from-pdf`, or rely on the visual axis (PDFBox reference). The synthetic
matrix remains the place for exact, single-feature assertions.
