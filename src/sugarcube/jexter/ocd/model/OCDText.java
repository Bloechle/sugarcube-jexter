package sugarcube.jexter.ocd.model;

import sugarcube.jexter.core.JxRect;

import java.util.ArrayList;
import java.util.List;

/**
 * A text run — the glyphs painted by one show-text operation, sharing a single
 * paint state. This is the physical text atom; analysis later wraps runs into
 * {@link OCDParagraph}s in place (lines separated by {@link OCDBreak} tokens; a run
 * lives on the page on its own until then).
 *
 * <p>State is hoisted to the run, not repeated per glyph: font ({@link #fontId}),
 * size, fill colour (argb), and {@link #renderMode}. The baseline placement is the
 * base {@code transform}. Each {@link Glyph} is a lightweight placed reference —
 * a glyph id into the run's font, an x along the baseline, and the unicode it
 * maps to (for selection/extraction) — never the outline, which is shared in the
 * font.
 *
 * <p>Render mode matters: mode 3/7 is invisible text (selectable layer over a
 * scanned image), and modes ≥4 add text clipping.
 */
public final class OCDText extends OCDNode {

    // PDF text render modes (Tr)
    public static final int FILL = 0, STROKE = 1, FILL_STROKE = 2, INVISIBLE = 3,
            FILL_CLIP = 4, STROKE_CLIP = 5, FILL_STROKE_CLIP = 6, CLIP = 7;

    /** A placed glyph: {@code gid} into the run's font, baseline {@code x}, and the text it maps to. */
    public record Glyph(int gid, double x, String unicode) {
        /** A non-empty whitespace glyph — a real space glyph or a {@link sugarcube.jexter.ocd.analysis.Spacer}
         *  space sentinel. Never paints; the single blank-glyph authority (Cleaner strips it, Spacer skips it). */
        public boolean isBlank() { return unicode != null && !unicode.isEmpty() && unicode.isBlank(); }
    }

    private String fontId;             // ref into the document fonts
    private double fontSize;           // Tf size (text space)
    private int    fill = 0xFF000000;  // argb (alpha = ca); default opaque black
    private int    stroke = 0;         // argb stroke paint (modes 1/2/5/6); alpha 0 = none
    private double strokeWidth = 0;    // text-space stroke width (PDF line width through the CTM)
    private int    renderMode = FILL;

    // stroke style — parity with OCDPath; only meaningful when the run strokes (modes 1/2/5/6)
    private int      cap;              // 0=butt 1=round 2=square
    private int      join;            // 0=miter 1=round 2=bevel
    private double   miterLimit = 10;
    private double[] dash;            // null/empty = solid
    private double   dashPhase;

    private final List<Glyph> glyphs = new ArrayList<>();

    public OCDText() {}
    public OCDText(String fontId, double fontSize) { this.fontId = fontId; this.fontSize = fontSize; }

    // ── run state ─────────────────────────────────────────────────────────────
    public String  fontId()             { return fontId; }
    public OCDText fontId(String id)    { this.fontId = id; return this; }
    public double  fontSize()           { return fontSize; }
    public OCDText fontSize(double s)   { this.fontSize = s; return this; }
    public int     fill()               { return fill; }
    public OCDText fill(int argb)       { this.fill = argb; return this; }
    public int     stroke()             { return stroke; }
    public double  strokeWidth()        { return strokeWidth; }
    public OCDText strokePaint(int argb, double w) { this.stroke = argb; this.strokeWidth = w; return this; }
    public int     renderMode()         { return renderMode; }
    public OCDText renderMode(int m)    { this.renderMode = m; return this; }

