package sugarcube.jexter.pdfimport;

import sugarcube.jexter.core.*;
import sugarcube.jexter.ocd.model.*;

import java.io.File;

/** Every text run of the model that falls inside a page-space rectangle, as the model holds it. */
public final class AreaProbe {
    public static void main(String[] a) throws Exception {
        OCDDocument doc = PdfImporter.convert(new File(a[0]), new ConvertOptions());
        OCDPage page = doc.pages().get(Integer.parseInt(a[1]) - 1);
        double x = Double.parseDouble(a[2]), y = Double.parseDouble(a[3]), w = Double.parseDouble(a[4]), h = Double.parseDouble(a[5]);
        JxRect box = new JxRect(x, y, w, h);
        page.texts().forEach(t -> {
            JxRect b = t.bounds();
            if (b == null || !b.intersects(box)) return;
            OCDFont f = doc.font(t.fontId());
            StringBuilder s = new StringBuilder();
            for (OCDText.Glyph g : t.glyphs()) {
                String u = g.unicode();
                boolean has = f != null && f.hasGlyph(g.gid());
                boolean ink = has && f.glyph(g.gid()).outline() != null && !f.glyph(g.gid()).outline().isEmpty();
                s.append(u == null || u.isEmpty() ? "?" : u).append(has ? (ink ? "" : "\u2718") : "\u26A0");
            }
            System.out.printf("%-34s %5.1f  mode=%d fill=%08X  [%6.1f %6.1f]  %s%n",
                    f == null ? "(no font)" : f.name(), t.fontSize(), t.renderMode(), t.fill(), b.x(), b.y(), s);
        });
    }
}
