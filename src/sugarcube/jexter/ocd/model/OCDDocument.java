package sugarcube.jexter.ocd.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The document model: ordered {@link OCDPage}s plus the document-scoped shared
 * resources — fonts (by name), image bytes (by ref), and the layer registry —
 * and metadata. Pages own their own clip tables; everything else shared lives
 * here.
 *
 * <p>Fully self-contained: once built it has no dependency on PDFBox, so it can
 * be projected to EPUB, PDF, an AI format, or serialized to the OCD-EPUB working format.
 */
public final class OCDDocument {

    private String id;

    private final List<OCDPage>          pages    = new ArrayList<>();
    private final OCDMeta                 meta     = new OCDMeta();
    private final Map<String, OCDFont>   fonts    = new LinkedHashMap<>();  // name → font
    private final Map<String, byte[]>    images   = new LinkedHashMap<>();  // ref  → bytes (png/jpg)
    private final Map<String, byte[]>    media    = new LinkedHashMap<>();  // ref  → bytes (mp3/mp4)
    private final Map<String, OCDLayer>  layers   = new LinkedHashMap<>();  // id   → layer
    private final List<OCDOutline>       outline  = new ArrayList<>();      // bookmark tree (top-level items)
    private final List<OCDStructure>     structures = new ArrayList<>();    // logical structures (each a tree of refs); may be several
    private String                        defaultStructureId;                // id of the structure exporters/viewer use by default
    private boolean                       textSegmented;                     // analysis provenance: text segmentation (paragraphs/lines) was performed
    private boolean                       headingsDetected;                  // analysis provenance: heading/hierarchy detection was performed

    public OCDDocument()          { this.id = UUID.randomUUID().toString().substring(0, 8); }
    public OCDDocument(String id) { this.id = id; }

    public String      id()           { return id; }
    public OCDDocument id(String v)   { this.id = v; return this; }

    // ── pages ──────────────────────────────────────────────────────────────────
    public OCDDocument add(OCDPage page) { if (page != null) pages.add(page); return this; }
    public List<OCDPage> pages()          { return pages; }
    public int           pageCount()      { return pages.size(); }
    public OCDPage       page(int i)      { return (i >= 0 && i < pages.size()) ? pages.get(i) : null; }

    // ── metadata ────────────────────────────────────────────────────────────────
    public OCDMeta meta() { return meta; }

    // ── fonts (document-scoped) ───────────────────────────────────────────────
    public OCDDocument add(OCDFont font) {
        if (font != null && font.id() != null) fonts.put(font.id(), font);
        return this;
    }
    public OCDFont font(String id)           { return id == null ? null : fonts.get(id); }
    public Map<String, OCDFont> fonts()      { return fonts; }

    /** Resolve a font reference: by id (the normal case), tolerant of a stale name match. */
    public OCDFont findFont(String ref) {
        if (ref == null) return null;
        OCDFont f = fonts.get(ref);
        if (f != null) return f;
        for (OCDFont g : fonts.values()) if (ref.equals(g.name())) return g;   // fallback: descriptive name
        return null;
    }

    // ── cover (container resource) ──────────────────────────────────────────────
    // The EPUB cover is a RESOURCE, not a projection: a re-export of the same document
    // must carry the same bytes, or write(read(x)) = x breaks on one member. The reader
    // stashes what it read; writers reuse it and only rasterize page 1 when absent.
    private byte[] cover;
    public OCDDocument cover(byte[] png) { this.cover = png; return this; }
    public byte[]      cover()           { return cover; }

    // ── images (document-scoped) ────────────────────────────────────────────────
    private int imageSeq = 0;
    /** Allocate a stable, document-scoped image resource name: {@code img_0001.png}. */
    public String newImageRef(String ext) { return String.format("img_%04d.%s", ++imageSeq, ext); }

    public OCDDocument addImage(String ref, byte[] data) { images.put(ref, data); return this; }
    public byte[]      image(String ref)                 { return images.get(ref); }
    public Map<String, byte[]> images()                  { return images; }

    // ── media: embedded audio / video resources (document-scoped) ────────────────
    private int audioSeq = 0, videoSeq = 0;
    /** Allocate a stable media resource name: {@code audio_0001.mp3}. */
    public String newAudioRef(String ext) { return String.format("audio_%04d.%s", ++audioSeq, ext); }
    /** Allocate a stable media resource name: {@code video_0001.mp4}. */
    public String newVideoRef(String ext) { return String.format("video_%04d.%s", ++videoSeq, ext); }

    public OCDDocument addMedia(String ref, byte[] data) { media.put(ref, data); return this; }
    public byte[]      media(String ref)                 { return media.get(ref); }
    public Map<String, byte[]> media()                   { return media; }

    // ── layers (document-scoped registry) ────────────────────────────────────────
    public OCDDocument add(OCDLayer layer) { if (layer != null) layers.put(layer.id(), layer); return this; }
    public OCDLayer    layer(String id)    { return id == null ? null : layers.get(id); }
    public Map<String, OCDLayer> layers()  { return layers; }

    // ── outline (bookmark tree) ──────────────────────────────────────────────
    public OCDDocument addOutline(OCDOutline o) { if (o != null) outline.add(o); return this; }
    public List<OCDOutline> outline()           { return outline; }

    // ── logical structure tree ───────────────────────────────────────────────
    public OCDStruct   structure()              { OCDStructure d = defaultStructure(); return d == null ? null : d.root(); }  // default tree (convenience)
    public List<OCDStructure> structures()      { return structures; }
    public OCDDocument addStructure(OCDStructure s) { if (s != null) { structures.add(s); if (defaultStructureId == null) defaultStructureId = s.id(); } return this; }
    public OCDStructure structureById(String id){ if (id != null) for (OCDStructure s : structures) if (id.equals(s.id())) return s; return null; }
    public OCDStructure defaultStructure()      { OCDStructure s = structureById(defaultStructureId); return s != null ? s : (structures.isEmpty() ? null : structures.get(0)); }
    public String      defaultStructureId()     { return defaultStructureId; }
    public OCDDocument defaultStructureId(String id) { this.defaultStructureId = id; return this; }

    // ── analysis provenance: which heuristic passes the document has been through ──
    /** Whether text segmentation (paragraphs/lines) has been performed on this document. */
    public boolean     textSegmented()              { return textSegmented; }
    public OCDDocument textSegmented(boolean v)     { this.textSegmented = v; return this; }
    /** Whether heading/hierarchy detection has been performed on this document. */
    public boolean     headingsDetected()           { return headingsDetected; }
    public OCDDocument headingsDetected(boolean v)  { this.headingsDetected = v; return this; }

    @Override public String toString() {
        return "OCDDocument[" + id + ", " + pages.size() + " pages, "
                + fonts.size() + " fonts, " + images.size() + " images, " + layers.size() + " layers]";
    }
}
