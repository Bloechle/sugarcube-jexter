// Fixtures — the corpus the gates run on, written by PDFBox: one PDF per feature, each isolating ONE
// trait, plus `rich` (the tracks beside the ink) and `tagged` (a real structure tree). Nothing outside
// the JDK and lib/: the Latin font is the Liberation Sans PDFBox carries, and the scripts it lacks
// (CJK, Arabic) get a box font compiled by the engine's own GlyfOtf — what those features test is the
// encoding and the Unicode mapping, not the drawing of a glyph.
//
// Every page starts with the marker "MATRIX::<id>", so a gate or a person can tell the files apart.

import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDNumberTreeNode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType2;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.*;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.apache.pdfbox.pdmodel.graphics.color.*;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.*;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.*;
import org.apache.pdfbox.pdmodel.graphics.shading.*;
import org.apache.pdfbox.pdmodel.graphics.state.*;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.*;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.*;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.*;
import org.apache.pdfbox.util.Matrix;
import sugarcube.jexter.core.JxPath;
import sugarcube.jexter.font.JxFont;
import sugarcube.jexter.ocd.model.OCDFont;
import sugarcube.jexter.ocd.model.OCDGlyph;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

final class Fixtures {

    private Fixtures() {}

    private static final float MM = 72f / 25.4f;
    private static final PDRectangle A4 = PDRectangle.A4;
    private static final float H = A4.getHeight();

    private interface Page { void draw(Doc d, PDPageContentStream cs) throws IOException; }
    private interface Whole { void build(Doc d) throws IOException; }

    /** id → a one-page A4 feature (drawn under its header), or a whole document. */
    private static final Map<String, Object> FEATURES = new LinkedHashMap<>();
    static {
        FEATURES.put("text_base14", (Page) Fixtures::base14);
        FEATURES.put("text_embedded_ttf", (Page) Fixtures::embeddedTtf);
        FEATURES.put("text_cjk_cid", (Page) Fixtures::cjk);
        FEATURES.put("text_unicode", (Page) Fixtures::unicode);
        FEATURES.put("text_rtl", (Page) Fixtures::rtl);
        FEATURES.put("text_rotated", (Page) Fixtures::rotated);
        FEATURES.put("text_render_modes", (Page) Fixtures::renderModes);
        FEATURES.put("text_clip_image", (Page) Fixtures::clipImage);
        FEATURES.put("text_tiny_leading", (Page) Fixtures::tinyLeading);
        FEATURES.put("text_ligature", (Page) Fixtures::ligature);
        FEATURES.put("text_press", (Page) Fixtures::press);
        FEATURES.put("vector_shapes", (Page) Fixtures::shapes);
        FEATURES.put("vector_clipping", (Page) Fixtures::clipping);
        FEATURES.put("vector_transforms", (Page) Fixtures::transforms);
        FEATURES.put("vector_gradient", (Page) Fixtures::gradient);
        FEATURES.put("vector_dash_caps", (Page) Fixtures::dashCaps);
        FEATURES.put("vector_svg_like", (Page) Fixtures::svgLike);
        FEATURES.put("vector_soft_mask", (Whole) Fixtures::softMask);
        FEATURES.put("vector_tiling", (Page) Fixtures::tiling);
        FEATURES.put("color_rgb", (Page) Fixtures::rgb);
        FEATURES.put("color_cmyk", (Page) Fixtures::cmyk);
        FEATURES.put("color_spot", (Page) Fixtures::spot);
        FEATURES.put("color_alpha", (Page) Fixtures::alpha);
        FEATURES.put("image_jpeg_rgb", (Page) Fixtures::jpegRgb);
        FEATURES.put("image_png_alpha", (Page) Fixtures::pngAlpha);
        FEATURES.put("image_cmyk_jpeg", (Page) Fixtures::jpegCmyk);
        FEATURES.put("image_1bit", (Page) Fixtures::bilevel);
        FEATURES.put("page_mixed_sizes", (Whole) Fixtures::mixedSizes);
        FEATURES.put("page_rotate", (Whole) Fixtures::rotate);
        FEATURES.put("page_mediabox_cropbox", (Whole) Fixtures::cropBox);
        FEATURES.put("doc_outline_links", (Whole) Fixtures::outlineLinks);
        FEATURES.put("doc_metadata", (Whole) Fixtures::metadata);
        FEATURES.put("doc_running_heads", (Whole) Fixtures::runningHeads);
        FEATURES.put("doc_page_break", (Whole) Fixtures::pageBreak);
        FEATURES.put("doc_magazine", (Whole) Fixtures::magazine);
        FEATURES.put("doc_columns_page_break", (Whole) Fixtures::columnsPageBreak);
        FEATURES.put("doc_three_columns", (Whole) Fixtures::threeColumns);
        FEATURES.put("doc_grid_background", (Whole) Fixtures::gridBackground);
        FEATURES.put("doc_wallpaper", (Whole) Fixtures::wallpaper);
        FEATURES.put("doc_sections", (Whole) Fixtures::sections);
        FEATURES.put("doc_chapters", (Whole) Fixtures::chapters);
        FEATURES.put("doc_chapters_outline", (Whole) Fixtures::chaptersOutline);
        FEATURES.put("rich", (Whole) Fixtures::rich);
        FEATURES.put("tagged", (Whole) Fixtures::tagged);
        FEATURES.put("tagged_empty", (Whole) Fixtures::taggedEmpty);
        FEATURES.put("tagged_partial", (Whole) Fixtures::taggedPartial);
        FEATURES.put("tagged_figure", (Whole) Fixtures::taggedFigure);
    }

    /** Writes every fixture into {@code dir}; returns their paths in a stable order. */
    static List<Path> write(Path dir) throws IOException {
        Files.createDirectories(dir);
        List<Path> out = new ArrayList<>();
        for (var e : FEATURES.entrySet()) {
            Path f = dir.resolve(e.getKey() + ".pdf");
            try (Doc d = new Doc()) {
                d.pdf.getDocumentInformation().setTitle("Fixture " + e.getKey());
                d.pdf.getDocumentInformation().setCreator("test/Fixtures.java");
                if (e.getValue() instanceof Page p) {
                    try (var cs = d.page(A4)) { d.header(cs, e.getKey()); p.draw(d, cs); }
                } else ((Whole) e.getValue()).build(d);
                d.pdf.save(f.toFile());
            }
            out.add(f);
        }
        return out;
    }

    // ── a document and its fonts ─────────────────────────────────────────────────

    private static final class Doc implements Closeable {
        final PDDocument pdf = new PDDocument();
        private PDType0Font sans;

        PDPageContentStream page(PDRectangle size) throws IOException {
            PDPage p = new PDPage(size);
            pdf.addPage(p);
            return new PDPageContentStream(pdf, p);
        }

        /** Liberation Sans, from inside PDFBox: embedded as a subset Type0 / CIDFontType2. */
        PDType0Font sans() throws IOException {
            if (sans == null) sans = PDType0Font.load(pdf, liberation());
            return sans;
        }

        void header(PDPageContentStream cs, String id) throws IOException {
            text(cs, sans(), 9, 20 * MM, H - 18 * MM, "MATRIX::" + id);
        }

        @Override public void close() throws IOException { pdf.close(); }
    }

    private static InputStream liberation() {
        return PDFont.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf");
    }

    private static void text(PDPageContentStream cs, PDFont f, float size, float x, float y, String s) throws IOException {
        cs.beginText();
        cs.setFont(f, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }

    /** What a fixture's text must read, line by line, word for word and in this order (the WORDS gate). */
    static final Map<String, List<String>> WORDS = new LinkedHashMap<>();

    /** Words a fixture paints where no engine shows them: the WORDS gate fails when any is read. */
    static final Map<String, List<String>> ABSENT = new LinkedHashMap<>();

    /** Fixtures whose tag tree is no structure of the text (it marks nothing, or too little): read as untagged. */
    static final Set<String> UNTAGGED = Set.of("tagged_empty", "tagged_partial");

    /**
     * Press setting, as a newspaper producer writes it: no space glyph anywhere, every word placed by a TJ
     * offset. A justified column whose word spaces vary from tight to loose, a results table whose names hold
     * a word space beside wide column gaps, tracked capitals, a headline under Tz, and two columns set on
     * baselines offset by half a line, running on under a photo set across their gutter, and a title set tight
     * over a two-column list, and three line-final hyphens — each one a way the words were lost (glued, read row
     * by row across the columns, or a compound's hyphen dropped).
     */
    private static void press(Doc d, PDPageContentStream cs) throws IOException {
        PDFont times = new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);
        PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        List<String> words = new ArrayList<>();
        float x = 20 * MM, y = H - 45 * MM;
        // offsets in thousandths of an em, between consecutive words
        Object[][] body = {
            { "Le conseil communal a voté", new int[]{ 90, 300, 110, 280 } },
            { "un crédit de deux millions", new int[]{ 95, 95, 320, 100 } },
            { "pour la rénovation du collège", new int[]{ 230, 230, 230, 230 } },
            { "une ligne serrée comme souvent", new int[]{ 85, 90, 88, 92 } } };
        for (Object[] l : body) { words.add(tj(cs, times, 9, x, y, (String) l[0], (int[]) l[1], 0, 100)); y -= 11.5f; }
        Object[][] table = {
            { "Young Boys 12 8 2 2 26", new int[]{ 250, 2400, 1800, 1800, 1800, 1800 } },
            { "FC Lausanne-Sport 12 6 3 3 21", new int[]{ 250, 1000, 1800, 1800, 1800, 1800 } } };
        for (Object[] l : table) { words.add(tj(cs, times, 7, x, y, (String) l[0], (int[]) l[1], 0, 100)); y -= 9; }
        y -= 12;
        words.add(tj(cs, bold, 14, x, y, "LE GRAND ENTRETIEN", new int[]{ 350, 350 }, 1.6f, 100));
        y -= 34;
        words.add(tj(cs, bold, 30, x, y, "Une rentrée sous tension", new int[]{ 200, 200, 200 }, 0, 93));
        // two columns as a newspaper sets them — narrow, a 4 mm gutter, the right one half a line lower: a
        // union band chained them into one line, and a gutter measured against the column's width (15 % of
        // it) was never cut, so the page read across its columns
        String[] left = { "La commune investit dans", "ses écoles cette année", "un montant record pour", "la ville et la région" };
        String[] right = { "Les travaux commencent", "au printemps prochain", "sous réserve du budget", "voté en décembre" };
        y -= 30;
        float col = 40 * MM, gutter = 4 * MM;
        for (int i = 0; i < left.length; i++) words.add(justified(cs, times, 9, x, y - i * 11, left[i], col));
        for (int i = 0; i < right.length; i++) words.add(justified(cs, times, 9, x + col + gutter, y - 5.5f - i * 11, right[i], col));
        // a photo set across the gutter, both columns running on under it: a shape that cut the order filled
        // the gutter and the region was sorted by y, row by row across the columns
        cs.setNonStrokingColor(new Color(200, 200, 200));
        cs.addRect(x + col - 10 * MM, y - 62.5f, gutter + 20 * MM, 20);
        cs.fill();
        cs.setNonStrokingColor(Color.BLACK);
        String[] left2 = { "et pour longtemps encore", "avec le soutien du canton", "comme chaque année" },
                 right2 = { "selon le calendrier prévu", "par le conseil communal", "lors de sa séance" };
        int at = words.size() - right.length;
        for (int i = 0; i < left2.length; i++) words.add(at + i, justified(cs, times, 9, x, y - 71.5f - i * 11, left2[i], col));
        for (int i = 0; i < right2.length; i++) words.add(justified(cs, times, 9, x + col + gutter, y - 77 - i * 11, right2[i], col));
        // a title over a two-column list with no gap under it, the rows on shared baselines: the title covers
        // the gutter and no horizontal valley parts it from the list, so no projection cut exists — the
        // region was read row by row across both columns
        y -= 130;
        words.add(tj(cs, bold, 11, x, y, "Les rendez-vous de la semaine dans la région", null, 0, 100));
        String[] l2 = { "Lundi", "Marché couvert", "Mardi", "Concert au parc" };
        String[] r2 = { "Jeudi", "Fête du village", "Samedi", "Course populaire" };
        for (int i = 0; i < l2.length; i++) words.add(tj(cs, i % 2 == 0 ? bold : times, 8, x, row(y, i), l2[i], null, 0, 100));
        for (int i = 0; i < r2.length; i++) words.add(tj(cs, i % 2 == 0 ? bold : times, 8, x + col + gutter, row(y, i), r2[i], null, 0, 100));
        // a line-final hyphen: a compound the page also writes whole keeps it, a syllable break whose joined
        // word the page writes rejoins (before a capital too), and with no evidence a lowercase continuation
        // rejoins (the common case) and a capital one keeps it — never a space either way
        y -= 70;
        String[] hy = { "les allers-retours entre", "les deux villes et les allers-", "retours du soir avec la pré-",
                        "vention routière et la prévention", "des chantiers de collabo-", "ration avec le canton",
                        "et le moteur TraceMonkey du Trace-", "Monkey du soir à Saint-", "Gall ce jeudi" };
        for (int i = 0; i < hy.length; i++) tj(cs, times, 9, x, y - i * 11, hy[i], null, 0, 100);
        words.add("les allers-retours entre les deux villes et les allers-retours du soir avec la prévention"
                + " routière et la prévention des chantiers de collaboration avec le canton"
                + " et le moteur TraceMonkey du TraceMonkey du soir à Saint-Gall ce jeudi");
        WORDS.put("text_press", words);
    }

