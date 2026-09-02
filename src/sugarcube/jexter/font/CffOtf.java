package sugarcube.jexter.font;

import sugarcube.jexter.core.JxPath;
import sugarcube.jexter.ocd.model.OCDFont;
import sugarcube.jexter.ocd.model.OCDGlyph;

import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a CFF-flavored OpenType font ('OTTO' sfnt with a {@code CFF } table)
 * directly from a normalized {@link OCDFont}. The glyph outlines are em-unit
 * cubic {@link JxPath}s — exactly what Type 2 charstrings encode natively
 * ({@code rrcurveto}), so there is no cubic→quadratic conversion and the shapes
 * are reproduced without approximation (unlike the TrueType {@code glyf} path).
 *
 * <p>Source glyph ids are remapped to a dense 0..n space (gid 0 = .notdef). The
 * sfnt {@code cmap} (format 4) maps codepoints to the dense gids, so every
 * surface that renders through the cmap (PDF {@code showText}, browser
 * {@code <text>} via {@code @font-face}) selects the correct glyph. Verified by
 * round-tripping through FontBox.
 */
public final class CffOtf extends OtfBuilder {

    private static final int EM = 1000;   // CFF default FontMatrix is 0.001 → 1000 upem

    public static byte[] build(OCDFont f) { return new CffOtf(f).assemble(); }


    private CffOtf(OCDFont f) { super(f); }

    @Override int em() { return EM; }



    // ════════════════════════ CFF primitives ════════════════════════
    /** A CFF INDEX of the given byte objects. */
    private static byte[] index(List<byte[]> items) {
        Buf b = new Buf();
        int count = items.size();
        b.u16(count);
        if (count == 0) return b.out();
        int dataLen = 0;
        for (byte[] it : items) dataLen += it.length;
        int last = dataLen + 1;
        int offSize = last <= 0xFF ? 1 : last <= 0xFFFF ? 2 : last <= 0xFFFFFF ? 3 : 4;
        b.u8(offSize);
        int off = 1;
        b.off(off, offSize);
        for (byte[] it : items) { off += it.length; b.off(off, offSize); }
        for (byte[] it : items) b.bytes(it);
        return b.out();
    }

    /** DICT integer operand (supports the 32-bit form). */
    private static void dictInt(Buf b, int v) {
        if (v >= -107 && v <= 107) b.u8(v + 139);
        else if (v >= 108 && v <= 1131) { int w = v - 108; b.u8(247 + (w >> 8)); b.u8(w & 0xFF); }
        else if (v >= -1131 && v <= -108) { int w = -108 - v; b.u8(251 + (w >> 8)); b.u8(w & 0xFF); }
        else if (v >= -32768 && v <= 32767) { b.u8(28); b.u16(v & 0xFFFF); }
        else { b.u8(29); b.u32(v & 0xFFFFFFFFL); }
    }

    /** Fixed 5-byte DICT integer (29 + int32) so a Top DICT offset can be backpatched without changing length. */
    private static void dictOffset(Buf b, int v) { b.u8(29); b.u32(v & 0xFFFFFFFFL); }

    /** Type 2 charstring integer operand (no 32-bit form; 28 = int16). */
    private static void t2int(Buf b, int v) {
        if (v >= -107 && v <= 107) b.u8(v + 139);
        else if (v >= 108 && v <= 1131) { int w = v - 108; b.u8(247 + (w >> 8)); b.u8(w & 0xFF); }
        else if (v >= -1131 && v <= -108) { int w = -108 - v; b.u8(251 + (w >> 8)); b.u8(w & 0xFF); }
        else { b.u8(28); b.u16(v & 0xFFFF); }
    }

