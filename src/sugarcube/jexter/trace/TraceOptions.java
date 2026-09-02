package sugarcube.jexter.trace;

/**
 * Tuning for {@link Tracer}. Defaults match Potrace's published defaults
 * ({@code alphaMax = 1.0}, {@code optTolerance = 0.2}, minority turn policy),
 * which give clean, compact curves on typical line art and logos.
 */
public final class TraceOptions {

    public enum Mode {
        /** Single threshold → one black layer (line art, scans, glyphs). */
        BW,
        /** Median-cut palette → one filled layer per colour, stacked back-to-front. */
        COLOR
    }

    /** Tie-break at a diagonally ambiguous boundary corner (Selinger §2.1). */
    public enum TurnPolicy { BLACK, WHITE, LEFT, RIGHT, MINORITY, MAJORITY }

    public Mode       mode         = Mode.COLOR;
    public int        colors       = 8;            // palette size in COLOR mode (2..256)
    public int        threshold    = 128;          // luminance cut in BW mode (0..255)
    public boolean    invert       = false;        // BW: trace light-on-dark instead
    public int        turdSize     = 2;            // drop regions with area ≤ this (px²)
    public TurnPolicy turnPolicy   = TurnPolicy.MINORITY;
    public double     alphaMax     = 1.0;          // corner threshold; higher → rounder
    public boolean    optimize     = true;         // join Béziers within optTolerance
    public double     optTolerance = 0.2;          // optiCurve error budget (px)

    public TraceOptions() {}

    // ── fluent setters ───────────────────────────────────────────────────────
    public TraceOptions mode(Mode m)             { this.mode = m; return this; }
    public TraceOptions colors(int n)            { this.colors = Math.max(2, Math.min(256, n)); return this; }
    public TraceOptions threshold(int t)         { this.threshold = t; return this; }
    public TraceOptions invert(boolean b)        { this.invert = b; return this; }
    public TraceOptions turdSize(int n)          { this.turdSize = Math.max(0, n); return this; }
    public TraceOptions turnPolicy(TurnPolicy p) { this.turnPolicy = p; return this; }
    public TraceOptions alphaMax(double a)       { this.alphaMax = a; return this; }
    public TraceOptions optimize(boolean b)      { this.optimize = b; return this; }
    public TraceOptions optTolerance(double t)   { this.optTolerance = t; return this; }

    public static TraceOptions bw()    { return new TraceOptions().mode(Mode.BW); }
    public static TraceOptions color() { return new TraceOptions().mode(Mode.COLOR); }
}
