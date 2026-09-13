package sugarcube.jexter.ocd.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One node of the document outline (bookmark tree): a title and an optional
 * destination (a page, with an optional page-space {@code y} anchor), plus
 * nested children. The document holds the top-level items in {@link OCDDocument#outline()}.
 *
 * <p>Destinations are resolved at import to a 0-based {@code pageIndex} (and a
 * {@code y} in the target page's content space, origin bottom-left), so the
 * outline is self-contained — no named-destination indirection survives.
 */
public final class OCDOutline {

    private String title;
    private int    pageIndex = -1;             // 0-based target page; -1 = no destination
    private double y = Double.NaN;             // optional page-space y of the target (top), NaN = none
    private final List<OCDOutline> children = new ArrayList<>();

    public OCDOutline() {}
    public OCDOutline(String title) { this.title = title; }

    public String     title()           { return title; }
    public OCDOutline title(String v)    { this.title = v; return this; }
    public int        pageIndex()        { return pageIndex; }
    public OCDOutline pageIndex(int i)   { this.pageIndex = i; return this; }
    public double     y()                { return y; }
    public OCDOutline y(double v)        { this.y = v; return this; }

    public boolean hasDestination() { return pageIndex >= 0; }
    public boolean hasY()           { return !Double.isNaN(y); }

    public List<OCDOutline> children()      { return children; }
    public OCDOutline       add(OCDOutline c) { if (c != null) children.add(c); return this; }
    public boolean          isEmpty()       { return children.isEmpty(); }

    @Override public String toString() {
        return "OCDOutline[\"" + title + "\"" + (hasDestination() ? " →p" + pageIndex : "")
                + (children.isEmpty() ? "" : " (" + children.size() + ")") + "]";
    }
}
