package sugarcube.jexter.ocd.model;

import sugarcube.jexter.core.JxColor;
import sugarcube.jexter.core.JxTransform;

/**
 * A linear (axial) or radial gradient fill — a first-class paint, so gradient-filled vector
 * shapes and {@code sh} shadings keep their true colour ramp instead of being rasterised to an
 * image or flattened to grey.
 *
 * <p>Maps 1:1 to PDF axial/radial shadings (types 2 &amp; 3), Java2D {@code LinearGradientPaint}/
 * {@code RadialGradientPaint}, and SVG {@code <linearGradient>}/{@code <radialGradient>}.
 * Coordinates live in the gradient's own space; {@link #transform()} maps that space to page
 * space (the SVG {@code gradientTransform} / a Java2D gradient transform), so a sheared or
 * non-uniform CTM turns a radial gradient into the correct ellipse without baking the distortion
 * into the stops.
 *
 * <p>Stops are parallel arrays: {@code offsets[i]} in {@code [0,1]} (non-decreasing) carry the
 * sRGB+alpha {@code colors[i]}. {@code extend0}/{@code extend1} mirror the PDF {@code Extend}
 * flags (whether the ramp pads past each end of the axis). {@link #flatArgb()} gives a
 * representative solid colour for consumers that cannot draw a ramp (Markdown / DocTags, or a PDF
 * writer without shading support).
 *
 * <p>Immutable value object; the arrays are not copied, so callers must not mutate them after
 * construction.
 */
public final class OCDGradient {

    public enum Kind { LINEAR, RADIAL }

    private final Kind        kind;
    private final double[]    coords;     // LINEAR: x0,y0,x1,y1 ; RADIAL: x0,y0,r0,x1,y1,r1
    private final float[]     offsets;    // stop positions in [0,1], non-decreasing
    private final int[]       colors;     // stop argb, parallel to offsets
    private final JxTransform transform;  // gradient space → page space
    private final boolean     extend0;    // pad before the first axis point
    private final boolean     extend1;    // pad after the last axis point

    public OCDGradient(Kind kind, double[] coords, float[] offsets, int[] colors,
                       JxTransform transform, boolean extend0, boolean extend1) {
        this.kind      = kind;
        this.coords    = coords;
        this.offsets   = offsets;
        this.colors    = colors;
        this.transform = transform != null ? transform : JxTransform.IDENTITY;
        this.extend0   = extend0;
        this.extend1   = extend1;
    }

    public Kind        kind()      { return kind; }
    public boolean     isLinear()  { return kind == Kind.LINEAR; }
    public boolean     isRadial()  { return kind == Kind.RADIAL; }
    public double[]    coords()    { return coords; }
    public float[]     offsets()   { return offsets; }
    public int[]       colors()    { return colors; }
    public JxTransform transform() { return transform; }
    public boolean     extend0()   { return extend0; }
    public boolean     extend1()   { return extend1; }
    public int         stopCount() { return offsets != null ? offsets.length : 0; }

    /** Whether the stop arrays are well-formed enough to render (≥ 2 parallel stops). */
    public boolean isValid() {
        return kind != null && offsets != null && colors != null
                && offsets.length >= 2 && offsets.length == colors.length
                && coords != null && coords.length == (isLinear() ? 4 : 6);
    }

    /** Representative solid colour (mean of the stops) for consumers that cannot render a ramp. */
    public int flatArgb() {
        if (colors == null || colors.length == 0) return 0;
        if (colors.length == 1) return colors[0];
        long a = 0, r = 0, g = 0, b = 0;
        for (int c : colors) {
            a += (c >>> 24) & 0xFF; r += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF;
        }
        int n = colors.length;
        return JxColor.rgba((int) (r / n), (int) (g / n), (int) (b / n), (int) (a / n)).argb();
    }
}
