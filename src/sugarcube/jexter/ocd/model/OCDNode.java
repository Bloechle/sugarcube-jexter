package sugarcube.jexter.ocd.model;

import sugarcube.jexter.core.JxRect;
import sugarcube.jexter.core.JxTransform;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Base of every paintable node in the OCD model.
 *
 * <p>One z-ordered item carrying the presentational state shared by all nodes —
 * placement {@link #transform}, {@link #clipId}, scalar {@link #alpha}, and
 * {@link #blend} mode — plus an optional {@link #name} and an optional semantic
 * {@link #role}. Geometry and paint specifics (outlines, colours, glyphs, image
 * bytes) live in the leaves; containment lives in {@link OCDGroup}.
 *
 * <p>The {@code role} is orthogonal to a node's container type: a node can be a
 * graphical group (or an {@link OCDLayerContent layer}) <i>and</i> carry a structure
 * tag (Figure, Section…) at the same time — the "graphique et/ou sémantique" of the
 * OCD model.
 *
 * <p>{@code z} is the authoritative <b>paint order</b> (content-stream order at
 * import), a float so analysis can re-insert a node between two siblings without
 * renumbering. The OCD flow — child order — carries <b>reading order</b> instead;
 * every rasteriser and exporter walks {@link #inPaintOrder} so the two stay
 * independent: the document is stored as it reads, painted as {@code z} dictates.
 */
public sealed abstract class OCDNode permits OCDText, OCDPath, OCDImage, OCDGroup, OCDMedia, OCDBreak {

    /** Reserved sentinel id: never assigned to a real node (live ids start at {@code n1}),
     *  free to denote "no node" / a synthetic anchor in references. */
    public static final String NONE = "n0";

    protected String id;                                  // page-scoped, unique
    protected float  z;                                   // paint order (float: insert between siblings); flow carries reading order
    protected String name = "";                           // optional human / source name
    protected String role;                                // optional semantic tag — orthogonal to container type

    // shared presentational state — every node, groups included
    protected JxTransform transform = JxTransform.IDENTITY; // local→page placement; for groups, applies to children
    protected String clipId;                                // ref into the page clip table; null = inherit page clip
    protected float  alpha = 1f;                            // scalar opacity 0..1 (multiplies any per-leaf colour alpha)
    protected String blend;                                 // blend-mode name; null = Normal

    // ── identity / order ─────────────────────────────────────────────────────
    public String  id()             { return id; }
    public OCDNode id(String v)      { this.id = v; return this; }
    public float   z()              { return z; }
    public OCDNode z(float v)        { this.z = v; return this; }

    /** A copy of {@code nodes} in <b>paint order</b> — sorted by {@link #z()}, stable so equal-{@code z}
     *  ties keep their flow position. The OCD flow carries reading order; paint order lives in {@code z};
     *  every rasteriser/exporter walks this so the flow can be stored as the document reads while the
     *  painted result stays byte-identical. */
    public static List<OCDNode> inPaintOrder(List<OCDNode> nodes) {
        List<OCDNode> flat = new ArrayList<>(nodes.size());
        splice(flat, nodes);
        flat.sort(Comparator.comparingDouble(OCDNode::z));
        return flat;
    }

    /** Splice render-transparent analysis wrappers (an {@link OCDParagraph}/{@link OCDGraphic} with identity
     *  transform, no clip/blend, opaque) into the paint list, recursively, so {@code z} is a faithful GLOBAL
     *  paint order: a logical grouping never paints as a single z-block. Opaque or transformed groups keep a
     *  paint context and stay atomic. */
    private static void splice(List<OCDNode> out, List<OCDNode> in) {
        for (OCDNode n : in) {
            if ((n instanceof OCDParagraph || n instanceof OCDGraphic)
                    && n.transform().isIdentity() && !n.hasClip() && !n.hasBlend() && n.alpha() == 1f)
                splice(out, ((OCDGroup) n).children());
            else
                out.add(n);
        }
    }
    public String  name()           { return name; }
    public OCDNode name(String v)    { this.name = v == null ? "" : v; return this; }

    // ── semantic role (orthogonal facet) ─────────────────────────────────────
    public String  role()           { return role; }
    public OCDNode role(String v)    { this.role = v; return this; }
    public boolean hasRole()         { return role != null && !role.isEmpty(); }

    // ── presentational state ─────────────────────────────────────────────────
    public JxTransform transform()              { return transform; }
    public OCDNode     transform(JxTransform t) { this.transform = t == null ? JxTransform.IDENTITY : t; return this; }
    public String      clipId()                 { return clipId; }
    public OCDNode     clipId(String v)         { this.clipId = v; return this; }
    public boolean     hasClip()                { return clipId != null && !clipId.isEmpty(); }
    public float       alpha()                  { return alpha; }
    public OCDNode     alpha(float a)           { this.alpha = a; return this; }
    public String      blend()                  { return blend; }
    public OCDNode     blend(String b)          { this.blend = b; return this; }
    public boolean     hasBlend()               { return blend != null && !blend.isEmpty(); }

    // ── geometry ─────────────────────────────────────────────────────────────
    /** Axis-aligned bounds in page space, derived from content. */
    public abstract JxRect bounds();

    // ── traversal ──────────────────────────────────────────────────────────────
    /** This node and all its descendants, pre-order (paint order). A leaf yields only itself;
     *  {@link OCDGroup} overrides to include its subtree. The one place tree-walking lives:
     *  callers filter/map instead of re-coding the {@code instanceof OCDGroup} recursion.
     *  Examples: {@code node.stream().filter(OCDText.class::isInstance)}; for whole pages prefer
     *  {@link OCDPage#texts()} / {@link OCDPage#paths()} / {@link OCDPage#nodes()}. */
    public java.util.stream.Stream<OCDNode> stream() { return java.util.stream.Stream.of(this); }

    @Override public String toString() {
        return getClass().getSimpleName() + "[" + id + " z=" + z + (hasRole() ? " role=" + role : "") + "]";
    }
}