    /** The baseline of row {@code i} of a list of entries — a day and its event — set {@code 8 pt} apart, the
     *  entries {@code 5 pt} further: each entry a paragraph of its own. */
    private static float row(float top, int i) { return top - 11 - i * 8 - i / 2 * 5; }

    /** One justified line: its word gaps share whatever {@code width} leaves after the words. */
    private static String justified(PDPageContentStream cs, PDFont f, float size, float x, float y, String line,
                                    float width) throws IOException {
        String[] w = line.split(" ");
        float ink = 0;
        for (String s : w) ink += f.getStringWidth(s) / 1000 * size;
        int gap = Math.round((width - ink) / (w.length - 1) / size * 1000);
        int[] gaps = new int[w.length - 1];
        java.util.Arrays.fill(gaps, gap);
        return tj(cs, f, size, x, y, line, gaps, 0, 100);
    }

    /** One line in one TJ: the words of {@code line}, each pair parted by {@code gaps[i]} thousandths of an em
     *  (0.25 em when null) and no space glyph; {@code tc} character spacing, {@code tz} horizontal scaling. */
    private static String tj(PDPageContentStream cs, PDFont f, float size, float x, float y, String line,
                             int[] gaps, float tc, float tz) throws IOException {
        String[] w = line.split(" ");
        List<Object> ops = new ArrayList<>();
        for (int i = 0; i < w.length; i++) {
            ops.add(w[i]);
            if (i + 1 < w.length) ops.add((float) -(gaps == null ? 250 : gaps[i]));
        }
        cs.beginText();
        cs.setFont(f, size);
        cs.setCharacterSpacing(tc);
        cs.setHorizontalScaling(tz);
        cs.newLineAtOffset(x, y);
        cs.showTextWithPositioning(ops.toArray());
        cs.endText();
        return line;
    }

    /** A font for scripts Liberation lacks: one box glyph per character, compiled by the engine. */
    private static PDType0Font boxes(PDDocument pdf, String chars, double advance) throws IOException {
        return PDType0Font.load(pdf, new ByteArrayInputStream(JxFont.toOtf(boxFont(chars, advance))));
    }

    private static OCDFont boxFont(String chars, double advance) {
        OCDFont f = new OCDFont("FixtureBoxes").ascent(0.8).descent(0.2);
        f.add(new OCDGlyph(0, null, new JxPath(), advance));
        int gid = 0;
        for (int cp : chars.codePoints().distinct().toArray()) {
            if (Character.isWhitespace(cp)) continue;
            double w = advance * 0.8, inset = advance * 0.1 + (gid % 3) * 0.02;
            JxPath box = new JxPath().move(inset, 0).line(inset + w - 0.1, 0).line(inset + w - 0.1, 0.7 - (gid % 4) * 0.05).line(inset, 0.7).close();
            f.add(new OCDGlyph(++gid, new String(Character.toChars(cp)), box, advance));
            f.map(cp, gid);
        }
        f.add(new OCDGlyph(++gid, " ", new JxPath(), advance / 2));
        f.map(' ', gid);
        return f;
    }

    // ── text ─────────────────────────────────────────────────────────────────────

    private static void base14(Doc d, PDPageContentStream cs) throws IOException {
        float y = H - 45 * MM;
        for (var name : List.of(Standard14Fonts.FontName.HELVETICA, Standard14Fonts.FontName.HELVETICA_BOLD,
                Standard14Fonts.FontName.TIMES_ROMAN, Standard14Fonts.FontName.TIMES_ITALIC, Standard14Fonts.FontName.COURIER)) {
            text(cs, new PDType1Font(name), 13, 20 * MM, y, name.getName() + ": The quick brown fox 0123456789");
            y -= 9 * MM;
        }
    }

    private static void embeddedTtf(Doc d, PDPageContentStream cs) throws IOException {
        text(cs, d.sans(), 16, 20 * MM, H - 50 * MM, "Liberation Sans, Type0 / CIDFontType2 subset.");
        PDFont simple = PDTrueTypeFont.load(d.pdf, liberation(), WinAnsiEncoding.INSTANCE);
        text(cs, simple, 16, 20 * MM, H - 62 * MM, "Liberation Sans, simple TrueType with WinAnsi.");
    }

    /**
     * A ligature as pdfTeX writes one: ONE glyph whose ToUnicode is TWO letters ("ff"). The word "of" comes first,
     * so the font's single "f" is known before the ligature arrives — the ligature must not take its place.
     */
    private static void ligature(Doc d, PDPageContentStream cs) throws IOException {
        String chars = "ofice\uE000";                         // the ligature box sits at a private-use code point
        PDType0Font font = PDType0Font.load(d.pdf, new ByteArrayInputStream(JxFont.toOtf(boxFont(chars, 0.6))), false);
        StringBuilder bf = new StringBuilder();
        int[] cps = chars.codePoints().toArray();
        for (int cp : cps) {
            byte[] code = font.encode(new String(Character.toChars(cp)));     // Identity-H: two bytes
            String uni = cp == 0xE000 ? "ff" : new String(Character.toChars(cp));
            StringBuilder hex = new StringBuilder();
            for (char c : uni.toCharArray()) hex.append(String.format("%04X", (int) c));
            bf.append(String.format("<%02X%02X> <%s>%n", code[0] & 255, code[1] & 255, hex));
        }
        String cmap = "/CIDInit /ProcSet findresource begin 12 dict begin begincmap\n"
                + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
                + "/CMapName /Adobe-Identity-UCS def /CMapType 2 def\n"
                + "1 begincodespacerange <0000> <FFFF> endcodespacerange\n"
                + cps.length + " beginbfchar\n" + bf + "endbfchar\n"
                + "endcmap CMapName currentdict /CMap defineresource pop end end\n";
        COSStream toUnicode = d.pdf.getDocument().createCOSStream();
        try (var out = toUnicode.createOutputStream()) { out.write(cmap.getBytes(java.nio.charset.StandardCharsets.US_ASCII)); }
        font.getCOSObject().setItem(COSName.TO_UNICODE, toUnicode);
        text(cs, font, 24, 20 * MM, H - 50 * MM, "of");
        text(cs, font, 24, 20 * MM, H - 62 * MM, "o\uE000ice");      // reads "office"
    }

    private static void cjk(Doc d, PDPageContentStream cs) throws IOException {
        String s = "日本語のテキスト 漢字 ひらがな";
        text(cs, boxes(d.pdf, s, 1.0), 24, 20 * MM, H - 55 * MM, s);
        text(cs, d.sans(), 11, 20 * MM, H - 70 * MM, "(a glyf font compiled by the engine -> Type0 / CIDFontType2)");
    }

    private static void unicode(Doc d, PDPageContentStream cs) throws IOException {
        float y = H - 50 * MM;
        for (String r : List.of("Accents: àâ ä éè êë îï ôö ùû ç — Æœ ß", "Greek: αβγδ ΔΣΩ — μ ≈ π·r²",
                "Cyrillic: Привет мир — Здравствуйте", "Symbols: € £ ¥ © ® ™ † ‡ … — “ ” ‘ ’")) {
            text(cs, d.sans(), 15, 20 * MM, y, r);
            y -= 11 * MM;
        }
    }

    private static void rtl(Doc d, PDPageContentStream cs) throws IOException {
        text(cs, d.sans(), 16, 20 * MM, H - 52 * MM, "Hebrew: שלום עולם");
        String arabic = "السلام عليكم";                      // logical order, no shaping: the codepoints as stored
        text(cs, d.sans(), 16, 20 * MM, H - 66 * MM, "Arabic codepoints:");
        text(cs, boxes(d.pdf, arabic, 0.6), 16, 75 * MM, H - 66 * MM, arabic);
    }

    private static void rotated(Doc d, PDPageContentStream cs) throws IOException {
        for (int deg : new int[] { 0, 45, 90, 180, 270 }) {
            cs.saveGraphicsState();
            cs.transform(Matrix.getTranslateInstance(A4.getWidth() / 2, H / 2 - 10 * MM));
            cs.transform(Matrix.getRotateInstance(Math.toRadians(deg), 0, 0));
            text(cs, d.sans(), 14, 0, 0, "  rotated " + deg + "°");
            cs.restoreGraphicsState();
        }
    }

