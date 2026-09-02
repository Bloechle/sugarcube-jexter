package sugarcube.jexter.write;

import sugarcube.jexter.convert.ConvertOptions;
import sugarcube.jexter.core.JxColor;
import sugarcube.jexter.core.JxLog;
import sugarcube.jexter.font.JxFont;
import sugarcube.jexter.core.JxName;
import sugarcube.jexter.core.JxNum;
import sugarcube.jexter.core.JxPath;
import sugarcube.jexter.core.JxText;
import sugarcube.jexter.core.JxRect;
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

    public static String render(OCDDocument doc, OCDPage page) { return render(doc, page, true); }

    public static String render(OCDDocument doc, OCDPage page, boolean annotations) {
        return render(doc, page, annotations, FontSrc.LINKED);
    }

    public static String render(OCDDocument doc, OCDPage page, boolean annotations, FontSrc fontSrc) {
        double w = page.displayWidth(), h = page.displayHeight();         // visual size (rotation-swapped) → viewBox
        double ch = page.effectiveBox().height();           // unrotated content height (flip axis)
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
          .append("viewBox=\"0 0 ").append(f(w)).append(' ').append(f(h)).append("\" ")
          .append("width=\"").append(f(w)).append("\" height=\"").append(f(h)).append("\">\n");
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
        double cw = page.effectiveBox().width();
        String rot = switch (page.rotation()) {
            case 90  -> "translate(" + f(ch) + ",0) rotate(90)";
            case 180 -> "translate(" + f(cw) + ',' + f(ch) + ") rotate(180)";
            case 270 -> "translate(0," + f(cw) + ") rotate(270)";
            default  -> null;
        };
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
            case OCDPath p  -> path(sb, page, p, styles, clips, grads);
            case OCDImage im -> image(sb, page, im, clips);
            case OCDMedia m -> {
                if (m instanceof OCDVideo v && v.poster() != null) {
                    OCDImage poster = new OCDImage(v.poster());
                    poster.transform(m.transform());
                    image(sb, page, poster, clips);
                }
            }
            case OCDGroup g -> { for (OCDNode c : OCDNode.inPaintOrder(g.children())) node(sb, doc, page, c, styles, faces, clips, grads, fontSrc); }
            default -> { }
        }
        if (!open.isEmpty()) sb.append(CLIP_CLOSE);
    }

    // ── Text: one <text> per run, placed via pageFlip · transform · flipY ─────

    private static void text(StringBuilder sb, OCDDocument doc, OCDPage page, OCDText t,
                             Map<String, String> styles, Map<String, String> faces, FontSrc fontSrc) {
        if (t.isInvisible() || t.glyphs().isEmpty()) return;

        OCDFont df = doc.findFont(t.fontId());
        Map<Integer, Integer> rev = df != null ? df.reverseCmap() : Map.of();

        var xs = new StringBuilder();
        var chars = new StringBuilder();
        for (OCDText.Glyph g : t.glyphs()) {
            Integer cp = rev.get(g.gid());
            String ch = cp != null ? String.valueOf((char) (int) cp)
                                    : (g.unicode() != null && !g.unicode().isEmpty() ? g.unicode() : null);
            if (ch == null) continue;
            if (!xs.isEmpty()) xs.append(' ');
            xs.append(f(g.x()));
            esc(chars, ch);
        }
        if (chars.isEmpty()) return;

        String fam = JxName.safe(t.fontId());
        ensureFace(doc, t.fontId(), faces, fontSrc);

        JxTransform flip  = pageFlip(page);
        JxTransform flipY = new JxTransform(1, 0, 0, -1, 0, 0);
        JxTransform T = flip.concat(t.transform()).concat(flipY);

        boolean stroked = t.hasStrokePaint();
        boolean filled  = t.hasFill() || !stroked;     // mode 1 (stroke-only) → fill:none, like the renderer
        var css = new StringBuilder();
        if (filled) {
            css.append("fill:").append(rgb(t.fill()));
            float a = new JxColor(t.fill()).alpha();
            if (a < 1f) css.append(";fill-opacity:").append(f(a));
        } else {
            css.append("fill:none");
        }
        if (stroked) {
            css.append(";stroke:").append(rgb(t.stroke()));
            if (t.strokeWidth() > 0) css.append(";stroke-width:").append(f(t.strokeWidth()));
            if (t.cap() == 1) css.append(";stroke-linecap:round");
            else if (t.cap() == 2) css.append(";stroke-linecap:square");
            if (t.join() == 1) css.append(";stroke-linejoin:round");
            else if (t.join() == 2) css.append(";stroke-linejoin:bevel");
            else if (t.miterLimit() > 0 && t.miterLimit() != 10) css.append(";stroke-miterlimit:").append(f(t.miterLimit()));
            if (t.hasDash()) {
                css.append(";stroke-dasharray:");
                double[] dash = t.dash();
                for (int i = 0; i < dash.length; i++) { if (i > 0) css.append(','); css.append(f(dash[i])); }
                if (t.dashPhase() > 0) css.append(";stroke-dashoffset:").append(f(t.dashPhase()));
            }
            float sa = new JxColor(t.stroke()).alpha();
            if (sa < 1f) css.append(";stroke-opacity:").append(f(sa));
        }
        if (t.hasBlend() && !t.blend().equalsIgnoreCase("Normal"))
            css.append(";mix-blend-mode:").append(t.blend().toLowerCase(Locale.US));
        String cls = classFor(styles, css.toString());

        sb.append("<text transform=\"matrix(").append(mat(T)).append(")\" ")
          .append("x=\"").append(xs).append("\" y=\"0\" ")
          .append("font-family=\"").append(stack(doc, fam, t.fontId())).append("\" font-size=\"").append(f(t.fontSize())).append("\" ")
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

    // ── Vector path: page-space geometry flipped to SVG, paint via CSS class ──

    static void path(StringBuilder sb, OCDPage page, OCDPath p,
                             Map<String, String> styles, Map<String, String> clips, Map<String, String> grads) {
        path(sb, page, p, styles, clips, grads, null);
    }

    static void path(StringBuilder sb, OCDPage page, OCDPath p,
                             Map<String, String> styles, Map<String, String> clips, Map<String, String> grads, String id) {
        if (p.geometry() == null) return;
        String d = p.geometry().toSvg();
        if (d == null || d.isBlank()) return;

        var css = new StringBuilder();
        if (p.hasGradient()) {
            css.append("fill:").append(gradFill(p.fillGradient(), grads));
            if (p.isEvenOdd()) css.append(";fill-rule:evenodd");
        } else if (p.isFilled()) {
            css.append("fill:").append(rgb(p.fill()));
            if (p.isEvenOdd()) css.append(";fill-rule:evenodd");
            float fa = new JxColor(p.fill()).alpha();
            if (fa < 1f) css.append(";fill-opacity:").append(f(fa));
        } else {
            css.append("fill:none");
        }
        if (p.isStroked()) {
            css.append(";stroke:").append(rgb(p.stroke()));
            if (p.strokeWidth() > 0) css.append(";stroke-width:").append(f(p.strokeWidth()));
            if (p.cap() == 1) css.append(";stroke-linecap:round");
            else if (p.cap() == 2) css.append(";stroke-linecap:square");
            if (p.join() == 1) css.append(";stroke-linejoin:round");
            else if (p.join() == 2) css.append(";stroke-linejoin:bevel");
            else if (p.miterLimit() > 0 && p.miterLimit() != 10) css.append(";stroke-miterlimit:").append(f(p.miterLimit()));
            if (p.hasDash()) {
                css.append(";stroke-dasharray:");
                double[] dash = p.dash();
                for (int i = 0; i < dash.length; i++) { if (i > 0) css.append(','); css.append(f(dash[i])); }
                if (p.dashPhase() > 0) css.append(";stroke-dashoffset:").append(f(p.dashPhase()));
            }
            float sa = new JxColor(p.stroke()).alpha();
            if (sa < 1f) css.append(";stroke-opacity:").append(f(sa));
        }
        if (p.hasBlend() && !p.blend().equalsIgnoreCase("Normal"))
            css.append(";mix-blend-mode:").append(p.blend().toLowerCase(Locale.US));

        String cls = classFor(styles, css.toString());
        sb.append("<path ");
        if (id != null) sb.append("id=\"").append(id).append("\" ");
        sb.append("d=\"").append(d).append("\" class=\"").append(cls).append('"');
        sb.append(" transform=\"matrix(").append(pageFlipM(page)).append(")\"/>\n");
    }

    // ── Image: placed via its CTM (unit square → page), honoring rotation/shear ──

    /** THE clip-id authority of a written page: registers {@code clipId}'s geometry in {@code clips} and
     *  returns the id to reference, or {@code null} when the node carries no resolvable clip.
     *
     *  <p><b>The emitted id IS the model id</b>, minted once at import. A presentation id minted per
     *  {@code <defs>} entry would be a second naming authority, and every reference written from the model
     *  side would dangle.
     *
     *  <p><b>The stored geometry is in SVG space</b> — the page flip is folded in. Everything else in the
     *  page is already stored flipped (a run's matrix included), so a page-space clip was the one coordinate
     *  system out of step: it forced the referencing element to carry the flip and nothing else, which a text
     *  run cannot do (its matrix folds in the font size and its own CTM). Folded here, the clip applies in
     *  the space the content already lives in and a BARE wrapper carries it, for every node kind alike.
     *  {@code OCDReader} unfolds it (the flip is self-inverse); the round-trip is exact because
     *  {@link JxPath} is double-backed. */
    static String clipRef(OCDPage page, String clipId, Map<String, String> clips) {
        if (clipId == null || clipId.isEmpty()) return null;
        OCDClip c = page.clip(clipId);
        if (c == null || c.path() == null) return null;
        clips.putIfAbsent(clipId, new JxPath(pageFlip(page).awt().createTransformedShape(c.path())).toSvg());
        return clipId;
    }

    /** Open the clip wrapper for a node, or {@code ""} when it has none.
     *
     *  <p>A node's own matrix is in SVG space and folds in whatever it likes (a text run folds in the font
     *  size and its CTM), so the clip cannot ride on the node itself — it rides on a wrapper. The wrapper
     *  needs no transform: {@link #clipRef} stores the def in SVG space, the very space the wrapper sits in.
     *  One rule, every node kind alike, and the wrapped node serializes byte-identically to its unclipped
     *  form. */
    static String clipOpen(OCDPage page, OCDNode n, Map<String, String> clips, boolean typed) {
        String cid = clipRef(page, n.clipId(), clips);
        if (cid == null) return "";
        return "<g " + (typed ? "data-ocd=\"clip\" data-ref=\"" + cid + "\" " : "")
             + "clip-path=\"url(#" + cid + ")\">\n";
    }

    static final String CLIP_CLOSE = "</g>\n";

    static void image(StringBuilder sb, OCDPage page, OCDImage im, Map<String, String> clips) {
        image(sb, page, im, clips, null);
    }

    static void image(StringBuilder sb, OCDPage page, OCDImage im, Map<String, String> clips, String id) {
        JxTransform t = im.transform();
        String href = "../images/" + JxName.safe(im.resourceRef());

        // unit square [0,1]² → page space (Y-up), image upright (row 0 at the top)
        String place;
        if (t == null || t.isIdentity()) {
            JxRect b = im.bounds();
            if (b == null || b.isEmpty()) return;
            place = "matrix(" + f(b.width()) + " 0 0 " + f(-b.height()) + " " + f(b.x()) + " " + f(b.y() + b.height()) + ")";
        } else {
            place = "matrix(" + mat(t) + ") translate(0 1) scale(1 -1)";
        }
        String tail = imgTail(im);

        // An image only ever states its own placement: a clip rides on the wrapper opened by node().
        String idAttr = id != null ? "id=\"" + id + "\" " : "";
            sb.append("<image ").append(idAttr).append("xlink:href=\"").append(href).append("\" x=\"0\" y=\"0\" width=\"1\" height=\"1\" ")
              .append("preserveAspectRatio=\"none\" transform=\"matrix(").append(pageFlipM(page)).append(") ")
              .append(place).append('"').append(tail).append("/>\n");
    }

    private static String imgTail(OCDImage im) {
        var s = new StringBuilder();
        if (im.alpha() < 1f) s.append(" opacity=\"").append(f(im.alpha())).append('"');
        if (im.hasBlend() && !im.blend().equalsIgnoreCase("Normal"))
            s.append(" style=\"mix-blend-mode:").append(im.blend().toLowerCase(Locale.US)).append('"');
        return s.toString();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    static String classFor(Map<String, String> styles, String css) {
        return styles.computeIfAbsent(css, k -> "s" + styles.size());
    }

    /** Register a gradient in the defs map (dedup by content) and return a {@code url(#id)} ref. */
    static String gradFill(OCDGradient g, Map<String, String> grads) {
        String def = gradientDef(g);                       // carries an "__ID__" placeholder
        String id  = grads.computeIfAbsent(def, k -> "grad" + grads.size());
        return "url(#" + id + ")";
    }

    /** SVG {@code <linear/radialGradient>} for an OCDGradient, with an {@code __ID__} placeholder the
     *  caller replaces with the assigned id. Coords are userSpaceOnUse (page space), with the
     *  gradient→page matrix as gradientTransform; {@code pad} matches the renderer's NO_CYCLE. */
    private static String gradientDef(OCDGradient g) {
        double[] c = g.coords();
        String head = " id=\"__ID__\" gradientUnits=\"userSpaceOnUse\" gradientTransform=\"matrix("
                + mat(g.transform()) + ")\" spreadMethod=\"pad\"";
        var s = new StringBuilder();
        if (g.isLinear())
            s.append("<linearGradient").append(head)
             .append(" x1=\"").append(f(c[0])).append("\" y1=\"").append(f(c[1]))
             .append("\" x2=\"").append(f(c[2])).append("\" y2=\"").append(f(c[3])).append("\">");
        else
            s.append("<radialGradient").append(head)
             .append(" cx=\"").append(f(c[3])).append("\" cy=\"").append(f(c[4])).append("\" r=\"").append(f(c[5]))
             .append("\" fx=\"").append(f(c[0])).append("\" fy=\"").append(f(c[1])).append("\">");
        float[] off = g.offsets();
        int[]   col = g.colors();
        for (int i = 0; i < off.length; i++) {
            float a = new JxColor(col[i]).alpha();
            s.append("<stop offset=\"").append(f(off[i])).append("\" stop-color=\"").append(rgb(col[i])).append('"');
            if (a < 1f) s.append(" stop-opacity=\"").append(f(a)).append('"');
            s.append("/>");
        }
        s.append(g.isLinear() ? "</linearGradient>" : "</radialGradient>");
        return s.toString();
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
                case HIGHLIGHT -> { for (JxRect r : rs) sb.append("<rect x=\"").append(f(r.x())).append("\" y=\"").append(f(r.y()))
                        .append("\" width=\"").append(f(r.width())).append("\" height=\"").append(f(r.height()))
                        .append("\" fill=\"").append(col).append("\" fill-opacity=\".4\" transform=\"").append(flip).append("\"/>"); }
                case UNDERLINE -> { for (JxRect r : rs) line(sb, r.x(), r.y() + 0.6, r.x() + r.width(), r.y() + 0.6, col, 1, flip); }
                case STRIKEOUT -> { for (JxRect r : rs) { double m = r.y() + r.height() / 2; line(sb, r.x(), m, r.x() + r.width(), m, col, 1, flip); } }
                case SQUIGGLY  -> { for (JxRect r : rs) sb.append("<polyline points=\"").append(zig(r.x(), r.x() + r.width(), r.y() + 1.0, 1.4))
                        .append("\" fill=\"none\" stroke=\"").append(col).append("\" stroke-width=\".8\" transform=\"").append(flip).append("\"/>"); }
                case NOTE      -> { if (a.rect() != null) noteIcon(sb, a.rect(), col, flip); }
                default        -> { if (a.rect() != null) { JxRect r = a.rect(); sb.append("<rect x=\"").append(f(r.x())).append("\" y=\"").append(f(r.y()))
                        .append("\" width=\"").append(f(r.width())).append("\" height=\"").append(f(r.height()))
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
            sb.append("<rect x=\"").append(f(r.x())).append("\" y=\"").append(f(r.y()))
              .append("\" width=\"").append(f(r.width())).append("\" height=\"").append(f(r.height()))
              .append("\" rx=\"").append(f(rad))
              .append("\" fill=\"").append(btn ? FieldStyle.FACE_HEX : FieldStyle.SURFACE_HEX).append('"');
            if (!btn) sb.append(" stroke=\"").append(FieldStyle.BORDER_HEX)
                        .append("\" stroke-width=\"").append(f(FieldStyle.BORDER_W)).append('"');
            sb.append(" transform=\"").append(flip).append("\"/>");
            if (fld.type() == OCDFormField.Field.CHECKBOX) {
                if (on) {
                    double[] t = FieldStyle.tick(r);
                    sb.append("<path d=\"M").append(f(t[0])).append(' ').append(f(t[1]))
                      .append('L').append(f(t[2])).append(' ').append(f(t[3]))
                      .append('L').append(f(t[4])).append(' ').append(f(t[5]))
                      .append("\" fill=\"none\" stroke=\"").append(FieldStyle.INK_HEX)
                      .append("\" stroke-width=\"").append(f(FieldStyle.TICK_W))
                      .append("\" stroke-linecap=\"round\" stroke-linejoin=\"round\" transform=\"").append(flip).append("\"/>");
                }
            } else if (fld.type() == OCDFormField.Field.RADIO) {
                if (on) {                                              // one of a set is CHOSEN — a dot, not a tick
                    double[] c = FieldStyle.dot(r);
                    sb.append("<circle cx=\"").append(f(c[0])).append("\" cy=\"").append(f(c[1]))
                      .append("\" r=\"").append(f(c[2])).append("\" fill=\"").append(FieldStyle.INK_HEX)
                      .append("\" transform=\"").append(flip).append("\"/>");
                }
            } else if (fld.type() == OCDFormField.Field.SIGNATURE) {
                double[] l = FieldStyle.rule(r);                       // a place to sign, not an empty input
                line(sb, l[0], l[1], l[2], l[3], FieldStyle.BORDER_HEX, FieldStyle.BORDER_W, flip);
            } else {
                if (fld.type() == OCDFormField.Field.CHOICE) {         // the one mark that says "a closed list"
                    double[] v = FieldStyle.chevron(r);
                    sb.append("<path d=\"M").append(f(v[0])).append(' ').append(f(v[1]))
                      .append('L').append(f(v[2])).append(' ').append(f(v[3]))
                      .append('L').append(f(v[4])).append(' ').append(f(v[5]))
                      .append("\" fill=\"none\" stroke=\"").append(FieldStyle.INK_HEX)
                      .append("\" stroke-width=\"").append(f(FieldStyle.BORDER_W * 1.6))
                      .append("\" stroke-linecap=\"round\" stroke-linejoin=\"round\" transform=\"").append(flip).append("\"/>");
                }
                if (!fld.value().isEmpty()) {
                    double sz = btn ? FieldStyle.buttonTextSize(r) : FieldStyle.textSize(r);
                    boolean mid = btn;                                       // a button's label is centred
                    sb.append("<text x=\"").append(f((mid ? r.x() + r.width() / 2 : r.x() + FieldStyle.PAD_X) - eb.x()))
                      .append("\" y=\"").append(f(top - (btn ? FieldStyle.buttonBaseline(r) : FieldStyle.baseline(r))))
                      .append("\" font-family=\"sans-serif\" font-size=\"").append(f(sz));
                    if (mid) sb.append("\" text-anchor=\"middle");
                    sb.append("\" fill=\"").append(FieldStyle.INK_HEX).append("\">").append(escS(fld.value())).append("</text>");
                }
            }
            sb.append("</g>\n");
        }
    }

    private static void line(StringBuilder sb, double x1, double y1, double x2, double y2, String col, double sw, String flip) {
        sb.append("<line x1=\"").append(f(x1)).append("\" y1=\"").append(f(y1)).append("\" x2=\"").append(f(x2)).append("\" y2=\"").append(f(y2))
          .append("\" stroke=\"").append(col).append("\" stroke-width=\"").append(f(sw)).append("\" transform=\"").append(flip).append("\"/>");
    }
    private static String zig(double x0, double x1, double y, double amp) {
        var p = new StringBuilder(); boolean up = true;
        p.append(f(x0)).append(',').append(f(y));
        for (double x = x0; x < x1; x += 2.0) { p.append(' ').append(f(Math.min(x + 2.0, x1))).append(',').append(f(y + (up ? amp : -amp))); up = !up; }
        return p.toString();
    }
    private static void noteIcon(StringBuilder sb, JxRect r, String col, String flip) {
        double s = Math.min(Math.min(r.width(), r.height()) > 0 ? Math.min(r.width(), r.height()) : 14, 16);
        double x = r.x(), y = r.y() + r.height() - s;
        sb.append("<rect x=\"").append(f(x)).append("\" y=\"").append(f(y)).append("\" width=\"").append(f(s)).append("\" height=\"").append(f(s))
          .append("\" rx=\"").append(f(s * 0.28)).append("\" fill=\"").append(col).append("\" stroke=\"#666\" stroke-width=\".5\" transform=\"").append(flip).append("\"/>");
        line(sb, x + s * 0.22, y + s * 0.62, x + s * 0.78, y + s * 0.62, "#666", 0.5, flip);
        line(sb, x + s * 0.22, y + s * 0.40, x + s * 0.78, y + s * 0.40, "#666", 0.5, flip);
    }
    private static String defHex(OCDAnnotation.Markup t) {
        return switch (t) { case HIGHLIGHT -> "#ffeb3c"; case NOTE -> "#ffc850"; default -> "#787878"; };
    }
    private static String escS(String s) { var b = new StringBuilder(); JxText.text(b, s); return b.toString(); }

    static String rgb(int argb) { return new JxColor(argb).rgbHex(); }
    static String mat(JxTransform t) { return t.toMatrix6(); }

    /** page-space (effective-box coords, Y-up) → SVG (Y-down), with the origin moved to the
     *  effective-box corner. Subtracting the crop origin makes a MediaBox≠CropBox page land
     *  exactly like a reader's crop (content is authored in media-box space). */
    static JxTransform pageFlip(OCDPage page) {
        JxRect b = page.effectiveBox();
        return new JxTransform(1, 0, 0, -1, -b.x(), b.y() + b.height());
    }
    static String pageFlipM(OCDPage page) { return pageFlip(page).toMatrix6(); }

    static void esc(StringBuilder sb, String s) { JxText.text(sb, s); }

    static String f(double v) { return JxNum.fmt(v); }
}
