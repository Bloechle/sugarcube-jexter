package sugarcube.jexter.pdfimport;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import sugarcube.jexter.core.ConvertOptions;

import java.awt.geom.GeneralPath;
import java.io.File;

/** Which fonts lose their outlines, and does the post repair cause it? */
public final class FontProbe {
    public static void main(String[] args) throws Exception {
        File f = new File(args[0]);
        boolean repair = args.length > 1 && args[1].equals("repair");
        try (PDDocument pdf = Loader.loadPDF(f)) {
            if (repair) TrueTypeRepair.repair(pdf, new ConvertOptions());
            PDPage page = pdf.getPage(0);
            PDResources res = page.getResources();
            System.out.printf("%-36s %-22s %7s %7s %7s%n", "font", "class", "codes", "inked", "EMPTY");
            for (COSName n : res.getFontNames()) {
                PDFont font = res.getFont(n);
                if (font == null) continue;
                int codes = 0, inked = 0, empty = 0;
                for (int c = 0; c < 256; c++) {
                    float w;
                    try { w = font.getWidth(c); } catch (Exception e) { continue; }
                    if (w <= 0) continue;
                    codes++;
                    if (!(font instanceof PDVectorFont vf)) continue;
                    try {
                        GeneralPath gp = vf.getNormalizedPath(c);
                        if (gp == null || gp.getCurrentPoint() == null && gp.getPathIterator(null).isDone()) empty++;
                        else inked++;
                    } catch (Exception e) { empty++; }
                }
                String cls = font.getClass().getSimpleName();
                boolean bad = empty > inked;
                System.out.printf("%-36s %-22s %7d %7d %7d %s%n",
                        font.getName(), cls + (font.isEmbedded() ? "" : "/SUBST"), codes, inked, empty,
                        bad ? "  <<< NO INK" : "");
            }
        }
    }
}
