#!/usr/bin/env python3
"""Generate the 11-feature PDF matrix into OUT (default ./featcorpus) — one hand-crafted
minimal PDF per rendering feature (exact content streams, uncompressed, correct xref).
Gate each with PDFBox FIRST (opens + renders non-blank ink), then measure conversion,
round-trip, and pixel fidelity (Fid, original vs re-export, PDFBox arbitrating both).
Features: radial (t3) + free-form mesh (t4) shadings, colored tiling pattern, blend modes
(Multiply/Screen/Difference), ExtGState /SMask luminosity, Type3 font (d0+d1 charprocs,
Differences encoding), WinAnsi/MacRoman/Differences text encodings, CalRGB/Lab/Separation/
Indexed colorspaces, image SMask + stencil + 16-bit gray, full text state (Tz Ts Tc TL T*
Tr0/1/2), vector ops (even-odd, caps, joins, v/y curves, miter).
TRAP: mesh coordinates encode as round(v/range*0xFFFFFFFF) — raw ints map to ~0 and the
triangle is microscopic (the ink gate catches it).
Judge high-frequency raster fallbacks (tiling) at the raster's NATIVE dpi (144): at 96 dpi
the resampling alone reads ~10% on a checkerboard while geometry is exact (0.2% at 144)."""
import os, struct, sys

OUT = sys.argv[1] if len(sys.argv) > 1 else 'featcorpus'
os.makedirs(OUT, exist_ok=True)

class PDF:
    def __init__(self): self.objs = []
    def add(self, body): self.objs.append(body); return len(self.objs)
    def stream(self, d, data):
        if isinstance(data, str): data = data.encode('latin-1')
        return self.add(('<< ' + d + f' /Length {len(data)} >>').encode() + b'\nstream\n' + data + b'\nendstream')
    def write(self, path, root):
        out = bytearray(b'%PDF-1.7\n%\xe2\xe3\xcf\xd3\n'); offs = [0]
        for i, o in enumerate(self.objs, 1):
            offs.append(len(out)); body = o if isinstance(o, bytes) else o.encode('latin-1')
            out += f'{i} 0 obj\n'.encode() + body + b'\nendobj\n'
        xref = len(out)
        out += f'xref\n0 {len(self.objs)+1}\n0000000000 65535 f \n'.encode()
        for off in offs[1:]: out += f'{off:010d} 00000 n \n'.encode()
        out += f'trailer\n<< /Size {len(self.objs)+1} /Root {root} 0 R >>\nstartxref\n{xref}\n%%EOF'.encode()
        open(path, 'wb').write(out)

def doc(name, content, res=''):
    p = PDF(); cs = p.stream('', content)
    f1 = p.add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
    pg = p.add(f'<< /Type /Page /Parent {f1+2} 0 R /MediaBox [0 0 300 300] /Contents {cs} 0 R '
               f'/Resources << /Font << /F1 {f1} 0 R >> {res} >> >>')
    ps = p.add(f'<< /Type /Pages /Kids [{pg} 0 R] /Count 1 >>')
    p.write(os.path.join(OUT, name), p.add(f'<< /Type /Catalog /Pages {ps} 0 R >>'))
    return p

def custom(name, build):
    p = PDF(); build(p); print(' ', name)

# 1 · radial
p = PDF()
fn = p.add('<< /FunctionType 2 /Domain [0 1] /C0 [0.05 0.36 0.55] /C1 [0.36 0.76 0.69] /N 1 >>')
sh = p.add(f'<< /ShadingType 3 /ColorSpace /DeviceRGB /Coords [150 150 10 150 150 130] /Function {fn} 0 R /Extend [true true] >>')
cs = p.stream('', 'q /Sh0 sh Q BT /F1 10 Tf 20 20 Td (radial shading) Tj ET')
f1 = p.add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
pg = p.add(f'<< /Type /Page /Parent 6 0 R /MediaBox [0 0 300 300] /Contents {cs} 0 R /Resources << /Font << /F1 {f1} 0 R >> /Shading << /Sh0 {sh} 0 R >> >> >>')
ps = p.add(f'<< /Type /Pages /Kids [{pg} 0 R] /Count 1 >>')
p.write(f'{OUT}/shading3-radial.pdf', p.add(f'<< /Type /Catalog /Pages {ps} 0 R >>'))

