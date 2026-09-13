package sugarcube.jexter.write;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationHighlight;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquare;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquiggly;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationStrikeout;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationUnderline;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;

import sugarcube.jexter.core.JxColor;
import sugarcube.jexter.core.JxLog;
import sugarcube.jexter.core.JxRect;
import sugarcube.jexter.ocd.model.OCDAnnotation;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDLink;
import sugarcube.jexter.ocd.model.OCDPage;

import java.io.IOException;
import java.util.List;

/**
 * The page's <b>annotation layer</b> — links and review markup — written back into the exported PDF.
 *
 * <p>An annotation is not content: in PDF it lives in the page's {@code /Annots} array, never in the
 * content stream, and the model keeps it the same way ({@link OCDPage#links()},
 * {@link OCDPage#annotations()}). Which means a projection that only walks the content nodes writes a
 * document where every link is dead and every note is gone — silently, because the page still looks
 * right. This class is the return leg, exactly as {@link PdfForms} is for form fields.
 *
 * <p><b>Positional page identity</b>, as everywhere on this side: {@link PdfWriter} adds one
 * {@link PDPage} per model page, in order, so model page <i>i</i> is {@code pdf.getPage(i)} — and an
 * internal link's destination is resolved through the same rule, so a link to page 3 is a link to the
 * third page written, not to a name that no longer exists.
 *
 * <p><b>No appearance is invented.</b> A link gets no border (Acrobat's own default for a modern
 * link), and a markup annotation carries its colour, its quads and its author: readers draw those
 * themselves, and a hand-drawn approximation would be a worse lie than none. What the model never
 * carried — ink paths, stamp artwork — cannot be written and is not faked.
 */
final class PdfAnnots {

    private PdfAnnots() {}

    /** A no-op on a document whose pages carry neither, so a plain document is byte-identical to what
     *  this writer produced before. */
    static void write(PDDocument pdf, OCDDocument doc) {
        int pages = Math.min(doc.pages().size(), pdf.getNumberOfPages());
        for (int i = 0; i < pages; i++) {
            OCDPage p = doc.pages().get(i);
            if (p.links().isEmpty() && p.annotations().isEmpty()) continue;
            PDPage page = pdf.getPage(i);
            for (OCDLink l : p.links()) {
                try { link(pdf, page, l); }
                catch (IOException | RuntimeException e) { JxLog.warn(PdfAnnots.class, "link not written: " + e); }
            }
            for (OCDAnnotation a : p.annotations()) {
                try { markup(page, a); }
                catch (IOException | RuntimeException e) { JxLog.warn(PdfAnnots.class, "annotation not written: " + e); }
            }
        }
    }

    // ── links ──────────────────────────────────────────────────────────────────

    private static void link(PDDocument pdf, PDPage page, OCDLink l) throws IOException {
        if (l.rect() == null) return;
        PDAnnotationLink a = new PDAnnotationLink();
        a.setRectangle(box(l.rect()));
        a.setPage(page);
        // A visible border on a link is a 1990s default that no producer writes any more, and PDFBox's
        // own default is a 1pt box. State zero rather than inherit it.
        PDBorderStyleDictionary bs = new PDBorderStyleDictionary();
        bs.setWidth(0);
        a.setBorderStyle(bs);

        if (l.isExternal()) {
            PDActionURI uri = new PDActionURI();
            uri.setURI(l.uri());
            a.setAction(uri);
        } else if (l.hasDestination() && l.pageIndex() < pdf.getNumberOfPages()) {
            PDPageXYZDestination d = new PDPageXYZDestination();
            d.setPage(pdf.getPage(l.pageIndex()));
            // Left and zoom are left unset (PDF's "retain current"): the model states a y anchor and
            // nothing else, and inventing a left edge or a zoom would move the reader's view on a jump
            // it never asked to reframe.
            if (l.hasY()) d.setTop((int) Math.round(l.y()));
            PDActionGoTo go = new PDActionGoTo();
            go.setDestination(d);
            a.setAction(go);
        } else {
            return;                                   // a link to nothing is not a link
        }
        page.getAnnotations().add(a);
    }

    // ── review markup ──────────────────────────────────────────────────────────

    private static void markup(PDPage page, OCDAnnotation m) throws IOException {
        if (m.rect() == null) return;
        // PDFBox 3 states each markup kind as its own class — the shared base is abstract and its
        // subtype constants are gone — so the model's kind maps to a type, not to a string.
        PDAnnotation a = switch (m.type()) {
            case HIGHLIGHT -> quads(new PDAnnotationHighlight(), m);
            case UNDERLINE -> quads(new PDAnnotationUnderline(), m);
            case STRIKEOUT -> quads(new PDAnnotationStrikeout(), m);
            case SQUIGGLY  -> quads(new PDAnnotationSquiggly(),  m);
            case NOTE, FREETEXT -> new PDAnnotationText();
            case LINE, SHAPE    -> new PDAnnotationSquare();
            // INK, STAMP, REDACT and OTHER: the model holds the semantics but not the artwork an
            // appearance would need. A sticky note at the same place says the same thing honestly —
            // its kind, its author, its text — without drawing something the source never drew.
            default -> new PDAnnotationText();
        };
        a.setRectangle(box(m.rect()));
        a.setPage(page);
        if (!m.contents().isEmpty()) a.setContents(m.contents());
        if (!m.modified().isEmpty()) a.setModifiedDate(m.modified());
        if (m.color() != null) a.setColor(rgb(m.color()));
        if (!m.author().isEmpty()) a.getCOSObject().setString(COSName.T, m.author());
        page.getAnnotations().add(a);
    }

    /** Text markup is the family whose geometry is the MARKED TEXT, not the rectangle: {@code /QuadPoints}
     *  is what a reader highlights, and a highlight without them paints nothing at all. The model keeps
     *  one quad per marked run; the rectangle is only their envelope. */
    private static PDAnnotationTextMarkup quads(PDAnnotationTextMarkup a, OCDAnnotation m) {
        List<JxRect> qs = m.quads().isEmpty() ? List.of(m.rect()) : m.quads();
        float[] pts = new float[qs.size() * 8];
        int k = 0;
        for (JxRect r : qs) {
            float x0 = (float) r.x(), y0 = (float) r.y();
            float x1 = (float) (r.x() + r.width()), y1 = (float) (r.y() + r.height());
            // PDF's quad order is the one Acrobat writes and every reader assumes: upper-left,
            // upper-right, lower-left, lower-right. The specification's own wording says otherwise and
            // is famously wrong; following it paints nothing.
            pts[k++] = x0; pts[k++] = y1;
            pts[k++] = x1; pts[k++] = y1;
            pts[k++] = x0; pts[k++] = y0;
            pts[k++] = x1; pts[k++] = y0;
        }
        a.setQuadPoints(pts);
        return a;
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static PDRectangle box(JxRect r) {
        return new PDRectangle((float) r.x(), (float) r.y(), (float) r.width(), (float) r.height());
    }

    private static PDColor rgb(JxColor c) {
        return new PDColor(new float[]{ c.r() / 255f, c.g() / 255f, c.b() / 255f }, PDDeviceRGB.INSTANCE);
    }
}
