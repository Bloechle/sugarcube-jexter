package sugarcube.jexter.ocd.model;

import sugarcube.jexter.core.JxRect;

import java.util.ArrayList;
import java.util.List;

/**
 * A container of child nodes — the OCD grouping primitive. A plain group applies the
 * node's transform / clip / opacity / blend to its children (e.g. a Form XObject or a
 * transparency group).
 *
 * <p>Grouping no longer carries a {@code kind} tag: specialised containers are their own
 * first-class subtypes — {@link OCDParagraph} (text block, lines separated by
 * {@link OCDBreak}) and {@link OCDLayerContent} (content bound to an optional-content
 * {@link OCDLayer}). A semantic tag, when present, rides on the inherited {@link #role()}.
 */
public non-sealed class OCDGroup extends OCDNode {

    private final List<OCDNode> children = new ArrayList<>();

    public OCDGroup() {}

    // ── children ─────────────────────────────────────────────────────────────
    public List<OCDNode> children()      { return children; }
    public OCDGroup      add(OCDNode c)  { if (c != null) children.add(c); return this; }
    public int           size()          { return children.size(); }
    public boolean       isEmpty()       { return children.isEmpty(); }

    /**
     * True when this group's blend mode or opacity must act on the group as ONE composite — PDF's
     * transparency-group semantics — and not on each child in turn. The children are painted onto
     * their own canvas with ordinary compositing, and only that result is blended (or faded) onto
     * what lies beneath. Folding a Multiply onto every leaf instead multiplies each child with the
     * others: a photo no longer covers the red it sits on, it tints with it. Measured on a press
     * page placed as a Multiply group: 63.6% of pixels off, the whole page red.
     *
     * <p>A lone leaf is the exception: with nothing to overlap, the per-leaf reading is the same
     * picture. A lone GROUP is not — its own children overlap.
     */
    public boolean compositesAsOne() {
        boolean blends = hasBlend() && !"Normal".equalsIgnoreCase(blend());
        if (!blends && alpha() >= 1f) return false;
        return size() > 1 || (size() == 1 && children.get(0) instanceof OCDGroup);
    }

    /** A copy of the group AND of its subtree — a container that shared its children would be two
     *  groups editing one another. The concrete type is preserved through {@link #shell()}, which each
     *  subtype answers for itself, so a paragraph copies as a paragraph and keeps its own facets. */
    @Override public OCDGroup copy() {
        OCDGroup g = copyInto(shell());
        for (OCDNode c : children) g.children.add(c.copy());
        return g;
    }

    /** An empty node of THIS group's type, carrying its own type-specific state. Overridden by every
     *  subtype: forgetting it would silently downgrade a paragraph to a plain group on copy. */
    protected OCDGroup shell() { return new OCDGroup(); }

    @Override public JxRect bounds() {
        JxRect box = JxRect.EMPTY;
        for (OCDNode c : children) box = box.union(c.bounds());
        return box;
    }

    /** This group then every descendant, pre-order (paint order). */
    @Override public java.util.stream.Stream<OCDNode> stream() {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(this), children.stream().flatMap(OCDNode::stream));
    }
}
