package sugarcube.jexter.convert;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sugarcube.jexter.core.JxStringer;

/**
 * Settings for {@link PdfImporter#convert(java.io.File, ConvertOptions)}.
 *
 * <p>Each option is a self-describing {@link Opt} (stable key + UI metadata + typed default +
 * {@link Group}), registered in {@link #ALL}. UIs (Prism, PDFInspector) iterate {@code ALL} to
 * render controls generically — no per-option UI code — and may section them by {@code group};
 * values cross the {@code /api} boundary through the {@code String}-map (de)serialization.
 *
 * <p>Adding a setting = add one {@code Opt} constant (with its group) and list it in {@code ALL};
 * nothing else changes in the importer plumbing or the UI wiring. Behaviour lives elsewhere — the
 * analysis groups map onto the render-neutral passes in {@code sugarcube.jexter.ocd.analysis};
 * this class is only the introspectable configuration façade.
 */
public final class ConvertOptions {

    public enum Type { BOOL, INT, DOUBLE, STRING }

    /** UI section a setting belongs to: the conversion mechanics, one of the analysis facets,
     *  or the per-target export/projection settings consumed by the {@code write.*} writers. */
    public enum Group { CONVERSION, STRUCTURE, TEXT, METADATA, EXPORT }

    /** A self-describing, typed option: stable {@code key}, UI {@code label}/{@code help}, default, group. */
    public record Opt<T>(String key, String label, String help, Type type, T def, Group group) {}

    // ── CONVERSION — import & render mechanics (fidelity / output) ───────────────
    public static final Opt<Boolean> MERGE_GLYPH_CLIPS = new Opt<>(
            "mergeGlyphClips",
            "Merge per-glyph auto-clips",
            "Drops clips applied letter by letter (identical rendering, cleaner model). "
                    + "Disable to keep the faithful raw model (one run per clipped glyph).",
            Type.BOOL, Boolean.TRUE, Group.CONVERSION);

    public static final Opt<Boolean> RASTERIZE_GROUPS = new Opt<>(
            "rasterizeGroups",
            "Rasterize composited groups",
            "When a transparency group must be composited as a unit (a blend mode or opacity < 1), "
                    + "render it once to an image instead of applying the blend to every path (which "
                    + "over-darkens overlapping areas). Disable to keep the vector model (lower fidelity "
                    + "on dense facets, vector re-export).",
            Type.BOOL, Boolean.TRUE, Group.CONVERSION);

    public static final Opt<Integer> GROUP_RASTER_DPI = new Opt<>(
            "groupRasterDpi",
            "Group rasterization resolution (dpi)",
            "Resolution of the images produced for rasterized composited groups.",
            Type.INT, 300, Group.CONVERSION);

    public static final Opt<Integer> EXPORT_PRECISION = new Opt<>(
            "exportPrecision",
            "Export decimal precision",
            "Number of decimal places for coordinates, sizes and matrices in exported files "
                    + "(OCD-EPUB, SVG, EPUB, font JSON). Lower = smaller files; 4 is well below a device "
                    + "pixel at page scale.",
            Type.INT, 4, Group.CONVERSION);

    public static final Opt<Boolean> RENDER_ANNOTATIONS = new Opt<>(
            "renderAnnotations",
            "Render annotation layer",
            "Paint the page annotation layer — review markup (highlights, underlines, strike-outs, "
                    + "notes, free text) and form-field widgets — on top of the content when rendering and "
                    + "projecting to SVG/EPUB. The annotations are always captured in the model; this only "
                    + "controls their display. Disable to see the bare content (handy for debugging).",
            Type.BOOL, Boolean.TRUE, Group.CONVERSION);

    // ── STRUCTURE — layout, tables, vector grouping, reading order ───────────────
    public static final Opt<Boolean> STRUCTURE = new Opt<>(
            "structure",
            "Detect text structure (paragraphs/lines)",
            "Group the page's text runs into a paragraph → line → run hierarchy from their "
                    + "geometry. Purely structural (rendering is identical); the logical layer the "
                    + "structured format builds on. Disable to keep the flat run list.",
            Type.BOOL, Boolean.TRUE, Group.STRUCTURE);

    public static final Opt<Boolean> DETECT_HEADERS = new Opt<>(
            "detectHeaders",
            "Detect headers & footers",
            "On documents of at least three pages, tag running headers, footers and page numbers by "
                    + "vertical projection profile: ink is accumulated per Y band across pages, and the margin "
                    + "valley separating the header/footer peak from the variable body mass is the cut (rules "
                    + "dissolve into the band, footnotes stay with the body). Section-varying heads are caught "
                    + "since the cut is positional, not textual. Non-destructive \u2014 it only sets a node role.",
            Type.BOOL, Boolean.TRUE, Group.STRUCTURE);

