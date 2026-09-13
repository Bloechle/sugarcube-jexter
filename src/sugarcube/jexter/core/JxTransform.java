package sugarcube.jexter.core;

import java.awt.geom.AffineTransform;

/**
 * Immutable 2D affine transform, six components in the order PDF, SVG and Java2D all use:
 * {@code (a b c d tx ty)} = {@code (m00 m10 m01 m11 m02 m12)}, meaning
 * {@code x' = a·x + c·y + tx} and {@code y' = b·x + d·y + ty}.
 *
 * <p>That order is not a detail, it is the point of this class. PDF's {@code cm} operator, SVG's
 * {@code matrix()} and {@code AffineTransform(m00, m10, m01, m11, …)} all agree on it, so a
 * transform crossing any of those boundaries needs no reordering and cannot be transposed on the
 * way. This record used to store the row-major reading instead — {@code (m00 m01 m10 m11)} — and
 * every author who wrote the six numbers by habit wrote them in PDF order against a record that
 * meant the transpose. It cost a quarter turn on a rotated soft mask (a veil drawn 115×230 where
 * the model said 229×115), a mirrored diagonal gradient in the PDF writer, and left four silent
 * bugs in the analysis and rendering passes that this alignment fixes without touching them.
 *
 * <p>Transposition is invisible wherever {@code b} and {@code c} are both zero, which is nearly
 * everywhere: a translation, a scale, the page flip. It surfaces on a rotation or a shear — the
 * rare case, found late, on a press page. Hence one order, everywhere, by construction.
 */
public record JxTransform(double a, double b, double c, double d, double tx, double ty) {

    public static final JxTransform IDENTITY = new JxTransform(1, 0, 0, 1, 0, 0);

    public static JxTransform translate(double tx, double ty) { return new JxTransform(1, 0, 0, 1, tx, ty); }
    public static JxTransform scale(double sx, double sy)     { return new JxTransform(sx, 0, 0, sy, 0, 0); }
    public static JxTransform scale(double s)                 { return scale(s, s); }

    /** Counter-clockwise by {@code radians} in a Y-up frame — the PDF/SVG form, verbatim. */
    public static JxTransform rotate(double radians) {
        double cos = Math.cos(radians), sin = Math.sin(radians);
        return new JxTransform(cos, sin, -sin, cos, 0, 0);
    }

    /** Bridge from Java2D: its constructor takes the same six in the same order, so this is a copy. */
    public static JxTransform of(AffineTransform t) {
        return t == null ? IDENTITY
                : new JxTransform(t.getScaleX(), t.getShearY(), t.getShearX(), t.getScaleY(),
                                  t.getTranslateX(), t.getTranslateY());
    }

    /** Six numbers in PDF/SVG order → this record. The inverse of {@link #toMatrix6}. */
    public static JxTransform fromMatrix6(double[] v) {
        return new JxTransform(v[0], v[1], v[2], v[3], v[4], v[5]);
    }

    public AffineTransform awt() { return new AffineTransform(a, b, c, d, tx, ty); }

    /** this × other (apply other first, then this). */
    public JxTransform concat(JxTransform o) {
        return new JxTransform(
            a * o.a + c * o.b,         b * o.a + d * o.b,
            a * o.c + c * o.d,         b * o.c + d * o.d,
            a * o.tx + c * o.ty + tx,  b * o.tx + d * o.ty + ty
        );
    }

    public JxPoint apply(JxPoint p)          { return apply(p.x(), p.y()); }
    public JxPoint apply(double x, double y) { return new JxPoint(a * x + c * y + tx, b * x + d * y + ty); }

    public JxRect apply(JxRect r) {
        JxPoint p0 = apply(r.x(), r.y());
        JxPoint p1 = apply(r.right(), r.y());
        JxPoint p2 = apply(r.right(), r.maxY());
        JxPoint p3 = apply(r.x(), r.maxY());
        double minX = Math.min(Math.min(p0.x(), p1.x()), Math.min(p2.x(), p3.x()));
        double minY = Math.min(Math.min(p0.y(), p1.y()), Math.min(p2.y(), p3.y()));
        double maxX = Math.max(Math.max(p0.x(), p1.x()), Math.max(p2.x(), p3.x()));
        double maxY = Math.max(Math.max(p0.y(), p1.y()), Math.max(p2.y(), p3.y()));
        return new JxRect(minX, minY, maxX - minX, maxY - minY);
    }

    public double det() { return a * d - b * c; }

    public JxTransform inverse() {
        double det = det();
        if (Math.abs(det) < 1e-10) throw new ArithmeticException("Singular transform");
        double inv = 1.0 / det;
        return new JxTransform(
            d * inv, -b * inv,
            -c * inv, a * inv,
            (c * ty - d * tx) * inv, (b * tx - a * ty) * inv
        );
    }

    /** The length of the image of the unit x-axis — the horizontal scale, shear included. */
    public double scaleX() { return Math.sqrt(a * a + b * b); }
    /** The length of the image of the unit y-axis. */
    public double scaleY() { return Math.sqrt(c * c + d * d); }

    public boolean isIdentity() {
        return a == 1 && b == 0 && c == 0 && d == 1 && tx == 0 && ty == 0;
    }

    /** The six components at export precision, ready for SVG {@code matrix(…)} or PDF {@code cm}. */
    public String toMatrix6() {
        return JxNum.fmt(a) + " " + JxNum.fmt(b) + " " + JxNum.fmt(c) + " "
             + JxNum.fmt(d) + " " + JxNum.fmt(tx) + " " + JxNum.fmt(ty);
    }

    @Override public String toString() {
        return isIdentity() ? "[identity]" : "[" + a + " " + b + " " + c + " " + d + " " + tx + " " + ty + "]";
    }
}
