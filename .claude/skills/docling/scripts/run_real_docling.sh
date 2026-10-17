#!/usr/bin/env bash
# Run the REAL docling pipeline (layout model + TableFormer) on a machine with
# network access, producing the three artefacts the comparison gates consume.
#
#   bash run_real_docling.sh <pdf-or-dir> [out-dir]
#
# Produces, per input <name>.pdf, in <out-dir>:
#   <name>.doctags   - the reference DocTags (what jexter's DocTags is compared against)
#   <name>.json      - the full DoclingDocument (labels, prov bboxes, page sizes)
#   <name>.md        - docling's own Markdown projection
# Zip <out-dir> and drop it in the sandbox next to jexter's outputs.
set -euo pipefail
IN="${1:?pdf file or directory}"
OUT="${2:-docling-out}"
mkdir -p "$OUT"

python3 -c "import docling" 2>/dev/null || pip install docling

# Pre-fetch the weights once (layout, tableformer, code/formula) so batch runs are offline.
ART="${DOCLING_ARTIFACTS_PATH:-$HOME/.cache/docling/models}"
[ -d "$ART" ] || docling-tools models download --output-dir "$ART"

# --no-ocr: born-digital PDFs only (OCR would re-read the page raster and the text
#           comparison would then measure the OCR engine, not docling).
# --pdf-backend docling_parse: the same parser scripts/parse_text.py runs in the sandbox,
#           so the text axis is apples to apples.
docling convert "$IN" \
  --to doctags --to json --to md \
  --pipeline standard \
  --pdf-backend docling_parse \
  --no-ocr \
  --table-mode accurate \
  --artifacts-path "$ART" \
  --output "$OUT" \
  --abort-on-error

ls -la "$OUT"
