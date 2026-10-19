package sugarcube.jexter.write;

import sugarcube.jexter.core.JxBlend;
import sugarcube.jexter.core.ConvertOptions;
import sugarcube.jexter.core.JxColor;
import sugarcube.jexter.core.JxLog;
import sugarcube.jexter.font.JxFont;
import sugarcube.jexter.core.JxName;
import sugarcube.jexter.core.JxNum;
import sugarcube.jexter.core.JxPath;
import sugarcube.jexter.core.JxText;
import sugarcube.jexter.core.JxRect;
import sugarcube.jexter.core.JxStroke;
import sugarcube.jexter.core.JxTransform;
import sugarcube.jexter.ocd.model.OCDAnnotation;
import sugarcube.jexter.ocd.model.OCDFormField;
import sugarcube.jexter.ocd.render.FieldStyle;
import sugarcube.jexter.ocd.model.OCDClip;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDFont;
import sugarcube.jexter.ocd.model.OCDGroup;
import sugarcube.jexter.ocd.model.OCDGradient;
import sugarcube.jexter.ocd.model.OCDImage;
import sugarcube.jexter.ocd.model.OCDMedia;
import sugarcube.jexter.ocd.model.OCDVideo;
import sugarcube.jexter.ocd.model.OCDNode;
import sugarcube.jexter.ocd.model.OCDPage;
import sugarcube.jexter.ocd.model.OCDPath;
import sugarcube.jexter.ocd.model.OCDText;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import static sugarcube.jexter.core.JxNum.fmt;
import static sugarcube.jexter.ocd.io.SvgPaint.CLIP_CLOSE;
import static sugarcube.jexter.ocd.io.SvgPaint.classFor;
import static sugarcube.jexter.ocd.io.SvgPaint.clipOpen;
import static sugarcube.jexter.ocd.io.SvgPaint.clipRef;
import static sugarcube.jexter.ocd.io.SvgPaint.cssBlend;
import static sugarcube.jexter.ocd.io.SvgPaint.gradFill;
import static sugarcube.jexter.ocd.io.SvgPaint.groupStyle;
import static sugarcube.jexter.ocd.io.SvgPaint.image;
import static sugarcube.jexter.ocd.io.SvgPaint.mat;
import static sugarcube.jexter.ocd.io.SvgPaint.pageFlip;
import static sugarcube.jexter.ocd.io.SvgPaint.pageFlipM;
import static sugarcube.jexter.ocd.io.SvgPaint.path;
import static sugarcube.jexter.ocd.io.SvgPaint.rgb;
import static sugarcube.jexter.ocd.io.SvgPaint.strokeCss;

/**
 * Renders an {@link OCDPage} to a browser-native SVG with REAL selectable text.
 *
 * <p>Text is emitted as {@code <text>} elements in document fonts (embedded as
 * OpenType and referenced via {@code @font-face}), so it is selectable and
 * searchable. Shared paint styles are factored into CSS classes.
 *
 * <p>Coordinates: OCD page space is Y-up. Vector paths (page-space geometry)
 * carry {@code transform="matrix(1 0 0 -1 0 H)"} to flip into SVG (Y-down).
 * A text run's baseline transform maps text space (Y-up) to page space; it is
 * composed as {@code pageFlip · transform · flipY} so the run lands correctly in
 * SVG space and glyphs are not mirrored — this handles rotation/scale exactly.
 * Nodes are emitted in z-order.
 */
public final class SvgWriter {

    private SvgWriter() {}

    /** Where a page's {@code @font-face} finds its font.
     *
     *  <p>The same renderer serves two containers and they cannot share one answer: inside a fixed-layout
     *  EPUB the pages sit in {@code OEBPS/pages/} beside a real {@code OEBPS/fonts/}, so a relative URL is
     *  right and repeating the font bytes on every page would be ruinous. The standalone {@code .svg} target
     *  writes ONE file and nothing else, so the same URL points at a directory that will never exist — the
     *  browser silently falls back to a default serif with its own advances, and the page renders in the
     *  wrong font with the right glyph positions. A single file has to carry its fonts. */
    public enum FontSrc { LINKED, EMBEDDED }

