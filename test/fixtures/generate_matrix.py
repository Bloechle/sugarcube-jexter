#!/usr/bin/env python3
"""
generate_matrix.py — Synthetic PDF feature-matrix generator for testing PDF
tools (parsers, extractors, converters such as jexter / PDF->OCD/EPUB).

Each feature is ONE single-page PDF that isolates exactly one trait, plus a
machine-readable ground truth in manifest.json. Deterministic output
(reportlab invariant=1 + fixed metadata) so diffs are stable across runs.

Usage
-----
    python generate_matrix.py --list                 # list all features
    python generate_matrix.py --all                  # build the whole matrix
    python generate_matrix.py --feature color_cmyk    # build just one
    python generate_matrix.py --category fonts        # build a category
    python generate_matrix.py --all --out out/synthetic

The on-the-fly entry point for other code:
    from generate_matrix import build_one, FEATURES
    expected = build_one("color_cmyk", "x.pdf")
"""
from __future__ import annotations
import argparse, json, os, sys, tempfile, datetime
from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import A4, LETTER, A5
from reportlab.lib.units import mm
from reportlab.lib.colors import Color, CMYKColor, HexColor
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont

# ----------------------------------------------------------------------------
# constants / shared assets
# ----------------------------------------------------------------------------
GEN_VERSION = "1.0.0"
A4W, A4H = A4
# Each font is looked up where each OS keeps it, first hit wins. The matrix needs a TrueType (glyf)
# Latin sans and serif, and a glyf CJK font: ReportLab embeds that as CIDFontType2 (Noto Sans CJK is
# CFF-outlined, which ReportLab's TTFont cannot embed). The outlines differ per OS; what each feature
# tests does not. Not found = that font's features fail the run (never skipped in silence).
_WIN = os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Fonts")
FONTS = {
    "DejaVu":      ["/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "/Library/Fonts/Arial.ttf",
                    "/System/Library/Fonts/Supplemental/Arial.ttf", os.path.join(_WIN, "DejaVuSans.ttf"),
                    os.path.join(_WIN, "arial.ttf")],
    "DejaVuSerif": ["/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf", "/System/Library/Fonts/Supplemental/Times New Roman.ttf",
                    os.path.join(_WIN, "DejaVuSerif.ttf"), os.path.join(_WIN, "times.ttf")],
    "CJK":         ["/usr/share/fonts/truetype/fonts-japanese-gothic.ttf",
                    os.path.join(_WIN, "msgothic.ttc")],
}

_FONTS_READY = False
def _ensure_fonts():
    global _FONTS_READY
    if _FONTS_READY:
        return
    for name, paths in FONTS.items():
        if name in pdfmetrics.getRegisteredFontNames():
            continue
        path = next((p for p in paths if os.path.exists(p)), None)
        if path is None:
            print(f"  [warn] font '{name}' not found in: {', '.join(paths)}", file=sys.stderr)
            continue
        # a .ttc collection (MS Gothic): its first face
        pdfmetrics.registerFont(TTFont(name, path, subfontIndex=0) if path.endswith(".ttc") else TTFont(name, path))
    _FONTS_READY = True

