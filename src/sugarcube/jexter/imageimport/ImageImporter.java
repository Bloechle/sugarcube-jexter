package sugarcube.jexter.imageimport;

import sugarcube.jexter.core.ConvertOptions;
import sugarcube.jexter.core.JxLog;
import sugarcube.jexter.core.JxRect;
import sugarcube.jexter.core.JxTransform;
import sugarcube.jexter.core.OcrClient;
import sugarcube.jexter.ocd.analysis.Analysis;
import sugarcube.jexter.ocd.io.OCDVocab;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDFont;
import sugarcube.jexter.ocd.model.OCDGlyph;
import sugarcube.jexter.ocd.model.OCDImage;
import sugarcube.jexter.ocd.model.OCDLayer;
import sugarcube.jexter.ocd.model.OCDLayerContent;
import sugarcube.jexter.ocd.model.OCDPage;
import sugarcube.jexter.ocd.model.OCDText;

import zigzag.ZigZag;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

/**
 * Converts an image — a scan or a photograph, PNG, JPEG or a (multi-page) TIFF — into an
 * {@link OCDDocument}: one page per frame, the frame painted full-page, and the words the bound
 * {@link OcrClient} recognizes laid over it as INVISIBLE text (render mode 3) — the searchable layer of a
 * scanned PDF, built directly. The document then goes through the same {@link Analysis} as any other, so
 * lines, spaces, paragraphs and reading order are rebuilt from the word boxes, geometry first.
 *
 * <p><b>The pixels are the source.</b> A single-frame PNG or JPEG is stored byte for byte; only a frame
 * that has no file of its own (a TIFF page) is re-encoded, as PNG. The OCR engine reads exactly the bytes
 * that are stored, so the layer and the picture can never disagree.
 *
 * <p><b>A word fills its box, a line has one height.</b> All glyphs of the OCR font share one advance of
 * half an em and the run's transform stretches it horizontally to the word's width. Vertically, every word
 * of a line the ENGINE read is set at that line's box — its size the line's height, its baseline a fifth of
 * it above the line's bottom, which is precisely where {@link OCDText#bounds()} puts a run — so a line reads
 * as one size and one baseline, not one per word (a word's own box is as tall as its letters: "FR" is
 * shorter than "John,"). A word the engine placed in no line keeps its own box. The font carries no
 * outlines: invisible text has nothing to paint.
 *
 * <p><b>Recognized text is a LAYER over the picture</b> (FORMAT §B4b): the picture stays in the page's
 * base stratum, the words go into a recognition layer — one {@link OCDLayer} for the document, naming the
 * engine that read it, and on each page an {@link OCDLayerContent} holding that page's words, painted over
 * the image.
 *
 * <p><b>The engine may read a PREPARED picture</b> ({@link ConvertOptions#OCR_PREP}): ZigZag gray — its
 * background removal, with a window sized from the image — flattens uneven lighting, shadows and printed
 * backgrounds before the engine sees the frame. It moves no pixel — same size, same geometry, never
 * upsampled — so the boxes the engine answers land on the ORIGINAL picture, which is the one the page keeps. The recognition layer states the preparation, because
 * a reading of a prepared picture is a different reading. Every word carries the engine's confidence ({@link OCDText#confidence}, §B4) when the engine
 * gave one — what a rule that extracts a value needs in order to doubt it.
 *
 * <p>No engine bound, or an engine that fails, is not an error: the page is imported with its image and
 * no text layer.
 */
public final class ImageImporter {

    /** The recognition layer's id and name — the layer the importer writes its words into. */
    static final String LAYER_ID = "ocr", LAYER_NAME = "Recognized text";
    /** The OCR font: one resource for every page, glyphs minted as the words need them. */
    static final String FONT_ID = "OCR";
    /** Every OCR glyph's advance, in em — the unit the run transform stretches to the word box. */
    static final double ADVANCE = 0.5;
    /** Where {@link OCDText#bounds()} places a run's bottom, below the baseline, in em. */
    static final double DESCENT = 0.2;

    private ImageImporter() {}

