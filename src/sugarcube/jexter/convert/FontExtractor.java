package sugarcube.jexter.convert;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDVectorFont;

import sugarcube.jexter.core.JxName;
import sugarcube.jexter.core.JxPath;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDFont;
import sugarcube.jexter.ocd.model.OCDGlyph;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Builds {@link OCDFont}s lazily as glyphs are encountered, pulling each glyph's
 * vector outline from the embedded (or substituted) font program via FontBox.
 *
 * <p>The outline comes from {@link PDVectorFont#getNormalizedPath(int)} in the
 * program's glyph space; the font matrix (which carries the 1/unitsPerEm scale —
 * 1/1000 for CFF/Type1, 1/2048 for a TrueType substitute) maps it to em units
 * (1 em = 1, Y-up, baseline 0). This is the correct fix for the historical
 * "hardcoded 1000" glyph-scale bug. Advances and descriptor metrics are in the
 * PDF 1000-unit text space, so they are divided by 1000.
 *
 * <p>Glyphs are keyed by glyph code (the gid), with a codepoint→gid cmap, so
 * ligatures / variants / unmapped glyphs never collide.
 *
 * <p><b>Font identity.</b> A PDF may embed two <em>different</em> subsets under
 * the <em>same</em> name (producers sometimes reuse the {@code ABCDEF+} subset
 * tag), with different encodings and glyph programs. Keying an {@link OCDFont}
 * by name alone would merge them and let one program's code→glyph map clobber
 * the other (e.g. code 32 = 'B' in one subset, ' ' in the other → spaces render
 * as 'B'). So fonts are identified by their COS object: a new font program gets
 * a unique id (the name, or {@code name_2}, {@code name_3}… on a name clash),
 * while the same program seen again reuses its id.
 */
final class FontExtractor {

    private static final double EM = 1000.0;

    private final OCDDocument doc;
    private final Set<String> done = new HashSet<>();              // "fontId#code"
    private final Map<COSBase, String> ids = new IdentityHashMap<>(); // font COS object → assigned OCDFont id

    FontExtractor(OCDDocument doc) { this.doc = doc; }

    /** Ensure {@code font}'s outline for {@code code} is stored; returns the font id. */
    String capture(PDFont font, int code, String unicode) {
        String name = font.getName();
        if (name == null) return null;

        // Identify by the underlying COS font object, not the (possibly shared) name.
        COSBase cos = font.getCOSObject();
        String id = ids.get(cos);
        if (id == null) {
            OCDFont f = newFont(font, name);        // family / style / weight known here
            id = unique(slug(f));                   // explicit slug, e.g. CambriaMath, ArialMT-Bold, CambriaMath-2
            f.id(id);
            ids.put(cos, id);
            doc.add(f);
        }

        OCDFont f = doc.font(id);
        if (done.add(id + "#" + code) && !f.hasGlyph(code)) {
            String uni = unicode != null ? unicode : "";
            f.add(new OCDGlyph(code, uni, outline(font, code), advance(font, code)));
            if (!uni.isEmpty()) f.map(uni.codePointAt(0), code);
        }
        return id;
    }

    /** Human-readable resource slug from family + style: {@code Family}, {@code Family-Bold},
     *  {@code Family-Italic}, {@code Family-BoldItalic}; filename/ref-safe. */
    private static String slug(OCDFont f) {
        String base = (f.family() != null && !f.family().isBlank()) ? f.family() : "font";
        String lo   = base.toLowerCase(java.util.Locale.US);
        // only append a style token the family name does not already carry (e.g. "Times-Italic"
        // must not become "Times-Italic-Italic", while "Helvetica" + bold → "Helvetica-Bold")
        boolean bold = "bold".equals(f.weight()) && !lo.contains("bold");
        boolean ital = ("italic".equals(f.style()) || "oblique".equals(f.style()))
                       && !(lo.contains("italic") || lo.contains("oblique"));
        String style = bold && ital ? "-BoldItalic" : bold ? "-Bold" : ital ? "-Italic" : "";
        return JxName.safe(base + style);
    }

    /** {@code slug} if free in the document, else {@code slug-2}, {@code slug-3}, … —
     *  distinct font programs that share a family+style still get distinct ids. */
    private String unique(String slug) {
        if (doc.font(slug) == null) return slug;
        for (int i = 2; ; i++) {
            String cand = slug + "-" + i;
            if (doc.font(cand) == null) return cand;
        }
    }

