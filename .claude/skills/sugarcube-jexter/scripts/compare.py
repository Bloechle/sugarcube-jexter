#!/usr/bin/env python3
"""Validate visual fidelity against a PDFBox reference render of the source PDF.

PDFBox (scripts/RenderPdf.java) is the single source of truth. The candidate is
measured against it:

    reference  = PDFBox PDFRenderer of source.pdf
    candidate  = .epub/.xhtml/.html  -> WebKit (render_html.py)
                 .png                 -> used as-is (e.g. an OCDRenderer output)
                 .ocd.epub            -> jexter OCDRenderer CLI (needs JEXTER_OUT)

Output: a labeled side-by-side PNG per page plus a printed mean per-pixel diff
(0 = identical); add --heatmap for a divergence map. Then OPEN the image and
describe concrete matches/divergences — see references/diagnosis.md.

Env:
    PDFBOX_JAR   path to pdfbox-app-3.x.jar          (required)
    JEXTER_OUT   compiled jexter classes dir          (only for .ocd.epub candidates)
    JAVA_HOME    JDK home                             (optional; else `java` on PATH)

Usage:
    compare.py source.pdf candidate.epub -o compare.png
    compare.py source.pdf candidate.png  -o compare.png --page 3
    compare.py source.pdf doc.ocd.epub   -o compare.png --all --dpi 144 --heatmap
"""
import argparse, os, subprocess, sys, tempfile
from PIL import Image, ImageChops, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
PDFBOX = os.environ.get("PDFBOX_JAR", "")
JEXTER_OUT = os.environ.get("JEXTER_OUT", "")


def java():
    jh = os.environ.get("JAVA_HOME")
    return os.path.join(jh, "bin", "java") if jh else "java"


def need_pdfbox():
    if not PDFBOX:
        sys.exit("set PDFBOX_JAR to the pdfbox-app jar (the reference renderer)")


def pdf_pages(pdf):
    need_pdfbox()
    out = subprocess.run([java(), "-cp", PDFBOX, os.path.join(HERE, "RenderPdf.java"), pdf, "count"],
                         check=True, capture_output=True, text=True)
    return int(out.stdout.strip())


def render_reference(pdf, out, page, dpi):
    need_pdfbox()
    subprocess.run([java(), "-cp", PDFBOX, os.path.join(HERE, "RenderPdf.java"),
                    pdf, str(page), out, str(dpi)], check=True)


def render_candidate(path, out, ref_w, page, dpi):
    low = path.lower()
    ext = os.path.splitext(path)[1].lower()
    # the OCD-EPUB is a compound suffix: splitext() sees only ".epub", which would
    # route the model container to WebKit and silently measure the WRONG surface.
    if low.endswith(".ocd.epub"):
        ext = ".ocd"
    if ext == ".png":
        Image.open(path).convert("RGBA").save(out)
    elif ext == ".ocd":
        cp = PDFBOX + ((os.pathsep + JEXTER_OUT) if JEXTER_OUT else "")
        subprocess.run([java(), "-cp", cp, os.path.join(HERE, "RenderOcd.java"),
                        path, str(page), out, str(dpi)], check=True)
    else:                                  # .epub / .xhtml / .html -> WebKit
        subprocess.run([sys.executable, os.path.join(HERE, "render_html.py"),
                        path, out, "--width", str(ref_w), "--page", str(page)], check=True)


def labeled(im, title, W):
    if im.width != W:
        im = im.resize((W, int(im.height * W / im.width)))
    bar = 28
    c = Image.new("RGB", (W, im.height + bar), "white")
    c.paste(im.convert("RGB"), (0, bar))
    d = ImageDraw.Draw(c)
    d.rectangle([0, 0, W, bar], fill=(40, 40, 48))
    d.text((8, 8), title, fill="white")
    return c


def mean_diff(a, b):
    if b.size != a.size:
        b = b.resize(a.size)
    da = ImageChops.difference(a.convert("RGB"), b.convert("RGB"))
    hist = da.histogram()                  # 3 * 256 (R, G, B)
    total = sum(v * hist[ch * 256 + v] for ch in range(3) for v in range(256))
    return total / (255.0 * a.width * a.height * 3)


def heatmap(a, b):
    if b.size != a.size:
        b = b.resize(a.size)
    da = ImageChops.difference(a.convert("RGB"), b.convert("RGB")).convert("L")
    return da.point(lambda v: min(255, v * 3))


def compose(ref_png, cand_png, out, W, want_heat):
    ref = Image.open(ref_png).convert("RGB")
    cand = Image.open(cand_png).convert("RGB")
    score = mean_diff(ref, cand)
    panels = [labeled(ref, "REFERENCE  (PDFBox PDFRenderer)", W),
              labeled(cand, f"CANDIDATE   (diff={score:.4f})", W)]
    if want_heat:
        panels.append(labeled(heatmap(ref, cand), "DIVERGENCE  (hotter = bigger)", W))
    gap = 16
    H = max(p.height for p in panels)
    comp = Image.new("RGB", (W * len(panels) + gap * (len(panels) - 1), H), (220, 220, 225))
    x = 0
    for p in panels:
        comp.paste(p, (x, 0))
        x += W + gap
    comp.save(out)
    return score


def main():
    ap = argparse.ArgumentParser(description="PDFBox-reference visual fidelity check")
    ap.add_argument("source_pdf")
    ap.add_argument("candidate", help=".epub / .xhtml / .html / .png / .ocd.epub")
    ap.add_argument("-o", "--out", default="compare.png")
    ap.add_argument("--page", type=int, default=1)
    ap.add_argument("--all", action="store_true", help="compare every PDF page")
    ap.add_argument("--dpi", type=float, default=144.0)
    ap.add_argument("--heatmap", action="store_true", help="add a divergence-map panel")
    a = ap.parse_args()

    pages = range(1, pdf_pages(a.source_pdf) + 1) if a.all else [a.page]
    tmp = tempfile.mkdtemp(prefix="fidelity_")
    for pg in pages:
        ref = f"{tmp}/ref_{pg}.png"
        cand = f"{tmp}/cand_{pg}.png"
        render_reference(a.source_pdf, ref, pg, a.dpi)
        W = Image.open(ref).width                       # ref width at --dpi → panel width
        # WebKit (wkhtmltoimage) ignores --zoom: it renders 1 CSS px = 1 px, i.e. at the
        # page's POINT width. Passing the high-dpi ref width (e.g. 1224 for a 612 pt page at
        # 144 dpi) makes the page render at ~half scale inside a too-wide canvas. Pass the CSS
        # page width (= ref width at 72 dpi); compose()/mean_diff upscale it to W for the panel.
        css_w = max(1, round(W * 72.0 / a.dpi))
        render_candidate(a.candidate, cand, css_w, pg, a.dpi)
        out = a.out if len(list(pages)) == 1 else a.out.replace(".png", f"_p{pg:03d}.png")
        score = compose(ref, cand, out, W, a.heatmap)
        print(f"wrote {out}  (mean per-pixel diff {score:.4f})")


if __name__ == "__main__":
    main()
