package sugarcube.jexter.pdfimport;

import sugarcube.jexter.ocd.model.OCDOutputIntent;

import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code DeviceCMYK} → sRGB through the document's OWN output intent, as Acrobat and MuPDF do, instead of
 * PDFBox's built-in CMYK profile, which ignores the intent. Measured against MuPDF on the same file with
 * the intent (Coated FOGRA39): within 1–2 levels on every swatch, where the built-in profile was up to 14
 * off (0,0,0,1 → 43,43,42 vs 28,28,27). {@link ICC_ColorSpace#toRGB} is the path that agrees; a
 * {@code ColorConvertOp} over the same profile does not (it lands on PDFBox's numbers).
 *
 * <p>One per document, shared by every page: parsing a press profile (650 KB) per page would dwarf the
 * page. {@code toRGB} is slow per call, so a colour is converted once — a page reuses a handful.
 */
final class OutputIntentCmyk {

    private final ICC_ColorSpace cs;
    private final Map<Long, float[]> cache = new HashMap<>();

    private OutputIntentCmyk(ICC_ColorSpace cs) { this.cs = cs; }

    /** The converter for a CMYK intent, or null — no intent, an RGB one, or a profile Java will not read. */
    static OutputIntentCmyk of(OCDOutputIntent oi) {
        if (oi == null || !oi.isCmyk()) return null;
        try {
            ICC_Profile p = ICC_Profile.getInstance(oi.profile());
            if (p.getNumComponents() != 4) return null;
            return new OutputIntentCmyk(new ICC_ColorSpace(p));
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /** sRGB [0..1] of a CMYK value [0..1]. */
    float[] toRGB(float[] cmyk) {
        long key = 0;
        for (int i = 0; i < 4; i++) key = key * 1001 + Math.round(Math.max(0, Math.min(1, cmyk[i])) * 1000);
        synchronized (cache) {
            return cache.computeIfAbsent(key, k -> cs.toRGB(new float[]{ cmyk[0], cmyk[1], cmyk[2], cmyk[3] }));
        }
    }
}