    private static OCDFont newFont(PDFont font, String name) {
        OCDFont f = new OCDFont(name).embedded(font.isEmbedded());
        // family stays the human name (subset prefix stripped), independent of the id
        f.family(name.length() > 7 && name.charAt(6) == '+' ? name.substring(7) : name);

        PDFontDescriptor fd = font.getFontDescriptor();
        if (fd != null) {
            f.ascent(fd.getAscent() / EM).descent(fd.getDescent() / EM)
                    .capHeight(fd.getCapHeight() / EM).xHeight(fd.getXHeight() / EM);
            boolean italic = fd.isItalic() || Math.abs(fd.getItalicAngle()) > 0.01;
            f.style(italic ? "italic" : "normal").weight(fd.getFontWeight() >= 600 ? "bold" : "normal");
            f.serif(fd.isSerif());                       // the producer's own classification (/Flags bit 2)
        }
        // The REAL space width of the face, in em — the Spacer's style threshold hangs on it.
        // The model default (0.25) over-shoots condensed faces (newspaper headline fonts space
        // at ~0.19 em: justified word gaps land near 0.11 em, under 0.5×0.25 → words glue).
        // PDFBox knows the width even for subsets (space stays in /Widths without an outline) —
        // but falls back to average/missing width when it does not (0.5-1.0 em: absurd as a
        // space, total gluing). Implausible values are re-derived in finish() from the face's
        // own glyph advances, once every used glyph is captured.
        try {
            float sw = font.getSpaceWidth();                    // glyph space (1/1000 em)
            if (sw > 0) f.spaceWidth(sw / EM);
        } catch (Exception e) { /* keep the model default */ }
        return f;
    }

    /** Glyph outline as a JxPath in em units, or null (Type3 / bitmap / failure).
     *
     *  <p>A NON-EMBEDDED font's outline comes from a <b>substitute</b> program whose natural advance has no
     *  reason to match the {@code /Widths} the PDF declares — and the advance we store IS the PDF's. PDFBox
     *  reconciles the two at paint time by scaling the glyph horizontally
     *  ({@code PageDrawer.drawGlyph}); the model reconciles them ONCE, here, so a stored glyph is always
     *  consistent with its own advance and every surface (SVG, PDF export, renderer, OCD-EPUB) inherits the
     *  parity instead of re-deriving it. Same guards as the reference rasterizer, so the two agree by
     *  construction. */
    private static JxPath outline(PDFont font, int code) {
        if (!(font instanceof PDVectorFont vf)) return null;
        try {
            GeneralPath gp = vf.getNormalizedPath(code);
            if (gp == null) return null;
            AffineTransform fm = font.getFontMatrix().createAffineTransform();   // glyph space → em
            double sx = widthStretch(font, code);
            if (sx != 1.0) fm.scale(sx, 1);          // appended AFTER the font matrix = glyph space, as PageDrawer
            return new JxPath(fm.createTransformedShape(gp));
        } catch (Exception e) {
            return null;
        }
    }

    /** The horizontal factor that makes a substituted glyph occupy the width the PDF declares, or {@code 1}.
     *  Mirrors {@code PageDrawer.drawGlyph}: only for a non-embedded, non-vertical, non-standard-14 font that
     *  declares an explicit width for this code; zero widths (PDFBOX-5611) and zero-width glyph programs
     *  (spaces) are skipped. */
    private static double widthStretch(PDFont font, int code) {
        try {
            if (font.isEmbedded() || font.isVertical() || font.isStandard14() || !font.hasExplicitWidth(code))
                return 1.0;
            float fontWidth = font.getWidthFromFont(code);       // the substitute program's own advance
            float pdfWidth  = font.getWidth(code);               // what the document says it must be
            if (pdfWidth <= 0 || fontWidth <= 0 || Math.abs(fontWidth - pdfWidth) <= 0.0001) return 1.0;
            return pdfWidth / fontWidth;
        } catch (Exception e) {
            return 1.0;
        }
    }

    private static double advance(PDFont font, int code) {
        try {
            return font.getWidth(code) / EM;   // PDF widths are in 1000-unit text space
        } catch (Exception e) {
            return 0;
        }
    }

    /** Post-capture space-width sanitising — call once, after every page is processed. A face
     *  whose declared space lies outside the plausible text band (0.05-0.45 em: the PDFBox
     *  average/missing-width fallbacks land at 0.5-1.0) gets one derived from its own median
     *  glyph advance instead: 0.5 × median, clamped to [0.12, 0.30] em. The ONE derivation,
     *  per face, from the face — condensed, agate and display all scale with themselves. */
    void finish() {
        for (OCDFont f : doc.fonts().values()) {
            double sw = f.spaceWidth();
            if (sw > 0.05 && sw < 0.45) continue;
            var adv = new java.util.ArrayList<Double>();
            for (OCDGlyph g : f.glyphs().values()) if (g.advance() > 0.05) adv.add(g.advance());
            if (adv.isEmpty()) continue;
            java.util.Collections.sort(adv);
            double med = adv.get(adv.size() / 2);
            f.spaceWidth(Math.max(0.12, Math.min(0.30, 0.5 * med)));
        }
    }
}
