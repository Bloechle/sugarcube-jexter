package sugarcube.jexter.ocd.model;

import sugarcube.jexter.core.JxPath;
import sugarcube.jexter.core.JxRect;

/**
 * A clip region — a page resource, not a paintable node. A {@link JxPath} whose
 * winding rule <i>is</i> the clip rule. Nodes reference a clip by {@code id}
 * ({@link OCDNode#clipId()}); the {@link OCDPage} owns the clip table, so one
 * clip path is stored once and shared by every node it bounds.
 *
 * <p>Reserved ids: {@link #NONE} (no clip) and {@link #PAGE} (the full-page box,
 * i.e. no effective clip) — both report {@link #isNone()}.
 */
public final class OCDClip {

    public static final String NONE = "";    // no clip
    public static final String PAGE = "c0";  // full-page clip = no effective clip

    private final String id;
    private JxPath path;                      // clip geometry; winding rule = clip rule

    public OCDClip(String id, JxPath path) { this.id = id; this.path = path; }

    public String  id()          { return id; }
    public JxPath  path()        { return path; }
    public OCDClip path(JxPath p){ this.path = p; return this; }

    public boolean isNone() { return id == null || id.isEmpty() || PAGE.equals(id); }

    public JxRect bounds() { return path == null ? JxRect.EMPTY : JxRect.of(path.bounds()); }

    @Override public String toString() { return "OCDClip[" + id + " " + bounds() + "]"; }
}