    private static void renderModes(Doc d, PDPageContentStream cs) throws IOException {
        float y = H - 50 * MM;
        cs.setStrokingColor(Color.BLACK);
        cs.setLineWidth(0.5f);
        Object[][] modes = { { RenderingMode.FILL, "Mode 0 fill", new Color(25, 50, 180) },
                             { RenderingMode.STROKE, "Mode 1 stroke", new Color(180, 25, 25) },
                             { RenderingMode.FILL_STROKE, "Mode 2 fill+stroke", new Color(25, 128, 50) },
                             { RenderingMode.NEITHER, "INVISIBLE_OCR_LAYER_TOKEN", Color.BLACK } };
        for (Object[] m : modes) {
            cs.setNonStrokingColor((Color) m[2]);
            cs.beginText();
            cs.setFont(d.sans(), 26);
            cs.setRenderingMode((RenderingMode) m[0]);
            cs.newLineAtOffset(20 * MM, y);
            cs.showText((String) m[1]);
            cs.endText();
            y -= 16 * MM;
        }
        // the clip modes paint as their low two bits say, THEN clip: each in its own q/Q, a stripe through it
        Object[][] clips = { { RenderingMode.FILL_CLIP, "Mode 4" }, { RenderingMode.STROKE_CLIP, "Mode 5" },
                             { RenderingMode.FILL_STROKE_CLIP, "Mode 6" } };
        float x = 20 * MM;
        for (Object[] m : clips) {
            cs.saveGraphicsState();
            cs.setNonStrokingColor(new Color(25, 50, 180));
            cs.beginText();
            cs.setFont(d.sans(), 26);
            cs.setRenderingMode((RenderingMode) m[0]);
            cs.newLineAtOffset(x, y);
            cs.showText((String) m[1]);
            cs.endText();
            cs.setNonStrokingColor(new Color(230, 128, 25));
            cs.addRect(x, y + 4, 60 * MM, 4);
            cs.fill();
            cs.restoreGraphicsState();
            x += 55 * MM;
        }
    }

    /** Big text as a CLIP (Tr 7 paints nothing), then a picture painted THROUGH the glyph outlines. */
    private static void clipImage(Doc d, PDPageContentStream cs) throws IOException {
        cs.saveGraphicsState();
        cs.beginText();
        cs.setFont(d.sans(), 64);
        cs.setRenderingMode(RenderingMode.NEITHER_CLIP);
        cs.newLineAtOffset(20 * MM, H - 80 * MM);
        cs.showText("CLIP");
        cs.endText();
        cs.drawImage(JPEGFactory.createFromImage(d.pdf, gradient(256, 160), 0.88f), 18 * MM, H - 92 * MM, 130 * MM, 28 * MM);
        cs.restoreGraphicsState();
    }

    private static void tinyLeading(Doc d, PDPageContentStream cs) throws IOException {
        cs.beginText();
        cs.setFont(d.sans(), 4.5f);
        cs.setLeading(4.0f);                                  // leading < size: baselines overlap
        cs.newLineAtOffset(20 * MM, H - 45 * MM);
        for (int i = 0; i < 40; i++) {
            cs.showText(String.format("line %02d tiny-text-overlap-%02d 0123456789 abcdefghij", i, i));
            cs.newLine();
        }
        cs.endText();
    }

    // ── vector ───────────────────────────────────────────────────────────────────

    private static void shapes(Doc d, PDPageContentStream cs) throws IOException {
        cs.setStrokingColor(Color.BLACK);
        cs.setNonStrokingColor(new Color(230, 217, 51));
        cs.addRect(20 * MM, H - 80 * MM, 40 * MM, 25 * MM);
        cs.fillAndStroke();
        cs.setNonStrokingColor(new Color(51, 153, 230));
        ellipse(cs, 110 * MM, H - 68 * MM, 14 * MM, 14 * MM);
        cs.fillAndStroke();
        cs.setNonStrokingColor(new Color(204, 77, 77));
        ellipse(cs, 157.5f * MM, H - 70 * MM, 17.5f * MM, 10 * MM);
        cs.fill();
        cs.setLineWidth(2);
        cs.moveTo(20 * MM, H - 110 * MM);
        cs.curveTo(50 * MM, H - 90 * MM, 90 * MM, H - 130 * MM, 120 * MM, H - 110 * MM);
        cs.stroke();
    }

    private static void clipping(Doc d, PDPageContentStream cs) throws IOException {
        cs.saveGraphicsState();
        ellipse(cs, 80 * MM, H - 80 * MM, 30 * MM, 30 * MM);
        cs.clip();
        cs.setNonStrokingColor(new Color(51, 128, 230));
        for (int i = 0; i < 200; i += 6) cs.addRect(20 * MM, H - 120 * MM + i, 120 * MM, 3);
        cs.fill();
        cs.restoreGraphicsState();
    }

    private static void transforms(Doc d, PDPageContentStream cs) throws IOException {
        cs.saveGraphicsState();
        cs.transform(Matrix.getTranslateInstance(60 * MM, H - 80 * MM));
        cs.transform(Matrix.getRotateInstance(Math.toRadians(20), 0, 0));
        cs.transform(Matrix.getScaleInstance(1.4f, 0.8f));
        cs.setNonStrokingColor(new Color(102, 51, 179));
        cs.addRect(0, 0, 30 * MM, 18 * MM);
        cs.fill();
        cs.restoreGraphicsState();
        cs.saveGraphicsState();
        cs.transform(new Matrix(1, 0, 0.4f, 1, 110 * MM, H - 90 * MM));     // skew
        cs.setNonStrokingColor(new Color(230, 128, 25));
        cs.addRect(0, 0, 28 * MM, 18 * MM);
        cs.fill();
        cs.restoreGraphicsState();
    }

    private static void gradient(Doc d, PDPageContentStream cs) throws IOException {
        cs.saveGraphicsState();
        cs.addRect(20 * MM, H - 90 * MM, 75 * MM, 40 * MM);
        cs.clip();
        cs.shadingFill(shading(2, new float[] { 20 * MM, H - 90 * MM, 95 * MM, H - 50 * MM },
                new float[] { 1, 0.37f, 0.23f }, new float[] { 0.17f, 0.36f, 1 }));
        cs.restoreGraphicsState();
        cs.saveGraphicsState();
        ellipse(cs, 135 * MM, H - 70 * MM, 22 * MM, 22 * MM);
        cs.clip();
        cs.shadingFill(shading(3, new float[] { 135 * MM, H - 70 * MM, 0, 135 * MM, H - 70 * MM, 22 * MM },
                new float[] { 1, 1, 1 }, new float[] { 0.12f, 0.54f, 0.3f }));
        cs.restoreGraphicsState();
        // A radial whose start circle has a radius and whose START does not extend (nothing inside it): what
        // SVG cannot say (FORMAT §B3 fr, data-extend); `sh` under a clip lands as a gradient on a path.
        cs.saveGraphicsState();
        cs.addRect(20 * MM, H - 150 * MM, 60 * MM, 50 * MM);
        cs.clip();
        cs.shadingFill(shading(3, new float[] { 50 * MM, H - 125 * MM, 6 * MM, 50 * MM, H - 125 * MM, 20 * MM },
                new float[] { 0.95f, 0.8f, 0.2f }, new float[] { 0.5f, 0.1f, 0.6f }, false, true));
        cs.restoreGraphicsState();
    }

    /**
     * Soft masks, everywhere the model cannot say them: a luminosity mask (a white-to-black ramp) over a fill, a
     * stroke, a line of text and an image, each over a backdrop and under text painted after; then two masked
     * groups — a luminosity mask whose CMYK backdrop [0 0 0 0] is WHITE, and an alpha mask. Page 2 repeats a
     * masked fill on a page turned by /Rotate 90.
     */
    private static void softMask(Doc d) throws IOException {
        PDExtendedGraphicsState ramp = mask(d, COSName.LUMINOSITY, null, null, fcs -> {
            fcs.shadingFill(shading(2, new float[] { 20 * MM, 0, 190 * MM, 0 }, new float[] { 1, 1, 1 }, new float[] { 0, 0, 0 }));
        });
        try (var cs = d.page(A4)) {
            d.header(cs, "vector_soft_mask");
            cs.setNonStrokingColor(new Color(250, 236, 160));                       // the backdrop the mask lets through
            cs.addRect(15 * MM, H - 175 * MM, 180 * MM, 140 * MM);
            cs.fill();
            cs.saveGraphicsState();
            cs.setGraphicsStateParameters(ramp);
            cs.setNonStrokingColor(new Color(30, 70, 200));
            cs.addRect(20 * MM, H - 70 * MM, 170 * MM, 25 * MM);
            cs.fill();
            cs.setStrokingColor(new Color(200, 30, 30));
            cs.setLineWidth(8);
            cs.moveTo(20 * MM, H - 80 * MM);
            cs.lineTo(190 * MM, H - 80 * MM);
            cs.stroke();
            cs.setNonStrokingColor(new Color(20, 120, 40));
            text(cs, d.sans(), 28, 20 * MM, H - 98 * MM, "Masked text, fading out");
            cs.drawImage(JPEGFactory.createFromImage(d.pdf, gradient(256, 160), 0.88f), 20 * MM, H - 125 * MM, 170 * MM, 22 * MM);
            cs.restoreGraphicsState();

            // A CMYK luminosity mask with a WHITE backdrop: everything shows but the black square it paints.
            PDExtendedGraphicsState white = mask(d, COSName.LUMINOSITY, PDDeviceCMYK.INSTANCE, new float[] { 0, 0, 0, 0 }, fcs -> {
                fcs.setNonStrokingColor(new PDColor(new float[] { 0, 0, 0, 1 }, PDDeviceCMYK.INSTANCE));
                fcs.addRect(40 * MM, H - 160 * MM, 15 * MM, 15 * MM);
                fcs.fill();
            });
            cs.saveGraphicsState();
            cs.setGraphicsStateParameters(white);
            cs.drawForm(group(d, new Color(0, 150, 90), 20 * MM, H - 170 * MM, 55 * MM, 35 * MM));
            cs.restoreGraphicsState();
            // An ALPHA mask: the group's coverage, not its colours — two bands at full and at 30 % alpha.
            PDExtendedGraphicsState alpha = mask(d, COSName.ALPHA, null, null, fcs -> {
                fcs.setNonStrokingColor(Color.BLACK);
                fcs.addRect(100 * MM, H - 170 * MM, 40 * MM, 35 * MM);
                fcs.fill();
                PDExtendedGraphicsState faint = new PDExtendedGraphicsState();
                faint.setNonStrokingAlphaConstant(0.3f);
                fcs.setGraphicsStateParameters(faint);
                fcs.addRect(140 * MM, H - 170 * MM, 45 * MM, 35 * MM);
                fcs.fill();
            });
            cs.saveGraphicsState();
            cs.setGraphicsStateParameters(alpha);
            cs.drawForm(group(d, new Color(150, 0, 120), 100 * MM, H - 170 * MM, 85 * MM, 35 * MM));
            cs.restoreGraphicsState();

            cs.setNonStrokingColor(Color.BLACK);                                     // painted AFTER, over the masks
            text(cs, d.sans(), 11, 22 * MM, H - 64 * MM, "Text painted after the mask, over it");
        }
        try (var cs = d.page(A4)) {
            cs.setNonStrokingColor(new Color(250, 236, 160));
            cs.addRect(15 * MM, H - 120 * MM, 120 * MM, 80 * MM);
            cs.fill();
            cs.saveGraphicsState();
            cs.setGraphicsStateParameters(ramp);
            cs.setNonStrokingColor(new Color(30, 70, 200));
            cs.addRect(25 * MM, H - 90 * MM, 90 * MM, 30 * MM);
            cs.fill();
            cs.restoreGraphicsState();
            cs.setNonStrokingColor(Color.BLACK);
            text(cs, d.sans(), 14, 25 * MM, H - 100 * MM, "Masked fill on a page turned by /Rotate 90");
        }
        d.pdf.getPage(1).setRotation(90);
    }

