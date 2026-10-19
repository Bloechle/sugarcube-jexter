package sugarcube.jexter.ocd.io;

import sugarcube.jexter.core.JxBlend;
import sugarcube.jexter.core.JxColor;
import sugarcube.jexter.core.JxLog;
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

/**
 * The SVG paint of a page node — the one place a path, an image, a clip, a group's compositing and a pen
 * become SVG, shared by the presentation SVG ({@code write.SvgWriter}) and the OCD-EPUB pages
 * ({@link SvgOcdWriter}), and read back by {@link OCDReader}. Neutral by construction: it states only how a
 * node paints, never how a dialect names or annotates it (a dialect passes its attributes in).
 */
public final class SvgPaint {

    private SvgPaint() {}


    /**
     * The paint a group applies AS ONE — its blend mode and its opacity — as an SVG style, or "" when
     * it has none. On a {@code <g>}, {@code mix-blend-mode} and {@code opacity} composite the group's
     * content first and blend the result: PDF's transparency-group semantics, which the children
     * cannot reproduce one by one (see {@link OCDGroup#compositesAsOne}). Before this, both SVG
     * writers dropped a group's blend and alpha altogether. Shared with {@link SvgOcdWriter}.
     */
    public static String groupStyle(OCDGroup g) {
        StringBuilder css = new StringBuilder();
        if (g.hasBlend() && !g.blend().equalsIgnoreCase("Normal"))
            css.append("mix-blend-mode:").append(cssBlend(g.blend()));
        if (g.alpha() < 1f) css.append(css.isEmpty() ? "" : ";").append("opacity:").append(JxNum.fmt(g.alpha()));
        return css.isEmpty() ? "" : " style=\"" + css + "\"";
    }

    /** The stroke declarations — the one place a pen becomes CSS, for paths, runs and the OCD-EPUB alike.
     *  Lengths are divided by {@code k}: a run drawn through {@code <use>} at its font size states them in em. */
    public static void strokeCss(StringBuilder css, int argb, JxStroke pen, double k) {
        css.append(";stroke:").append(rgb(argb));
        if (pen.width() > 0) css.append(";stroke-width:").append(fmt(pen.width() / k));
        if (pen.cap() != JxStroke.Cap.BUTT) css.append(";stroke-linecap:").append(pen.cap().keyword());
        if (pen.join() != JxStroke.Join.MITER) css.append(";stroke-linejoin:").append(pen.join().keyword());
        if (pen.miterLimit() > 0 && pen.miterLimit() != 10) css.append(";stroke-miterlimit:").append(fmt(pen.miterLimit()));
        if (pen.hasDash()) {
            css.append(";stroke-dasharray:");
            double[] dash = pen.dash();
            for (int i = 0; i < dash.length; i++) { if (i > 0) css.append(','); css.append(fmt(dash[i] / k)); }
            if (pen.dashPhase() > 0) css.append(";stroke-dashoffset:").append(fmt(pen.dashPhase() / k));
        }
        float sa = new JxColor(argb).alpha();
        if (sa < 1f) css.append(";stroke-opacity:").append(fmt(sa));
    }

    /** A model blend name as CSS spells it ({@link JxBlend#css}). */
    public static String cssBlend(String pdfName) { return JxBlend.of(pdfName).css(); }

    // ── Vector path: page-space geometry flipped to SVG, paint via CSS class ──

    /** {@code attrs}: what a dialect writes after the tag name ({@code id="…"} and its data), or null. */
    public static void path(StringBuilder sb, OCDPage page, OCDPath p,
                     Map<String, String> styles, Map<String, String> clips, Map<String, String> grads, String attrs) {
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
            if (fa < 1f) css.append(";fill-opacity:").append(fmt(fa));
        } else {
            css.append("fill:none");
        }
        if (p.isStroked()) strokeCss(css, p.stroke(), p.pen(), 1);
        if (p.hasBlend() && !p.blend().equalsIgnoreCase("Normal"))
            css.append(";mix-blend-mode:").append(cssBlend(p.blend()));

