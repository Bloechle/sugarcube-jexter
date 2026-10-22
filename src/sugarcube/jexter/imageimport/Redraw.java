package sugarcube.jexter.imageimport;

import sugarcube.jexter.core.JxPath;
import sugarcube.jexter.core.JxRect;
import sugarcube.jexter.core.JxTransform;
import sugarcube.jexter.core.OcrClient;
import sugarcube.jexter.ocd.model.OCDFont;
import sugarcube.jexter.ocd.model.OCDText;
import sugarcube.jexter.pdfimport.TtfImporter;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The second reading of a picture's words ({@code ocrText=redrawn}): each word read with confidence is ERASED
 * from the picture and drawn again as real text — sharp at any zoom and in print, where the invisible layer
 * keeps the photograph's own pixels. What the engine is not sure of keeps its pixels, so a word is replaced
 * only when it was read.
 *
 * <p><b>A word is redrawn when</b> the engine's confidence reaches {@link #MIN_CONFIDENCE} (an engine that
 * gives none is believed), the face has every one of its characters, and the face set at the line's height
 * fits the word's box within {@link #MIN_STRETCH}–{@link #MAX_STRETCH} — a word that would need more squeezing
 * or stretching than that was not read as the letters it is. Otherwise it stays in the picture and lies over
 * it as invisible text, as in the first reading.
 *
 * <p><b>Erased, then drawn.</b> The word's box, padded by a fraction of the line's height, is filled with the
 * paper around it (the median of a ring just outside); the word is drawn in the median colour of its own ink
 * (the darker part of its box). The face is Liberation Sans ({@link TtfImporter#standardSans}): the engine
 * says nothing of the face a word was set in, and a sans of Arial's metrics is the least surprising stand-in.
 * A line's size is its height over the height the face's ink spans for its own letters ({@link Face#ink}: a line
 * with no descender is shorter, not smaller), its baseline that ink's foot above the line's bottom.
 *
 * <p><b>Set, not pasted.</b> Each word sized and stretched to its own box reads as words cut out and glued back:
 * the sizes and widths a reading measures carry its noise. So the lines of one paragraph ({@link #blocks}, from
 * the geometry alone) share one size, the median of theirs; the words of one line share one horizontal stretch,
 * the median of theirs; and each word, still placed where it was read, fills its own width with the spacing
 * between its letters ({@link #draw}) — the letters keep their shape, the gaps take the difference.
 *
 * <p><b>The picture's ink, not the engine's box</b> ({@link #ink}): an engine's box is its own idea of a word —
 * Tesseract's hug the letters, another engine's need not — so the line's height and the word's width are measured in the
 * picture, inside the boxes the engine gave: the band of rows its ink fills, the columns a word's ink spans.
 * Set at boxes padded by half a line, a word came out too large and kept its pixels (gate READ).
 *
 * <p><b>A picture left with no ink is dropped</b> ({@link #inkless}): a page of text, once every word is
 * redrawn, is paper — no reason to carry a photograph of it. A picture with anything left (a logo, a
 * signature, a word kept) is kept, erased where words were redrawn.
 */
final class Redraw {

    private Redraw() {}

    /** The {@code ocrText} values: the two readings of a picture's words. */
    static final String INVISIBLE = "invisible", REDRAWN = "redrawn";

    /** The font the redrawn words are set in. */
    static final String FONT_ID = "OCR-Sans";
    /** Liberation Sans's vertical metrics, in em (hhea: 1854 and −434 over 2048). */
    static final double ASCENT = 0.905, DESCENT = 0.212;
    /** Below this confidence a word keeps its pixels. */
    static final double MIN_CONFIDENCE = 0.8;
    /** The horizontal stretch the face may take to fill a word's box. */
    static final double MIN_STRETCH = 0.6, MAX_STRETCH = 1.7;
    /** Lines whose sizes differ by less than this (relative) may be one paragraph, set at one size. */
    static final double SAME_SIZE = 0.15;
    /** The spacing a word may take between its letters to fill its width, in em of its size. */
    static final double MAX_TRACKING = 0.1;
    /** A picture with less of its area as ink than this, once the words are erased, is dropped. */
    static final double INK_LEFT = 0.0015;

    /** Why words kept their pixels, counted for the log: a picture where none was redrawn says which rule refused them. */
    static final class Tally {
        int redrawn, doubtful, unknown, stretched;
        final java.util.List<Double> stretch = new java.util.ArrayList<>();

        @Override public String toString() {
            double[] v = stretch.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            return redrawn + " redrawn; kept: " + doubtful + " doubtful, " + unknown + " with a letter the face lacks, "
                    + stretched + " out of stretch" + (v.length == 0 ? "" : String.format(java.util.Locale.ROOT,
                    " (stretch min %.2f, median %.2f, max %.2f)", v[0], v[v.length / 2], v[v.length - 1]));
        }
    }

    /** {@code null} or blank = the first reading; the two names otherwise, anything else refused in those words. */
    static boolean redrawn(String v) {
        if (v == null || v.isBlank() || v.equals(INVISIBLE)) return false;
        if (v.equals(REDRAWN)) return true;
        throw new IllegalArgumentException("unknown ocrText \"" + v + "\" (expected " + INVISIBLE + " or " + REDRAWN + ")");
    }

    /** The face, its glyphs added as the words need them: one per document. */
    static final class Face {
        final OCDFont font = new OCDFont("Liberation Sans").family("Liberation Sans").id(FONT_ID).embedded(true)
                .ascent(ASCENT).descent(DESCENT);
        private final byte[] ttf;
        private final Map<Integer, Double> advance = new HashMap<>();
        private final java.util.Set<Integer> missing = new java.util.HashSet<>();

        Face() throws IOException {
            ttf = TtfImporter.standardSans();
            add(new int[]{' '});
        }

        /** Whether the face has every character of {@code cps}, adding the ones it has not given yet. */
        boolean has(int[] cps) throws IOException {
            int[] fresh = Arrays.stream(cps).distinct().filter(c -> !advance.containsKey(c) && !missing.contains(c)).toArray();
            if (fresh.length > 0) add(fresh);
            return Arrays.stream(cps).allMatch(advance::containsKey);
        }

        private void add(int[] cps) throws IOException {
            for (var g : TtfImporter.extract(ttf, cps)) { font.addChar(g.cp(), g.outline(), g.advance()); advance.put(g.cp(), g.advance()); }
            for (int cp : cps) {
                if (advance.containsKey(cp)) continue;
                if (cp == ' ') { font.addChar(' ', new JxPath(), 0.278); advance.put(cp, 0.278); }   // Liberation's space: no contour
                else missing.add(cp);
            }
        }

        double advance(int cp) { return advance.get(cp); }

        /** {top, foot} of the ink the face gives {@code cps} (em, foot ≤ 0): an engine's line box hugs the letters
         *  it holds, so a line with no descender ("delivered The") is shorter than one with them ("quickly") and
         *  must not come out a smaller size. Both reach at least the x-height and the baseline. */
        double[] ink(int[] cps) throws IOException {
            has(cps);
            double top = 0.52, foot = 0;
            for (int cp : cps) {
                Integer gid = font.gidOf(cp);
                var g = gid == null ? null : font.glyph(gid);
                if (g == null || g.isSpace()) continue;
                top = Math.max(top, g.bounds().maxY());
                foot = Math.min(foot, g.bounds().minY());
            }
            return new double[]{top, foot};
        }
        int gid(int cp)        { return font.gidOf(cp); }
    }

    /** A word the face can redraw: its letters and their natural width at a size of 1 (em). */
    record Fit(int[] cps, double em) {}

    /** The size a line's ink gives (see the class comment): {@code line} its ink down (pixels), {@code ink}
     *  {@link Face#ink} of its letters. */
    static double size(JxRect line, double[] ink, double s) { return line.height() * s / (ink[0] - ink[1]); }

    /** The word, if it may be redrawn, or {@code null} when it must keep its pixels (see the class comment):
     *  {@code b} is its ink across, {@code size} its line's. */
    static Fit fit(Face face, OcrClient.Word word, JxRect b, double size, double s, Tally tally) throws IOException {
        if (word.confidence() >= 0 && word.confidence() < MIN_CONFIDENCE) { tally.doubtful++; return null; }
        int[] cps = word.text().strip().codePoints().toArray();
        if (cps.length == 0 || b.width() <= 0 || size <= 0) return null;
        if (!face.has(cps)) { tally.unknown++; return null; }
        double em = 0;
        for (int cp : cps) em += face.advance(cp);
        double sx = b.width() * s / (em * size);
        tally.stretch.add(sx);
        if (sx < MIN_STRETCH || sx > MAX_STRETCH) { tally.stretched++; return null; }
        tally.redrawn++;
        return new Fit(cps, em);
    }

    /**
     * The paragraphs, as the picture shows them: each line (its ink, pixels) joins a block whose last line it
     * sits under — a gap of at most one em of that line's size (pixels per point {@code s}), half of the shorter one shared across, a size within
     * {@link #SAME_SIZE} of the block's first — or starts one. Answers each line's block, in the input's order.
     * Geometry only: the paragraphs the analysis builds come later, over the words drawn here.
     */
    static int[] blocks(List<JxRect> lines, double[] sizes, double s) {
        Integer[] order = new Integer[lines.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, java.util.Comparator.comparingDouble(i -> lines.get(i).y()));
        int[] block = new int[lines.size()];
        List<int[]> open = new java.util.ArrayList<>();                 // {first line, last line} of each block
        for (int i : order) {
            JxRect r = lines.get(i);
            int join = -1;
            for (int k = 0; k < open.size() && join < 0; k++) {
                JxRect last = lines.get(open.get(k)[1]);
                double shared = Math.min(r.maxX(), last.maxX()) - Math.max(r.x(), last.x());
                if (r.y() - last.maxY() <= sizes[open.get(k)[1]] / s && r.y() >= last.cy()
                        && shared >= 0.5 * Math.min(r.width(), last.width())
                        && Math.abs(sizes[i] / sizes[open.get(k)[0]] - 1) <= SAME_SIZE) join = k;
            }
            if (join < 0) { open.add(new int[]{i, i}); join = open.size() - 1; }
            else open.get(join)[1] = i;
            block[i] = join;
        }
        return block;
    }

    /**
     * One word drawn: at {@code size} (its paragraph's), on its own line's baseline ({@code lineSize}, {@code ink}),
     * stretched as its whole line ({@code stretch}) and spaced to fill its own ink across — the letters of one
     * line set alike, each word where it was read. A word whose spacing would pass {@link #MAX_TRACKING} (or a
     * single letter) takes the stretch it alone needs instead.
     */
    static OCDText draw(Face face, OcrClient.Word word, Fit f, JxRect b, JxRect line, double[] ink, double lineSize,
                        double size, double stretch, double s, double pageHeight) {
        double natural = f.em() * size, width = b.width() * s;
        double sx = stretch, tracking = f.cps().length > 1 ? (width / stretch - natural) / (f.cps().length - 1) : Double.NaN;
        if (!(Math.abs(tracking) <= MAX_TRACKING * size)) { sx = width / natural; tracking = 0; }
        double baseline = pageHeight - line.maxY() * s - ink[1] * lineSize;
        OCDText t = new OCDText(FONT_ID, size);
        if (word.confidence() >= 0) t.confidence(Math.min(1, word.confidence()));
        t.transform(new JxTransform(sx, 0, 0, 1, b.x() * s, baseline));
        double x = 0;
        for (int cp : f.cps()) { t.add(face.gid(cp), x, new String(Character.toChars(cp))); x += face.advance(cp) * size + tracking; }
        return t;
    }

    static double median(double[] v) {
        double[] c = v.clone();
        Arrays.sort(c);
        return c.length == 0 ? Double.NaN : c[c.length / 2];
    }

    /** Erase the word's box (in the picture's pixels) with the paper around it; answer the ink's colour (argb). */
    static int erase(BufferedImage img, JxRect box, double lineHeight) {
        int pad = (int) Math.max(1, Math.round(lineHeight * 0.12));
        int x0 = clamp((int) Math.floor(box.x()) - pad, img.getWidth()), x1 = clamp((int) Math.ceil(box.maxX()) + pad, img.getWidth());
        int y0 = clamp((int) Math.floor(box.y()) - pad, img.getHeight()), y1 = clamp((int) Math.ceil(box.maxY()) + pad, img.getHeight());
        if (x1 <= x0 || y1 <= y0) return 0xFF000000;
        int paper = paperAround(img, x0, y0, x1, y1);
        int ink = inkWithin(img, x0, y0, x1, y1, paper);
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) img.setRGB(x, y, paper);
        return ink;
    }

    /** The ink inside {@code box} (pixels): the band of rows holding the box's densest row of ink, and the
     *  columns that band's ink spans — {@code null} when the box holds nothing far darker than its paper. A
     *  neighbouring line the box reaches into is left out by the band, a padding by the columns. */
    static JxRect ink(BufferedImage img, JxRect box) {
        int x0 = clamp((int) Math.floor(box.x()), img.getWidth()), x1 = clamp((int) Math.ceil(box.maxX()), img.getWidth());
        int y0 = clamp((int) Math.floor(box.y()), img.getHeight()), y1 = clamp((int) Math.ceil(box.maxY()), img.getHeight());
        if (x1 <= x0 || y1 <= y0) return null;
        int paper = luma(paperAround(img, x0, y0, x1, y1)), darkest = 255;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) darkest = Math.min(darkest, luma(img.getRGB(x, y)));
        if (paper - darkest < 48) return null;
        int cut = (paper + darkest) / 2;
        int[] rows = new int[y1 - y0];
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) if (luma(img.getRGB(x, y)) <= cut) rows[y - y0]++;
        int peak = 0;
        for (int i = 1; i < rows.length; i++) if (rows[i] > rows[peak]) peak = i;
        int top = peak, bottom = peak;
        while (top > 0 && rows[top - 1] > 0) top--;
        while (bottom < rows.length - 1 && rows[bottom + 1] > 0) bottom++;
        int left = x1, right = x0 - 1;
        for (int y = y0 + top; y <= y0 + bottom; y++) for (int x = x0; x < x1; x++)
            if (luma(img.getRGB(x, y)) <= cut) { left = Math.min(left, x); right = Math.max(right, x); }
        return new JxRect(left, y0 + top, right - left + 1, bottom - top + 1);
    }

    /** Whether less than {@link #INK_LEFT} of the picture is ink — far darker than its own paper — on a
     *  copy of at most 600 px across (enough to see a logo, a signature or a kept word). */
    static boolean inkless(BufferedImage img) {
        int step = Math.max(1, Math.max(img.getWidth(), img.getHeight()) / 600);
        int[] lum = new int[256];
        int n = 0;
        for (int y = 0; y < img.getHeight(); y += step) for (int x = 0; x < img.getWidth(); x += step) { lum[luma(img.getRGB(x, y))]++; n++; }
        int paper = percentile(lum, n, 0.5), ink = 0;
        for (int v = 0; v < Math.max(0, paper - 80); v++) ink += lum[v];
        return ink < INK_LEFT * n;
    }

    // ── colours ─────────────────────────────────────────────────────────────────────────────────

    /** The median colour of a 2-px ring just outside the box: the paper the word sits on. */
    private static int paperAround(BufferedImage img, int x0, int y0, int x1, int y1) {
        int w = img.getWidth(), h = img.getHeight();
        int[][] ch = new int[3][256];
        int n = 0;
        for (int y = y0 - 2; y < y1 + 2; y++) for (int x = x0 - 2; x < x1 + 2; x++) {
            if (x < 0 || y < 0 || x >= w || y >= h || (x >= x0 && x < x1 && y >= y0 && y < y1)) continue;
            int p = img.getRGB(x, y);
            ch[0][(p >> 16) & 255]++; ch[1][(p >> 8) & 255]++; ch[2][p & 255]++; n++;
        }
        if (n == 0) return 0xFFFFFFFF;
        return 0xFF000000 | percentile(ch[0], n, 0.5) << 16 | percentile(ch[1], n, 0.5) << 8 | percentile(ch[2], n, 0.5);
    }

    /** The median colour of the box's darker pixels — those halfway or more from the paper to the darkest. */
    private static int inkWithin(BufferedImage img, int x0, int y0, int x1, int y1, int paper) {
        int darkest = 255;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) darkest = Math.min(darkest, luma(img.getRGB(x, y)));
        int cut = (luma(paper) + darkest) / 2;
        int[][] ch = new int[3][256];
        int n = 0;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
            int p = img.getRGB(x, y);
            if (luma(p) > cut) continue;
            ch[0][(p >> 16) & 255]++; ch[1][(p >> 8) & 255]++; ch[2][p & 255]++; n++;
        }
        if (n == 0) return 0xFF000000;
        return 0xFF000000 | percentile(ch[0], n, 0.5) << 16 | percentile(ch[1], n, 0.5) << 8 | percentile(ch[2], n, 0.5);
    }

    private static int luma(int p) { return (((p >> 16) & 255) * 299 + ((p >> 8) & 255) * 587 + (p & 255) * 114) / 1000; }

    private static int percentile(int[] hist, int n, double q) {
        int target = (int) Math.ceil(n * q), seen = 0;
        for (int v = 0; v < hist.length; v++) { seen += hist[v]; if (seen >= target) return v; }
        return hist.length - 1;
    }

    private static int clamp(int v, int max) { return Math.max(0, Math.min(max, v)); }
}
