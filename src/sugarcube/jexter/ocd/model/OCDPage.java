package sugarcube.jexter.ocd.model;

import sugarcube.jexter.core.JxRect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A single page: geometry (media/crop box, rotation, dpi), the z-ordered content
 * nodes, and the page-scoped clip table.
 *
 * <p>Content is the flat list of top-level {@link OCDNode}s in paint order
 * (groups nest within it). A text run lives here directly until analysis wraps
 * it in a structural group. Clips are page resources keyed by id; nodes carry a
 * {@link OCDNode#clipId()} into {@link #clips()}.
 */
public final class OCDPage {

    private final String id;
    private final JxRect mediaBox;
    private JxRect cropBox;          // null = same as mediaBox
    private JxRect bleedBox;         // print boxes; null = not distinct from the content box
    private JxRect trimBox;
    private JxRect artBox;
    private int    rotation;         // 0 / 90 / 180 / 270
    private double dpi = 72;

    private final List<OCDNode>        content = new ArrayList<>();
    private final Map<String, OCDClip> clips   = new LinkedHashMap<>();
    private final List<OCDLink>        links   = new ArrayList<>();
    private final List<OCDAnnotation>  annotations = new ArrayList<>();
    private final List<OCDFormField>   fields      = new ArrayList<>();

    public OCDPage(String id, JxRect mediaBox) {
        this.id = id;
        this.mediaBox = mediaBox;
    }
    public OCDPage(String id, double width, double height) {
        this(id, new JxRect(0, 0, width, height));
    }

    // ── geometry ─────────────────────────────────────────────────────────────
    public String id()        { return id; }
    public JxRect mediaBox()  { return mediaBox; }
    public JxRect cropBox()   { return cropBox; }
    public JxRect bleedBox()  { return bleedBox; }
    public JxRect trimBox()   { return trimBox; }
    public JxRect artBox()    { return artBox; }
    public double dpi()       { return dpi; }
    public int    rotation()  { return rotation; }

    public OCDPage bleedBox(JxRect r) { this.bleedBox = r; return this; }
    public OCDPage trimBox(JxRect r)  { this.trimBox = r; return this; }
    public OCDPage artBox(JxRect r)   { this.artBox = r; return this; }

    public OCDPage dpi(double v) { this.dpi = v; return this; }

    public OCDPage rotation(int degrees) {
        int r = ((degrees % 360) + 360) % 360;
        this.rotation = (r % 90 == 0) ? r : 0;
        return this;
    }

    public OCDPage cropBox(JxRect crop) {
        if (crop != null && !crop.equals(mediaBox)) this.cropBox = mediaBox.intersection(crop);
        return this;
    }

    /** The unrotated frame in which all content is authored — the explicit {@link #cropBox()}
     *  if set, else the {@link #mediaBox()}. Everything in {@link #content()} lives in this
     *  space; rotation is applied only for display. */
    public JxRect effectiveBox() { return cropBox != null ? cropBox : mediaBox; }

    /** Visible width after page rotation (effective-box width/height swapped for 90/270). */
    public double displayWidth() {
        JxRect b = effectiveBox();
        return (rotation == 90 || rotation == 270) ? b.height() : b.width();
    }
    /** Visible height after page rotation (effective-box width/height swapped for 90/270). */
    public double displayHeight() {
        JxRect b = effectiveBox();
        return (rotation == 90 || rotation == 270) ? b.width() : b.height();
    }

    // ── content ──────────────────────────────────────────────────────────────
    public List<OCDNode> content()      { return content; }
    public OCDPage add(OCDNode node)    { if (node != null) content.add(node); return this; }

    // ── traversal: the whole page subtree, pre-order, with typed filters (see OCDNode#stream) ──
    public java.util.stream.Stream<OCDNode>  nodes()  { return content.stream().flatMap(OCDNode::stream); }
    public java.util.stream.Stream<OCDText>  texts()  { return nodes().filter(OCDText.class::isInstance).map(OCDText.class::cast); }
    public java.util.stream.Stream<OCDPath>  paths()  { return nodes().filter(OCDPath.class::isInstance).map(OCDPath.class::cast); }
    public java.util.stream.Stream<OCDImage> images() { return nodes().filter(OCDImage.class::isInstance).map(OCDImage.class::cast); }

    // ── clip table (page-scoped) ───────────────────────────────────────────────
    public Map<String, OCDClip> clips() { return clips; }
    public OCDPage addClip(OCDClip clip){ if (clip != null) clips.put(clip.id(), clip); return this; }
    public OCDClip clip(String id)      { return id == null ? null : clips.get(id); }

    public List<OCDLink> links()        { return links; }
    public OCDPage addLink(OCDLink l)   { if (l != null) links.add(l); return this; }

    public List<OCDAnnotation> annotations()        { return annotations; }
    public OCDPage addAnnotation(OCDAnnotation a)   { if (a != null) annotations.add(a); return this; }

    public List<OCDFormField> fields()              { return fields; }
    public OCDPage addField(OCDFormField f)         { if (f != null) fields.add(f); return this; }

    // ── text convenience ───────────────────────────────────────────────────────
    /** All text on the page in z-order (runs joined by space), recursing into groups. */
    public String text() {
        var sb = new StringBuilder();
        collectText(content, sb);
        return sb.toString().trim();
    }

    private static void collectText(List<OCDNode> nodes, StringBuilder sb) {
        for (OCDNode n : nodes) {
            if (n instanceof OCDText t) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(t.text());
            } else if (n instanceof OCDGroup g) {
                collectText(g.children(), sb);
            }
        }
    }

    @Override public String toString() {
        return "OCDPage[" + id + " " + (int) displayWidth() + "×" + (int) displayHeight()
                + ", nodes=" + content.size() + ", clips=" + clips.size() + "]";
    }
}
