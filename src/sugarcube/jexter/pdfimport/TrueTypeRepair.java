package sugarcube.jexter.pdfimport;

import org.apache.fontbox.ttf.TTFParser;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import sugarcube.jexter.core.ConvertOptions;
import sugarcube.jexter.core.JxProgress;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Embedded TrueType fonts whose {@code post} table FontBox cannot read, repaired before import. */
final class TrueTypeRepair {

    private TrueTypeRepair() {}

    /** DELIBERATE EXCEPTION — the one place jexter mutates PDFBox's loaded model.
     *  The rule everywhere else is "delegate PDF/font interpretation to PDFBox"; here
     *  we can't, because the defect is inside FontBox and the jar is vendored.
     *
     *  <p>Some PDFs embed subset TrueType fonts whose {@code post} (PostScript names)
     *  table is truncated/corrupt; FontBox aborts the *entire* font on it, so PDFBox
     *  substitutes a stand-in and the real glyphs are lost. {@code post} is non-critical
     *  for rendering, so when a {@code FontFile2} fails to parse we downgrade its
     *  {@code post} to version 3.0 (header only, no name array) — then {@code glyf}/
     *  {@code loca}/{@code cmap} load and the true glyphs render. Untouched when healthy;
     *  the edit lives only in the transient in-memory document (never the user's file). */
    static void repair(PDDocument pdf, ConvertOptions opts) {
        // Every embedded font is parsed to find out whether it is healthy — 213 descriptors and 1.7 s on a
        // press book, all of it before a single page is touched. It reports, or the wait is silent.
        // Listing the descriptors is a full COS scan — 663 ms on a 1014-page book, 416 ms on a press
        // issue that has 3 embedded TrueType fonts out of 499 descriptors — and it is one opaque call,
        // so it can be named but not counted through.
        opts.at(JxProgress.Stage.FONTS, 0, 0);
        java.util.List<COSObject> descriptors = pdf.getDocument().getObjectsByType(COSName.getPDFName("FontDescriptor"));
        int seen = 0;
        for (COSObject obj : descriptors) {
            if (seen % 25 == 0) opts.at(JxProgress.Stage.FONTS, seen, descriptors.size());
            seen++;
            if (!(obj.getObject() instanceof COSDictionary d)) continue;
            if (!(d.getDictionaryObject(COSName.FONT_FILE2) instanceof COSStream s)) continue;
            try {
                byte[] b = readStream(s);
                // Screen on the post table before parsing: every repair below rewrites post, so a font
                // whose post is already 3.0 (or has none) cannot be changed by one — the clone would
                // equal the original and would fail the same way. Parsing it to find that out cost
                // 752 ms of the 1071 on a 213-font book, where the directory read costs 23 ms and
                // leaves 10 fonts worth parsing.
                Table post = table(b, "post");
                if (post == null || version(b, post.off) == POST_3_0) continue;
                if (ttfParses(b)) continue;                 // healthy → leave alone
                byte[] fixed = rebuiltPost(b, post);        // first choice: KEEP the names
                if (fixed == null || !ttfParses(fixed)) {
                    // Last resort: drop the names entirely. Only legitimate when the program carries a
                    // cmap, because then the cmap is the code→glyph authority and post is decoration.
                    // With no cmap the names ARE the mapping: a font repaired this way loads, maps every
                    // code to .notdef and paints NOTHING — worse than the substitute PDFBox would have
                    // used, and invisible to every gate but a pixel. Measured on a press page: two faces,
                    // 68 and 73 codes, 0 inked (see the note above rebuiltPost).
                    if (table(b, "cmap") == null) continue;
                    fixed = b.clone();
                    fixed[post.off] = 0; fixed[post.off + 1] = 3; fixed[post.off + 2] = 0; fixed[post.off + 3] = 0;
                    if (!ttfParses(fixed)) continue;        // unfixable → let PDFBox fall back
                }
                try (OutputStream os = s.createOutputStream(COSName.FLATE_DECODE)) { os.write(fixed); }
                s.setItem(COSName.LENGTH1, COSInteger.get(fixed.length));
            } catch (IOException ignored) { }
        }
    }

    private static byte[] readStream(COSStream s) throws IOException {
        try (InputStream in = s.createInputStream()) { return in.readAllBytes(); }
    }

    private static boolean ttfParses(byte[] b) {
        try { new TTFParser(true).parse(new RandomAccessReadBuffer(b)); return true; }
        catch (Exception e) { return false; }
    }

    private static final int POST_3_0 = 0x00030000;
    private static final int POST_HEADER = 32;          // version … maxMemType1, then numberOfGlyphs

    /** One entry of the sfnt table directory: where the entry itself sits, and what it points at. */
    private record Table(int entry, int off, int len) { }

