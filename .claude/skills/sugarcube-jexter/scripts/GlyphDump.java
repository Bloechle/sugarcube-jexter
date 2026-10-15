package sugarcube.jexter.pdfimport;

import sugarcube.jexter.core.ConvertOptions;
import sugarcube.jexter.ocd.model.*;

import java.io.File;

/** Every glyph of one font in the model: gid, unicode, advance, outline bounds. */
public final class GlyphDump {
    public static void main(String[] a) throws Exception {
        OCDDocument doc = PdfImporter.convert(new File(a[0]), new ConvertOptions());
        for (OCDFont f : doc.fonts().values()) {
            if (!f.name().contains(a[1])) continue;
            System.out.println("== " + f.name() + "  id=" + f.id() + "  glyphs=" + f.glyphCount());
            f.glyphs().values().stream().sorted(java.util.Comparator.comparingInt(OCDGlyph::gid)).forEach(g ->
                    System.out.printf("   gid=%3d uni=%-4s adv=%.3f  outline=%s%n",
                            g.gid(), g.unicode() == null ? "?" : g.unicode().replaceAll("\\p{C}", "."),
                            g.advance(),
                            g.outline() == null ? "NULL" : (g.outline().isEmpty() ? "EMPTY" : g.bounds().toString())));
        }
    }
}