    public static final Opt<Boolean> IGNORE_TAGS = new Opt<>(
            "ignoreTags",
            "Ignore the PDF tag structure",
            "Skip the PDF/UA structure tree even when the PDF is tagged: navigation and heading "
                    + "detection fall back to the bookmarks (then the heuristic), and the outline "
                    + "alignment works from those instead. The escape hatch for badly tagged PDFs \u2014 "
                    + "re-run the mill without trusting the tags.",
            Type.BOOL, Boolean.FALSE, Group.STRUCTURE);

    public static final Opt<Boolean> GRAPHICS = new Opt<>(
            "graphics",
            "Group vector graphics",
            "Cluster contiguous vector paths that form one drawing into a graphic node (logos, charts), "
                    + "leaving page furniture (frames, rules, backgrounds) loose. Structural only "
                    + "(rendering identical). Disable to keep the flat path list.",
            Type.BOOL, Boolean.TRUE, Group.STRUCTURE);

    public static final Opt<Boolean> RESTRUCTURE_TEXT = new Opt<>(
            "restructureText",
            "Restructure text (re-segment)",
            "Re-run text segmentation (paragraphs / lines) on an already-loaded document, without re-importing "
          + "a PDF. Used by Prism to restructure a standalone OCD-EPUB; off by default so loading one never "
          + "re-analyses it.",
            Type.BOOL, Boolean.FALSE, Group.STRUCTURE);

    public static final Opt<Boolean> RESTRUCTURE_HIERARCHY = new Opt<>(
            "restructureHierarchy",
            "Restructure hierarchy (re-detect headings)",
            "Re-run heading / hierarchy detection on an already-loaded document, without re-segmenting the text "
          + "or re-importing a PDF. Used by Prism to restructure a standalone OCD-EPUB; off by default.",
            Type.BOOL, Boolean.FALSE, Group.STRUCTURE);

    public static final Opt<Boolean> GENERATE_OUTLINE = new Opt<>(
            "generateOutline",
            "Generate a heuristic outline",
            "Force the heuristic HEADING/PARAGRAPH detection even when the document already carries its own "
          + "structure (a PDF/UA tag tree or a resolved bookmark outline). When off (default), the heuristic "
          + "runs only for documents with no native structure of their own. The 'Generate outline' button sets "
          + "this; the generated outline is added alongside the existing one (it is never replaced).",
            Type.BOOL, Boolean.FALSE, Group.STRUCTURE);

    public static final Opt<Boolean> REFINE_STRUCTURE = new Opt<>(
            "refineStructure",
            "Refine structure with an LLM",
            "After heuristic structuring, ask a bound language model to re-derive the logical structure "
          + "(headings, lists, tables) by reference to the existing content blocks. Adds a separate MODEL "
          + "structure alongside the heuristic one; it can only regroup/relabel existing blocks, never alter "
          + "geometry or fidelity. Requires a bound LlmClient; a no-op when none is bound.",
            Type.BOOL, Boolean.FALSE, Group.STRUCTURE);

    public static final Opt<String> LLM_MODEL = new Opt<>(
            "llmModel",
            "LLM model (provenance)",
            "Model identifier recorded as the refined structure's provenance. The bound LlmClient decides the "
          + "actual endpoint/model; leave blank to use the client's own model id.",
            Type.STRING, "", Group.STRUCTURE);

    // ── EXPORT — per-target projection settings consumed by the write.* writers ──
    public static final Opt<Integer> PAGE = new Opt<>(
            "page",
            "Page (SVG)",
            "0-based page index for the single-page SVG projection. Ignored by whole-document "
                    + "targets (PDF, EPUB, HTML, Markdown, DocTags), which always cover every page.",
            Type.INT, 0, Group.EXPORT);

    public static final Opt<Boolean> SELECTABLE = new Opt<>(
            "selectable",
            "Selectable text (PDF)",
            "Embed fonts and place real, copyable text in the PDF projection. Off = a pixel-exact "
                    + "outline facsimile (text not selectable). Only affects the PDF target.",
            Type.BOOL, Boolean.FALSE, Group.EXPORT);

    public static final Opt<Integer> DOCTAGS_GRID = new Opt<>(
            "grid",
            "Location grid (DocTags)",
            "Integer grid the DocTags projection quantizes <loc_> boxes onto (e.g. 500 → coordinates "
                    + "0..500). Higher = finer grounding. Only affects the DocTags target.",
            Type.INT, 500, Group.EXPORT);

