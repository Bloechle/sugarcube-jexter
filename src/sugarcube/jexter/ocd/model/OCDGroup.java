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
