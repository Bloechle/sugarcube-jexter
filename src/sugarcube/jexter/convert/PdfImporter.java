package sugarcube.jexter.convert;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.PDSoftMask;

import sugarcube.jexter.core.JxLog;
import sugarcube.jexter.core.JxRect;
import sugarcube.jexter.core.JxTransform;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDGroup;
import sugarcube.jexter.ocd.model.OCDLayer;
import sugarcube.jexter.ocd.model.OCDLayerContent;
import sugarcube.jexter.ocd.model.OCDImage;
import sugarcube.jexter.ocd.model.OCDNode;
import sugarcube.jexter.ocd.model.OCDPage;
import sugarcube.jexter.ocd.render.OCDRenderer;

import javax.imageio.ImageIO;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

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
        OCDDocument doc = new OCDDocument();
        doc.id(contentId(file));                     // content-addressed: same input, same identity
        FontExtractor fonts = new FontExtractor(doc);
        java.util.Map<Integer, java.util.Map<Integer, java.util.List<OCDNode>>> mcidByPage = new java.util.HashMap<>();
        try (PDDocument pdf = Loader.loadPDF(file)) {
            repairEmbeddedTrueTypeFonts(pdf);
            extractMetadata(pdf, doc.meta());
            extractOutline(pdf, doc);
            extractLayers(pdf, doc);
            for (int i = 0; i < pdf.getNumberOfPages(); i++) {
                PDPage pd = pdf.getPage(i);
                PDRectangle mb = pd.getMediaBox();
                OCDPage page = new OCDPage("p" + (i + 1),
                        new JxRect(mb.getLowerLeftX(), mb.getLowerLeftY(), mb.getWidth(), mb.getHeight()));
                PDRectangle cb = pd.getCropBox();
                if (cb != null)
                    page.cropBox(new JxRect(cb.getLowerLeftX(), cb.getLowerLeftY(), cb.getWidth(), cb.getHeight()));
                page.rotation(pd.getRotation());
                extractPrintBoxes(pd, page);

                PdfImporter engine = new PdfImporter(pd, doc, page, fonts, opts);
                final int pageIx = i;
                // The reference raster feeds the unified fallback for paints the model cannot
                // vectorise (tiling patterns, soft-masked fills): PDFBox's own renderer, lazily,
                // ~2 px/page-unit capped at 4 MP — the same budget as mesh shadings.
                engine.referenceRaster = () -> {
                    try {
                        double scale = 2.0;
                        long px = (long) Math.ceil(mb.getWidth() * scale) * (long) Math.ceil(mb.getHeight() * scale);
                        if (px > 4_000_000) scale *= Math.sqrt(4_000_000.0 / px);
                        return new org.apache.pdfbox.rendering.PDFRenderer(pdf)
                                .renderImage(pageIx, (float) scale);
                    } catch (Exception e) {
                        JxLog.debug(PdfImporter.class, "reference raster failed", e);
                        return null;
                    }
                };
                engine.processPage(pd);
                engine.flushText();        // flush any run still open at page end
                extractAnnotations(pd, page);
                mcidByPage.put(i, engine.mcidNodes());
                doc.add(page);
            }
            fonts.finish();                 // sanitise implausible space widths (see FontExtractor)
            extractFormFields(pdf, doc);    // AcroForm → per-page form fields
            var st = pdf.getDocumentCatalog().getStructureTreeRoot();
            boolean tagged = opts.get(ConvertOptions.STRUCTURE)
                    && !opts.get(ConvertOptions.IGNORE_TAGS)                          // escape hatch: badly tagged PDFs
                    && st != null && st.getKids() != null && !st.getKids().isEmpty();
            Analysis.run(doc, opts, tagged);                                          // OCD-native detection; outline priority gated on `tagged`
            if (tagged) {                                                             // PDF/UA ground truth on top, when present
                TaggedStructureBuilder.build(doc, st, pdf, mcidByPage);              // ground truth (PDF/UA)
                doc.defaultStructureId("pdf");                                        // … becomes the default structure
                sugarcube.jexter.ocd.analysis.HeadingRoles.project(doc);              // now that it exists, project its headings to page roles too
            }
        }
        return doc;
    }

    /** Pull the PDF info dictionary + catalog language into the typed metadata. */
    private static void extractMetadata(PDDocument pdf, sugarcube.jexter.ocd.model.OCDMeta m) {
        var info = pdf.getDocumentInformation();
        if (info != null) {
            m.title(info.getTitle()).subject(info.getSubject())
             .creator(info.getCreator()).producer(info.getProducer())
             .created(iso(info.getCreationDate())).modified(iso(info.getModificationDate()));
            splitInto(info.getAuthor(),   ";",     m::addAuthor);    // multi-author separator
            splitInto(info.getKeywords(), "[;,]",  m::addKeyword);
        }
        // XMP overlay — the modern, authoritative channel; richer than the info-dict on
        // contemporary PDFs (ordered authors, identifier, publisher, rights, ISO dates).
        try {
            var xmp = pdf.getDocumentCatalog().getMetadata();
            if (xmp != null) XmpReader.apply(xmp.createInputStream(), m);
        } catch (Exception ignore) { /* malformed/absent XMP → info-dict stands */ }
        // the catalog /Lang is the document's declared primary language → last word
        String lang = pdf.getDocumentCatalog().getLanguage();
        if (lang != null) m.language(lang);
    }

    private static void splitInto(String s, String regex, java.util.function.Consumer<String> add) {
        if (s == null) return;
        for (String p : s.split(regex)) if (!p.isBlank()) add.accept(p.trim());
    }

    /** The layer registry is document-level presentation metadata, so it is read from the document's own
     *  optional-content configuration — not discovered from the content stream. {@code /OC … BDC} tells us
     *  a layer is USED; only {@code /OCProperties /D} says whether it is ON, in which order, and it is the
     *  single place that knows. Registered here, {@link #beginLayer} finds the entry already present and
     *  keeps its state; an OCG referenced by content but never declared still falls back to a default entry.
     *  Without this pass every layer defaulted to visible and a hidden stratum painted on every surface —
     *  invisible to the model gates (the layer count round-trips either way) and caught only by comparing a
     *  browser render against the PDFBox reference. */
    private static void extractLayers(PDDocument pdf, OCDDocument doc) {
        try {
            var oc = pdf.getDocumentCatalog().getOCProperties();
            if (oc == null) return;
            int order = 0;
            for (String name : oc.getGroupNames()) {
                String id = "L_" + name.trim().replaceAll("\\s+", "_");     // the id layerOf mints, one convention
                OCDLayer l = doc.layer(id);
                if (l == null) doc.add(l = new OCDLayer(id, name.trim()));
                l.visible(oc.isGroupEnabled(name)).order(order++);
            }
        } catch (Exception e) {
            JxLog.debug(PdfImporter.class, "optional-content properties unreadable", e);
        }
    }

    private static String iso(java.util.Calendar c) { return c == null ? "" : c.toInstant().toString(); }

    /** Capture bleed/trim/art only when genuinely distinct from media & crop (PDFBox cascades them otherwise). */
    private static void extractPrintBoxes(PDPage pd, OCDPage page) {
        JxRect media = page.mediaBox(), content = page.effectiveBox();
        JxRect bleed = box(pd.getBleedBox()), trim = box(pd.getTrimBox()), art = box(pd.getArtBox());
        if (distinct(bleed, media, content)) page.bleedBox(bleed);
        if (distinct(trim,  media, content)) page.trimBox(trim);
        if (distinct(art,   media, content)) page.artBox(art);
    }
    private static JxRect box(PDRectangle r) {
        return r == null ? null : new JxRect(r.getLowerLeftX(), r.getLowerLeftY(), r.getWidth(), r.getHeight());
    }
    private static boolean distinct(JxRect b, JxRect media, JxRect content) {
        return b != null && !b.equals(media) && !b.equals(content);
    }

    /** Read the PDF bookmark tree into the document outline, resolving destinations to (page, y). */
    private static void extractOutline(PDDocument pdf, OCDDocument doc) {
        var root = pdf.getDocumentCatalog().getDocumentOutline();
        if (root == null) return;
        for (var it : root.children()) doc.addOutline(toOutline(it, pdf));
    }

    private static sugarcube.jexter.ocd.model.OCDOutline toOutline(
            org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem it, PDDocument pdf) {
        var o = new sugarcube.jexter.ocd.model.OCDOutline(it.getTitle());
        try {
            var dest = it.getDestination();
            if (dest == null && it.getAction() instanceof
                    org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo go) dest = go.getDestination();
            double[] d = resolve(dest);
            if (d[0] >= 0) o.pageIndex((int) d[0]);
            if (!Double.isNaN(d[1])) o.y(d[1]);
        } catch (Exception ignore) { /* unresolved destination → title-only node */ }
        for (var child : it.children()) o.add(toOutline(child, pdf));
        return o;
    }

    /** Resolve a GoTo destination to {pageIndex (-1 if none), y (NaN if none)}. Shared by outline + links. */
    private static double[] resolve(org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination dest) {
        double[] r = { -1, Double.NaN };
        try {
            if (dest instanceof org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination pd) {
                int idx = pd.retrievePageNumber();
                if (idx >= 0) r[0] = idx;
                if (pd instanceof org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination xyz
                        && xyz.getTop() != -1) r[1] = xyz.getTop();
            }
        } catch (Exception ignore) {}
        return r;
    }

    /** Pull each page's annotations into the model: links (targets resolved) and the
     *  comment/markup family (highlights, notes, …). Form widgets are handled document-side. */
    private static void extractAnnotations(PDPage pd, OCDPage page) {
        try {
            for (var an : pd.getAnnotations()) {
                if (an instanceof org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink lk) {
                    PDRectangle r = lk.getRectangle();
                    if (r == null) continue;
                    var link = new sugarcube.jexter.ocd.model.OCDLink(
                            new JxRect(r.getLowerLeftX(), r.getLowerLeftY(), r.getWidth(), r.getHeight()));
                    var action = lk.getAction();
                    if (action instanceof org.apache.pdfbox.pdmodel.interactive.action.PDActionURI u) {
                        link.uri(u.getURI());
                    } else {
                        var dest = lk.getDestination();
                        if (dest == null && action instanceof org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo go)
                            dest = go.getDestination();
                        double[] d = resolve(dest);
                        if (d[0] >= 0) link.pageIndex((int) d[0]);
                        if (!Double.isNaN(d[1])) link.y(d[1]);
                    }
                    if (link.isExternal() || link.hasDestination()) page.addLink(link);
                } else if (!(an instanceof org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget)) {
                    var a = markup(an);                      // widgets → handled via the AcroForm tree
                    if (a != null) page.addAnnotation(a);
                }
            }
        } catch (Exception ignore) { /* annotation parsing best-effort */ }
    }

    /** Build a markup/comment annotation from a non-link, non-widget PDF annotation, or null to skip. */
    private static sugarcube.jexter.ocd.model.OCDAnnotation markup(
            org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation an) {
        String sub = an.getSubtype();
        if (sub == null || "Popup".equals(sub)) return null;     // popups are the window of another markup
        var type = switch (sub) {
            case "Highlight" -> sugarcube.jexter.ocd.model.OCDAnnotation.Markup.HIGHLIGHT;
            case "Underline" -> sugarcube.jexter.ocd.model.OCDAnnotation.Markup.UNDERLINE;
            case "StrikeOut" -> sugarcube.jexter.ocd.model.OCDAnnotation.Markup.STRIKEOUT;
            case "Squiggly"  -> sugarcube.jexter.ocd.model.OCDAnnotation.Markup.SQUIGGLY;
            case "Text"      -> sugarcube.jexter.ocd.model.OCDAnnotation.Markup.NOTE;
            case "FreeText"  -> sugarcube.jexter.ocd.model.OCDAnnotation.Markup.FREETEXT;
            case "Stamp"     -> sugarcube.jexter.ocd.model.OCDAnnotation.Markup.STAMP;
            case "Ink"       -> sugarcube.jexter.ocd.model.OCDAnnotation.Markup.INK;
            case "Line"      -> sugarcube.jexter.ocd.model.OCDAnnotation.Markup.LINE;
            case "Square", "Circle", "Polygon", "PolyLine" -> sugarcube.jexter.ocd.model.OCDAnnotation.Markup.SHAPE;
            default          -> sugarcube.jexter.ocd.model.OCDAnnotation.Markup.OTHER;
        };
        var a = new sugarcube.jexter.ocd.model.OCDAnnotation(type);
        PDRectangle r = an.getRectangle();
        if (r != null) a.rect(new JxRect(r.getLowerLeftX(), r.getLowerLeftY(), r.getWidth(), r.getHeight()));
        a.contents(an.getContents()).modified(an.getModifiedDate());
        a.author(an.getCOSObject().getString(org.apache.pdfbox.cos.COSName.T));
        try {
            var col = an.getColor();
            if (col != null) a.color(new sugarcube.jexter.core.JxColor(0xFF000000 | (col.toRGB() & 0xFFFFFF)));
        } catch (Exception ignore) {}
        if (an instanceof org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup tm) {
            float[] q = tm.getQuadPoints();
            if (q != null) for (int i = 0; i + 7 < q.length; i += 8) {
                double minX = Math.min(Math.min(q[i], q[i + 2]), Math.min(q[i + 4], q[i + 6]));
                double maxX = Math.max(Math.max(q[i], q[i + 2]), Math.max(q[i + 4], q[i + 6]));
                double minY = Math.min(Math.min(q[i + 1], q[i + 3]), Math.min(q[i + 5], q[i + 7]));
                double maxY = Math.max(Math.max(q[i + 1], q[i + 3]), Math.max(q[i + 5], q[i + 7]));
                a.addQuad(new JxRect(minX, minY, maxX - minX, maxY - minY));
            }
        }
        return a;
    }

    /** Document-level: flatten the AcroForm into per-page {@link sugarcube.jexter.ocd.model.OCDFormField}s. */
    private static void extractFormFields(PDDocument pdf, OCDDocument doc) {
        try {
            var acro = pdf.getDocumentCatalog().getAcroForm();
            if (acro == null) return;
            var pageIdx = new java.util.IdentityHashMap<org.apache.pdfbox.cos.COSDictionary, Integer>();
            int i = 0;
            for (PDPage p : pdf.getPages()) pageIdx.put(p.getCOSObject(), i++);
            for (var f : acro.getFieldTree()) {
                if (!(f instanceof org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField tf)) continue;
                var type = fieldType(tf);
                String name = tf.getFullyQualifiedName();
                String value;
                java.util.List<String> opts = java.util.List.of();
                if (tf instanceof org.apache.pdfbox.pdmodel.interactive.form.PDChoice ch) {
                    try { value = String.join(", ", ch.getValue()); } catch (Exception e) { value = ""; }
                    opts = ch.getOptions();
                } else {
                    try { value = tf.getValueAsString(); } catch (Exception e) { value = ""; }
                }
                boolean multiline = tf instanceof org.apache.pdfbox.pdmodel.interactive.form.PDTextField txt && txt.isMultiline();
                for (var w : tf.getWidgets()) {
                    var wp = w.getPage();
                    Integer idx = wp != null ? pageIdx.get(wp.getCOSObject()) : null;
                    if (idx == null || idx < 0 || idx >= doc.pages().size()) continue;
                    var ff = new sugarcube.jexter.ocd.model.OCDFormField(type)
                            .name(name).value(value)
                            .readOnly(tf.isReadOnly()).required(tf.isRequired()).multiline(multiline);
                    if (opts != null) for (String o : opts) ff.addOption(o);
                    ff.onState(onState(w));      // WHICH button of the group this widget is
                    // A pushbutton has NO value — its label lives in the widget's /MK /CA caption, and
                    // without it the control renders as an empty chip. For a BUTTON that caption IS what
                    // the control displays, which is what value() means to a renderer.
                    if (type == sugarcube.jexter.ocd.model.OCDFormField.Field.BUTTON) ff.value(caption(w));
                    var r = w.getRectangle();
                    if (r != null) ff.rect(new JxRect(r.getLowerLeftX(), r.getLowerLeftY(), r.getWidth(), r.getHeight()));
                    doc.pages().get(idx).addField(ff);
                }
            }
        } catch (Exception ignore) { /* form parsing best-effort */ }
    }

    /** A pushbutton's caption: {@code /MK /CA}, the only place its label exists. */
    private static String caption(org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget w) {
        try {
            var mk = w.getAppearanceCharacteristics();
            return mk == null || mk.getNormalCaption() == null ? "" : mk.getNormalCaption();
        } catch (Exception ignore) { return ""; }
    }

    /** A widget's own export name: the single {@code /AP /N} key that is not {@code Off}. The field's value
     *  says WHAT is selected; only this says whether THIS widget is the one. */
    private static String onState(org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget w) {
        try {
            var ap = w.getAppearance();
            if (ap == null || ap.getNormalAppearance() == null) return "";
            var sub = ap.getNormalAppearance().getSubDictionary();
            if (sub == null) return "";
            for (var k : sub.keySet()) if (!"Off".equals(k.getName())) return k.getName();
        } catch (Exception ignore) { /* appearance parsing best-effort */ }
        return "";
    }

    private static sugarcube.jexter.ocd.model.OCDFormField.Field fieldType(
            org.apache.pdfbox.pdmodel.interactive.form.PDField f) {
        if (f instanceof org.apache.pdfbox.pdmodel.interactive.form.PDTextField)      return sugarcube.jexter.ocd.model.OCDFormField.Field.TEXT;
        if (f instanceof org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox)       return sugarcube.jexter.ocd.model.OCDFormField.Field.CHECKBOX;
        if (f instanceof org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton)    return sugarcube.jexter.ocd.model.OCDFormField.Field.RADIO;
        if (f instanceof org.apache.pdfbox.pdmodel.interactive.form.PDChoice)         return sugarcube.jexter.ocd.model.OCDFormField.Field.CHOICE;
        if (f instanceof org.apache.pdfbox.pdmodel.interactive.form.PDPushButton)     return sugarcube.jexter.ocd.model.OCDFormField.Field.BUTTON;
        if (f instanceof org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField) return sugarcube.jexter.ocd.model.OCDFormField.Field.SIGNATURE;
        return sugarcube.jexter.ocd.model.OCDFormField.Field.OTHER;
    }

    /** Append a node to the innermost open group, or to the page when none is open. */
    @Override protected void addNode(OCDNode n) {
        if (groups.isEmpty()) page.add(n);
        else groups.peek().add(n);
        int mcid = currentMcid();
        if (mcid >= 0) mcidNodes.computeIfAbsent(mcid, k -> new java.util.ArrayList<>()).add(n);  // capture node; id minted later by IdStamper.fill
    }

    /** page-local map: marked-content id → the content nodes tagged with it (ids read after stamping). */
    private final java.util.Map<Integer, java.util.List<OCDNode>> mcidNodes = new java.util.HashMap<>();
    java.util.Map<Integer, java.util.List<OCDNode>> mcidNodes() { return mcidNodes; }

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
        JxRect pb = page.effectiveBox();
        JxRect gb = g.bounds();
        double x0 = Math.max(pb.x(), gb.x()), y0 = Math.max(pb.y(), gb.y());
        double x1 = Math.min(pb.x() + pb.width(), gb.x() + gb.width());
        double y1 = Math.min(pb.y() + pb.height(), gb.y() + gb.height());
        if (x1 - x0 < 0.5 || y1 - y0 < 0.5) return null;
        return new JxRect(x0, y0, x1 - x0, y1 - y0);
    }

    /** Emit a composited image for group {@code g} carrying its blend/alpha (Y-up unit-square map). */
    private void emitBaked(OCDGroup g, JxRect crop, BufferedImage img) throws IOException {
        String ref = doc.newImageRef("png");
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ImageIO.write(img, "png", buf);
        doc.addImage(ref, buf.toByteArray());
        OCDImage n = new OCDImage(ref).pixelSize(img.getWidth(), img.getHeight());
        n.id(g.id()).z(g.z());
        n.transform(JxTransform.of(new AffineTransform(crop.width(), 0, 0, crop.height(), crop.x(), crop.y())));
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
            emitBaked(g, crop, OCDRenderer.renderNodes(g.children(), crop, page, doc, groupRasterDpi));
            return true;
        } catch (Exception e) {
            JxLog.debug(PdfImporter.class, "group raster bake failed \u2192 vector fallback", e);
            return false;   // any failure → fall back to the vector group
        }
    }

    /** Bake a Luminosity-soft-masked group: render the content and the mask isolated over the same
     *  crop, multiply the content's alpha by the mask's luminosity, emit the result as one image. */
    private boolean maskedBake(OCDGroup g, OCDGroup mgrp, PDSoftMask sm) {
        try {
            JxRect crop = bakeCrop(g);
            if (crop == null) return false;
            BufferedImage cimg = OCDRenderer.renderNodes(g.children(), crop, page, doc, groupRasterDpi);
            BufferedImage mimg = OCDRenderer.renderNodes(mgrp.children(), crop, page, doc, groupRasterDpi);
            int bd = backdropLuma(sm);
            int[] cp = ((DataBufferInt) cimg.getRaster().getDataBuffer()).getData();
            int[] mp = ((DataBufferInt) mimg.getRaster().getDataBuffer()).getData();
            int n = Math.min(cp.length, mp.length);
            for (int i = 0; i < n; i++) {
                int m = mp[i];
                // unpainted mask pixel → backdrop luminance; else PDF Luminosity 0.30R+0.59G+0.11B
                int lum = (m >>> 24) == 0 ? bd : (77 * ((m >> 16) & 255) + 150 * ((m >> 8) & 255) + 29 * (m & 255)) >> 8;
                int a = ((cp[i] >>> 24) * lum) / 255;
                cp[i] = (a << 24) | (cp[i] & 0x00FFFFFF);
            }
            emitBaked(g, crop, cimg);
            return true;
        } catch (Exception e) {
            JxLog.debug(PdfImporter.class, "image bake failed \u2192 vector fallback", e);
            return false;
        }
    }

    /** Backdrop luminance (0-255) for a Luminosity mask's /BC array; default black. */
    private static int backdropLuma(PDSoftMask sm) {
        try {
            var bc = sm.getCOSObject().getDictionaryObject(org.apache.pdfbox.cos.COSName.BC);
            if (bc instanceof org.apache.pdfbox.cos.COSArray arr && arr.size() > 0)
                return Math.round(((org.apache.pdfbox.cos.COSNumber) arr.getObject(0)).floatValue() * 255);
        } catch (Exception ignore) { }
        return 0;
    }

    // ── CLI: convert + render a page + save (fidelity testing) ───────────────────
    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("usage: PdfImporter <pdf> [page0] [out.png] [dpi]"); System.exit(2); }
        OCDDocument doc = convert(new File(args[0]));
        int pi = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        String out = args.length > 2 ? args[2] : "ocd.png";
        double dpi = args.length > 3 ? Double.parseDouble(args[3]) : 144;
        ImageIO.write(OCDRenderer.render(doc.page(pi), doc, dpi), "png", new File(out));
        System.out.println("converted " + args[0] + " page " + pi + " -> " + out + "  " + doc);
    }

    /** First 8 hex chars of the source's SHA-256 — the document identity is content-addressed, so a
     *  re-import of the same PDF yields the same id (and downstream, the same EPUB identifiers). */
    private static String contentId(File f) throws IOException {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            try (var in = new java.io.FileInputStream(f)) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            }
            return java.util.HexFormat.of().formatHex(md.digest(), 0, 4);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }   // SHA-256 is mandatory in every JRE
    }
}