    /** The {@code tag} table of {@code b}, or null when there is none — the one place the table
     *  directory is walked. */
    private static Table table(byte[] b, String tag) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
            in.skipBytes(4);                                    // sfnt version
            int n = in.readUnsignedShort();
            in.skipBytes(6);                                    // searchRange/entrySelector/rangeShift
            for (int i = 0; i < n; i++) {
                int entry = 12 + i * 16;
                byte[] t = new byte[4];
                in.readFully(t);
                in.skipBytes(4);                                // checksum
                long off = in.readInt() & 0xffffffffL;
                long len = in.readInt() & 0xffffffffL;
                if (tag.equals(new String(t, java.nio.charset.StandardCharsets.US_ASCII)) && off + 4 <= b.length)
                    return new Table(entry, (int) off, (int) Math.min(len, b.length - off));
            }
        } catch (IOException malformed) { /* no directory to read: nothing to repair */ }
        return null;
    }

    /**
     * Rebuild a truncated {@code post} 2.0 table, KEEPING every glyph name that is still there.
     *
     * <p>The defect these fonts carry is in the name array alone: the header, the glyph count and the
     * whole index array are intact, and so are the first names — which are the ones the document uses,
     * since a subset is ordered by first use. FontBox nevertheless aborts the entire font when the
     * array ends early, PDFBox substitutes, and the real glyphs are lost.
     *
     * <p>So the table is rewritten with the names that parsed and an empty name for each one missing,
     * APPENDED at the end of the program with the directory entry re-pointed at it: no other table
     * moves, and nothing already valid is touched. Keeping the names matters because a simple
     * non-symbolic TrueType with no {@code cmap} maps a code through its glyph NAME and nothing else —
     * throwing them away is what made two faces of a press page load perfectly and paint nothing.
     *
     * @return the repaired program, or null when the table is not a repairable post 2.0
     */
    private static byte[] rebuiltPost(byte[] b, Table post) {
        try {
            if (version(b, post.off) != 0x00020000) return null;
            int at = post.off + POST_HEADER;
            int numGlyphs = ((b[at] & 255) << 8) | (b[at + 1] & 255);
            if (numGlyphs <= 0 || at + 2 + numGlyphs * 2 > b.length) return null;
            int[] index = new int[numGlyphs];
            int maxIndex = 0;
            for (int i = 0; i < numGlyphs; i++) {
                index[i] = ((b[at + 2 + i * 2] & 255) << 8) | (b[at + 3 + i * 2] & 255);
                maxIndex = Math.max(maxIndex, index[i]);
            }
            int needed = Math.max(0, maxIndex - 257);            // names past the 258 standard Macintosh ones
            // Read the Pascal strings that ARE there, stopping at the first one the table cannot hold.
            java.util.List<byte[]> names = new java.util.ArrayList<>();
            int p = at + 2 + numGlyphs * 2, end = Math.min(post.off + post.len, b.length);
            while (names.size() < needed && p < end) {
                int len = b[p] & 255;
                if (p + 1 + len > end) break;                     // the truncation itself
                names.add(java.util.Arrays.copyOfRange(b, p + 1, p + 1 + len));
                p += 1 + len;
            }
            if (names.isEmpty()) return null;                    // nothing salvaged: not worth the rewrite

            java.io.ByteArrayOutputStream t = new java.io.ByteArrayOutputStream();
            t.write(b, post.off, POST_HEADER + 2 + numGlyphs * 2);   // header + count + index, verbatim
            for (byte[] n : names) { t.write(n.length); t.write(n, 0, n.length); }
            for (int i = names.size(); i < needed; i++) t.write(0);  // an empty name: legal, and unmappable
            byte[] table = t.toByteArray();

            int newOff = (b.length + 3) & ~3;                      // sfnt tables are 4-byte aligned
            byte[] out = java.util.Arrays.copyOf(b, newOff + ((table.length + 3) & ~3));
            System.arraycopy(table, 0, out, newOff, table.length);
            put32(out, post.entry + 4, checksum(table));
            put32(out, post.entry + 8, newOff);
            put32(out, post.entry + 12, table.length);
            return out;
        } catch (RuntimeException malformed) { return null; }
    }

    /** The sfnt table checksum: the sum of the table's 32-bit words, zero-padded to a word. */
    private static int checksum(byte[] t) {
        int sum = 0;
        for (int i = 0; i < t.length; i += 4) {
            int w = 0;
            for (int k = 0; k < 4; k++) w = (w << 8) | (i + k < t.length ? t[i + k] & 255 : 0);
            sum += w;
        }
        return sum;
    }

    private static void put32(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 24); b[at + 1] = (byte) (v >>> 16); b[at + 2] = (byte) (v >>> 8); b[at + 3] = (byte) v;
    }

    private static int version(byte[] b, int at) {
        return ((b[at] & 255) << 24) | ((b[at + 1] & 255) << 16) | ((b[at + 2] & 255) << 8) | (b[at + 3] & 255);
    }
}
