package sugarcube.jexter.font;

import sugarcube.jexter.core.JxJson;
import sugarcube.jexter.core.JxPath;
import sugarcube.jexter.core.JxStringer;
import sugarcube.jexter.ocd.model.OCDFont;
import sugarcube.jexter.ocd.model.OCDGlyph;
import sugarcube.jexter.trace.TraceOptions;
import sugarcube.jexter.trace.TracedShape;
import sugarcube.jexter.trace.Tracer;

import javax.imageio.ImageIO;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Foundry — turn hand-traced glyph bitmaps into a real OpenType font.
 *
 * <p>The single authority for the trace→font path: a client draws one black-on-transparent bitmap
 * per glyph (against shared baseline / x-height / cap / ascent / descent guides) and hands them in
 * as a small JSON request; this runs each through jexter's Potrace ({@link Tracer} BW mode → clean
 * even-odd outlines), normalizes the pixel-space contour into em units <em>against those guides</em>
 * (so the 'o' and the 'l' keep their true relative heights), assembles an {@link OCDFont}, and
 * compiles it to an embeddable font via {@link JxFont} — TrueType/glyf by default, CFF on request.
 *
 * <p>Surface-agnostic on purpose: the local {@code FontStudio} WebApp and the cloud
 * {@code FontServlet} both delegate here, so the foundry logic lives in exactly one place.
 *
 * <pre>
 *   request JSON: { name, family, format:"ttf"|"otf",
 *                   guides:{ baselineY, capY, ascentY, xY, descentY },   // px, shared by every glyph
 *                   glyphs:[ { cp:&lt;codepoint&gt;, png:"data:image/png;base64,…" } … ] }
 *
 *   Foundry.build(request)  → { bytes, filename, mediaType }
 *   Foundry.preview({ png, guides }) → { svg, w, h, advance }            // cleaned vector, for live UI
 * </pre>
 */
public final class Foundry {

    private Foundry() {}

    // ── em metrics (1 em = 1.0); the on-screen guides mirror these proportions ──
    public static final double EM_ASCENT  = 0.80;
    public static final double EM_DESCENT = 0.20;
    public static final double EM_CAP     = 0.70;
    public static final double EM_XHEIGHT = 0.48;
    public static final double EM_SPACE   = 0.28;
    public static final double SIDE_BEAR  = 0.06;   // left/right bearing, em
    public static final int    TURD_PX    = 6;      // drop trace specks ≤ this (px²)

    /** A compiled font + how to deliver it. */
    public record Font(byte[] bytes, String filename, String mediaType) {}

    // ── build ─────────────────────────────────────────────────────────────────
    /** Glyph-bitmaps request JSON → a compiled OpenType font. Throws on malformed input / no glyphs. */
    public static Font build(String requestJson) throws IOException {
        Map<String, Object> req = JxJson.asObj(JxJson.parse(requestJson));
        String name   = orDefault(JxJson.str(req, "name"), "MyHand");
        String family = orDefault(JxJson.str(req, "family"), name);
        String format = orDefault(JxJson.str(req, "format"), "ttf").toLowerCase();
        Guides g = guides(JxJson.has(req, "guides") ? JxJson.obj(req, "guides") : java.util.Map.of());

        OCDFont font = new OCDFont(name).family(family).id(slug(name))
                .ascent(EM_ASCENT).descent(EM_DESCENT).capHeight(EM_CAP)
                .xHeight(EM_XHEIGHT).spaceWidth(EM_SPACE).embedded(true);

        int gid = 1;
        for (Object e : JxJson.arr(req, "glyphs")) {
            Map<String, Object> ge = JxJson.asObj(e);
            int cp = (int) JxJson.lng(ge, "cp");
            JxPath outline;
            double advance;
            if (JxJson.has(ge, "em")) {                          // already vectorized (loaded font, unedited)
                outline = JxPath.ofSvg(JxJson.str(ge, "em"));
                if (outline == null || outline.isEmpty()) continue;
                advance = JxJson.has(ge, "advance") ? JxJson.dbl(ge, "advance")
                        : outline.bounds().getWidth() + 2 * SIDE_BEAR;
            } else {                                             // drawn bitmap → Potrace
                BufferedImage img = decodePng(JxJson.str(ge, "png"));
                if (img == null) continue;
                outline = glyphOutline(img, g);
                if (outline == null) continue;                   // blank cell
                advance = outline.bounds().getWidth() + 2 * SIDE_BEAR;
            }
            font.add(new OCDGlyph(gid, new String(Character.toChars(cp)), outline, advance));
            font.map(cp, gid++);
        }
        if (font.glyphCount() == 0) throw new IllegalArgumentException("no glyphs traced");

        boolean cff = format.equals("otf") || format.equals("cff");
        byte[] data = cff ? JxFont.toCffOtf(font) : JxFont.toOtf(font);
        return new Font(data, slug(name) + (cff ? ".otf" : ".ttf"), cff ? "font/otf" : "font/ttf");
    }

