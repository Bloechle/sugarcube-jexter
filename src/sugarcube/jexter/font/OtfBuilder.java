package sugarcube.jexter.font;

import sugarcube.jexter.ocd.model.OCDFont;
import sugarcube.jexter.ocd.model.OCDGlyph;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The format-independent half of an sfnt compiler, shared by {@link CffOtf} and {@link GlyfOtf}:
 * the dense gid remap (0 = .notdef), the document-wide bbox, and every table whose bytes do not
 * depend on the outline format — {@code hhea}, {@code hmtx}, {@code cmap} format 4,
 * {@code name} — plus the sfnt directory assembly and its checksum adjustment.
 *
 * <p>What stays in the subclasses is exactly what differs per format: the outline tables
 * ({@code CFF } vs {@code glyf}/{@code loca}), {@code head} (flags, upem, loca format),
 * {@code maxp} (version 0.5 vs 1.0), the {@code cmap} platform records, and {@code OS/2} /
 * {@code post} whose metric constants are hand-tuned to each upem grid. One authority per table:
 * a table's bytes are built in exactly one place.
 */
abstract sealed class OtfBuilder permits CffOtf, GlyfOtf {

    final OCDFont font;
    final List<OCDGlyph> glyphs = new ArrayList<>();        // dense gid → glyph (0 = notdef = null)
    final TreeMap<Integer, Integer> cmap = new TreeMap<>(); // codepoint → dense gid
    int xMin, yMin, xMax, yMax;

    OtfBuilder(OCDFont f) {
        this.font = f;
        List<Integer> src = new ArrayList<>(f.glyphs().keySet());
        java.util.Collections.sort(src);
        glyphs.add(null);                                          // gid 0 = .notdef
        Map<Integer, Integer> oldToNew = new java.util.HashMap<>();
        for (int g : src) { oldToNew.put(g, glyphs.size()); glyphs.add(f.glyph(g)); }
        for (Map.Entry<Integer, Integer> e : f.cmap().entrySet()) {
            Integer dense = oldToNew.get(e.getValue());
            if (dense != null && e.getKey() >= 0 && e.getKey() <= 0xFFFF) cmap.put(e.getKey(), dense);
        }
        boolean any = false;
        for (OCDGlyph g : glyphs) {
            if (g == null || g.outline() == null || g.outline().isEmpty()) continue;
            Rectangle2D b = g.outline().bounds();
            int gx0 = r(b.getMinX()), gy0 = r(b.getMinY()), gx1 = r(b.getMaxX()), gy1 = r(b.getMaxY());
            if (!any) { xMin = gx0; yMin = gy0; xMax = gx1; yMax = gy1; any = true; }
            else { xMin = Math.min(xMin, gx0); yMin = Math.min(yMin, gy0); xMax = Math.max(xMax, gx1); yMax = Math.max(yMax, gy1); }
        }
        if (!any) { xMin = 0; yMin = 0; xMax = em(); yMax = em(); }
    }

    /** Units per em — a per-format constant ({@code 1000} CFF, {@code 2048} TrueType). */
    abstract int em();

    /** Em-fraction → font units on this builder's grid. */
    final int r(double em) { return (int) Math.round(em * em()); }

    // ════════════════════════ growable big-endian buffer ════════════════════════
    static final class Buf {
        byte[] a = new byte[256];
        int n = 0;
        void ensure(int k) { if (n + k > a.length) a = Arrays.copyOf(a, Math.max(a.length * 2, n + k)); }
        void u8(int v)  { ensure(1); a[n++] = (byte) v; }
        void u16(int v) { u8(v >> 8); u8(v); }
        void u32(long v){ u8((int) (v >> 24)); u8((int) (v >> 16)); u8((int) (v >> 8)); u8((int) v); }
        void bytes(byte[] b) { ensure(b.length); System.arraycopy(b, 0, a, n, b.length); n += b.length; }
        void off(int v, int size) { for (int s = size - 1; s >= 0; s--) u8(v >> (8 * s)); }   // CFF INDEX offsets

        byte[] out() { return Arrays.copyOf(a, n); }
    }

    // ════════════════════════ format-independent tables ════════════════════════
    final byte[] hhea(int numGlyphs, int advMax) {
        Buf b = new Buf();
        b.u32(0x00010000L);
        b.u16(r(font.ascent())); b.u16(r(font.descent())); b.u16(0);
        b.u16(advMax);
        b.u16(0); b.u16(0); b.u16(xMax & 0xFFFF);
        b.u16(1); b.u16(0); b.u16(0);
        b.u16(0); b.u16(0); b.u16(0); b.u16(0);
        b.u16(0);
        b.u16(numGlyphs);
        return b.out();
    }

    final byte[] hmtx() {
        Buf b = new Buf();
        for (OCDGlyph g : glyphs) {
            int adv = (g == null) ? 0 : r(g.advance());
            int lsb = (g == null || g.outline() == null || g.outline().isEmpty()) ? 0 : r(g.outline().bounds().getMinX());
            b.u16(adv); b.u16(lsb & 0xFFFF);
        }
        return b.out();
    }

