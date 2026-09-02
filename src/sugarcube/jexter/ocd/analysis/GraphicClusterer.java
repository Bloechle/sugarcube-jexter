package sugarcube.jexter.ocd.analysis;

import sugarcube.jexter.core.JxRect;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDGraphic;
import sugarcube.jexter.ocd.model.OCDGroup;
import sugarcube.jexter.ocd.model.OCDNode;
import sugarcube.jexter.ocd.model.OCDPage;
import sugarcube.jexter.ocd.model.OCDParagraph;
import sugarcube.jexter.ocd.model.OCDPath;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cluster contiguous runs of vector {@link OCDPath} into {@link OCDGraphic} nodes — the vector
 * analogue of what {@link Paragrapher} does for text. A "graphic" is a drawing (logo, icon, chart
 * body): several paths that form one visual unit.
 *
 * <p>The hard part is that a graphic has <b>no marker</b> in the PDF, so segmentation is heuristic
 * and prone to swallowing page furniture (frames, full-page backgrounds, table grids, rules). The
 * guards here follow the battle-tested recipe of pymupdf4llm (its {@code is_significant} +
 * candidate filtering + graphics limit), plus a paint-order contiguity guard of our own:
 *
 * <ul>
 *   <li><b>Candidate filter</b> — a path that spans (almost) a whole page dimension is furniture
 *       (frame / background / full-width rule) and is never clustered; degenerate dots are dropped.</li>
 *   <li><b>Contiguity</b> — only a <i>contiguous run</i> in paint order is wrapped, so the grouping
 *       can never reorder painting (the fidelity bar is preserved exactly).</li>
 *   <li><b>Significance</b> — a run is a graphic only if its paths are not all the same width or all
 *       the same height (that signature is a grid / set of rules / a frame) and at least one path
 *       genuinely occupies the cluster's interior (real 2-D content, not just boundary lines).</li>
 *   <li><b>Graphics limit</b> — pages with a huge number of paths (slide-deck vector explosions)
 *       are left untouched.</li>
 * </ul>
 *
 * <p>Scope: vector-only. Elaborate figures mixing path + text + image (a chart with its labels) are
 * a logical-figure concern, deferred to the LLM-refine layer ({@link Refiner}); the heuristic
 * structure pass ({@link StructureBuilder}) emits HEADING + PARAGRAPH only and leaves graphics in the
 * presentation layer.
 */
public final class GraphicClusterer {

    private GraphicClusterer() {}

    private static final double PAGE_SPAN     = 0.95;   // bbox covering ≥ this of a page dimension = furniture
    private static final double MIN_DIM       = 3.0;    // both bbox dims ≤ this = degenerate
    private static final double GAP_FLOOR     = 4.0;    // single-linkage: always join within this gap (points)
    private static final double GAP_FRAC      = 0.5;    // …else join within this × the smaller path's scale (adaptive)
    private static final double INTERIOR_SHRINK = 0.05; // significance: shrink each side by this × max-dim → ~90% interior
    private static final double RULE_THICK    = 4.0;    // a path thinner than this on its short side is a rule/hairline, not a 2-D block
    private static final int    MIN_PATHS     = 2;      // a single path is not a graphic
    private static final int    GRAPHICS_LIMIT = 4000;  // too many paths on a page → leave the whole page loose

    public static void cluster(OCDDocument doc) {
        for (OCDPage p : doc.pages()) {
            JxRect box = p.cropBox() != null ? p.cropBox() : p.mediaBox();
            cluster(p.content(), box.width(), box.height());
        }
    }

    /** Compose {@link OCDGraphic}s in paint order, in place. A graphic grows from a candidate path and
     *  accretes (a) later candidate paths that single-linkage-join it or fall inside its bounds, and
     *  (b) the text / images whose centre lies inside its bounds — the labels and insets that belong to
     *  the drawing. The run is a <i>contiguous</i> span, so wrapping it never reorders painting: the
     *  render is byte-identical and z-order is preserved. A run is kept only when its paths form genuine
     *  2-D content; otherwise its nodes stay loose. "Most of the time it is a sequence of paths"; a chart
     *  with its tick labels, or a panel with its caption text, becomes one composite graphic. */
    private static void cluster(List<OCDNode> nodes, double pw, double ph) {
        // recurse first (mirror Paragrapher); don't descend into already-built paragraphs/graphics
        for (OCDNode n : nodes)
            if (n instanceof OCDGroup g && !(n instanceof OCDParagraph) && !(n instanceof OCDGraphic))
                cluster(g.children(), pw, ph);

        long paths = nodes.stream().filter(n -> n instanceof OCDPath).count();
        if (paths > GRAPHICS_LIMIT) return;             // vector explosion → leave the page as-is

        List<OCDNode> out = new ArrayList<>(nodes.size());
        int i = 0, N = nodes.size();
        while (i < N) {
            if (!isCandidate(nodes.get(i), pw, ph)) { out.add(nodes.get(i)); i++; continue; }

            List<OCDNode> run = new ArrayList<>();      // the contiguous span we will wrap (paths + engulfed labels/insets)
            List<OCDPath> runPaths = new ArrayList<>();
            OCDPath p0 = (OCDPath) nodes.get(i);
            JxRect bbox = p0.bounds();
            run.add(p0); runPaths.add(p0);
            int j = i + 1;
            while (j < N) {
                OCDNode m = nodes.get(j);
                if (isCandidate(m, pw, ph)) {                       // a path joins if it links the cluster or sits inside it
                    OCDPath mp = (OCDPath) m;
                    if (nearAny(runPaths, mp) || inside(bbox, mp.bounds())) { run.add(mp); runPaths.add(mp); bbox = bbox.union(mp.bounds()); j++; continue; }
                    break;                                          // a far path opens the next run
                }
                JxRect mb = m.bounds();                             // text / image: engulf only when it lies inside the drawing
                if (mb != null && !mb.isEmpty() && bbox.contains(mb.x() + mb.width() / 2, mb.y() + mb.height() / 2)) { run.add(m); j++; continue; }
                break;                                              // body content outside the drawing → stop
            }

            if (runPaths.size() >= MIN_PATHS && isSignificant(runPaths)) {
                OCDGraphic gr = new OCDGraphic();
                gr.z(run.get(0).z());                               // paint order; id minted later by IdStamper.fill
                for (OCDNode m : run) gr.add(m);
                out.add(gr);
            } else {
                out.addAll(run);                                    // not a graphic → leave its nodes loose, order intact
            }
            i = j;
        }
        nodes.clear();
        nodes.addAll(out);
    }

