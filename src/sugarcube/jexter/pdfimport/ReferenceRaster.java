package sugarcube.jexter.pdfimport;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * PDFBox's rendering of ONLY the paints the model cannot vectorise ({@link PdfStreamEngine#beyondVectors}):
 * soft-masked fills, strokes, glyphs and images, tiling patterns — on a transparent page, without annotations,
 * in the page's own (unrotated) frame. The importer crops what it cannot vectorise from it.
 *
 * <p>It used to be the whole page rendered on white. A crop of it carried everything painted there: the
 * backdrop (an opaque square where only a masked shadow was), the text above it (painted again by the model,
 * twice the ink and a bitmap of words a redaction must chase), the annotations (painted again by every viewer),
 * and on a page turned by {@code /Rotate} 90 or 270 the region came from the wrong place. Now the crop holds
 * the offending paint and nothing else, its transparency included; the model composites it in its slot.
 *
 * <p>Inside a transparency group or a soft mask everything is drawn: the group composites as a whole, and a
 * mask is made of what its group paints.
 */
final class ReferenceRaster extends PDFRenderer {

    private ReferenceRaster(PDDocument pdf) {
        super(pdf);
        setAnnotationsFilter(a -> false);
    }

    /** Page {@code i} at {@code scale} pixels per page unit, ARGB, unrotated; its frame is the crop box. */
    static BufferedImage render(PDDocument pdf, int i, float scale) throws IOException {
        BufferedImage img = new ReferenceRaster(pdf).renderImage(i, scale, ImageType.ARGB);
        return unrotated(img, pdf.getPage(i).getRotation());
    }

    /** {@link PDFRenderer} turns the page by {@code /Rotate}; the model's page space is the unturned one. */
    private static BufferedImage unrotated(BufferedImage img, int rotation) {
        int r = ((rotation % 360) + 360) % 360;
        if (r == 0) return img;
        int w = img.getWidth(), h = img.getHeight();
        boolean swap = r == 90 || r == 270;
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_ARGB);
        AffineTransform back = switch (r) {
            case 90  -> new AffineTransform(0, -1, 1, 0, 0, w);       // turned clockwise: turn it back
            case 270 -> new AffineTransform(0, 1, -1, 0, h, 0);
            default  -> new AffineTransform(-1, 0, 0, -1, w, h);
        };
        Graphics2D g = out.createGraphics();
        try { g.drawImage(img, back, null); } finally { g.dispose(); }
        return out;
    }

    @Override protected PageDrawer createPageDrawer(PageDrawerParameters p) throws IOException {
        return new OnlyBeyondVectors(p);
    }

    /** Draws a paint only when the model cannot ({@link PdfStreamEngine#beyondVectors}), or inside a group or
     *  a mask. A skipped path still ends — its pending {@code W} clip must apply to what follows. */
    private static final class OnlyBeyondVectors extends PageDrawer {
        private int depth;                                      // inside a transparency group or a soft mask

        OnlyBeyondVectors(PageDrawerParameters p) throws IOException { super(p); }

        private boolean draws(boolean stroking) { return depth > 0 || PdfStreamEngine.beyondVectors(getGraphicsState(), stroking); }

        @Override public void fillPath(int rule) throws IOException { if (draws(false)) super.fillPath(rule); else endPath(); }
        @Override public void strokePath() throws IOException      { if (draws(true)) super.strokePath(); else endPath(); }
        @Override public void fillAndStrokePath(int rule) throws IOException {
            if (draws(false) || draws(true)) super.fillAndStrokePath(rule); else endPath();
        }
        @Override public void shadingFill(COSName name) throws IOException { if (draws(false)) super.shadingFill(name); }
        @Override public void drawImage(PDImage image) throws IOException {
            if (depth > 0 || getGraphicsState().getSoftMask() != null) super.drawImage(image);
        }
        @Override protected void showFontGlyph(Matrix m, PDFont font, int code, Vector d) throws IOException {
            // a clipping mode must still add its glyph to the text clip
            if (depth > 0 || getGraphicsState().getSoftMask() != null || getGraphicsState().getTextState().getRenderingMode().isClip())
                super.showFontGlyph(m, font, code, d);
        }
        @Override protected void processTransparencyGroup(PDTransparencyGroup group) throws IOException {
            depth++;
            try { super.processTransparencyGroup(group); } finally { depth--; }
        }
        @Override protected void processSoftMask(PDTransparencyGroup group) throws IOException {
            depth++;
            try { super.processSoftMask(group); } finally { depth--; }
        }
    }
}
