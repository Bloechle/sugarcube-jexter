package sugarcube.jexter.ocd.model;

import sugarcube.jexter.core.JxRect;

/**
 * A line-break sentinel inside an {@link OCDParagraph}: a zero-extent token marking
 * where one visual line ends and the next begins.
 *
 * <p>It exists so line breaks are explicit in the serialized form — a {@code <br/>}
 * element sitting between two runs — without a LINE node level and without smuggling a
 * magic glyph into the render stream. It paints nothing (its {@link #bounds()} is
 * empty); renderers ignore it via their default case.
 */
public final class OCDBreak extends OCDNode {

    @Override public JxRect bounds() { return JxRect.EMPTY; }
}
