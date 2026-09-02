package sugarcube.jexter.ocd.model;

/**
 * A named logical structure over the page content. Because an {@link OCDStruct} tree only
 * <em>references</em> content nodes (via {@link OCDStruct.Ref}) and never owns them, a document
 * can carry several structures at once over the same content stream — the PDF's own tagged tree,
 * a heuristic recomposition, and purpose-specific variants — at no duplication cost.
 *
 * <p>Each structure records its provenance: a stable {@code id}, a human {@code label}, where it
 * came from ({@code source}), {@code by} what generator, {@code at} which time, {@code how} (e.g. an
 * options snapshot or "PDF/UA tag tree"), and a free {@code purpose} label. The document marks one
 * as default; the default is what exporters and the viewer use unless another is selected.
 */
public final class OCDStructure {

    /** Where a structure originated. */
    public enum Source { PDF, HEURISTIC, MODEL, MANUAL, OTHER }

    private String    id;
    private String    label   = "";
    private Source    source  = Source.OTHER;
    private String    by      = "";        // generator (e.g. "TaggedStructureBuilder", "StructureBuilder", "user")
    private long      at;                  // epoch millis when generated (0 = unknown)
    private String    how     = "";        // how it was produced (options snapshot, "PDF/UA tag tree", …)
    private String    purpose = "";        // free label for purpose-specific variants (reading-order, layout, a11y…)
    private OCDStruct root;

    public OCDStructure() {}
    public OCDStructure(String id, String label, Source source) {
        this.id = id;
        this.label = label == null ? "" : label;
        this.source = source == null ? Source.OTHER : source;
    }

    public String      id()                  { return id; }
    public OCDStructure id(String v)          { this.id = v; return this; }
    public String      label()               { return label; }
    public OCDStructure label(String v)       { this.label = v == null ? "" : v; return this; }
    public Source      source()              { return source; }
    public OCDStructure source(Source v)      { this.source = v == null ? Source.OTHER : v; return this; }
    public String      by()                  { return by; }
    public OCDStructure by(String v)          { this.by = v == null ? "" : v; return this; }
    public long        at()                  { return at; }
    public OCDStructure at(long v)            { this.at = v; return this; }
    public String      how()                 { return how; }
    public OCDStructure how(String v)         { this.how = v == null ? "" : v; return this; }
    public String      purpose()             { return purpose; }
    public OCDStructure purpose(String v)     { this.purpose = v == null ? "" : v; return this; }
    public OCDStruct   root()                { return root; }
    public OCDStructure root(OCDStruct v)     { this.root = v; return this; }

    /** Parse a serialized source name leniently. */
    public static Source sourceOf(String s) {
        if (s == null) return Source.OTHER;
        try { return Source.valueOf(s.trim().toUpperCase()); } catch (Exception e) { return Source.OTHER; }
    }
}