# 2 · mesh type 4 (coordinate encoding trap!)
enc = lambda v, lo, hi: struct.pack('>I', round((v - lo) / (hi - lo) * 0xFFFFFFFF))
vert = lambda x, y, r, g, b: b'\x00' + enc(x, 0, 300) + enc(y, 0, 300) + bytes([r, g, b])
tris = vert(20, 20, 255, 40, 40) + vert(280, 20, 40, 255, 40) + vert(150, 280, 40, 40, 255)
p = PDF()
sh = p.stream('/ShadingType 4 /ColorSpace /DeviceRGB /BitsPerCoordinate 32 /BitsPerComponent 8 /BitsPerFlag 8 /Decode [0 300 0 300 0 1 0 1 0 1]', tris)
cs = p.stream('', 'q /Sh0 sh Q BT /F1 10 Tf 20 5 Td (mesh type4) Tj ET')
f1 = p.add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
pg = p.add(f'<< /Type /Page /Parent 6 0 R /MediaBox [0 0 300 300] /Contents {cs} 0 R /Resources << /Font << /F1 {f1} 0 R >> /Shading << /Sh0 {sh} 0 R >> >> >>')
ps = p.add(f'<< /Type /Pages /Kids [{pg} 0 R] /Count 1 >>')
p.write(f'{OUT}/shading4-mesh.pdf', p.add(f'<< /Type /Catalog /Pages {ps} 0 R >>'))

# 3 · tiling pattern
p = PDF()
pat = p.stream('/Type /Pattern /PatternType 1 /PaintType 1 /TilingType 1 /BBox [0 0 20 20] /XStep 20 /YStep 20 /Resources << >>',
               '0.05 0.36 0.55 rg 0 0 10 10 re f 0.36 0.76 0.69 rg 10 10 10 10 re f')
cs = p.stream('', '/Pattern cs /P0 scn 20 40 260 220 re f BT /F1 10 Tf 20 15 Td (tiling pattern) Tj ET')
f1 = p.add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
pg = p.add(f'<< /Type /Page /Parent 6 0 R /MediaBox [0 0 300 300] /Contents {cs} 0 R /Resources << /Font << /F1 {f1} 0 R >> /Pattern << /P0 {pat} 0 R >> >> >>')
ps = p.add(f'<< /Type /Pages /Kids [{pg} 0 R] /Count 1 >>')
p.write(f'{OUT}/pattern-tiling.pdf', p.add(f'<< /Type /Catalog /Pages {ps} 0 R >>'))

# 4 · blend modes
p = PDF()
g1 = p.add('<< /Type /ExtGState /BM /Multiply >>'); g2 = p.add('<< /Type /ExtGState /BM /Screen >>')
g3 = p.add('<< /Type /ExtGState /BM /Difference >>')
cs = p.stream('', '1 0.2 0.2 rg 20 160 120 120 re f /G1 gs 0.2 0.2 1 rg 80 200 120 80 re f '
                  '/G2 gs 0.2 1 0.2 rg 60 120 120 60 re f /G3 gs 1 1 0.2 rg 120 60 120 90 re f '
                  'BT /F1 10 Tf 20 20 Td (blend modes) Tj ET')
f1 = p.add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
pg = p.add(f'<< /Type /Page /Parent 7 0 R /MediaBox [0 0 300 300] /Contents {cs} 0 R /Resources << /Font << /F1 {f1} 0 R >> /ExtGState << /G1 {g1} 0 R /G2 {g2} 0 R /G3 {g3} 0 R >> >> >>')
ps = p.add(f'<< /Type /Pages /Kids [{pg} 0 R] /Count 1 >>')
p.write(f'{OUT}/blend-modes.pdf', p.add(f'<< /Type /Catalog /Pages {ps} 0 R >>'))

# 5 · gs SMask luminosity (bezier circle mask)
p = PDF()
circle = ('0 g 0 0 300 300 re f 1 g 150 270 m 216 270 270 216 270 150 c 270 84 216 30 150 30 c '
          '84 30 30 84 30 150 c 30 216 84 270 150 270 c f')
mf = p.stream('/Type /XObject /Subtype /Form /BBox [0 0 300 300] /Group << /S /Transparency /CS /DeviceGray >>', circle)
gs = p.add(f'<< /Type /ExtGState /SMask << /S /Luminosity /G {mf} 0 R >> >>')
cs = p.stream('', '/GS0 gs 0.05 0.36 0.55 rg 0 0 300 300 re f BT /F1 10 Tf 20 8 Td (luminosity smask) Tj ET')
f1 = p.add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
pg = p.add(f'<< /Type /Page /Parent 7 0 R /MediaBox [0 0 300 300] /Contents {cs} 0 R /Resources << /Font << /F1 {f1} 0 R >> /ExtGState << /GS0 {gs} 0 R >> >> >>')
ps = p.add(f'<< /Type /Pages /Kids [{pg} 0 R] /Count 1 >>')
p.write(f'{OUT}/smask-luminosity.pdf', p.add(f'<< /Type /Catalog /Pages {ps} 0 R >>'))