    public static final Opt<String> DEFAULT_STRUCTURE = new Opt<>(
            "defaultStructure",
            "Default structure (export)",
            "Id of the structure every exporter treats as the document default (e.g. \"heuristic\", \"pdf\", "
                    + "\"model\", \"manual\"). When set and present in the document it becomes the default before "
                    + "writing — so HTML/EPUB/Markdown/DocTags and the PDF outline project from it, and a re-saved "
                    + "the OCD-EPUB records it. Blank keeps the document's own default.",
            Type.STRING, "", Group.EXPORT);

    /** The single source of truth UIs iterate over, declared in {@link Group} order. */
    public static final List<Opt<?>> ALL = List.<Opt<?>>of(
            MERGE_GLYPH_CLIPS, RASTERIZE_GROUPS, GROUP_RASTER_DPI, EXPORT_PRECISION, RENDER_ANNOTATIONS,
            STRUCTURE, DETECT_HEADERS, IGNORE_TAGS,
            GRAPHICS, GENERATE_OUTLINE, RESTRUCTURE_TEXT, RESTRUCTURE_HIERARCHY, REFINE_STRUCTURE, LLM_MODEL,
            PAGE, SELECTABLE, DOCTAGS_GRID, DEFAULT_STRUCTURE);

    // ── values (sparse: only overrides are stored; missing keys fall back to def) ─
    private final Map<String, Object> values = new LinkedHashMap<>();

    public static ConvertOptions defaults() { return new ConvertOptions(); }

    @SuppressWarnings("unchecked")
    public <T> T get(Opt<T> o) {
        return values.containsKey(o.key()) ? (T) values.get(o.key()) : o.def();
    }

    public <T> ConvertOptions set(Opt<T> o, T value) {
        values.put(o.key(), value);
        return this;
    }

    /**
     * The full {@code /api/options} response body, as one JSON string — the single serialization
     * shared by every convert front (Prism desktop + the cloud), so both emit byte-identical
     * shape: {@code { aiBound, aiModel, aiProvider, aiEffort, options:[ {key,label,help,type,group,def} … ] } }.
     * The {@code ai*} fields describe the bound LLM (all empty / false where there is none).
     */
    public static String optionsJson(boolean aiBound, String aiModel, String aiProvider, String aiEffort) {
        StringBuilder b = new StringBuilder("{\"aiBound\":").append(aiBound)
                .append(",\"aiModel\":").append(JxStringer.quoted(aiModel))
                .append(",\"aiProvider\":").append(JxStringer.quoted(aiProvider))
                .append(",\"aiEffort\":").append(JxStringer.quoted(aiEffort))
                .append(",\"options\":[");
        for (int i = 0; i < ALL.size(); i++) {
            Opt<?> o = ALL.get(i);
            JxStringer js = new JxStringer().obj()
                    .str("key", o.key()).str("label", o.label()).str("help", o.help())
                    .str("type", o.type().name().toLowerCase())
                    .str("group", o.group().name().toLowerCase());
            switch (o.type()) {
                case BOOL   -> js.bool("def", Boolean.TRUE.equals(o.def()));
                case INT    -> js.num("def", o.def() instanceof Number n ? n.longValue() : 0L);
                case DOUBLE -> js.num("def", o.def() instanceof Number n ? n.doubleValue() : 0.0);
                default     -> js.str("def", String.valueOf(o.def()));
            }
            b.append(js.end().toString());
            if (i < ALL.size() - 1) b.append(',');
        }
        return b.append("]}").toString();
    }

    // ── API boundary: string map ⇄ options (Prism /api, PDFInspector) ───────────
    public static ConvertOptions fromMap(Map<String, String> m) {
        ConvertOptions o = new ConvertOptions();
        if (m != null)
            for (Opt<?> opt : ALL) {
                String raw = m.get(opt.key());
                if (raw != null) o.values.put(opt.key(), parse(opt.type(), raw));
            }
        return o;
    }

    /** Every option's effective value (overrides + defaults) — ready to send to a UI. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Opt<?> opt : ALL) m.put(opt.key(), values.getOrDefault(opt.key(), opt.def()));
        return m;
    }

    private static Object parse(Type t, String v) {
        return switch (t) {
            case BOOL   -> Boolean.parseBoolean(v);
            case INT    -> Integer.parseInt(v.trim());
            case DOUBLE -> Double.parseDouble(v.trim());
            case STRING -> v;
        };
    }
}
