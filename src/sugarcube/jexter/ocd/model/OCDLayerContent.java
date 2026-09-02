package sugarcube.jexter.ocd.model;

/**
 * A content container bound to an optional-content layer: its children are the content
 * that lives on the layer identified by {@link #layerId()} (a reference into the
 * document's {@link OCDLayer} registry). Replaces the former {@code OCDGroup} of kind
 * {@code LAYER}.
 *
 * <p>This is the content-side <i>binding</i> (PDF {@code /OC … BDC … EMC}); the layer's
 * presentation metadata (name, default visibility, order) lives once in the document
 * registry as an {@link OCDLayer} (PDF {@code /OCG}). Many layer groups across pages may
 * reference the same {@link OCDLayer}.
 *
 * <p>Extends {@link OCDGroup} so it composites and is traversed like any container —
 * renderers and writers recurse through it via their existing group case; only its
 * serialization ({@code <layer ref="…">}) treats it specially.
 */
public final class OCDLayerContent extends OCDGroup {

    private String layerId;     // ref into the document OCDLayer registry

    public OCDLayerContent() {}
    public OCDLayerContent(String layerId) { this.layerId = layerId; }

    public String         layerId()            { return layerId; }
    public OCDLayerContent  layerId(String id)   { this.layerId = id; return this; }

    @Override public OCDLayerContent add(OCDNode c) { super.add(c); return this; }
}