# 6 · Type3 font
p = PDF()
ga = p.stream('', '600 0 0 0 550 700 d1 0 0 550 700 re f')
gb = p.stream('', '600 0 0 d0 50 50 m 500 50 l 275 650 l h f')
cp_ = p.add(f'<< /GA {ga} 0 R /GB {gb} 0 R >>')
en = p.add('<< /Type /Encoding /Differences [65 /GA 66 /GB] >>')
t3 = p.add(f'<< /Type /Font /Subtype /Type3 /FontBBox [0 0 600 700] /FontMatrix [0.001 0 0 0.001 0 0] '
           f'/CharProcs {cp_} 0 R /Encoding {en} 0 R /FirstChar 65 /LastChar 66 /Widths [600 600] >>')
f1 = p.add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
cs = p.stream('', 'BT /T3 48 Tf 0.36 0.2 0.6 rg 40 150 Td (ABAB) Tj ET BT /F1 10 Tf 20 20 Td (type3 font) Tj ET')
pg = p.add(f'<< /Type /Page /Parent {f1+3} 0 R /MediaBox [0 0 300 300] /Contents {cs} 0 R /Resources << /Font << /T3 {t3} 0 R /F1 {f1} 0 R >> >> >>')
ps = p.add(f'<< /Type /Pages /Kids [{pg} 0 R] /Count 1 >>')
p.write(f'{OUT}/font-type3.pdf', p.add(f'<< /Type /Catalog /Pages {ps} 0 R >>'))

# 7 · encodings
p = PDF()
fw = p.add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>')
fm = p.add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /MacRomanEncoding >>')
ed = p.add('<< /Type /Encoding /BaseEncoding /WinAnsiEncoding /Differences [65 /eacute 66 /ccedilla 67 /oe] >>')
fd = p.add(f'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding {ed} 0 R >>')
cs = p.stream('', b'BT /FW 16 Tf 20 240 Td (WinAnsi \xe9\xe7\xf4) Tj ET '
                  b'BT /FM 16 Tf 20 200 Td (MacRoman \x8e\x8d\x9b) Tj ET '
                  b'BT /FD 16 Tf 20 160 Td (Differences ABC) Tj ET')
pg = p.add(f'<< /Type /Page /Parent {fd+2} 0 R /MediaBox [0 0 300 300] /Contents {cs} 0 R /Resources << /Font << /FW {fw} 0 R /FM {fm} 0 R /FD {fd} 0 R >> >> >>')
ps = p.add(f'<< /Type /Pages /Kids [{pg} 0 R] /Count 1 >>')
p.write(f'{OUT}/font-encodings.pdf', p.add(f'<< /Type /Catalog /Pages {ps} 0 R >>'))

# 8 · colorspaces
p = PDF()
cal = p.add('[/CalRGB << /WhitePoint [0.9505 1 1.089] /Gamma [2.2 2.2 2.2] >>]')
lab = p.add('[/Lab << /WhitePoint [0.9505 1 1.089] /Range [-100 100 -100 100] >>]')
tf = p.add('<< /FunctionType 2 /Domain [0 1] /C0 [1 1 1] /C1 [0.05 0.36 0.55] /N 1 >>')
sep = p.add(f'[/Separation /PANTONE#20blue /DeviceRGB {tf} 0 R]')
idx = p.add('[/Indexed /DeviceRGB 3 <0d5c8c 5dc3b0 aa3355 f0f0f0>]')
cs = p.stream('', '/C0 cs 0.8 0.3 0.2 sc 20 220 120 60 re f /C1 cs 60 40 -30 sc 160 220 120 60 re f '
                  '/C2 cs 0.8 sc 20 140 120 60 re f /C3 cs 2 sc 160 140 120 60 re f '
                  'BT /F1 10 Tf 20 20 Td (CalRGB Lab Separation Indexed) Tj ET')
