package sugarcube.jexter.ocd.model;

import sugarcube.jexter.core.JxPath;
import sugarcube.jexter.core.JxRect;

/**
 * A glyph in an {@link OCDFont} — the outline, keyed by glyph id.
 *
 * <p>The outline is a {@link JxPath} in normalized em units (Y-up, baseline at
 * y=0); scale by font size to render. It is shared: a text run references it by
 * {@code gid}, never copies it. {@code unicode} is the text the glyph maps to
 * (usually one char, more for a ligature) — kept here so extraction is
 * font-resolvable in the gid→text direction.
 *
 * <p>Bounds are <b>derived</b> from the outline ({@link JxPath#bounds()}), never
 * stored — so cap/x-height computation works even after a load round-trip, and
 * there is nothing to drop on serialization.
 *
 * <p>Build-then-freeze: the {@link JxPath} is treated as immutable once the glyph
 * is constructed.
 */
public record OCDGlyph(int gid, String unicode, JxPath outline, double advance, String name) {

    public OCDGlyph(int gid, String unicode, JxPath outline, double advance) {
        this(gid, unicode, outline, advance, null);
    }

    public boolean isSpace() { return outline == null || outline.isEmpty(); }

    /** Outline bounds in em units (empty for spaces). */
    public JxRect bounds() { return outline == null ? JxRect.EMPTY : JxRect.of(outline.bounds()); }

    /** Lowest y of the outline (negative = descent below baseline). */
    public double yMin() { return bounds().y(); }
    /** Highest y of the outline (cap / ascender height). */
    public double yMax() { return bounds().bottom(); }
}
