import sugarcube.jexter.pdfimport.TtfImporter;
import sugarcube.jexter.font.Foundry;
import java.nio.file.*;
import java.util.*;

/** The loop the two halves describe but nothing closes: a REAL font in, its glyphs as em outlines,
 *  the Foundry's own request shape, a font back out. */
public class FontLoop {
    public static void main(String[] a) throws Exception {
        byte[] ttf = Files.readAllBytes(Path.of(a[0]));
        int[] cps = "Hello, world!".codePoints().distinct().toArray();
        List<TtfImporter.Glyph> gs = TtfImporter.extract(ttf, cps);
        System.out.println("source font : " + TtfImporter.name(ttf) + " · asked " + cps.length + " codepoints, got " + gs.size() + " outlined");
        StringBuilder js = new StringBuilder("{\"name\":\"Reforged\",\"family\":\"Reforged\",\"format\":\"ttf\",\"glyphs\":[");
        for (int i = 0; i < gs.size(); i++) {
            TtfImporter.Glyph g = gs.get(i);
            if (i > 0) js.append(',');
            js.append("{\"cp\":").append(g.cp()).append(",\"em\":\"").append(g.outline().toSvg())
              .append("\",\"advance\":").append(g.advance()).append('}');
        }
        js.append("]}");
        Foundry.Font out = Foundry.build(js.toString());
        Files.write(Path.of(a[1]), out.bytes());
        System.out.println("rebuilt     : " + out.filename() + " · " + out.bytes().length + " bytes");
    }
}
