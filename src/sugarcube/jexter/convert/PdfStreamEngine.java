package sugarcube.jexter.convert;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDVectorFont;
import org.apache.pdfbox.pdmodel.graphics.PDLineDashPattern;
import org.apache.pdfbox.pdmodel.graphics.blend.BlendMode;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDICCBased;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDPattern;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType3;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDAbstractPattern;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDShadingPattern;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import sugarcube.jexter.core.JxColor;
import sugarcube.jexter.core.JxLog;
import sugarcube.jexter.core.JxPath;
import sugarcube.jexter.core.JxRect;
import sugarcube.jexter.core.JxTransform;
import sugarcube.jexter.ocd.model.OCDClip;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDGradient;
import sugarcube.jexter.ocd.model.OCDImage;
import sugarcube.jexter.ocd.model.OCDNode;
import sugarcube.jexter.ocd.model.OCDPage;
import sugarcube.jexter.ocd.model.OCDPath;
import sugarcube.jexter.ocd.model.OCDText;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Abstract base that walks a PDF page's content stream with PDFBox's
 * {@link PDFGraphicsStreamEngine} and turns each paint operation into an {@link OCDNode}, in
 * content-stream z-order. It owns ALL the shared extraction — paths, glyph runs, shadings, images,
 * clips, colours, stroke style, blend-mode mapping — and hands each finished node to the abstract
 * {@link #addNode} sink. Subclasses decide what a node becomes and how transparency groups
 * composite:
 * <ul>
 *   <li>{@link PdfImporter} stores nodes into an {@link OCDDocument} (groups become an OCDGroup
 *       tree, low-alpha multi-child groups are baked);</li>
 *   <li>{@link PdfRenderer} paints nodes straight to a raster (groups composite as offscreen
 *       layers).</li>
 * </ul>
 *
 * <p>Path callbacks deliver page-space coordinates (PDFBox applies the CTM), so geometry is built
 * straight into a {@link JxPath} with an identity node transform. Colours resolve through
 * {@code PDColorSpace.toRGB} to sRGB {@code int} argb, with the fill/stroke alpha (ca/CA) folded in.
 *
 * <p>Group descent is exposed via {@link #descendGroup} so a subclass can wrap PDFBox's default
 * {@code Do} handling with its own composite policy. One engine instance per page.
 */
public abstract class PdfStreamEngine extends PDFGraphicsStreamEngine {

    protected final OCDDocument doc;
    protected final OCDPage page;
    private final FontExtractor fonts;
    private final boolean mergeGlyphClips;   // drop neutral per-glyph self-clips so runs stay merged
    protected int z = 0;
    private JxPath path = new JxPath();

    /** Hand a finished node to the sink: store it (importer) or paint it (renderer). */
    protected abstract void addNode(OCDNode n);

    // ── marked-content / MCID tracking (for tagged-PDF structure ingestion) ──────
    private final java.util.Deque<Integer> mcidStack = new java.util.ArrayDeque<>();
    // optional-content (/OC) nesting, parallel to mcidStack: each entry is a layer id, or "" when
    // the marked-content sequence is not optional content.
    private final java.util.Deque<String> ocStack = new java.util.ArrayDeque<>();

    /** Innermost enclosing marked-content id, or -1 if the content is untagged. */
    protected int currentMcid() {
        for (Integer m : mcidStack) if (m != null && m >= 0) return m;
        return -1;
    }

    /** Hooks for optional-content layers: a subclass (the importer) opens an OCDLayerContent on
     *  {@code beginLayer} and closes it on {@code endLayer}. No-op in the base / renderer. */
    protected void beginLayer(String layerId, String name) { }
    protected void endLayer() { }

    @Override
    protected void processOperator(org.apache.pdfbox.contentstream.operator.Operator operator,
                                   List<org.apache.pdfbox.cos.COSBase> operands) throws IOException {
        String op = operator.getName();
        if ("BDC".equals(op) || "BMC".equals(op)) {
            flushText();                       // commit the run under its current MCID before the context changes
            mcidStack.push(mcidOf(operands));
            String[] layer = "BDC".equals(op) ? layerOf(operands) : null;   // optional content (/OC) only on BDC
            ocStack.push(layer != null ? layer[0] : "");                    // "" = not an OC sequence
            if (layer != null) beginLayer(layer[0], layer[1]);
        } else if ("EMC".equals(op)) {
            flushText();                       // commit the marked run before popping its MCID
            if (!mcidStack.isEmpty()) mcidStack.pop();
            if (!ocStack.isEmpty()) { String id = ocStack.pop(); if (!id.isEmpty()) endLayer(); }
        }
        super.processOperator(operator, operands);
    }

    private int mcidOf(List<org.apache.pdfbox.cos.COSBase> operands) {
        if (operands.size() < 2) return -1;
        org.apache.pdfbox.cos.COSBase props = operands.get(1);
        if (props instanceof COSDictionary d) return d.getInt(COSName.MCID, -1);
        if (props instanceof COSName n) {
            try {
                var pl = getResources().getProperties(n);
                if (pl != null) return pl.getCOSObject().getInt(COSName.MCID, -1);
            } catch (Exception ignore) {}
        }
        return -1;
    }

    /** If a {@code BDC} marks an optional-content sequence ({@code /OC}), resolve the OCG and
     *  return {@code [layerId, name]}; else null. Handles a direct OCG dict, a named property
     *  resolved via the page {@code /Properties}, and an OCMD membership dict (first OCG). */
    private String[] layerOf(List<org.apache.pdfbox.cos.COSBase> operands) {
        if (operands.size() < 2 || !(operands.get(0) instanceof COSName tag) || !"OC".equals(tag.getName()))
            return null;
        try {
            org.apache.pdfbox.cos.COSBase props = operands.get(1);
            COSDictionary ocg = null;
            if (props instanceof COSDictionary d) ocg = d;
            else if (props instanceof COSName n) {
                var pl = getResources().getProperties(n);
                if (pl != null) ocg = pl.getCOSObject();
            }
            if (ocg == null) return null;
            if (COSName.getPDFName("OCMD").equals(ocg.getCOSName(COSName.TYPE))) {   // membership → first OCG
                org.apache.pdfbox.cos.COSBase ocgs = ocg.getDictionaryObject(COSName.getPDFName("OCGs"));
                if (ocgs instanceof COSArray a && a.size() > 0 && a.getObject(0) instanceof COSDictionary first) ocg = first;
                else if (ocgs instanceof COSDictionary single) ocg = single;
            }
            String name = ocg.getString(COSName.NAME);
            if (name == null || name.isBlank()) name = "Layer";
            name = name.trim();
            return new String[]{ "L_" + name.replaceAll("\\s+", "_"), name };
        } catch (Exception e) {
            JxLog.debug(PdfStreamEngine.class, "optional-content resolve failed", e);
            return null;
        }
    }

    /** Descend into a transparency-group form's content (PDFBox's default {@code Do} handling).
     *  Exposed so a subclass can reuse the descent while replacing the group composite policy. */
    protected void descendGroup(PDTransparencyGroup form) throws IOException {
        super.showTransparencyGroup(form);
    }

    /** PDF blend-mode name for a PDFBox {@link BlendMode} (null for Normal/unmapped). */
    protected static String blendName(BlendMode bm) { return BLEND.get(bm); }

    // open text run (glyphs grouped by font/fill/mode/baseline)
    private OCDText run;
    private AffineTransform runOrigin;     // first-glyph matrix (em→page, size included)
    private AffineTransform runInverse;    // its inverse (page→em)
    private double runSize;                // effective point size of the run
    private String runKey;
    private String runClipId;              // clip active for this run (part of run state)

    // deferred clip (PageDrawer pattern): W marks pending, applied after the paint op
    private int pendingClipRule = -1;
    private int clipSeq = 0;
    private Area lastClipArea;     // dedup cache: consecutive nodes usually share a clip
    private String lastClipId;

    // text clipping (Tr 4–7): glyph outlines buffered between BT/ET, then unioned and intersected
    // into the clip at ET. The base engine — unlike PageDrawer — does not build the text clip, so we
    // reconstruct each glyph's outline ourselves (we already know the font + code) and apply it.
    private List<Shape> textClippings;

    protected PdfStreamEngine(PDPage pdPage, OCDDocument doc, OCDPage page, FontExtractor fonts, boolean mergeGlyphClips) {
        super(pdPage);
        this.doc = doc;
        this.page = page;
        this.fonts = fonts;
        this.mergeGlyphClips = mergeGlyphClips;
    }

    // ── embedded-font robustness ────────────────────────────────────────────────
    /** DELIBERATE EXCEPTION — the one place jexter mutates PDFBox's loaded model.
     *  The rule everywhere else is "delegate PDF/font interpretation to PDFBox"; here
     *  we can't, because the defect is inside FontBox and the jar is vendored.
     *
     *  <p>Some PDFs embed subset TrueType fonts whose {@code post} (PostScript names)
     *  table is truncated/corrupt; FontBox aborts the *entire* font on it, so PDFBox
     *  substitutes a stand-in and the real glyphs are lost. {@code post} is non-critical
     *  for rendering, so when a {@code FontFile2} fails to parse we downgrade its
     *  {@code post} to version 3.0 (header only, no name array) — then {@code glyf}/
     *  {@code loca}/{@code cmap} load and the true glyphs render. Untouched when healthy;
     *  the edit lives only in the transient in-memory document (never the user's file). */
    static void repairEmbeddedTrueTypeFonts(PDDocument pdf) {
        for (COSObject obj : pdf.getDocument().getObjectsByType(COSName.getPDFName("FontDescriptor"))) {
            if (!(obj.getObject() instanceof COSDictionary d)) continue;
            if (!(d.getDictionaryObject(COSName.FONT_FILE2) instanceof COSStream s)) continue;
            try {
                byte[] b = readStream(s);
                if (ttfParses(b)) continue;                 // healthy → leave alone
                byte[] fixed = downgradePost(b);
                if (fixed == null || !ttfParses(fixed)) continue;   // unfixable → let PDFBox fall back
                try (OutputStream os = s.createOutputStream(COSName.FLATE_DECODE)) { os.write(fixed); }
                s.setItem(COSName.LENGTH1, COSInteger.get(fixed.length));
            } catch (IOException ignored) { }
        }
    }

    private static byte[] readStream(COSStream s) throws IOException {
        try (InputStream in = s.createInputStream()) { return in.readAllBytes(); }
    }

    private static boolean ttfParses(byte[] b) {
        try { new TTFParser(true).parse(new RandomAccessReadBuffer(b)); return true; }
        catch (Exception e) { return false; }
    }

    /** Flip the sfnt {@code post} table's version field to 3.0 in place (4 bytes). */
    private static byte[] downgradePost(byte[] b) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
        in.skipBytes(4);                                    // sfnt version
        int n = in.readUnsignedShort();
        in.skipBytes(6);                                    // searchRange/entrySelector/rangeShift
        for (int i = 0; i < n; i++) {
            byte[] tag = new byte[4]; in.readFully(tag);
            in.skipBytes(4);                                // checksum
            long off = in.readInt() & 0xffffffffL;
            in.skipBytes(4);                                // length
            if ("post".equals(new String(tag, java.nio.charset.StandardCharsets.US_ASCII)) && off + 4 <= b.length) {
                byte[] c = b.clone();
                c[(int) off] = 0; c[(int) off + 1] = 3; c[(int) off + 2] = 0; c[(int) off + 3] = 0;  // version 3.0
                return c;
            }
        }
        return null;
    }

    // ── path construction (page-space) ─────────────────────────────────────────
    @Override public void moveTo(float x, float y)                                  { path.moveTo(x, y); }
    @Override public void lineTo(float x, float y)                                  { path.lineTo(x, y); }
    @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) { path.curveTo(x1, y1, x2, y2, x3, y3); }
    @Override public void closePath()                                               { path.closePath(); }
    @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
        path.moveTo(p0.getX(), p0.getY());
        path.lineTo(p1.getX(), p1.getY());
        path.lineTo(p2.getX(), p2.getY());
        path.lineTo(p3.getX(), p3.getY());
        path.closePath();
    }
    @Override public Point2D getCurrentPoint() {
        Point2D p = path.getCurrentPoint();
        return p != null ? p : new Point2D.Float(0, 0);
    }
    @Override public void endPath() { applyPendingClip(); path = new JxPath(); }   // e.g. W n

    // ── painting ────────────────────────────────────────────────────────────────
    @Override public void fillPath(int windingRule) throws IOException {
        path.setWindingRule(windingRule);
        if (needsReferenceRaster() && fillGradientOrNull() == null && emitFromReference(new Area(path)))
            { applyPendingClip(); path = new JxPath(); return; }
        emit(fillPaint(new OCDPath(path)));
        applyPendingClip();
        path = new JxPath();
    }

    @Override public void strokePath() throws IOException {
        OCDPath n = new OCDPath(path).stroke(strokeArgb(), strokeWidth());
        applyStrokeStyle(n);
        emit(n);
        applyPendingClip();
        path = new JxPath();
    }

    @Override public void fillAndStrokePath(int windingRule) throws IOException {
        path.setWindingRule(windingRule);
        if (needsReferenceRaster() && fillGradientOrNull() == null
                && emitFromReference(new Area(path)))
            { applyPendingClip(); path = new JxPath(); return; }
        OCDPath n = fillPaint(new OCDPath(path)).stroke(strokeArgb(), strokeWidth());
        applyStrokeStyle(n);
        emit(n);
        applyPendingClip();
        path = new JxPath();
    }

    /** Apply the current non-stroking paint to a path: the solid argb, plus a first-class gradient
     *  when the fill colour is an axial/radial shading pattern (the solid stays as the flat fallback). */
    private OCDPath fillPaint(OCDPath n) {
        n.fill(fillArgb());
        OCDGradient g = fillGradientOrNull();
        if (g != null) n.fill(g.flatArgb()).fillGradient(g);
        return n;
    }

    /** Lazily-rendered PDFBox reference raster of the CURRENT page (page space, ~2 px/unit),
     *  installed by the importer. Null when unavailable (e.g. fidelity probes). */
    protected java.util.function.Supplier<BufferedImage> referenceRaster;
    private BufferedImage refRasterCache;

    /** True when the current non-stroking paint cannot be expressed by the model as vectors:
     *  a tiling pattern, or any paint under a soft mask (ExtGState /SMask). */
    private boolean needsReferenceRaster() {
        PDGraphicsState gs = getGraphicsState();
        if (gs.getSoftMask() != null) return true;
        if (gs.getNonStrokingColorSpace() instanceof PDPattern pcs) {
            try {
                return pcs.getPattern(gs.getNonStrokingColor())
                        instanceof org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
            } catch (Exception e) { return true; }
        }
        return false;
    }

    /** Emit {@code area} (page space) as an image cropped from the PDFBox reference raster —
     *  the same "PDFBox is the reference rasteriser" principle as mesh shadings. The region
     *  looks exactly right; content painted later still covers it (z order preserved). */
    private boolean emitFromReference(Area area) {
        if (referenceRaster == null) return false;
        if (refRasterCache == null) refRasterCache = referenceRaster.get();
        BufferedImage ref = refRasterCache;
        if (ref == null) return false;
        Area a = new Area(area);
        Area clip = getGraphicsState().getCurrentClippingPath();
        if (clip != null) a.intersect(clip);
        if (a.isEmpty()) return true;                       // nothing visible: consumed
        Rectangle2D b0 = a.getBounds2D();
        if (b0.getWidth() < 0.5 || b0.getHeight() < 0.5) return true;
        JxRect pb = page.effectiveBox();
        double sx = ref.getWidth() / pb.width(), sy = ref.getHeight() / pb.height();
        // Snap the crop to the reference pixel grid: the copy becomes an exact 1:1 pixel
        // transfer (no sub-pixel phase — decisive on high-frequency fills like tilings).
        Rectangle2D b = new Rectangle2D.Double(
                Math.floor(b0.getMinX() * sx) / sx, Math.floor(b0.getMinY() * sy) / sy,
                (Math.ceil(b0.getMaxX() * sx) - Math.floor(b0.getMinX() * sx)) / sx,
                (Math.ceil(b0.getMaxY() * sy) - Math.floor(b0.getMinY() * sy)) / sy);
        int w = Math.max(1, (int) Math.round(b.getWidth() * sx));
        int h = Math.max(1, (int) Math.round(b.getHeight() * sy));
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // page space → crop pixels (top-down flip), then draw the full-page raster through it
            g.setTransform(new AffineTransform(sx, 0, 0, -sy, -b.getMinX() * sx, b.getMaxY() * sy));
            g.clip(a);
            g.drawImage(ref, new AffineTransform(1 / sx, 0, 0, -1 / sy, pb.x(), pb.y() + pb.height()), null);
        } finally { g.dispose(); }
        try {
            String ref2 = doc.newImageRef("png");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            ImageIO.write(img, "png", buf);
            doc.addImage(ref2, buf.toByteArray());
            OCDImage n = new OCDImage(ref2).pixelSize(w, h);
            n.transform(JxTransform.of(new AffineTransform(b.getWidth(), 0, 0, b.getHeight(), b.getMinX(), b.getMinY())));
            emit(n);
        } catch (IOException e) { return false; }
        return true;
    }

    @Override public void clip(int windingRule)        { pendingClipRule = windingRule; }
    @Override public void shadingFill(COSName shading) {
        // The `sh` operator paints a shading across the current clip region. OCD has no
        // gradient primitive, so we rasterize the shading (via PDFBox's own PDShading.toPaint
        // — the reference rasteriser) and emit it as an OCDImage. The fill area mirrors
        // PageDrawer exactly: the shading's OWN extent (BBox, or its computed axis bounds)
        // intersected with the clip — NOT the whole clip. A non-extended axial/radial gradient
        // only covers its axis span, so filling the whole clip would flood past it and bury
        // whatever is painted behind (e.g. the maroon background under this cover's gradients).
        try {
            PDShading sh = getResources().getShading(shading);
            if (sh == null) return;
            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            Area clip = getGraphicsState().getCurrentClippingPath();

            Area area;
            PDRectangle sbbox = sh.getBBox();
            if (sbbox != null) {
                area = new Area(sbbox.transform(ctm));
                if (clip != null) area.intersect(clip);
            } else {
                Rectangle2D rb = sh.getBounds(new AffineTransform(), ctm);
                if (rb != null) {
                    rb.add(Math.floor(rb.getMinX() - 1), Math.floor(rb.getMinY() - 1));
                    rb.add(Math.ceil(rb.getMaxX() + 1), Math.ceil(rb.getMaxY() + 1));
                    area = new Area(rb);
                    if (clip != null) area.intersect(clip);
                } else if (clip != null) {
                    area = clip;
                } else {
                    JxRect pb = page.effectiveBox();
                    area = new Area(new Rectangle2D.Double(pb.x(), pb.y(), pb.width(), pb.height()));
                }
            }
            if (area.isEmpty()) return;
            Rectangle2D b = area.getBounds2D();
            if (b.getWidth() < 0.5 || b.getHeight() < 0.5) return;
            float alpha = (float) getGraphicsState().getNonStrokeAlphaConstant();

            // Axial/radial → first-class gradient: emit the fill region (extent ∩ clip) as an OCDPath
            // carrying the gradient paint, instead of rasterising. Mesh/function shadings fall through.
            OCDGradient grad = gradientOf(sh, ctm, alpha);
            if (grad != null) {
                emit(new OCDPath(new JxPath(area)).fill(grad.flatArgb()).fillGradient(grad));
                return;
            }

            // resolution: ~2 px per page unit, capped so a huge region can't blow up memory
            double scale = 2.0;
            long px = (long) Math.ceil(b.getWidth() * scale) * (long) Math.ceil(b.getHeight() * scale);
            if (px > 4_000_000) scale *= Math.sqrt(4_000_000.0 / px);
            int w = Math.max(1, (int) Math.ceil(b.getWidth() * scale));
            int h = Math.max(1, (int) Math.ceil(b.getHeight() * scale));

            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // page space → image pixels: x→(X-minX)*sx ; y→(maxY-Y)*sy (flip to top-down)
                double sx = w / b.getWidth(), sy = h / b.getHeight();
                g.setTransform(new AffineTransform(sx, 0, 0, -sy, -b.getMinX() * sx, b.getMaxY() * sy));
                g.clip(area);                                   // restrict to shading-extent ∩ clip, in page space
                g.setPaint(sh.toPaint(ctm));                    // gradient in page space (ctm maps shading→page)
                g.fill(b);
            } finally { g.dispose(); }

            String ref = doc.newImageRef("png");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            ImageIO.write(img, "png", buf);
            doc.addImage(ref, buf.toByteArray());

            // unit square → fill bbox in page space (Y-up); renderer flips the top-down raster
            OCDImage n = new OCDImage(ref).pixelSize(w, h);
            n.transform(JxTransform.of(new AffineTransform(b.getWidth(), 0, 0, b.getHeight(), b.getMinX(), b.getMinY())));
            n.alpha(alpha);
            emit(n);
        } catch (Exception e) {   // unsupported / malformed shading → skip, never crash the page
            JxLog.debug(PdfStreamEngine.class, "shading skipped (unsupported/malformed)", e);
        }
    }

    @Override public void drawImage(PDImage pdImage) throws IOException {
        // A stencil (ImageMask) has no colours of its own: it paints the CURRENT non-stroking
        // colour through its 1-bit mask. getImage() would give black — ask for the stencil
        // rendered in the live fill colour instead (exactly what PageDrawer does).
        BufferedImage bi = pdImage.isStencil()
                ? pdImage.getStencilImage(new java.awt.Color(fillArgb(), true))
                : pdImage.getImage();
        if (bi == null) return;

        String ref;
        byte[] jpeg = passThrough(pdImage);
        if (jpeg != null) {                                    // already a browser-ready JPEG — keep it
            ref = doc.newImageRef("jpg");
            doc.addImage(ref, jpeg);
        } else {
            ref = doc.newImageRef("png");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            ImageIO.write(bi, "png", buf);
            doc.addImage(ref, buf.toByteArray());
        }

        OCDImage n = new OCDImage(ref).pixelSize(bi.getWidth(), bi.getHeight());
        n.transform(JxTransform.of(getGraphicsState().getCurrentTransformationMatrix().createAffineTransform()));
        n.alpha((float) getGraphicsState().getNonStrokeAlphaConstant());
        emit(n);
    }

    /** The image's own compressed bytes when they can be shown as they are, else {@code null}.
     *
     *  <p>Decoding a JPEG and re-encoding it as PNG is lossless in pixels and ruinous in bytes: measured
     *  26× on a newspaper page, where 38 KB of source photographs became 997 KB and made up most of the
     *  container. A JPEG that a browser already renders correctly should simply travel.
     *
     *  <p>Every condition below is a case where it would NOT render correctly, so the pixels must be
     *  decoded after all: a stencil paints the fill colour through a mask rather than its own content; a
     *  soft or stencil mask needs an alpha channel JPEG cannot carry; a {@code /Decode} array inverts or
     *  remaps samples the codec knows nothing about; and a CMYK JPEG is the classic browser trap — Adobe's
     *  inverted APP14 flavour renders with swapped colours in engines that accept it at all. */
    private byte[] passThrough(PDImage pdImage) {
        try {
            if (pdImage.isStencil() || pdImage.getDecode() != null) return null;
            if (!(pdImage instanceof PDImageXObject xo)) return null;
            if (xo.getSoftMask() != null || xo.getMask() != null) return null;
            var cs = xo.getColorSpace();
            // DeviceRGB / DeviceGray, and the ICCBased spaces that ACTUALLY carry them: a photograph
            // written by any real producer is ICCBased, never DeviceRGB, and rejecting it would leave the
            // fast path firing on nothing. What matters is the component count — 3 or 1 — because that is
            // what decides whether a browser's JPEG decoder gets it right. Four components is CMYK, the
            // classic trap: Adobe's inverted APP14 flavour renders with swapped colours where it renders
            // at all, so it goes down the decode path.
            int comps = cs == null ? 0 : cs.getNumberOfComponents();
            boolean plain = cs instanceof PDDeviceRGB || cs instanceof PDDeviceGray
                    || (cs instanceof PDICCBased && (comps == 3 || comps == 1));
            if (!plain) return null;
            var filters = xo.getCOSObject().getFilters();
            String last = filters == null ? null : filters.toString();
            if (last == null || !last.contains("DCTDecode")) return null;
            byte[] raw;
            try (var in = xo.getStream().getCOSObject().createRawInputStream()) { raw = in.readAllBytes(); }
            // A chain like [ASCII85Decode, DCTDecode] leaves the JPEG only AFTER the outer filters run;
            // the raw stream is JPEG only when DCT is the whole chain.
            if (raw.length < 4 || (raw[0] & 0xFF) != 0xFF || (raw[1] & 0xFF) != 0xD8) return null;
            return raw;
        } catch (Exception e) {
            return null;   // anything unexpected → decode it, the slow path is always correct
        }
    }

    /** BT — start a fresh text-clip buffer (alongside the base text-matrix reset). */
    @Override public void beginText() throws IOException {
        super.beginText();
        textClippings = new ArrayList<>();
    }

    /** ET — fold any accumulated text clip (Tr modes 4–7) into the graphics-state clip, so content
     *  painted "through" the clipping text (e.g. an image filling textured/gradient glyphs) is masked
     *  to the glyph shapes. Mirrors {@code PageDrawer.endTextClip}; without it the following content
     *  would only see the rectangular {@code W} clip and render as solid boxes. */
    @Override public void endText() throws IOException {
        if (textClippings != null && !textClippings.isEmpty()
                && getGraphicsState().getTextState().getRenderingMode().isClip()) {
            GeneralPath clip = new GeneralPath(Path2D.WIND_NON_ZERO, textClippings.size());
            for (Shape s : textClippings) clip.append(s, false);   // union of the glyph outlines
            getGraphicsState().intersectClippingPath(clip);
            lastClipArea = null;   // clip mutated in place — drop the dedup cache so it re-registers
        }
        textClippings = null;
        super.endText();
    }

    /** Type3 glyphs are tiny content streams, not sfnt outlines: no font program can be
     *  captured. Process the charproc through THIS engine instead — its path/image operators
     *  fire under the glyph's matrix and the marks land in the model as regular vector
     *  content, so the glyph paints everywhere (page svg, re-export, clients). The letters
     *  are not text runs (no unicode extraction) — the honest trade for exact ink. */
    @Override protected void showType3Glyph(Matrix trm, org.apache.pdfbox.pdmodel.font.PDType3Font font,
                                            int code, Vector displacement) throws IOException {
        flushText();                                       // marks interleave with runs: keep z order
        var proc = font.getCharProc(code);
        if (proc != null) processType3Stream(proc, trm);
    }

    @Override protected void showGlyph(Matrix trm, PDFont font, int code, Vector displacement) throws IOException {
        // PDFBox's DEFAULT showGlyph is the type3 dispatcher (instanceof → showType3Glyph);
        // overriding it severs that route, so restore it explicitly before glyph capture.
        if (font instanceof org.apache.pdfbox.pdmodel.font.PDType3Font t3) { showType3Glyph(trm, t3, code, displacement); return; }
        String fontId = fonts.capture(font, code, sugarcube.jexter.core.JxText.sanitize(font.toUnicode(code)));
        if (fontId == null) return;

        int fill = fillArgb();
        int mode = getGraphicsState().getTextState().getRenderingMode().intValue();
        // Tr 4–7 add the glyph to the text clip. The base engine does not accumulate it, so we
        // rebuild the glyph's page-space outline from the font and buffer it for ET.
        if (textClippings != null && getGraphicsState().getTextState().getRenderingMode().isClip()) {
            Shape gc = clipGlyph(trm, font, code);
            if (gc != null) textClippings.add(gc);
        }
        AffineTransform at = trm.createAffineTransform();
        // Stroke paint is run state, but only in stroking modes (Tr 1/2/5/6): two stroked runs differing
        // only in stroke colour/width/style must split, else the run captures the first glyph's stroke for
        // all. In fill-only modes the stroke is unused, so it stays out of the key (no spurious split).
        String strokeSig = ((mode & 3) == 1 || (mode & 3) == 2)
                ? "|" + strokeArgb() + ":" + strokeWidth() : "";
        // Blend mode is run state too (the renderer composites text by node.blend, like paths). Non-Normal
        // only — Normal/unmapped is null, so the key is unchanged for the overwhelming fill+Normal case.
        String blend = blendName(getGraphicsState().getBlendMode());
        String key = fontId + "|" + fill + "|" + mode + "|" + sig(at)
                + strokeSig + (blend != null ? "|bm:" + blend : "");
        // A clip no larger than one glyph cell is a per-glyph self-clip (some generators clip
        // each letter to its own outline): visually neutral for the glyph it accompanies, so we
        // ignore it — keeps runs merged and avoids registering a tiny clip per letter.
        String cid = (mergeGlyphClips && isGlyphClip(at)) ? null : currentClipId();   // clip is run state: a real clip change starts a new run

        // continue the current run only if state matches AND the glyph sits on its baseline
        double localX = 0;
        boolean cont = run != null && key.equals(runKey) && java.util.Objects.equals(cid, runClipId);
        if (cont) {
            double[] o = { at.getTranslateX(), at.getTranslateY() };
            runInverse.transform(o, 0, o, 0, 1);
            if (Math.abs(o[1]) > 0.2) cont = false;   // moved to another line
            else localX = o[0];
        }
        if (!cont) startRun(fontId, fill, mode, at, key, cid);

        String uni = sugarcube.jexter.core.JxText.sanitize(font.toUnicode(code));
        double curX = localX * runSize;

        // Pure ink: the importer records only painted glyphs. Word spaces are NOT trusted from the source —
        // they are derived once, from glyph geometry, when lines are frozen (see Spacer).
        run.add(code, curX, uni);
    }

    private void startRun(String fontId, int fill, int mode, AffineTransform at, String key, String cid) {
        flushText();
        double fs = Math.hypot(at.getShearX(), at.getScaleY());   // effective em height = point size
        if (fs < 1e-6) fs = 1;
        AffineTransform base = new AffineTransform(at);
        base.scale(1.0 / fs, 1.0 / fs);                           // strip size, keep rotation/shear/pos
        runOrigin = at;
        runSize = fs;
        runKey = key;
        runClipId = cid;
        try { runInverse = at.createInverse(); } catch (Exception e) { runInverse = new AffineTransform(); }
        run = new OCDText(fontId, fs).fill(fill).renderMode(mode);
        if (run.hasStroke()) {
            run.strokePaint(strokeArgb(), strokeWidth());
            applyStrokeStyle(run);                                  // cap/join/miter/dash — parity with paths
        }
        run.transform(JxTransform.of(base));
        if (cid != null) run.clipId(cid);
        String bm = blendName(getGraphicsState().getBlendMode());   // blend rides on the node; renderer composites text by it
        if (bm != null) run.blend(bm);
    }

    void flushText() {
        if (run != null && !run.glyphs().isEmpty()) {
            run.z(z++);   // paint order; id minted later by IdStamper (clipId captured at startRun)
            addNode(run);
        }
        run = null;
        runOrigin = null;
        runInverse = null;
        runKey = null;
        runClipId = null;
    }

    /** The run-continuation key's matrix part. Called ONCE PER GLYPH, so `String.format` is the wrong
     *  tool here: it parses its format string and spins up a Formatter every time, and it measured 13%
     *  of a 600-page import's samples. This writes the same fixed-3-decimal text by hand — same
     *  rounding (HALF_UP on the magnitude, sign kept for a negative that rounds to zero, exactly what
     *  {@code %.3f} does), so runs group identically and the output stays byte-for-byte the same. */
    private static String sig(AffineTransform at) {
        StringBuilder b = new StringBuilder(28);
        f3(b, at.getScaleX()); b.append(',');
        f3(b, at.getShearY()); b.append(',');
        f3(b, at.getShearX()); b.append(',');
        f3(b, at.getScaleY());
        return b.toString();
    }

    /** {@code %.3f} for a finite double, without the Formatter. */
    private static void f3(StringBuilder b, double v) {
        if (!Double.isFinite(v)) { b.append(v); return; }        // NaN/Inf: let the JDK spell it
        boolean neg = v < 0 || (v == 0 && 1 / v < 0);            // keep the sign of -0.0, as %.3f does
        long n = Math.round(Math.abs(v) * 1000.0);
        if (neg) b.append('-');
        b.append(n / 1000).append('.');
        long f = n % 1000;
        if (f < 100) b.append('0');
        if (f < 10) b.append('0');
        b.append(f);
    }

    // ── emission ──────────────────────────────────────────────────────────────
    private void emit(OCDNode n) {
        flushText();   // commit any open text run first (z-order)
        n.z(z++);   // paint order; id minted later by IdStamper
        String cid = currentClipId();
        if (cid != null) n.clipId(cid);
        String name = BLEND.get(getGraphicsState().getBlendMode());
        if (name != null) n.blend(name);
        addNode(n);
    }

    /** Intersect the just-built path into the graphics-state clip (deferred from W). */
    private void applyPendingClip() {
        if (pendingClipRule == -1) return;
        path.setWindingRule(pendingClipRule);
        getGraphicsState().intersectClippingPath(new Area(path));
        pendingClipRule = -1;
    }

    /** True when the active clip is no larger than one glyph cell: a per-glyph self-clip that
     *  cannot cut the glyph it accompanies, so it is a no-op for text. A real text clip (column,
     *  box) is many times the point size and is not matched here. */
    private boolean isGlyphClip(AffineTransform at) {
        Area clip = getGraphicsState().getCurrentClippingPath();
        if (clip == null || clip.isEmpty()) return false;
        double fs = Math.hypot(at.getShearX(), at.getScaleY());
        Rectangle2D b = clip.getBounds2D();
        return fs > 0 && b.getWidth() <= fs * 1.6 && b.getHeight() <= fs * 1.6;
    }

    /** Page-space outline of one glyph for text clipping: {@code trm × fontMatrix × normalizedPath}
     *  — the same convention as {@link FontExtractor} and {@code PageDrawer.showFontGlyph}. Returns
     *  null for Type3 / bitmap fonts (no vector outline to clip with). */
    private static Shape clipGlyph(Matrix trm, PDFont font, int code) {
        if (!(font instanceof PDVectorFont vf)) return null;
        try {
            GeneralPath gp = vf.getNormalizedPath(code);
            if (gp == null) return null;
            AffineTransform at = trm.createAffineTransform();
            at.concatenate(font.getFontMatrix().createAffineTransform());   // glyph space → em, then trm → page
            return at.createTransformedShape(gp);
        } catch (Exception e) {
            return null;
        }
    }

    /** The current clip as a page-table id, or null when it still covers the whole page. */
    private String currentClipId() {
        Area clip = getGraphicsState().getCurrentClippingPath();
        if (clip == null || coversPage(clip)) return null;
        if (lastClipArea != null && clip.equals(lastClipArea)) return lastClipId;
        String id = "c" + (++clipSeq);
        page.addClip(new OCDClip(id, new JxPath(clip)));
        lastClipArea = clip;
        lastClipId = id;
        return id;
    }

    private boolean coversPage(Area clip) {
        JxRect b = page.effectiveBox();
        Rectangle2D r = clip.getBounds2D();
        return r.getMinX() <= b.minX() + 0.5 && r.getMinY() <= b.minY() + 0.5
            && r.getMaxX() >= b.maxX() - 0.5 && r.getMaxY() >= b.maxY() - 0.5;
    }

    /** PDF line width 0 means "the thinnest device line" (~1 device px). The SVG projection
     *  cannot say that (width 0 paints nothing, the CSS default 1 over-inks), so the ONE import
     *  convention maps it to 0.75 pt — one device pixel at 96 dpi — for every consumer alike. */
    private double strokeWidth() {
        double w = transformWidth(getGraphicsState().getLineWidth());
        return w < 0.05 ? 0.75 : w;
    }

    private void applyStrokeStyle(OCDPath n) { LineStyle s = lineStyle(); n.lineStyle(s.cap(), s.join(), s.miter(), s.dash(), s.phase()); }
    private void applyStrokeStyle(OCDText n) { LineStyle s = lineStyle(); n.lineStyle(s.cap(), s.join(), s.miter(), s.dash(), s.phase()); }

    private record LineStyle(int cap, int join, double miter, double[] dash, double phase) {}

    /** The current stroke style (cap/join/miter + CTM-scaled dash) — shared by the path and text appliers. */
    private LineStyle lineStyle() {
        PDGraphicsState gs = getGraphicsState();
        double[] dash = null;
        double phase = 0;
        PDLineDashPattern d = gs.getLineDashPattern();
        if (d != null && d.getDashArray() != null && d.getDashArray().length > 0) {
            float[] da = d.getDashArray();
            dash = new double[da.length];
            for (int i = 0; i < da.length; i++) dash[i] = transformWidth(da[i]);
            phase = transformWidth(d.getPhase());
        }
        return new LineStyle(gs.getLineCap(), gs.getLineJoin(), gs.getMiterLimit(), dash, phase);
    }

    private int fillArgb() {
        PDGraphicsState gs = getGraphicsState();
        return resolveArgb(gs.getNonStrokingColorSpace(), gs.getNonStrokingColor(), (float) gs.getNonStrokeAlphaConstant());
    }

    private int strokeArgb() {
        PDGraphicsState gs = getGraphicsState();
        return resolveArgb(gs.getStrokingColorSpace(), gs.getStrokingColor(), (float) gs.getAlphaConstant());
    }

    /**
     * Resolve a paint color to sRGB argb. Pattern color spaces (tiling / shading)
     * have no single colour — {@code PDPattern.toRGB} throws — so they are
     * flattened to a neutral fill (a documented limitation, like Separation;
     * future: sample the shading). Any other {@code toRGB} failure also falls
     * back to neutral instead of crashing on a malformed real-world colour.
     */
    private int resolveArgb(PDColorSpace cs, PDColor color, float alpha) {
        try {
            if (cs instanceof PDPattern) return JxColor.rgba(128, 128, 128, Math.round(alpha * 255)).argb();
            float[] rgb = cs.toRGB(color.getComponents());
            return argb(rgb, alpha);
        } catch (Exception e) {   // IOException, UnsupportedOperationException (patterns), malformed CS
            JxLog.debug(PdfStreamEngine.class, "color conversion failed \u2192 neutral grey", e);
            return JxColor.rgba(128, 128, 128, Math.round(alpha * 255)).argb();
        }
    }

    // ── gradients (axial/radial shadings → first-class OCDGradient) ───────────────
    /** If the current non-stroking colour is an axial/radial shading <i>pattern</i>, build its
     *  gradient (its colour ramp + the pattern→page matrix); else null. */
    private OCDGradient fillGradientOrNull() {
        PDGraphicsState gs = getGraphicsState();
        if (!(gs.getNonStrokingColorSpace() instanceof PDPattern pcs)) return null;
        try {
            PDAbstractPattern pat = pcs.getPattern(gs.getNonStrokingColor());
            if (pat instanceof PDShadingPattern sp) {
                Matrix m = Matrix.concatenate(getInitialMatrix(), sp.getMatrix());   // pattern space → page
                return gradientOf(sp.getShading(), m, (float) gs.getNonStrokeAlphaConstant());
            }
        } catch (Exception e) {
            JxLog.debug(PdfStreamEngine.class, "shading pattern resolve failed", e);
        }
        return null;
    }

    /** Sample a PDF axial (type 2) / radial (type 3) shading into an {@link OCDGradient}. The
     *  colour ramp is sampled from the shading's function across its domain; {@code m} maps the
     *  shading's own coordinate space to page space. Returns null for other shading types (the
     *  caller rasterises those). */
    private static OCDGradient gradientOf(PDShading sh, Matrix m, float alpha) {
        try {
            int type = sh.getShadingType();
            float[] co; float[] dom; COSArray ext;
            if (type == 2) {
                PDShadingType2 s = (PDShadingType2) sh;
                co = s.getCoords().toFloatArray(); dom = toArr(s.getDomain()); ext = s.getExtend();
            } else if (type == 3) {
                PDShadingType3 s = (PDShadingType3) sh;
                co = s.getCoords().toFloatArray(); dom = toArr(s.getDomain()); ext = s.getExtend();
            } else {
                return null;   // 1 (function-based) / 4–7 (mesh) → rasterise
            }
            double[] coords = new double[co.length];
            for (int i = 0; i < co.length; i++) coords[i] = co[i];
            float d0 = (dom != null && dom.length == 2) ? dom[0] : 0f;
            float d1 = (dom != null && dom.length == 2) ? dom[1] : 1f;
            boolean e0 = ext != null && ext.size() == 2 && ext.getObject(0) instanceof COSBoolean b0 && b0.getValue();
            boolean e1 = ext != null && ext.size() == 2 && ext.getObject(1) instanceof COSBoolean b1 && b1.getValue();

            PDColorSpace cs = sh.getColorSpace();
            int a8 = clamp8(Math.round(alpha * 255));
            final int N = 33;                          // linear interpolation between 33 samples
            float[] offs = new float[N];
            int[]   cols = new int[N];
            for (int i = 0; i < N; i++) {
                float f = i / (float) (N - 1);
                float[] rgb = cs.toRGB(sh.evalFunction(new float[]{ d0 + (d1 - d0) * f }));
                cols[i] = JxColor.rgba(clamp8(Math.round(rgb[0] * 255)),
                                       clamp8(Math.round(rgb[1] * 255)),
                                       clamp8(Math.round(rgb[2] * 255)), a8).argb();
                offs[i] = f;
            }
            OCDGradient.Kind kind = (type == 2) ? OCDGradient.Kind.LINEAR : OCDGradient.Kind.RADIAL;
            OCDGradient g = new OCDGradient(kind, coords, offs, cols,
                    JxTransform.of(m.createAffineTransform()), e0, e1);
            return g.isValid() ? g : null;
        } catch (Exception e) {
            JxLog.debug(PdfStreamEngine.class, "gradient extraction failed \u2192 fallback", e);
            return null;
        }
    }

    private static float[] toArr(COSArray a) { return a == null ? null : a.toFloatArray(); }
    private static int clamp8(int v) { return v < 0 ? 0 : Math.min(255, v); }

    private static int argb(float[] rgb, float a) {
        return JxColor.rgba(Math.round(rgb[0] * 255), Math.round(rgb[1] * 255), Math.round(rgb[2] * 255),
                Math.round(a * 255)).argb();
    }

    private static final Map<BlendMode, String> BLEND = new IdentityHashMap<>();
    static {
        BLEND.put(BlendMode.MULTIPLY, "Multiply");
        BLEND.put(BlendMode.SCREEN, "Screen");
        BLEND.put(BlendMode.OVERLAY, "Overlay");
        BLEND.put(BlendMode.DARKEN, "Darken");
        BLEND.put(BlendMode.LIGHTEN, "Lighten");
        BLEND.put(BlendMode.COLOR_DODGE, "ColorDodge");
        BLEND.put(BlendMode.COLOR_BURN, "ColorBurn");
        BLEND.put(BlendMode.HARD_LIGHT, "HardLight");
        BLEND.put(BlendMode.SOFT_LIGHT, "SoftLight");
        BLEND.put(BlendMode.DIFFERENCE, "Difference");
        BLEND.put(BlendMode.EXCLUSION, "Exclusion");
        BLEND.put(BlendMode.HUE, "Hue");
        BLEND.put(BlendMode.SATURATION, "Saturation");
        BLEND.put(BlendMode.COLOR, "Color");
        BLEND.put(BlendMode.LUMINOSITY, "Luminosity");
    }
}
