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
}
