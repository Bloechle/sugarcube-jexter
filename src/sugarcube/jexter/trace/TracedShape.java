package sugarcube.jexter.trace;

import sugarcube.jexter.core.JxPath;

/**
 * One traced layer: a closed {@link JxPath} (pixel space, holes folded in via
 * non-zero winding), its solid fill as packed sRGB {@code 0xAARRGGBB}, and the
 * covered pixel area (paint order / debugging).
 */
public record TracedShape(JxPath path, int argb, double area) {}
