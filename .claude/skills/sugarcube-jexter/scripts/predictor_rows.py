#!/usr/bin/env python3
"""Count the PNG-predictor row filters of every Flate image (and soft mask) in a PDF.

    python3 -I predictor_rows.py out.pdf [more.pdf ...]

Exit 1 when any row uses Up, Average or Paeth. PdfWriter writes Sub on every row on purpose
(write/PdfImages): a downstream converter reads Paeth wrong — its decoder takes the upper-left
neighbour as 0 at byte index == bpp — and paints flat colours as red-channel stripes. Acrobat,
PDFBox, poppler and MuPDF all read Paeth correctly, so no pixel gate can see this; only the row
filters can. See SKILL.md, "A downstream reader is a reference engine too".
"""
import sys, zlib, collections
import pikepdf

NAMES = {0: 'None', 1: 'Sub', 2: 'Up', 3: 'Average', 4: 'Paeth'}

def rows(x):
    dp = x.get('/DecodeParms')
    if str(x.get('/Filter')) != '/FlateDecode' or dp is None or int(dp.get('/Predictor', 1)) < 10:
        return collections.Counter()
    n = int(dp.get('/Colors', 1)); bpc = int(dp.get('/BitsPerComponent', 8)); w = int(dp.get('/Columns', x.Width))
    stride = (w * n * bpc + 7) // 8 + 1
    data = zlib.decompress(x.read_raw_bytes())
    return collections.Counter(data[r * stride] for r in range(len(data) // stride))

bad = False
for path in sys.argv[1:]:
    pdf = pikepdf.open(path); total = collections.Counter(); seen = set()
    for page in pdf.pages:
        for _, x in page.obj.Resources.get('/XObject', {}).items():
            if x.get('/Subtype') != '/Image' or x.objgen in seen: continue
            seen.add(x.objgen); total += rows(x)
            if '/SMask' in x: total += rows(x.SMask)
    risky = sum(v for k, v in total.items() if k in (2, 3, 4))
    bad |= risky > 0
    print(f"{path}: {', '.join(f'{NAMES.get(k, k)} {v}' for k, v in sorted(total.items())) or 'no predicted images'}"
          f"{'  <- Up/Average/Paeth rows' if risky else ''}")
sys.exit(1 if bad else 0)
