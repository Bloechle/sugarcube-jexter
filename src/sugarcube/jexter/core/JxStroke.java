package sugarcube.jexter.core;

import java.awt.BasicStroke;
import java.util.Arrays;
import java.util.Locale;

/**
 * How an outline is drawn — the pen, without its colour: width, cap, join, miter limit and dash. One
 * type for a path's stroke and a text run's (render modes 1, 2, 5, 6), so every painter reads the same
 * five facts from the same place; the colour stays on the node beside its fill.
 *
 * <p>Immutable: the dash array is copied in and out. {@link #width} 0 is a hairline (PDF {@code 0 w}),
 * still a stroke. Units are the node's own (page units for a path, text space for a run).
 */
public final class JxStroke {

    /** Line cap. The ordinal is the PDF code ({@code J}) and the AWT one; the lower-case name is the keyword
     *  SVG and CSS use ({@code stroke-linecap}). */
    public enum Cap { BUTT, ROUND, SQUARE;
        public static Cap of(int pdf) { return pdf == 1 ? ROUND : pdf == 2 ? SQUARE : BUTT; }
        public String keyword() { return name().toLowerCase(Locale.ROOT); }
        public static Cap of(String keyword) { return "round".equals(keyword) ? ROUND : "square".equals(keyword) ? SQUARE : BUTT; } }

    /** Line join. The ordinal is the PDF code ({@code j}) and the AWT one; the lower-case name is the keyword
     *  SVG and CSS use ({@code stroke-linejoin}). */
    public enum Join { MITER, ROUND, BEVEL;
        public static Join of(int pdf) { return pdf == 1 ? ROUND : pdf == 2 ? BEVEL : MITER; }
        public String keyword() { return name().toLowerCase(Locale.ROOT); }
        public static Join of(String keyword) { return "round".equals(keyword) ? ROUND : "bevel".equals(keyword) ? BEVEL : MITER; } }

    /** A 1-unit butt/miter solid line — PDF's initial graphics state. */
    public static final JxStroke DEFAULT = new JxStroke(1, Cap.BUTT, Join.MITER, 10, null, 0);

    private final double width, miterLimit, dashPhase;
    private final Cap cap;
    private final Join join;
    private final double[] dash;            // null = solid; never empty

    public JxStroke(double width, Cap cap, Join join, double miterLimit, double[] dash, double dashPhase) {
        this.width = width;
        this.cap = cap == null ? Cap.BUTT : cap;
        this.join = join == null ? Join.MITER : join;
        this.miterLimit = miterLimit;
        this.dash = dash == null || dash.length == 0 ? null : dash.clone();
        this.dashPhase = dashPhase;
    }

    public double   width()      { return width; }
    public Cap      cap()        { return cap; }
    public Join     join()       { return join; }
    public double   miterLimit() { return miterLimit; }
    public double[] dash()       { return dash == null ? null : dash.clone(); }
    public double   dashPhase()  { return dashPhase; }
    public boolean  hasDash()    { return dash != null; }

    public JxStroke width(double w) { return new JxStroke(w, cap, join, miterLimit, dash, dashPhase); }

    /** The same pen scaled by {@code k}: width, dash and phase (a run's stroke is in text space). */
    public JxStroke scaled(double k) {
        double[] d = null;
        if (dash != null) { d = dash.clone(); for (int i = 0; i < d.length; i++) d[i] *= k; }
        return new JxStroke(width * k, cap, join, miterLimit, d, dashPhase * k);
    }

    /**
     * The AWT stroke at {@code scale} (the pen's units → the drawing space), never thinner than
     * {@code minWidth} there.
     *
     * <p>The dash is what Java2D accepts. A negative entry, or nothing but zeros, draws solid, as PDF
     * readers do, instead of throwing in the middle of a page. A ZERO-length entry becomes a short one
     * (0.062): PDF says a zero-length dash with a round cap paints a DOT ({@code [0 6] 0 d} with {@code 1 J}
     * is the standard dotted rule, and press pages are full of them), Java2D draws nothing for it, and PDFBox
     * clamps the same way (PDFBOX-2373 / 2929 / 3204 / 3724 / 3813). The pen itself keeps the source's zeros:
     * they are right there, and SVG honours them like PDF. Only this Java2D consumer needs the floor.
     */
    public BasicStroke awt(double scale, double minWidth) {
        float w = (float) Math.max(width * scale, minWidth);
        float miter = (float) Math.max(1, miterLimit);
        float[] d = null;
        if (dash != null) {
            d = new float[dash.length];
            boolean ink = false;
            for (int i = 0; i < dash.length && d != null; i++) {
                if (dash[i] < 0) { d = null; break; }
                if (dash[i] > 0) ink = true;
                d[i] = (float) (Math.max(dash[i], 0.062) * scale);
            }
            if (!ink) d = null;
        }
        return new BasicStroke(w, cap.ordinal(), join.ordinal(), miter, d, (float) Math.max(0, dashPhase * scale));
    }

    @Override public boolean equals(Object o) {
        return o instanceof JxStroke s && width == s.width && cap == s.cap && join == s.join
                && miterLimit == s.miterLimit && Arrays.equals(dash, s.dash) && dashPhase == s.dashPhase;
    }
    @Override public int hashCode() { return Arrays.hashCode(new double[] { width, cap.ordinal(), join.ordinal(), miterLimit, dashPhase }) * 31 + Arrays.hashCode(dash); }
    @Override public String toString() {
        return "JxStroke[" + width + " " + cap + " " + join + " " + miterLimit + (dash == null ? "" : " " + Arrays.toString(dash) + "@" + dashPhase) + "]";
    }
}