    // ── stroke style (parity with OCDPath) ──────────────────────────────────────
    public OCDText lineStyle(int cap, int join, double miter, double[] dash, double phase) {
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

    /** A stroke paint is actually present (mode strokes AND the colour isn't transparent). */
    public boolean hasStrokePaint() { return hasStroke() && (stroke >>> 24) != 0; }

    public boolean isInvisible() { return renderMode == INVISIBLE || renderMode == CLIP; }
    public boolean isClipping()  { return renderMode >= FILL_CLIP; }
    public boolean hasFill()     { int m = renderMode & 3; return m == 0 || m == 2; }
    public boolean hasStroke()   { int m = renderMode & 3; return m == 1 || m == 2; }

    // ── glyphs ─────────────────────────────────────────────────────────────────
    public List<Glyph> glyphs()                          { return glyphs; }
    public OCDText add(int gid, double x, String unicode) { glyphs.add(new Glyph(gid, x, unicode)); return this; }
    public OCDText add(Glyph g)                          { glyphs.add(g); return this; }
    public int     count()                               { return glyphs.size(); }

    /** The glyph's position ALONG THE BASELINE, in page units — its page position projected on the
     *  direction the run writes.
     *
     *  <p>Not its page x: a run rotated a quarter turn advances along page Y, its x never moves, and every
     *  word gap measures zero — the spacer then materialises no space at all and a vertical caption reads
     *  {@code LEGENDEVERTICALEDELACOLONNE}. Projecting on the writing direction gives page x exactly for
     *  upright text and the right number for the rest.
     *
     *  <p>The projection is of the ABSOLUTE page position, never of an offset from the run's own origin:
     *  the spacer compares glyphs ACROSS the runs of a line to find the seams between them, so every run
     *  has to answer in one shared frame. Measuring from each run's origin silently doubled the hyphens a
     *  newspaper page recovered. A line holds one writing direction ({@code Liner}), so the projection axis
     *  is the same for every run being compared. */
    public double glyphPageX(Glyph g) {
        var o = transform().apply(0, 0);
        var u = transform().apply(1, 0);
        double ux = u.x() - o.x(), uy = u.y() - o.y();
        double len = Math.hypot(ux, uy);
        var p = transform().apply(g.x(), 0);
        return len < 1e-9 ? p.x() : (p.x() * ux + p.y() * uy) / len;
    }

    /** A new run carrying this run's full paint state (font, size, fill, render mode, stroke, transform,
     *  clip, alpha, blend, role) but <b>no glyphs</b> — the basis for a split run, so the
     *  rendered output is byte-identical once the chosen glyphs are added back. */
    public OCDText copyState() {
        OCDText a = new OCDText(fontId, fontSize).fill(fill).renderMode(renderMode);
        if (hasStroke()) a.strokePaint(stroke, strokeWidth);
        a.lineStyle(cap, join, miterLimit, dash, dashPhase);   // stroke style rides along on a split run
        a.transform(transform());
        a.clipId(clipId());
        a.alpha(alpha());
        a.blend(blend());
        a.role(role());
        a.z(z());                 // keep paint order: a split run paints where the original did (reid overrides on clean)
        return a;
    }

    /** The run's text, for selection / extraction / AI. */
    public String text() {
        var sb = new StringBuilder(glyphs.size());
        for (Glyph g : glyphs) if (g.unicode() != null) sb.append(g.unicode());
        return sb.toString();
    }

    /**
     * Approximate bounds in page space: the glyph x-range by ~1 em tall, placed
     * through the baseline transform. Exact per-glyph extents need the font and
     * aren't required at the model level.
     */
    @Override public JxRect bounds() {
        if (glyphs.isEmpty()) return JxRect.EMPTY;
        // The EXTENT of the glyphs, not first-to-last: a run whose glyphs are laid out right-to-left —
        // Hebrew, Arabic, or any producer emitting in reverse — has its last glyph LEFT of its first, and
        // a first-to-last width collapses to zero. A zero-width run is then discarded by XY-Cut and
        // vanishes from every text projection while still painting correctly on the page: the document
        // looks right and reads short. Measured on a Hebrew line and on a newspaper's masthead.
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (Glyph g : glyphs) { lo = Math.min(lo, g.x()); hi = Math.max(hi, g.x()); }
        JxRect local = new JxRect(lo, -0.2 * fontSize, (hi - lo) + fontSize * 0.5, fontSize);
        return transform.apply(local);
    }
}