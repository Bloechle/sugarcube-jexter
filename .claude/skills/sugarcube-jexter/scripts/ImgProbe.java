package sugarcube.jexter.pdfimport;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.File;

/** Every image XObject of a page: filter, colour space, and whether PDFBox can decode it. */
public final class ImgProbe {
    public static void main(String[] a) throws Exception {
        try (PDDocument pdf = Loader.loadPDF(new File(a[0]))) {
            PDPage page = pdf.getPage(Integer.parseInt(a[1]) - 1);
            PDResources res = page.getResources();
            for (COSName n : res.getXObjectNames()) {
                PDXObject xo;
                try { xo = res.getXObject(n); } catch (Exception e) { System.out.println(n + " getXObject: " + e); continue; }
                if (!(xo instanceof PDImageXObject img)) continue;
                String filters = String.valueOf(img.getCOSObject().getItem(COSName.FILTER));
                String cs;
                try { cs = String.valueOf(img.getColorSpace()); } catch (Exception e) { cs = "cs FAILED: " + e.getClass().getSimpleName(); }
                String state;
                try { state = img.getImage() == null ? "NULL" : "decoded " + img.getImage().getWidth() + "x" + img.getImage().getHeight(); }
                catch (Throwable t) { state = "FAILS: " + t.getClass().getSimpleName() + " " + String.valueOf(t.getMessage()).replaceAll("\\s+", " "); }
                System.out.printf("%-8s %-28s %-26s %s%n", n.getName(), filters, cs, state);
            }
        }
    }
}
