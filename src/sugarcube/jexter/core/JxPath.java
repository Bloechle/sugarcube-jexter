package sugarcube.jexter.core;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one path primitive of the doc model — geometry only, no paint.
 *
 * <p>A {@link Path2D.Double} used identically for the three things that are all
 * "a path" in a PDF: <b>glyph outlines</b>, <b>clip regions</b> and <b>vector
 * graphics</b>. In memory this is the canonical form; the SVG {@code d} string
 * is only a serialization, produced by {@link #toSvg()} at write time and read
 * back by {@link #parseSvg(String)}.
 *
 * <p>The winding rule carried by the path <i>is</i> the fill rule and the clip
 * rule (non-zero vs even-odd) — there is no separate flag to track elsewhere.
 *
 * <p>DOUBLE coordinates throughout — geometry, parsing and formatting alike. Float halves memory and
 * matches PDFBox's stream-engine callbacks, but it is not enough to survive a transform round-trip: the
 * page stores a clip already flipped into SVG space, and reading it back means flipping again. In float
 * that returns 164.007 as 164.0069 and breaks write(read(x)) == x. Precision is the invariant; memory is not
 * vs double; em-units (0..1) and page points keep ample precision. Depends only
 * on {@code java.awt.geom}: it stays a pure geometry type, free of doc/font
 * coupling, so the converter, the OTF writer and every serializer share it.
 *
 * <p>PDF paths only ever use moveto / lineto / curveto (cubic) / close +
 * rectangles, so the SVG subset {@code M L H V C Q Z} (abs + rel) is complete
 * for this pipeline; arcs (A) and smooth shorthands (S/T) never occur and are
 * skipped defensively on parse.
 */
public final class JxPath extends Path2D.Double {

    private static final long serialVersionUID = 1L;

    public enum Op {
        MOVE(PathIterator.SEG_MOVETO), LINE(PathIterator.SEG_LINETO),
        QUAD(PathIterator.SEG_QUADTO), CUBIC(PathIterator.SEG_CUBICTO),
        CLOSE(PathIterator.SEG_CLOSE);

        public final int type;
        Op(int type) { this.type = type; }

        public static Op type(int segType) {
            return switch (segType) {
                case PathIterator.SEG_MOVETO  -> MOVE;
                case PathIterator.SEG_LINETO  -> LINE;
                case PathIterator.SEG_QUADTO  -> QUAD;
                case PathIterator.SEG_CUBICTO -> CUBIC;
                case PathIterator.SEG_CLOSE   -> CLOSE;
                default -> null;
            };
        }
    }

    /** One path segment: an op plus its raw coordinates. */
    public static final class Seg {
        public final Op op;
        public final double[] p;
        public Seg(Op op, double... p) { this.op = op; this.p = p; }

        public double x()   { return p[p.length - 2]; }
        public double y()   { return p[p.length - 1]; }
        public double c0x() { return p[0]; }
        public double c0y() { return p[1]; }
        public double c1x() { return p[2]; }
        public double c1y() { return p[3]; }

        // point-valued convenience (endpoint, first/second control) — used by the OTF glyf builder
        public JxPoint p()  { return new JxPoint(x(), y()); }
        public JxPoint c0() { return new JxPoint(c0x(), c0y()); }
        public JxPoint c1() { return new JxPoint(c1x(), c1y()); }
    }

    // ── Construction ─────────────────────────────────────────────────────────

    public JxPath()          { super(); }
    public JxPath(int rule)  { super(rule); }          // WIND_NON_ZERO / WIND_EVEN_ODD
    public JxPath(Shape s)   { super(s); }             // wraps a clip Area, a Path2D, etc.

    /** Build from an SVG {@code d} string (em-units glyph, vector or clip path). */
    public static JxPath ofSvg(String d) { return parseSvg(d); }

    // ── Fluent builders (return this, unlike Path2D's void methods) ──────────

    public JxPath move(double x, double y)  { moveTo(x, y); return this; }
    public JxPath line(double x, double y)  { lineTo(x, y); return this; }
    public JxPath quad(double cx, double cy, double x, double y) { quadTo(cx, cy, x, y); return this; }
    public JxPath cubic(double c0x, double c0y, double c1x, double c1y, double x, double y) {
        curveTo(c0x, c0y, c1x, c1y, x, y); return this;
    }
    public JxPath close() { closePath(); return this; }

    // ── Winding rule = fill rule = clip rule ─────────────────────────────────

    public boolean isEvenOdd()  { return getWindingRule() == WIND_EVEN_ODD; }
    public JxPath evenOdd()     { setWindingRule(WIND_EVEN_ODD); return this; }
    public JxPath nonZero()     { setWindingRule(WIND_NON_ZERO); return this; }

    public boolean isEmpty()    { return getCurrentPoint() == null; }

    // ── Bounds / boolean ops / transforms ────────────────────────────────────

    public Rectangle2D bounds() { return getBounds2D(); }

    /** Java2D {@link Area} for clip intersection and boolean geometry. */
    public Area area() { return new Area(this); }

    /** A transformed copy (leaves this path untouched). */
    public JxPath transformed(AffineTransform at) {
        return new JxPath(at == null ? this : at.createTransformedShape(this));
    }

    // ── SVG serialization ────────────────────────────────────────────────────

    /** Serialize to a compact SVG {@code d} string — the write-time projection. */
    public String toSvg() {
        StringBuilder sb = new StringBuilder(256);
        PathIterator it = getPathIterator(null);
        double[] p = new double[6];
        while (!it.isDone()) {
            switch (Op.type(it.currentSegment(p))) {
                case MOVE  -> sb.append('M').append(num(p[0])).append(' ').append(num(p[1]));
                case LINE  -> sb.append('L').append(num(p[0])).append(' ').append(num(p[1]));
                case QUAD  -> sb.append('Q').append(num(p[0])).append(' ').append(num(p[1])).append(' ')
                                .append(num(p[2])).append(' ').append(num(p[3]));
                case CUBIC -> sb.append('C').append(num(p[0])).append(' ').append(num(p[1])).append(' ')
                                .append(num(p[2])).append(' ').append(num(p[3])).append(' ')
                                .append(num(p[4])).append(' ').append(num(p[5]));
                case CLOSE -> sb.append('Z');
                case null  -> { }
            }
            it.next();
        }
        return sb.toString();
    }

    /** Parse an SVG path {@code d} string (M/L/H/V/C/Q/Z, absolute &amp; relative). */
    public static JxPath parseSvg(String d) {
        JxPath path = new JxPath();
        if (d == null || d.isBlank()) return path;

        SvgTokens tok = new SvgTokens(d);
        double cx = 0, cy = 0, sx = 0, sy = 0;   // current + subpath start
        char cmd = 0;
        while (tok.hasNext()) {
            char c = tok.peekCmd();
            if (c != 0) { cmd = c; tok.skip(); }
            boolean rel = Character.isLowerCase(cmd);
            switch (Character.toUpperCase(cmd)) {
                case 'M' -> {
                    cx = rel ? cx + tok.f() : tok.f(); cy = rel ? cy + tok.f() : tok.f();
                    path.moveTo(cx, cy); sx = cx; sy = cy; cmd = rel ? 'l' : 'L';
                }
                case 'L' -> { cx = rel ? cx + tok.f() : tok.f(); cy = rel ? cy + tok.f() : tok.f(); path.lineTo(cx, cy); }
                case 'H' -> { cx = rel ? cx + tok.f() : tok.f(); path.lineTo(cx, cy); }
                case 'V' -> { cy = rel ? cy + tok.f() : tok.f(); path.lineTo(cx, cy); }
                case 'C' -> {
                    double x1 = rel ? cx + tok.f() : tok.f(), y1 = rel ? cy + tok.f() : tok.f();
                    double x2 = rel ? cx + tok.f() : tok.f(), y2 = rel ? cy + tok.f() : tok.f();
                    cx = rel ? cx + tok.f() : tok.f(); cy = rel ? cy + tok.f() : tok.f();
                    path.curveTo(x1, y1, x2, y2, cx, cy);
                }
                case 'Q' -> {
                    double x1 = rel ? cx + tok.f() : tok.f(), y1 = rel ? cy + tok.f() : tok.f();
                    cx = rel ? cx + tok.f() : tok.f(); cy = rel ? cy + tok.f() : tok.f();
                    path.quadTo(x1, y1, cx, cy);
                }
                case 'Z' -> { path.closePath(); cx = sx; cy = sy; }
                default  -> tok.skip();   // unsupported (A/S/T) — skip defensively
            }
        }
        return path;
    }

    // ── Segment extraction ───────────────────────────────────────────────────

    public Seg[] segments() {
        List<Seg> segs = new ArrayList<>();
        PathIterator it = getPathIterator(null);
        double[] p = new double[6];
        double ox = 0, oy = 0;
        while (!it.isDone()) {
            switch (Op.type(it.currentSegment(p))) {
                case MOVE  -> segs.add(new Seg(Op.MOVE, ox = p[0], oy = p[1]));
                case LINE  -> segs.add(new Seg(Op.LINE, p[0], p[1]));
                case QUAD  -> segs.add(new Seg(Op.QUAD, p[0], p[1], p[2], p[3]));
                case CUBIC -> segs.add(new Seg(Op.CUBIC, p[0], p[1], p[2], p[3], p[4], p[5]));
                case CLOSE -> segs.add(new Seg(Op.CLOSE, ox, oy));
                case null  -> { }
            }
            it.next();
        }
        return segs.toArray(new Seg[0]);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Font helpers — used by the OTF writer (harmless on any path)
    // ══════════════════════════════════════════════════════════════════════════

    /** Mirror the Y axis (glyph space vs font space). */
    public JxPath reverseY() { return reverse(false, true); }

    public JxPath reverse(boolean bx, boolean by) {
        JxPath path = new JxPath(getWindingRule());
        PathIterator it = getPathIterator(null);
        double[] p = new double[6];
        while (!it.isDone()) {
            switch (Op.type(it.currentSegment(p))) {
                case MOVE  -> path.moveTo(sx(bx, p[0]), sy(by, p[1]));
                case LINE  -> path.lineTo(sx(bx, p[0]), sy(by, p[1]));
                case QUAD  -> path.quadTo(sx(bx, p[0]), sy(by, p[1]), sx(bx, p[2]), sy(by, p[3]));
                case CUBIC -> path.curveTo(sx(bx, p[0]), sy(by, p[1]), sx(bx, p[2]), sy(by, p[3]), sx(bx, p[4]), sy(by, p[5]));
                case CLOSE -> path.closePath();
                case null  -> { }
            }
            it.next();
        }
        return path;
    }

    private static double sx(boolean flip, double v) { return flip ? -v : v; }
    private static double sy(boolean flip, double v) { return flip ? -v : v; }

    public JxPath closeSubpaths() {
        JxPath norm = new JxPath(getWindingRule());
        PathIterator it = getPathIterator(null);
        double[] p = new double[6];
        boolean firstMove = true;
        while (!it.isDone()) {
            int type = it.currentSegment(p);
            if (type == PathIterator.SEG_MOVETO) {
                if (firstMove) firstMove = false; else norm.closePath();
            }
            append(norm, type, p);
            it.next();
        }
        if (!firstMove) norm.closePath();   // close last subpath only if one was opened
        return norm;
    }

    private static void append(JxPath path, int type, double[] p) {
        switch (Op.type(type)) {
            case MOVE  -> path.moveTo(p[0], p[1]);
            case LINE  -> path.lineTo(p[0], p[1]);
            case QUAD  -> path.quadTo(p[0], p[1], p[2], p[3]);
            case CUBIC -> path.curveTo(p[0], p[1], p[2], p[3], p[4], p[5]);
            case CLOSE -> path.closePath();
            case null  -> { }
        }
    }

    /**
     * Faithful cubic→quadratic conversion for the TrueType {@code glyf} format
     * (which has no cubics). Each cubic is recursively subdivided (de Casteljau)
     * until a single quadratic approximates it within {@code tol} em units, so
     * curves of any shape — including the cubic outlines of CFF/Type1 fonts — are
     * reproduced to sub-pixel accuracy, instead of a fixed 2-quad guess.
     */
    public JxPath toQuadratic() { return toQuadratic(0.0003); }

    public JxPath toQuadratic(double tol) {
        PathIterator it = getPathIterator(null);
        JxPath out = new JxPath(getWindingRule());
        double[] p = new double[6];
        double cx = 0, cy = 0, sx = 0, sy = 0;
        while (!it.isDone()) {
            switch (it.currentSegment(p)) {
                case PathIterator.SEG_MOVETO -> { out.moveTo(p[0], p[1]); cx = sx = p[0]; cy = sy = p[1]; }
                case PathIterator.SEG_LINETO -> { out.lineTo(p[0], p[1]); cx = p[0]; cy = p[1]; }
                case PathIterator.SEG_QUADTO -> { out.quadTo(p[0], p[1], p[2], p[3]); cx = p[2]; cy = p[3]; }
                case PathIterator.SEG_CUBICTO -> { cubicToQuad(out, cx, cy, p[0], p[1], p[2], p[3], p[4], p[5], tol, 0); cx = p[4]; cy = p[5]; }
                case PathIterator.SEG_CLOSE -> { out.closePath(); cx = sx; cy = sy; }
                default -> { }
            }
            it.next();
        }
        return out;
    }

    private static void cubicToQuad(JxPath out, double x0, double y0, double x1, double y1,
                                    double x2, double y2, double x3, double y3, double tol, int depth) {
        // single-quad approx error ≈ |P0 − 3P1 + 3P2 − P3| · √3/36
        double dx = x0 - 3 * x1 + 3 * x2 - x3, dy = y0 - 3 * y1 + 3 * y2 - y3;
        double err = Math.sqrt(dx * dx + dy * dy) * (Math.sqrt(3) / 36.0);
        if (err <= tol || depth >= 12) {
            double qx = (3 * x1 + 3 * x2 - x0 - x3) / 4.0;
            double qy = (3 * y1 + 3 * y2 - y0 - y3) / 4.0;
            out.quadTo(qx, qy, x3, y3);
            return;
        }
        double l1x = (x0 + x1) / 2, l1y = (y0 + y1) / 2;
        double mx = (x1 + x2) / 2,  my = (y1 + y2) / 2;
        double r2x = (x2 + x3) / 2, r2y = (y2 + y3) / 2;
        double l2x = (l1x + mx) / 2, l2y = (l1y + my) / 2;
        double r1x = (mx + r2x) / 2, r1y = (my + r2y) / 2;
        double cx = (l2x + r1x) / 2, cy = (l2y + r1y) / 2;
        cubicToQuad(out, x0, y0, l1x, l1y, l2x, l2y, cx, cy, tol, depth + 1);
        cubicToQuad(out, cx, cy, r1x, r1y, r2x, r2y, x3, y3, tol, depth + 1);
    }

    // ── Compact number formatting (US locale, trailing zeros stripped) ───────

    private static String num(double v) {
        return JxNum.fmt(v);   // single export-precision rule (ConvertOptions.EXPORT_PRECISION)
    }

    @Override
    public String toString() { return String.format(Locale.US, "JxPath[%d segs]", segments().length); }

    // ── Minimal SVG path-data tokenizer ──────────────────────────────────────

    private static final class SvgTokens {
        private final String s;
        private int i = 0;
        SvgTokens(String s) { this.s = s; }

        boolean hasNext() { skipSep(); return i < s.length(); }

        char peekCmd() {
            skipSep();
            if (i < s.length() && Character.isLetter(s.charAt(i))) return s.charAt(i);
            return 0;
        }

        void skip() { i++; }

        private void skipSep() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == ',' || c == '\t' || c == '\n' || c == '\r') i++; else break;
            }
        }

        double f() {
            skipSep();
            int start = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            boolean dot = false, exp = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') i++;
                else if (c == '.' && !dot && !exp) { dot = true; i++; }
                else if ((c == 'e' || c == 'E') && !exp) { exp = true; i++; if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++; }
                else break;
            }
            try { return java.lang.Double.parseDouble(s.substring(start, i)); }
            catch (Exception e) { return 0d; }
        }
    }
}
