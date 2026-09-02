package sugarcube.jexter.core;

import java.awt.geom.AffineTransform;

/**
 * Immutable 2D affine transform [a b tx; c d ty; 0 0 1] — PDF's matrix layout.
 * Rename of {@code core/Transform} into the {@code Jx*} toolkit.
 */
public record JxTransform(double a, double b, double c, double d, double tx, double ty) {

    public static final JxTransform IDENTITY = new JxTransform(1, 0, 0, 1, 0, 0);

    public static JxTransform translate(double tx, double ty) { return new JxTransform(1, 0, 0, 1, tx, ty); }
    public static JxTransform scale(double sx, double sy)     { return new JxTransform(sx, 0, 0, sy, 0, 0); }
    public static JxTransform scale(double s)                 { return scale(s, s); }

    public static JxTransform rotate(double radians) {
        double cos = Math.cos(radians), sin = Math.sin(radians);
        return new JxTransform(cos, sin, -sin, cos, 0, 0);
    }

    /** Bridge from Java2D (e.g. a CTM obtained as an AffineTransform). */
    public static JxTransform of(AffineTransform t) {
        return t == null ? IDENTITY
                : new JxTransform(t.getScaleX(), t.getShearX(), t.getShearY(), t.getScaleY(),
                                  t.getTranslateX(), t.getTranslateY());
    }

    public AffineTransform awt() { return new AffineTransform(a, c, b, d, tx, ty); }

    /** this × other (apply other first, then this). */
    public JxTransform concat(JxTransform o) {
        return new JxTransform(
            a * o.a + b * o.c,        a * o.b + b * o.d,
            c * o.a + d * o.c,        c * o.b + d * o.d,
            a * o.tx + b * o.ty + tx, c * o.tx + d * o.ty + ty
        );
    }

    public JxPoint apply(JxPoint p)          { return apply(p.x(), p.y()); }
    public JxPoint apply(double x, double y) { return new JxPoint(a * x + b * y + tx, c * x + d * y + ty); }

    public JxRect apply(JxRect r) {
        JxPoint p0 = apply(r.x(), r.y());
        JxPoint p1 = apply(r.right(), r.y());
        JxPoint p2 = apply(r.right(), r.bottom());
        JxPoint p3 = apply(r.x(), r.bottom());
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
            (b * ty - d * tx) * inv, (c * tx - a * ty) * inv
        );
    }

    public double scaleX() { return Math.sqrt(a * a + c * c); }
    public double scaleY() { return Math.sqrt(b * b + d * d); }

    public boolean isIdentity() {
        return a == 1 && b == 0 && c == 0 && d == 1 && tx == 0 && ty == 0;
    }

    /** The six components as {@code "a b c d tx ty"} at export precision (SVG/OCD matrix form). */
    public String toMatrix6() {
        return JxNum.fmt(a) + " " + JxNum.fmt(b) + " " + JxNum.fmt(c) + " "
             + JxNum.fmt(d) + " " + JxNum.fmt(tx) + " " + JxNum.fmt(ty);
    }

    @Override public String toString() {
        return isIdentity() ? "[identity]" : "[" + a + " " + b + " " + c + " " + d + " " + tx + " " + ty + "]";
    }
}
