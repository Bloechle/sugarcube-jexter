---
name: docling
description: Run and benchmark IBM's docling (the document-to-DoclingDocument converter behind the DocTags format) and use it as the reference for any DocTags producer — jexter's DocTags/Markdown writers in particular. ALWAYS use this skill when Jean-Luc mentions docling, DocTags, doctags, OTSL, DoclingDocument, docling-parse, docling-core, "le vrai docling", comparing jexter's structure/text/Markdown against docling, validating that a .doctags file loads in docling, or wants the interop / text / structure gates run on a PDF corpus — even if he doesn't say "skill". Covers the sandbox install (PyPI ok, huggingface blocked, disk quota), what runs offline (docling-core loader, docling-parse) vs what needs his machine (the layout-model pipeline), the DocTags grammar as the loader actually parses it, and the three comparison gates with their scripts.
---

# docling — reference converter and DocTags authority

docling (IBM, `pip install docling`) converts PDF/DOCX/HTML/… into one model,
`DoclingDocument` (package `docling-core`), and serialises it as Markdown, JSON,
HTML or **DocTags** — the tag language its VLM emits and its loader consumes.
When jexter (or anything) writes DocTags, docling-core's loader is the judge of
what a consumer will see; when jexter's analysis is benchmarked, docling's
layout-model output is the reference structure to compare against.

Three things this skill does, in the order they usually happen:

1. **Set up** — `bash scripts/setup.sh light` (docling-core + docling-parse, seconds)
   or `bash scripts/setup.sh` (full, ~4 GB, only if the CLI itself is wanted).
   Read `references/sandbox.md` before spending a turn on anything that fails:
   PyPI works, **huggingface.co is blocked**, so the layout/table models never
   download here and `DocumentConverter` / `docling convert` cannot run in the
   sandbox. Say that once, plainly, and move to what does run.
2. **Judge a DocTags file** — `references/doctags-grammar.md` is the loader's
   real grammar (verified by execution, docling-core 2.92): root tags accepted,
   loc grid 0–500 top-left, OTSL cells, and the tags it **drops silently**
   (`<paragraph>`, `<reference>`, unclosed roots). Read it before touching a
   DocTags writer, before hand-writing a fixture, and whenever a block "disappears".
3. **Run the gates** — `references/gates.md` explains each number.
4. **OCD-AI** — jexter's LLM-facing projection is a strict superset of DocTags
   (`references/ocdai.md`): docling drops its attribute tokens and extension roots
   by design, an OCD reader keeps them. `doctags_interop.py` recognises the
   `<ocdai_N>` marker and adds the id/page-token checks; a plain DocTags file is
   judged exactly as before.

## The gates

```bash
S=<skill>/scripts
# 1 · interop: does the candidate's DocTags load intact? (offline)
python3 $S/doctags_interop.py out/doc.doctags --pdf doc.pdf --out out/docling   # rc 1 = dropped/unclosed
# 2 · text: characters lost / invented vs IBM's own PDF parser (offline)
python3 $S/parse_text.py doc.pdf --out out/doc.parse.json
python3 $S/text_fid.py out/doc.parse.json out/doc.doctags                      # or .md / .txt
# 3 · structure: blocks + labels + order vs the real docling (needs its output)
bash   $S/run_real_docling.sh corpus/ docling-out/     # on Jean-Luc's machine, drop the zip here
python3 $S/struct_compare.py docling-out/doc.doctags out/doc.doctags
```

Gates 1 and 2 run on every PDF with nothing but the sandbox; gate 3 needs the
reference DocTags from a networked machine. Ask for them once, with the exact
command; meanwhile deliver gates 1 and 2 — they already catch writer errors
(dropped tags, lost text) that no amount of layout comparison would.

All scripts accept `--json` for corpus-wide aggregation; wrap them in a loop and
tabulate one line per document rather than pasting twelve reports.

## Reading the result honestly

- docling is the **format authority**, not the truth. Its layout model labels by
  visual class; on Fedlex-style legal text it will call bold lead-ins headings.
  A disagreement in gate 3 is a question to settle by opening the page, and the
  report must say which side was right — Jean-Luc pushes back hard on
  scoreboards that flatter either engine.
- Over-segmentation (`split`) is the safe bias; under-segmentation (`merged`) is
  the defect. Keep that asymmetry in the wording.
- docling-parse has its own misreads (a ligature dropped on an encoding edge —
  sandbox.md). A character the candidate reads and the reference misses is a
  candidate win; record it, do not "align" to the reference.
- Never claim a jexter fix on the strength of a gate going green without the
  jexter skill's own gates (byte-stability, Fid2=0) — this skill measures
  docling-compatibility, nothing about rendering.

## Using docling-core directly (when a script does not fit)

```python
from docling_core.types.doc.document import DoclingDocument, DocTagsDocument
raw = open("doc.doctags", encoding="utf-8").read()
n = raw.count("<page_break>") + 1
dtd = DocTagsDocument.from_multipage_doctags_and_images(raw, [None] * n)   # split on <page_break>
doc = DoclingDocument.load_from_doctags(dtd, document_name="doc")
doc.export_to_markdown(); doc.export_to_dict(); doc.export_to_doctags(minified=True)
for item, level in doc.iterate_items(): item.label, item.text, item.prov
```

```python
from docling_parse.pdf_parser import DoclingPdfParser
from docling_core.types.doc.page import TextCellUnit
pdf = DoclingPdfParser().load(path_or_stream="doc.pdf")
for pno, page in pdf.iterate_pages():
    for c in page.iterate_cells(unit_type=TextCellUnit.LINE): c.text, c.rect, c.font_name
```

## Conventions

DRY/KISS; English in code. This skill lives in the jexter repo at `.claude/skills/docling/`
since 2026-10-17 and is updated BY COMMIT, like the jexter skill beside it. Send gate reports and
rendered comparisons to Jean-Luc as files; the scripts and references below travel by commit.

```
references/
  doctags-grammar.md  — the loader's real grammar (root tags, loc, OTSL, what it drops)
  sandbox.md          — install facts, blocked hosts, disk quota, API gotchas
  gates.md            — how to read interop / text / structure numbers, how to report
  ocdai.md            — OCD-AI v1: jexter's DocTags superset (ids, typography, colour, edit channel)
scripts/
  setup.sh            — pip install (light | full), quota-safe, checks HF reachability
  run_real_docling.sh — the ML pipeline on a networked machine → doctags/json/md per PDF
  doctags_interop.py  — gate 1: load through docling-core, dropped/unclosed/loss, docling's md/json/re-export
  parse_text.py       — gate 2a: docling-parse cell dump (line/word/char) as JSON
  text_fid.py         — gate 2b: chars lost/invented + line recall between two readings
  struct_compare.py   — gate 3: 1:1/split/merged/only, label confusion, order inversions
```
