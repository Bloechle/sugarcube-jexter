# docling in the sandbox — measured facts (2026-08)

## What installs and runs

| package | version seen | runs here | what it gives |
|---|---|---|---|
| `docling-core` | 2.92.0 | yes | `DoclingDocument`, the DocTags loader/exporter, md/json/html projections |
| `docling-parse` | 7.16.0 | yes | IBM's low-level PDF parser (chars/words/lines with bbox + font), no ML |
| `docling` | 2.123.0 | CLI installs, **pipeline does not run** | `docling convert`, `DocumentConverter` — needs model weights |
| `docling-ibm-models` | 3.14.0 | code only | layout (RT-DETR "heron"), TableFormer, code/formula models |

PyPI is an allowed host, so `pip install` works. Two traps:

1. **Disk quota.** The first `pip install docling` died with `ENOSPC` — the PyPI
   `torch` wheel is the CUDA build and drags ~2.5 GB of `nvidia-*` libraries. Fix:
   `pip cache purge`, then `pip install --no-cache-dir`. After install ~5 GB free
   went to ~5.2 GB used; budget 4 GB. `bash scripts/setup.sh light` skips torch
   entirely when only docling-core + docling-parse are needed (the usual case).
2. **huggingface.co is not an allowed egress host** (`x-deny-reason:
   host_not_allowed`). `docling-tools models download` fails with 403; the
   `DocumentConverter` fails the same way on first use. No GitHub release of
   `docling-ibm-models`, `docling`, or `docling-layout-heron` carries the weights
   (checked via api.github.com), so there is no allowed mirror. Do not spend
   turns on this — send the user `scripts/run_real_docling.sh` instead.

## API gotchas hit while wiring the scripts

- `SegmentedPage.iterate_cells(unit_type=TextCellUnit.LINE)` — `unit_type` is
  required (CHAR / WORD / LINE, from `docling_core.types.doc.page`).
- `DoclingPdfParser().load(path_or_stream=…)` returns a `PdfDocument`;
  `iterate_pages()` yields `(page_no, SegmentedPage)`, 1-based.
- Cell rects are `r_x0, r_y0, r_x2, r_y2` (four corners; r_x1/r_y1 exist too),
  page coordinates as the parser reports them.
- `DoclingDocument.validate_tree(root)` needs a root; `export_to_dict()` is the
  cheap "did it load" check.
- docling-parse dropped a `ﬁ` ligature drawn by reportlab in Helvetica (WinAnsi
  has no ﬁ) and emitted an orphan `n` cell — a parser quirk on an encoding edge,
  visible in `text_fid.py` as lost `n`/invented `ﬁ`. Know it before blaming the
  candidate.

## On the user's machine

`pip install docling` (CPU torch is fine), first run downloads ~1 GB of weights
to `~/.cache/huggingface`; pin them with `docling-tools models download` and
`--artifacts-path` for reproducible batches. `--no-ocr` on born-digital PDFs,
`--pdf-backend docling_parse` so the text axis matches the sandbox parser.
Outputs: `--to doctags --to json --to md` per input file.
