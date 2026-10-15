import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * The reference rasterizer for the fidelity check — PDFBox is the single source
 * of truth. Single-file source program; run with the pdfbox-app jar on the
 * classpath (no build step needed):
 *
 *   java -cp "$PDFBOX_JAR" RenderPdf.java SOURCE.pdf PAGE OUT.png [DPI]
 *   java -cp "$PDFBOX_JAR" RenderPdf.java SOURCE.pdf count
 *
 * PAGE is 1-based. Output is RGB (opaque white bg, matching OCDRenderer) at DPI (default 144); page rotation and crop
 * box are applied by PDFRenderer.
 */
public class RenderPdf {
    public static void main(String[] a) throws Exception {
        if (a.length < 2) {
            System.err.println("usage: RenderPdf <pdf> <page|count> [out.png] [dpi]");
            System.exit(2);
        }
        File pdf = new File(a[0]);

        if (a[1].equals("count")) {
            try (PDDocument doc = Loader.loadPDF(pdf)) {
                System.out.println(doc.getNumberOfPages());
            }
            return;
        }

        int page = Integer.parseInt(a[1]) - 1;          // CLI is 1-based
        File out = new File(a[2]);
        float dpi = a.length > 3 ? Float.parseFloat(a[3]) : 144f;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(page, dpi, ImageType.RGB);
            ImageIO.write(img, "png", out);
            System.out.println(out + " " + img.getWidth() + "x" + img.getHeight());
        }
    }
}
