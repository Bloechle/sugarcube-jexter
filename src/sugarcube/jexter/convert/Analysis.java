package sugarcube.jexter.convert;

import sugarcube.jexter.ocd.analysis.Cleaner;
import sugarcube.jexter.ocd.analysis.HeadingRoles;
import sugarcube.jexter.ocd.analysis.OutlineAligner;
import sugarcube.jexter.ocd.analysis.GraphicClusterer;
import sugarcube.jexter.ocd.analysis.Furniture;
import sugarcube.jexter.ocd.analysis.Refiner;
import sugarcube.jexter.ocd.analysis.StructureBuilder;
import sugarcube.jexter.ocd.analysis.BookmarkStructureBuilder;
import sugarcube.jexter.ocd.analysis.Paragrapher;
import sugarcube.jexter.core.LlmClient;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDStructure;
import sugarcube.jexter.ocd.model.IdStamper;
import sugarcube.jexter.ocd.model.OCDParagraph;

/**
 * The OCD-native analysis pipeline, factored out of {@link PdfImporter} so it can run on <i>any</i>
 * in-memory {@link OCDDocument} — whether it came from a PDF or was read back from an OCD-EPUB.
 * Every pass operates on the OCD model (geometry, text, paint order), gated by {@link ConvertOptions}.
 *
 * <p>{@link #run} performs the detection passes: ids, running furniture, segmentation, graphics,
 * heuristic structure (text + titles), reading-order, and an optional LLM refine. It deliberately does
 * <b>not</b> build the PDF/UA tagged structure — that needs the PDF tag tree and is added by
 * {@link PdfImporter} when importing a tagged document.
 *
 * <p>{@link #run} {@linkplain Cleaner#clean cleans} the content first — non-painting tokens and blank runs
 * dropped, render-neutral wrappers spliced, inked content kept — so detection restarts from a clean
 * idempotent base whatever the source, and a re-analysis is simply {@code run} again.
 *
 * <p>{@link #restructureText} and {@link #restructureHierarchy} are the two PARTIAL re-runs Prism offers;
 * each is a documented subset of {@code run}, not a second pipeline — {@code run} stays the stage-order
 * authority.
 */
public final class Analysis {

    private Analysis() {}

    /** Run the OCD-native detection passes on {@code doc}, each gated by {@code opts}. The pass begins by
     *  {@linkplain Cleaner#clean cleaning} the content, so detection always restarts from the same idempotent
     *  base whatever the source — a freshly imported PDF (a near no-op: nothing analysed yet) or an
     *  OCD-EPUB read back with structure already on it (wrappers spliced, refs preserved). */
    public static void run(OCDDocument doc, ConvertOptions opts) {
        // OCD-EPUB / generic entry: a PDF/UA structure is "native" iff one is already on the document.
        OCDStructure pdf = doc.structureById("pdf");
        run(doc, opts, pdf != null && pdf.root() != null && !pdf.root().isEmpty());
    }

    /** Run the detection passes. {@code pdfTagged} = the source carries a PDF/UA tag tree (known by the
     *  {@link PdfImporter} before the {@code pdf} structure is built); it drives the outline-priority gate. */
    public static void run(OCDDocument doc, ConvertOptions opts, boolean pdfTagged) {
        Cleaner.clean(doc);                                                          // clean to the shared base — idempotent, source-agnostic; mints leaf ids (IdStamper.fill) and preserves any a structure already references
        if (opts.get(ConvertOptions.STRUCTURE)) {
            Paragrapher.recompose(doc);                                            // frozen lines (ordered + spaced) → blocks → paragraphs + breaks — ALWAYS re-segment
            doc.textSegmented(true);
        }
        if (opts.get(ConvertOptions.DETECT_HEADERS)) Furniture.detect(doc);          // running heads/feet → node roles, on the reconstructed spaced lines — before the font profile
        if (opts.get(ConvertOptions.GRAPHICS))        GraphicClusterer.cluster(doc); // vector paths → graphics — BEFORE structure, so a FIGURE can wrap a graphic
        if (opts.get(ConvertOptions.STRUCTURE))       OutlineAligner.align(doc);     // nav is ground truth: re-cut/merge title blocks + heading roles — before ids are minted
        IdStamper.fill(doc);                                                         // mint the paragraph + graphic ids created above — continues each per-kind sequence, never disturbs a referenced id
        if (opts.get(ConvertOptions.STRUCTURE)) {
            buildOutline(doc, opts, pdfTagged);
            HeadingRoles.project(doc);                                             // best structure → page-level heading roles (all three worlds)
            doc.headingsDetected(true);
        }
        if (opts.get(ConvertOptions.REFINE_STRUCTURE) && LlmClient.isBound())      // optional LLM logical-structure pass
            Refiner.refine(doc, opts, LlmClient.bound());                     // grounded; no-op on failure
    }