    /** Uniform projection: the single page {@link ConvertOptions#PAGE} as standalone SVG (UTF-8). */
    public static void write(OCDDocument doc, OutputStream out, ConvertOptions opt) throws IOException {
        int i = opt.get(ConvertOptions.PAGE);
        if (i < 0 || i >= doc.pageCount())
            throw new IllegalArgumentException("page " + i + " out of range (0.." + (doc.pageCount() - 1) + ")");
        // standalone: nothing is written beside it, so the fonts ride inside
        out.write(render(doc, doc.page(i), opt.get(ConvertOptions.RENDER_ANNOTATIONS), FontSrc.EMBEDDED)
                .getBytes(StandardCharsets.UTF_8));
    }

    public static String render(OCDDocument doc, OCDPage page, boolean annotations) {
        return render(doc, page, annotations, FontSrc.LINKED);
    }

    public static String render(OCDDocument doc, OCDPage page, boolean annotations, FontSrc fontSrc) {
        double w = page.displayWidth(), h = page.displayHeight();         // visual size (rotation-swapped) → viewBox
        var styles = new LinkedHashMap<String, String>();   // css decl  -> class name
        var faces  = new LinkedHashMap<String, String>();   // font family -> @font-face
        var clips  = new LinkedHashMap<String, String>();   // MODEL clip id -> clip path-data (see clipRef)
        var grads  = new LinkedHashMap<String, String>();   // gradient def -> gradient id
        var body   = new StringBuilder(8192);

        for (OCDNode node : OCDNode.inPaintOrder(page.content())) node(body, doc, page, node, styles, faces, clips, grads, fontSrc);
        if (annotations) annotations(body, page);

        var sb = new StringBuilder(body.length() + 1024);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" ")
          .append("viewBox=\"").append(viewBox(page)).append("\" ")
          .append("width=\"").append(fmt(w)).append("\" height=\"").append(fmt(h)).append("\">\n");
        sb.append("<style>\n");
        for (var src : faces.values()) sb.append(src).append('\n');
        for (var e : styles.entrySet()) sb.append('.').append(e.getValue()).append('{').append(e.getKey()).append("}\n");
        sb.append("</style>\n");
        if (!clips.isEmpty() || !grads.isEmpty()) {
            sb.append("<defs>\n");
            for (var e : clips.entrySet())
                sb.append("<clipPath id=\"").append(e.getKey()).append("\" clipPathUnits=\"userSpaceOnUse\">")
                  .append("<path d=\"").append(e.getValue()).append("\"/></clipPath>\n");
            for (var e : grads.entrySet())
                sb.append(e.getKey().replace("__ID__", e.getValue())).append('\n');
            sb.append("</defs>\n");
        }
        // page /Rotate: content is laid out in the unrotated frame; rotate it (clockwise) into the visual viewBox
        String rot = rotation(page);
        if (rot != null) sb.append("<g transform=\"").append(rot).append("\">\n").append(body).append("</g>\n");
        else sb.append(body);
        sb.append("</svg>\n");
        return sb.toString();
    }

    private static void node(StringBuilder sb, OCDDocument doc, OCDPage page, OCDNode n,
                             Map<String, String> styles, Map<String, String> faces,
                             Map<String, String> clips, Map<String, String> grads, FontSrc fontSrc) {
        // ONE clip rule, every node kind alike — see clipOpen. Nothing below ever emits a clip of its own.
        String open = clipOpen(page, n, clips, false);
        sb.append(open);
        switch (n) {
            case OCDText t  -> text(sb, doc, page, t, styles, faces, fontSrc);
            case OCDPath p  -> path(sb, page, p, styles, clips, grads, null);
            case OCDImage im -> image(sb, page, im, clips, null);
            case OCDMedia m -> {
                if (m instanceof OCDVideo v && v.poster() != null) {
                    OCDImage poster = new OCDImage(v.poster());
                    poster.transform(m.transform());
                    image(sb, page, poster, clips, null);
                }
            }
            case OCDGroup g -> {
                String gs = groupStyle(g);
                if (!gs.isEmpty()) sb.append("<g").append(gs).append(">\n");
                for (OCDNode c : OCDNode.inPaintOrder(g.children())) node(sb, doc, page, c, styles, faces, clips, grads, fontSrc);
                if (!gs.isEmpty()) sb.append("</g>\n");
            }
            default -> { }
        }
        if (!open.isEmpty()) sb.append(CLIP_CLOSE);
    }

    // ── Text: one <text> per run, placed via pageFlip · transform · flipY ─────

    private static void text(StringBuilder sb, OCDDocument doc, OCDPage page, OCDText t,
                             Map<String, String> styles, Map<String, String> faces, FontSrc fontSrc) {
        if (t.glyphs().isEmpty()) return;

        OCDFont df = doc.findFont(t.fontId());
        Map<Integer, Integer> rev = df != null ? df.reverseCmap() : Map.of();

        var xs = new StringBuilder();
        var chars = new StringBuilder();
        for (OCDText.Glyph g : t.glyphs()) {
            Integer cp = rev.get(g.gid());
            String ch = cp != null ? Character.toString(cp)
                                    : (g.unicode() != null && !g.unicode().isEmpty() ? g.unicode() : null);
            if (ch == null) continue;
            // SVG positions each CHARACTER by the next x: a glyph that maps to several ("fi" kept as a ligature,
            // a character beyond the BMP) gets one x per code point, all at the glyph's origin — one x for two
            // once pushed every later glyph of the run one slot right.
            for (int k = ch.codePointCount(0, ch.length()); k > 0; k--) {
                if (!xs.isEmpty()) xs.append(' ');
                xs.append(fmt(g.x()));
            }
            esc(chars, ch);
        }
        if (chars.isEmpty()) return;

        String fam = JxName.safe(t.fontId());
        ensureFace(doc, t.fontId(), faces, fontSrc);

        JxTransform flip  = pageFlip(page);
        JxTransform flipY = new JxTransform(1, 0, 0, -1, 0, 0);
        JxTransform T = flip.concat(t.transform()).concat(flipY);

        // An INVISIBLE run (a scan's OCR layer) is real text with no ink: selectable and searchable, painted
        // at zero opacity — fill:none would also hide it, but leaves some viewers nothing to hit-test.
        boolean invisible = t.isInvisible();
        boolean stroked = t.paintsStroke();
        boolean filled  = t.paintsFill();
        var css = new StringBuilder();
        if (invisible) {
            css.append("fill:#000000;fill-opacity:0");
        } else if (filled) {
            css.append("fill:").append(rgb(t.fill()));
            float a = new JxColor(t.fill()).alpha();
            if (a < 1f) css.append(";fill-opacity:").append(fmt(a));
        } else {
            css.append("fill:none");
        }
        if (stroked) strokeCss(css, t.stroke(), t.pen(), 1);
        if (t.hasBlend() && !t.blend().equalsIgnoreCase("Normal"))
            css.append(";mix-blend-mode:").append(cssBlend(t.blend()));
        String cls = classFor(styles, css.toString());

        sb.append("<text transform=\"matrix(").append(mat(T)).append(")\" ")
          .append("x=\"").append(xs).append("\" y=\"0\" ")
          .append("font-family=\"").append(stack(doc, fam, t.fontId())).append("\" font-size=\"").append(fmt(t.fontSize())).append("\" ")
          .append("class=\"").append(cls).append("\">").append(chars).append("</text>\n");
    }

    /** THE font stack: the face, then the generic it belongs to.
     *
     *  <p>A stack of one is a bet that the face will load, and a face can always fail — bytes that will not
     *  compile, a container written without its fonts, a viewer that refuses the format. The browser then
     *  falls back to ITS default, a serif whatever the document was, and repaints the page in the wrong
     *  family with the right glyph positions: letters adrift in their own advances, the disfigurement that
     *  looks like a bug in the engine. The generic comes from the FONT, never from a guess — the producer's
     *  own {@code /Flags} classification — so the miss stays a near miss. */
    private static String stack(OCDDocument doc, String fam, String fontId) {
        OCDFont df = doc.findFont(fontId);
        String generic = df == null ? "sans-serif" : df.isMono() ? "monospace" : df.isSerif() ? "serif" : "sans-serif";
        return fam + "," + generic;
    }

    private static void ensureFace(OCDDocument doc, String fontId, Map<String, String> faces, FontSrc src) {
        OCDFont df = doc.findFont(fontId);
        String fam = JxName.safe(df != null ? df.id() : fontId);
        faces.computeIfAbsent(fam, k -> "@font-face{font-family:\"" + k + "\";src:url(\"" + url(df, k, src)
                + "\") format(\"opentype\");}");
    }

    /** The {@code src} of a face: a sibling file, or the font itself. A face whose bytes cannot be compiled
     *  keeps the relative URL — a dangling link is no worse than an empty one, and the glyph positions still
     *  hold. */
    private static String url(OCDFont df, String fam, FontSrc src) {
        if (src == FontSrc.EMBEDDED && df != null) {
            try {
                byte[] otf = JxFont.toOtf(df);
                if (otf != null && otf.length > 0)
                    return "data:font/otf;base64," + java.util.Base64.getEncoder().encodeToString(otf);
            } catch (Exception e) {
                JxLog.debug(SvgWriter.class, "font otf compile failed: " + fam, e);
            }
        }
        return "../fonts/" + fam + ".otf";
    }

    // ── annotation layer (geometry-faithful overlay, painted above content) ──────
    static void annotations(StringBuilder sb, OCDPage page) {
        JxRect eb = page.effectiveBox();
        double top = eb.y() + eb.height();          // page-space y of the effective-box top edge
        String flip = "matrix(" + pageFlipM(page) + ")";
        for (OCDAnnotation a : page.annotations()) {
            String col = a.color() != null ? rgb(a.color().argb()) : defHex(a.type());
            var rs = !a.quads().isEmpty() ? a.quads()
                    : (a.rect() != null ? java.util.List.of(a.rect()) : java.util.List.<JxRect>of());
            sb.append("<g>");
            if (!a.contents().isEmpty() || !a.author().isEmpty())
                sb.append("<title>").append(escS((a.author().isEmpty() ? "" : a.author() + ": ") + a.contents())).append("</title>");
            switch (a.type()) {
                case HIGHLIGHT -> { for (JxRect r : rs) sb.append("<rect x=\"").append(fmt(r.x())).append("\" y=\"").append(fmt(r.y()))
                        .append("\" width=\"").append(fmt(r.width())).append("\" height=\"").append(fmt(r.height()))
                        .append("\" fill=\"").append(col).append("\" fill-opacity=\".4\" transform=\"").append(flip).append("\"/>"); }
                case UNDERLINE -> { for (JxRect r : rs) line(sb, r.x(), r.y() + 0.6, r.x() + r.width(), r.y() + 0.6, col, 1, flip); }
                case STRIKEOUT -> { for (JxRect r : rs) { double m = r.y() + r.height() / 2; line(sb, r.x(), m, r.x() + r.width(), m, col, 1, flip); } }
                case SQUIGGLY  -> { for (JxRect r : rs) sb.append("<polyline points=\"").append(zig(r.x(), r.x() + r.width(), r.y() + 1.0, 1.4))
                        .append("\" fill=\"none\" stroke=\"").append(col).append("\" stroke-width=\".8\" transform=\"").append(flip).append("\"/>"); }
                case NOTE      -> { if (a.rect() != null) noteIcon(sb, a.rect(), col, flip); }
                default        -> { if (a.rect() != null) { JxRect r = a.rect(); sb.append("<rect x=\"").append(fmt(r.x())).append("\" y=\"").append(fmt(r.y()))
                        .append("\" width=\"").append(fmt(r.width())).append("\" height=\"").append(fmt(r.height()))
                        .append("\" fill=\"none\" stroke=\"").append(col).append("\" stroke-width=\".6\" stroke-dasharray=\"2 2\" transform=\"").append(flip).append("\"/>"); } }
            }
            sb.append("</g>\n");
        }
        for (OCDFormField fld : page.fields()) {
            JxRect r = fld.rect(); if (r == null) continue;
            boolean on  = fld.isOn();   // the field knows; a writer must not re-decide it
            boolean btn = fld.type() == OCDFormField.Field.BUTTON;
            double rad  = fld.type() == OCDFormField.Field.RADIO ? FieldStyle.radioRadius(r)
                        : btn ? FieldStyle.buttonRadius(r) : FieldStyle.radius(r);
            sb.append("<g><title>").append(escS(fld.name() + (fld.value().isEmpty() ? "" : " = " + fld.value()))).append("</title>");
            // ONE field appearance (FieldStyle) — flat surface, hairline border, no bevel. The surface is
            // the same checked or not: the state is carried by the ink, exactly as a value is in a text field.
            sb.append("<rect x=\"").append(fmt(r.x())).append("\" y=\"").append(fmt(r.y()))
              .append("\" width=\"").append(fmt(r.width())).append("\" height=\"").append(fmt(r.height()))
              .append("\" rx=\"").append(fmt(rad))
              .append("\" fill=\"").append(btn ? FieldStyle.FACE_HEX : FieldStyle.SURFACE_HEX).append('"');
            if (!btn) sb.append(" stroke=\"").append(FieldStyle.BORDER_HEX)
                        .append("\" stroke-width=\"").append(fmt(FieldStyle.BORDER_W)).append('"');
            sb.append(" transform=\"").append(flip).append("\"/>");
            if (fld.type() == OCDFormField.Field.CHECKBOX) {
                if (on) {
                    double[] t = FieldStyle.tick(r);
                    sb.append("<path d=\"M").append(fmt(t[0])).append(' ').append(fmt(t[1]))
                      .append('L').append(fmt(t[2])).append(' ').append(fmt(t[3]))
                      .append('L').append(fmt(t[4])).append(' ').append(fmt(t[5]))
                      .append("\" fill=\"none\" stroke=\"").append(FieldStyle.INK_HEX)
                      .append("\" stroke-width=\"").append(fmt(FieldStyle.TICK_W))
                      .append("\" stroke-linecap=\"round\" stroke-linejoin=\"round\" transform=\"").append(flip).append("\"/>");
                }
            } else if (fld.type() == OCDFormField.Field.RADIO) {
                if (on) {                                              // one of a set is CHOSEN — a dot, not a tick
                    double[] c = FieldStyle.dot(r);
                    sb.append("<circle cx=\"").append(fmt(c[0])).append("\" cy=\"").append(fmt(c[1]))
                      .append("\" r=\"").append(fmt(c[2])).append("\" fill=\"").append(FieldStyle.INK_HEX)
                      .append("\" transform=\"").append(flip).append("\"/>");
                }
            } else if (fld.type() == OCDFormField.Field.SIGNATURE) {
                double[] l = FieldStyle.rule(r);                       // a place to sign, not an empty input
                line(sb, l[0], l[1], l[2], l[3], FieldStyle.BORDER_HEX, FieldStyle.BORDER_W, flip);
            } else {
                if (fld.type() == OCDFormField.Field.CHOICE) {         // the one mark that says "a closed list"
                    double[] v = FieldStyle.chevron(r);
                    sb.append("<path d=\"M").append(fmt(v[0])).append(' ').append(fmt(v[1]))
                      .append('L').append(fmt(v[2])).append(' ').append(fmt(v[3]))
                      .append('L').append(fmt(v[4])).append(' ').append(fmt(v[5]))
                      .append("\" fill=\"none\" stroke=\"").append(FieldStyle.INK_HEX)
                      .append("\" stroke-width=\"").append(fmt(FieldStyle.BORDER_W * 1.6))
                      .append("\" stroke-linecap=\"round\" stroke-linejoin=\"round\" transform=\"").append(flip).append("\"/>");
                }
                if (!fld.value().isEmpty()) {
                    double sz = btn ? FieldStyle.buttonTextSize(r) : FieldStyle.textSize(r);
                    boolean mid = btn;                                       // a button's label is centred
                    sb.append("<text x=\"").append(fmt((mid ? r.x() + r.width() / 2 : r.x() + FieldStyle.PAD_X) - eb.x()))
                      .append("\" y=\"").append(fmt(top - (btn ? FieldStyle.buttonBaseline(r) : FieldStyle.baseline(r))))
                      .append("\" font-family=\"sans-serif\" font-size=\"").append(fmt(sz));
                    if (mid) sb.append("\" text-anchor=\"middle");
                    sb.append("\" fill=\"").append(FieldStyle.INK_HEX).append("\">").append(escS(fld.value())).append("</text>");
                }
            }
            sb.append("</g>\n");
        }
    }

    private static void line(StringBuilder sb, double x1, double y1, double x2, double y2, String col, double sw, String flip) {
        sb.append("<line x1=\"").append(fmt(x1)).append("\" y1=\"").append(fmt(y1)).append("\" x2=\"").append(fmt(x2)).append("\" y2=\"").append(fmt(y2))
          .append("\" stroke=\"").append(col).append("\" stroke-width=\"").append(fmt(sw)).append("\" transform=\"").append(flip).append("\"/>");
    }
    private static String zig(double x0, double x1, double y, double amp) {
        var p = new StringBuilder(); boolean up = true;
        p.append(fmt(x0)).append(',').append(fmt(y));
        for (double x = x0; x < x1; x += 2.0) { p.append(' ').append(fmt(Math.min(x + 2.0, x1))).append(',').append(fmt(y + (up ? amp : -amp))); up = !up; }
        return p.toString();
    }
    private static void noteIcon(StringBuilder sb, JxRect r, String col, String flip) {
        double s = Math.min(Math.min(r.width(), r.height()) > 0 ? Math.min(r.width(), r.height()) : 14, 16);
        double x = r.x(), y = r.y() + r.height() - s;
        sb.append("<rect x=\"").append(fmt(x)).append("\" y=\"").append(fmt(y)).append("\" width=\"").append(fmt(s)).append("\" height=\"").append(fmt(s))
          .append("\" rx=\"").append(fmt(s * 0.28)).append("\" fill=\"").append(col).append("\" stroke=\"#666\" stroke-width=\".5\" transform=\"").append(flip).append("\"/>");
        line(sb, x + s * 0.22, y + s * 0.62, x + s * 0.78, y + s * 0.62, "#666", 0.5, flip);
        line(sb, x + s * 0.22, y + s * 0.40, x + s * 0.78, y + s * 0.40, "#666", 0.5, flip);
    }
    private static String defHex(OCDAnnotation.Markup t) {
        return switch (t) { case HIGHLIGHT -> "#ffeb3c"; case NOTE -> "#ffc850"; default -> "#787878"; };
    }
    private static String escS(String s) { var b = new StringBuilder(); JxText.text(b, s); return b.toString(); }

    /** The root {@code viewBox}: the crop window itself when the page is upright — a crop IS a
     *  window — and the visual frame when the page is rotated, the rotation then carrying the
     *  window's origin ({@link #rotation}). An uncropped page yields {@code "0 0 w h"} exactly as
     *  before, which is what keeps every existing page byte-identical. */
    static String viewBox(OCDPage page) {
        JxRect win = page.svgWindow();
        return page.rotation() != 0
                ? "0 0 " + fmt(page.displayWidth()) + ' ' + fmt(page.displayHeight())
                : fmt(win.x()) + ' ' + fmt(win.y()) + ' ' + fmt(win.width()) + ' ' + fmt(win.height());
    }

    /** The rotation carrier's transform, or {@code null} for an upright page. Content is laid out in
     *  the unrotated media frame, so the window origin is subtracted here — and only when it is not
     *  the origin, so an uncropped rotated page keeps the exact string it had. */
    static String rotation(OCDPage page) {
        JxRect win = page.svgWindow();
        double cw = win.width(), ch = win.height();
        String shift = (win.x() == 0 && win.y() == 0) ? "" : " translate(" + fmt(-win.x()) + ',' + fmt(-win.y()) + ')';
        return switch (page.rotation()) {
            case 90  -> "translate(" + fmt(ch) + ",0) rotate(90)" + shift;
            case 180 -> "translate(" + fmt(cw) + ',' + fmt(ch) + ") rotate(180)" + shift;
            case 270 -> "translate(0," + fmt(cw) + ") rotate(270)" + shift;
            default  -> null;
        };
    }

    static void esc(StringBuilder sb, String s) { JxText.text(sb, s); }

}
