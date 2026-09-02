package sugarcube.jexter.ocd.model;

import sugarcube.jexter.core.JxRect;

/**
 * A raster image placed on the page.
 *
 * <p>The bytes live once in the document resources (keyed by {@link #resourceRef});
 * many nodes may reference the same key. The model normalises to two formats only
 * — PNG (lossless / alpha) and JPG (opaque photographic); every other PDF image
 * codec is decoded into one of these at extraction.
 *
 * <p>Like a PDF image, it occupies the unit square [0,1]² mapped through the base
 * {@code transform} (the CTM), so its bounds are derived — no stored box. Opacity
 * and blend come from the base node.
 */
public final class OCDImage extends OCDNode {

    private static final JxRect UNIT = new JxRect(0, 0, 1, 1);

    private String resourceRef;        // key into the document image resources (e.g. "p1_img0.png")
    private int    pixelWidth;         // intrinsic size (metadata: dpi, info) — optional
    private int    pixelHeight;

    public OCDImage() {}
    public OCDImage(String resourceRef) { this.resourceRef = resourceRef; }

    public String   resourceRef()             { return resourceRef; }
    public OCDImage resourceRef(String ref)   { this.resourceRef = ref; return this; }

    public int      pixelWidth()              { return pixelWidth; }
    public int      pixelHeight()             { return pixelHeight; }
    public OCDImage pixelSize(int w, int h)   { this.pixelWidth = w; this.pixelHeight = h; return this; }

    /** Image format inferred from the resource extension ("png" / "jpg"). */
    public String format() {
        if (resourceRef == null) return null;
        int dot = resourceRef.lastIndexOf('.');
        return dot < 0 ? null : resourceRef.substring(dot + 1).toLowerCase();
    }

    @Override public JxRect bounds() { return transform.apply(UNIT); }
}
