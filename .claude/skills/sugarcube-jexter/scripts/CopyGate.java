import sugarcube.jexter.core.ConvertOptions;
import sugarcube.jexter.ocd.io.OCDReader;
import sugarcube.jexter.ocd.model.*;
import sugarcube.jexter.write.SvgOcdWriter;
import java.io.File;

/** The copy gate: a page and its copy must PROJECT identically, and must be independent. */
public class CopyGate {
    public static void main(String[] a) throws Exception {
        int pages = 0, same = 0;
        for (String f : a) {
            OCDDocument d = OCDReader.read(new File(f));
            var glyphs = new SvgOcdWriter.Fonts(d);
            for (int i = 0; i < d.pageCount(); i++) {
                OCDPage p = d.page(i), c = p.copy("copy");
                String x = SvgOcdWriter.render(d, p, glyphs), y = SvgOcdWriter.render(d, c, glyphs);
                pages++;
                if (x.equals(y)) same++;
                else System.out.println("  DIVERGES " + f + " page " + (i + 1) + "\n    a=" + head(x) + "\n    b=" + head(y));
            }
            // independence: edit the copy, the original must not move
            OCDPage p0 = d.page(0), c0 = p0.copy("c");
            String before = SvgOcdWriter.render(d, p0, glyphs);
            c0.content().clear();
            if (!c0.links().isEmpty()) c0.links().clear();
            String after = SvgOcdWriter.render(d, p0, glyphs);
            System.out.println((before.equals(after) ? "  independent ✓  " : "  ALIASED ✗  ") + f);
        }
        System.out.println("projection-identical: " + same + " / " + pages + " pages");
    }
    static String head(String s) { int n = Math.min(160, s.length()); return s.substring(0, n).replace("\n", " "); }
}
