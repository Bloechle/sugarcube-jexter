package sugarcube.jexter.trace;

import sugarcube.jexter.core.JxColor;
import sugarcube.jexter.core.JxPath;
import sugarcube.jexter.ocd.model.OCDGraphic;
import sugarcube.jexter.ocd.model.OCDPath;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Raster → vector entry point. Turns a {@link BufferedImage} into a stack of
 * filled {@link TracedShape}s (Potrace-grade curves), then optionally into an
 * {@link OCDGraphic} for the model.
 *
 * <pre>
 *   List&lt;TracedShape&gt; shapes = Tracer.trace(img, TraceOptions.color().colors(6));
 *   OCDGraphic g = Tracer.toGraphic(shapes);
 *   // caller sets g.transform(...) to map pixel space → page space
 * </pre>
 *
 * <p><b>BW</b> emits one black layer from a luminance threshold. <b>COLOR</b>
 * median-cuts a palette and traces one layer per colour, stacked back-to-front
 * (largest area first) so foreground detail paints on top. All layers' contours
 * are folded into one even-odd {@link JxPath} per colour, so interior holes show
 * through.
 */
public final class Tracer {

    private Tracer() {}

    public static List<TracedShape> trace(BufferedImage img, TraceOptions o) {
        int w = img.getWidth(), h = img.getHeight();
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
        return o.mode == TraceOptions.Mode.BW ? traceBw(argb, w, h, o) : traceColor(argb, w, h, o);
    }

    // ── BW ─────────────────────────────────────────────────────────────────────

    private static List<TracedShape> traceBw(int[] argb, int w, int h, TraceOptions o) {
        Bitmap bm = new Bitmap(w, h);
        for (int i = 0; i < argb.length; i++) {
            int a = (argb[i] >>> 24) & 0xFF;
            int lum = a == 0 ? 255 : luminance(argb[i]);           // transparent → background
            boolean on = lum < o.threshold;
            bm.set(i % w, i / w, o.invert ? !on : on);
        }
        List<JxPath> contours = Potrace.trace(bm, o);
        if (contours.isEmpty()) return List.of();
        JxPath merged = mergeEvenOdd(contours);
        double area = onCount(bm);
        return List.of(new TracedShape(merged, JxColor.BLACK.argb(), area));
    }

    // ── COLOR ────────────────────────────────────────────────────────────────

    private static List<TracedShape> traceColor(int[] argb, int w, int h, TraceOptions o) {
        MedianCut q = MedianCut.quantize(argb, o.colors, /*alphaThreshold*/ 128);
        List<TracedShape> shapes = new ArrayList<>();
        for (int ci = 0; ci < q.palette.length; ci++) {
            Bitmap bm = new Bitmap(w, h);
            long count = 0;
            for (int i = 0; i < q.index.length; i++)
                if (q.index[i] == ci) { bm.set(i % w, i / w, true); count++; }
            if (count <= o.turdSize) continue;
            List<JxPath> contours = Potrace.trace(bm, o);
            if (contours.isEmpty()) continue;
            shapes.add(new TracedShape(mergeEvenOdd(contours), q.palette[ci], count));
        }
        // back-to-front: biggest coverage first (background), small detail painted last
        shapes.sort(Comparator.comparingDouble(TracedShape::area).reversed());
        return shapes;
    }

    // ── bridge to the OCD model ────────────────────────────────────────────────

    /**
     * Wrap traced shapes in an {@link OCDGraphic}, in paint order (z ascending).
     * Paths stay in pixel space; the caller sets the graphic's transform to place
     * it (e.g. mapping the source image's unit square through its CTM).
     */
    public static OCDGraphic toGraphic(List<TracedShape> shapes) {
        OCDGraphic g = new OCDGraphic();
        float z = 0;
        for (TracedShape s : shapes) {
            OCDPath path = new OCDPath(s.path()).fill(s.argb());   // winding rule already set on the path
            path.z(z++);
            g.add(path);
        }
        return g;
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static int luminance(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return (int) Math.round(0.2126 * r + 0.7152 * g + 0.0722 * b);
    }

    /** Fold every contour into one even-odd path: nesting parity cuts holes regardless of contour orientation. */
    private static JxPath mergeEvenOdd(List<JxPath> contours) {
        JxPath merged = new JxPath();
        merged.evenOdd();
        for (JxPath c : contours) merged.append(c, false);
        return merged;
    }

    private static long onCount(Bitmap bm) {
        long n = 0;
        for (int y = 0; y < bm.h; y++) for (int x = 0; x < bm.w; x++) if (bm.get(x, y)) n++;
        return n;
    }
}
