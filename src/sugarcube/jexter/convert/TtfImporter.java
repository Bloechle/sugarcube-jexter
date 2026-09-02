package sugarcube.jexter.convert;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.GlyphData;
import org.apache.fontbox.ttf.GlyphTable;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;

import sugarcube.jexter.core.JxPath;
import sugarcube.jexter.core.JxStringer;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads an existing TrueType/OpenType font and hands back per-glyph vector outlines in OCD em units
 * (Y-up, baseline 0), so a font can be loaded and edited rather than re-traced from scratch.
 *
 * <p>This is the PDFBox/FontBox-coupled side of the font foundry: FontBox parses the {@code glyf}
 * outlines and metrics, and we normalize them to the em square ({@code 1 em = 1.0}) used everywhere
 * in the model. The pure compilation side ({@code font.JxFont}/{@code GlyfOtf}) stays FontBox-free.
 *
 * <pre>
 *   TtfImporter.json(ttfBytes, codepoints)
 *     → { "name":…, "unitsPerEm":…, "glyphs":[ { "cp":…, "em":"&lt;svg path&gt;", "advance":… } … ] }
 * </pre>
 *
 * Only outlined glyphs are returned; spaces (no contours) carry no entry. Glyphs are emitted in the
 * order of the requested codepoints.
 */
public final class TtfImporter {

    private TtfImporter() {}

    /** One extracted glyph: codepoint, em-unit outline as an SVG path, em advance width. */
    public record Glyph(int cp, String emSvg, double advance) {}

    /** Extract the requested codepoints (those the font actually maps and outlines). */
    public static List<Glyph> extract(byte[] ttf, int[] codepoints) throws IOException {
        try (TrueTypeFont font = new TTFParser().parse(new RandomAccessReadBuffer(ttf))) {
            return extract(font, codepoints);
        }
    }

    private static List<Glyph> extract(TrueTypeFont font, int[] codepoints) throws IOException {
        List<Glyph> out = new ArrayList<>();
        int upem = font.getUnitsPerEm();
        if (upem <= 0) upem = 1000;
        double s = 1.0 / upem;
        GlyphTable glyf = font.getGlyph();
        CmapLookup cmap = font.getUnicodeCmapLookup();
        if (cmap == null) return out;                                   // symbol font / no unicode cmap
        AffineTransform toEm = AffineTransform.getScaleInstance(s, s);   // font units → em (Y already up)
        for (int cp : codepoints) {
            int gid = cmap.getGlyphId(cp);
            if (gid <= 0) continue;
            GlyphData gd;
            try { gd = glyf.getGlyph(gid); } catch (Exception e) { continue; }
            if (gd == null || gd.getNumberOfContours() == 0) continue;   // space / empty
            GeneralPath gp = gd.getPath();                               // font units, Y-up, baseline 0
            if (gp == null) continue;
            JxPath em = new JxPath(toEm.createTransformedShape(gp));      // non-zero winding, holes already correct
            if (em.isEmpty() || em.bounds().isEmpty()) continue;
            out.add(new Glyph(cp, em.toSvg(), font.getAdvanceWidth(gid) * s));
        }
        return out;
    }

    /** A friendly font name (family, else PostScript name, else "Imported"). */
    public static String name(byte[] ttf) {
        try (TrueTypeFont font = new TTFParser().parse(new RandomAccessReadBuffer(ttf))) {
            return nameOf(font);
        } catch (Exception e) { return "Imported"; }
    }

    private static String nameOf(TrueTypeFont font) throws IOException {
        if (font.getNaming() == null) return "Imported";
        String n = font.getNaming().getFontFamily();
        if (n == null || n.isBlank()) n = font.getNaming().getPostScriptName();
        return n == null || n.isBlank() ? "Imported" : n;
    }

    /** The import response the front-end consumes: name + the extracted glyph outlines. Parses once. */
    public static String json(byte[] ttf, int[] codepoints) throws IOException {
        try (TrueTypeFont font = new TTFParser().parse(new RandomAccessReadBuffer(ttf))) {
            JxStringer j = new JxStringer().obj().str("name", nameOf(font)).arr("glyphs");
            for (Glyph g : extract(font, codepoints))
                j.obj().num("cp", g.cp()).str("em", g.emSvg()).num("advance", g.advance()).end();
            return j.end().end().toString();
        }
    }
}
