import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.write.Conversion;
import sugarcube.jexter.write.SvgOcdWriter;
import sugarcube.jexter.core.JxName;
import java.nio.file.*;

/** What Prism's /api/font answers, for the bench (which serves the site itself). */
public class FontBlock {
    public static void main(String[] a) throws Exception {
        OCDDocument doc = new OCDDocument();
        String chars = a.length > 1 ? a[1] : "";
        // "@path" reads the characters from a FILE, in UTF-8: an argv round trip mangles anything
        // outside ASCII, and a font is then added without the very glyph you asked for.
        if (chars.startsWith("@")) chars = Files.readString(Path.of(chars.substring(1)), java.nio.charset.StandardCharsets.UTF_8);
        Conversion.addFont(doc, Files.readAllBytes(Path.of(a[0])), chars);
        String id = JxName.safe(a.length > 2 ? a[2] : "f0");
        System.out.print(SvgOcdWriter.glyphsSvg(doc, new SvgOcdWriter.Fonts(doc))
                .replace("<g id=\"f0\"", "<g id=\"" + id + "\"").replace("id=\"f0-", "id=\"" + id + "-"));
    }
}
