package sugarcube.jexter.ocd.model;

import sugarcube.jexter.core.JxPath;
import sugarcube.jexter.core.JxRect;

/**
 * A clip region — a page resource, not a paintable node. A {@link JxPath} whose
 * winding rule <i>is</i> the clip rule. Nodes reference a clip by {@code id}
 * ({@link OCDNode#clipId()}); the {@link OCDPage} owns the clip table, so one
 * clip path is stored once and shared by every node it bounds. A clip that covers the whole page is no
 * clip: the importer never registers one.
 */
public final class OCDClip {

    private final String id;
    private JxPath path;                      // clip geometry; winding rule = clip rule

    public OCDClip(String id, JxPath path) { this.id = id; this.path = path; }

    public String  id()          { return id; }
    public JxPath  path()        { return path; }
    public OCDClip path(JxPath p){ this.path = p; return this; }

    public JxRect bounds() { return path == null ? JxRect.EMPTY : JxRect.of(path.bounds()); }

    @Override public String toString() { return "OCDClip[" + id + " " + bounds() + "]"; }

    /** A copy with its own geometry — {@link JxPath} is a {@code Path2D}, so sharing it would let one
     *  page's clip be edited through another's. */
    public OCDClip copy() { return new OCDClip(id, path == null ? null : new JxPath(path)); }
}
