package sugarcube.jexter.core;

import java.util.Locale;

/**
 * The single number-formatting rule for everything we serialize (XML/SVG/JSON
 * path data, coordinates, sizes, matrices). Integral values print without a
 * decimal point; otherwise they are rounded to {@value #DECIMALS} places with
 * trailing zeros stripped — so {@code 1.5000 → 1.5} and {@code 102.829956… → 102.83}.
 *
 * <p>The precision is fixed at {@value #DECIMALS} places — plenty at page scale, where 1e-4 pt is far
 * below a device pixel. It is deliberately a constant and not a setting: it would have to be global to
 * reach the core serializers ({@link JxPath}, {@link JxTransform}, {@link JxStringer}), and a global
 * would make two concurrent conversions race. One document per thread, many documents in parallel, is
 * the engine's concurrency contract — this class holds no mutable state so that it stays true.
 */
public final class JxNum {

    private JxNum() {}

    /** Export decimal precision (places after the point). */
    private static final int DECIMALS = 4;

    private static final long[] POW10 = {1L, 10L, 100L, 1_000L, 10_000L, 100_000L,
            1_000_000L, 10_000_000L, 100_000_000L, 1_000_000_000L};

    public static String fmt(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0";
        if (v == (long) v) return Long.toString((long) v);
        long scale = POW10[DECIMALS];
        double av = Math.abs(v);
        // Hand-rolled rather than String.format: this is the hottest method of every serializer
        // (one call per path coordinate, matrix cell and size) and the Formatter machinery — parsing
        // a format string built by concatenation, then DoubleToDecimal — measured 14% of a whole
        // PDF → OCD-EPUB conversion. Outside the long range the general case still answers.
        if (av >= (double) (Long.MAX_VALUE / 2) / scale)
            return String.format(Locale.US, "%." + DECIMALS + "f", v);
        long r = Math.round(av * scale);
        if (r == 0) return "0";
        StringBuilder b = new StringBuilder(24);
        if (v < 0) b.append('-');
        b.append(r / scale);
        long frac = r % scale;
        if (frac != 0) {
            b.append('.');
            for (long p = scale / 10; p > 0 && frac > 0; p /= 10) {   // stops on the last non-zero digit
                b.append((char) ('0' + frac / p));
                frac %= p;
            }
        }
        return b.toString();
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
