package sugarcube.jexter.ocd.model;

/**
 * A layer definition — an optional-content group (OCG), document-scoped.
 * Content references it via {@link OCDGroup} of kind {@code LAYER} and its
 * {@code layerId}; this registry entry holds the presentation metadata.
 */
public final class OCDLayer {

    public static final String BACKGROUND = "background";

    private final String id;
    private String  name;
    private boolean visible = true;   // default visibility
    private int     order;            // stacking / panel order

    public OCDLayer(String id, String name) { this.id = id; this.name = name; }

    public String   id()              { return id; }
    public String   name()            { return name; }
    public OCDLayer name(String n)    { this.name = n; return this; }
    public boolean  visible()         { return visible; }
    public OCDLayer visible(boolean v){ this.visible = v; return this; }
    public int      order()           { return order; }
    public OCDLayer order(int o)      { this.order = o; return this; }

    @Override public String toString() {
        return "OCDLayer[" + id + " \"" + name + "\"" + (visible ? "" : " hidden") + "]";
    }
}
