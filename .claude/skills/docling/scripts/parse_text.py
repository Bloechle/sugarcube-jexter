#!/usr/bin/env python3
"""Text axis, docling side: dump what docling-parse (IBM's PDF parser, no ML) reads.

    python3 parse_text.py <file.pdf> [--unit line|word|char] [--out <file.json>]

Writes one record per cell: page, text, bbox (points, bottom-left origin as the
parser reports it), font. This is the SAME parser the real docling pipeline uses
(`--pdf-backend docling_parse`), so what it reads is what docling's DocTags can
at most contain. Compare against jexter with text_fid.py.
"""
import argparse, json, sys
from pathlib import Path

from docling_core.types.doc.page import TextCellUnit
from docling_parse.pdf_parser import DoclingPdfParser

UNITS = {"line": TextCellUnit.LINE, "word": TextCellUnit.WORD, "char": TextCellUnit.CHAR}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pdf", type=Path)
    ap.add_argument("--unit", choices=UNITS, default="line")
    ap.add_argument("--out", type=Path)
    a = ap.parse_args()

    doc = DoclingPdfParser().load(path_or_stream=str(a.pdf))
    cells = []
    for pno, page in doc.iterate_pages():
        for c in page.iterate_cells(unit_type=UNITS[a.unit]):
            r = c.rect
            cells.append({
                "page": pno, "text": c.text,
                "bbox": [round(r.r_x0, 2), round(r.r_y0, 2), round(r.r_x2, 2), round(r.r_y2, 2)],
                "font": getattr(c, "font_name", None),
            })
    payload = {"pdf": str(a.pdf), "unit": a.unit, "cells": cells}
    if a.out:
        a.out.write_text(json.dumps(payload, ensure_ascii=False, indent=0), encoding="utf-8")
        print(f"{a.pdf.name}: {len(cells)} {a.unit} cells over {doc.number_of_pages()} pages -> {a.out}")
    else:
        json.dump(payload, sys.stdout, ensure_ascii=False)


if __name__ == "__main__":
    main()