    /** True when {@code r}'s centre lies inside {@code box} (engulf test for paths drawn within a drawing). */
    private static boolean inside(JxRect box, JxRect r) {
        return r != null && !r.isEmpty() && box.contains(r.x() + r.width() / 2, r.y() + r.height() / 2);
    }

    /** True if {@code p} is within the adaptive gap of any current member (single-linkage join test). */
    private static boolean nearAny(List<OCDPath> members, OCDPath p) {
        for (OCDPath m : members) if (near(m, p)) return true;
        return false;
    }

    /** Scale-adaptive proximity: two paths belong to the same drawing when the gap between their bounding
     *  boxes (Euclidean edge distance, 0 when they overlap) is at most {@link #GAP_FLOOR}, or at most
     *  {@link #GAP_FRAC} × the smaller path's larger dimension. The local scale (not a cluster-global one)
     *  lets big shapes accrete from a bit farther without unbounded chaining across empty space. */
    private static boolean near(OCDPath a, OCDPath b) {
        JxRect ra = a.bounds(), rb = b.bounds();
        double scale = Math.min(Math.max(ra.width(), ra.height()), Math.max(rb.width(), rb.height()));
        double tol = Math.max(GAP_FLOOR, GAP_FRAC * scale);
        double dx = Math.max(0, Math.max(ra.x() - rb.right(), rb.x() - ra.right()));
        double dy = Math.max(0, Math.max(ra.y() - rb.bottom(), rb.y() - ra.bottom()));
        return Math.hypot(dx, dy) <= tol;
    }

    /** A path eligible for clustering: a real local shape, not page furniture nor a degenerate dot. */
    private static boolean isCandidate(OCDNode n, double pw, double ph) {
        if (!(n instanceof OCDPath p)) return false;
        JxRect b = p.bounds();
        double bw = b.width(), bh = b.height();
        if (bw >= pw * PAGE_SPAN || bh >= ph * PAGE_SPAN) return false;   // frame / background / full rule
        if (bw <= MIN_DIM && bh <= MIN_DIM) return false;                 // degenerate
        return true;
    }

    /** Port of pymupdf4llm's {@code is_significant}: the cluster must hold genuine 2-D content, not a
     *  grid / set of rules / a bare frame. A run whose paths share one extent (all the same width or all
     *  the same height) is a grid of rules — <i>unless</i> the repeated marks are solid 2-D blocks (a row
     *  of panels, the bars of a chart), which is a real drawing. At least one such mark must occupy the
     *  cluster's ~90% interior. */
    private static boolean isSignificant(List<OCDPath> paths) {
        JxRect box = JxRect.EMPTY;
        for (OCDPath p : paths) box = box.union(p.bounds());
        if (box.isEmpty()) return false;

        Set<Long> widths = new HashSet<>(), heights = new HashSet<>();
        for (OCDPath p : paths) {
            JxRect r = p.bounds();
            widths.add(Math.round(r.width()));
            heights.add(Math.round(r.height()));
        }
        widths.add(Math.round(box.width()));
        heights.add(Math.round(box.height()));
        boolean uniform = widths.size() == 1 || heights.size() == 1;      // one extent repeated: a grid, or a row of identical marks

        double d = Math.max(box.width(), box.height()) * INTERIOR_SHRINK;
        JxRect interior = box.inflate(-d, -d);
        for (OCDPath p : paths) {
            JxRect r = p.bounds();
            if (r.isEmpty() || r.intersection(interior).isEmpty()) continue;   // pure line / boundary-only: not interior content
            boolean solid2D = p.isFilled() && Math.min(r.width(), r.height()) > RULE_THICK;
            if (uniform && !solid2D) continue;                                 // uniform thin marks: a grid of rules, not a drawing
            return true;                                                       // a real 2-D mark in the interior → significant
        }
        return false;
    }
}
