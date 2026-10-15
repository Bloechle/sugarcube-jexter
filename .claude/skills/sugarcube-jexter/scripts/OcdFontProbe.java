package sugarcube.jexter.pdfimport;

import sugarcube.jexter.core.ConvertOptions;
import sugarcube.jexter.ocd.model.*;

import java.io.File;

/** After import: which glyphs carry NO outline in the model, and what text they stand for. */
public final class OcdFontProbe {
    public static void main(String[] args) throws Exception {
        OCDDocument doc = PdfImporter.convert(new File(args[0]), new ConvertOptions());
        System.out.printf("%-42s %6s %6s  %s%n", "font", "glyphs", "blank", "characters with no outline");
        for (OCDFont f : doc.fonts().values()) {
            StringBuilder blank = new StringBuilder();
            int n = 0;
            for (OCDGlyph g : f.glyphs().values()) {
                if (g.outline() != null && !g.outline().isEmpty()) continue;
                n++;
                String u = g.unicode();
                blank.append(u == null || u.isBlank() ? "[gid" + g.gid() + (g.name() != null ? " " + g.name() : "") + "]" : "'" + u + "'");
            }
            if (n > 0) System.out.printf("%-42s %6d %6d  %s%n", f.name(), f.glyphCount(), n, blank);
        }
    }
}
