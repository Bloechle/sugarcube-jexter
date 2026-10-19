#!/usr/bin/env python3
"""make_bench_fixtures.py — the documents the Chromium scenarios are measured against.

Every scenario under scripts/bench/ opens one of these. They are SMALL and each exists to make ONE
thing checkable; a wild corpus proves nothing a fixture cannot here, and takes a minute to convert.

    pip install --break-system-packages reportlab pillow
    python3 make_bench_fixtures.py /tmp/fix
    for f in /tmp/fix/*.pdf; do java -cp "$CP" sugarcube.jexter.Conversion \\
                                     "$f" "${f%.pdf}.ocd.epub"; done

  dossier   an ordinary 3-page document — the neutral one, and the false-positive control
  hidden    FOUR ways of hiding text on one page: under a box, white on white, transparent, and
            invisible render mode (Tr 3). The fifth the tool knows — a run whose glyphs are ALL
            inkless — needs a font with blank outlines and is not built here; `reveal.mjs` therefore
            expects four on this fixture, and the no-glyph path is covered by its own case in the
            engine gates
  scan      a page that is ONE image, with a black box painted over a personal number
  turned    /Rotate 90 and 270 — the pages a naive geometry gets wrong
  banner    white on a dark banner (VISIBLE) and black on a black band (hidden): the pair that says
            a leak detector must compare ink to its BACKDROP, never to white
  heavy     190 runs plus a banner — the only page big enough to measure a per-run cost on
"""
import os
import sys

from PIL import Image, ImageDraw
from reportlab.lib.colors import black, white
from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas

OUT = sys.argv[1] if len(sys.argv) > 1 else '/tmp/fix'
W, H = A4

ROWS = [("Dossier 2026-4417 — confidential", 22),
        ("Name: Jean Dupont", 13),
        ("AVS: 756.1234.5678.97", 13),
        ("IBAN: CH93 0076 2011 6238 5295 7", 13),
        ("Email: jean.dupont@example.ch", 13),
        ("Phone: +41 26 300 00 00", 13)]


def at(name):
    return os.path.join(OUT, name)


def dossier():
    """Three ordinary pages. Every 'does this tool invent leaks?' run is against this one."""
    c = canvas.Canvas(at('dossier.pdf'), pagesize=A4, invariant=1)
    for page in range(3):
        y = 780
        for text, size in ROWS:
            c.setFont("Helvetica-Bold" if size > 15 else "Helvetica", size)
            c.drawString(60, y, text if size > 15 else f"{text}   (page {page + 1})")
            y -= 34
        c.setFont("Helvetica", 11)
        for i in range(12):
            c.drawString(60, y, f"Line {i + 1} of body text on page {page + 1}, ordinary content that must survive.")
            y -= 16
        c.showPage()
    c.save()


def hidden():
    """Four hiding techniques, one page. The order matters: the invisible run goes LAST because the
    text render mode is content-stream STATE — reportlab does not re-emit `0 Tr` on a text object it
    believes is already at 0, so a `3 Tr` leaks into every later drawString."""
    c = canvas.Canvas(at('hidden.pdf'), pagesize=A4, invariant=1)
    c.setFont("Helvetica-Bold", 22)
    c.drawString(60, 780, "Dossier 2026-4417 — confidential")

    c.setFont("Helvetica", 13)
    c.drawString(60, 730, "VISIBLE: ordinary text, nothing to hide here.")

    # 1 · under a box: painted first, covered second — the fake redaction
    c.drawString(60, 700, "UNDER-BOX: Jean Dupont, 756.1234.5678.97")
    c.setFillColor(black)
    c.rect(55, 694, 320, 20, stroke=0, fill=1)

    # 2 · white on white paper
    c.setFillColor(white)
    c.drawString(60, 660, "WHITE: account CH93 0076 2011 6238 5295 7")

    # 3 · transparent fill
    c.setFillColor(black)
    c.setFillAlpha(0)
    c.drawString(60, 630, "TRANSPARENT: salary 128000 CHF")
    c.setFillAlpha(1)

    y = 590
    for i in range(12):
        c.drawString(60, y, f"Line {i + 1} of body text on page 1, ordinary content that must survive.")
        y -= 16

    # 4 · invisible render mode — an OCR layer is exactly this, and it goes last (see the docstring)
    c.setFillColor(black)
    t = c.beginText(60, 400)
    t.setTextRenderMode(3)
    t.textLine("INVISIBLE: passport X1234567, render mode 3")
    c.drawText(t)
    c.showPage()
    c.save()