_ASSET_DIR = None
def _assets():
    """Generate the raster/vector assets once (RGB jpg, alpha png, cmyk jpg,
    1-bit png, an SVG). Returns dict of paths."""
    global _ASSET_DIR
    if _ASSET_DIR:
        return _ASSET_DIR
    from PIL import Image, ImageDraw
    d = tempfile.mkdtemp(prefix="pdfmatrix_assets_")
    # RGB gradient JPEG (DCTDecode / DeviceRGB)
    rgb = Image.new("RGB", (256, 160))
    for y in range(160):
        for x in range(256):
            rgb.putpixel((x, y), (x, int(y * 1.6) % 256, (x + y) % 256))
    rgb.save(os.path.join(d, "rgb.jpg"), "JPEG", quality=88)
    # PNG with alpha (SMask)
    rgba = Image.new("RGBA", (160, 160), (0, 0, 0, 0))
    dr = ImageDraw.Draw(rgba)
    dr.ellipse([10, 10, 150, 150], fill=(220, 40, 40, 200))
    dr.rectangle([60, 60, 150, 150], fill=(40, 80, 220, 140))
    rgba.save(os.path.join(d, "alpha.png"), "PNG")
    # CMYK JPEG (DCTDecode / DeviceCMYK)
    cmyk = Image.new("CMYK", (200, 130))
    for y in range(130):
        for x in range(200):
            cmyk.putpixel((x, y), (x % 256, (130 - y) * 2 % 256, (x + y) % 256, 20))
    cmyk.save(os.path.join(d, "cmyk.jpg"), "JPEG", quality=90)
    # 1-bit bilevel checker (ImageMask-friendly)
    bw = Image.new("1", (120, 120), 1)
    for y in range(120):
        for x in range(120):
            if (x // 12 + y // 12) % 2 == 0:
                bw.putpixel((x, y), 0)
    bw.save(os.path.join(d, "bilevel.png"), "PNG")
    # SVG (vector -> should convert to path/line ops, not a raster image)
    with open(os.path.join(d, "vector.svg"), "w") as f:
        f.write(
            '<svg xmlns="http://www.w3.org/2000/svg" width="180" height="120" '
            'viewBox="0 0 180 120">'
            '<rect x="2" y="2" width="176" height="116" fill="#eef" stroke="#229" stroke-width="2"/>'
            '<circle cx="50" cy="60" r="35" fill="#e63"/>'
            '<polygon points="110,20 160,100 100,100" fill="#2a8" stroke="#063" stroke-width="3"/>'
            '<path d="M120 30 Q150 60 120 90" fill="none" stroke="#306" stroke-width="4"/>'
            "</svg>"
        )
    _ASSET_DIR = {
        "dir": d,
        "rgb": os.path.join(d, "rgb.jpg"),
        "alpha": os.path.join(d, "alpha.png"),
        "cmyk": os.path.join(d, "cmyk.jpg"),
        "bilevel": os.path.join(d, "bilevel.png"),
        "svg": os.path.join(d, "vector.svg"),
    }
    return _ASSET_DIR

# ----------------------------------------------------------------------------
# drawing helpers
# ----------------------------------------------------------------------------
def _header(c, fid, title):
    """ASCII marker + title, always in an embedded TTF so it round-trips even on
    pages whose feature font may not extract cleanly. Returns marker string."""
    _ensure_fonts()
    marker = f"MATRIX::{fid}"
    c.setFillColorRGB(0, 0, 0)
    c.setFont("DejaVu", 9)
    c.drawString(20 * mm, A4H - 18 * mm, marker)
    c.setFont("DejaVuSerif", 15)
    c.drawString(20 * mm, A4H - 28 * mm, title)
    c.setStrokeColorRGB(0.7, 0.7, 0.7)
    c.line(20 * mm, A4H - 31 * mm, A4W - 20 * mm, A4H - 31 * mm)
    return marker

def _new_canvas(path, pagesize=A4):
    c = canvas.Canvas(path, pagesize=pagesize, invariant=1)
    c.setTitle("PDF Test Matrix")
    c.setAuthor("pdf-testbench")
    c.setSubject("synthetic feature isolation")
    c.setCreator("generate_matrix.py")
    return c

# ----------------------------------------------------------------------------
# FEATURE BUILDERS
# Each returns a dict merged into the manifest entry's "expected".
# Conventions in the returned dict:
#   text_contains   : list[str] substrings that MUST be text-extractable
#   fonts_embedded  : list[str] human labels of fonts that must be embedded
#   fonts_not_embedded : list[str] base-14 expected NOT embedded
#   raw_tokens      : list[str] byte tokens that must appear in the PDF stream
#   images          : list[dict] {colorspace, bpc?} expected by pdfimages
#   pages           : int (default 1)
#   notes           : str
# ----------------------------------------------------------------------------
def f_text_base14(c, W, H, A):
    m = _header(c, "text_base14", "Standard 14 fonts (not embedded)")
    y = H - 45 * mm
    for fn in ["Helvetica", "Helvetica-Bold", "Times-Roman", "Times-Italic", "Courier"]:
        c.setFont(fn, 13); c.drawString(20 * mm, y, f"{fn}: The quick brown fox 0123456789")
        y -= 9 * mm
    return {"text_contains": [m, "quick brown fox", "Times-Roman", "Courier"],
            "fonts_not_embedded": ["Helvetica", "Times", "Courier"],
            "notes": "Base-14 fonts must NOT be embedded; relies on viewer fonts."}

def f_text_embedded_ttf(c, W, H, A):
    m = _header(c, "text_embedded_ttf", "Embedded TrueType (subset)")
    c.setFont("DejaVu", 16)
    c.drawString(20 * mm, H - 50 * mm, "DejaVu Sans embedded TTF subset.")
    c.setFont("DejaVuSerif", 16)
    c.drawString(20 * mm, H - 62 * mm, "DejaVu Serif embedded TTF subset.")
    return {"text_contains": [m, "embedded TTF subset"],
            "fonts_embedded": ["DejaVu (TrueType)"],
            "notes": "Subsetted TrueType, embedded with ToUnicode CMap."}

def f_text_cjk_cid(c, W, H, A):
    m = _header(c, "text_cjk_cid", "Embedded CJK, CIDFontType2 (TrueType)")
    c.setFont("CJK", 24)
    c.drawString(20 * mm, H - 55 * mm, "日本語のテキスト 漢字 ひらがな")
    c.setFont("DejaVu", 11)
    c.drawString(20 * mm, H - 70 * mm, "(IPA Gothic, glyf outlines -> Type0 / CIDFontType2)")
    return {"text_contains": [m],
            "fonts_embedded": ["CJK (TrueType CID)"],
            "notes": "Type0 composite font, CIDFontType2 (TrueType). For a pure "
                     "CFF/Type1C sample use the fetched corpus (cff_text)."}

def f_text_unicode(c, W, H, A):
    m = _header(c, "text_unicode", "Unicode: Latin accents, Greek, Cyrillic")
    c.setFont("DejaVu", 15)
    rows = ["Accents: àâ ä éè êë îï ôö ùû ç — Æœ ß",
            "Greek: αβγδ ΔΣΩ — μ ≈ π·r²",
            "Cyrillic: Привет мир — Здравствуйте",
            "Symbols: € £ ¥ © ® ™ † ‡ … — “ ” ‘ ’"]
    y = H - 50 * mm
    for r in rows:
        c.drawString(20 * mm, y, r); y -= 11 * mm
    return {"text_contains": [m, "Привет", "αβγδ"],
            "fonts_embedded": ["DejaVu (TrueType)"],
            "notes": "Multi-script BMP; checks ToUnicode round-trip."}

def f_text_rtl(c, W, H, A):
    m = _header(c, "text_rtl", "RTL scripts (logical order, no shaping)")
    c.setFont("DejaVu", 16)
    c.drawString(20 * mm, H - 52 * mm, "Hebrew: שלום עולם")
    c.drawString(20 * mm, H - 66 * mm, "Arabic codepoints: السلام عليكم")
    return {"text_contains": [m],
            "fonts_embedded": ["DejaVu (TrueType)"],
            "notes": "RTL stored in logical order; ReportLab does NOT do bidi/shaping. "
                     "Tests how the consumer handles RTL + presentation forms."}

def f_text_rotated(c, W, H, A):
    m = _header(c, "text_rotated", "Rotated text (0/45/90/270 deg)")
    cx, cy = W / 2, H / 2 - 10 * mm
    for ang in (0, 45, 90, 180, 270):
        c.saveState(); c.translate(cx, cy); c.rotate(ang)
        c.setFont("DejaVu", 14); c.drawString(0, 0, f"  rotated {ang}°")
        c.restoreState()
    return {"text_contains": [m, "rotated"],
            "fonts_embedded": ["DejaVu (TrueType)"],
            "notes": "Text matrices with rotation; checks geometry recovery."}

def f_text_render_modes(c, W, H, A):
    m = _header(c, "text_render_modes", "Text render modes incl. invisible (Tr 3)")
    _ensure_fonts()
    # Render mode must be set on a text object; canvas.drawString resets it each call.
    y = H - 50 * mm
    for mode, lab, col in [(0, "Mode 0 fill", (0.1, 0.2, 0.7)),
                           (1, "Mode 1 stroke", (0.7, 0.1, 0.1)),
                           (2, "Mode 2 fill+stroke", (0.1, 0.5, 0.2))]:
        c.setFillColorRGB(*col); c.setStrokeColorRGB(0, 0, 0); c.setLineWidth(0.5)
        t = c.beginText(20 * mm, y); t.setFont("DejaVuSerif", 26)
        t.setTextRenderMode(mode); t.textLine(lab); c.drawText(t)
        y -= 16 * mm
    # invisible OCR-style text (mode 3): not painted, but extractable
    t = c.beginText(20 * mm, y); t.setFont("DejaVu", 14)
    t.setTextRenderMode(3); t.textLine("INVISIBLE_OCR_LAYER_TOKEN"); c.drawText(t)
    return {"text_contains": [m, "Mode 0 fill", "INVISIBLE_OCR_LAYER_TOKEN"],
            "raw_tokens": ["3 Tr", "1 Tr", "2 Tr"],
            "fonts_embedded": ["DejaVu (TrueType)"],
            "notes": "Mode 3 = invisible text (OCR layer): must extract but not show."}

def f_text_clip_image(c, W, H, A):
    m = _header(c, "text_clip_image", "Image-filled clipping text (Tr 7)")
    _ensure_fonts()
    # Big text used as a CLIP (Tr 7 = add to clip, paint nothing), then an image painted THROUGH the
    # glyph clip so the picture shows only inside the letters — the gradient/textured-text technique
    # real designers use (e.g. Ouest-France's "Voiles et Voiliers" banner). A renderer that drops the
    # clip-text instead of accumulating its glyph outlines fills each glyph CELL solid (black-box /
    # tofu); the image must be masked to the glyph OUTLINES, not their bounding boxes.
    c.saveState()
    t = c.beginText(20 * mm, H - 80 * mm)
    t.setFont("DejaVuSerif", 64)
    t.setTextRenderMode(7)                                   # clip only — paints nothing itself
    t.textLine("CLIP")
    c.drawText(t)                                            # ET: the glyph outlines become the clip
    c.drawImage(A["rgb"], 18 * mm, H - 92 * mm, 130 * mm, 28 * mm)   # shown only inside the letters
    c.restoreState()
    return {"text_contains": [m, "CLIP"],
            "raw_tokens": ["7 Tr"],
            "images": [{"colorspace": "rgb"}],
            "fonts_embedded": ["DejaVu (TrueType)"],
            "notes": "Tr 7 clip text + image fill: image must be masked to the glyph outlines. "
                     "Guards the text-clip accumulation (BT..ET -> intersect into the clip)."}

def f_text_tiny_leading(c, W, H, A):
    m = _header(c, "text_tiny_leading", "Tiny font, tight/overlapping leading")
    c.setFont("DejaVu", 4.5)
    t = c.beginText(20 * mm, H - 45 * mm); t.setLeading(4.0)
    for i in range(40):
        t.textLine(f"line {i:02d} tiny-text-overlap-{i:02d} 0123456789 abcdefghij")
    c.drawText(t)
    return {"text_contains": [m, "tiny-text-overlap-00", "tiny-text-overlap-39"],
            "fonts_embedded": ["DejaVu (TrueType)"],
            "notes": "Leading < font size -> overlapping baselines; line-grouping stress."}

def f_vector_shapes(c, W, H, A):
    m = _header(c, "vector_shapes", "Vector primitives (rect/circle/bezier)")
    c.setStrokeColorRGB(0, 0, 0); c.setFillColorRGB(0.9, 0.85, 0.2)
    c.rect(20 * mm, H - 80 * mm, 40 * mm, 25 * mm, fill=1, stroke=1)
    c.setFillColorRGB(0.2, 0.6, 0.9); c.circle(110 * mm, H - 68 * mm, 14 * mm, fill=1, stroke=1)
    c.setFillColorRGB(0.8, 0.3, 0.3); c.ellipse(140 * mm, H - 80 * mm, 175 * mm, H - 60 * mm, fill=1)
    p = c.beginPath(); p.moveTo(20 * mm, H - 110 * mm)
    p.curveTo(50 * mm, H - 90 * mm, 90 * mm, H - 130 * mm, 120 * mm, H - 110 * mm)
    c.setStrokeColorRGB(0.1, 0.1, 0.1); c.setLineWidth(2); c.drawPath(p, stroke=1, fill=0)
    return {"text_contains": [m], "raw_tokens": ["re", " c\n", "m\n"],
            "notes": "Filled/stroked rect, circle, ellipse, Bézier path."}

def f_vector_clipping(c, W, H, A):
    m = _header(c, "vector_clipping", "Clipping path")
    c.saveState()
    p = c.beginPath(); p.circle(80 * mm, H - 80 * mm, 30 * mm)
    c.clipPath(p, stroke=0, fill=0)
    c.setFillColorRGB(0.2, 0.5, 0.9)
    for i in range(0, 200, 6):
        c.rect(20 * mm, H - 120 * mm + i, 120 * mm, 3, fill=1, stroke=0)
    c.restoreState()
    return {"text_contains": [m], "raw_tokens": ["W* n || W n"],
            "notes": "Stripes clipped to a circle (W/W* clip operator)."}

def f_vector_transforms(c, W, H, A):
    m = _header(c, "vector_transforms", "CTM transforms (scale/rotate/skew)")
    c.saveState(); c.translate(60 * mm, H - 80 * mm); c.rotate(20); c.scale(1.4, 0.8)
    c.setFillColorRGB(0.4, 0.2, 0.7); c.rect(0, 0, 30 * mm, 18 * mm, fill=1)
    c.restoreState()
    c.saveState(); c.transform(1, 0, 0.4, 1, 110 * mm, H - 90 * mm)  # skew
    c.setFillColorRGB(0.9, 0.5, 0.1); c.rect(0, 0, 28 * mm, 18 * mm, fill=1)
    c.restoreState()
    return {"text_contains": [m], "raw_tokens": ["cm\n", " cm"],
            "notes": "Rotated+scaled and skewed rectangles via cm operator."}

def f_vector_gradient(c, W, H, A):
    m = _header(c, "vector_gradient", "Axial + radial shadings")
    c.saveState()
    p = c.beginPath(); p.rect(20 * mm, H - 90 * mm, 75 * mm, 40 * mm); c.clipPath(p, stroke=0)
    c.linearGradient(20 * mm, H - 90 * mm, 95 * mm, H - 50 * mm,
                     (HexColor("#ff5e3a"), HexColor("#2b5cff")))
    c.restoreState()
    c.saveState()
    p = c.beginPath(); p.circle(135 * mm, H - 70 * mm, 22 * mm); c.clipPath(p, stroke=0)
    c.radialGradient(135 * mm, H - 70 * mm, 22 * mm,
                     (HexColor("#ffffff"), HexColor("#1f8a4c")))
    c.restoreState()
    return {"text_contains": [m], "raw_tokens": ["/Shading", "sh\n"],
            "notes": "Type 2 (axial) and Type 3 (radial) shadings."}

def f_vector_dash_caps(c, W, H, A):
    m = _header(c, "vector_dash_caps", "Dashes, caps, joins, widths")
    y = H - 50 * mm
    for dash, cap in [([], 0), ([6, 3], 1), ([1, 4], 2), ([10, 4, 2, 4], 0)]:
        c.saveState(); c.setLineWidth(4); c.setDash(dash); c.setLineCap(cap)
        c.setStrokeColorRGB(0.1, 0.1, 0.1)
        c.line(20 * mm, y, 150 * mm, y); c.restoreState(); y -= 12 * mm
    return {"text_contains": [m], "raw_tokens": ["d\n", " J\n", " d"],
            "notes": "setDash/setLineCap/setLineJoin -> d/J/j operators."}

def f_color_rgb(c, W, H, A):
    m = _header(c, "color_rgb", "DeviceRGB swatches")
    cols = [(1, 0, 0), (0, 1, 0), (0, 0, 1), (1, 1, 0), (0, 1, 1), (1, 0, 1)]
    x = 20 * mm
    for r, g, b in cols:
        c.setFillColorRGB(r, g, b); c.rect(x, H - 70 * mm, 22 * mm, 22 * mm, fill=1, stroke=1)
        x += 26 * mm
    return {"text_contains": [m], "raw_tokens": ["rg\n", " rg"],
            "colorspaces": ["DeviceRGB"], "notes": "rg/RG operators."}

def f_color_cmyk(c, W, H, A):
    m = _header(c, "color_cmyk", "DeviceCMYK swatches")
    cols = [(0, 1, 1, 0), (1, 0, 1, 0), (1, 1, 0, 0), (0, 0, 0, 1), (0, 0, 0, 0.4)]
    x = 20 * mm
    for cy, mg, ye, k in cols:
        c.setFillColorCMYK(cy, mg, ye, k); c.rect(x, H - 70 * mm, 26 * mm, 26 * mm, fill=1, stroke=1)
        x += 30 * mm
    c.setFont("DejaVu", 10); c.setFillColorRGB(0, 0, 0)
    c.drawString(20 * mm, H - 80 * mm, "CMYK 0,1,1,0 / 1,0,1,0 / 1,1,0,0 / K")
    return {"text_contains": [m, "CMYK 0,1,1,0"], "raw_tokens": ["k\n", " k"],
            "colorspaces": ["DeviceCMYK"], "notes": "k/K operators."}

def fc_color_spot(path):
    """Real Separation (spot) color space. ReportLab 4.x does not emit one for
    CMYKColor(spotName=...), so we build the base page with ReportLab and inject
    a genuine /Separation color space + tint transform via pikepdf."""
    _ensure_fonts()
    tmp = path + ".tmp.pdf"
    c = _new_canvas(tmp, pagesize=A4)
    c.setFont("DejaVu", 9); c.drawString(20 * mm, A4H - 18 * mm, "MATRIX::color_spot")
    c.setFont("DejaVuSerif", 15)
    c.drawString(20 * mm, A4H - 28 * mm, "Spot / Separation color space")
    c.setStrokeColorRGB(0.7, 0.7, 0.7); c.line(20 * mm, A4H - 31 * mm, A4W - 20 * mm, A4H - 31 * mm)
    c.setFont("DejaVu", 10)
    c.drawString(20 * mm, A4H - 95 * mm, "Two rects below use a Separation 'PANTONE-1655-C' (tints 1.0 and 0.5)")
    c.save()
    import pikepdf
    from pikepdf import Pdf, Name, Array, Dictionary, Stream
    pdf = Pdf.open(tmp)
    page = pdf.pages[0]
    func = pdf.make_indirect(Dictionary(
        FunctionType=2, Domain=[0, 1], C0=[0, 0, 0, 0], C1=[0, 0.9, 1.0, 0], N=1))
    sep = pdf.make_indirect(Array([Name.Separation, Name("/PANTONE-1655-C"), Name.DeviceCMYK, func]))
    if Name.Resources not in page:
        page.Resources = Dictionary()
    if Name.ColorSpace not in page.Resources:
        page.Resources.ColorSpace = Dictionary()
    page.Resources.ColorSpace.CSspot = sep
    extra = (b"q /CSspot cs 1 sc 56 640 150 70 re f "
             b"0.5 sc 230 640 150 70 re f Q\n")
    page.contents_add(Stream(pdf, extra))
    pdf.save(path)
    pdf.close()
    os.remove(tmp)
    return {"pages": 1, "text_contains": ["MATRIX::color_spot"],
            "raw_tokens": ["/Separation", "PANTONE-1655-C", "cs", "sc"],
            "colorspaces": ["Separation"],
            "notes": "Genuine Separation color space with Type-2 tint transform to DeviceCMYK."}

def f_color_alpha(c, W, H, A):
    m = _header(c, "color_alpha", "Constant alpha / transparency")
    c.saveState()
    c.setFillColorRGB(0.9, 0.1, 0.1); c.setFillAlpha(0.5)
    c.circle(70 * mm, H - 70 * mm, 24 * mm, fill=1, stroke=0)
    c.setFillColorRGB(0.1, 0.3, 0.9); c.setFillAlpha(0.5)
    c.circle(95 * mm, H - 70 * mm, 24 * mm, fill=1, stroke=0)
    c.restoreState()
    return {"text_contains": [m], "raw_tokens": ["/ca ", "/ca\n", "ExtGState"],
            "notes": "Constant fill alpha -> ExtGState /ca; overlapping translucency."}

def f_image_jpeg_rgb(c, W, H, A):
    m = _header(c, "image_jpeg_rgb", "Embedded RGB JPEG (DCTDecode)")
    c.drawImage(A["rgb"], 20 * mm, H - 95 * mm, 80 * mm, 50 * mm)
    return {"text_contains": [m], "raw_tokens": ["/DCTDecode"],
            "images": [{"colorspace": "rgb"}],
            "notes": "Baseline JPEG, DeviceRGB."}

def f_image_png_alpha(c, W, H, A):
    m = _header(c, "image_png_alpha", "PNG with alpha (SMask)")
    c.setFillColorRGB(0.85, 0.85, 0.6); c.rect(18 * mm, H - 100 * mm, 90 * mm, 60 * mm, fill=1, stroke=0)
    c.drawImage(A["alpha"], 25 * mm, H - 95 * mm, 50 * mm, 50 * mm, mask="auto")
    return {"text_contains": [m], "raw_tokens": ["/SMask"],
            "images": [{"colorspace": "rgb"}],
            "notes": "Soft mask alpha over a colored background."}

def f_image_cmyk_jpeg(c, W, H, A):
    m = _header(c, "image_cmyk_jpeg", "Embedded CMYK JPEG")
    c.drawImage(A["cmyk"], 20 * mm, H - 90 * mm, 70 * mm, 45 * mm)
    return {"text_contains": [m], "raw_tokens": ["/DCTDecode"],
            "images": [{"colorspace": "cmyk"}],
            "notes": "CMYK JPEG; many tools mishandle Adobe-inverted CMYK."}

def f_image_1bit(c, W, H, A):
    m = _header(c, "image_1bit", "1-bit bilevel image")
    c.drawImage(A["bilevel"], 20 * mm, H - 90 * mm, 45 * mm, 45 * mm)
    return {"text_contains": [m],
            "images": [{"bpc": 1}],
            "notes": "1 bit-per-component bilevel raster."}

def f_image_svg_vector(c, W, H, A):
    m = _header(c, "image_svg_vector", "Embedded SVG -> vector ops")
    from svglib.svglib import svg2rlg
    from reportlab.graphics import renderPDF
    dwg = svg2rlg(A["svg"])
    renderPDF.draw(dwg, c, 20 * mm, H - 95 * mm)
    return {"text_contains": [m], "raw_tokens": ["re", " c\n"],
            "notes": "SVG rendered as native PDF vector ops, NOT a raster image."}

# ---- custom (whole-file) builders: multipage / structural --------------------
def fc_page_mixed_sizes(path):
    _ensure_fonts()
    c = _new_canvas(path, pagesize=A4)
    for sz, name in [(A4, "A4 portrait"), (LETTER, "Letter portrait"),
                     ((A5[1], A5[0]), "A5 landscape")]:
        c.setPageSize(sz)
        c.setFont("DejaVu", 11)
        c.drawString(20 * mm, sz[1] - 20 * mm, f"MATRIX::page_mixed_sizes  page={name}")
        c.setFont("DejaVuSerif", 18)
        c.drawString(20 * mm, sz[1] - 35 * mm, name)
        c.showPage()
    c.save()
    return {"pages": 3, "text_contains": ["MATRIX::page_mixed_sizes", "A4 portrait",
            "Letter portrait", "A5 landscape"],
            "notes": "Three pages, three different MediaBox sizes."}

def fc_page_rotate(path):
    _ensure_fonts()
    tmp = path + ".tmp.pdf"
    c = _new_canvas(tmp, pagesize=A4)
    c.setFont("DejaVu", 11); c.drawString(20 * mm, A4H - 20 * mm, "MATRIX::page_rotate")
    c.setFont("DejaVuSerif", 18)
    c.drawString(20 * mm, A4H - 40 * mm, "This page has /Rotate 90")
    c.line(20 * mm, A4H - 45 * mm, 120 * mm, A4H - 45 * mm)
    c.save()
    from pypdf import PdfReader, PdfWriter
    from pypdf.generic import NumberObject, NameObject
    r = PdfReader(tmp); w = PdfWriter()
    pg = r.pages[0]; pg[NameObject("/Rotate")] = NumberObject(90); w.add_page(pg)
    with open(path, "wb") as fh: w.write(fh)
    os.remove(tmp)
    return {"pages": 1, "text_contains": ["MATRIX::page_rotate", "/Rotate 90"],
            "raw_tokens": ["/Rotate"],
            "notes": "Page /Rotate attribute = 90; visual vs logical orientation."}

def fc_page_mediabox_cropbox(path):
    _ensure_fonts()
    tmp = path + ".tmp.pdf"
    c = _new_canvas(tmp, pagesize=A4)
    c.setFont("DejaVu", 11); c.drawString(20 * mm, A4H - 20 * mm, "MATRIX::page_mediabox_cropbox")
    c.setStrokeColorRGB(0.8, 0, 0); c.setDash([4, 3])
    c.rect(15 * mm, 15 * mm, A4W - 30 * mm, A4H - 30 * mm, stroke=1, fill=0)
    c.setFont("DejaVuSerif", 14)
    c.drawString(20 * mm, A4H / 2, "CropBox is inset 15mm from MediaBox (red dashes)")
    c.save()
    from pypdf import PdfReader, PdfWriter
    from pypdf.generic import RectangleObject
    r = PdfReader(tmp); w = PdfWriter(); pg = r.pages[0]
    pg.cropbox = RectangleObject([15 * mm, 15 * mm, A4W - 15 * mm, A4H - 15 * mm])
    w.add_page(pg)
    with open(path, "wb") as fh: w.write(fh)
    os.remove(tmp)
    return {"pages": 1, "text_contains": ["MATRIX::page_mediabox_cropbox"],
            "raw_tokens": ["/CropBox"],
            "notes": "CropBox != MediaBox; effective page area differs from media."}

def fc_doc_outline_links(path):
    _ensure_fonts()
    c = _new_canvas(path, pagesize=A4)
    # page 1
    c.setFont("DejaVu", 11); c.drawString(20 * mm, A4H - 20 * mm, "MATRIX::doc_outline_links")
    c.setFont("DejaVuSerif", 20); c.drawString(20 * mm, A4H - 40 * mm, "Chapter 1")
    c.bookmarkPage("ch1"); c.addOutlineEntry("Chapter 1", "ch1", level=0)
    c.setFont("DejaVu", 12)
    c.drawString(20 * mm, A4H - 60 * mm, "External link: https://example.org")
    c.linkURL("https://example.org", (20 * mm, A4H - 63 * mm, 120 * mm, A4H - 57 * mm),
              relative=0)
    c.drawString(20 * mm, A4H - 75 * mm, "Internal link -> jump to Chapter 2")
    c.linkRect("", "ch2", (20 * mm, A4H - 78 * mm, 120 * mm, A4H - 72 * mm), relative=0)
    c.showPage()
    # page 2
    c.setFont("DejaVuSerif", 20); c.drawString(20 * mm, A4H - 40 * mm, "Chapter 2")
    c.bookmarkPage("ch2"); c.addOutlineEntry("Chapter 2", "ch2", level=0)
    c.showPage()
    c.showOutline()
    c.save()
    return {"pages": 2, "text_contains": ["MATRIX::doc_outline_links", "Chapter 1",
            "Chapter 2", "https://example.org"],
            "raw_tokens": ["/Outlines", "/Link", "/URI"],
            "notes": "Document outline (bookmarks) + external URI + internal GoTo links."}

def fc_doc_metadata_xmp(path):
    c = _new_canvas(path, pagesize=A4)
    _ensure_fonts()
    c.setTitle("Matrix Metadata Sample TITLE_TOKEN")
    c.setAuthor("Author AUTHOR_TOKEN")
    c.setSubject("Subject SUBJECT_TOKEN")
    c.setKeywords("alpha,beta,KEYWORD_TOKEN")
    c.setFont("DejaVu", 11); c.drawString(20 * mm, A4H - 20 * mm, "MATRIX::doc_metadata_xmp")
    c.setFont("DejaVuSerif", 14)
    c.drawString(20 * mm, A4H - 40 * mm, "Info dict + XMP carry title/author/subject/keywords.")
    c.save()
    return {"pages": 1, "text_contains": ["MATRIX::doc_metadata_xmp"],
            "info": {"title": "Matrix Metadata Sample TITLE_TOKEN",
                     "author": "Author AUTHOR_TOKEN"},
            "notes": "Document Info dictionary metadata; check title/author extraction."}

# ----------------------------------------------------------------------------
# registry
# ----------------------------------------------------------------------------
FEATURES = {
    # id: (category, title, builder, custom?)
    "text_base14":          ("fonts",  "Standard 14 fonts",            f_text_base14, False),
    "text_embedded_ttf":    ("fonts",  "Embedded TrueType",            f_text_embedded_ttf, False),
    "text_cjk_cid":         ("fonts",  "Embedded CJK (CIDFontType2)",  f_text_cjk_cid, False),
    "text_unicode":         ("fonts",  "Unicode multi-script",         f_text_unicode, False),
    "text_rtl":             ("fonts",  "RTL scripts",                  f_text_rtl, False),
    "text_rotated":         ("text",   "Rotated text",                 f_text_rotated, False),
    "text_render_modes":    ("text",   "Text render modes",            f_text_render_modes, False),
    "text_clip_image":      ("text",   "Image-filled clip text (Tr 7)",f_text_clip_image, False),
    "text_tiny_leading":    ("text",   "Tiny font / tight leading",    f_text_tiny_leading, False),
    "vector_shapes":        ("vector", "Vector primitives",            f_vector_shapes, False),
    "vector_clipping":      ("vector", "Clipping path",                f_vector_clipping, False),
    "vector_transforms":    ("vector", "CTM transforms",               f_vector_transforms, False),
    "vector_gradient":      ("vector", "Axial+radial shadings",        f_vector_gradient, False),
    "vector_dash_caps":     ("vector", "Dashes/caps/joins",            f_vector_dash_caps, False),
    "color_rgb":            ("color",  "DeviceRGB",                    f_color_rgb, False),
    "color_cmyk":           ("color",  "DeviceCMYK",                   f_color_cmyk, False),
    "color_spot":           ("color",  "Spot/Separation",              fc_color_spot, True),
    "color_alpha":          ("color",  "Alpha/transparency",           f_color_alpha, False),
    "image_jpeg_rgb":       ("image",  "RGB JPEG",                     f_image_jpeg_rgb, False),
    "image_png_alpha":      ("image",  "PNG alpha (SMask)",            f_image_png_alpha, False),
    "image_cmyk_jpeg":      ("image",  "CMYK JPEG",                    f_image_cmyk_jpeg, False),
    "image_1bit":           ("image",  "1-bit bilevel",                f_image_1bit, False),
    "image_svg_vector":     ("image",  "SVG -> vector",                f_image_svg_vector, False),
    "page_mixed_sizes":     ("page",   "Mixed page sizes",             fc_page_mixed_sizes, True),
    "page_rotate":          ("page",   "/Rotate 90",                   fc_page_rotate, True),
    "page_mediabox_cropbox":("page",   "MediaBox != CropBox",          fc_page_mediabox_cropbox, True),
    "doc_outline_links":    ("doc",    "Outline + links",              fc_doc_outline_links, True),
    "doc_metadata_xmp":     ("doc",    "Metadata",                     fc_doc_metadata_xmp, True),
}

def build_one(fid, out_path):
    """Build a single feature PDF on the fly. Returns the 'expected' dict."""
    cat, title, fn, custom = FEATURES[fid]
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    if custom:
        exp = fn(out_path)
    else:
        A = _assets()
        c = _new_canvas(out_path, pagesize=A4)
        exp = fn(c, A4W, A4H, A)
        c.showPage(); c.save()
    exp.setdefault("pages", 1)
    exp.setdefault("page_size_pt", [round(A4W, 2), round(A4H, 2)])
    return {"id": fid, "category": cat, "title": title,
            "file": os.path.basename(out_path), "expected": exp}

def build_all(out_dir, only=None, category=None):
    os.makedirs(out_dir, exist_ok=True)
    entries, failed = [], []
    for fid, (cat, title, fn, custom) in FEATURES.items():
        if only and fid not in only:
            continue
        if category and cat != category:
            continue
        path = os.path.join(out_dir, f"{fid}.pdf")
        try:
            entry = build_one(fid, path)
            entry["file"] = f"{fid}.pdf"
            entries.append(entry)
            print(f"  [ok] {fid:24s} -> {entry['file']}")
        except Exception as e:
            failed.append(fid)
            print(f"  [FAIL] {fid:24s} {type(e).__name__}: {e}", file=sys.stderr)
    manifest = {
        "generator": "generate_matrix.py", "version": GEN_VERSION,
        "generated_utc": datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat(),
        "count": len(entries),
        "categories": sorted({e["category"] for e in entries}),
        "entries": entries,
    }
    with open(os.path.join(out_dir, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
    print(f"\nWrote {len(entries)} PDFs + manifest.json to {out_dir}")
    if failed:   # a corpus that shrinks in silence tests less than it claims
        sys.exit(f"{len(failed)} feature(s) could not be built: {', '.join(failed)}")
    return manifest

def main():
    ap = argparse.ArgumentParser(description="Synthetic PDF feature-matrix generator")
    ap.add_argument("--all", action="store_true", help="build the whole matrix")
    ap.add_argument("--feature", action="append", help="build specific feature id(s)")
    ap.add_argument("--category", help="build only one category")
    ap.add_argument("--list", action="store_true", help="list features and exit")
    ap.add_argument("--out", default="out/synthetic", help="output directory")
    a = ap.parse_args()
    if a.list:
        w = max(len(k) for k in FEATURES)
        for fid, (cat, title, _, custom) in FEATURES.items():
            print(f"  {fid:<{w}}  [{cat:<6}] {title}{'  (multipage/struct)' if custom else ''}")
        print(f"\n{len(FEATURES)} features.")
        return
    if not (a.all or a.feature or a.category):
        ap.error("nothing to do: pass --all, --feature ID, --category CAT, or --list")
    build_all(a.out, only=set(a.feature) if a.feature else None, category=a.category)

if __name__ == "__main__":
    main()
