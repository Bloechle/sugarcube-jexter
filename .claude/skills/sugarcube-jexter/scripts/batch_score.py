#!/usr/bin/env python3
"""batch_score.py — a customer batch, page by page: source vs the DELIVERED output vs a NEW build,
under two independent engines (poppler and MuPDF). PDFBox is deliberately absent: it is our parser
AND our reference, and it ignores two things a customer's reader honours (a page's CMYK blending
space, an anisotropic pen) — so it agrees with us exactly where we are wrong.

  batch_score.py --src DIR --old DIR --new DIR [--old-suffix -OUT] [--pages DOC:1,14 DOC:3 ...] [--all]

  --src   the customer's PDFs (DOC.pdf)       --old  the delivered outputs (DOC<suffix>.pdf)
  --new   the new build's outputs (DOC.pdf)   --pages the reported pages (1-based); --all sweeps
          every page at low dpi and lists the pages the new build made WORSE (> +0.05 pt).
Score = % of pixels whose max channel differs by > 32 from the source render of the same engine.
"""
import argparse, os, subprocess
import numpy as np, pymupdf
from PIL import Image

def pop(f, p, dpi):
    subprocess.run(['pdftoppm', '-r', str(dpi), '-f', str(p), '-l', str(p), '-png', '-singlefile', f, '/tmp/_bs'],
                   stderr=subprocess.DEVNULL)
    return np.asarray(Image.open('/tmp/_bs.png').convert('RGB')).astype(int)

def mu(f, p, dpi):
    pymupdf.open(f)[p - 1].get_pixmap(dpi=dpi).save('/tmp/_bm.png')
    return np.asarray(Image.open('/tmp/_bm.png').convert('RGB')).astype(int)

def score(a, b):
    h, w = min(a.shape[0], b.shape[0]), min(a.shape[1], b.shape[1])
    return (np.abs(a[:h, :w] - b[:h, :w]).max(2) > 32).mean() * 100

ap = argparse.ArgumentParser()
ap.add_argument('--src', required=True); ap.add_argument('--old', required=True); ap.add_argument('--new', required=True)
ap.add_argument('--old-suffix', default='-OUT'); ap.add_argument('--pages', nargs='*', default=[]); ap.add_argument('--all', action='store_true')
a = ap.parse_args()
S = lambda d: os.path.join(a.src, d + '.pdf'); O = lambda d: os.path.join(a.old, d + a.old_suffix + '.pdf'); N = lambda d: os.path.join(a.new, d + '.pdf')

if a.pages:
    print(f"{'doc':28} {'p':>3}   poppler old -> new     MuPDF old -> new")
    for spec in a.pages:
        d, ps = spec.split(':')
        for p in map(int, ps.split(',')):
            x, y = pop(S(d), p, 50), mu(S(d), p, 50)
            print(f"{d:28} {p:>3}   {score(x, pop(O(d), p, 50)):6.2f} -> {score(x, pop(N(d), p, 50)):6.2f}"
                  f"     {score(y, mu(O(d), p, 50)):6.2f} -> {score(y, mu(N(d), p, 50)):6.2f}")
if a.all:
    docs = sorted(f[:-4] for f in os.listdir(a.src) if f.endswith('.pdf') and not f[:-4].endswith(a.old_suffix))
    so = sn = n = 0; worse = []
    for d in docs:
        for p in range(1, len(pymupdf.open(S(d))) + 1):
            y = mu(S(d), p, 30); o, w = score(y, mu(O(d), p, 30)), score(y, mu(N(d), p, 30))
            so += o; sn += w; n += 1
            if w > o + 0.05: worse.append((d, p, round(o, 2), round(w, 2)))
    print(f"{n} pages  mean MuPDF diff  old {so / n:.3f}%  ->  new {sn / n:.3f}%")
    print("worse by > 0.05 pt:", worse or "none")
