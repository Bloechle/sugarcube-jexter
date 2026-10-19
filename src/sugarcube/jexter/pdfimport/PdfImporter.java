package sugarcube.jexter.pdfimport;

import sugarcube.jexter.core.ConvertOptions;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.PDSoftMask;

import sugarcube.jexter.core.JxLog;
import sugarcube.jexter.core.JxNum;
import sugarcube.jexter.core.JxProgress;
import sugarcube.jexter.core.JxRect;
import sugarcube.jexter.ocd.analysis.Analysis;
import sugarcube.jexter.ocd.analysis.Cleaner;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDGroup;
import sugarcube.jexter.ocd.model.OCDLayer;
import sugarcube.jexter.ocd.model.OCDLayerContent;
import sugarcube.jexter.ocd.model.OCDImage;
import sugarcube.jexter.ocd.model.OCDNode;
import sugarcube.jexter.ocd.model.OCDPage;
import sugarcube.jexter.ocd.render.OCDRenderer;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a PDF into an {@link OCDDocument}: the model-building subclass of {@link PdfStreamEngine}.
 * Each extracted node is stored on the page (or into the open group), and transparency groups are
 * modelled as an {@link OCDGroup} tree carrying the blend/alpha live at the {@code Do}. On close, a
 * multi-child group that actually composites (alpha &lt; 1) is BAKED into a single image composited
 * once (so the blend/alpha is applied once, not folded onto every overlapping leaf); everything else
 * stays a faithful vector group.
 *
 * @see PdfRenderer the sibling that renders straight to a raster instead of building a model
 */
public final class PdfImporter extends PdfStreamEngine {

    private final boolean rasterizeGroups;   // bake composited groups (blend/alpha) into one image
    private final double  groupRasterDpi;    // resolution for that baking

    // Open transparency groups (a `Do` of a /Group form). PDFBox composites each group as a UNIT:
    // its content is rendered into its own buffer, then composited once with the blend mode +
    // constant alpha live at the `Do`. We build an OCDGroup per group with that blend/alpha and
    // route its content into it as children. On close, a group that actually composites (alpha<1)
    // is baked into a single image (rasterizeGroups) so the composite happens ONCE — folding the
    // blend onto every leaf instead over-darkens where content overlaps.
    private final Deque<OCDGroup> groups = new ArrayDeque<>();
    private boolean inMask = false;   // true while collecting a soft-mask group (don't re-mask nested)

    protected PdfImporter(PDPage pdPage, OCDDocument doc, OCDPage page, FontExtractor fonts, ConvertOptions opts) {
        super(pdPage, doc, page, fonts, opts.get(ConvertOptions.MERGE_GLYPH_CLIPS));
        this.rasterizeGroups = opts.get(ConvertOptions.RASTERIZE_GROUPS);
        this.groupRasterDpi  = Math.max(36, opts.get(ConvertOptions.GROUP_RASTER_DPI));
    }

    public static OCDDocument convert(File file) throws IOException {
        return convert(file, ConvertOptions.defaults());
    }

    /** @param opts conversion settings (see {@link ConvertOptions}); UIs build these from {@link ConvertOptions#ALL}. */
    public static OCDDocument convert(File file, ConvertOptions opts) throws IOException {
        try (PDDocument pdf = Loader.loadPDF(file)) { return convert(pdf, contentId(sha(), file), opts); }
    }

    /** Import straight from the source bytes. A host that already holds them — an HTTP body, an editor
     *  buffer — has nothing to gain from spooling them to a temp file and reading them back: the array
     *  stays referenced for the whole call either way, so the round trip costs a write and a read and
     *  saves no memory. Measured at ~0.4 s on a 14 MB book, on top of the hash it still has to do. */
    public static OCDDocument convert(byte[] data, ConvertOptions opts) throws IOException {
        try (PDDocument pdf = Loader.loadPDF(data)) { return convert(pdf, contentId(sha(), data), opts); }
    }

