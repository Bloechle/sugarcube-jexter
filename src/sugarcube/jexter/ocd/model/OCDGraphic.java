package sugarcube.jexter.ocd.model;

/**
 * A physical grouping of vector primitives that form one drawing — the vector analogue of
 * {@link OCDParagraph} (which groups text runs into a block). Where a paragraph clusters
 * {@link OCDText} by baseline, an {@link OCDGraphic} clusters {@link OCDPath} by paint-order
 * contiguity and spatial cohesion (a logo, an icon, a chart's vector body…).
 *
 * <p>This is the <i>content</i>-level grouping, distinct from the <i>logical</i> figure layer
 * ({@link OCDStruct.Type#FIGURE} / the {@link #role()} facet): the logical figure may point at
 * this graphic, just as a logical paragraph points at an {@link OCDParagraph}. Producers wrap
 * only a contiguous z-order run of paths so the grouping never reorders painting.
 *
 * <p>Extends {@link OCDGroup} so it composites and is traversed like any container — renderers
 * and writers recurse through it via their existing group case; only its serialization
 * ({@code <graphic>}) treats it specially.
 */
public final class OCDGraphic extends OCDGroup {

    public OCDGraphic() {}

    @Override public OCDGraphic add(OCDNode c) { super.add(c); return this; }
}
