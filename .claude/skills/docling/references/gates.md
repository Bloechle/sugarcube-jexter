# The three gates — what each number means and what to do with it

The engine under test (jexter or any other DocTags producer) is the **candidate**;
docling is the **reference** — not because docling is ground truth (it is a
layout model, it misreads too) but because DocTags *is docling's format*: the
loader decides what a consumer will see.

## Gate 1 · Interop — `doctags_interop.py <cand.doctags> --pdf <src.pdf> --out <dir>`

Runs without the ML models. Green means: every element the writer emitted reaches
a `DoclingDocument` with its text, and docling's own re-export is structurally
the same file.

| line | red when | cause on the writer side |
|---|---|---|
| `DROPPED tags` | non-empty | a tag outside the loader's root set (`<paragraph>`, `<reference>`, custom tags) — see doctags-grammar.md |
| `UNCLOSED tags` | non-empty | writer wrote `<x>` without `</x>`; the loader keeps an empty item and drops the text |
| `text loss` | > 0 % | sum of the two above, plus text placed outside any root element (between chunks) |
| `re-export changed` | > 0 | usually `<loc_500>` → 499 clamps, or list wrappers reflowed; inspect the `.docling.doctags` diff before caring |
| `items {…}` | labels you did not intend | e.g. `text` where `section_header` was meant — the writer chose the wrong tag name |

The `.docling.md` written next to it is docling's Markdown for the candidate's
structure: diff it against the candidate's own Markdown to see where the two
projections disagree on *rendering*, independent of analysis.

## Gate 2 · Text — `parse_text.py <src.pdf> --out p.json` then `text_fid.py p.json <cand.(doctags|md|txt)>`

Runs without the ML models. docling-parse is the parser behind the real pipeline
(`--pdf-backend docling_parse`), so this is exactly the character stream docling's
DocTags can at most contain.

- `lost` = characters the reference read that the candidate did not; `invented`
  the reverse. Whitespace is ignored on both sides; so are tags and Markdown
  syntax. A symmetric pair (`lost 'ﬁ'` / `invented 'f','i'`) is a **policy
  difference** (ligature expansion), not a defect. Asymmetric loss (`'€'`, `'•'`,
  accented letters) is a defect on one side — check which by opening the PDF.
- `line recall` = share of reference lines found verbatim (whitespace-free) in the
  candidate. Missing lines are listed: that is the first place to look.
- The reference itself can be wrong (see sandbox.md: a docling-parse ligature
  drop). When the candidate reads a character docling-parse missed, that is a win
  for the candidate — record it as such, do not "fix" it.

## Gate 3 · Structure — `struct_compare.py <ref.doctags> <cand.doctags>`

Needs the real docling output (`scripts/run_real_docling.sh` on a networked
machine). Both files are loaded through docling-core so the same grammar judges
both, then blocks are matched on whitespace-free text.

- `1:1` blocks are the only ones where **label agreement** is measured. The
  confusion line (`section_header->text×12`) is the actionable output: it names
  the label the candidate under-recognises.
- `split` (one reference block → several candidate blocks) is over-segmentation:
  the safe bias for a heuristic engine; a downstream consumer can merge, it
  cannot un-merge. Report it, do not chase it unless it is systematic (every
  paragraph split at every line → the paragrapher is off).
- `merged` (several reference blocks → one candidate block) is under-segmentation:
  this is the one to fix — a heading swallowed into a paragraph, two columns
  glued.
- `ref-only` / `cand-only` after text normalisation are blocks whose text
  differs (see gate 2) or that only one side emits (page furniture, captions).
- `order inversions` counts pairs of 1:1 blocks in opposite order — reading-order
  disagreements, typically columns or floats.

Docling's layout model is itself heuristic-grade on some inputs (it labels by
visual class, so a bold first line becomes `section_header` whether or not it is
one). Disagreement is a *question*, not a verdict — open the page before deciding
which side is right, and say so in the report.

## Reporting

One table per document: gate 1 (dropped / unclosed / loss %), gate 2 (lost /
invented / recall), gate 3 (1:1 / split / merged / agreement / inversions). Then
the three or four concrete findings with a page reference each. Keep docling's
own limitations in the same table — the user needs the honest comparison, not a
scoreboard.