    private static OCDDocument convert(PDDocument pdf, String id, ConvertOptions opts) throws IOException {
        OCDDocument doc = new OCDDocument();
        doc.id(id);                                  // content-addressed: same input, same identity
        FontExtractor fonts = new FontExtractor(doc);
        Map<Integer, Map<Mcid, List<OCDNode>>> mcidByPage = new HashMap<>();
        {
            // OPEN goes out FIRST, before any document-wide work: how many pages and how big page one is
            // is all a viewer needs to lay the book out for real, and both are known the moment the PDF is
            // parsed. Announced after the font repair instead, it arrived ~3.9 s in on a 1014-page book —
            // seconds of silence a reader reads as a hang.
            int total = pdf.getNumberOfPages();
            PDRectangle first = total > 0 ? pdf.getPage(0).getMediaBox() : new PDRectangle(0, 0);
            opts.at(JxProgress.Stage.OPEN, 0, total,
                    JxNum.fmt(first.getWidth()) + "x" + JxNum.fmt(first.getHeight()));
            TrueTypeRepair.repair(pdf, opts);
            PdfCatalog.read(pdf, doc);
            OutputIntentCmyk intentCmyk = OutputIntentCmyk.of(doc.outputIntent());
            // Import is the long half of a conversion — a thousand-page book spends ~26 s here. One step
            // every 25 pages, which costs a record and nothing else.
            for (int i = 0; i < total; i++) {
                if (i % 25 == 0) opts.at(JxProgress.Stage.IMPORT, i, total);
                PDPage pd = pdf.getPage(i);
                OCDPage page = PdfCatalog.page(pd);
                PdfImporter engine = new PdfImporter(pd, doc, page, fonts, opts);
                engine.intentCmyk(intentCmyk);
                engine.referenceRaster = PdfCatalog.referenceRaster(pdf, i);
                engine.processPage(pd);
                engine.flushText();        // flush any run still open at page end
                PdfCatalog.annotations(pdf, pd, page);
                mcidByPage.put(i, engine.mcidNodes());
                doc.add(page);
            }
            fonts.finish();                 // sanitise implausible space widths (see FontExtractor)
            PdfCatalog.formFields(pdf, doc);  // AcroForm → per-page form fields
            var st = pdf.getDocumentCatalog().getStructureTreeRoot();
            boolean tagged = opts.get(ConvertOptions.STRUCTURE)
                    && !opts.get(ConvertOptions.IGNORE_TAGS)                          // escape hatch: badly tagged PDFs
                    && st != null && st.getKids() != null && !st.getKids().isEmpty();
            // PDF/UA is the author's own structure: it exists BEFORE the analysis, so the analysis knows the
            // document is tagged, builds no heuristic in its place, and projects ITS headings to the pages.
            // Built after, it came second to whatever the heuristic had already tagged. The clean first gives
            // the marked runs their ids (it is the analysis' own first step, idempotent).
            if (tagged) {
                Cleaner.clean(doc);
                TaggedStructureBuilder.build(doc, st, pdf, mcidByPage);
                doc.defaultStructureId("pdf");
            }
            Analysis.run(doc, opts);
        }
        return doc;
    }

    /** Append a node to the innermost open group, or to the page when none is open. */
    @Override protected void addNode(OCDNode n) {
        if (groups.isEmpty()) page.add(n);
        else groups.peek().add(n);
        Mcid mcid = currentMcid();
        if (mcid != null) mcidNodes.computeIfAbsent(mcid, k -> new ArrayList<>()).add(n);  // capture node; id minted later by IdStamper.fill
    }

    /** page-local map: marked-content id → the content nodes tagged with it (ids read after stamping). */
    private final Map<Mcid, List<OCDNode>> mcidNodes = new HashMap<>();
    Map<Mcid, List<OCDNode>> mcidNodes() { return mcidNodes; }

    /** Optional content {@code /OC … BDC}: register the layer once in the document registry and open
     *  an {@link OCDLayerContent} to collect the content until the matching {@code EMC}. */
    @Override protected void beginLayer(String layerId, String name) {
        flushText();                                   // z-order boundary
        if (doc.layer(layerId) == null) doc.add(new OCDLayer(layerId, name));
        OCDLayerContent lg = new OCDLayerContent(layerId);
        lg.z(z++);   // paint order; id minted later by IdStamper
        groups.push(lg);
    }

    @Override protected void endLayer() {
        flushText();
        OCDGroup g = groups.pop();
        if (!g.isEmpty()) addNode(g);                  // emit the layer group into its parent (or page)
    }

