package sugarcube.jexter.font;

import sugarcube.jexter.core.JxPath;
import sugarcube.jexter.ocd.model.OCDFont;
import sugarcube.jexter.ocd.model.OCDGlyph;

import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a TrueType-flavored OpenType font (sfnt {@code 0x00010000} with
 * {@code glyf}/{@code loca}) directly from a normalized {@link OCDFont}. This is
 * the maximally-compatible format: PDFBox embeds it natively (CIDFontType2) and
 * every browser / e-reader renders it via {@code @font-face}.
 *
 * <p>Outlines are em-unit cubic {@link JxPath}s; {@code glyf} stores quadratics,
 * so each outline is converted with {@link JxPath#toQuadratic(double)} at a
 * sub-pixel tolerance. Source glyph ids are remapped to a dense 0..n space
 * (gid 0 = .notdef) and the {@code cmap} (format 4, exposed under both the
 * Windows (3,1) and Unicode (0,3) platform records for compatibility) maps
 * codepoints to those dense gids. Verified by round-tripping through FontBox.
 */
public final class GlyfOtf extends OtfBuilder {

    private static final int EM = 2048;                // power-of-two grid (TrueType convention)
    private static final double QUAD_TOL = 0.00025;    // ~0.5 unit @2048 → sub-pixel

    public static byte[] build(OCDFont f) { return new GlyfOtf(f).assemble(); }

    private int maxPts, maxCtrs;                       // populated while building glyphs (for maxp)

    private GlyfOtf(OCDFont f) { super(f); }

    @Override int em() { return EM; }

    // ════════════════════════ glyf + loca ════════════════════════
    private record Pt(int x, int y, boolean on) {}

    /** One simple-glyph entry, or an empty array for a glyph with no outline. */
    private byte[] glyphData(OCDGlyph g) {
        if (g == null || g.outline() == null || g.outline().isEmpty()) return new byte[0];

        List<List<Pt>> contours = new ArrayList<>();
        List<Pt> cur = null;
        PathIterator it = g.outline().toQuadratic(QUAD_TOL).getPathIterator(null);
        double[] p = new double[6];
        while (!it.isDone()) {
            switch (it.currentSegment(p)) {
                case PathIterator.SEG_MOVETO -> { cur = new ArrayList<>(); contours.add(cur); cur.add(new Pt(r(p[0]), r(p[1]), true)); }
                case PathIterator.SEG_LINETO -> { if (cur != null) cur.add(new Pt(r(p[0]), r(p[1]), true)); }
                case PathIterator.SEG_QUADTO -> { if (cur != null) { cur.add(new Pt(r(p[0]), r(p[1]), false)); cur.add(new Pt(r(p[2]), r(p[3]), true)); } }
                case PathIterator.SEG_CUBICTO -> { /* toQuadratic removes these */ }
                case PathIterator.SEG_CLOSE -> {                    // drop a final point coinciding with the start
                    if (cur != null && cur.size() > 1) {
                        Pt f = cur.get(0), l = cur.get(cur.size() - 1);
                        if (f.on && l.on && f.x == l.x && f.y == l.y) cur.remove(cur.size() - 1);
                    }
                }
                default -> { }
            }
            it.next();
        }
        contours.removeIf(c -> c.isEmpty());
        if (contours.isEmpty()) return new byte[0];

        int npts = 0;
        for (List<Pt> c : contours) npts += c.size();
        maxPts = Math.max(maxPts, npts);
        maxCtrs = Math.max(maxCtrs, contours.size());

        // bbox
        int gx0 = Integer.MAX_VALUE, gy0 = Integer.MAX_VALUE, gx1 = Integer.MIN_VALUE, gy1 = Integer.MIN_VALUE;
        for (List<Pt> c : contours) for (Pt pt : c) { gx0 = Math.min(gx0, pt.x); gy0 = Math.min(gy0, pt.y); gx1 = Math.max(gx1, pt.x); gy1 = Math.max(gy1, pt.y); }

        Buf b = new Buf();
        b.u16(contours.size());
        b.u16(gx0 & 0xFFFF); b.u16(gy0 & 0xFFFF); b.u16(gx1 & 0xFFFF); b.u16(gy1 & 0xFFFF);
        int idx = -1;
        for (List<Pt> c : contours) { idx += c.size(); b.u16(idx); }   // endPtsOfContours
        b.u16(0);                                                       // instructionLength

        // flags (no compression): ON_CURVE bit only → x/y follow as int16 deltas
        for (List<Pt> c : contours) for (Pt pt : c) b.u8(pt.on ? 0x01 : 0x00);
        int px = 0;
        for (List<Pt> c : contours) for (Pt pt : c) { b.u16((pt.x - px) & 0xFFFF); px = pt.x; }
        int py = 0;
        for (List<Pt> c : contours) for (Pt pt : c) { b.u16((pt.y - py) & 0xFFFF); py = pt.y; }

        if ((b.n & 1) == 1) b.u8(0);                                   // pad to even
        return b.out();
    }

    private byte[][] glyfLoca() {
        int n = glyphs.size();
        Buf glyf = new Buf();
        long[] loca = new long[n + 1];
        for (int i = 0; i < n; i++) { loca[i] = glyf.n; glyf.bytes(glyphData(glyphs.get(i))); }
        loca[n] = glyf.n;
        Buf lb = new Buf();
        for (long off : loca) lb.u32(off);                             // long loca (indexToLocFormat = 1)
        return new byte[][]{ glyf.out(), lb.out() };
    }

    // ════════════════════════ sfnt tables ════════════════════════
    private byte[] maxp(int numGlyphs, int maxPts, int maxCtrs) {
        Buf b = new Buf();
        b.u32(0x00010000L);                                            // version 1.0 (TrueType)
        b.u16(numGlyphs);
        b.u16(maxPts); b.u16(maxCtrs);
        b.u16(0); b.u16(0);                                            // composite max
        b.u16(1); b.u16(0);                                            // maxZones, maxTwilightPoints
        b.u16(0); b.u16(0); b.u16(0); b.u16(0); b.u16(0);              // storage/functions/instr/stack
        b.u16(0); b.u16(0);                                            // component elements/depth
        return b.out();
    }

    // ════════════════════════ sfnt assembly ════════════════════════
    private byte[] assemble() {
        int n = glyphs.size();
        byte[][] gl = glyfLoca();                       // also populates maxPts / maxCtrs

        List<Table> tables = new ArrayList<>();
        tables.add(new Table("OS/2", os2()));
        tables.add(new Table("cmap", cmapTable()));
        tables.add(new Table("glyf", gl[0]));
        tables.add(new Table("head", head(0x000B, 1)));
        tables.add(new Table("hhea", hhea(n)));
        tables.add(new Table("hmtx", hmtx()));
        tables.add(new Table("loca", gl[1]));
        tables.add(new Table("maxp", maxp(n, maxPts, maxCtrs)));
        tables.add(new Table("name", nameTable()));
        tables.add(new Table("post", post()));
        return sfnt(0x00010000L, tables);                          // TrueType flavor
    }

}
