package sugarcube.jexter.ocd.model;

/**
 * A layer definition — an optional-content group (OCG), document-scoped.
 * Content references it via {@link OCDGroup} of kind {@code LAYER} and its
 * {@code layerId}; this registry entry holds the presentation metadata.
 *
 * <p>A <b>recognition layer</b> ({@link #recognition()} set) is the text an OCR engine read from the page's picture:
 * a stratum OVER the image, whose words are runs in render mode 3. The provenance — which engine read it —
 * lives here, once for the document, because that is what the layer IS (FORMAT §B4b). One recognition, one
 * layer: re-reading a page replaces a layer and never touches the picture.
 */
public final class OCDLayer {

    public static final String BACKGROUND = "background";

    private final String id;
    private String  name;
    private boolean visible = true;   // default visibility
    private int     order;            // stacking / panel order
    private Recognition recognition;  // what made this layer's text, when it was read from the picture; null = not a recognition

    /** How a recognition layer's text was obtained: the OCR {@code engine} that read it (e.g. {@code azure-read}),
     *  and the {@code prep} the picture went through before the engine saw it ({@code null} = the picture as
     *  stored; {@code zigzag-gray}). Two readings that differ in either are two layers.
     *  FORMAT §B4b. */
    public record Recognition(String engine, String prep) {
        public Recognition {
            if (engine == null || engine.isBlank()) throw new IllegalArgumentException("a recognition names its engine");
            if (prep != null && prep.isBlank()) prep = null;
        }
    }

    public OCDLayer(String id, String name) { this.id = id; this.name = name; }

    public String   id()              { return id; }
    public String   name()            { return name; }
    public OCDLayer name(String n)    { this.name = n; return this; }
    public boolean  visible()         { return visible; }
    public OCDLayer visible(boolean v){ this.visible = v; return this; }
    public int      order()           { return order; }
    public OCDLayer order(int o)      { this.order = o; return this; }
    /** How this layer's text was recognized, or {@code null} when the layer is not a recognition. */
    public Recognition recognition()                { return recognition; }
    public OCDLayer    recognition(Recognition r)   { this.recognition = r; return this; }
    public boolean     isRecognition()              { return recognition != null; }

    @Override public String toString() {
        return "OCDLayer[" + id + " \"" + name + "\"" + (visible ? "" : " hidden") + (recognition == null ? "" : " " + recognition) + "]";
    }
}
