package sugarcube.jexter.pdfimport;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;
import sugarcube.jexter.core.ConvertOptions;
import sugarcube.jexter.ocd.model.*;

import java.awt.geom.Point2D;
import java.io.File;

/** Where the PAGE puts each image (the CTM), and where the MODEL ends up putting it. */
public final class PlaceProbe extends PDFGraphicsStreamEngine {
    private PlaceProbe(PDPage page) { super(page); }

    @Override public void drawImage(PDImage img) {
        Matrix m = getGraphicsState().getCurrentTransformationMatrix();
        String f = "?";
        boolean jpx = false;
        if (img instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject xo) {
            f = String.valueOf(xo.getCOSObject().getFilters());
            jpx = JpxImage.isJpx(xo);
        } else f = "inline/" + img.getClass().getSimpleName();
        System.out.printf("        filters=%s  isJpx=%s%n", f, jpx);
        if (img instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject x2) {
            org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject sm;
            try { sm = x2.getSoftMask(); } catch (Exception e) { sm = null; }
            System.out.printf("        smask=%s  opaque=%s  jpxBase=%s  jpxMask=%s%n",
                sm == null ? "none" : String.valueOf(sm.getCOSObject().getFilters()),
                tryIt(() -> x2.getOpaqueImage() == null ? "null" : "ok"),
                JpxImage.decode(x2) == null ? "null" : "ok",
                sm == null ? "-" : (JpxImage.decode(sm) == null ? "null" : "ok"));
        }
        java.awt.geom.Area clip = getGraphicsState().getCurrentClippingPath();
        String cb = clip == null ? "none" : String.format("[%.0f %.0f %.0f x %.0f]",
                clip.getBounds2D().getX(), clip.getBounds2D().getY(), clip.getBounds2D().getWidth(), clip.getBounds2D().getHeight());
        System.out.printf("  PAGE  %4dx%-4d  ctm=[%8.2f %7.2f %7.2f %8.2f %8.2f %8.2f]  clip=%s%n",
                img.getWidth(), img.getHeight(), m.getScaleX(), m.getShearY(), m.getShearX(),
                m.getScaleY(), m.getTranslateX(), m.getTranslateY(), cb);
    }

    private static String clipOf(OCDPage p, OCDImage im) {
        if (im.clipId() == null) return "NONE";
        OCDClip c = p.clip(im.clipId());
        if (c == null || c.path() == null) return im.clipId() + " (unresolved)";
        var b = c.path().bounds();
        return String.format("%s [%.0f %.0f %.0f x %.0f]", im.clipId(), b.getX(), b.getY(), b.getWidth(), b.getHeight());
    }

    private static String tryIt(java.util.concurrent.Callable<String> c) {
        try { return c.call(); } catch (Throwable t) { return t.getClass().getSimpleName(); }
    }

    public static void main(String[] a) throws Exception {
        int pi = a.length > 1 ? Integer.parseInt(a[1]) - 1 : 0;
        try (PDDocument pdf = Loader.loadPDF(new File(a[0]))) {
            PDPage page = pdf.getPage(pi);
            new PlaceProbe(page).processPage(page);
        }
        OCDDocument doc = PdfImporter.convert(new File(a[0]), new ConvertOptions());
        OCDPage page2 = doc.pages().get(pi);
        page2.images().forEach(im -> {
            var t = im.transform();
            var b = im.bounds();
            System.out.printf("  MODEL %4dx%-4d  tr=[%8.2f %7.2f %7.2f %8.2f %8.2f %8.2f]  clipId=%s%n",
                    im.pixelWidth(), im.pixelHeight(), t.a(), t.c(), t.b(), t.d(), t.tx(), t.ty(),
                    clipOf(page2, im));
        });
    }

    @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) { }
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
    @Override public void shadingFill(org.apache.pdfbox.cos.COSName n) { }
    @Override protected void showGlyph(Matrix trm, PDFont font, int code, Vector d) { }
}