        String cls = classFor(styles, css.toString());
        sb.append("<path ");
        if (attrs != null) sb.append(attrs).append(' ');
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
    public static String clipRef(OCDPage page, String clipId, Map<String, String> clips) {
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
    public static String clipOpen(OCDPage page, OCDNode n, Map<String, String> clips, boolean typed) {
        String cid = clipRef(page, n.clipId(), clips);
        if (cid == null) return "";
        return "<g " + (typed ? "data-ocd=\"clip\" data-clip=\"" + cid + "\" " : "")
             + "clip-path=\"url(#" + cid + ")\">\n";
    }

    public static final String CLIP_CLOSE = "</g>\n";

    /** {@code attrs} as for {@link #path}. */
    public static void image(StringBuilder sb, OCDPage page, OCDImage im, Map<String, String> clips, String attrs) {
        JxTransform t = im.transform();
        String href = "../images/" + JxName.safe(im.resourceRef());

        // unit square [0,1]² → page space (Y-up), image upright (row 0 at the top)
        String place;
        if (t == null || t.isIdentity()) {
            JxRect b = im.bounds();
            if (b == null || b.isEmpty()) return;
            place = "matrix(" + fmt(b.width()) + " 0 0 " + fmt(-b.height()) + " " + fmt(b.x()) + " " + fmt(b.y() + b.height()) + ")";
        } else {
            place = "matrix(" + mat(t) + ") translate(0 1) scale(1 -1)";
        }
        String tail = imgTail(im);

        // An image only ever states its own placement: a clip rides on the wrapper opened by node().
        String idAttr = attrs != null ? attrs + " " : "";
            sb.append("<image ").append(idAttr).append("xlink:href=\"").append(href).append("\" x=\"0\" y=\"0\" width=\"1\" height=\"1\" ")
              .append("preserveAspectRatio=\"none\" transform=\"matrix(").append(pageFlipM(page)).append(") ")
              .append(place).append('"').append(tail).append("/>\n");
    }

    private static String imgTail(OCDImage im) {
        var s = new StringBuilder();
        if (im.alpha() < 1f) s.append(" opacity=\"").append(fmt(im.alpha())).append('"');
        if (im.hasBlend() && !im.blend().equalsIgnoreCase("Normal"))
            s.append(" style=\"mix-blend-mode:").append(cssBlend(im.blend())).append('"');
        return s.toString();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    public static String classFor(Map<String, String> styles, String css) {
        return styles.computeIfAbsent(css, k -> "s" + styles.size());
    }

    /** Register a gradient in the defs map (dedup by content) and return a {@code url(#id)} ref. */
    public static String gradFill(OCDGradient g, Map<String, String> grads) {
        String def = gradientDef(g);                       // carries an "__ID__" placeholder
        String id  = grads.computeIfAbsent(def, k -> "grad" + grads.size());
        return "url(#" + id + ")";
    }

    /** SVG {@code <linear/radialGradient>} for an OCDGradient, with an {@code __ID__} placeholder the
     *  caller replaces with the assigned id. Coords are userSpaceOnUse (page space), with the
     *  gradient→page matrix as gradientTransform; {@code pad} matches the renderer's NO_CYCLE. A radial one
     *  states its start radius ({@code fr}) when it has one, and a gradient whose ends do not both extend
     *  says so in {@code data-extend} (FORMAT §B3) — SVG pads either way, the model keeps what PDF said. */
    private static String gradientDef(OCDGradient g) {
        double[] c = g.coords();
        String head = " id=\"__ID__\" gradientUnits=\"userSpaceOnUse\" gradientTransform=\"matrix("
                + mat(g.transform()) + ")\" spreadMethod=\"pad\""
                + (g.extend0() && g.extend1() ? "" : " data-extend=\"" + (g.extend0() ? 1 : 0) + " " + (g.extend1() ? 1 : 0) + "\"");
        var s = new StringBuilder();
        if (g.isLinear())
            s.append("<linearGradient").append(head)
             .append(" x1=\"").append(fmt(c[0])).append("\" y1=\"").append(fmt(c[1]))
             .append("\" x2=\"").append(fmt(c[2])).append("\" y2=\"").append(fmt(c[3])).append("\">");
        else
            s.append("<radialGradient").append(head)
             .append(" cx=\"").append(fmt(c[3])).append("\" cy=\"").append(fmt(c[4])).append("\" r=\"").append(fmt(c[5]))
             .append("\" fx=\"").append(fmt(c[0])).append("\" fy=\"").append(fmt(c[1]))
             .append(c[2] == 0 ? "" : "\" fr=\"" + fmt(c[2])).append("\">");
        float[] off = g.offsets();
        int[]   col = g.colors();
        for (int i = 0; i < off.length; i++) {
            float a = new JxColor(col[i]).alpha();
            s.append("<stop offset=\"").append(fmt(off[i])).append("\" stop-color=\"").append(rgb(col[i])).append('"');
            if (a < 1f) s.append(" stop-opacity=\"").append(fmt(a)).append('"');
            s.append("/>");
        }
        s.append(g.isLinear() ? "</linearGradient>" : "</radialGradient>");
        return s.toString();
    }

    public static String rgb(int argb) { return new JxColor(argb).rgbHex(); }
    public static String mat(JxTransform t) { return t.toMatrix6(); }

    /** The page flip, delegated to the ONE authority ({@link OCDPage#toSvg()}): the frame is the
     *  MEDIA box, so nothing a page paints depends on where the crop window sits. */
    public static JxTransform pageFlip(OCDPage page) { return page.toSvg(); }
    public static String pageFlipM(OCDPage page) { return pageFlip(page).toMatrix6(); }
}