    // ── preview ───────────────────────────────────────────────────────────────
    /** { png, guides } → { svg, w, h, advance }: the cleaned vector for one glyph, for live on-page feedback. */
    public static String preview(String requestJson) throws IOException {
        Map<String, Object> req = JxJson.asObj(JxJson.parse(requestJson));
        BufferedImage img = decodePng(JxJson.str(req, "png"));
        if (img == null) throw new IllegalArgumentException("empty image");
        JxPath pixels = tracePixels(img);
        Guides g = guides(JxJson.has(req, "guides") ? JxJson.obj(req, "guides") : java.util.Map.of());
        JxPath em = pixels == null ? null : toEm(pixels, g);
        double advance = em == null ? 0 : em.bounds().getWidth() + 2 * SIDE_BEAR;
        return new JxStringer().obj()
                .str("svg", pixels == null ? "" : pixels.toSvg())
                .num("w", img.getWidth()).num("h", img.getHeight())
                .num("advance", advance).end().toString();
    }

    // ── trace + em-normalization (the one piece of glue) ─────────────────────────

    /** Potrace the black ink to a single contour in pixel space (null if blank). */
    private static JxPath tracePixels(BufferedImage img) {
        List<TracedShape> shapes = Tracer.trace(img, TraceOptions.bw().turdSize(TURD_PX));
        if (shapes.isEmpty()) return null;
        // Potrace merges contours even-odd, but TrueType glyf has no winding flag — it fills non-zero,
        // so an even-odd outline leaves counters (o, a, e, b, 0, 8…) filled. Re-fill through Area to get
        // a non-zero outline whose holes are oriented opposite the outer contour, so glyf cuts them.
        return new JxPath(new Area(shapes.get(0).path()));
    }

    /** Pixel-space contour → em outline (Y-up, baseline 0), left-aligned to the side bearing. */
    private static JxPath toEm(JxPath pixels, Guides g) {
        double s = EM_CAP / Math.max(1, g.baselineY - g.capY);        // px → em, anchored cap↔baseline
        // flip Y about the baseline:  em_y = (baselineY - y_px) * s ;  em_x = x_px * s
        JxPath em = pixels.transformed(new AffineTransform(s, 0, 0, -s, 0, g.baselineY * s));
        Rectangle2D b = em.bounds();
        if (b.isEmpty()) return null;
        return em.transformed(AffineTransform.getTranslateInstance(SIDE_BEAR - b.getMinX(), 0));
    }

    private static JxPath glyphOutline(BufferedImage img, Guides g) {
        JxPath pixels = tracePixels(img);
        return pixels == null ? null : toEm(pixels, g);
    }

    // ── small helpers ────────────────────────────────────────────────────────────
    private record Guides(double baselineY, double capY, double ascentY, double xY, double descentY) {}

    private static Guides guides(Map<String, Object> m) {
        return new Guides(
                JxJson.has(m, "baselineY") ? JxJson.dbl(m, "baselineY") : 0,
                JxJson.has(m, "capY")      ? JxJson.dbl(m, "capY")      : 0,
                JxJson.has(m, "ascentY")   ? JxJson.dbl(m, "ascentY")   : 0,
                JxJson.has(m, "xY")        ? JxJson.dbl(m, "xY")        : 0,
                JxJson.has(m, "descentY")  ? JxJson.dbl(m, "descentY")  : 0);
    }

    private static BufferedImage decodePng(String dataUrl) throws IOException {
        if (dataUrl == null || dataUrl.isBlank()) return null;
        int comma = dataUrl.indexOf(',');
        String b64 = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
        return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(b64.trim())));
    }

    private static String orDefault(String v, String def) { return v == null || v.isBlank() ? def : v; }

    /** Font id slug: keep letters/digits, drop the rest, never empty. */
    public static String slug(String s) {
        String t = (s == null ? "" : s).replaceAll("[^A-Za-z0-9]+", "");
        return t.isEmpty() ? "MyHand" : t;
    }
}
