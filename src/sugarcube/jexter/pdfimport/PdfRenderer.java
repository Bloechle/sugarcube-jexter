package sugarcube.jexter.pdfimport;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.PDSoftMask;

import sugarcube.jexter.core.JxRect;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDNode;
import sugarcube.jexter.ocd.model.OCDPage;
import sugarcube.jexter.ocd.render.BlendComposite;
import sugarcube.jexter.ocd.render.OCDRenderer;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Renders a PDF page <em>directly</em> to a raster with our own painting — the third leg of the
 * fidelity harness, symmetric to {@link PdfImporter}:
 *
 * <pre>
 *   PdfImporter : COS -> OCD model        OCDRenderer : OCD model -> raster
 *   PdfRenderer : COS -> raster (ours)    PDFBox      : COS -> raster (reference)
 * </pre>
 *
 * <p>It reuses {@link PdfStreamEngine}'s entire extraction (paths, glyph runs, shadings, images,
 * fonts, clips) and reuses {@link OCDRenderer}'s painting via {@link OCDRenderer#paintNode}. The
 * only thing it changes is the <em>sink</em>: each node is
 * painted immediately, in stream order, into the current target instead of being stored.
 *
 * <p>Transparency groups are composited for maximum graphical fidelity, without ever assembling an
 * OCD model:
 * <ul>
 *   <li>a group with constant alpha &lt; 1 is painted as a UNIT — its content goes onto an
 *       offscreen layer (same pixel grid as the canvas, so no resampling), then the whole layer is
 *       composited once with the group's blend/alpha (this is what avoids the per-leaf
 *       over-darkening where content overlaps);</li>
 *   <li>a full-alpha blend group (e.g. Overlay) is painted per-leaf — its blend is folded onto each
 *       child against the live backdrop (compositing it isolated would blacken it).</li>
 * </ul>
 *
 * <p>Because it builds no model, comparing {@code pdf-direct} with {@code ocd} isolates the OCD
 * <em>model pipeline</em>: in principle they should match — any difference is "sand in the gears"
 * (a loss in conversion, assembly, or serialization).
 *
 * <p>Note: unlike {@link PdfImporter#convert}, this does not repair truncated embedded-font tables
 * (it must not mutate a shared {@code PDDocument}); affected fonts render with PDFBox's substitute.
 */
public final class PdfRenderer extends PdfStreamEngine {

    private final AffineTransform pageTx;
    private final Map<String, BufferedImage> cache;
    private final int cw, ch;                         // canvas size (device pixels)
    private final Deque<Graphics2D> targets = new ArrayDeque<>();   // current paint target (main canvas or a group layer)

    /** A full-alpha blend group, applied per-leaf to its content. */
    private record GroupCtx(String blend) {}
    private final Deque<GroupCtx> gstack = new ArrayDeque<>();
    private boolean inMask = false;   // true while rendering a soft-mask group (don't re-apply masks)
    private final Deque<Boolean> layers = new ArrayDeque<>();   // open optional-content sequences: hidden?
    private int hidden;

    @Override protected void beginLayer(String layerId, String name) {
        var l = doc.layer(layerId);
        boolean off = l != null && !l.visible();
        layers.push(off);
        if (off) hidden++;
    }

    @Override protected void endLayer() {
        if (!layers.isEmpty() && layers.pop()) hidden--;
    }

    protected PdfRenderer(PDPage pd, OCDDocument doc, OCDPage page, FontExtractor fonts,
                          Graphics2D g, AffineTransform pageTx, Map<String, BufferedImage> cache, int cw, int ch) {
        super(pd, doc, page, fonts, true);   // merge per-glyph self-clips
        this.pageTx = pageTx;
        this.cache = cache;
        this.cw = cw;
        this.ch = ch;
        this.targets.push(g);
    }

    /** Render page {@code i} of an already-loaded document straight to a raster with our painting. */
    public static BufferedImage render(PDDocument pdf, int i, double dpi) throws IOException {
        PDPage pd = pdf.getPage(i);
        OCDDocument doc = new OCDDocument();
        PdfCatalog.read(pdf, doc);                       // the layers' visibility, as the importer reads it
        FontExtractor fonts = new FontExtractor(doc);
        OCDPage page = PdfCatalog.page(pd);              // the importer's frame, so the two compare like for like

        // Canvas setup mirrors OCDRenderer.render (white bg, page /Rotate, scale + Y-flip).
        double scale = dpi / 72.0;
        JxRect box = page.effectiveBox();
        int rot = page.rotation();
        boolean swap = (rot == 90 || rot == 270);
        int uw = Math.max(1, (int) Math.ceil(box.width() * scale));
        int uh = Math.max(1, (int) Math.ceil(box.height() * scale));
        int w = swap ? uh : uw;
        int h = swap ? uw : uh;

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            applyHints(g);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);

            switch (rot) {
                case 90  -> { g.translate(uh, 0);  g.rotate(Math.PI / 2); }
                case 180 -> { g.translate(uw, uh); g.rotate(Math.PI); }
                case 270 -> { g.translate(0, uw);  g.rotate(-Math.PI / 2); }
                default  -> { }
            }
            g.translate(0, uh);
            g.scale(scale, -scale);
            g.translate(-box.x(), -box.y());

            AffineTransform pageTx = g.getTransform();
            Map<String, BufferedImage> cache = new HashMap<>();
            PdfRenderer engine = new PdfRenderer(pd, doc, page, fonts, g, pageTx, cache, w, h);
            // The same inputs as the importer (output intent, reference raster): the direct render differs
            // from the OCD one only by what the model loses, never by what one side was not handed.
            engine.intentCmyk(OutputIntentCmyk.of(PdfCatalog.outputIntent(pdf)));
            engine.referenceRaster = PdfCatalog.referenceRaster(pdf, i);
            engine.processPage(pd);
            engine.flushText();   // flush any run still open at page end
        } finally {
            g.dispose();
        }
        return img;
    }

    private static void applyHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    // -- sink: paint each node inline into the current target --------------------
    @Override protected void addNode(OCDNode n) {
        if (hidden > 0) return;                       // inside a layer switched off
        applyGroupCtx(n);
        OCDRenderer.paintNode(targets.peek(), n, page, doc, pageTx, cache);
    }

    // -- transparency groups: unit-composite low-alpha groups; per-leaf otherwise -
    @Override public void showTransparencyGroup(PDTransparencyGroup form) throws IOException {
        flushText();   // groups are z-order boundaries
        PDGraphicsState gs = getGraphicsState();
        String blend = blendName(gs.getBlendMode());
        float alpha = (float) gs.getNonStrokeAlphaConstant();
        PDSoftMask sm = inMask ? null : gs.getSoftMask();

        if (sm != null && sm.getGroup() != null) {
            renderMaskedGroup(form, sm, blend, alpha);
            return;
        }
        if (alpha < 1f) {
            // group-as-unit: paint content onto an offscreen layer (same grid -> no resampling),
            // then composite the whole layer once with the group's blend/alpha.
            Graphics2D parent = targets.peek();
            BufferedImage layer = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
            Graphics2D lg = layer.createGraphics();
            applyHints(lg);
            lg.setTransform(pageTx);
            targets.push(lg);
            try { descendGroup(form); flushText(); }
            finally {
                targets.pop();
                lg.dispose();
                compositeLayer(parent, layer, blend, alpha);
            }
        } else {
            // full-alpha blend group -> per-leaf (fold the blend onto each child, live backdrop)
            gstack.push(new GroupCtx(blend));
            try { descendGroup(form); flushText(); }
            finally { gstack.pop(); }
        }
    }

    /**
     * A group drawn under a soft mask (e.g. a soft drop shadow). We render the content to one layer and the
     * mask group to another, both transparent, apply the mask ({@link SoftMasks#apply}, the importer's own),
     * then composite the masked layer once.
     */
    private void renderMaskedGroup(PDTransparencyGroup form, PDSoftMask sm, String blend, float alpha) throws IOException {
        Graphics2D parent = targets.peek();
        boolean prev = inMask; inMask = true;   // mask applies once here; don't re-mask nested groups
        try {
            // 1. content layer
            BufferedImage content = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
            Graphics2D cg = content.createGraphics();
            applyHints(cg);
            cg.setTransform(pageTx);
            targets.push(cg);
            try { descendGroup(form); flushText(); }
            finally { targets.pop(); cg.dispose(); }

            // 2. mask layer (its group alone: SoftMasks composites the backdrop)
            BufferedImage mask = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
            Graphics2D mg = mask.createGraphics();
            applyHints(mg);
            mg.setTransform(pageTx);
            targets.push(mg);
            try { descendGroup(sm.getGroup()); flushText(); }
            finally { targets.pop(); mg.dispose(); }

            // 3. content alpha *= mask value
            SoftMasks.apply(((java.awt.image.DataBufferInt) content.getRaster().getDataBuffer()).getData(),
                            ((java.awt.image.DataBufferInt) mask.getRaster().getDataBuffer()).getData(), sm);

            // 4. composite the masked content once
            compositeLayer(parent, content, blend, alpha);
        } finally { inMask = prev; }
    }

    /** Composite a full-canvas layer onto its parent in device space with the group's blend/alpha. */
    private void compositeLayer(Graphics2D parent, BufferedImage layer, String blend, float alpha) {
        Composite sc = parent.getComposite();
        AffineTransform st = parent.getTransform();
        Shape scl = parent.getClip();
        parent.setTransform(new AffineTransform());
        parent.setClip(null);
        parent.setComposite(groupComposite(blend, alpha));
        parent.drawImage(layer, 0, 0, null);
        parent.setComposite(sc);
        parent.setTransform(st);
        parent.setClip(scl);
    }

    /** Composite used to lay a group's layer over its backdrop (mirrors OCDRenderer.composite). */
    private static Composite groupComposite(String blend, float alpha) {
        float a = alpha < 0 ? 0 : (alpha > 1 ? 1 : alpha);
        if (blend != null && !"Normal".equals(blend)) return BlendComposite.getInstance(blend, a);
        return AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a);
    }

    /** Fold open full-alpha blend groups (innermost wins) onto a node painted per-leaf. */
    private void applyGroupCtx(OCDNode n) {
        if (gstack.isEmpty() || n.hasBlend()) return;
        String blend = null;
        for (GroupCtx c : gstack) if (c.blend() != null) blend = c.blend();
        if (blend != null) n.blend(blend);
    }

    // -- CLI: render a page straight from PDF (fidelity testing) -----------------
    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("usage: PdfRenderer <pdf> [page0] [out.png] [dpi]"); System.exit(2); }
        int pi = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        String out = args.length > 2 ? args[2] : "pdf-direct.png";
        double dpi = args.length > 3 ? Double.parseDouble(args[3]) : 144;
        try (PDDocument pdf = org.apache.pdfbox.Loader.loadPDF(new java.io.File(args[0]))) {
            javax.imageio.ImageIO.write(render(pdf, pi, dpi), "png", new java.io.File(out));
        }
        System.out.println("rendered " + args[0] + " page " + pi + " -> " + out);
    }
}
