package sugarcube.jexter.core;

/**
 * Immutable 2D point. Consolidates the old {@code core/Point} and
 * {@code util/JxPoint} into one geometry primitive (the {@code Jx*} toolkit).
 */
public record JxPoint(double x, double y) {

    @Override public String toString() { return "(" + x + ", " + y + ")"; }
}
