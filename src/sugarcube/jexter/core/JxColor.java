package sugarcube.jexter.core;

import java.util.Locale;

/**
 * A packed sRGB colour with alpha — {@code 0xAARRGGBB}. The model stores the raw
 * {@code int} on nodes (compact); this value type wraps it for conversions, the
 * sRGB is the model's single colour space.
 */
public record JxColor(int argb) {

    public static final JxColor BLACK       = new JxColor(0xFF000000);
    public static final JxColor WHITE       = new JxColor(0xFFFFFFFF);
    public static final JxColor TRANSPARENT = new JxColor(0x00000000);

    public static JxColor rgb(int r, int g, int b) { return rgba(r, g, b, 255); }

    public static JxColor rgba(int r, int g, int b, int a) {
        return new JxColor((a & 0xFF) << 24 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | (b & 0xFF));
    }

    /** From a [0..1] RGB triplet (e.g. PDFBox {@code PDColorSpace.toRGB}), opaque. */
    public static JxColor ofRgb(float[] rgb) {
        return rgb == null ? BLACK
                : rgba(Math.round(rgb[0] * 255), Math.round(rgb[1] * 255), Math.round(rgb[2] * 255), 255);
    }

    public int a() { return (argb >>> 24) & 0xFF; }
    public int r() { return (argb >> 16) & 0xFF; }
    public int g() { return (argb >> 8) & 0xFF; }
    public int b() { return argb & 0xFF; }

    public float alpha() { return a() / 255f; }

    /** Same colour with alpha replaced (folds an ExtGState ca/CA into the colour). */
    public JxColor withAlpha(float a) { return rgba(r(), g(), b(), Math.round(a * 255)); }

    public boolean isOpaque()      { return a() == 0xFF; }
    public boolean isTransparent() { return a() == 0; }

    /** CSS form: {@code #rrggbb} when opaque, otherwise {@code rgba(r,g,b,a)}. */
    public String css() {
        return isOpaque()
                ? String.format("#%02x%02x%02x", r(), g(), b())
                : String.format(Locale.US, "rgba(%d,%d,%d,%.3f)", r(), g(), b(), alpha());
    }

    /** {@code #rrggbb} (alpha dropped — pair with a separate opacity). */
    public String rgbHex() { return String.format("#%02x%02x%02x", r(), g(), b()); }

    /** Canonical 8-digit hex in <b>CSS/SVG order</b> {@code #RRGGBBAA} (alpha last) — the lossless
     *  serialized form for the OCD model. A web client can assign it straight to a CSS colour. */
    public String hex() { return String.format("#%02x%02x%02x%02x", r(), g(), b(), a()); }

    /** Parse {@code #RRGGBBAA} (alpha last) — or {@code #RRGGBB} (taken as opaque) — into a colour. */
    public static JxColor ofHex(String s) {
        if (s == null || s.isEmpty()) return TRANSPARENT;
        long v = Long.parseLong(s.charAt(0) == '#' ? s.substring(1) : s, 16);
        int len = (s.charAt(0) == '#' ? s.length() - 1 : s.length());
        int r, g, b, a;
        if (len >= 8) { r = (int) ((v >> 24) & 0xFF); g = (int) ((v >> 16) & 0xFF); b = (int) ((v >> 8) & 0xFF); a = (int) (v & 0xFF); }
        else          { r = (int) ((v >> 16) & 0xFF); g = (int) ((v >> 8) & 0xFF); b = (int) (v & 0xFF); a = 0xFF; }
        return rgba(r, g, b, a);
    }

    @Override public String toString() { return css(); }
}
