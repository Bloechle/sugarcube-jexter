package sugarcube.jexter.ocd.model;

import sugarcube.jexter.core.JxPath;
import sugarcube.jexter.core.JxRect;

/**
 * A vector path. Geometry is a {@link JxPath}; its winding rule <i>is</i> the
 * fill rule (non-zero vs even-odd) — there is no separate flag.
 *
 * <p>Paths arrive from PDFBox already in page space (the stream engine applies
 * the CTM), so their geometry is page-space and the base {@code transform} is
 * usually identity; it can still be set to move the path as a unit.
 *
 * <p>Fill and stroke are packed sRGB+alpha ({@code int argb}); the alpha channel
 * carries the fill / stroke opacity (PDF {@code ca} / {@code CA}), so there are
 * no separate alpha fields — the base scalar {@code alpha} is an extra (group)
 * multiplier. A colour with alpha 0 means "absent".
 */
public final class OCDPath extends OCDNode {

    private JxPath geometry;            // path geometry; winding rule = fill rule
    private int    fill   = 0;          // argb; alpha 0 = no fill
    private OCDGradient fillGradient;   // optional gradient fill; when set it overrides the solid fill
    private int    stroke = 0;          // argb; alpha 0 = no stroke
    private double strokeWidth = 0;

    // stroke style
    private int      cap;               // 0=butt 1=round 2=square
    private int      join;              // 0=miter 1=round 2=bevel
    private double   miterLimit = 10;
    private double[] dash;              // null/empty = solid
    private double   dashPhase;

    public OCDPath() {}
    public OCDPath(JxPath geometry) { this.geometry = geometry; }

    // ── geometry ─────────────────────────────────────────────────────────────
    public JxPath  geometry()              { return geometry; }
    public OCDPath geometry(JxPath g)      { this.geometry = g; return this; }
    public boolean isEvenOdd()             { return geometry != null && geometry.isEvenOdd(); }

    // ── paint ────────────────────────────────────────────────────────────────
    public int     fill()                  { return fill; }
    public OCDPath fill(int argb)          { this.fill = argb; return this; }
    public int     stroke()                { return stroke; }
    public OCDPath stroke(int argb, double w) { this.stroke = argb; this.strokeWidth = w; return this; }
    public double  strokeWidth()           { return strokeWidth; }

    /** Optional gradient fill. When present it is the fill; {@link #fill()} stays as a flat
     *  fallback (its argb should be set to {@link OCDGradient#flatArgb()} for non-gradient consumers). */
    public OCDGradient fillGradient()             { return fillGradient; }
    public OCDPath     fillGradient(OCDGradient g) { this.fillGradient = g; return this; }
    public boolean     hasGradient()              { return fillGradient != null && fillGradient.isValid(); }

    public boolean isFilled()   { return (fill   >>> 24) != 0 || hasGradient(); }
    public boolean isStroked()  { return (stroke >>> 24) != 0; }   // width 0 = hairline (PDF `0 w`), still a stroke

    // ── stroke style ─────────────────────────────────────────────────────────
    public OCDPath lineStyle(int cap, int join, double miter, double[] dash, double phase) {
        this.cap = cap; this.join = join; this.miterLimit = miter;
        this.dash = (dash != null && dash.length > 0) ? dash : null; this.dashPhase = phase;
        return this;
    }
    public int      cap()        { return cap; }
    public int      join()       { return join; }
    public double   miterLimit() { return miterLimit; }
    public double[] dash()       { return dash; }
    public double   dashPhase()  { return dashPhase; }
    public boolean  hasDash()    { return dash != null && dash.length > 0; }

    // ── geometry-derived bounds ───────────────────────────────────────────────
    @Override public JxRect bounds() {
        if (geometry == null) return JxRect.EMPTY;
        return transform.apply(JxRect.of(geometry.bounds()));
    }
}
