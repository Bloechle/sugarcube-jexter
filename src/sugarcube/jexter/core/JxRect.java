package sugarcube.jexter.core;

import java.awt.geom.Rectangle2D;

/**
 * Immutable axis-aligned rectangle. Consolidates the old {@code core/Rect} and
 * {@code util/JxRect}.
 */
public record JxRect(double x, double y, double width, double height) {

    public static final JxRect EMPTY = new JxRect(0, 0, 0, 0);

    public static JxRect fromLTRB(double left, double top, double right, double bottom) {
        return new JxRect(left, top, right - left, bottom - top);
    }

    /** From a Java2D bounds (e.g. {@code JxPath.bounds()}). */
    public static JxRect of(Rectangle2D r) {
        return r == null ? EMPTY : new JxRect(r.getX(), r.getY(), r.getWidth(), r.getHeight());
    }

    public double right()  { return x + width; }
    public double bottom() { return y + height; }
    public double minX()   { return x; }
    public double minY()   { return y; }
    public double maxX()   { return right(); }
    public double maxY()   { return bottom(); }
    public double cx()     { return x + width / 2; }
    public double cy()     { return y + height / 2; }
    public JxPoint origin(){ return new JxPoint(x, y); }
    public JxPoint center(){ return new JxPoint(cx(), cy()); }

    public boolean isEmpty() { return width <= 0 || height <= 0; }

    public boolean contains(double px, double py) {
        return px >= x && py >= y && px <= right() && py <= bottom();
    }

    public boolean contains(JxPoint p) { return contains(p.x(), p.y()); }

    /** Whether this rectangle fully encloses {@code r}. The single rect-in-rect authority. */
    public boolean contains(JxRect r) {
        return x <= r.x && y <= r.y && right() >= r.right() && bottom() >= r.bottom();
    }

    public boolean intersects(JxRect r) {
        return x < r.right() && right() > r.x && y < r.bottom() && bottom() > r.y;
    }

    public JxRect union(JxRect r) {
        if (isEmpty()) return r;
        if (r.isEmpty()) return this;
        double nx = Math.min(x, r.x), ny = Math.min(y, r.y);
        return new JxRect(nx, ny, Math.max(right(), r.right()) - nx, Math.max(bottom(), r.bottom()) - ny);
    }

    public JxRect intersection(JxRect r) {
        double nx = Math.max(x, r.x), ny = Math.max(y, r.y);
        double nw = Math.min(right(), r.right()) - nx;
        double nh = Math.min(bottom(), r.bottom()) - ny;
        return (nw > 0 && nh > 0) ? new JxRect(nx, ny, nw, nh) : EMPTY;
    }

    public JxRect inflate(double dx, double dy)   { return new JxRect(x - dx, y - dy, width + 2 * dx, height + 2 * dy); }
    public JxRect translate(double dx, double dy) { return new JxRect(x + dx, y + dy, width, height); }
    public JxRect scale(double s)                 { return new JxRect(x * s, y * s, width * s, height * s); }

    @Override public String toString() { return "[" + x + ", " + y + ", " + width + "×" + height + "]"; }
}