def scan():
    """A page that is ONE image, with a box painted over a number — the shape a scanned dossier has,
    and the case where redaction has to repair PIXELS rather than remove a node."""
    img = Image.new('RGB', (1240, 1754), 'white')
    d = ImageDraw.Draw(img)
    d.text((120, 200), "SCANNED DOSSIER 2026-4417", fill='black')
    d.text((120, 260), "Name: Jean Dupont", fill='black')
    d.text((120, 300), "Personal number: 756.1234.5678.97", fill='black')
    for i in range(10):
        d.text((120, 380 + i * 34), f"Line {i + 1} of scanned body text.", fill='black')
    d.rectangle([115, 292, 620, 320], fill='black')          # the box over the number
    img.save(at('_scan.png'))
    c = canvas.Canvas(at('scan.pdf'), pagesize=A4, invariant=1)
    c.drawImage(at('_scan.png'), 0, 0, width=W, height=H)
    c.showPage()
    c.save()
    os.remove(at('_scan.png'))


def turned():
    """/Rotate 90 and 270 — the page geometry that a hand-rolled mapping gets wrong."""
    c = canvas.Canvas(at('turned.pdf'), pagesize=A4, invariant=1)
    for rot in (90, 270):
        y = 780
        for text, size in ROWS:
            c.setFont("Helvetica-Bold" if size > 15 else "Helvetica", size)
            c.drawString(60, y, text)
            y -= 34
        c.setPageRotation(rot)
        c.showPage()
    c.save()


def banner():
    """White ink is not hidden ink, and black ink is not safe ink. This page carries both, and it is
    the fixture that refused the 'is the fill white' test: CONFIDENTIAL on a dark banner is ordinary
    design, while black on a black band is a leak the colour test could not even ask about."""
    c = canvas.Canvas(at('banner.pdf'), pagesize=A4, invariant=1)
    c.setFillColor(black)
    c.rect(50, 760, 500, 40, stroke=0, fill=1)               # the banner, painted FIRST
    c.setFillColor(white)
    c.setFont("Helvetica-Bold", 16)
    c.drawString(66, 773, "CONFIDENTIAL — Dossier 2026-4417")   # white ON it: visible

    c.setFillColor(black)
    c.setFont("Helvetica", 12)
    c.drawString(60, 720, "Ordinary body text under the banner.")
    c.setFillColor(white)
    c.drawString(60, 690, "WHITE ON PAPER: this one really is hidden.")

    c.setFillColor(black)
    c.rect(55, 645, 300, 22, stroke=0, fill=1)               # a black band, painted FIRST
    c.setFillColor(black)
    c.drawString(62, 651, "BLACK ON BLACK: also hidden, also in the file.")

    y = 610
    for i in range(7):
        c.drawString(60, y, f"Body line {i + 1} — ordinary content.")
        y -= 16
    c.showPage()
    c.save()


def heavy():
    """190 runs and a banner: the only fixture big enough to say what a per-run check costs."""
    c = canvas.Canvas(at('heavy.pdf'), pagesize=A4, invariant=1)
    c.setFillColor(black)
    c.rect(40, 800, 520, 30, stroke=0, fill=1)
    c.setFillColor(white)
    c.setFont("Helvetica-Bold", 13)
    c.drawString(50, 810, "BANNER — white on black, must not be flagged")
    c.setFillColor(black)
    c.setFont("Helvetica", 7)
    y = 780
    for i in range(190):
        c.drawString(40, y, f"Line {i + 1:03d} — ordinary body content, repeated to make the page heavy. " * 2)
        y -= 4
        if y < 30:
            break
    c.setFillColor(white)
    c.drawString(40, 20, "HIDDEN: white on paper at the foot of a heavy page.")
    c.showPage()
    c.save()


if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    for fn in (dossier, hidden, scan, turned, banner, heavy):
        fn()
        print(f"  {fn.__name__}.pdf")
    print(f"→ {OUT}")