    // ════════════════════════ Type 2 charstring per glyph ════════════════════════
    private byte[] charString(OCDGlyph g) {
        Buf b = new Buf();
        int adv = (g == null) ? 0 : r(g.advance());
        t2int(b, adv);                                             // width (nominalWidthX = 0 → absolute)
        if (g == null || g.outline() == null || g.outline().isEmpty()) { b.u8(14); return b.out(); }

        PathIterator it = g.outline().getPathIterator(null);
        double[] p = new double[6];
        int cx = 0, cy = 0;
        while (!it.isDone()) {
            switch (it.currentSegment(p)) {
                case PathIterator.SEG_MOVETO -> {
                    int x = r(p[0]), y = r(p[1]);
                    t2int(b, x - cx); t2int(b, y - cy); b.u8(21);   // rmoveto
                    cx = x; cy = y;
                }
                case PathIterator.SEG_LINETO -> {
                    int x = r(p[0]), y = r(p[1]);
                    t2int(b, x - cx); t2int(b, y - cy); b.u8(5);    // rlineto
                    cx = x; cy = y;
                }
                case PathIterator.SEG_QUADTO -> {                    // quad → cubic (outlines are cubic; defensive)
                    double qx = p[0], qy = p[1], ex = p[2], ey = p[3];
                    double c1x = cx / (double) EM + 2.0 / 3 * (qx - cx / (double) EM);
                    double c1y = cy / (double) EM + 2.0 / 3 * (qy - cy / (double) EM);
                    double c2x = ex + 2.0 / 3 * (qx - ex), c2y = ey + 2.0 / 3 * (qy - ey);
                    int C1x = r(c1x), C1y = r(c1y), C2x = r(c2x), C2y = r(c2y), Ex = r(ex), Ey = r(ey);
                    t2int(b, C1x - cx); t2int(b, C1y - cy);
                    t2int(b, C2x - C1x); t2int(b, C2y - C1y);
                    t2int(b, Ex - C2x); t2int(b, Ey - C2y); b.u8(8); // rrcurveto
                    cx = Ex; cy = Ey;
                }
                case PathIterator.SEG_CUBICTO -> {
                    int c1x = r(p[0]), c1y = r(p[1]), c2x = r(p[2]), c2y = r(p[3]), ex = r(p[4]), ey = r(p[5]);
                    t2int(b, c1x - cx); t2int(b, c1y - cy);
                    t2int(b, c2x - c1x); t2int(b, c2y - c1y);
                    t2int(b, ex - c2x); t2int(b, ey - c2y); b.u8(8); // rrcurveto
                    cx = ex; cy = ey;
                }
                case PathIterator.SEG_CLOSE -> { }                   // implicit in Type 2
                default -> { }
            }
            it.next();
        }
        b.u8(14);                                                   // endchar
        return b.out();
    }

