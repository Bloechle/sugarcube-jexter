package sugarcube.jexter.core;

/**
 * Immutable 2D point. Consolidates the old {@code core/Point} and
 * {@code util/JxPoint} into one geometry primitive (the {@code Jx*} toolkit).
 */
public record JxPoint(double x, double y) {

    public static final JxPoint ZERO = new JxPoint(0, 0);

    public JxPoint translate(double dx, double dy) { return new JxPoint(x + dx, y + dy); }
    public JxPoint scale(double sx, double sy)     { return new JxPoint(x * sx, y * sy); }
    public JxPoint scale(double s)                 { return scale(s, s); }

    public double distanceTo(JxPoint p) {
        double dx = x - p.x, dy = y - p.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    @Override public String toString() { return "(" + x + ", " + y + ")"; }
}
