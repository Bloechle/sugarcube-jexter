package sugarcube.jexter.font;

import sugarcube.jexter.ocd.model.OCDFont;


/**
 * Compiles a normalized {@link OCDFont} into a real OpenType font. OCDGlyph
 * outlines are em-unit JxPaths (Y-up, baseline 0), consumed directly by the
 * writers: {@link GlyfOtf} (TrueType/glyf, the default — maximally compatible
 * and PDFBox-embeddable) and {@link CffOtf} (CFF, native cubic — for web/EPUB).
 */
public final class JxFont {
    private JxFont() {}

    /** OCDFont -> OpenType font bytes. Default: TrueType-flavored (glyf), maximally compatible
     *  (PDFBox-embeddable + universal browser/e-reader support). */
    public static byte[] toOtf(OCDFont f) {
        return GlyfOtf.build(f);
    }

    /** OCDFont -> CFF-flavored OpenType (native cubic outlines). Ideal for web/EPUB; note that
     *  PDFBox cannot embed CFF-flavored OTF, so {@link #toOtf} (glyf) is used for PDF output. */
    public static byte[] toCffOtf(OCDFont f) {
        return CffOtf.build(f);
    }

    /** The sfnt checksum: the sum of the data's big-endian 32-bit words, zero-padded to a word (OpenType
     *  "Calculating checksums") — for a table, and for the whole file's {@code head.checkSumAdjustment}. */
    public static long checksum(byte[] data) {
        long sum = 0;
        int i = 0, len = data.length;
        while (i + 4 <= len) { sum += ((data[i] & 0xFFL) << 24) | ((data[i + 1] & 0xFFL) << 16) | ((data[i + 2] & 0xFFL) << 8) | (data[i + 3] & 0xFFL); i += 4; }
        if (i < len) { long v = 0; for (int s = 24; i < len; i++, s -= 8) v |= (data[i] & 0xFFL) << s; sum += v; }
        return sum & 0xFFFFFFFFL;
    }
}
