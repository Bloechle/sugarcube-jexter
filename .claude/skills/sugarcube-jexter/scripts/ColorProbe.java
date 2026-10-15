package sugarcube.jexter.pdfimport;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.graphics.color.*;

import java.io.File;

/** Every colour space of a page, and whether PDFBox can turn a tint of it into RGB. */
public final class ColorProbe {
    public static void main(String[] a) throws Exception {
        try (PDDocument pdf = Loader.loadPDF(new File(a[0]))) {
            int p0 = a.length > 1 ? Integer.parseInt(a[1]) - 1 : 0;
            PDResources res = pdf.getPage(p0).getResources();
            for (COSName n : res.getColorSpaceNames()) {
                PDColorSpace cs;
                try { cs = res.getColorSpace(n); }
                catch (Exception e) { System.out.printf("%-10s GET FAILED %s%n", n.getName(), e); continue; }
                int c = cs.getNumberOfComponents();
                float[] full = new float[c];
                java.util.Arrays.fill(full, 1f);
                String out;
                try {
                    float[] rgb = cs.toRGB(full);
                    out = String.format("rgb(%3d,%3d,%3d)", Math.round(rgb[0] * 255), Math.round(rgb[1] * 255), Math.round(rgb[2] * 255));
                } catch (Throwable t) { out = "toRGB THROWS " + t.getClass().getSimpleName() + ": " + t.getMessage(); }
                System.out.printf("%-10s %-46s comps=%d  tint=1 -> %s%n", n.getName(), cs.toString(), c, out);
            }
        }
    }
}
