#!/usr/bin/env python3
"""make_tagged.py - build a genuinely TAGGED PDF, to exercise the structure path.

Most corpus PDFs carry no tag tree, so TaggedStructureBuilder never runs and the claim that a
PDF's own structure wins stays untested. This writes the real thing: marked content (BDC/EMC
with MCIDs), a StructTreeRoot, a ParentTree, MarkInfo/Marked.

The case that matters is the LAST element: ONE P holding TWO MCIDs, a paragraph split over two
lines. A builder that maps one element per block passes every easy tag and still splits that
one - which is the whole point of honouring a structure instead of re-deriving it.

    python make_tagged.py [out.pdf]     # default tagged.pdf
"""
import sys

import pikepdf
from pikepdf import Array, Dictionary, Name, String

pdf = pikepdf.new()

font = pdf.make_indirect(Dictionary(
    Type=Name.Font, Subtype=Name.Type1, BaseFont=Name("/Helvetica"),
    Encoding=Name.WinAnsiEncoding))
bold = pdf.make_indirect(Dictionary(
    Type=Name.Font, Subtype=Name.Type1, BaseFont=Name("/Helvetica-Bold"),
    Encoding=Name.WinAnsiEncoding))

# Each block is wrapped in marked content with its own MCID, in the order the tags will reference.
blocks = [
    ("H1", bold, 16, 40, 340, "Titre principal du document"),
    ("P",  font, 10, 40, 310, "Premier paragraphe, marque comme P dans l'arbre de balises."),
    ("H2", bold, 12, 40, 275, "Sous-titre de section"),
    ("P",  font, 10, 40, 248, "Second paragraphe. La structure vient du PDF, pas de la geometrie."),
    ("P",  font, 10, 40, 228, "Sa suite, sur une autre ligne, mais le MEME element P."),
]
ops = []
for mcid, (tag, f, size, x, y, txt) in enumerate(blocks):
    fn = "F2" if f is bold else "F1"
    ops.append(f"/{tag} <</MCID {mcid}>> BDC BT /{fn} {size} Tf 1 0 0 1 {x} {y} Tm ({txt}) Tj ET EMC")
content = "\n".join(ops).encode("latin-1")

page = pdf.add_blank_page(page_size=(400, 400))
page.Resources = Dictionary(Font=Dictionary(F1=font, F2=bold))
page.Contents = pdf.make_stream(content)
page.StructParents = 0

# One StructElem per block, in reading order, each pointing at its MCID on this page.
page_ref = page.obj
# blocks 3 and 4 are ONE paragraph split over two lines: a single P holding both MCIDs
groups = [("H1", [0]), ("P", [1]), ("H2", [2]), ("P", [3, 4])]
elems = []
for tag, mcids in groups:
    k = mcids[0] if len(mcids) == 1 else Array(mcids)
    elems.append(pdf.make_indirect(Dictionary(
        Type=Name.StructElem, S=Name("/" + tag), Pg=page_ref, K=k)))

doc_elem = pdf.make_indirect(Dictionary(
    Type=Name.StructElem, S=Name.Document, K=Array(elems)))
for e in elems:
    e.P = doc_elem

flat = [elems[0], elems[1], elems[2], elems[3], elems[3]]   # MCID -> owning element
parent_tree = pdf.make_indirect(Dictionary(Nums=Array([0, Array(flat)])))
root = pdf.make_indirect(Dictionary(
    Type=Name.StructTreeRoot, K=Array([doc_elem]), ParentTree=parent_tree, ParentTreeNextKey=1))
doc_elem.P = root

pdf.Root.StructTreeRoot = root
pdf.Root.MarkInfo = Dictionary(Marked=True)
pdf.Root.Lang = String("fr-CH")
out = sys.argv[1] if len(sys.argv) > 1 else "tagged.pdf"
pdf.save(out)

q = pikepdf.open(out)
print("Marked      :", bool(q.Root.MarkInfo.Marked))
print("StructTree  :", "/StructTreeRoot" in q.Root)
kids = q.Root.StructTreeRoot.K[0].K
print("elements    :", [str(k.S) for k in kids])
q.close()
