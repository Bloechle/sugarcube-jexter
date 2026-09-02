package sugarcube.jexter.core;

import java.util.Locale;

/**
 * The single number-formatting rule for everything we serialize (XML/SVG/JSON
 * path data, coordinates, sizes, matrices). Integral values print without a
 * decimal point; otherwise they are rounded to {@link #decimals} places with
 * trailing zeros stripped — so {@code 1.5000 → 1.5} and {@code 102.829956… → 102.83}.
 *
 * <p>{@link #decimals} is the export precision. It defaults to 4 (plenty at page
 * scale: 1e-4 pt is far below a device pixel) and is the value behind
 * {@code ConvertOptions.EXPORT_PRECISION}; writers set it before serializing.
 */
public final class JxNum {

    private JxNum() {}

    /** Export decimal precision (places after the point). Default 4. */
    public static volatile int decimals = 4;

    public static String fmt(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0";
        if (v == (long) v) return Long.toString((long) v);
        String s = String.format(Locale.US, "%." + decimals + "f", v);
        int i = s.length() - 1;
        while (i > 0 && s.charAt(i) == '0') i--;
        if (s.charAt(i) == '.') i--;
        return s.substring(0, i + 1);
    }

    /** Median of {@code xs} (sorted copy, upper-middle element on ties); 0 for an empty array.
     *  The single median authority for the analysis passes — they previously each rolled their own. */
    public static double median(double[] xs) {
        if (xs == null || xs.length == 0) return 0;
        double[] c = xs.clone();
        java.util.Arrays.sort(c);
        return c[c.length / 2];
    }

    /** Median of {@code xs} with index {@code skip} left out (so a single outlier never sets its own
     *  reference); {@code fallback} when nothing remains. */
    public static double medianExcept(double[] xs, int skip, double fallback) {
        if (xs.length <= 1) return fallback;
        double[] g = new double[xs.length - 1];
        for (int i = 0, j = 0; i < xs.length; i++) if (i != skip) g[j++] = xs[i];
        return median(g);
    }
}
