package sugarcube.jexter.ocd.render;

import sugarcube.jexter.core.JxPath;
import sugarcube.jexter.core.JxRect;
import sugarcube.jexter.core.JxTransform;
import sugarcube.jexter.ocd.model.OCDAnnotation;
import sugarcube.jexter.ocd.model.OCDBreak;
import sugarcube.jexter.ocd.model.OCDClip;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDFont;
import sugarcube.jexter.ocd.model.OCDGlyph;
import sugarcube.jexter.ocd.model.OCDGradient;
import sugarcube.jexter.ocd.model.OCDGroup;
import sugarcube.jexter.ocd.model.OCDImage;
import sugarcube.jexter.ocd.model.OCDMedia;
import sugarcube.jexter.ocd.model.OCDVideo;
import sugarcube.jexter.ocd.model.OCDNode;
import sugarcube.jexter.ocd.model.OCDFormField;
import sugarcube.jexter.ocd.model.OCDPage;
import sugarcube.jexter.ocd.model.OCDPath;
import sugarcube.jexter.ocd.model.OCDText;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders an {@link OCDPage} to a {@link BufferedImage} with Java2D — the OCD
 * side of the fidelity harness (compared against PDFBox's {@code PDFRenderer}).
 *
 * <p>Port of the old {@code doc.PageRenderer} to the new model. Because the model
 * stores live {@link JxPath} geometry and {@link OCDGlyph} outlines (not SVG
 * strings) and packed {@code int} argb (not CSS), there is no SVG parsing and no
 * colour parsing: paths and glyph outlines are painted directly, colours are
 * {@code new Color(argb, true)}.
 *
 * <p>Coordinate handling matches the source: page user space is Y-up, so the
 * canvas is set up once with a scale + Y-flip; node transforms compose on top.
 * Clips are page-space and are applied relative to the page transform regardless
 * of group nesting. Page rotation (90/180/270) is applied as a clockwise canvas
 * transform, matching PDFBox's {@code /Rotate} handling.
 */
public final class OCDRenderer {

    private OCDRenderer() {}


    /**
     * Display filter for the inspector. Each leaf type can be hidden and the page clips
     * can be ignored (to reveal clipped-away content). {@link #ALL} is the faithful default
     * (every caller that does not opt in renders exactly as before), so the fidelity bar
     * cannot regress: the analysis/fidelity paths all use {@code ALL}.
     */
    public record View(boolean text, boolean paths, boolean images, boolean clips, boolean annotations) {
        public static final View ALL = new View(true, true, true, true, true);
        public static View show(boolean text, boolean paths, boolean images, boolean clips, boolean annotations) {
            return new View(text, paths, images, clips, annotations);
        }
    }

    public static BufferedImage render(OCDPage page, OCDDocument doc) {
        return render(page, doc, 144.0);
    }

    public static BufferedImage render(OCDPage page, OCDDocument doc, double dpi) {
        return render(page, doc, dpi, true);
    }

    public static BufferedImage render(OCDPage page, OCDDocument doc, double dpi, boolean annotations) {
        return render(page, doc, dpi, new View(true, true, true, true, annotations));
    }

    public static BufferedImage render(OCDPage page, OCDDocument doc, double dpi, View view) {
        double scale = dpi / 72.0;
        JxRect box = page.effectiveBox();
        int rot = page.rotation();
        boolean swap = (rot == 90 || rot == 270);

        // unrotated device frame, then the canvas (dims swapped for 90/270)
        int uw = Math.max(1, (int) Math.ceil(box.width() * scale));
        int uh = Math.max(1, (int) Math.ceil(box.height() * scale));
        int w = swap ? uh : uw;
        int h = swap ? uw : uh;

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);

            // page /Rotate (clockwise on screen) maps the unrotated uw×uh device
            // frame into the canvas; content below is drawn in that unrotated frame.
            switch (rot) {
                case 90  -> { g.translate(uh, 0);  g.rotate(Math.PI / 2); }
                case 180 -> { g.translate(uw, uh); g.rotate(Math.PI); }
                case 270 -> { g.translate(0, uw);  g.rotate(-Math.PI / 2); }
                default  -> { }
            }

            // page user space (Y-up) → unrotated device: scale + Y-flip, origin at the box.
            g.translate(0, uh);
            g.scale(scale, -scale);
            g.translate(-box.x(), -box.y());

            AffineTransform pageTx = g.getTransform();   // page space — clips are applied here
            Map<String, BufferedImage> imageCache = new HashMap<>();
            paintAll(g, page.content(), page, doc, pageTx, imageCache, view);
            if (view.annotations()) paintAnnotations(g, page);
        } finally {
            g.dispose();
        }
        return img;
    }

    /**
     * Bake a subset of a page's nodes (e.g. one transparency group's content) onto a TRANSPARENT
     * canvas, cropped to {@code bounds} (page space) at {@code dpi}. The nodes keep their page-space
     * transforms and clips ({@code page} resolves clip ids), so they land exactly where they would
     * on the full page. Used by the importer to composite a group's content ONCE into an image,
     * instead of folding the group's blend/alpha onto every leaf (which over-darkens on overlap).
     *
     * <p>Bakes in UNROTATED page space — the resulting image is stored as an ordinary page-space
     * node, so any page {@code /Rotate} is applied later by {@link #render} to the whole page,
     * including this baked node.
     */
    public static BufferedImage renderNodes(java.util.List<OCDNode> nodes, JxRect bounds,
                                            OCDPage page, OCDDocument doc, double dpi) {
        double scale = dpi / 72.0;
        int w = Math.max(1, (int) Math.ceil(bounds.width() * scale));
        int h = Math.max(1, (int) Math.ceil(bounds.height() * scale));
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            // NO background fill — the canvas stays transparent so the group composites cleanly.
            g.translate(0, h);
            g.scale(scale, -scale);
            g.translate(-bounds.x(), -bounds.y());
            AffineTransform pageTx = g.getTransform();
            Map<String, BufferedImage> imageCache = new HashMap<>();
            paintAll(g, nodes, page, doc, pageTx, imageCache, View.ALL);
        } finally {
            g.dispose();
        }
        return img;
    }

    /**
     * Paint a single node onto an already-configured canvas (transform = {@code pageTx},
     * page→device). Exposed so a direct PDF renderer ({@code PdfRenderer}) can paint nodes inline,
     * in stream order, with the exact same primitives as the OCD path — sharing one painting code
     * path so a discrepancy can be attributed to the model, not the rendering. {@code page} resolves
     * clip ids; {@code doc} resolves fonts and image bytes; {@code cache} memoises decoded images.
     */
    public static void paintNode(Graphics2D g, OCDNode node, OCDPage page, OCDDocument doc,
                                 AffineTransform pageTx, Map<String, BufferedImage> cache) {
        paint(g, node, page, doc, pageTx, cache, View.ALL);
    }

    private static void paint(Graphics2D g, OCDNode node, OCDPage page, OCDDocument doc,
                              AffineTransform pageTx, Map<String, BufferedImage> imageCache, View view) {
        // display filter (inspector): hide a leaf type entirely; groups still recurse so their
        // surviving children paint. Order-preserving — z is untouched, so fidelity holds for View.ALL.
        if ((node instanceof OCDText  && !view.text())
                || (node instanceof OCDPath  && !view.paths())
                || (node instanceof OCDImage && !view.images())) return;
        AffineTransform savedT = g.getTransform();
        Shape savedClip = g.getClip();
        Composite savedComposite = g.getComposite();
        try {
            // clip is page-space: intersect it under the page transform, not the node's
            if (node.hasClip() && view.clips()) {
                OCDClip clip = page.clip(node.clipId());
                if (clip != null && clip.path() != null && !clip.isNone()) {
                    g.setTransform(pageTx);
                    g.clip(clip.path());
                    g.setTransform(savedT);
                }
            }
            Composite comp = composite(node);
            if (comp != null) g.setComposite(comp);
            if (!node.transform().isIdentity()) g.transform(node.transform().awt());

            switch (node) {
                case OCDPath p   -> paintPath(g, p);
                case OCDImage im -> paintImage(g, im, doc, imageCache);
                case OCDMedia m  -> {
                    if (m instanceof OCDVideo v && v.poster() != null) {   // static frame for video
                        OCDImage poster = new OCDImage(v.poster());
                        poster.transform(m.transform());
                        paintImage(g, poster, doc, imageCache);
                    }
                }
                case OCDText t   -> paintText(g, t, doc);
                case OCDGroup gr -> paintAll(g, gr.children(), page, doc, pageTx, imageCache, view);
                case OCDBreak b  -> { }   // line-break sentinel: paints nothing
            }
        } finally {
            g.setComposite(savedComposite);
            g.setClip(savedClip);
            g.setTransform(savedT);
        }
    }

    /** Paint a sibling list in <b>{@code z} order</b> — the authoritative paint order — rather than child
     *  order. The OCD flow carries reading order; {@code z} carries paint order. Sorting a copy here (stable,
     *  so equal-{@code z} ties keep their flow order) lets the document be stored in reading order while the
     *  raster stays byte-identical. Applied at every level (page content and each group's children). */
    private static void paintAll(Graphics2D g, List<OCDNode> nodes, OCDPage page, OCDDocument doc,
                                 AffineTransform pageTx, Map<String, BufferedImage> imageCache, View view) {
        for (OCDNode n : OCDNode.inPaintOrder(nodes)) paint(g, n, page, doc, pageTx, imageCache, view);
    }

    // ── annotation layer ─────────────────────────────────────────────────────
    // Painted in page space (Y-up) after content, so it composites above it exactly as in PDF.
    private static final java.awt.Font ANNOT_FONT = new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 1);

    private static void paintAnnotations(Graphics2D g, OCDPage page) {
        Color savedC = g.getColor();
        java.awt.Stroke savedS = g.getStroke();
        Composite savedX = g.getComposite();

        for (var a : page.annotations()) {
            Color c = a.color() != null ? new Color(a.color().argb(), true) : defaultColor(a.type());
            java.util.List<JxRect> regions = !a.quads().isEmpty() ? a.quads()
                    : (a.rect() != null ? java.util.List.of(a.rect()) : java.util.List.of());
            switch (a.type()) {
                case HIGHLIGHT -> {
                    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.40f));
                    g.setColor(opaque(c));
                    for (JxRect r : regions) g.fill(new java.awt.geom.Rectangle2D.Double(r.x(), r.y(), r.width(), r.height()));
                    g.setComposite(savedX);
                }
                case UNDERLINE -> { g.setColor(opaque(c)); g.setStroke(new BasicStroke(1f));
                    for (JxRect r : regions) g.draw(new java.awt.geom.Line2D.Double(r.x(), r.y() + 0.6, r.x() + r.width(), r.y() + 0.6)); }
                case STRIKEOUT -> { g.setColor(opaque(c)); g.setStroke(new BasicStroke(1f));
                    for (JxRect r : regions) { double my = r.y() + r.height() / 2; g.draw(new java.awt.geom.Line2D.Double(r.x(), my, r.x() + r.width(), my)); } }
                case SQUIGGLY -> { g.setColor(opaque(c)); g.setStroke(new BasicStroke(0.8f));
                    for (JxRect r : regions) g.draw(squiggle(r.x(), r.x() + r.width(), r.y() + 1.0, 1.4)); }
                case NOTE -> { if (a.rect() != null) paintNoteIcon(g, a.rect(), opaque(c)); }
                default -> {                                  // FREETEXT, STAMP, INK, LINE, SHAPE, OTHER
                    if (a.rect() != null) { g.setColor(opaque(c)); g.setStroke(dashed());
                        JxRect r = a.rect(); g.draw(new java.awt.geom.Rectangle2D.Double(r.x(), r.y(), r.width(), r.height())); }
                }
            }
        }

        for (var f : page.fields()) {
            JxRect r = f.rect(); if (r == null) continue;
            boolean on  = f.isOn();   // the field knows; a renderer must not re-decide it
            boolean btn = f.type() == OCDFormField.Field.BUTTON;
            double rad  = f.type() == OCDFormField.Field.RADIO ? FieldStyle.radioRadius(r)
                        : btn ? FieldStyle.buttonRadius(r) : FieldStyle.radius(r);
            var shape = new java.awt.geom.RoundRectangle2D.Double(r.x(), r.y(), r.width(), r.height(), rad * 2, rad * 2);

            // A hollow to write in, or a solid to press: the fill is what tells them apart, and a button
            // carries no border — a bounded outline is what makes a control read as a slot.
            g.setColor(btn ? FieldStyle.FACE : FieldStyle.SURFACE);
            g.fill(shape);
            if (!btn) { g.setColor(FieldStyle.BORDER); g.setStroke(new BasicStroke((float) FieldStyle.BORDER_W)); g.draw(shape); }

            if (f.type() == OCDFormField.Field.CHECKBOX) {
                if (on) {                                              // the state is the ink, nothing else
                    double[] t = FieldStyle.tick(r);
                    g.setColor(FieldStyle.INK);
                    g.setStroke(new BasicStroke((float) FieldStyle.TICK_W, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    var p = new java.awt.geom.Path2D.Double();
                    p.moveTo(t[0], t[1]); p.lineTo(t[2], t[3]); p.lineTo(t[4], t[5]);
                    g.draw(p);
                }
            } else if (f.type() == OCDFormField.Field.RADIO) {
                if (on) {                                              // one of a set is CHOSEN — a dot, not a tick
                    double[] c = FieldStyle.dot(r);
                    g.setColor(FieldStyle.INK);
                    g.fill(new java.awt.geom.Ellipse2D.Double(c[0] - c[2], c[1] - c[2], c[2] * 2, c[2] * 2));
                }
            } else if (f.type() == OCDFormField.Field.SIGNATURE) {
                double[] l = FieldStyle.rule(r);                       // a place to sign, not an empty input
                g.setColor(FieldStyle.BORDER);
                g.setStroke(new BasicStroke((float) FieldStyle.BORDER_W));
                g.draw(new java.awt.geom.Line2D.Double(l[0], l[1], l[2], l[3]));
            } else {
                boolean choice = f.type() == OCDFormField.Field.CHOICE;
                if (choice) {                                          // the one mark that says "a closed list"
                    double[] v = FieldStyle.chevron(r);
                    g.setColor(FieldStyle.INK);
                    g.setStroke(new BasicStroke((float) (FieldStyle.BORDER_W * 1.6), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    var p = new java.awt.geom.Path2D.Double();
                    p.moveTo(v[0], v[1]); p.lineTo(v[2], v[3]); p.lineTo(v[4], v[5]);
                    g.draw(p);
                }
                if (!f.value().isEmpty()) {
                    double size = btn ? FieldStyle.buttonTextSize(r) : FieldStyle.textSize(r);
                    double x = r.x() + FieldStyle.PAD_X;
                    if (btn) {                                          // a button's label is centred, by definition
                        var fm = g.getFontMetrics(ANNOT_FONT.deriveFont((float) size));
                        x = r.x() + (r.width() - fm.stringWidth(f.value())) / 2;
                    }
                    drawUpright(g, f.value(), x, btn ? FieldStyle.buttonBaseline(r) : FieldStyle.baseline(r), size);
                }
            }
        }
        g.setColor(savedC); g.setStroke(savedS); g.setComposite(savedX);
    }

    /** Draw upright text in the Y-flipped page space (local Y-flip so glyphs aren't mirrored). */
    private static void drawUpright(Graphics2D g, String s, double x, double baseline, double sizePt) {
        AffineTransform save = g.getTransform();
        g.translate(x, baseline); g.scale(1, -1);
        g.setColor(FieldStyle.INK); g.setFont(ANNOT_FONT.deriveFont((float) sizePt));
        g.drawString(s, 0, 0);
        g.setTransform(save);
    }

    private static void paintNoteIcon(Graphics2D g, JxRect r, Color c) {
        double s = Math.min(Math.min(r.width(), r.height()) > 0 ? Math.min(r.width(), r.height()) : 14, 16);
        double x = r.x(), y = r.y() + r.height() - s;          // page-space top-left
        var box = new java.awt.geom.RoundRectangle2D.Double(x, y, s, s, s * 0.28, s * 0.28);
        g.setColor(c); g.fill(box);
        g.setColor(new Color(Math.max(0, c.getRed() - 70), Math.max(0, c.getGreen() - 70), Math.max(0, c.getBlue() - 70)));
        g.setStroke(new BasicStroke(0.5f)); g.draw(box);
        g.draw(new java.awt.geom.Line2D.Double(x + s * 0.22, y + s * 0.62, x + s * 0.78, y + s * 0.62));
        g.draw(new java.awt.geom.Line2D.Double(x + s * 0.22, y + s * 0.40, x + s * 0.78, y + s * 0.40));
    }

    private static Shape squiggle(double x0, double x1, double y, double amp) {
        java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
        p.moveTo(x0, y); boolean up = true;
        for (double x = x0; x < x1; x += 2.0) { p.lineTo(Math.min(x + 2.0, x1), y + (up ? amp : -amp)); up = !up; }
        return p;
    }

    private static java.awt.Stroke dashed() {
        return new BasicStroke(0.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{2f, 2f}, 0f);
    }
    private static Color opaque(Color c) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), 255); }
    private static Color defaultColor(OCDAnnotation.Markup t) {
        return switch (t) {
            case HIGHLIGHT -> new Color(255, 235, 60);
            case NOTE      -> new Color(255, 200, 80);
            default        -> new Color(120, 120, 120);
        };
    }

    private static void paintPath(Graphics2D g, OCDPath p) {        JxPath geo = p.geometry();
        if (geo == null) return;
        if (p.isFilled()) {
            if (p.hasGradient()) g.setPaint(gradientPaint(p.fillGradient()));
            else                 g.setColor(new Color(p.fill(), true));
            g.fill(geo);
        }
        if (p.isStroked()) {
            g.setColor(new Color(p.stroke(), true));
            g.setStroke(stroke(p));
            g.draw(geo);
        }
    }

    /** Build a Java2D gradient {@link Paint} from an {@link OCDGradient}. The gradient's coords are
     *  in its own space; its transform (gradient→page) is passed as the paint's gradient transform,
     *  so anisotropic/sheared gradients stay correct. {@code NO_CYCLE} pads the ends (PDF Extend). */
    private static Paint gradientPaint(OCDGradient grad) {
        float[] fractions = sanitizeFractions(grad.offsets());
        int[] argb = grad.colors();
        Color[] colors = new Color[argb.length];
        for (int i = 0; i < argb.length; i++) colors[i] = new Color(argb[i], true);
        JxTransform t = grad.transform();
        AffineTransform gt = new AffineTransform(t.a(), t.b(), t.c(), t.d(), t.tx(), t.ty());
        double[] co = grad.coords();
        try {
            if (grad.isLinear()) {
                Point2D p0 = new Point2D.Double(co[0], co[1]), p1 = new Point2D.Double(co[2], co[3]);
                if (p0.equals(p1)) return colors[colors.length - 1];
                return new LinearGradientPaint(p0, p1, fractions, colors,
                        MultipleGradientPaint.CycleMethod.NO_CYCLE,
                        MultipleGradientPaint.ColorSpaceType.SRGB, gt);
            }
            // radial: outer circle (x1,y1,r1) with focus at the inner-circle centre (x0,y0)
            double cx = co[3], cy = co[4], r = co[5], fx = co[0], fy = co[1];
            if (r <= 0) return colors[colors.length - 1];
            return new RadialGradientPaint(new Point2D.Double(cx, cy), (float) r,
                    new Point2D.Double(fx, fy), fractions, colors,
                    MultipleGradientPaint.CycleMethod.NO_CYCLE,
                    MultipleGradientPaint.ColorSpaceType.SRGB, gt);
        } catch (RuntimeException e) {           // degenerate geometry → flat fallback
            return new Color(grad.flatArgb(), true);
        }
    }

    /** Java2D demands strictly-increasing fractions in [0,1]; nudge any duplicates/regressions. */
    private static float[] sanitizeFractions(float[] in) {
        float[] f = in.clone();
        f[0] = Math.max(0f, Math.min(1f, f[0]));
        for (int i = 1; i < f.length; i++) {
            f[i] = Math.max(0f, Math.min(1f, f[i]));
            if (f[i] <= f[i - 1]) f[i] = Math.min(1f, f[i - 1] + 1e-5f);
        }
        return f;
    }

    private static void paintImage(Graphics2D g, OCDImage im, OCDDocument doc, Map<String, BufferedImage> cache) {
        String ref = im.resourceRef();
        if (ref == null) return;
        BufferedImage bi = cache.containsKey(ref) ? cache.get(ref) : load(doc.image(ref));
        cache.put(ref, bi);
        if (bi == null) return;

        // node transform already maps the unit square to the page; map the raster
        // (top-down) into the unit square (Y-up): (0,0)->(0,1), (w,h)->(1,0).
        AffineTransform saved = g.getTransform();
        Object savedInterp = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.translate(0, 1);
        g.scale(1.0 / bi.getWidth(), -1.0 / bi.getHeight());
        g.drawImage(bi, 0, 0, null);
        g.setTransform(saved);
        if (savedInterp != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, savedInterp);
    }

    private static void paintText(Graphics2D g, OCDText t, OCDDocument doc) {
        if (t.isInvisible() || t.glyphs().isEmpty()) return;
        OCDFont font = doc.findFont(t.fontId());
        if (font == null) return;
        double fs = t.fontSize();
        boolean doStroke = t.hasStrokePaint();
        boolean doFill   = t.hasFill() || !doStroke;     // default to fill if the mode paints nothing else
        Color  fillColor   = new Color(t.fill(), true);
        Color  strokeColor = doStroke ? new Color(t.stroke(), true) : null;
        BasicStroke pen    = doStroke ? textPen(t, fs) : null;
        for (OCDText.Glyph gl : t.glyphs()) {
            OCDGlyph fg = font.glyph(gl.gid());
            if (fg == null || fg.outline() == null || fg.isSpace()) continue;
            AffineTransform saved = g.getTransform();
            g.translate(gl.x(), 0);          // along the baseline (text space)
            g.scale(fs, fs);                 // em → text-space units (outline is Y-up); stroke width /fs to stay page-space
            if (doFill)   { g.setColor(fillColor);   g.fill(fg.outline()); }
            if (doStroke) { g.setColor(strokeColor); g.setStroke(pen); g.draw(fg.outline()); }
            g.setTransform(saved);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static Composite composite(OCDNode n) {
        float a = clampAlpha(n.alpha());
        if (n.hasBlend() && !"Normal".equals(n.blend())) return BlendComposite.getInstance(n.blend(), a);
        if (a < 1f) return AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a);
        return null;
    }

    private static BasicStroke stroke(OCDPath p) {
        float w = (float) Math.max(p.strokeWidth(), 1e-3);
        float miter = (float) Math.max(p.miterLimit(), 1f);
        float[] dash = validDash(p.dash());
        return new BasicStroke(w, p.cap(), p.join(), miter, dash, (float) p.dashPhase());
    }

    /** Text stroke pen. The glyph outline is drawn in em space (after {@code g.scale(fs,fs)}), so the
     *  page-space width and dash lengths are divided by {@code fs} to stay page-space — parity with paths. */
    private static BasicStroke textPen(OCDText t, double fs) {
        float inv   = (float) (1.0 / fs);
        float w     = Math.max((float) t.strokeWidth() * inv, 1e-4f);
        float miter = (float) Math.max(t.miterLimit(), 1f);
        float[] dash = validDash(t.dash());
        if (dash != null) for (int i = 0; i < dash.length; i++) dash[i] *= inv;
        return new BasicStroke(w, t.cap(), t.join(), miter, dash, (float) (t.dashPhase() * inv));
    }

    /** BasicStroke rejects null-vs-empty and all-zero/negative dash arrays. */
    private static float[] validDash(double[] dash) {
        if (dash == null || dash.length == 0) return null;
        float[] f = new float[dash.length];
        boolean positive = false;
        for (int i = 0; i < dash.length; i++) {
            f[i] = (float) dash[i];
            if (f[i] < 0) return null;
            if (f[i] > 0) positive = true;
        }
        return positive ? f : null;
    }

    private static float clampAlpha(float a) { return a < 0 ? 0 : (a > 1 ? 1 : a); }

    private static BufferedImage load(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            return ImageIO.read(new ByteArrayInputStream(data));
        } catch (Exception e) {
            return null;
        }
    }
}