    /** Whether {@code head} (the first bytes of a file) is an image this importer reads: PNG, JPEG or TIFF. */
    public static boolean isImage(byte[] head) {
        if (head == null || head.length < 4) return false;
        int b0 = head[0] & 0xFF, b1 = head[1] & 0xFF, b2 = head[2] & 0xFF, b3 = head[3] & 0xFF;
        return (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47)                    // PNG
            || (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF)                                     // JPEG
            || (b0 == 0x49 && b1 == 0x49 && b2 == 0x2A && b3 == 0x00)                       // TIFF, little-endian
            || (b0 == 0x4D && b1 == 0x4D && b2 == 0x00 && b3 == 0x2A);                      // TIFF, big-endian
    }

    /** Import {@code data} with the process-wide {@link OcrClient} (none bound = no text layer). */
    public static OCDDocument convert(byte[] data, ConvertOptions opts) throws IOException {
        return convert(data, opts, OcrClient.bound());
    }

    /** Import {@code data} with an explicit engine ({@code null} = no text layer). */
    public static OCDDocument convert(byte[] data, ConvertOptions opts, OcrClient ocr) throws IOException {
        double dpi = Math.max(1, opts.get(ConvertOptions.IMAGE_DPI));
        String lang = opts.get(ConvertOptions.OCR_LANG);
        String prep = knownPrep(opts.get(ConvertOptions.OCR_PREP));
        OCDDocument doc = new OCDDocument();
        OCDFont font = new OCDFont(FONT_ID).id(FONT_ID).ascent(1 - DESCENT).descent(DESCENT).spaceWidth(ADVANCE);
        glyph(font, ' ');                                   // the word break the analysis re-derives needs a face that maps U+0020

        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> it = in == null ? null : ImageIO.getImageReaders(in);
            if (it == null || !it.hasNext()) throw new IOException("unreadable image");
            ImageReader reader = it.next();
            try {
                reader.setInput(in);
                String fmt = reader.getFormatName().toLowerCase();
                int frames = reader.getNumImages(true);
                boolean asIs = frames == 1 && (fmt.equals("png") || fmt.startsWith("jp"));
                for (int f = 0; f < frames; f++) {
                    int w = reader.getWidth(f), h = reader.getHeight(f);
                    byte[] bytes = asIs ? data : png(reader.read(f));
                    String ext = asIs && fmt.startsWith("jp") ? "jpg" : "png";
                    doc.add(page(doc, font, bytes, ext, w, h, f, 72.0 / dpi, ocr, lang, prep));
                }
            } finally {
                reader.dispose();
            }
        }
        if (doc.pages().isEmpty()) throw new IOException("the image has no frame");
        if (font.glyphCount() > 1) doc.add(font);           // a font only when some page carries words
        if (ocr != null && doc.pages().stream().anyMatch(p -> p.content().stream().anyMatch(OCDLayerContent.class::isInstance)))
            doc.add(new OCDLayer(LAYER_ID, LAYER_NAME).recognition(new OCDLayer.Recognition(ocr.engine(), prep)).order(doc.layers().size()));
        Analysis.run(doc, opts);
        return doc;
    }

    /** One frame → one page: the image full-page, then one invisible run per recognized word. */
    private static OCDPage page(OCDDocument doc, OCDFont font, byte[] bytes, String ext, int w, int h,
                                int index, double s, OcrClient ocr, String lang, String prep) {
        double pw = w * s, ph = h * s;
        OCDPage page = new OCDPage(OCDVocab.pageId(index), pw, ph);
        String ref = doc.newImageRef(ext);
        doc.addImage(ref, bytes);
        OCDImage im = new OCDImage(ref).pixelSize(w, h);
        im.transform(new JxTransform(pw, 0, 0, ph, 0, 0)).z(0);
        page.add(im);
        if (ocr == null) return page;
        List<OcrClient.Word> words;
        try {
            words = ocr.read(prep == null ? bytes : prepared(bytes, prep), lang);
        } catch (Exception e) {
            JxLog.warn(ImageImporter.class, "OCR failed on page " + (index + 1) + " (" + ocr.engine() + ") \u2014 imported without a text layer", e);
            return page;
        }
        java.util.Map<Integer, JxRect> lines = new java.util.HashMap<>();   // engine line → its box (pixels)
        for (OcrClient.Word word : words)
            if (word.line() >= 0) lines.merge(word.line(), word.box(), JxRect::union);
        OCDLayerContent layer = new OCDLayerContent(LAYER_ID);              // over the picture: painted after it
        int z = 0;
        for (OcrClient.Word word : words) {
            OCDText t = run(font, word, word.line() >= 0 ? lines.get(word.line()) : word.box(), s, ph);
            if (t != null) layer.add(t.z(z++));
        }
        page.add(layer.z(1));
        return page;
    }

    /** A word as an invisible run (see the class comment): its own box across, its line's box down —
     *  {@code line} is the word's own box when the engine named no line. {@code null} for a word with no
     *  text or no area. */
    static OCDText run(OCDFont font, OcrClient.Word word, JxRect line, double s, double pageHeight) {
        JxRect b = word.box();
        int[] cps = word.text().strip().codePoints().toArray();
        if (cps.length == 0 || b.width() <= 0 || line.height() <= 0) return null;
        double fs = line.height() * s;
        double em = ADVANCE * fs;
        double sx = b.width() * s / (cps.length * em);
        double x0 = b.x() * s;
        double baseline = pageHeight - (line.y() + line.height()) * s + DESCENT * fs;
        OCDText t = new OCDText(FONT_ID, fs).renderMode(OCDText.INVISIBLE);
        if (word.confidence() >= 0) t.confidence(Math.min(1, word.confidence()));
        t.transform(new JxTransform(sx, 0, 0, 1, x0, baseline));
        for (int i = 0; i < cps.length; i++) t.add(glyph(font, cps[i]), i * em, new String(Character.toChars(cps[i])));
        return t;
    }

    /** The gid of {@code cp} in the OCR font, minting an outline-less glyph the first time. */
    private static int glyph(OCDFont font, int cp) {
        Integer gid = font.gidOf(cp);
        if (gid != null) return gid;
        int g = font.glyphCount() + 1;                      // gid 0 stays .notdef
        font.add(new OCDGlyph(g, new String(Character.toChars(cp)), null, ADVANCE));
        font.map(cp, g);
        return g;
    }

    /** The one preparation: ZigZag in GRAY mode — the background flattened, the ink kept in its grey levels.
     *  Binary would throw away the anti-aliasing an engine reads, colour keeps the printed background it is
     *  meant to remove. The name the option and the layer both use. */
    static final String ZIGZAG_GRAY = "zigzag-gray";

    /** {@code null} for none, the preparation when it is the one this importer knows; anything else is
     *  refused, in those words — a misspelt option must not silently mean "none". */
    static String knownPrep(String p) {
        if (p == null || p.isBlank()) return null;
        if (p.equals(ZIGZAG_GRAY)) return p;
        throw new IllegalArgumentException("unknown OCR preparation \"" + p + "\" (expected " + ZIGZAG_GRAY + ")");
    }

    /** ZigZag's window, from the image: {@code clamp(floor(diagonal / 45 + 0.5), 20, 250)} pixels — the rule
     *  measured and agreed for ZigZag's automatic size (no resolution collapses it, where a fixed 30 is too
     *  small for a phone photograph: a shadow is wider than the window and is taken for ink). Written
     *  {@code floor(x + 0.5)} on purpose: it is the form all three ZigZag ports round with, so the day the
     *  upstream ships the rule, this call site can hand it over without moving a pixel. */
    static int zigzagSize(int w, int h) {
        return Math.max(20, Math.min(250, (int) Math.floor(Math.hypot(w, h) / 45 + 0.5)));
    }

    /** The frame as the ENGINE will see it: ZigZag gray, window sized from the frame, at the frame's own size
     *  (nothing is upsampled), so every box the engine answers is a box on the original picture. */
    static byte[] prepared(byte[] frame, String prep) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(frame));
        if (img == null) throw new IOException("unreadable frame");
        ZigZag.Options o = new ZigZag.Options();
        o.mode = "gray";
        o.size = zigzagSize(img.getWidth(), img.getHeight());
        o.upsample = false;
        return png(ZigZag.process(img, o).image);
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(img, "png", out)) throw new IOException("cannot re-encode a frame as PNG");
        return out.toByteArray();
    }
}
