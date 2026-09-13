package sugarcube.jexter.write;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceCharacteristicsDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDNonTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDPushButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.pdmodel.interactive.form.PDVariableText;

import sugarcube.jexter.core.JxColor;
import sugarcube.jexter.core.JxLog;
import sugarcube.jexter.core.JxRect;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDFormField;
import sugarcube.jexter.ocd.model.OCDPage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The model's form fields written back as a real, fillable <b>AcroForm</b> — the return leg of
 * {@link sugarcube.jexter.pdfimport.PdfImporter}'s {@code extractFormFields}.
 *
 * <p>A field is <b>live data</b> in the model: the source's appearance streams are never imported, so
 * they cannot be copied back. This class states the field again from what the model holds and lets
 * PDFBox generate the appearance from the {@code /DA} and the {@code /DR} written here — which is what
 * makes the result fillable in a reader rather than a picture of a form.
 *
 * <p><b>One field per NAME, one widget per {@link OCDFormField}.</b> A field with several widgets (a
 * radio group, a repeated field) is imported as one {@code OCDFormField} per widget, all sharing the
 * name; grouping them back is this class's job, and the widget's own {@link OCDFormField#onState()} is
 * what says which button of a group each one is.
 *
 * <p><b>Page identity is positional.</b> {@link PdfWriter} adds exactly one {@link PDPage} per model
 * page, in order, so model page <i>i</i> is {@code pdf.getPage(i)}. Nothing else links a widget to its
 * page: a widget states {@code /P}, and it must be the page that also holds it in {@code /Annots}.
 *
 * <p>Fonts: one {@code /DR} carrying <b>Helv</b> (the value text) and <b>ZaDb</b> (the check and radio
 * marks), the two Acrobat has used for thirty years. The producer's own {@code /DA} font name is
 * deliberately NOT carried into the model — a resource name means nothing outside the {@code /DR} it
 * was written against — so the model states the SIZE and the COLOUR and the writer states the font.
 */
final class PdfForms {

    private static final String HELV = "Helv";
    private static final String ZADB = "ZaDb";
    // The check and the dot, named by CODEPOINT and not by code: PDFBox encodes a string through the
    // font's own encoding, and ZapfDingbats has no glyph for U+0034. U+2714 encodes to byte 0x34 and
    // U+25CF to 0x6C — the very codes Acrobat has written these two marks with for thirty years — so
    // the output is the conventional one and the writer never hands the encoder a .notdef.
    private static final String CHECK = "\u2714";
    private static final String DOT   = "\u25CF";

    private PdfForms() {}

    /** One field per name, one widget per model field. A no-op on a document that carries none, so a
     *  document without a form is byte-identical to what this writer produced before. */
    static void write(PDDocument pdf, OCDDocument doc) {
        Map<String, List<Placed>> byName = new LinkedHashMap<>();
        int anon = 0;
        int pages = Math.min(doc.pages().size(), pdf.getNumberOfPages());
        for (int i = 0; i < pages; i++) {
            OCDPage p = doc.pages().get(i);
            for (OCDFormField f : p.fields()) {
                if (f.rect() == null) continue;          // a widget with no rect has nowhere to be
                // A nameless terminal field is legal in PDF and unusable in a reader: it cannot be
                // filled, exported or scripted. One is minted rather than dropped, so the count a
                // consumer sees never silently shrinks.
                String name = f.name().isEmpty() ? "Field" + (++anon) : f.name();
                byName.computeIfAbsent(name, k -> new ArrayList<>()).add(new Placed(pdf.getPage(i), f));
            }
        }
        if (byName.isEmpty()) return;

        PDAcroForm acro = new PDAcroForm(pdf);
        PDResources dr = new PDResources();
        dr.put(COSName.getPDFName(HELV), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
        dr.put(COSName.getPDFName(ZADB), new PDType1Font(Standard14Fonts.FontName.ZAPF_DINGBATS));
        acro.setDefaultResources(dr);
        acro.setDefaultAppearance("/" + HELV + " 0 Tf 0 g");
        pdf.getDocumentCatalog().setAcroForm(acro);

        Tree tree = new Tree(acro);
        for (Map.Entry<String, List<Placed>> e : byName.entrySet()) {
            try {
                field(pdf, acro, tree, e.getKey(), e.getValue());
            } catch (IOException | RuntimeException ex) {
                // One malformed field must not cost the document its form.
                JxLog.warn(PdfForms.class, "form field '" + e.getKey() + "' not written: " + ex);
            }
        }
        tree.commit();
        acro.setFields(tree.roots);
    }

    /** A model field and the page its widget sits on. */
    private record Placed(PDPage page, OCDFormField field) {}

    // ── one name → one field ───────────────────────────────────────────────────

    private static void field(PDDocument pdf, PDAcroForm acro, Tree tree, String name, List<Placed> ws)
            throws IOException {
        OCDFormField m = ws.get(0).field();
        PDTerminalField fld = switch (m.type()) {
            case CHECKBOX    -> new PDCheckBox(acro);
            case RADIO       -> new PDRadioButton(acro);
            case CHOICE      -> new PDComboBox(acro);
            case BUTTON      -> new PDPushButton(acro);
            case SIGNATURE   -> new PDSignatureField(acro);
            // OTHER is a field type the model could not classify. A text field is the only shape that
            // keeps its value fillable without inventing a behaviour a button or a box would assert.
            case TEXT, OTHER -> new PDTextField(acro);
        };
        fld.setReadOnly(m.readOnly());
        fld.setRequired(m.required());
        tree.place(name, fld);

        if (fld instanceof PDVariableText vt) {
            vt.setQ(m.align());
            vt.setDefaultAppearance(da(m));
        }
        if (fld instanceof PDTextField t) {
            t.setMultiline(m.multiline());
            if (m.maxLen() > 0) t.setMaxLen(m.maxLen());
        }
        if (fld instanceof PDComboBox cb && !m.options().isEmpty()) {
            cb.setCombo(true);
            cb.setOptions(new ArrayList<>(m.options()));
        }

        boolean radio = m.type() == OCDFormField.Field.RADIO;
        boolean check = m.type() == OCDFormField.Field.CHECKBOX;
        // A field with ONE widget is written as ONE dictionary — the field IS the annotation, which is
        // what the source did and what a reader expects: /T, /FT, /Ff, /DA and /Rect on the object that
        // sits in the page's /Annots. Split into /Kids, the same form is legal and a consumer reading
        // only the page's annotations finds five nameless, typeless boxes. Several widgets have no
        // choice: the field is their parent, and each kid carries its own rect and state.
        boolean merged = ws.size() == 1;
        List<PDAnnotationWidget> widgets = new ArrayList<>(ws.size());
        List<String> states = new ArrayList<>(ws.size());
        for (int i = 0; i < ws.size(); i++) {
            Placed pl = ws.get(i);
            PDAnnotationWidget w = merged ? onField(fld) : new PDAnnotationWidget();
            dress(w, pl.field(), pl.page());
            if (check || radio) {
                // The widget's export name is what says WHICH button this is, and it is read from the
                // source's /AP. A document that carried no appearance at all — this is common, the
                // reader was expected to build one — leaves the model without it, and a name must be
                // minted or the value has nothing to point at. A check box's own VALUE is that name
                // ("Yes"), which keeps the exported form exporting under the name the source used; a
                // radio group's widgets each need their own, and its options ARE those names.
                String on = !pl.field().onState().isEmpty() ? pl.field().onState()
                          : radio ? (m.options().size() > i ? m.options().get(i) : "Choice" + (i + 1))
                          : usable(pl.field().value()) ? pl.field().value() : "On";
                states.add(on);
                marks(pdf, w, pl.field(), on, radio);
            }
            widgets.add(w);
        }
        if (!merged) fld.setWidgets(widgets);
        for (int i = 0; i < ws.size(); i++) ws.get(i).page().getAnnotations().add(widgets.get(i));

        value(fld, m, states);
    }

    /** The value, set LAST: PDFBox builds the appearance from the field's {@code /DA}, the {@code /DR}
     *  and the widget's rectangle, so all three must already be there. A radio group's value is the
     *  export name of the widget that is on — the field's own value cannot say which. */
    private static void value(PDTerminalField fld, OCDFormField m, List<String> states) {
        try {
            if (fld instanceof PDCheckBox cb) {
                if (m.isOn()) cb.check(); else cb.unCheck();
            } else if (fld instanceof PDRadioButton rb) {
                String on = m.isOn() && !m.onState().isEmpty() ? m.onState()
                          : !m.value().isEmpty() && states.contains(m.value()) ? m.value() : "";
                if (on.isEmpty()) rb.setValue("Off"); else rb.setValue(on);
            } else if (fld instanceof PDPushButton) {
                // A push button holds no value: its label is the widget's /MK /CA, written with the widget.
                return;
            } else if (fld instanceof PDSignatureField) {
                return;                                   // an empty place to sign; a signature is not text
            } else {
                fld.setValue(m.value());
                if (!m.defaultValue().isEmpty() && fld instanceof PDTextField t) t.setDefaultValue(m.defaultValue());
            }
        } catch (IOException | RuntimeException ex) {
            JxLog.warn(PdfForms.class, "value of '" + m.name() + "' not set: " + ex);
        }
    }

    /** {@code /DA} — the font the writer states, the size and colour the MODEL states. Size 0 is PDF's
     *  own auto-size and is written as such. */
    private static String da(OCDFormField m) {
        JxColor c = new JxColor(m.textColor() == 0 ? 0xFF000000 : m.textColor());
        return "/" + HELV + " " + trim(m.fontSize()) + " Tf "
                + trim(c.r() / 255.0) + " " + trim(c.g() / 255.0) + " " + trim(c.b() / 255.0) + " rg";
    }

    // ── the widget ─────────────────────────────────────────────────────────────

    /** The field's own dictionary, made into its widget: the merged form PDF calls the common case. */
    private static PDAnnotationWidget onField(PDTerminalField fld) {
        COSDictionary d = fld.getCOSObject();
        d.setItem(COSName.TYPE, COSName.ANNOT);
        d.setItem(COSName.SUBTYPE, COSName.getPDFName(PDAnnotationWidget.SUB_TYPE));
        return new PDAnnotationWidget(d);
    }

    private static void dress(PDAnnotationWidget w, OCDFormField f, PDPage page) {
        JxRect r = f.rect();
        w.setRectangle(new PDRectangle((float) r.x(), (float) r.y(), (float) r.width(), (float) r.height()));
        w.setPage(page);
        w.setPrinted(!f.hidden());
        w.setHidden(f.hidden());

        boolean caption = f.type() == OCDFormField.Field.BUTTON && !f.value().isEmpty();
        if (f.rotation() != 0 || f.borderColor() != 0 || f.backColor() != 0 || caption) {
            PDAppearanceCharacteristicsDictionary mk =
                    new PDAppearanceCharacteristicsDictionary(new COSDictionary());
            if (f.rotation() != 0)    mk.setRotation(f.rotation());
            if (f.borderColor() != 0) mk.setBorderColour(rgb(f.borderColor()));
            if (f.backColor() != 0)   mk.setBackground(rgb(f.backColor()));
            if (caption)              mk.setNormalCaption(f.value());
            w.setAppearanceCharacteristics(mk);
        }
        PDBorderStyleDictionary bs = new PDBorderStyleDictionary();
        bs.setWidth((float) f.borderWidth());
        w.setBorderStyle(bs);
    }

    /** A check box and a radio button are the two controls whose appearance a reader will NOT generate:
     *  their {@code /AP /N} dictionary is what names their states, and a value naming a state that has no
     *  stream is refused. Both states are drawn here — {@code Off} and the widget's own export name. */
    private static void marks(PDDocument pdf, PDAnnotationWidget w, OCDFormField f, String on, boolean radio)
            throws IOException {
        COSDictionary n = new COSDictionary();
        n.setItem(COSName.getPDFName("Off"), state(pdf, w, f, false, radio).getCOSObject());
        n.setItem(COSName.getPDFName(on),    state(pdf, w, f, true,  radio).getCOSObject());
        PDAppearanceDictionary ap = new PDAppearanceDictionary();
        ap.getCOSObject().setItem(COSName.N, n);
        w.setAppearance(ap);
    }

    private static PDAppearanceStream state(PDDocument pdf, PDAnnotationWidget w, OCDFormField f,
                                            boolean on, boolean radio) throws IOException {
        PDRectangle r = w.getRectangle();
        float cw = r.getWidth(), ch = r.getHeight();
        PDAppearanceStream s = new PDAppearanceStream(pdf);
        s.setBBox(new PDRectangle(cw, ch));
        // A new appearance stream has NO /Resources, and PDPageContentStream.setFont writes straight into
        // them: without this line the first mark throws NullPointerException, the field is abandoned
        // half-built (rect and border, no /AP, no value) and the box silently stops being a check box.
        s.setResources(new PDResources());
        try (PDPageContentStream cs = new PDPageContentStream(pdf, s)) {
            if (f.backColor() != 0) {
                JxColor c = new JxColor(f.backColor());
                cs.setNonStrokingColor(c.r() / 255f, c.g() / 255f, c.b() / 255f);
                cs.addRect(0, 0, cw, ch);
                cs.fill();
            }
            float bw = (float) f.borderWidth();
            if (f.borderColor() != 0 && bw > 0) {
                JxColor c = new JxColor(f.borderColor());
                cs.setStrokingColor(c.r() / 255f, c.g() / 255f, c.b() / 255f);
                cs.setLineWidth(bw);
                cs.addRect(bw / 2, bw / 2, cw - bw, ch - bw);
                cs.stroke();
            }
            if (on) {
                float size = Math.min(cw, ch) * 0.8f;
                JxColor ink = new JxColor(f.textColor() == 0 ? 0xFF000000 : f.textColor());
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.ZAPF_DINGBATS), size);
                cs.setNonStrokingColor(ink.r() / 255f, ink.g() / 255f, ink.b() / 255f);
                cs.newLineAtOffset((cw - size * 0.75f) / 2, (ch - size * 0.7f) / 2);
                cs.showText(radio ? DOT : CHECK);
                cs.endText();
            }
        }
        return s;
    }

    // ── the name tree ──────────────────────────────────────────────────────────

    /** A fully-qualified name is a PATH ({@code personne.nom}), and PDF states it as a tree of fields,
     *  not as a string: a reader that walks the tree would otherwise see a field literally called
     *  "personne.nom" and export it under a name nobody asked for. Intermediate nodes are minted once
     *  and their children set in one go, because {@code setChildren} replaces the list. */
    private static final class Tree {
        private final PDAcroForm acro;
        private final Map<String, PDNonTerminalField> nodes = new LinkedHashMap<>();
        private final Map<String, List<PDField>> kids = new LinkedHashMap<>();
        private final List<PDField> roots = new ArrayList<>();

        Tree(PDAcroForm acro) { this.acro = acro; }

        void place(String name, PDField field) {
            String[] seg = name.split("\\.");
            field.setPartialName(seg[seg.length - 1]);
            if (seg.length == 1) { roots.add(field); return; }
            String path = "";
            String parent = null;
            for (int i = 0; i < seg.length - 1; i++) {
                path = path.isEmpty() ? seg[i] : path + "." + seg[i];
                if (!nodes.containsKey(path)) {
                    PDNonTerminalField node = new PDNonTerminalField(acro);
                    node.setPartialName(seg[i]);
                    nodes.put(path, node);
                    if (parent == null) roots.add(node);
                    else kids.computeIfAbsent(parent, k -> new ArrayList<>()).add(node);
                }
                parent = path;
            }
            kids.computeIfAbsent(parent, k -> new ArrayList<>()).add(field);
        }

        void commit() {
            for (Map.Entry<String, List<PDField>> e : kids.entrySet()) {
                PDNonTerminalField node = nodes.get(e.getKey());
                if (node != null) node.setChildren(e.getValue());
            }
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** A value that can name an ON state: anything but empty and PDF's reserved {@code Off}. */
    private static boolean usable(String v) { return !v.isEmpty() && !"Off".equalsIgnoreCase(v); }

    private static PDColor rgb(int argb) {
        JxColor c = new JxColor(argb);
        return new PDColor(new float[]{c.r() / 255f, c.g() / 255f, c.b() / 255f}, PDDeviceRGB.INSTANCE);
    }

    /** A PDF number, short: {@code 9} not {@code 9.0} — a /DA is read by humans as often as by readers. */
    private static String trim(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : sugarcube.jexter.core.JxNum.fmt(v);
    }
}
