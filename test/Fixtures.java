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
import org.apache.pdfbox.pdmodel.graphics.image.*;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.*;
import org.apache.pdfbox.pdmodel.graphics.shading.*;
import org.apache.pdfbox.pdmodel.graphics.state.*;
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
        FEATURES.put("vector_shapes", (Page) Fixtures::shapes);
        FEATURES.put("vector_clipping", (Page) Fixtures::clipping);
        FEATURES.put("vector_transforms", (Page) Fixtures::transforms);
        FEATURES.put("vector_gradient", (Page) Fixtures::gradient);
        FEATURES.put("vector_dash_caps", (Page) Fixtures::dashCaps);
        FEATURES.put("vector_svg_like", (Page) Fixtures::svgLike);
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
        FEATURES.put("rich", (Whole) Fixtures::rich);
        FEATURES.put("tagged", (Whole) Fixtures::tagged);
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

    /** A font for scripts Liberation lacks: one box glyph per character, compiled by the engine. */
    private static PDType0Font boxes(PDDocument pdf, String chars, double advance) throws IOException {
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
        return PDType0Font.load(pdf, new ByteArrayInputStream(JxFont.toOtf(f)));
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

    private static void outlineLinks(Doc d) throws IOException {
        try (var cs = d.page(A4)) {
            d.header(cs, "doc_outline_links");
            text(cs, d.sans(), 20, 20 * MM, H - 40 * MM, "Chapter 1");
            text(cs, d.sans(), 12, 20 * MM, H - 60 * MM, "External link: https://example.org");
            text(cs, d.sans(), 12, 20 * MM, H - 75 * MM, "Internal link -> jump to Chapter 2");
        }
        try (var cs = d.page(A4)) { text(cs, d.sans(), 20, 20 * MM, H - 40 * MM, "Chapter 2"); }
        PDPage p1 = d.pdf.getPage(0), p2 = d.pdf.getPage(1);
        p1.getAnnotations().add(uriLink(new PDRectangle(20 * MM, H - 63 * MM, 100 * MM, 6 * MM), "https://example.org"));
        p1.getAnnotations().add(goTo(new PDRectangle(20 * MM, H - 78 * MM, 100 * MM, 6 * MM), p2, H));
        outline(d.pdf, new Object[] { "Chapter 1", p1 }, new Object[] { "Chapter 2", p2 });
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
    private static void tagged(Doc d) throws IOException {
        PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA), bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        Object[][] blocks = { { "H1", bold, 16f, 340f, "Titre principal du document" },
                              { "P", font, 10f, 310f, "Premier paragraphe, marque comme P dans l'arbre de balises." },
                              { "H2", bold, 12f, 275f, "Sous-titre de section" },
                              { "P", font, 10f, 248f, "Second paragraphe. La structure vient du PDF, pas de la geometrie." },
                              { "P", font, 10f, 228f, "Sa suite, sur une autre ligne, mais le MEME element P." } };
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
        }
        PDPage page = d.pdf.getPage(0);
        page.setStructParents(0);
        var root = new PDStructureTreeRoot();
        var document = new PDStructureElement("Document", root);
        root.appendKid(document);
        Object[][] groups = { { "H1", new int[] { 0 } }, { "P", new int[] { 1 } }, { "H2", new int[] { 2 } }, { "P", new int[] { 3, 4 } } };
        COSArray byMcid = new COSArray();
        for (Object[] g : groups) {
            var e = new PDStructureElement((String) g[0], document);
            e.setPage(page);
            for (int mcid : (int[]) g[1]) {
                e.appendKid(mcid);
                byMcid.add(e);
            }
            document.appendKid(e);
        }
        var parentTree = new PDNumberTreeNode(PDParentTreeValue.class);
        parentTree.setNumbers(Map.of(0, new PDParentTreeValue(byMcid)));
        root.setParentTree(parentTree);
        root.setParentTreeNextKey(1);
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
        PDShading s = type == 2 ? new PDShadingType2(new COSDictionary()) : new PDShadingType3(new COSDictionary());
        s.setShadingType(type);
        s.setColorSpace(PDDeviceRGB.INSTANCE);
        COSDictionary dict = s.getCOSObject();
        dict.setItem(COSName.COORDS, floats(coords));
        dict.setItem(COSName.FUNCTION, type2(from, to));
        dict.setItem(COSName.EXTEND, new COSArray(List.of(COSBoolean.TRUE, COSBoolean.TRUE)));
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
