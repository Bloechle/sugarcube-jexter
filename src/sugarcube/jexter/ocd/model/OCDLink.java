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
    public OCDLink uri(String v)    { this.uri = v; return this; }
    public int     pageIndex()      { return pageIndex; }
    public OCDLink pageIndex(int i) { this.pageIndex = i; return this; }
    public double  y()              { return y; }
    public OCDLink y(double v)      { this.y = v; return this; }

    public boolean isExternal()     { return uri != null && !uri.isEmpty(); }
    public boolean hasDestination() { return pageIndex >= 0; }
    public boolean hasY()           { return !Double.isNaN(y); }

    @Override public String toString() {
        return "OCDLink[" + rect + " → " + (isExternal() ? uri : "p" + pageIndex) + "]";
    }
}