    /**
     * Pick the heuristic-outline behaviour from the structure-priority chain:
     * <ol>
     *   <li><b>PDF/UA tag tree</b> ({@code pdfTagged}) — the {@code pdf} structure wins; built by the importer.</li>
     *   <li>else <b>bookmarks</b> — {@link BookmarkStructureBuilder} resolves them <i>immediately</i> into a
     *       block-referenced {@code outline} structure (so a non-tagged but bookmarked PDF still has a real
     *       structure, not just navigation).</li>
     *   <li>else <b>neither</b> — the heuristic {@link StructureBuilder} is the fallback.</li>
     * </ol>
     * {@link ConvertOptions#GENERATE_OUTLINE} forces the heuristic on top, <b>in parallel</b>, without removing
     * whatever native structure is present (the user explicitly asked to generate one).
     */
    private static void buildOutline(OCDDocument doc, ConvertOptions opts, boolean pdfTagged) {
        boolean bookmarks = !doc.outline().isEmpty();
        if (!pdfTagged && bookmarks) BookmarkStructureBuilder.build(doc);            // resolve nav → real structure, now
        boolean hasNative = pdfTagged || bookmarks;
        if (opts.get(ConvertOptions.GENERATE_OUTLINE) || !hasNative)
            StructureBuilder.build(doc);                                          // forced parallel, or the only source
    }

    /** Re-segment the text of an already-loaded document — no PDF re-import. Cleans to the shared base
     *  (splicing any existing paragraphs back to runs), rebuilds paragraphs/lines, mints the new wrapper
     *  ids. The logical hierarchy is left untouched. Prism's <i>Restructure text</i> action. */
    public static void restructureText(OCDDocument doc, ConvertOptions opts) {
        Cleaner.clean(doc);                                                  // back to clean runs; mints leaf ids; preserves structure refs
        Paragrapher.recompose(doc);                                         // re-segment runs → paragraphs/lines
        if (opts.get(ConvertOptions.GRAPHICS)) GraphicClusterer.cluster(doc); // re-cluster vector graphics — text engulfed in a drawing segments with it, so this must run with the text layer, not be lost
        IdStamper.fill(doc);                                                // ids for the new paragraph + graphic wrappers
        doc.textSegmented(true);
    }

    /** The 100% heuristic re-run of an already-loaded document — no re-segmentation. Clears every
     *  prior page-level heading role (the nav/tag projections ride the pages and would lie),
     *  refreshes running furniture, rebuilds the HEADING/PARAGRAPH outline (replacing any prior
     *  heuristic structure, idempotently), makes it the DEFAULT view, and re-projects it to page
     *  roles — what Analysis shows afterwards is the pure geometry verdict, nothing inherited.
     *  Prism's <i>Restructure hierarchy</i> action. */
    public static void restructureHierarchy(OCDDocument doc, ConvertOptions opts) {
        clearHeadingRoles(doc);
        if (opts.get(ConvertOptions.DETECT_HEADERS)) Furniture.detect(doc);   // running heads/feet → roles (excluded from headings)
        StructureBuilder.build(doc);                                          // FontProfile recurrence → HEADING/PARAGRAPH
        doc.defaultStructureId("heuristic");
        HeadingRoles.project(doc);                                            // heuristic structure → page roles
        doc.headingsDetected(true);
    }

    private static void clearHeadingRoles(OCDDocument doc) {
        for (int pi = 0; pi < doc.pageCount(); pi++)
            doc.page(pi).nodes()
               .filter(OCDParagraph.class::isInstance).map(OCDParagraph.class::cast)
               .filter(p -> p.hasRole() && p.role().startsWith("heading-"))
               .forEach(p -> p.role(null));
    }
}
