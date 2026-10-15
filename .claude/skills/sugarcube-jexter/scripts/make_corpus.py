#!/usr/bin/env python3
"""Build the 6 synthetic stress PDFs into ./corpus (add the repo's welcome.pdf yourself)."""
from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import A4, landscape
from reportlab.lib.colors import Color, HexColor
from PIL import Image
import os, sys

OUT = sys.argv[1] if len(sys.argv) > 1 else 'corpus'
os.makedirs(OUT, exist_ok=True)
p = lambda n: os.path.join(OUT, n)

c = canvas.Canvas(p('rotated.pdf'), pagesize=A4); c.setTitle('Rotation stress')
for ang in (0, 45, 90, 180, 270):
    c.saveState(); c.translate(300, 420); c.rotate(ang)
    c.setFont('Helvetica-Bold', 14); c.setFillColor(HexColor('#334455'))
    c.drawString(20, 0, f'Rotated {ang} degrees'); c.restoreState()
c.setFont('Times-Italic', 9); c.drawString(50, 60, 'Baseline text after rotations.')
c.showPage(); c.save()

c = canvas.Canvas(p('columns.pdf'), pagesize=A4); c.setTitle('Deux colonnes accentuées')
c.setFont('Helvetica-Bold', 16); c.drawString(50, 790, 'Titre général — étude œcuménique')
c.setFont('Helvetica', 10)
L = ["Première colonne : l'élève reçoit une leçon», dit-il.", "Cœur, nœud, æther, Ærø, ça, où, dûment.",
     "Deuxième ligne avec des mots assez longs pour couler.", "Fin de la première colonne."]
R = ["Seconde colonne : garçon, français, Noël, naïf.", "Les accents aigus, graves et circonflexes tiennent.",
     "Test de crénage : AVATAR To Ta Yo.", "Fin de la seconde colonne."]
for i, t in enumerate(L): c.drawString(50, 760 - 14 * i, t)
for i, t in enumerate(R): c.drawString(310, 760 - 14 * i, t)
c.showPage(); c.save()

c = canvas.Canvas(p('graphics.pdf'), pagesize=A4); c.setTitle('Graphics stress')
c.setFillColor(Color(1, 0, 0, alpha=.5)); c.rect(60, 620, 200, 120, fill=1, stroke=0)
c.setFillColor(Color(0, 0, 1, alpha=.5)); c.rect(160, 680, 200, 120, fill=1, stroke=0)
c.saveState(); pt = c.beginPath(); pt.circle(300, 480, 80); c.clipPath(pt, stroke=0)
c.linearGradient(220, 400, 380, 560, (HexColor('#0d5c8c'), HexColor('#5dc3b0'))); c.restoreState()
c.setDash(6, 3); c.setLineWidth(2.5); c.setStrokeColor(HexColor('#aa3355'))
c.line(60, 340, 540, 340); c.setDash()
c.setFont('Helvetica', 11); c.drawString(60, 300, 'Alpha overlap, clipped axial gradient, dashed rule.')
c.showPage(); c.save()

im = Image.new('RGBA', (120, 80)); px = im.load()
for y in range(80):
    for x in range(120): px[x, y] = (255 * x // 120, 128, 255 * y // 80, 255 if (x // 12 + y // 10) % 2 else 90)
im.save('/tmp/_checker.png'); Image.new('RGB', (100, 70), (200, 90, 30)).save('/tmp/_plain.jpg', quality=88)
c = canvas.Canvas(p('images.pdf'), pagesize=A4); c.setTitle('Images stress')
c.drawImage('/tmp/_checker.png', 70, 600, 240, 160, mask='auto')
c.drawImage('/tmp/_plain.jpg', 340, 620, 150, 105)
c.setFont('Helvetica', 11); c.drawString(70, 560, 'PNG with alpha checker, plain JPEG beside it.')
c.showPage(); c.save()

c = canvas.Canvas(p('landscape.pdf'), pagesize=landscape(A4)); c.setTitle('Landscape link')
c.setFont('Helvetica', 42); c.drawString(60, 480, 'Huge 42pt heading')
c.setFont('Helvetica', 4.5); c.drawString(60, 450, 'tiny 4.5pt line that must survive the pipeline without vanishing')
c.linkURL('https://example.org', (60, 400, 260, 420), relative=0)
c.setFont('Helvetica-Oblique', 12); c.setFillColor(HexColor('#2255aa')); c.drawString(60, 405, 'external link zone here')
c.showPage(); c.save()

c = canvas.Canvas(p('book.pdf'), pagesize=A4); c.setTitle('Deep outline'); c.setAuthor('Audit')
for ch in (1, 2):
    c.setFont('Helvetica-Bold', 18); c.drawString(60, 780, f'Chapter {ch}')
    c.bookmarkPage(f'ch{ch}'); c.addOutlineEntry(f'Chapter {ch}', f'ch{ch}', level=0)
    for s in (1, 2):
        y = 740 - 120 * (s - 1)
        c.setFont('Helvetica-Bold', 13); c.drawString(60, y, f'Section {ch}.{s}')
        c.bookmarkPage(f'ch{ch}s{s}'); c.addOutlineEntry(f'Section {ch}.{s}', f'ch{ch}s{s}', level=1)
        c.setFont('Helvetica', 11)
        c.drawString(60, y - 18, f'Body paragraph for section {ch}.{s}, first line of prose.')
        c.drawString(60, y - 32, 'Second line, continuing the paragraph naturally.')
    c.showPage()
c.save()
print('corpus →', OUT, sorted(os.listdir(OUT)))