    final byte[] cmap4() {
        List<int[]> segs = new ArrayList<>();
        Integer start = null, prev = null;
        for (int c : cmap.keySet()) {
            if (start == null) { start = c; prev = c; }
            else if (c == prev + 1) prev = c;
            else { segs.add(new int[]{start, prev}); start = c; prev = c; }
        }
        if (start != null) segs.add(new int[]{start, prev});

        int segCount = segs.size() + 1;
        int[] endCode = new int[segCount], startCode = new int[segCount], idDelta = new int[segCount], idRangeOffset = new int[segCount];
        List<Integer> gia = new ArrayList<>();
        for (int i = 0; i < segs.size(); i++) {
            int s = segs.get(i)[0], e = segs.get(i)[1];
            startCode[i] = s; endCode[i] = e;
            boolean consecutive = true;
            int g0 = cmap.get(s);
            for (int c = s; c <= e; c++) if (cmap.get(c) != g0 + (c - s)) { consecutive = false; break; }
            if (consecutive) { idDelta[i] = (g0 - s) & 0xFFFF; idRangeOffset[i] = 0; }
            else { idDelta[i] = 0; idRangeOffset[i] = 2 * ((segCount - i) + gia.size()); for (int c = s; c <= e; c++) gia.add(cmap.get(c)); }
        }
        int last = segCount - 1;
        startCode[last] = 0xFFFF; endCode[last] = 0xFFFF; idDelta[last] = 1; idRangeOffset[last] = 0;

        int segX2 = segCount * 2;
        int searchRange = 2 * Integer.highestOneBit(segCount);
        int entrySelector = Integer.numberOfTrailingZeros(Integer.highestOneBit(segCount));
        Buf b = new Buf();
        int length = 16 + segCount * 8 + gia.size() * 2;
        b.u16(4); b.u16(length); b.u16(0);
        b.u16(segX2); b.u16(searchRange); b.u16(entrySelector); b.u16(segX2 - searchRange);
        for (int i = 0; i < segCount; i++) b.u16(endCode[i]);
        b.u16(0);
        for (int i = 0; i < segCount; i++) b.u16(startCode[i]);
        for (int i = 0; i < segCount; i++) b.u16(idDelta[i]);
        for (int i = 0; i < segCount; i++) b.u16(idRangeOffset[i]);
        for (int g : gia) b.u16(g);
        return b.out();
    }

    final byte[] nameTable() {
        String ps = psName();
        String fam = (font.family() != null && !font.family().isEmpty()) ? font.family() : ps;
        String sub = (font.isBold() && font.isItalic()) ? "Bold Italic" : font.isBold() ? "Bold" : font.isItalic() ? "Italic" : "Regular";
        int[] ids = {1, 2, 3, 4, 6};
        String[] vals = {fam, sub, ps + "-" + sub, fam + " " + sub, ps};
        Buf recs = new Buf();
        Buf strs = new Buf();
        for (int i = 0; i < ids.length; i++) {
            byte[] u = vals[i].getBytes(java.nio.charset.StandardCharsets.UTF_16BE);
            recs.u16(3); recs.u16(1); recs.u16(0x409); recs.u16(ids[i]); recs.u16(u.length); recs.u16(strs.n);
            strs.bytes(u);
        }
        Buf b = new Buf();
        b.u16(0); b.u16(ids.length); b.u16(6 + 12 * ids.length);
        b.bytes(recs.out()); b.bytes(strs.out());
        return b.out();
    }

    final String psName() {
        String s = font.name() != null ? font.name() : (font.family() != null ? font.family() : "Font");
        if (s.indexOf('+') == 6) s = s.substring(7);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 60; i++) {
            char c = s.charAt(i);
            if (c > 0x20 && c < 0x7F && "()<>[]{}/%".indexOf(c) < 0) sb.append(c);
        }
        return sb.length() == 0 ? "Font" : sb.toString();
    }

    // ════════════════════════ sfnt assembly ════════════════════════
    record Table(String tag, byte[] data) {}

    /** Sort, lay out and checksum the directory, then patch {@code head.checkSumAdjustment} —
     *  identical for both flavors; only the magic ({@code 'OTTO'} vs {@code 0x00010000}) differs. */
    final byte[] sfnt(long sfntVersion, List<Table> tables) {
        tables.sort((x, y) -> x.tag.compareTo(y.tag));
        int numTables = tables.size();
        int searchRange = 16 * Integer.highestOneBit(numTables);
        int entrySelector = Integer.numberOfTrailingZeros(Integer.highestOneBit(numTables));

        int offset = 12 + numTables * 16;
        int[] offs = new int[numTables];
        int headIdx = -1;
        for (int i = 0; i < numTables; i++) {
            offs[i] = offset;
            if (tables.get(i).tag.equals("head")) headIdx = i;
            offset += (tables.get(i).data.length + 3) & ~3;
        }

        Buf b = new Buf();
        b.u32(sfntVersion);
        b.u16(numTables); b.u16(searchRange); b.u16(entrySelector); b.u16(numTables * 16 - searchRange);
        for (int i = 0; i < numTables; i++) {
            Table t = tables.get(i);
            b.bytes(t.tag.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            b.u32(checksum(t.data));
            b.u32(offs[i]);
            b.u32(t.data.length);
        }
        for (Table t : tables) {
            b.bytes(t.data);
            int pad = ((t.data.length + 3) & ~3) - t.data.length;
            for (int i = 0; i < pad; i++) b.u8(0);
        }

        byte[] out = b.out();
        long adj = (0xB1B0AFBAL - checksum(out)) & 0xFFFFFFFFL;
        int ho = offs[headIdx];
        out[ho + 8] = (byte) (adj >> 24); out[ho + 9] = (byte) (adj >> 16); out[ho + 10] = (byte) (adj >> 8); out[ho + 11] = (byte) adj;
        return out;
    }

    static long checksum(byte[] data) {
        long sum = 0;
        int i = 0, len = data.length;
        while (i + 4 <= len) { sum += ((data[i] & 0xFFL) << 24) | ((data[i + 1] & 0xFFL) << 16) | ((data[i + 2] & 0xFFL) << 8) | (data[i + 3] & 0xFFL); i += 4; }
        if (i < len) { long v = 0; for (int s = 24; i < len; i++, s -= 8) v |= (data[i] & 0xFFL) << s; sum += v; }
        return sum & 0xFFFFFFFFL;
    }
}
