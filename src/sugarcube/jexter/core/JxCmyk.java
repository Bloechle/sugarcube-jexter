package sugarcube.jexter.core;

import java.util.Locale;

/**
 * A paint's SOURCE colour when the document stated it in CMYK — kept beside the model's sRGB, never
 * instead of it. The model paints in sRGB everywhere; this rides along so that a projection that CAN
 * speak CMYK (the PDF writer) states the colour the document stated, and the reader's own CMYK
 * profile decides how it looks, exactly as it does for the source. Flattened to sRGB and written back,
 * a {@code 1 0.5 0 0.2 k} background came out visibly darker in Acrobat than in the original, because
 * our conversion and Acrobat's profile disagree — and so do poppler, MuPDF and PDFBox, by up to 18
 * levels on that one colour.
 *
 * <p>{@code rgb} is the sRGB the colour was resolved to at import. It is the proof the pair still
 * holds: an editor that recolours a node changes its argb and leaves this behind, and {@link #stands}
 * then refuses it — the new colour wins, the stale CMYK is simply not used.
 *
 * <p>Serialized as {@code "c m y k #rrggbb"} (FORMAT §B3, {@code data-cmyk} / {@code data-cmyk-stroke}).
 */
public record JxCmyk(float c, float m, float y, float k, int rgb) {

    public JxCmyk {
        c = clamp(c); m = clamp(m); y = clamp(y); k = clamp(k);
        rgb &= 0xFFFFFF;
    }

    /** True when {@code argb} is still the colour this CMYK was resolved to (alpha ignored). */
    public boolean stands(int argb) { return (argb & 0xFFFFFF) == rgb; }

    public String toData() {
        return JxNum.fmt(c) + ' ' + JxNum.fmt(m) + ' ' + JxNum.fmt(y) + ' ' + JxNum.fmt(k)
                + String.format(Locale.ROOT, " #%06x", rgb);
    }

    /** The inverse of {@link #toData}; null for anything that is not exactly that shape. */
    public static JxCmyk parse(String s) {
        if (s == null) return null;
        String[] t = s.trim().split("\\s+");
        if (t.length != 5 || !t[4].startsWith("#") || t[4].length() != 7) return null;
        try {
            return new JxCmyk(Float.parseFloat(t[0]), Float.parseFloat(t[1]), Float.parseFloat(t[2]),
                    Float.parseFloat(t[3]), Integer.parseInt(t[4].substring(1), 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static float clamp(float v) { return Float.isNaN(v) ? 0f : Math.max(0f, Math.min(1f, v)); }
}
