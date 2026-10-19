package sugarcube.jexter.ocd.model;

import sugarcube.jexter.core.JxPath;
import sugarcube.jexter.core.JxRect;
import sugarcube.jexter.core.JxStroke;

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
    private JxStroke pen = JxStroke.DEFAULT;   // how the outline is drawn, in page units

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
    public OCDPath stroke(int argb)        { this.stroke = argb; return this; }
    public JxStroke pen()                  { return pen; }
    public OCDPath pen(JxStroke p)         { this.pen = p == null ? JxStroke.DEFAULT : p; return this; }

    /** Optional gradient fill. When present it is the fill; {@link #fill()} stays as a flat
     *  fallback (its argb should be set to {@link OCDGradient#flatArgb()} for non-gradient consumers). */
    public OCDGradient fillGradient()             { return fillGradient; }
    public OCDPath     fillGradient(OCDGradient g) { this.fillGradient = g; return this; }
    public boolean     hasGradient()              { return fillGradient != null && fillGradient.isValid(); }

    public boolean isFilled()   { return (fill   >>> 24) != 0 || hasGradient(); }
    public boolean isStroked()  { return (stroke >>> 24) != 0; }   // width 0 = hairline (PDF `0 w`), still a stroke

    /** A full copy. {@link sugarcube.jexter.core.JxPath} is a {@code Path2D} and therefore MUTABLE, so
     *  the geometry is duplicated rather than shared — two pages that paint the same drawing must not
     *  edit each other. The gradient is immutable, and shared. */
    @Override public OCDPath copy() {
        OCDPath p = copyInto(new OCDPath(geometry == null ? null : new sugarcube.jexter.core.JxPath(geometry)));
        p.fill = fill; p.fillGradient = fillGradient; p.stroke = stroke; p.pen = pen;   // the pen is immutable
        return p;
    }

    // ── geometry-derived bounds ───────────────────────────────────────────────
    @Override public JxRect bounds() {
        if (geometry == null) return JxRect.EMPTY;
        return transform.apply(JxRect.of(geometry.bounds()));
    }
}