    private interface Drawing { void draw(PDFormContentStream cs) throws IOException; }

    /** An ExtGState whose /SMask is a group of the given subtype painted by {@code content} — in {@code cs}
     *  (none = the page's), with backdrop {@code bc} when given. */
    private static PDExtendedGraphicsState mask(Doc d, COSName subtype, PDColorSpace cs, float[] bc, Drawing content) throws IOException {
        PDFormXObject g = new PDFormXObject(d.pdf);
        g.setBBox(A4);
        g.setResources(new PDResources());
        COSDictionary attrs = new COSDictionary();
        attrs.setItem(COSName.S, COSName.TRANSPARENCY);
        if (cs != null) attrs.setItem(COSName.CS, cs.getCOSObject());
        g.getCOSObject().setItem(COSName.GROUP, attrs);
        try (var fcs = new PDFormContentStream(g)) { content.draw(fcs); }
        COSDictionary sm = new COSDictionary();
        sm.setItem(COSName.TYPE, COSName.MASK);
        sm.setItem(COSName.S, subtype);
        sm.setItem(COSName.G, g);
        if (bc != null) { COSArray a = new COSArray(); for (float v : bc) a.add(new COSFloat(v)); sm.setItem(COSName.BC, a); }
        PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
        gs.getCOSObject().setItem(COSName.SMASK, sm);
        return gs;
    }

    /** A transparency group painting one rectangle — what a masked {@code Do} draws. */
    private static PDFormXObject group(Doc d, Color c, float x, float y, float w, float h) throws IOException {
        PDFormXObject g = new PDFormXObject(d.pdf);
        g.setBBox(A4);
        g.setResources(new PDResources());
        COSDictionary attrs = new COSDictionary();
        attrs.setItem(COSName.S, COSName.TRANSPARENCY);
        g.getCOSObject().setItem(COSName.GROUP, attrs);
        try (var fcs = new PDFormContentStream(g)) {
            fcs.setNonStrokingColor(c);
            fcs.addRect(x, y, w, h);
            fcs.fill();
        }
        return g;
    }

    private static void dashCaps(Doc d, PDPageContentStream cs) throws IOException {
        float y = H - 50 * MM;
        float[][] dashes = { {}, { 6, 3 }, { 1, 4 }, { 10, 4, 2, 4 } };
        int[] caps = { 0, 1, 2, 0 };
        for (int i = 0; i < dashes.length; i++) {
            cs.saveGraphicsState();
            cs.setLineWidth(4);
            cs.setLineDashPattern(dashes[i], 0);
            cs.setLineCapStyle(caps[i]);
            cs.setLineJoinStyle(i % 3);
            cs.moveTo(20 * MM, y);
            cs.lineTo(150 * MM, y);
            cs.stroke();
            cs.restoreGraphicsState();
            y -= 12 * MM;
        }
    }

    /** What an SVG becomes once a producer draws it: a stroked rect, a circle, a polygon, a quadratic. */
    private static void svgLike(Doc d, PDPageContentStream cs) throws IOException {
        cs.saveGraphicsState();
        cs.transform(new Matrix(1, 0, 0, -1, 20 * MM, H - 50 * MM));      // the SVG's Y-down frame
        cs.setNonStrokingColor(new Color(0xEE, 0xEE, 0xFF));
        cs.setStrokingColor(new Color(0x22, 0x22, 0x99));
        cs.setLineWidth(2);
        cs.addRect(2, 2, 176, 116);
        cs.fillAndStroke();
        cs.setNonStrokingColor(new Color(0xEE, 0x66, 0x33));
        ellipse(cs, 50, 60, 35, 35);
        cs.fill();
        cs.setNonStrokingColor(new Color(0x22, 0xAA, 0x88));
        cs.setStrokingColor(new Color(0x00, 0x66, 0x33));
        cs.setLineWidth(3);
        cs.moveTo(110, 20); cs.lineTo(160, 100); cs.lineTo(100, 100); cs.closePath();
        cs.fillAndStroke();
        cs.setStrokingColor(new Color(0x33, 0x00, 0x66));
        cs.setLineWidth(4);
        cs.moveTo(120, 30);
        cs.curveTo(140, 50, 140, 70, 120, 90);                     // Q150,60 raised to a cubic
        cs.stroke();
        cs.restoreGraphicsState();
    }

    // ── colour ───────────────────────────────────────────────────────────────────

    private static void rgb(Doc d, PDPageContentStream cs) throws IOException {
        float x = 20 * MM;
        for (Color c : List.of(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN, Color.MAGENTA)) {
            cs.setNonStrokingColor(c);
            cs.addRect(x, H - 70 * MM, 22 * MM, 22 * MM);
            cs.fillAndStroke();
            x += 26 * MM;
        }
    }

    private static void cmyk(Doc d, PDPageContentStream cs) throws IOException {
        float x = 20 * MM;
        for (float[] c : new float[][] { { 0, 1, 1, 0 }, { 1, 0, 1, 0 }, { 1, 1, 0, 0 }, { 0, 0, 0, 1 }, { 0, 0, 0, 0.4f } }) {
            cs.setNonStrokingColor(new PDColor(c, PDDeviceCMYK.INSTANCE));
            cs.addRect(x, H - 70 * MM, 26 * MM, 26 * MM);
            cs.fillAndStroke();
            x += 30 * MM;
        }
        cs.setNonStrokingColor(Color.BLACK);
        text(cs, d.sans(), 10, 20 * MM, H - 80 * MM, "CMYK 0,1,1,0 / 1,0,1,0 / 1,1,0,0 / K");
    }