    // ════════════════════════ CFF table ════════════════════════
    private byte[] cffTable() {
        int n = glyphs.size();

        byte[] nameIndex = index(List.of(psName().getBytes(java.nio.charset.StandardCharsets.US_ASCII)));

        // String INDEX: one custom name per non-notdef glyph ("g1".."g{n-1}") → SID 391+.
        List<byte[]> strings = new ArrayList<>();
        for (int i = 1; i < n; i++) strings.add(("g" + i).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        byte[] stringIndex = index(strings);

        byte[] gsubrIndex = index(List.of());                      // no global subrs

        // charset format 0: SID for gid 1..n-1
        Buf cs = new Buf();
        cs.u8(0);
        for (int i = 1; i < n; i++) cs.u16(391 + (i - 1));
        byte[] charset = cs.out();

        List<byte[]> charStrings = new ArrayList<>(n);
        for (OCDGlyph g : glyphs) charStrings.add(charString(g));
        byte[] charStringsIndex = index(charStrings);

        // Private DICT: nominalWidthX = 0, defaultWidthX = 0 (widths are absolute in charstrings)
        Buf pv = new Buf();
        dictInt(pv, 0); pv.u8(20);                                 // defaultWidthX
        dictInt(pv, 0); pv.u8(21);                                 // nominalWidthX
        byte[] privateDict = pv.out();
        int privSize = privateDict.length;

        // Top DICT (offsets as fixed 5-byte → stable length for backpatch)
        byte[] topDict0 = topDict(0, 0, 0, privSize);
        byte[] topIndex0 = index(List.of(topDict0));

        int header = 4;
        int base = header + nameIndex.length + topIndex0.length + stringIndex.length + gsubrIndex.length;
        int charsetOff = base;
        int csOff = charsetOff + charset.length;
        int privOff = csOff + charStringsIndex.length;

        byte[] topDict = topDict(charsetOff, csOff, privOff, privSize);
        byte[] topIndex = index(List.of(topDict));
        if (topIndex.length != topIndex0.length) throw new IllegalStateException("Top DICT length drift");

        Buf b = new Buf();
        b.u8(1); b.u8(0); b.u8(4); b.u8(4);                        // header: major, minor, hdrSize, offSize
        b.bytes(nameIndex);
        b.bytes(topIndex);
        b.bytes(stringIndex);
        b.bytes(gsubrIndex);
        b.bytes(charset);
        b.bytes(charStringsIndex);
        b.bytes(privateDict);
        return b.out();
    }

    private byte[] topDict(int charsetOff, int csOff, int privOff, int privSize) {
        Buf b = new Buf();
        dictInt(b, xMin); dictInt(b, yMin); dictInt(b, xMax); dictInt(b, yMax); b.u8(5);  // FontBBox
        dictOffset(b, charsetOff); b.u8(15);                       // charset
        dictOffset(b, csOff); b.u8(17);                            // CharStrings
        dictInt(b, privSize); dictOffset(b, privOff); b.u8(18);    // Private (size, offset)
        return b.out();
    }


    // ════════════════════════ sfnt tables ════════════════════════
    private byte[] head() {
        Buf b = new Buf();
        b.u16(1); b.u16(0);                                        // version 1.0
        b.u32(0x00010000L);                                        // fontRevision
        b.u32(0);                                                  // checkSumAdjustment (patched later)
        b.u32(0x5F0F3CF5L);                                        // magic
        b.u16(0x0003);                                             // flags
        b.u16(EM);                                                 // unitsPerEm
        b.u32(0); b.u32(0);                                        // created
        b.u32(0); b.u32(0);                                        // modified
        b.u16(xMin & 0xFFFF); b.u16(yMin & 0xFFFF); b.u16(xMax & 0xFFFF); b.u16(yMax & 0xFFFF);
        b.u16((font.isBold() ? 1 : 0) | (font.isItalic() ? 2 : 0));// macStyle
        b.u16(8);                                                  // lowestRecPPEM
        b.u16(2);                                                  // fontDirectionHint
        b.u16(0);                                                  // indexToLocFormat
        b.u16(0);                                                  // glyphDataFormat
        return b.out();
    }



    private byte[] maxp(int numGlyphs) {
        Buf b = new Buf();
        b.u32(0x00005000L);                                        // version 0.5 (CFF)
        b.u16(numGlyphs);
        return b.out();
    }

    private byte[] cmapTable() {
        byte[] sub = cmap4();
        Buf b = new Buf();
        b.u16(0); b.u16(1);                                        // version, numTables
        b.u16(3); b.u16(1); b.u32(12);                             // (3,1) Windows BMP, offset
        b.bytes(sub);
        return b.out();
    }



    private byte[] os2(int avgAdv) {
        Buf b = new Buf();
        b.u16(4);                                                  // version
        b.u16(avgAdv);                                             // xAvgCharWidth
        b.u16(font.isBold() ? 700 : 400);                          // usWeightClass
        b.u16(5);                                                  // usWidthClass
        b.u16(0);                                                  // fsType (installable)
        b.u16(650); b.u16(700); b.u16(0); b.u16(140);              // subscript
        b.u16(650); b.u16(700); b.u16(0); b.u16(480);              // superscript
        b.u16(50); b.u16(r(font.capHeight() != 0 ? font.capHeight() * 0.6 : 0.3)); // strikeout
        b.u16(0);                                                  // sFamilyClass
        for (int i = 0; i < 10; i++) b.u8(0);                      // panose
        b.u32(1); b.u32(0); b.u32(0); b.u32(0);                    // unicode ranges (basic latin bit)
        b.bytes("JEXT".getBytes(java.nio.charset.StandardCharsets.US_ASCII)); // achVendID
        b.u16((font.isItalic() ? 0x01 : 0) | (font.isBold() ? 0x20 : 0) | (!font.isBold() && !font.isItalic() ? 0x40 : 0));
        b.u16(cmap.isEmpty() ? 0x20 : cmap.firstKey());            // usFirstCharIndex
        b.u16(cmap.isEmpty() ? 0x20 : cmap.lastKey());             // usLastCharIndex
        b.u16(r(font.ascent())); b.u16(r(font.descent())); b.u16(0); // typo asc/desc/linegap
        b.u16(r(font.ascent())); b.u16(-r(font.descent()) & 0xFFFF); // win asc/desc
        b.u32(1); b.u32(0);                                        // code page range (latin1)
        b.u16(r(font.xHeight())); b.u16(r(font.capHeight()));      // sxHeight, sCapHeight
        b.u16(0); b.u16(0x20); b.u16(0);                           // default, break, maxContext
        return b.out();
    }

    private byte[] post() {
        Buf b = new Buf();
        b.u32(0x00030000L);                                        // version 3.0 (no names)
        b.u32(0);                                                  // italicAngle
        b.u16(-100 & 0xFFFF); b.u16(50);                           // underlinePosition, underlineThickness
        b.u32(0);                                                  // isFixedPitch
        b.u32(0); b.u32(0); b.u32(0); b.u32(0);                    // mem usage
        return b.out();
    }

    // ════════════════════════ sfnt assembly ════════════════════════
    private byte[] assemble() {
        int n = glyphs.size();
        int advMax = 0, advSum = 0, cnt = 0;
        for (OCDGlyph g : glyphs) { if (g == null) continue; int a = r(g.advance()); advMax = Math.max(advMax, a); advSum += a; cnt++; }
        int avgAdv = cnt > 0 ? advSum / cnt : 0;

        List<Table> tables = new ArrayList<>();
        tables.add(new Table("CFF ", cffTable()));
        tables.add(new Table("OS/2", os2(avgAdv)));
        tables.add(new Table("cmap", cmapTable()));
        tables.add(new Table("head", head()));
        tables.add(new Table("hhea", hhea(n, advMax)));
        tables.add(new Table("hmtx", hmtx()));
        tables.add(new Table("maxp", maxp(n)));
        tables.add(new Table("name", nameTable()));
        tables.add(new Table("post", post()));
        return sfnt(0x4F54544FL, tables);                          // 'OTTO'
    }

}
