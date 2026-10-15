package sugarcube.jexter.pdfimport;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import java.awt.geom.*;
import java.io.File;

/** What the PAGE actually draws in a band: every glyph, its font, its code, and whether
 *  PDFBox hands back an outline for it — the ground truth the importer sees. */
public final class DrawProbe extends PDFGraphicsStreamEngine {
    private final double x0, x1, y0, y1;

    private DrawProbe(PDPage page, double x, double y, double w, double h) {
        super(page);
        this.x0 = x; this.x1 = x + w; this.y0 = y; this.y1 = y + h;
    }

    @Override protected void showGlyph(Matrix trm, PDFont font, int code, Vector d) throws java.io.IOException {
        float x = trm.getTranslateX(), y = trm.getTranslateY();
        if (x >= x0 && x <= x1 && y >= y0 && y <= y1) {
            String ink = "n/a";
            if (font instanceof PDVectorFont vf) {
                try {
                    GeneralPath gp = vf.getNormalizedPath(code);
                    ink = gp == null ? "NULL" : (gp.getPathIterator(null).isDone() ? "EMPTY" : "ok");
                } catch (Exception e) { ink = "THROWS " + e.getClass().getSimpleName(); }
            }
            System.out.printf("x=%7.2f  %-32s code=%3d uni=%-3s size=%5.2f mode=%s  outline=%s  name=%s%n",
                    x, font.getName() + (font.isEmbedded() ? "" : "/SUBST"), code, safe(font, code),
                    trm.getScalingFactorY(), getGraphicsState().getTextState().getRenderingMode(), ink,
                    font.getName() == null ? "NULL!" : "ok");
        }
        super.showGlyph(trm, font, code, d);
    }

    private static String safe(PDFont f, int c) { try { String u = f.toUnicode(c); return u == null ? "?" : u; } catch (Exception e) { return "?"; } }

    @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) { }
    @Override public void drawImage(PDImage pdImage) { }
    @Override public void clip(int windingRule) { }
    @Override public void moveTo(float x, float y) { }
    @Override public void lineTo(float x, float y) { }
    @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) { }
    @Override public Point2D getCurrentPoint() { return new Point2D.Float(); }
    @Override public void closePath() { }
    @Override public void endPath() { }
    @Override public void strokePath() { }
    @Override public void fillPath(int windingRule) { }
    @Override public void fillAndStrokePath(int windingRule) { }
    @Override public void shadingFill(org.apache.pdfbox.cos.COSName shadingName) { }

    public static void main(String[] a) throws Exception {
        try (PDDocument pdf = Loader.loadPDF(new File(a[0]))) {
            PDPage page = pdf.getPage(Integer.parseInt(a[1]) - 1);
            new DrawProbe(page, Double.parseDouble(a[2]), Double.parseDouble(a[3]),
                    Double.parseDouble(a[4]), Double.parseDouble(a[5])).processPage(page);
        }
    }
}
