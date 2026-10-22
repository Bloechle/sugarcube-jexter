package sugarcube.jexter.ocd.model;

import sugarcube.jexter.core.JxRect;

/**
 * A link annotation on a page: a clickable {@link #rect} (page content space,
 * origin bottom-left) targeting either an external {@link #uri} or an internal
 * destination — a 0-based {@link #pageIndex} with an optional page-space {@link #y}.
 *
 * <p>Internal destinations are resolved at import (named destinations flattened),
 * so a link is self-contained, exactly like {@link OCDOutline}.
 */
public final class OCDLink {

    private JxRect rect;
    private String uri;                    // external target; null/empty = internal
    private int    pageIndex = -1;         // internal target page (0-based); -1 = none
    private double y = Double.NaN;         // optional target y

    public OCDLink() {}
    public OCDLink(JxRect rect) { this.rect = rect; }

    public JxRect  rect()           { return rect; }
    public OCDLink rect(JxRect r)   { this.rect = r; return this; }
    public String  uri()            { return uri; }
    /** The external target — kept only when its scheme is {@code http}, {@code https} or {@code mailto}. A link's
     *  URI comes from the source (a PDF action, a container's anchor) and every projection writes it into an
     *  {@code href}: a {@code javascript:} URI ran in any viewer that showed the page, so anything else is
     *  dropped here, once, and the link keeps only an internal destination if it has one. */
    public OCDLink uri(String v)    {
        this.uri = v == null || v.isEmpty() || SCHEME.matcher(v.strip()).lookingAt() ? v : null;
        if (uri == null && v != null && !v.isEmpty())
            sugarcube.jexter.core.JxLog.warn(OCDLink.class, "link target dropped: only http, https and mailto are written");
        return this;
    }
    private static final java.util.regex.Pattern SCHEME =
            java.util.regex.Pattern.compile("(?i)(?:https?|mailto):");
    public int     pageIndex()      { return pageIndex; }
    public OCDLink pageIndex(int i) { this.pageIndex = i; return this; }
    public double  y()              { return y; }
    public OCDLink y(double v)      { this.y = v; return this; }

    public boolean isExternal()     { return uri != null && !uri.isEmpty(); }
    public boolean hasDestination() { return pageIndex >= 0; }
    public boolean hasY()           { return !Double.isNaN(y); }
    /** Whether the link leads anywhere: a link with neither target is not a link. */
    public boolean hasTarget()      { return isExternal() || hasDestination(); }

    /** A copy. {@link JxRect} is a record, so the rectangle is shared safely. */
    public OCDLink copy() {
        OCDLink l = new OCDLink(rect);
        l.uri = uri; l.pageIndex = pageIndex; l.y = y;
        return l;
    }

    @Override public String toString() {
        return "OCDLink[" + rect + " → " + (isExternal() ? uri : "p" + pageIndex) + "]";
    }
}