    /** A genuine Separation colour space with a Type 2 tint transform to DeviceCMYK. */
    /** A hatched bar, as a chart draws one: a coloured tiling pattern (blue cell, white diagonal) fills a
     *  rectangle. The model cannot vectorise it and crops it from the reference raster — which must draw the
     *  cell's plain fills, or the bar tiles nothing. */
    private static void tiling(Doc d, PDPageContentStream cs) throws IOException {
        org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern hatch = new org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern();
        hatch.setPaintType(org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern.PAINT_COLORED);
        hatch.setTilingType(org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern.TILING_CONSTANT_SPACING);
        hatch.setBBox(new PDRectangle(0, 0, 12, 12));
        hatch.setXStep(12);
        hatch.setYStep(12);
        hatch.setResources(new org.apache.pdfbox.pdmodel.PDResources());
        try (OutputStream os = hatch.getContentStream().createOutputStream()) {
            os.write("0.25 0.35 0.95 rg 0 0 12 12 re f 1 1 1 RG 2 w 0 0 m 12 12 l S".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        org.apache.pdfbox.cos.COSName name = d.pdf.getPage(d.pdf.getNumberOfPages() - 1).getResources().add(hatch);
        cs.setNonStrokingColor(new PDColor(name, new org.apache.pdfbox.pdmodel.graphics.color.PDPattern(null)));
        cs.addRect(30 * MM, H - 160 * MM, 30 * MM, 110 * MM);
        cs.fill();
        cs.setNonStrokingColor(Color.BLACK);
        text(cs, d.sans(), 10, 70 * MM, H - 60 * MM, "A bar filled with a hatched tiling pattern");
    }

    private static void spot(Doc d, PDPageContentStream cs) throws IOException {
        PDSeparation sep = new PDSeparation();
        sep.setColorantName("PANTONE-1655-C");
        sep.setAlternateColorSpace(PDDeviceCMYK.INSTANCE);
        sep.setTintTransform(type2(new float[] { 0, 0, 0, 0 }, new float[] { 0, 0.9f, 1, 0 }));
        cs.setNonStrokingColor(new PDColor(new float[] { 1 }, sep));
        cs.addRect(56, 640, 150, 70);
        cs.fill();
        cs.setNonStrokingColor(new PDColor(new float[] { 0.5f }, sep));
        cs.addRect(230, 640, 150, 70);
        cs.fill();
        cs.setNonStrokingColor(Color.BLACK);
        text(cs, d.sans(), 10, 20 * MM, H - 95 * MM, "Two rects use a Separation 'PANTONE-1655-C' (tints 1.0 and 0.5)");
    }

    private static void alpha(Doc d, PDPageContentStream cs) throws IOException {
        PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
        gs.setNonStrokingAlphaConstant(0.5f);
        cs.saveGraphicsState();
        cs.setGraphicsStateParameters(gs);
        cs.setNonStrokingColor(new Color(230, 25, 25));
        ellipse(cs, 70 * MM, H - 70 * MM, 24 * MM, 24 * MM);
        cs.fill();
        cs.setNonStrokingColor(new Color(25, 77, 230));
        ellipse(cs, 95 * MM, H - 70 * MM, 24 * MM, 24 * MM);
        cs.fill();
        cs.restoreGraphicsState();
    }

    // ── images ───────────────────────────────────────────────────────────────────

    private static void jpegRgb(Doc d, PDPageContentStream cs) throws IOException {
        cs.drawImage(JPEGFactory.createFromImage(d.pdf, gradient(256, 160), 0.88f), 20 * MM, H - 95 * MM, 80 * MM, 50 * MM);
    }

    private static void pngAlpha(Doc d, PDPageContentStream cs) throws IOException {
        cs.setNonStrokingColor(new Color(217, 217, 153));
        cs.addRect(18 * MM, H - 100 * MM, 90 * MM, 60 * MM);
        cs.fill();
        BufferedImage img = new BufferedImage(160, 160, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(220, 40, 40, 200));
        g.fillOval(10, 10, 140, 140);
        g.setColor(new Color(40, 80, 220, 140));
        g.fillRect(60, 60, 90, 90);
        g.dispose();
        cs.drawImage(LosslessFactory.createFromImage(d.pdf, img), 25 * MM, H - 95 * MM, 50 * MM, 50 * MM);   // SMask
    }

    /** DCTDecode in DeviceCMYK: the JPEG flavour most tools mishandle. */
    private static void jpegCmyk(Doc d, PDPageContentStream cs) throws IOException {
        WritableRaster r = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, 200, 130, 4, null);
        for (int y = 0; y < 130; y++)
            for (int x = 0; x < 200; x++) r.setPixel(x, y, new int[] { x % 256, (130 - y) * 2 % 256, (x + y) % 256, 20 });
        var bytes = new ByteArrayOutputStream();
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (var io = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(io);
            writer.write(null, new IIOImage(r, null, null), null);
        } finally { writer.dispose(); }
        cs.drawImage(JPEGFactory.createFromByteArray(d.pdf, bytes.toByteArray()), 20 * MM, H - 90 * MM, 70 * MM, 45 * MM);
    }

    private static void bilevel(Doc d, PDPageContentStream cs) throws IOException {
        BufferedImage img = new BufferedImage(120, 120, BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < 120; y++)
            for (int x = 0; x < 120; x++) img.setRGB(x, y, (x / 12 + y / 12) % 2 == 0 ? 0xFF000000 : 0xFFFFFFFF);
        cs.drawImage(LosslessFactory.createFromImage(d.pdf, img), 20 * MM, H - 90 * MM, 45 * MM, 45 * MM);
    }

    // ── pages and documents ──────────────────────────────────────────────────────

    private static void mixedSizes(Doc d) throws IOException {
        var a5 = PDRectangle.A5;
        Object[][] pages = { { A4, "A4 portrait" }, { PDRectangle.LETTER, "Letter portrait" },
                             { new PDRectangle(a5.getHeight(), a5.getWidth()), "A5 landscape" } };
        for (Object[] p : pages) {
            PDRectangle size = (PDRectangle) p[0];
            try (var cs = d.page(size)) {
                text(cs, d.sans(), 11, 20 * MM, size.getHeight() - 20 * MM, "MATRIX::page_mixed_sizes  page=" + p[1]);
                text(cs, d.sans(), 18, 20 * MM, size.getHeight() - 35 * MM, (String) p[1]);
            }
        }
    }

    private static void rotate(Doc d) throws IOException {
        try (var cs = d.page(A4)) {
            d.header(cs, "page_rotate");
            text(cs, d.sans(), 18, 20 * MM, H - 40 * MM, "This page has /Rotate 90");
            cs.moveTo(20 * MM, H - 45 * MM);
            cs.lineTo(120 * MM, H - 45 * MM);
            cs.stroke();
        }
        d.pdf.getPage(0).setRotation(90);
    }

    private static void cropBox(Doc d) throws IOException {
        float in = 15 * MM;
        try (var cs = d.page(A4)) {
            d.header(cs, "page_mediabox_cropbox");
            cs.setStrokingColor(new Color(204, 0, 0));
            cs.setLineDashPattern(new float[] { 4, 3 }, 0);
            cs.addRect(in, in, A4.getWidth() - 2 * in, H - 2 * in);
            cs.stroke();
            text(cs, d.sans(), 14, 20 * MM, H / 2, "CropBox is inset 15mm from MediaBox (red dashes)");
        }
        d.pdf.getPage(0).setCropBox(new PDRectangle(in, in, A4.getWidth() - 2 * in, H - 2 * in));
    }

    /** A book under one title, as a reflowable book must cut it: the title alone is no chapter, so the cut
     *  falls at its sections; a subsection is an entry of its section, not a chapter; a section heading
     *  wrapped over two lines is one heading; a section of one line is too short to stand and joins the
     *  next. No bookmarks: the headings are the typography's (sizes that recur). */
    private static void chapters(Doc d) throws IOException {
        String[] words = ("river stone window garden letter harbour lantern meadow pencil orchard candle bridge winter "
                + "copper violin market forest ladder mirror thunder basket pepper silver castle feather ribbon "
                + "anchor marble cotton saddle ember valley tunnel kettle shadow walnut glacier compass").split(" ");
        Object[][] pages = {
            { "Title", "A Book Under One Title" },
            { "Section", "1 The first part" }, { "Body", 6 }, { "Sub", "1.1 Where the river begins" }, { "Body", 4 },
            { "Page" },
            { "Section", "2 The second part, whose heading is long enough that the" }, { "Section", "press wrapped it over two lines" },
            { "Body", 6 }, { "Sub", "2.1 Stones and windows in the old town" }, { "Body", 4 },
            { "Page" },
            { "Section", "3 A stub" }, { "Body", 0 },
            { "Section", "4 The last part" }, { "Body", 6 }, { "Sub", "4.1 Lanterns" }, { "Body", 4 },
        };
        PDPageContentStream cs = d.page(A4);
        float y = H - 30 * MM;
        int line = 1;
        try {
            for (Object[] e : pages) {
                switch ((String) e[0]) {
                    case "Page"    -> { cs.close(); cs = d.page(A4); y = H - 30 * MM; }
                    case "Title"   -> { text(cs, d.sans(), 24, 20 * MM, y, (String) e[1]); y -= 40; }
                    case "Section" -> { text(cs, d.sans(), 16, 20 * MM, y, (String) e[1]); y -= 20; }
                    case "Sub"     -> { y -= 6; text(cs, d.sans(), 14, 20 * MM, y, (String) e[1]); y -= 20; }
                    default        -> {
                        int n = (Integer) e[1];
                        if (n == 0) { text(cs, d.sans(), 11, 20 * MM, y, "One line."); y -= 14; }
                        for (int i = 0; i < n; i++, line++) {                // every line its own words: no two pages alike
                            StringBuilder t = new StringBuilder(words[(line * 13) % words.length]);
                            for (int w = 1; w < 11; w++) t.append(' ').append(words[(line * 13 + w * w * 3 + w) % words.length]);
                            text(cs, d.sans(), 11, 20 * MM, y, t.append('.').toString());
                            y -= 14;
                        }
                        y -= 12;
                    }
                }
            }
        } finally { cs.close(); }
    }

    /** {@link #chapters} with its bookmarks, nested as the book is: the title holds the parts, a part its
     *  detail — the table of contents must keep that nesting, the spine still cut at the parts. */
    private static void chaptersOutline(Doc d) throws IOException {
        chapters(d);
        PDPage p1 = d.pdf.getPage(0), p2 = d.pdf.getPage(1), p3 = d.pdf.getPage(2);
        var outline = new PDDocumentOutline();
        PDOutlineItem title = item("A Book Under One Title", p1, H);
        outline.addLast(title);
        PDOutlineItem one = item("1 The first part", p1, H), two = item("2 The second part, whose heading is long enough that the press wrapped it over two lines", p2, H);
        PDOutlineItem four = item("4 The last part", p3, H);
        title.addLast(one);
        one.addLast(item("1.1 Where the river begins", p1, H));
        title.addLast(two);
        two.addLast(item("2.1 Stones and windows in the old town", p2, H));
        title.addLast(item("3 A stub", p3, H));
        title.addLast(four);
        four.addLast(item("4.1 Lanterns", p3, H));
        title.openNode();
        outline.openNode();
        d.pdf.getDocumentCatalog().setDocumentOutline(outline);
    }

    private static void outlineLinks(Doc d) throws IOException {
        try (var cs = d.page(A4)) {
            d.header(cs, "doc_outline_links");
            text(cs, d.sans(), 20, 20 * MM, H - 40 * MM, "Chapter 1");
            text(cs, d.sans(), 12, 20 * MM, H - 60 * MM, "External link: https://example.org");
            text(cs, d.sans(), 12, 20 * MM, H - 75 * MM, "Internal link -> jump to Chapter 2");
        }
        try (var cs = d.page(A4)) {
            // The bookmark title is the first line of a body-size paragraph, painted over a fill: the outline
            // aligner must cut the title off into its own paragraph, and the cut must keep its paint slot.
            cs.setNonStrokingColor(new Color(235, 240, 250));
            cs.addRect(15 * MM, H - 70 * MM, 170 * MM, 40 * MM);
            cs.fill();
            cs.setNonStrokingColor(Color.BLACK);
            text(cs, d.sans(), 12, 20 * MM, H - 40 * MM, "Chapter 2");
            text(cs, d.sans(), 12, 20 * MM, H - 40 * MM - 14, "The chapter opens on a line that follows its title without a gap,");
            text(cs, d.sans(), 12, 20 * MM, H - 40 * MM - 28, "so the paragraph builder sees one block and the outline must cut it.");
        }
        PDPage p1 = d.pdf.getPage(0), p2 = d.pdf.getPage(1);
        p1.getAnnotations().add(uriLink(new PDRectangle(20 * MM, H - 63 * MM, 100 * MM, 6 * MM), "https://example.org"));
        outline(d.pdf, new Object[] { "Chapter 1", p1 }, new Object[] { "Chapter 2", p2 });
        // What LaTeX (hyperref) and InDesign write: NAMED destinations. The second bookmark names "chapter.2"
        // from the /Names /Dests tree, the internal link names "toc.2" from the catalog's older /Dests
        // dictionary — both must land on page 2 like an explicit destination.
        var tree = new PDDestinationNameTreeNode();
        tree.setNames(Map.of("chapter.2", xyz(p2, H)));
        var names = new PDDocumentNameDictionary(d.pdf.getDocumentCatalog());
        names.setDests(tree);
        d.pdf.getDocumentCatalog().setNames(names);
        COSDictionary dests = new COSDictionary();
        dests.setItem("toc.2", xyz(p2, H));
        d.pdf.getDocumentCatalog().getCOSObject().setItem(COSName.DESTS, dests);
        d.pdf.getDocumentCatalog().getDocumentOutline().getLastChild().setDestination(new PDNamedDestination("chapter.2"));
        var internal = new PDAnnotationLink();
        internal.setRectangle(new PDRectangle(20 * MM, H - 78 * MM, 100 * MM, 6 * MM));
        var go = new PDActionGoTo();
        go.setDestination(new PDNamedDestination("toc.2"));
        internal.setAction(go);
        internal.setBorderStyle(noBorder());
        p1.getAnnotations().add(internal);
    }

    /** Five pages under one running head and a folio — page furniture, which the logical tree must leave out. */
    private static void runningHeads(Doc d) throws IOException {
        for (int i = 1; i <= 5; i++)
            try (var cs = d.page(A4)) {
                text(cs, d.sans(), 9, 20 * MM, H - 12 * MM, "MATRIX::doc_running_heads");
                text(cs, d.sans(), 9, 20 * MM, H - 18 * MM, "A Treatise on Running Heads");
                float y = H - 40 * MM;
                for (int l = 0; l < 8; l++, y -= 6 * MM)
                    text(cs, d.sans(), 11, 20 * MM, y, "Page " + i + ", line " + (l + 1) + ": the body text that changes on every page.");
                text(cs, d.sans(), 9, A4.getWidth() / 2, 12 * MM, String.valueOf(i));
            }
    }

    /**
     * A contract whose every page opens on its section: « 1. Section 1: terms and conditions », then « 2. … 2: … »,
     * at the top edge where a running head would sit, the same words but for the numbers — as stable from page to
     * page as a running head, but set larger than the text. A heading is elevated; a running head is not.
     */
    private static void sections(Doc d) throws IOException {
        for (int i = 1; i <= 4; i++)
            try (var cs = d.page(A4)) {
                text(cs, d.sans(), 18, 20 * MM, H - 25 * MM, i + ". Section " + i + ": terms and conditions");
                float y = H - 38 * MM;
                for (int l = 0; l < 10; l++, y -= 6 * MM)
                    text(cs, d.sans(), 11, 20 * MM, y, "Clause " + i + "." + (l + 1) + ": the parties agree on the terms below.");
                text(cs, d.sans(), 9, A4.getWidth() / 2, 12 * MM, String.valueOf(i));
            }
    }

    /**
     * A paragraph the press cut: its lines run the full column to the foot of page 1 and carry on at the head
     * of page 2 — one paragraph to a reader. The pages are numbered by a BARE folio (« 1 », « 2 », « 3 »), which
     * no edit distance sees as stable. A short last line ends a paragraph; an indented first line starts one.
     */
    private static void pageBreak(Doc d) throws IOException {
        PDFont mono = new PDType1Font(Standard14Fonts.FontName.COURIER);
        String full = "the river ran on past the old mill and the bridge where";    // every body line: the same width
        String[][] pages = {
            { "First paragraph, short and complete in itself.", "", full, full, full, full, full, "and so this one long sentence of ours is carried over the" },
            { "page break and ends here, its last line short.", "", "    A new paragraph opens with an indent.", full, "and ends." },
            { "A third page, a paragraph of its own, ends.", "", full, "and ends too." } };
        for (int i = 0; i < pages.length; i++)
            try (var cs = d.page(A4)) {
                float y = H - 30 * MM;
                for (String line : pages[i]) { if (!line.isEmpty()) text(cs, mono, 11, 20 * MM, y, line); y -= 5 * MM; }
                text(cs, mono, 9, A4.getWidth() / 2, 12 * MM, String.valueOf(i + 1));
            }
    }

    /**
     * A magazine spread, as a layout program exports it: each page carries the whole spread, the other half
     * beyond its edge; the bookmarks are the layout's file names and spread numbers, naming nothing in the
     * text (the page holds a « 4 » all the same). Page 1: a
     * display line set over its photograph, two columns set ragged with a paragraph cut at the foot of the
     * first, the second column's next paragraph set over another photograph, and a note on a translucent
     * panel (a transparency group at 90 %: baked). Page 2: the article's title, two lines set solid at 30 pt over a
     * body of 10 pt, and its first paragraph.
     */
    private static void magazine(Doc d) throws IOException {
        PDFont f = d.sans();
        float col = 80 * MM, x1 = 20 * MM, x2 = 110 * MM, lead = 12, top = H - 110 * MM;
        WORDS.put("doc_magazine", List.of(
            "Every minute counts",
            "and the paper keeps the night watch over the shelves until the morning",
            "The reading room opens at nine",
            "A note set on a translucent panel stays readable: its words are text, whatever the panel is baked",
            "The archive at night",
            "A lead set in the middle of a mosaic of coloured tiles is still the article's text, not a label"));
        ABSENT.put("doc_magazine", List.of("neighbour"));
        PDPage first, second;
        try (var cs = d.page(A4)) {
            first = d.pdf.getPage(0);
            var photo = JPEGFactory.createFromImage(d.pdf, gradient(256, 160), 0.88f);
            cs.drawImage(photo, x1, H - 95 * MM, 110 * MM, 50 * MM);
            text(cs, f, 24, x1 + 70 * MM, H - 75 * MM, "Every minute counts");      // over the photo, past its edge
            String[] left = {                                                       // ragged: no line fills the column
                "In the cellars of the old town house the", "boxes stand in rows along the walls, each",
                "one labelled by a hand long gone. Nobody", "comes down after dark, the lamps are", 
                "switched off one by one at seven and the", "doors are locked behind the last clerk",
                "who leaves the building by the side gate", "and the paper keeps the night watch over" };
            for (int i = 0; i < left.length; i++) text(cs, f, 10, x1, top - i * lead, left[i]);
            cs.drawImage(photo, x2 + 15 * MM, top - 9 * lead, 75 * MM, 8 * lead);  // under most of the next paragraph
            String[] right = {
                "the shelves until the morning comes.", "",
                "    The reading room opens at nine and the", "first visitors are students with notes",
                "and laptops, then the historians arrive", "with their lists of boxes, and later the",
                "families looking for a name, a date, a", "house that stood here before the war and",
                "a photograph of a grandmother's wedding." };
            for (int i = 0; i < right.length; i++) if (!right[i].isEmpty()) text(cs, f, 10, x2, top - i * lead, right[i]);
            PDFormXObject note = new PDFormXObject(d.pdf);                      // a panel and its words, one group
            note.setBBox(A4);
            note.setResources(new PDResources());
            COSDictionary attrs = new COSDictionary();
            attrs.setItem(COSName.S, COSName.TRANSPARENCY);
            note.getCOSObject().setItem(COSName.GROUP, attrs);
            try (var fcs = new PDFormContentStream(note)) {
                fcs.setNonStrokingColor(new Color(250, 230, 90));
                fcs.addRect(x1, top - 14 * lead - 6, x2 + col - x1, 3 * lead + 10);   // under both columns
                fcs.fill();
                fcs.setNonStrokingColor(Color.BLACK);
                String[] lines = { "A note set on a translucent panel stays readable: its words are text,",
                                   "whatever the panel is baked into, and they read after both columns", "of the article." };
                for (int i = 0; i < lines.length; i++) {
                    fcs.beginText();
                    fcs.setFont(f, 10);
                    fcs.newLineAtOffset(x1 + 4, top - 12 * lead - i * lead);
                    fcs.showText(lines[i]);
                    fcs.endText();
                }
            }
            PDExtendedGraphicsState translucent = new PDExtendedGraphicsState();
            translucent.setNonStrokingAlphaConstant(0.9f);
            cs.saveGraphicsState();
            cs.setGraphicsStateParameters(translucent);
            cs.drawForm(note);
            cs.restoreGraphicsState();
            text(cs, f, 10, A4.getWidth() + 20 * MM, top, "The neighbour page, past the edge.");   // the spread's other half
        }
        try (var cs = d.page(A4)) {
            second = d.pdf.getPage(1);
            text(cs, f, 10, -150 * MM, top, "The neighbour page, past the edge.");
            text(cs, f, 30, x1, H - 30 * MM, "The archive");                   // a title on two lines, set solid
            text(cs, f, 30, x1, H - 30 * MM - 30, "at night");
            String[] body = { "Every city keeps a memory of paper, and this", "one keeps it in the cellars of a house that",
                              "has seen 4 centuries of clerks come and go and", "will see a few more." };
            for (int i = 0; i < body.length; i++) text(cs, f, 10, x1, H - 55 * MM - i * lead, body[i]);
            // a lead painted in the middle of a mosaic of tiles that runs all around it: one stretch of paint
            String[] lead2 = { "A lead set in the middle of a mosaic of", "coloured tiles is still the article's text,",
                               "not a label of the drawing around it, and", "it reads where it stands." };
            float mx = x1, my = H - 230 * MM;
            for (int k = 0; k < 2; k++) {                                       // tiles a point or two apart, a hole for the lead
                for (int r = 0; r < 12; r++)
                    for (int c = 0; c < 24; c++) {
                        if ((c == 23 ? 1 : 0) != k || r >= 3 && r <= 8 && c >= 3 && c <= 20) continue;
                        cs.setNonStrokingColor(new Color(40 + 8 * c, 90 + 12 * r, 160));
                        cs.addRect(mx + c * 15, my + r * 11, 13 + (r + c) % 2, 8 + (r * c) % 2);
                        cs.fill();
                    }
                if (k == 0) {
                    cs.setNonStrokingColor(Color.BLACK);
                    for (int i = 0; i < lead2.length; i++) text(cs, f, 11, mx + 3 * 15 + 6, my + 8 * 11 - 2 - i * 14, lead2[i]);
                }
            }
        }
        outline(d.pdf, new Object[] { "MAG_p1_layout_v3", first }, new Object[] { "4", second });   // file names, a spread's number
    }

    /**
     * Two pages set in two ragged columns: the paragraph at the foot of page 1's right column carries on at
     * the top of page 2's LEFT column — the flow of a page set in columns. Its last line is long, not full:
     * ragged text wraps short of the column, so only the paragraph's own lines say what full is.
     */
    private static void columnsPageBreak(Doc d) throws IOException {
        PDFont f = d.sans();
        float x1 = 20 * MM, x2 = 110 * MM, lead = 12, top = H - 40 * MM;
        String[][] cols = {
            { "The first column opens the story of a long", "night in the reading room, where the lamps", "burn low and the clocks are the only voice.", "",
              "    A second paragraph begins with an indent", "and ends after two lines of its own." },
            { "The right column runs to the foot of the", "page with lines that never fill the column", "because the text is set ragged, as many", "magazines set their columns, and this one",
              "sentence is carried over the page break to" },
            { "the next page, where it ends in the first", "column with a short last line." },
            { "    The right column of the second page opens", "a paragraph of its own and closes it." } };
        for (int p = 0; p < 2; p++)
            try (var cs = d.page(A4)) {
                for (int c = 0; c < 2; c++) {
                    String[] lines = cols[2 * p + c];
                    for (int i = 0; i < lines.length; i++)
                        if (!lines[i].isEmpty()) text(cs, f, 10, c == 0 ? x1 : x2, top - i * lead, lines[i]);
                }
            }
    }

    /**
     * A press page: a title on two lines set solid at 28 pt across the page, then three columns of 8 pt on the
     * same baselines, a paragraph of two lines closing the middle one. Over the whole page the lines of the
     * three columns share their baselines, so a leading measured across them is 0 — and every block of two
     * lines, the title first, was judged by it and cut in two.
     */
    private static void threeColumns(Doc d) throws IOException {
        PDFont f = d.sans();
        float lead = 10, top = H - 70 * MM, w = 58 * MM;
        WORDS.put("doc_three_columns", List.of(
            "The night shift of the city archive",
            "A short paragraph of two lines closes the middle column"));
        String[][] cols = {
            { "The archive keeps its doors shut after six,", "but the work goes on in the back rooms where", "the clerks sort the boxes that came in during", "the day, number them and carry them down to", "the cellars on a trolley that squeaks at every", "turn of the long corridor under the hall." },
            { "Nobody has oiled the trolley for years, and", "the clerks like it that way: they hear each", "other coming from the far end of the cellar.", "",
              "A short paragraph of two lines closes the", "middle column of the page." },
            { "At midnight the last box is down, the lamps", "go out one by one, and the night watchman", "takes his first round through the empty", "rooms, his torch on the shelves, his keys", "on a ring at his belt, until the morning", "when the first readers knock at the door." } };
        try (var cs = d.page(A4)) {
            text(cs, f, 28, 20 * MM, H - 30 * MM, "The night shift of");
            text(cs, f, 28, 20 * MM, H - 30 * MM - 28, "the city archive");
            for (int c = 0; c < 3; c++)
                for (int i = 0; i < cols[c].length; i++)
                    if (!cols[c][i].isEmpty()) text(cs, f, 8, 20 * MM + c * (w + 5 * MM), top - i * lead, cols[c][i]);
        }
    }

    /**
     * A contents page on a coloured ground: the whole page is a tinted rectangle, a title runs across the top
     * beside a page number (so it is no heading over the rest alone),
     * a note stands in a narrow column on the left, and a grid of two rows of two cells — a number, a title of
     * one or two lines, a short description — fills the right. The ground covers every row alike; counted as a
     * screen, it left no row to cut, and the grid was read line by line across its cells.
     */
    private static void gridBackground(Doc d) throws IOException {
        PDFont f = d.sans();
        String[][] cells = {
            { "18", "Working together", "for a missing child", "A child disappears near a village and", "the police of two countries search for", "him together until he is found." },
            { "24", "Silent witnesses", "", "Fingerprints and DNA profiles bring a", "murderer to trial after months of work", "in the laboratory." },
            { "38", "Faces for the victims", "", "Police and charities work hand in hand", "to recognise the victims of human", "trafficking on the street." },
            { "40", "A shameless trade", "in human beings", "In the present migration crisis, the", "smugglers run a very lucrative trade,", "alone or within criminal networks." } };
        WORDS.put("doc_grid_background", List.of(                              // the note first, then each cell whole
            "A note on the left runs down beside the first two rows of the",
            "Working together for a missing child", "A child disappears near a village and the police of two countries search for him together",
            "A shameless trade in human beings", "In the present migration crisis, the smugglers run a very lucrative"));   // by rows or by columns
        try (var cs = d.page(A4)) {
            cs.setNonStrokingColor(new Color(186, 206, 216));                    // the ground: the whole page
            cs.addRect(0, 0, A4.getWidth(), A4.getHeight());
            cs.fill();
            cs.setNonStrokingColor(Color.BLACK);
            text(cs, f, 36, 15 * MM, H - 30 * MM, "Contents of the year");
            text(cs, f, 20, 150 * MM, H - 28 * MM, "4");                        // a page number in the title's band
            String[] note = { "A note on the left runs", "down beside the first two", "rows of the grid, the way", "a foreword sits beside", "the contents of a report", "and reads before them.",
                              "It is long enough to stand", "beside both rows at once." };
            for (int i = 0; i < note.length; i++) text(cs, f, 8, 15 * MM, H - 60 * MM - i * 10, note[i]);
            float cw = 62 * MM, gut = 4 * MM, x0 = 62 * MM, rowH = 45 * MM;
            for (int k = 0; k < 4; k++) {
                float x = x0 + (k % 2) * (cw + gut), y = H - 60 * MM - (k / 2) * rowH;
                String[] c = cells[k];
                cs.setStrokingColor(new Color(90, 110, 125));                    // the rule over the cell
                cs.moveTo(x, y + 14);
                cs.lineTo(x + cw, y + 14);
                cs.stroke();
                text(cs, f, 20, x - 1, y - 12, c[0]);
                text(cs, f, 10, x + 9 * MM, y - 4, c[1]);
                if (!c[2].isEmpty()) text(cs, f, 10, x + 9 * MM, y - 16, c[2]);
                for (int i = 3; i < c.length; i++) text(cs, f, 7, x + 9 * MM, y - 30 - (i - 3) * 9, c[i]);
            }
        }
    }

    /**
     * A picture tiled over the page as a wallpaper: the same photograph placed six times in a grid (embedded six
     * times, the same bytes under six names, as a layout program does), a column of copy set over part of it.
     * A reader of the book sees the picture once.
     */
    private static void wallpaper(Doc d) throws IOException {
        PDFont f = d.sans();
        WORDS.put("doc_wallpaper", List.of("The same photograph is laid six times over the page"));
        try (var cs = d.page(A4)) {
            for (int k = 0; k < 6; k++)                                         // six embeddings, one picture
                cs.drawImage(JPEGFactory.createFromImage(d.pdf, gradient(256, 160), 0.88f),
                             15 * MM + (k % 2) * 95 * MM, H - 15 * MM - (k / 2 + 1) * 62 * MM, 85 * MM, 55 * MM);
            String[] copy = { "The same photograph is laid six times over the", "page as a wallpaper, the way a designer fills a",
                              "spread with one picture, and the article runs over", "it in a column of its own, long enough to read as",
                              "copy and never as the label of a drawing." };
            for (int i = 0; i < copy.length; i++) text(cs, f, 10, 60 * MM, H - 120 * MM - i * 12, copy[i]);   // across the grid
        }
    }

    private static void metadata(Doc d) throws IOException {
        var info = d.pdf.getDocumentInformation();
        info.setTitle("Matrix Metadata Sample TITLE_TOKEN");
        info.setAuthor("Author AUTHOR_TOKEN");
        info.setSubject("Subject SUBJECT_TOKEN");
        info.setKeywords("alpha,beta,KEYWORD_TOKEN");
        try (var cs = d.page(A4)) {
            d.header(cs, "doc_metadata");
            text(cs, d.sans(), 14, 20 * MM, H - 40 * MM, "The Info dictionary carries title/author/subject/keywords.");
        }
    }

    /**
     * The tracks that ride BESIDE the ink, one of each so a loss is unambiguous: a text field with a
     * value and a checkbox that is on, a highlight and a note, two layers (one on, one off) with real
     * content, an external and an internal link, a two-level outline. No paint or text gate sees these.
     * The hidden layer's name and the external URI carry quotes and markup: they are written into
     * attributes, and an unescaped quote once made the whole container unreadable.
     */
    private static void rich(Doc d) throws IOException {
        PDRectangle size = new PDRectangle(400, 300);
        var ocProps = new PDOptionalContentProperties();
        PDOptionalContentGroup on = new PDOptionalContentGroup("Visible layer"), off = new PDOptionalContentGroup("Hidden \"draft\" layer");
        ocProps.addGroup(on);
        ocProps.addGroup(off);
        ocProps.setGroupEnabled(off, false);
        d.pdf.getDocumentCatalog().setOCProperties(ocProps);
        PDFont helv = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        try (var cs = d.page(size)) {
            text(cs, helv, 13, 40, 270, "MATRIX::rich");
            text(cs, helv, 13, 40, 250, "RICH FIXTURE - page 1");
            text(cs, helv, 13, 40, 220, "external link below, internal link to page 2");
            cs.beginMarkedContent(COSName.OC, on);
            cs.setNonStrokingColor(Color.RED);
            text(cs, helv, 13, 40, 180, "VISIBLE LAYER");
            cs.endMarkedContent();
            cs.beginMarkedContent(COSName.OC, off);
            cs.setNonStrokingColor(Color.BLUE);
            text(cs, helv, 13, 40, 150, "HIDDEN LAYER");
            cs.endMarkedContent();
            cs.setNonStrokingColor(Color.BLACK);
            text(cs, helv, 13, 40, 110, "form field and annotations sit on this page");
        }
        try (var cs = d.page(size)) {
            text(cs, helv, 13, 40, 250, "RICH FIXTURE - page 2");
            text(cs, helv, 13, 40, 210, "internal target");
        }
        PDPage p1 = d.pdf.getPage(0), p2 = d.pdf.getPage(1);

        // AcroForm: fields as plain widget dictionaries, values stated, no appearance streams
        COSDictionary text = widget(p1, "Tx", "fullname", 220, 100, 380, 124);
        text.setString(COSName.V, "Ada Lovelace");
        text.setString(COSName.DA, "/Helv 0 Tf 0 g");
        COSDictionary box = widget(p1, "Btn", "agreed", 220, 60, 244, 84);
        box.setName(COSName.V, "Yes");
        box.setName(COSName.AS, "Yes");
        COSDictionary dr = new COSDictionary(), fonts = new COSDictionary();
        fonts.setItem("Helv", helv);
        dr.setItem(COSName.FONT, fonts);
        COSDictionary form = new COSDictionary();
        form.setItem(COSName.FIELDS, new COSArray(List.of(text, box)));
        form.setString(COSName.DA, "/Helv 0 Tf 0 g");
        form.setItem(COSName.DR, dr);
        d.pdf.getDocumentCatalog().getCOSObject().setItem(COSName.ACRO_FORM, form);

        var hl = new PDAnnotationHighlight();
        hl.setRectangle(new PDRectangle(38, 240, 212, 26));
        hl.setQuadPoints(new float[] { 38, 266, 250, 266, 38, 240, 250, 240 });
        hl.setColor(new PDColor(new float[] { 1, 1, 0 }, PDDeviceRGB.INSTANCE));
        hl.setTitlePopup("reviewer");
        hl.setContents("look at this title");
        var note = new PDAnnotationText();
        note.setRectangle(new PDRectangle(350, 240, 22, 22));
        note.setColor(new PDColor(new float[] { 1, 0.6f, 0 }, PDDeviceRGB.INSTANCE));
        note.setTitlePopup("reviewer");
        note.setContents("a sticky note");

        List<PDAnnotation> annots = new ArrayList<>(List.of(PDAnnotation.createAnnotation(text), PDAnnotation.createAnnotation(box), hl, note));
        annots.add(uriLink(new PDRectangle(40, 214, 180, 18), "https://example.org/?q=\"quoted\"&a=<b>"));   // markup in values
        annots.add(goTo(new PDRectangle(230, 214, 130, 18), p2, 300));
        p1.setAnnotations(annots);

        PDDocumentOutline outline = new PDDocumentOutline();
        PDOutlineItem top = item("Rich fixture", p1, 300), child = item("Sub-section on page 2", p2, 300);
        top.addLast(child);
        outline.addLast(top);
        outline.openNode();
        top.openNode();
        d.pdf.getDocumentCatalog().setDocumentOutline(outline);
    }

    /**
     * A genuinely TAGGED page: marked content with MCIDs, a StructTreeRoot, a ParentTree, MarkInfo.
     * The case that matters is the last element: ONE P holding TWO MCIDs, a paragraph split over two
     * lines. A builder mapping one element per block passes every easy tag and still splits it.
     */
    /**
     * A tag tree that tags nothing: a Document holding one empty Sect and no marked content, as a layout
     * program writes it with tagging switched off (the catalog's /MarkInfo does not say /Marked). The
     * text is the document's, untagged — read by the heuristic like any untagged PDF.
     */
    private static void taggedEmpty(Doc d) throws IOException {
        try (var cs = d.page(A4)) {
            text(cs, d.sans(), 18, 20 * MM, H - 30 * MM, "A tree that tags nothing");
            text(cs, d.sans(), 10, 20 * MM, H - 42 * MM, "The words on this page are marked by no element of the tag tree,");
            text(cs, d.sans(), 10, 20 * MM, H - 47 * MM, "so they are read as the text of an untagged page.");
        }
        var root = new PDStructureTreeRoot();
        var top = new PDStructureElement("Document", root);
        root.appendKid(top);
        top.appendKid(new PDStructureElement("Sect", top));
        d.pdf.getDocumentCatalog().setStructureTreeRoot(root);
        WORDS.put("tagged_empty", List.of("A tree that tags nothing",
            "The words on this page are marked by no element of the tag tree, so they are read as the text of an untagged page."));
    }

    /**
     * A tree that tags one caption of a page of text: a layout program tagging only the objects an export
     * happened to mark. The rest of the page is the document — read by the heuristic, the caption with it.
     */
    private static void taggedPartial(Doc d) throws IOException {
        String[] body = { "Most of this page is text that no element of the tag tree marks,",
                          "set in a column like any article, line after line, as an",
                          "untagged page would be, and read all the same as its text." };
        try (var cs = d.page(A4)) {
            text(cs, d.sans(), 18, 20 * MM, H - 30 * MM, "A tree that tags one caption");
            for (int i = 0; i < body.length; i++) text(cs, d.sans(), 10, 20 * MM, H - 45 * MM - i * 12, body[i]);
            COSDictionary props = new COSDictionary();
            props.setInt(COSName.MCID, 0);
            cs.beginMarkedContent(COSName.P, PDPropertyList.create(props));
            text(cs, d.sans(), 8, 20 * MM, H - 80 * MM, "Caption.");
            cs.endMarkedContent();
        }
        PDPage page = d.pdf.getPage(0);
        page.setStructParents(0);
        var root = new PDStructureTreeRoot();
        var top = new PDStructureElement("Document", root);
        root.appendKid(top);
        var p = new PDStructureElement("P", top);
        p.setPage(page);
        p.appendKid(0);
        top.appendKid(p);
        var tree = new PDNumberTreeNode(PDParentTreeValue.class);
        COSArray kids = new COSArray();
        kids.add(p);
        tree.setNumbers(Map.of(0, new PDParentTreeValue(kids)));
        root.setParentTree(tree);
        d.pdf.getDocumentCatalog().setStructureTreeRoot(root);
        WORDS.put("tagged_partial", List.of("A tree that tags one caption", String.join(" ", body)));
    }

    /** A tagged document with no text at all: one picture, marked as a Figure — its tree is its structure. */
    private static void taggedFigure(Doc d) throws IOException {
        try (var cs = d.page(A4)) {
            COSDictionary props = new COSDictionary();
            props.setInt(COSName.MCID, 0);
            cs.beginMarkedContent(COSName.getPDFName("Figure"), PDPropertyList.create(props));
            cs.drawImage(JPEGFactory.createFromImage(d.pdf, gradient(256, 160), 0.88f), 20 * MM, H - 120 * MM, 120 * MM, 75 * MM);
            cs.endMarkedContent();
        }
        PDPage page = d.pdf.getPage(0);
        page.setStructParents(0);
        var root = new PDStructureTreeRoot();
        var top = new PDStructureElement("Document", root);
        root.appendKid(top);
        var fig = new PDStructureElement("Figure", top);
        fig.setPage(page);
        fig.setAlternateDescription("A gradient");
        fig.appendKid(0);
        top.appendKid(fig);
        var tree = new PDNumberTreeNode(PDParentTreeValue.class);
        COSArray kids = new COSArray();
        kids.add(fig);
        tree.setNumbers(Map.of(0, new PDParentTreeValue(kids)));
        root.setParentTree(tree);
        d.pdf.getDocumentCatalog().setStructureTreeRoot(root);
    }

    private static void tagged(Doc d) throws IOException {
        PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA), bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        Object[][] blocks = { { "H1", bold, 16f, 340f, "Titre principal du document" },
                              { "P", font, 10f, 310f, "Premier paragraphe, marque comme P dans l'arbre de balises." },
                              { "H2", bold, 12f, 275f, "Sous-titre de section" },
                              { "P", font, 10f, 248f, "Second paragraphe. La structure vient du PDF, pas de la geometrie." },
                              { "P", font, 10f, 228f, "Sa suite, sur une autre ligne, mais le MEME element P." },
                              { "Lbl", font, 10f, 200f, "1." },
                              { "LBody", font, 10f, 186f, "Le corps de l'item, dans son LBody." },
                              { "Span", font, 10f, 160f, "Un encadre ecrit en" },
                              { "Span", bold, 10f, 148f, "deux morceaux" } };
        // A form XObject that numbers its OWN marked content (/StructParents): its MCID 0 is not the page's.
        PDFormXObject note = new PDFormXObject(d.pdf);
        note.setBBox(new PDRectangle(400, 400));
        note.setResources(new PDResources());
        note.getCOSObject().setInt(COSName.STRUCT_PARENTS, 1);
        try (var fcs = new PDFormContentStream(note)) {
            COSDictionary props = new COSDictionary();
            props.setInt(COSName.MCID, 0);
            fcs.beginMarkedContent(COSName.P, PDPropertyList.create(props));
            fcs.beginText();
            fcs.setFont(font, 9);
            fcs.newLineAtOffset(40, 120);
            fcs.showText("Une note dans un form XObject, MCID 0 de son propre flux.");
            fcs.endText();
            fcs.endMarkedContent();
        }
        try (var cs = d.page(new PDRectangle(400, 400))) {
            text(cs, font, 8, 40, 380, "MATRIX::tagged");
            for (int mcid = 0; mcid < blocks.length; mcid++) {
                Object[] b = blocks[mcid];
                COSDictionary props = new COSDictionary();
                props.setInt(COSName.MCID, mcid);
                cs.beginMarkedContent(COSName.getPDFName((String) b[0]), PDPropertyList.create(props));
                text(cs, (PDFont) b[1], (Float) b[2], 40, (Float) b[3], (String) b[4]);
                cs.endMarkedContent();
            }
            cs.drawForm(note);
        }
        PDPage page = d.pdf.getPage(0);
        page.setStructParents(0);
        var root = new PDStructureTreeRoot();
        // The title is a producer's OWN type ("Titre"), standard only through the RoleMap — what Word and
        // InDesign write for every style.
        root.setRoleMap(Map.of("Titre", "H1"));
        var top = new PDStructureElement("Document", root);
        root.appendKid(top);
        // Everything sits in one Sect, as Typst writes a document: a wrapper that only groups — the chapters
        // are below it, not one chapter called « Content ».
        var document = new PDStructureElement("Sect", top);
        top.appendKid(document);
        COSArray byMcid = new COSArray();
        Object[][] groups = { { "Titre", new int[] { 0 } }, { "P", new int[] { 1 } }, { "H2", new int[] { 2 } }, { "P", new int[] { 3, 4 } } };
        for (Object[] g : groups) {
            var e = new PDStructureElement((String) g[0], document);
            e.setPage(page);
            // The H2 holds its words through a Div, as Typst writes every heading: a grouping element inside a
            // heading groups nothing — the heading's text is still its words.
            var owner = "H2".equals(g[0]) ? new PDStructureElement("Div", e) : e;
            if (owner != e) { owner.setPage(page); e.appendKid(owner); }
            for (int mcid : (int[]) g[1]) {
                owner.appendKid(mcid);
                byMcid.add(owner);
            }
            document.appendKid(e);
        }
        // L > LI > (Lbl, LBody): the LBody is the item's body, not an item of its own.
        var list = new PDStructureElement("L", document);
        var item = new PDStructureElement("LI", list);
        var label = new PDStructureElement("Lbl", item);
        var body = new PDStructureElement("LBody", item);
        for (var e : List.of(list, item, label, body)) e.setPage(page);
        label.appendKid(5);
        body.appendKid(6);
        byMcid.add(label);
        byMcid.add(body);
        item.appendKid(label);
        item.appendKid(body);
        list.appendKid(item);
        document.appendKid(list);
        // A callout as Typst writes one: a Div of loose Spans, one per styled piece — ONE paragraph to a reader.
        var callout = new PDStructureElement("Div", document);
        callout.setPage(page);
        for (int mcid : new int[] { 7, 8 }) {
            var span = new PDStructureElement("Span", callout);
            span.setPage(page);
            span.appendKid(mcid);
            byMcid.add(span);
            callout.appendKid(span);
        }
        document.appendKid(callout);
        // The note reaches its MCID through a reference that names the form's stream.
        var noteP = new PDStructureElement("P", document);
        noteP.setPage(page);
        var mcr = new PDMarkedContentReference();
        mcr.setPage(page);
        mcr.setMCID(0);
        mcr.getCOSObject().setItem(COSName.getPDFName("Stm"), note.getCOSObject());
        noteP.appendKid(mcr);
        document.appendKid(noteP);
        var parentTree = new PDNumberTreeNode(PDParentTreeValue.class);
        parentTree.setNumbers(Map.of(0, new PDParentTreeValue(byMcid), 1, new PDParentTreeValue(new COSArray(List.of(noteP)))));
        root.setParentTree(parentTree);
        root.setParentTreeNextKey(2);
        d.pdf.getDocumentCatalog().setStructureTreeRoot(root);
        var mark = new PDMarkInfo();
        mark.setMarked(true);
        d.pdf.getDocumentCatalog().setMarkInfo(mark);
        d.pdf.getDocumentCatalog().setLanguage("fr-CH");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Four cubic arcs: an ellipse centred on (cx, cy). */
    private static void ellipse(PDPageContentStream cs, float cx, float cy, float rx, float ry) throws IOException {
        float k = 0.5523f, ox = rx * k, oy = ry * k;
        cs.moveTo(cx + rx, cy);
        cs.curveTo(cx + rx, cy + oy, cx + ox, cy + ry, cx, cy + ry);
        cs.curveTo(cx - ox, cy + ry, cx - rx, cy + oy, cx - rx, cy);
        cs.curveTo(cx - rx, cy - oy, cx - ox, cy - ry, cx, cy - ry);
        cs.curveTo(cx + ox, cy - ry, cx + rx, cy - oy, cx + rx, cy);
        cs.closePath();
    }

    private static BufferedImage gradient(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) img.setRGB(x, y, (x << 16) | (((int) (y * 1.6) % 256) << 8) | ((x + y) % 256));
        return img;
    }

