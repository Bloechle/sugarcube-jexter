#!/usr/bin/env python3
"""make_redaction_fixtures.py [OUT_DIR] — the five RedactionAudit fixtures (reportlab + pikepdf + Pillow).

  fake.pdf     black box AFTER text over "4711-2233", white box over "Alice Martin", a 0.5pt rule → 2 TEXT_UNDER_BOX, exit 1
  legit.pdf    yellow box BEFORE the text (a highlight)                                             → CLEAN, exit 0
  real.pdf     text removed, black box                                                              → CLEAN, exit 0
  scan.pdf     image + invisible OCR text (Tr 3) + black box over both                              → TEXT + IMAGE_UNDER_BOX
  pending.pdf  legit.pdf + a /Redact annotation never applied                                       → PENDING_REDACT
  split.pdf    "Alice " + bold "Martin" in two runs, title/author/keywords metadata quoting them     → zones --match matches across runs; --redactMeta
Run:  java -cp "$JEXTER_CP:lib/*" sugarcube.jexter.cli.Redact audit OUT/fake.pdf [--json]
      java -cp "$JEXTER_CP:lib/*" sugarcube.jexter.cli.Redact apply OUT/fake.pdf out.pdf   # then audit out.pdf → CLEAN
"""
import sys, os
from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import A4
import pikepdf
from PIL import Image
out = sys.argv[1] if len(sys.argv) > 1 else "/tmp/rt"; os.makedirs(out, exist_ok=True)
def base(c, txt="Account number 4711-2233 belongs to Alice Martin."):
    c.setFont("Helvetica", 12); c.drawString(72, 700, txt)
c = canvas.Canvas(f"{out}/fake.pdf", pagesize=A4); base(c)
c.setFillColorRGB(0, 0, 0); c.rect(160, 696, 66, 14, stroke=0, fill=1)
c.setFillColorRGB(1, 1, 1); c.rect(300, 696, 90, 14, stroke=0, fill=1)
c.rect(72, 694, 100, 0.5, stroke=0, fill=1); c.showPage(); c.save()
c = canvas.Canvas(f"{out}/legit.pdf", pagesize=A4)
c.setFillColorRGB(1, 1, 0); c.rect(160, 696, 66, 14, stroke=0, fill=1)
# Reset the ink. Without this line reportlab draws the text in the CURRENT fill — yellow on yellow —
# so the audit reported INVISIBLE_TEXT and was right, and the one fixture meant to be CLEAN never was
# (measured 2026-09-14: with the reset, `clean: true, findings: []`).
c.setFillColorRGB(0, 0, 0); base(c); c.showPage(); c.save()
c = canvas.Canvas(f"{out}/real.pdf", pagesize=A4); c.setFont("Helvetica", 12)
c.drawString(72, 700, "Account number"); c.drawString(230, 700, "belongs to Alice Martin.")
c.setFillColorRGB(0, 0, 0); c.rect(160, 696, 66, 14, stroke=0, fill=1); c.showPage(); c.save()
Image.new("RGB", (200, 100), (200, 220, 255)).save(f"{out}/scan.png")
c = canvas.Canvas(f"{out}/scan.pdf", pagesize=A4); c.drawImage(f"{out}/scan.png", 72, 500, 200, 100)
t = c.beginText(80, 540); t.setTextRenderMode(3); t.setFont("Helvetica", 12); t.textOut("SECRET-OCR-LAYER"); c.drawText(t)
c.setFillColorRGB(0, 0, 0); c.rect(72, 530, 120, 30, stroke=0, fill=1); c.showPage(); c.save()
pdf = pikepdf.open(f"{out}/legit.pdf"); pg = pdf.pages[0]
an = pikepdf.Dictionary(Type=pikepdf.Name.Annot, Subtype=pikepdf.Name.Redact, Rect=[160, 696, 226, 710],
                        Contents="to remove", C=[1, 0, 0], IC=[0, 0, 0])
pg.Annots = pikepdf.Array([pdf.make_indirect(an)]); pdf.save(f"{out}/pending.pdf")
c = canvas.Canvas(f"{out}/split.pdf", pagesize=A4); c.setTitle("Dossier Alice Martin"); c.setAuthor("Alice Martin"); c.setKeywords("4711-2233")
c.setFont("Helvetica", 12); c.drawString(72, 700, "Contact: Alice ")
c.setFont("Helvetica-Bold", 12); c.drawString(150, 700, "Martin")
c.setFont("Helvetica", 12); c.drawString(190, 700, " (client 4711-2233)"); c.drawString(72, 680, "Second line mentions Alice Martin again.")
c.showPage(); c.save()
print("fixtures →", out)
