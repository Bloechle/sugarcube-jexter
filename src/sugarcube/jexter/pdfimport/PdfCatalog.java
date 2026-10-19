package sugarcube.jexter.pdfimport;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDChoice;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDPushButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.pdmodel.interactive.form.PDVariableText;
import sugarcube.jexter.core.JxColor;
import sugarcube.jexter.core.JxLog;
import sugarcube.jexter.core.JxRect;
import sugarcube.jexter.ocd.model.OCDAnnotation;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDFormField;
import sugarcube.jexter.ocd.model.OCDLayer;
import sugarcube.jexter.ocd.model.OCDLink;
import sugarcube.jexter.ocd.model.OCDMeta;
import sugarcube.jexter.ocd.model.OCDOutline;
import sugarcube.jexter.ocd.model.OCDOutputIntent;
import sugarcube.jexter.ocd.model.OCDPage;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Calendar;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * What a PDF states OUTSIDE its content streams, read into the model: metadata, outline, layers, output
 * intent, each page's frame and annotations, the AcroForm. {@link PdfImporter} walks the content; this
 * reads the catalog around it — and {@link PdfRenderer} frames its pages with the same {@link #page}, so the
 * two never disagree on where a page is.
 */
final class PdfCatalog {

    private PdfCatalog() {}

    /** The document-level facts, read before any page: metadata, outline, layers, output intent. */
    static void read(PDDocument pdf, OCDDocument doc) {
        metadata(pdf, doc.meta());
        outline(pdf, doc);
        layers(pdf, doc);
        doc.outputIntent(outputIntent(pdf));
    }

    /** A page's frame as the source states it: media box, crop box, {@code /Rotate}, CMYK blending space and
     *  the print boxes that are genuinely distinct. */
    static OCDPage page(PDPage pd) {
        OCDPage page = new OCDPage(box(pd.getMediaBox()));
        JxRect crop = box(pd.getCropBox());
        if (crop != null) page.cropBox(crop);
        page.rotation(pd.getRotation());
        page.cmykBlending(blendsInCmyk(pd));
        printBoxes(pd, page);
        return page;
    }

    /** The {@link ReferenceRaster} of page {@code i}, made on first call: what the importer crops the paints it
     *  cannot vectorise from. ~2 px per page unit, capped at 4 MP — the same budget as mesh shadings. Null when
     *  PDFBox cannot render the page. */
    static Supplier<BufferedImage> referenceRaster(PDDocument pdf, int i) {
        return () -> {
            try {
                PDRectangle cb = pdf.getPage(i).getCropBox();
                return ReferenceRaster.render(pdf, i, (float) PdfStreamEngine.rasterScale(cb.getWidth(), cb.getHeight()));
            } catch (Exception e) {
                JxLog.debug(PdfCatalog.class, "reference raster failed", e);
                return null;
            }
        };
    }

    /** Pull the PDF info dictionary + catalog language into the typed metadata. */
    private static void metadata(PDDocument pdf, OCDMeta m) {
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

    /** The FIRST output intent of the catalog, verbatim (see {@link OCDOutputIntent}).
     *  PDF/X allows one; a file carrying several (PDF/X + PDF/A) keeps the first, which is the one
     *  readers apply to device colour. Anything unreadable is simply no intent. */
    static OCDOutputIntent outputIntent(PDDocument pdf) {
        try {
            var list = pdf.getDocumentCatalog().getOutputIntents();
            if (list == null || list.isEmpty()) return null;
            var oi = list.get(0);
            COSDictionary d = oi.getCOSObject();
            byte[] profile = null;
            int n = 0;
            if (d.getDictionaryObject(COSName.DEST_OUTPUT_PROFILE) instanceof COSStream st) {
                try (var in = st.createInputStream()) { profile = in.readAllBytes(); }
                n = st.getInt(COSName.N, 0);
            }
            String sub = d.getNameAsString(COSName.S);
            return new OCDOutputIntent(sub == null ? "GTS_PDFX" : sub,
                    oi.getOutputConditionIdentifier(), oi.getOutputCondition(), oi.getInfo(), oi.getRegistryName(),
                    profile, n);
        } catch (Exception e) {
            JxLog.debug(PdfCatalog.class, "output intent unreadable", e);
            return null;
        }
    }

    private static void splitInto(String s, String regex, Consumer<String> add) {
        if (s == null) return;
        for (String p : s.split(regex)) if (!p.isBlank()) add.accept(p.trim());
    }

    /** The layer registry is document-level presentation metadata, so it is read from the document's own
     *  optional-content configuration — not discovered from the content stream. {@code /OC … BDC} tells us
     *  a layer is USED; only {@code /OCProperties /D} says whether it is ON, in which order, and it is the
     *  single place that knows. Registered here, {@code PdfStreamEngine.beginLayer} finds the entry already present and
     *  keeps its state; an OCG referenced by content but never declared still falls back to a default entry.
     *  Without this pass every layer defaulted to visible and a hidden stratum painted on every surface —
     *  invisible to the model gates (the layer count round-trips either way) and caught only by comparing a
     *  browser render against the PDFBox reference. */
    private static void layers(PDDocument pdf, OCDDocument doc) {
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
            JxLog.debug(PdfCatalog.class, "optional-content properties unreadable", e);
        }
    }

    private static String iso(Calendar c) { return c == null ? "" : c.toInstant().toString(); }

    /** Capture bleed/trim/art only when genuinely distinct from media & crop (PDFBox cascades them otherwise). */
    private static void printBoxes(PDPage pd, OCDPage page) {
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
    private static void outline(PDDocument pdf, OCDDocument doc) {
        var root = pdf.getDocumentCatalog().getDocumentOutline();
        if (root == null) return;
        for (var it : root.children()) doc.addOutline(toOutline(it, pdf));
    }

    private static OCDOutline toOutline(PDOutlineItem it, PDDocument pdf) {
        var o = new OCDOutline(it.getTitle());
        try {
            double[] d = resolve(pdf, it.getDestination(), it.getAction());
            if (d[0] >= 0) o.pageIndex((int) d[0]);
            if (!Double.isNaN(d[1])) o.y(d[1]);
        } catch (IOException e) {
            JxLog.debug(PdfCatalog.class, "unreadable outline destination", e);
        }
        for (var child : it.children()) o.add(toOutline(child, pdf));
        return o;
    }

    /** Resolve a destination — or the GoTo action standing in for one — to {pageIndex (-1 if none), y (NaN if
     *  none)}. Shared by outline and links. A NAMED destination (hyperref, InDesign: {@code /Dest (sec.2)}) is
     *  looked up in the catalog's {@code /Names /Dests} tree or its {@code /Dests} dictionary: without that, a
     *  LaTeX book lost every bookmark's page and every internal link — and the nav is the ground truth
     *  {@code OutlineAligner} aligns headings on. */
    private static double[] resolve(PDDocument pdf, PDDestination dest, PDAction action) {
        double[] r = { -1, Double.NaN };
        try {
            if (dest == null && action instanceof PDActionGoTo go) dest = go.getDestination();
            if (dest instanceof PDNamedDestination named) dest = pdf.getDocumentCatalog().findNamedDestinationPage(named);
            if (dest instanceof PDPageDestination pd) {
                int idx = pd.retrievePageNumber();
                if (idx >= 0) r[0] = idx;
                if (pd instanceof PDPageXYZDestination xyz && xyz.getTop() != -1) r[1] = xyz.getTop();
            }
        } catch (Exception e) {
            JxLog.debug(PdfCatalog.class, "unresolved destination", e);     // a title-only node, a dropped link
        }
        return r;
    }

    /** Pull each page's annotations into the model: links (targets resolved) and the
     *  comment/markup family (highlights, notes, …). Form widgets are handled document-side. */
    static void annotations(PDDocument pdf, PDPage pd, OCDPage page) {
        try {
            for (var an : pd.getAnnotations()) {
                if (an instanceof PDAnnotationLink lk) {
                    PDRectangle r = lk.getRectangle();
                    if (r == null) continue;
                    var link = new OCDLink(box(r));
                    var action = lk.getAction();
                    if (action instanceof PDActionURI u) {
                        link.uri(u.getURI());
                    } else {
                        double[] d = resolve(pdf, lk.getDestination(), action);
                        if (d[0] >= 0) link.pageIndex((int) d[0]);
                        if (!Double.isNaN(d[1])) link.y(d[1]);
                    }
                    if (link.isExternal() || link.hasDestination()) page.addLink(link);
                } else if (!(an instanceof PDAnnotationWidget)) {
                    var a = markup(an);                      // widgets → handled via the AcroForm tree
                    if (a != null) page.addAnnotation(a);
                }
            }
        } catch (Exception ignore) { /* annotation parsing best-effort */ }
    }

    /** Build a markup/comment annotation from a non-link, non-widget PDF annotation, or null to skip. */
    private static OCDAnnotation markup(
            PDAnnotation an) {
        String sub = an.getSubtype();
        if (sub == null || "Popup".equals(sub)) return null;     // popups are the window of another markup
        var type = switch (sub) {
            case "Highlight" -> OCDAnnotation.Markup.HIGHLIGHT;
            case "Underline" -> OCDAnnotation.Markup.UNDERLINE;
            case "StrikeOut" -> OCDAnnotation.Markup.STRIKEOUT;
            case "Squiggly"  -> OCDAnnotation.Markup.SQUIGGLY;
            case "Text"      -> OCDAnnotation.Markup.NOTE;
            case "FreeText"  -> OCDAnnotation.Markup.FREETEXT;
            case "Stamp"     -> OCDAnnotation.Markup.STAMP;
            case "Ink"       -> OCDAnnotation.Markup.INK;
            case "Line"      -> OCDAnnotation.Markup.LINE;
            case "Square", "Circle", "Polygon", "PolyLine" -> OCDAnnotation.Markup.SHAPE;
            case "Redact"    -> OCDAnnotation.Markup.REDACT;   // marked, maybe never applied
            default          -> OCDAnnotation.Markup.OTHER;
        };
        var a = new OCDAnnotation(type);
        PDRectangle r = an.getRectangle();
        if (r != null) a.rect(box(r));
        a.contents(an.getContents()).modified(an.getModifiedDate());
        a.author(an.getCOSObject().getString(COSName.T));
        try {
            var col = an.getColor();
            if (col != null) a.color(new JxColor(0xFF000000 | (col.toRGB() & 0xFFFFFF)));
        } catch (Exception ignore) {}
        if (an instanceof PDAnnotationTextMarkup tm) {
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

    /** Document-level: flatten the AcroForm into per-page {@link OCDFormField}s. */
    static void formFields(PDDocument pdf, OCDDocument doc) {
        try {
            var acro = pdf.getDocumentCatalog().getAcroForm();
            if (acro == null) return;
            var pageIdx = new IdentityHashMap<COSDictionary, Integer>();
            int i = 0;
            for (PDPage p : pdf.getPages()) pageIdx.put(p.getCOSObject(), i++);
            for (var f : acro.getFieldTree()) {
                if (!(f instanceof PDTerminalField tf)) continue;
                var type = fieldType(tf);
                String name = tf.getFullyQualifiedName();
                String value;
                List<String> opts = List.of();
                if (tf instanceof PDChoice ch) {
                    try { value = String.join(", ", ch.getValue()); } catch (Exception e) { value = ""; }
                    opts = ch.getOptions();
                } else {
                    try { value = tf.getValueAsString(); } catch (Exception e) { value = ""; }
                }
                boolean multiline = tf instanceof PDTextField txt && txt.isMultiline();
                for (var w : tf.getWidgets()) {
                    var wp = w.getPage();
                    Integer idx = wp != null ? pageIdx.get(wp.getCOSObject()) : null;
                    if (idx == null || idx < 0 || idx >= doc.pages().size()) continue;
                    var ff = new OCDFormField(type)
                            .name(name).value(value)
                            .readOnly(tf.isReadOnly()).required(tf.isRequired()).multiline(multiline);
                    if (opts != null) for (String o : opts) ff.addOption(o);
                    ff.onState(onState(w));      // WHICH button of the group this widget is
                    look(w, ff);                 // what the WIDGET is: rotation, colours, border, flags
                    if (tf instanceof PDVariableText vt) {
                        ff.align(vt.getQ());
                        da(vt.getDefaultAppearance(), ff);
                    }
                    if (tf instanceof PDTextField t2) ff.maxLen(t2.getMaxLen());
                    // A pushbutton has NO value — its label lives in the widget's /MK /CA caption, and
                    // without it the control renders as an empty chip. For a BUTTON that caption IS what
                    // the control displays, which is what value() means to a renderer.
                    if (type == OCDFormField.Field.BUTTON) ff.value(caption(w));
                    var r = w.getRectangle();
                    if (r != null) ff.rect(box(r));
                    doc.pages().get(idx).addField(ff);
                }
            }
        } catch (Exception ignore) { /* form parsing best-effort */ }
    }

    /** What the WIDGET is, as the source states it: its own rotation ({@code /MK /R} — a form on a turned
     *  page carries it on every widget), its colours, its border width and its hidden flag. Best-effort:
     *  a widget that states none of it keeps the model's defaults. */
    private static void look(PDAnnotationWidget w,
                             OCDFormField ff) {
        try {
            ff.hidden(w.isHidden());
            var bs = w.getBorderStyle();
            if (bs != null) ff.borderWidth(bs.getWidth());
            var mk = w.getAppearanceCharacteristics();
            if (mk == null) return;
            ff.rotation(mk.getRotation());
            ff.borderColor(argbOf(mk.getBorderColour()));
            ff.backColor(argbOf(mk.getBackground()));
        } catch (Exception ignore) { /* widget look is best-effort */ }
    }

    /** A {@link PDColor} as the model's argb; 0 when absent —
     *  the model's word for NOT STATED. */
    private static int argbOf(PDColor c) {
        if (c == null) return 0;
        try { return JxColor.ofRgb(c.getColorSpace().toRGB(c.getComponents())).argb(); }
        catch (Exception e) { return 0; }
    }

    /** {@code /DA} — {@code /Helv 9 Tf 0 g}. Only the SIZE and the COLOUR are model facts: the font
     *  resource name belongs to the producer's {@code /DR}, and a writer states its own. */
    private static void da(String da, OCDFormField ff) {
        if (da == null || da.isEmpty()) return;
        String[] t = da.trim().split("\\s+");
        for (int i = 0; i < t.length; i++) {
            switch (t[i]) {
                case "Tf" -> { if (i >= 1) ff.fontSize(dbl(t[i - 1])); }
                case "g"  -> { if (i >= 1) { int v = JxColor.unit(dbl(t[i - 1])); ff.textColor(JxColor.rgb(v, v, v).argb()); } }
                case "rg" -> { if (i >= 3) ff.textColor(JxColor.rgb(JxColor.unit(dbl(t[i - 3])), JxColor.unit(dbl(t[i - 2])), JxColor.unit(dbl(t[i - 1]))).argb()); }
                default   -> { }
            }
        }
    }
    private static double dbl(String v) { try { return Double.parseDouble(v); } catch (Exception e) { return 0; } }

    /** A pushbutton's caption: {@code /MK /CA}, the only place its label exists. */
    private static String caption(PDAnnotationWidget w) {
        try {
            var mk = w.getAppearanceCharacteristics();
            return mk == null || mk.getNormalCaption() == null ? "" : mk.getNormalCaption();
        } catch (Exception ignore) { return ""; }
    }

    /** A widget's own export name: the single {@code /AP /N} key that is not {@code Off}. The field's value
     *  says WHAT is selected; only this says whether THIS widget is the one. */
    private static String onState(PDAnnotationWidget w) {
        try {
            var ap = w.getAppearance();
            if (ap == null || ap.getNormalAppearance() == null) return "";
            var sub = ap.getNormalAppearance().getSubDictionary();
            if (sub == null) return "";
            for (var k : sub.keySet()) if (!"Off".equals(k.getName())) return k.getName();
        } catch (Exception ignore) { /* appearance parsing best-effort */ }
        return "";
    }

    private static OCDFormField.Field fieldType(
            PDField f) {
        if (f instanceof PDTextField)      return OCDFormField.Field.TEXT;
        if (f instanceof PDCheckBox)       return OCDFormField.Field.CHECKBOX;
        if (f instanceof PDRadioButton)    return OCDFormField.Field.RADIO;
        if (f instanceof PDChoice)         return OCDFormField.Field.CHOICE;
        if (f instanceof PDPushButton)     return OCDFormField.Field.BUTTON;
        if (f instanceof PDSignatureField) return OCDFormField.Field.SIGNATURE;
        return OCDFormField.Field.OTHER;
    }


    /** The page's transparency group names a four-component blending space (see
     *  {@link OCDPage#cmykBlending()}). Anything unreadable counts as no statement. */
    private static boolean blendsInCmyk(PDPage pd) {
        try {
            COSDictionary g = pd.getCOSObject().getCOSDictionary(COSName.GROUP);
            if (g == null) return false;
            COSBase cs = g.getDictionaryObject(COSName.CS);
            if (cs == null) return false;
            return PDColorSpace.create(cs, pd.getResources())
                    .getNumberOfComponents() == 4;
        } catch (Exception e) {
            JxLog.debug(PdfCatalog.class, "page group colour space unreadable", e);
            return false;
        }
    }
}