f1 = p.add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
pg = p.add(f'<< /Type /Page /Parent {f1+2} 0 R /MediaBox [0 0 300 300] /Contents {cs} 0 R /Resources << /Font << /F1 {f1} 0 R >> /ColorSpace << /C0 {cal} 0 R /C1 {lab} 0 R /C2 {sep} 0 R /C3 {idx} 0 R >> >> >>')
ps = p.add(f'<< /Type /Pages /Kids [{pg} 0 R] /Count 1 >>')
p.write(f'{OUT}/colorspaces.pdf', p.add(f'<< /Type /Catalog /Pages {ps} 0 R >>'))

# 9 · image masks (SMask alpha, stencil, 16-bit)
w = h = 8
rgb = bytes(sum(([(x*32) % 256, (y*32) % 256, 128] for y in range(h) for x in range(w)), []))
alpha = bytes([255 if (x+y) % 2 == 0 else 60 for y in range(h) for x in range(w)])
sten = bytes([0b10101010] * h)
g16 = b''.join(struct.pack('>H', (x*y*1024) % 65536) for y in range(h) for x in range(w))
p = PDF()
sm = p.stream(f'/Type /XObject /Subtype /Image /Width {w} /Height {h} /ColorSpace /DeviceGray /BitsPerComponent 8', alpha)
im = p.stream(f'/Type /XObject /Subtype /Image /Width {w} /Height {h} /ColorSpace /DeviceRGB /BitsPerComponent 8 /SMask {sm} 0 R', rgb)
st = p.stream(f'/Type /XObject /Subtype /Image /Width {w} /Height {h} /ImageMask true /BitsPerComponent 1 /Decode [0 1]', sten)
i16 = p.stream(f'/Type /XObject /Subtype /Image /Width {w} /Height {h} /ColorSpace /DeviceGray /BitsPerComponent 16', g16)
cs = p.stream('', 'q 120 0 0 120 20 160 cm /ImA Do Q q 0.36 0.76 0.69 rg 120 0 0 120 160 160 cm /ImS Do Q '
                  'q 120 0 0 120 20 30 cm /Im16 Do Q BT /F1 10 Tf 160 80 Td (smask stencil 16bit) Tj ET')
f1 = p.add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
pg = p.add(f'<< /Type /Page /Parent {f1+2} 0 R /MediaBox [0 0 300 300] /Contents {cs} 0 R /Resources << /Font << /F1 {f1} 0 R >> /XObject << /ImA {im} 0 R /ImS {st} 0 R /Im16 {i16} 0 R >> >> >>')
ps = p.add(f'<< /Type /Pages /Kids [{pg} 0 R] /Count 1 >>')
p.write(f'{OUT}/image-masks.pdf', p.add(f'<< /Type /Catalog /Pages {ps} 0 R >>'))

# 10 · text state
doc('text-state.pdf', '''BT /F1 18 Tf 20 250 Td 120 Tz (Wide 120%) Tj ET
BT /F1 18 Tf 20 220 Td 5 Ts (Rise+5) Tj 0 Ts ET
BT /F1 18 Tf 4 Tc 20 190 Td (C h a r) Tj 0 Tc ET
BT /F1 18 Tf 1 Tr 1.2 w 0.05 0.36 0.55 RG 20 160 Td (Outline Tr1) Tj ET
BT /F1 18 Tf 2 Tr 0.36 0.76 0.69 rg 0.05 0.05 0.2 RG 20 130 Td (FillStroke Tr2) Tj 0 Tr ET
BT /F1 12 Tf 24 TL 20 100 Td (line one) Tj T* (line two via T*) Tj ET
BT /F1 10 Tf 20 20 Td (text state ops) Tj ET''')

# 11 · vector ops
doc('vector-ops.pdf', '''0.05 0.36 0.55 rg 60 160 m 240 160 l 150 280 l h 100 190 m 200 190 l 150 250 l h f*
0.36 0.2 0.6 RG 14 w 1 J 1 j 40 120 m 90 40 l 140 120 l S
2 J 0 j 10 M 170 120 m 210 40 l 250 120 l S
0.8 0.3 0.2 RG 6 w 40 20 m 90 60 140 20 v S 170 20 m 220 60 270 20 y S
BT /F1 10 Tf 20 5 Td (evenodd caps joins vy) Tj ET''')

print('feature matrix →', OUT, ':', sorted(os.listdir(OUT)))

# ── stroke/clip/fill-rule subtleties (three more docs; see production-audit.md "staged bench") ──
doc = None  # (the three subtlety generators live in references/production-audit.md verbatim;
            #  regenerate them from there or extend this script when needed)
