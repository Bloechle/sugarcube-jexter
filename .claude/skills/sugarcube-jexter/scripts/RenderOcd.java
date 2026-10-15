import sugarcube.jexter.core.ConvertOptions;
import sugarcube.jexter.ocd.io.OCDReader;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.render.OCDRenderer;
import sugarcube.jexter.pdfimport.PdfImporter;

import javax.imageio.ImageIO;
import java.io.File;

/** Raster the MODEL — the OCD side of the fidelity harness, and what compare.py calls for an
 *  `.ocd.epub` candidate.
 *
 *  The input decides the reader: an `.ocd.epub` is read back with {@link OCDReader} (the CONTAINER
 *  surface — what a consumer of the file will get); anything else is imported with {@link PdfImporter}
 *  (the OCD-virtual surface — the model straight from the PDF, before any container). This script
 *  once imported every input as a PDF, so an `.ocd.epub` candidate died in PDFBox's parser with
 *  "Missing root object specification in trailer" and the container path of compare.py could not run
 *  at all (found 2026-09-21).
 *
 *  usage: RenderOcd <in.ocd.epub | in.pdf> <page 1-based> <out.png> <dpi> */
public final class RenderOcd {
    public static void main(String[] a) throws Exception {
        if (a.length < 4) { System.err.println("usage: RenderOcd <in.ocd.epub|in.pdf> <page1> <out.png> <dpi>"); System.exit(2); }
        File in = new File(a[0]);
        OCDDocument doc = in.getName().toLowerCase().endsWith(".ocd.epub")
                ? OCDReader.read(in)
                : PdfImporter.convert(in, new ConvertOptions());
        int page = Integer.parseInt(a[1]) - 1;
        ImageIO.write(OCDRenderer.render(doc.pages().get(page), doc, Double.parseDouble(a[3])), "png", new File(a[2]));
        System.out.println("ocd render -> " + a[2]);
    }
}