    private static PDFunctionType2 type2(float[] c0, float[] c1) {
        COSDictionary f = new COSDictionary();
        f.setInt(COSName.FUNCTION_TYPE, 2);
        f.setItem(COSName.DOMAIN, floats(0, 1));
        f.setItem(COSName.C0, floats(c0));
        f.setItem(COSName.C1, floats(c1));
        f.setInt(COSName.N, 1);
        return new PDFunctionType2(f);
    }

    /** Type 2 (axial) or 3 (radial), RGB, extended at both ends. */
    private static PDShading shading(int type, float[] coords, float[] from, float[] to) {
        return shading(type, coords, from, to, true, true);
    }

    private static PDShading shading(int type, float[] coords, float[] from, float[] to, boolean e0, boolean e1) {
        PDShading s = type == 2 ? new PDShadingType2(new COSDictionary()) : new PDShadingType3(new COSDictionary());
        s.setShadingType(type);
        s.setColorSpace(PDDeviceRGB.INSTANCE);
        COSDictionary dict = s.getCOSObject();
        dict.setItem(COSName.COORDS, floats(coords));
        dict.setItem(COSName.FUNCTION, type2(from, to));
        dict.setItem(COSName.EXTEND, new COSArray(List.of(COSBoolean.getBoolean(e0), COSBoolean.getBoolean(e1))));
        return s;
    }