    /** A `Do` of a transparency-group form. We build an OCDGroup carrying the blend mode and
     *  constant alpha live on the graphics state HERE (not inside the group, where the state is
     *  reset), with the group's content as children. On close: if the group actually composites
     *  (alpha &lt; 1, multiple children) and {@code rasterizeGroups} is on, we bake its content into
     *  one image composited once with that blend/alpha — otherwise we keep the vector group (the
     *  renderer applies the group's blend to its children). */
    @Override public void showTransparencyGroup(PDTransparencyGroup form) throws IOException {
        flushText();   // groups are z-order boundaries
        PDGraphicsState gs = getGraphicsState();
        PDSoftMask sm = inMask ? null : gs.getSoftMask();
        boolean masked = sm != null && sm.getGroup() != null;
        OCDGroup g = new OCDGroup();
        String blend = blendName(gs.getBlendMode());
        float alpha = (float) gs.getNonStrokeAlphaConstant();
        if (blend != null) g.blend(blend);
        if (alpha < 1f)    g.alpha(alpha);
        g.z(z++);    // paint order; id minted later by IdStamper

        boolean prev = inMask;
        inMask = inMask || masked;
        OCDGroup mgrp = null;
        try {
            groups.push(g);
            try { descendGroup(form); } finally { flushText(); groups.pop(); }
            if (masked && !g.isEmpty()) {            // collect the mask group's content too
                mgrp = new OCDGroup();
                groups.push(mgrp);
                try { descendGroup(sm.getGroup()); } finally { flushText(); groups.pop(); }
            }
        } finally { inMask = prev; }

        if (g.isEmpty()) return;
        // Luminosity soft mask (e.g. a soft drop shadow): bake the content masked by the mask
        // group's luminosity into one image — same composite-once principle as a plain group bake.
        if (masked && rasterizeGroups && mgrp != null && maskedBake(g, mgrp, sm)) return;
        // Folding a group's alpha onto every leaf only over-darkens when several children
        // OVERLAP inside the same low-alpha group (each gets the alpha) — that needs the
        // composite-once bake. A single-child group can't compound: per-leaf already matches,
        // and baking it isolated would only add a resampled rectangle. So bake only the
        // multi-child low-alpha groups; everything else stays a faithful vector group.
        boolean composites = g.alpha() < 1f && g.size() > 1;
        if (rasterizeGroups && composites && bakeGroup(g)) return;   // baked → image emitted
        addNode(g);                                                  // else keep the vector group
    }

    /** Crop = group bounds ∩ page; null/empty when there's nothing to bake. */
    private JxRect bakeCrop(OCDGroup g) {
        JxRect crop = page.effectiveBox().intersection(inkBounds(g));
        return crop.width() < 0.5 || crop.height() < 0.5 ? null : crop;
    }

    /**
     * How far the group's INK reaches past its geometry — half of the widest stroke it contains.
     *
     * <p>The padding is PER NODE, not per group: a wide stroke reaches around its own path and
     * nowhere else, and inflating the whole group's box instead moved every baked drop shadow of a
     * press page by the width of the fattest rule it happened to share a group with.
     *
     * <p>A node's bounds are its geometry, by contract: a horizontal rule is 0 pt tall. Its ink is
     * not: a 4 pt stroke sticks out 2 pt on each side, and a round cap puts a half-disc past each
     * end. Baking a group to a raster cropped to the geometry therefore SHAVES the stroke — a
     * dotted rule ({@code [0 11.8] 0 d} with a round cap, the standard dotted separator) came back
     * as a 3-pixel-tall sliver of each dot, round in PDFBox and in the Direct raster, flat in ours.
     * A miter join can reach further still, so the allowance follows it, bounded: an unbounded
     * miter on a near-degenerate corner would inflate the crop to the page.
     */
    private static JxRect inkBounds(OCDNode n) {
        if (n instanceof OCDGroup g) {
            JxRect box = null;
            for (OCDNode c : g.children()) {
                JxRect cb = inkBounds(c);
                box = box == null ? cb : cb == null ? box : box.span(cb);
            }
            return box == null ? g.bounds() : box;
        }
        JxRect b = n.bounds();
        if (b == null) return null;
        double pad = 0;
        if (n instanceof sugarcube.jexter.ocd.model.OCDPath p && p.isStroked())
            pad = p.pen().width() / 2 * (p.pen().join() == sugarcube.jexter.core.JxStroke.Join.MITER
                    ? Math.min(Math.max(p.pen().miterLimit(), 1), 4) : 1);
        if (n instanceof sugarcube.jexter.ocd.model.OCDText t && t.hasStroke()) pad = t.pen().width() / 2;
        return pad <= 0 ? b : b.inflate(pad, pad);
    }

