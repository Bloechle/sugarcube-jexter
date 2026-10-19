#!/usr/bin/env python3
"""make_rich.py — build a PDF carrying the tracks that ride BESIDE the ink.

`Rich.java` compares links, form fields, annotations, layers and the outline across a
round-trip, because those live on their own tracks (page lists, jexter/ members) and no
paint or text gate touches them: a whole track can vanish with every other gate green.

But the synthetic matrix carries NONE of fields / annotations / layers, so Rich reports a
cheerful zero on every feature PDF and the claim stays untested. This writes the fixture
it actually needs — the same reason `make_tagged.py` exists for the structure path.

Carried here, one of each so a loss is unambiguous:
  - AcroForm  : a text field (with a value) and a checkbox (on)
  - Annots    : a highlight (with quadpoints) and a text note, both with author + contents
  - OCG       : two optional-content layers, one visible one hidden, with real content
  - Links     : one external URI, one internal destination
  - Outline   : a two-level bookmark tree

    python make_rich.py [out.pdf]        # default rich.pdf
"""
import sys
import pikepdf
from pikepdf import Array, Dictionary, Name, String

out = sys.argv[1] if len(sys.argv) > 1 else "rich.pdf"
pdf = pikepdf.new()

font = pdf.make_indirect(Dictionary(
    Type=Name.Font, Subtype=Name.Type1, BaseFont=Name.Helvetica))


def page(stream: str):
    p = pdf.add_blank_page(page_size=(400, 300))
    p.Contents = pdf.make_stream(stream.encode())
    p.Resources = Dictionary(Font=Dictionary(F1=font))
    return p


def text(x, y, s, size=13):
    return f"BT /F1 {size} Tf {x} {y} Td ({s}) Tj ET\n"


# ── page 1 — base ink + the layered content ────────────────────────────────────────────
p1 = page(
    text(40, 250, "RICH FIXTURE - page 1")
    + text(40, 220, "external link below, internal link to page 2")
    # layer content is wrapped in BDC/EMC marked content bound to the OCGs
    + "/OC /oc1 BDC\n" + "1 0 0 rg\n" + text(40, 180, "VISIBLE LAYER") + "EMC\n"
    + "/OC /oc2 BDC\n" + "0 0 1 rg\n" + text(40, 150, "HIDDEN LAYER") + "EMC\n"
    + "0 g\n" + text(40, 110, "form field and annotations sit on this page")
)
p2 = page(text(40, 250, "RICH FIXTURE - page 2") + text(40, 210, "internal target"))

# ── optional content groups (layers) ───────────────────────────────────────────────────
oc1 = pdf.make_indirect(Dictionary(Type=Name.OCG, Name=String("Visible layer")))
oc2 = pdf.make_indirect(Dictionary(Type=Name.OCG, Name=String("Hidden layer")))
p1.Resources[Name.Properties] = Dictionary(oc1=oc1, oc2=oc2)
pdf.Root[Name.OCProperties] = Dictionary(
    OCGs=Array([oc1, oc2]),
    D=Dictionary(Order=Array([oc1, oc2]), ON=Array([oc1]), OFF=Array([oc2])),
)

# ── AcroForm: a text field with a value, and a checkbox that is ON ─────────────────────
tf = pdf.make_indirect(Dictionary(
    Type=Name.Annot, Subtype=Name.Widget, FT=Name.Tx,
    T=String("fullname"), V=String("Jean-Luc Bloechle"), DA=String("/Helv 0 Tf 0 g"),
    Rect=Array([220, 100, 380, 124]), F=4, P=p1.obj))
cb = pdf.make_indirect(Dictionary(
    Type=Name.Annot, Subtype=Name.Widget, FT=Name.Btn,
    T=String("agreed"), V=Name.Yes, AS=Name.Yes,
    Rect=Array([220, 60, 244, 84]), F=4, P=p1.obj))
pdf.Root[Name.AcroForm] = Dictionary(
    Fields=Array([tf, cb]), DA=String("/Helv 0 Tf 0 g"),
    DR=Dictionary(Font=Dictionary(Helv=font)))

# ── annotations: a highlight with quadpoints, and a text note ──────────────────────────
hl = pdf.make_indirect(Dictionary(
    Type=Name.Annot, Subtype=Name.Highlight,
    Rect=Array([38, 240, 250, 266]),
    QuadPoints=Array([38, 266, 250, 266, 38, 240, 250, 240]),
    C=Array([1, 1, 0]), T=String("reviewer"), Contents=String("look at this title"), P=p1.obj))
note = pdf.make_indirect(Dictionary(
    Type=Name.Annot, Subtype=Name.Text,
    Rect=Array([350, 240, 372, 262]),
    C=Array([1, 0.6, 0]), T=String("reviewer"), Contents=String("a sticky note"), P=p1.obj))

# ── links: one external, one internal ──────────────────────────────────────────────────
ext = pdf.make_indirect(Dictionary(
    Type=Name.Annot, Subtype=Name.Link, Rect=Array([40, 214, 220, 232]),
    Border=Array([0, 0, 0]), A=Dictionary(S=Name.URI, URI=String("https://sugarcube.ch"))))
internal = pdf.make_indirect(Dictionary(
    Type=Name.Annot, Subtype=Name.Link, Rect=Array([230, 214, 360, 232]),
    Border=Array([0, 0, 0]), Dest=Array([p2.obj, Name.XYZ, 0, 300, 0])))

p1[Name.Annots] = Array([tf, cb, hl, note, ext, internal])

# ── outline: two levels ────────────────────────────────────────────────────────────────
child = pdf.make_indirect(Dictionary(
    Title=String("Sub-section on page 2"), Dest=Array([p2.obj, Name.XYZ, 0, 300, 0])))
top = pdf.make_indirect(Dictionary(
    Title=String("Rich fixture"), Dest=Array([p1.obj, Name.XYZ, 0, 300, 0]),
    First=child, Last=child, Count=1))
child[Name.Parent] = top
outlines = pdf.make_indirect(Dictionary(
    Type=Name.Outlines, First=top, Last=top, Count=2))
top[Name.Parent] = outlines
pdf.Root[Name.Outlines] = outlines

pdf.save(out)
print(f"wrote {out}")
print("  fields=2 (text+checkbox)  annots=2 (highlight+note)  layers=2 (1 on, 1 off)")
print("  links=2 (external+internal)  outline=2 (two levels)")