    private static COSArray floats(float... v) {
        COSArray a = new COSArray();
        for (float f : v) a.add(new COSFloat(f));
        return a;
    }

    private static COSDictionary widget(PDPage page, String type, String name, float x0, float y0, float x1, float y1) {
        COSDictionary w = new COSDictionary();
        w.setName(COSName.TYPE, "Annot");
        w.setName(COSName.SUBTYPE, "Widget");
        w.setName(COSName.FT, type);
        w.setString(COSName.T, name);
        w.setItem(COSName.RECT, floats(x0, y0, x1, y1));
        w.setInt(COSName.F, 4);
        w.setItem(COSName.P, page);
        return w;
    }

    private static PDAnnotationLink uriLink(PDRectangle at, String uri) {
        var link = new PDAnnotationLink();
        link.setRectangle(at);
        var action = new PDActionURI();
        action.setURI(uri);
        link.setAction(action);
        link.setBorderStyle(noBorder());
        return link;
    }

    private static PDAnnotationLink goTo(PDRectangle at, PDPage target, float top) {
        var link = new PDAnnotationLink();
        link.setRectangle(at);
        link.setDestination(xyz(target, top));
        link.setBorderStyle(noBorder());
        return link;
    }

    private static PDBorderStyleDictionary noBorder() {
        var b = new PDBorderStyleDictionary();
        b.setWidth(0);
        return b;
    }

    private static PDPageXYZDestination xyz(PDPage page, float top) {
        var dest = new PDPageXYZDestination();
        dest.setPage(page);
        dest.setLeft(0);
        dest.setTop((int) top);
        return dest;
    }

    private static PDOutlineItem item(String title, PDPage page, float top) {
        var i = new PDOutlineItem();
        i.setTitle(title);
        i.setDestination(xyz(page, top));
        return i;
    }

    /** One top-level entry per {title, page}, each anchored at the page's top. */
    private static void outline(PDDocument pdf, Object[]... entries) {
        var outline = new PDDocumentOutline();
        for (Object[] e : entries) outline.addLast(item((String) e[0], (PDPage) e[1], H));
        outline.openNode();
        pdf.getDocumentCatalog().setDocumentOutline(outline);
    }
}