    /** Emit a composited image for group {@code g} carrying its blend/alpha (Y-up unit-square map). */
    private void emitBaked(OCDGroup g, JxRect crop, BufferedImage img) throws IOException {
        OCDImage n = placed(img, new java.awt.geom.Rectangle2D.Double(crop.x(), crop.y(), crop.width(), crop.height()));
        n.id(g.id()).z(g.z());
        if (g.hasBlend()) n.blend(g.blend());
        if (g.alpha() < 1f) n.alpha(g.alpha());
        addNode(n);
    }

    /** Composite a group's content ONCE into an image: render its children isolated (transparent
     *  backdrop, Normal compositing) cropped to their bounds, then emit that image carrying the
     *  group's blend/alpha. Returns false (caller keeps the vector group) if there's nothing to bake. */
    private boolean bakeGroup(OCDGroup g) {
        try {
            JxRect crop = bakeCrop(g);
            if (crop == null) return false;
            liftBlend(g);   // should the bake fail, the vector group left behind composites as one: same picture
            emitBaked(g, crop, OCDRenderer.renderNodes(g.children(), crop, page, doc, groupRasterDpi));
            return true;
        } catch (Exception e) {
            JxLog.debug(PdfImporter.class, "group raster bake failed \u2192 vector fallback", e);
            return false;   // any failure → fall back to the vector group
        }
    }

    /**
     * A bake renders the group ALONE, on a transparent canvas — but a leaf's blend mode is defined
     * against what lies UNDER it on the page. When every painted leaf of the group carries the same
     * non-Normal blend (and the group none of its own), that blend is lifted off the leaves onto the
     * group, so the baked image is blended with the page instead of with nothing. The usual case is
     * an InDesign drop shadow: one rectangle in Multiply under a soft mask. A WHITE one multiplied
     * onto a light page is invisible, and baked in isolation it came out as a visible white halo
     * behind a headline; a yellow one onto white paper stays yellow either way. Only overlaps
     * between the leaves themselves differ, and a shadow has one leaf.
     * @return the lifted blend, now set on {@code g}, or null when nothing was lifted
     */
    private static String liftBlend(OCDGroup g) {
        if (g.hasBlend() && !"Normal".equals(g.blend())) return null;
        String common = null;
        java.util.List<OCDNode> leaves = g.stream().filter(n -> !(n instanceof OCDGroup)
                && !(n instanceof sugarcube.jexter.ocd.model.OCDBreak)).toList();
        for (OCDNode n : leaves) {
            String b = n.hasBlend() ? n.blend() : "Normal";
            if ("Normal".equals(b) || (common != null && !common.equals(b))) return null;
            common = b;
        }
        if (common == null) return null;
        for (OCDNode n : leaves) n.blend(null);
        g.blend(common);
        return common;
    }

    /** Bake a Luminosity-soft-masked group: render the content and the mask isolated over the same
     *  crop, multiply the content's alpha by the mask's luminosity, emit the result as one image. */
    private boolean maskedBake(OCDGroup g, OCDGroup mgrp, PDSoftMask sm) {
        try {
            JxRect crop = bakeCrop(g);
            if (crop == null) return false;
            liftBlend(g);
            BufferedImage cimg = OCDRenderer.renderNodes(g.children(), crop, page, doc, groupRasterDpi);
            BufferedImage mimg = OCDRenderer.renderNodes(mgrp.children(), crop, page, doc, groupRasterDpi);
            SoftMasks.apply(((DataBufferInt) cimg.getRaster().getDataBuffer()).getData(),
                            ((DataBufferInt) mimg.getRaster().getDataBuffer()).getData(), sm);
            emitBaked(g, crop, cimg);
            return true;
        } catch (Exception e) {
            JxLog.debug(PdfImporter.class, "image bake failed \u2192 vector fallback", e);
            return false;
        }
    }

    /** First 8 hex chars of the source's SHA-256 — the document identity is content-addressed, so a
     *  re-import of the same PDF yields the same id (and downstream, the same EPUB identifiers). */
    private static java.security.MessageDigest sha() throws IOException {
        try { return java.security.MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }   // SHA-256 is mandatory in every JRE
    }

    private static String contentId(java.security.MessageDigest md, File f) throws IOException {
        try (var in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        return java.util.HexFormat.of().formatHex(md.digest(), 0, 4);
    }

    private static String contentId(java.security.MessageDigest md, byte[] data) {
        md.update(data);
        return java.util.HexFormat.of().formatHex(md.digest(), 0, 4);
    }
}
