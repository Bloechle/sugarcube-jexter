#!/usr/bin/env python3
"""Diagnose embedded-font glyph SCALE bugs (the #1 cause of "text looks crammed
/ glyphs overlap" in a converted page).

Method: render the SAME minimal SVG <text>, positioning each glyph with an
explicit `x` list, twice — once with a known-good system font (DejaVu Sans),
once with the embedded font under test (via @font-face url) — then measure the
rendered width of each glyph by clustering black pixels. If the embedded glyphs
are markedly WIDER than the system reference at the same font-size, the font's
outlines are at the wrong units-per-em (classic bug: divide outline coords by a
hardcoded 1000 when the program is 2048 units/em -> glyphs ~2x too wide ->
overlap, even though the SVG x-positions are correct).

It also doubles as a renderer check: the system-font run shows whether the
engine honors a multi-value SVG `x` list at all (it should land glyphs at the
requested positions).

Usage:
    glyph_probe.py --font OEBPS/fonts/Helvetica.otf --family Helvetica
    glyph_probe.py --font X.otf --family X --text ABC --positions "10 100 200"
"""
import argparse, os, subprocess, sys, tempfile
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))

PAGE = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html><html xmlns="http://www.w3.org/1999/xhtml"><head><meta charset="utf-8"/>
{style}</head><body style="margin:0">
<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="80" viewBox="0 0 {w} 80">
<text x="{xs}" y="55" font-family="{family}" font-size="{size}">{text}</text>
</svg></body></html>
"""


def render(html_str, out, width, workdir):
    f = os.path.join(workdir, "probe.xhtml")
    open(f, "w").write(html_str)
    subprocess.run([sys.executable, os.path.join(HERE, "render_html.py"),
                    f, out, "--width", str(width)], check=True)


def clusters(png):
    im = Image.open(png).convert("L")
    W, H = im.size
    px = im.load()
    cols = [x for x in range(W) if any(px[x, y] < 100 for y in range(H))]
    out, start, prev = [], None, None
    for c in cols:
        if start is None:
            start = prev = c
        elif c - prev > 8:
            out.append((start, prev)); start = c
        prev = c
    if start is not None:
        out.append((start, prev))
    return out


def main():
    ap = argparse.ArgumentParser(description="Embedded-font glyph-scale diagnostic")
    ap.add_argument("--font", required=True, help="embedded font file (.otf/.ttf)")
    ap.add_argument("--family", required=True, help="font-family name used in the SVG")
    ap.add_argument("--text", default="ABC")
    ap.add_argument("--positions", default="10 100 200", help="x per glyph")
    ap.add_argument("--size", type=int, default=30)
    a = ap.parse_args()

    width = int(float(a.positions.split()[-1])) + 100
    wd = tempfile.mkdtemp(prefix="glyph_probe_")
    sys_png = os.path.join(wd, "sys.png")
    emb_png = os.path.join(wd, "emb.png")

    render(PAGE.format(style="", w=width, xs=a.positions, family="DejaVu Sans",
                       size=a.size, text=a.text), sys_png, width, wd)
    style = ('<style>@font-face{{font-family:"PROBE";'
             'src:url("{0}") format("opentype");}}</style>').format(os.path.abspath(a.font))
    render(PAGE.format(style=style, w=width, xs=a.positions, family="PROBE",
                       size=a.size, text=a.text), emb_png, width, wd)

    sysc, embc = clusters(sys_png), clusters(emb_png)
    sysw = [b - x for x, b in sysc]
    embw = [b - x for x, b in embc]
    print(f"system  (DejaVu) clusters={sysc}  widths={sysw}")
    print(f"embedded ({a.family}) clusters={embc}  widths={embw}")

    want = [float(x) for x in a.positions.split()]
    honored = len(sysc) == len(want) and all(abs(sysc[i][0] - want[i]) < 12 for i in range(len(want)))
    print(f"\nrenderer honors multi-value x list: {'YES' if honored else 'NO — investigate renderer'}")

    if sysw and embw and len(sysw) == len(embw):
        ratios = [e / s for e, s in zip(embw, sysw) if s]
        avg = sum(ratios) / len(ratios)
        print(f"width ratio embedded/system per glyph = {[round(r,2) for r in ratios]}  (avg {avg:.2f})")
        if avg > 1.5:
            print("VERDICT: embedded glyphs are too WIDE -> outline units-per-em mismatch.")
            print("         Fix the extractor: scale outline coords by 1/unitsPerEm (the font")
            print("         matrix), NOT a hardcoded 1000. Advances/ascent stay in 1000-text-space.")
        elif avg < 0.66:
            print("VERDICT: embedded glyphs are too NARROW -> outline scaled down too far.")
        else:
            print("VERDICT: glyph scale looks correct; cramming (if any) is elsewhere.")
    else:
        print("VERDICT: cluster counts differ — inspect the PNGs directly.")


if __name__ == "__main__":
    main()
