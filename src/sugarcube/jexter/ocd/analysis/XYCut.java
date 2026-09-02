package sugarcube.jexter.ocd.analysis;

import sugarcube.jexter.core.JxRect;
import sugarcube.jexter.core.JxNum;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Generic <b>XY-Cut+</b> reading-order &amp; segmentation engine — orders/segments any items by their
 * bounding boxes, page-space y-up (higher y = nearer the top). Shared by {@link Paragrapher} (orders
 * the content flow into reading order) and {@link StructureBuilder} (over logical blocks), so the
 * project keeps a single, authoritative layout algorithm for both {@link #order} (flatten to reading
 * order) and {@link #segment} (keep the leaf grouping).
 *
 * <p><b>Why "XY-Cut+" and not "XY-Cut++".</b> Plain recursive XY-cut (Nagy &amp; Seth, 1984) splits a
 * region along the widest empty whitespace band per axis and recurses; it fails on the <i>L-shape
 * problem</i>, where one full-width element (a banner, a spanning figure) crosses the column gutter
 * and erases the valley, collapsing the columns into one mis-ordered run. The paper <b>XY-Cut++</b>
 * (Liu, Li &amp; Wei, <i>Advanced Layout Ordering via Hierarchical Mask Mechanism</i>,
 * arXiv:2504.10258, 2025) fixes layout ordering with <i>three</i> pillars — pre-mask processing,
 * multi-granularity segmentation, and cross-modal matching. We implement the <b>geometric core</b>
 * (the first two), deliberately drop the model/semantic layer, and adapt the masking criterion to our
 * input granularity. To be honest about that gap the variant is named with a single {@code +}: the
 * geometric enhancements over classic XY-cut, one step short of the paper's {@code ++}.
 *
 * <p><b>What we take from the paper (geometry):</b>
 * <ol>
 *   <li><b>Cross-layout masking</b> — a gutter-spanning element is pulled out before the cut, then
 *       re-inserted by vertical position, so it never blocks a valley (paper pillar 1).</li>
 *   <li><b>Relative-depth valleys</b> — a valley is scored by its depth relative to the surrounding
 *       peaks (with a noise floor), not by raw width, so a near-empty corridor still cuts and page
 *       margins never do (paper's density-aware splitting).</li>
 *   <li><b>Adaptive thresholds</b> — the minimum gap per axis is derived from the document's own
 *       median block dimensions, not a fixed constant.</li>
 * </ol>
 * Density-driven axis selection prefers the deeper valley (horizontal on a tie → top before bottom;
 * vertical → left before right). Pure geometry, deterministic, no ML.
 *
 * <p><b>Where we differ from XY-Cut++ (by design or necessity):</b>
 * <ul>
 *   <li><b>No cross-modal matching</b> (paper pillar 3): the paper's edge-weighted margin distance
 *       matches visual regions to text via a model (+2.7 BLEU). We are pure geometry — deterministic,
 *       no model — so this pillar is intentionally absent. Cross-modal/semantic structure is deferred
 *       to the downstream LLM-refine layer, not done here.</li>
 *   <li><b>No semantic-label priors:</b> the paper masks elements whose detected <i>type</i> is
 *       title/figure/table (shallow semantic labels as structural priors). We mask by pure geometry —
 *       <i>type-agnostic</i> — because at this stage the elements are untyped.</li>
 *   <li><b>Different cross criterion:</b> the paper detects cross elements with
 *       {@code width > β·median ∧ Σ overlap ≥ 2} on <i>region-level typed boxes</i>. We run at finer
 *       <i>line</i> granularity, where that literal test over-flags (every full-width line overlaps
 *       ≥2 others) — measured as +52 spurious leaves on single-column Fedlex. So {@link #classifyCross}
 *       instead requires the wide element to <b>straddle a real gutter</b>: among the items in its own
 *       vertical band that it spans horizontally, two must be mutually <i>horizontally disjoint yet
 *       vertically overlapping</i> (side-by-side columns). This is a deviation from the paper's
 *       formula, forced by line- vs region-granularity, and verified to be inert on single-column
 *       input (Fedlex leaves/blocks byte-identical) while still splitting the L-shape correctly.</li>
 * </ul>
 * Net: the closest kin is a geometry-only XY-Cut++ implementation (e.g. OpenDataLoader's), not the
 * full PaddleX pipeline (liushuai35/PaddleXrc) which carries the semantic-prior and cross-modal parts.
 *
 * <p>This engine is rendering-orthogonal: masking and cutting reorder/group items, never touching
 * glyph paint or z-order, so {@code Fid2 = 0} is preserved by construction. The gated,
 * paragraph-preserving segmentation counterpart that projects leaves to paragraphs is
 * {@link Paragrapher#recompose}.
 */
public final class XYCut {

    private XYCut() {}

    /** Wider than {@code β · medianWidth} ⇒ a cross-layout candidate (then confirmed by a straddled gutter). */
    private static final double CROSS_BETA          = 1.3;
    /** A cross candidate must span at least this many band-mates (so a side-by-side disjoint pair can exist). */
    private static final int    CROSS_MIN_OVERLAPS  = 2;
    /** Minimum relative valley depth (0–1) to accept a cut. */
    private static final double MIN_VALLEY_DEPTH    = 0.10;
    /** Profile floor: coverage ≤ NOISE × peak counts as empty. */
    private static final double NOISE               = 0.05;
    /** Profile resolution cap (buckets); larger spans are scaled into this. */
    private static final int    MAX_RES             = 4096;

    /** Order {@code items} top-to-bottom, left-to-right by reading flow. Items with no usable
     *  bounds keep their input order and trail at the end (stable). */
    public static <T> List<T> order(List<T> items, Function<T, JxRect> bounds) {
        if (items == null) return new ArrayList<>();
        if (items.size() <= 1) return new ArrayList<>(items);

        List<Item<T>> all = new ArrayList<>(items.size());
        List<T> tail = new ArrayList<>();
        for (T it : items) {
            JxRect b = bounds.apply(it);
            if (b != null && !b.isEmpty()) all.add(new Item<>(it, b));
            else tail.add(it);
        }
        if (all.isEmpty()) return new ArrayList<>(items);

        Metrics m = metrics(all);
        List<Item<T>> cross = new ArrayList<>(), normal = new ArrayList<>();
        classifyCross(all, m, cross, normal);

        List<Item<T>> flat = new ArrayList<>();
        for (List<Item<T>> leaf : split(normal, m)) flat.addAll(leaf);   // one engine: order = the leaves, flattened
        List<Item<T>> ordered = reinsertCross(flat, cross);

        List<T> out = new ArrayList<>(items.size());
        for (Item<T> it : ordered) out.add(it.v);
        out.addAll(tail);
        return out;
    }

    /** The X-Y tree's <b>leaves</b>: the geometric segments the whitespace cuts isolate, in reading order.
     *  Same recursion as {@link #order} but keeping the leaf grouping instead of flattening it — so a leaf
     *  is one indivisible block (a column's paragraph, a cell) and the leaf order is the reading order.
     *  This is XY-Cut used as the segmenter it is, not merely a sorter.
     *  <p>Full XY-Cut+: like {@link #order}, it <b>masks cross-layout</b> elements first — a full-width
     *  banner that crosses a column gutter is pulled out before the cut so it can't erase the valley and
     *  collapse the columns (the L-shape problem), then re-inserted as its own leaf by vertical position.
     *  On single-column input no element is cross, so this is identical to a plain recursive cut. */
    public static <T> List<List<T>> segment(List<T> items, Function<T, JxRect> bounds) {
        List<List<T>> out = new ArrayList<>();
        if (items == null || items.isEmpty()) return out;
        List<Item<T>> all = new ArrayList<>(items.size());
        List<T> unplaceable = new ArrayList<>();
        for (T it : items) {
            JxRect b = bounds.apply(it);
            if (b != null && !b.isEmpty()) all.add(new Item<>(it, b));
            else unplaceable.add(it);          // no geometry to cut on — but it still leaves, see below
        }
        if (all.isEmpty()) { out.add(new ArrayList<>(items)); return out; }
        Metrics m = metrics(all);
        List<Item<T>> cross = new ArrayList<>(), normal = new ArrayList<>();
        classifyCross(all, m, cross, normal);                                       // pull out gutter-spanning banners
        List<List<Item<T>>> leaves = split(normal.isEmpty() ? all : normal, m);     // columns are now separable
        if (!normal.isEmpty() && !cross.isEmpty())
            leaves = reinsertCrossLeaves(leaves, split(cross, m));                   // banners back, each as its own leaf, by y
        for (List<Item<T>> leaf : leaves) out.add(values(leaf));                     // one engine: segment = the leaves, grouped
        // A PARTITION: whatever comes in, comes out. An item with no usable geometry cannot be ordered, so
        // it lands last — but it lands. Dropping it here made it vanish from every text projection while it
        // went on painting correctly, which is the worst shape a loss can take: the page looks right and
        // reads short, and no pixel gate can see it.
        if (!unplaceable.isEmpty()) out.add(unplaceable);
        return out;
    }

    /** Re-insert masked cross-layout leaves (grouped banners) into the column-ordered leaf list — each
     *  before the first leaf that begins below it. The leaf-level analogue of {@link #reinsertCross}. */
    private static <T> List<List<Item<T>>> reinsertCrossLeaves(List<List<Item<T>>> leaves, List<List<Item<T>>> crossLeaves) {
        List<List<Item<T>>> result = new ArrayList<>(leaves);
        crossLeaves.sort(Comparator.comparingDouble((List<Item<T>> c) -> -topCy(c)));   // top (higher y) first
        for (List<Item<T>> c : crossLeaves) {
            double cTop = topCy(c);
            int pos = result.size();
            for (int i = 0; i < result.size(); i++) if (topCy(result.get(i)) < cTop) { pos = i; break; }
            result.add(pos, c);
        }
        return result;
    }

    /** Top edge (max cy, y-up) of a leaf — its vertical position for reading-order re-insertion. */
    private static <T> double topCy(List<Item<T>> leaf) {
        double t = Double.NEGATIVE_INFINITY;
        for (Item<T> it : leaf) t = Math.max(t, it.cy);
        return t;
    }

    /** The single recursive XY-Cut+ core: split a region at its deepest relative valley (horizontal
     *  before vertical on a tie), recursing until no valley clears {@link #MIN_VALLEY_DEPTH}; each
     *  indivisible leaf is appended as its own group, top-to-bottom then left-to-right. {@link #order}
     *  flattens these leaves; {@link #segment} keeps them grouped — one recursion, two views. */
    private static <T> void splitInto(List<Item<T>> items, Metrics m, List<List<Item<T>>> leaves) {
        if (items.size() <= 1) { leaves.add(new ArrayList<>(items)); return; }
        JxRect region = bbox(items);
        Valley hV = deepestValley(profile(items, region, true),  m.minHGap);
        Valley vV = deepestValley(profile(items, region, false), m.minVGap);
        double hd = hV != null ? hV.depth : 0, vd = vV != null ? vV.depth : 0;
        if (hd >= vd && hd > MIN_VALLEY_DEPTH) {
            List<Item<T>> top = new ArrayList<>(), bottom = new ArrayList<>();
            for (Item<T> it : items) (it.cy > hV.cut ? top : bottom).add(it);
            if (!top.isEmpty() && !bottom.isEmpty()) { splitInto(top, m, leaves); splitInto(bottom, m, leaves); return; }
        }
        if (vd > MIN_VALLEY_DEPTH) {
            List<Item<T>> left = new ArrayList<>(), right = new ArrayList<>();
            for (Item<T> it : items) (it.cx < vV.cut ? left : right).add(it);
            if (!left.isEmpty() && !right.isEmpty()) { splitInto(left, m, leaves); splitInto(right, m, leaves); return; }
        }
        List<Item<T>> leaf = new ArrayList<>(items);
        leaf.sort(Comparator.comparingDouble((Item<T> it) -> -it.cy).thenComparingDouble(it -> it.cx));
        leaves.add(leaf);
    }

    /** Run {@link #splitInto} and return the leaf groups, each already in reading order. */
    private static <T> List<List<Item<T>>> split(List<Item<T>> items, Metrics m) {
        List<List<Item<T>>> leaves = new ArrayList<>();
        splitInto(items, m, leaves);
        return leaves;
    }

    private static <T> List<T> values(List<Item<T>> items) {
        List<T> v = new ArrayList<>(items.size());
        for (Item<T> it : items) v.add(it.v);
        return v;
    }

    // ── adaptive metrics ─────────────────────────────────────────────────────
    private static <T> Metrics metrics(List<Item<T>> items) {
        double[] w = new double[items.size()], h = new double[items.size()];
        for (int i = 0; i < items.size(); i++) { w[i] = items.get(i).b.width(); h[i] = items.get(i).b.height(); }
        Metrics m = new Metrics();
        m.medianWidth  = JxNum.median(w);
        m.medianHeight = JxNum.median(h);
        m.minHGap = m.medianHeight * 0.8;    // ≈ one line height for a horizontal split
        m.minVGap = m.medianWidth  * 0.15;   // ≈ 15 % of a median block for a vertical split
        return m;
    }

    // ── cross-layout masking (L-shape prevention) ────────────────────────────
    /** A wide element is cross-layout only when it <b>bridges a gutter</b>: it must cover two other
     *  items that are themselves horizontally disjoint (a real column gap runs under it). A plain
     *  full-width line in single-column text overlaps only mutually-overlapping lines — no disjoint
     *  pair, so it is never masked. This keeps {@link #segment} (leaf grouping is sensitive to a stray
     *  pull-out) correct on single-column input while still catching true banners. */
    private static <T> void classifyCross(List<Item<T>> all, Metrics m,
                                          List<Item<T>> cross, List<Item<T>> normal) {
        double threshold = CROSS_BETA * m.medianWidth;
        for (Item<T> bi : all) {
            boolean isCross = false;
            if (bi.b.width() > threshold) {
                List<Item<T>> cov = new ArrayList<>();
                for (Item<T> other : all)
                    if (other != bi && hOverlap(bi.b, other.b) > 0 && vOverlap(bi.b, other.b) > 0) cov.add(other);  // in bi's own band
                if (cov.size() >= CROSS_MIN_OVERLAPS)
                    for (int i = 0; i < cov.size() && !isCross; i++)
                        for (int j = i + 1; j < cov.size(); j++)
                            if (hOverlap(cov.get(i).b, cov.get(j).b) <= 0          // horizontally disjoint…
                                    && vOverlap(cov.get(i).b, cov.get(j).b) > 0) { // …yet vertically overlapping ⇒ side-by-side columns under a real gutter
                                isCross = true; break;
                            }
            }
            (isCross ? cross : normal).add(bi);
        }
    }

    private static <T> List<Item<T>> reinsertCross(List<Item<T>> ordered, List<Item<T>> cross) {
        if (cross.isEmpty()) return ordered;
        cross.sort(Comparator.comparingDouble((Item<T> c) -> -c.cy));   // top (higher y) first
        List<Item<T>> result = new ArrayList<>(ordered);
        for (Item<T> c : cross) {
            int pos = result.size();
            for (int i = 0; i < result.size(); i++)
                if (result.get(i).cy < c.cy) { pos = i; break; }          // first block below the banner
            result.add(pos, c);
        }
        return result;
    }

    // ── recursive projective cut: see splitInto (the single XY-Cut+ core) ────

    /** Coverage histogram along one axis, carrying the geometry to map a bucket back to a coordinate.
     *  {@code yAxis=true} projects onto Y (for horizontal cuts). */
    private static <T> Axis profile(List<Item<T>> items, JxRect region, boolean yAxis) {
        double span = yAxis ? region.height() : region.width();
        int res = Math.max(1, Math.min(MAX_RES, (int) Math.ceil(span)));
        double f = res / span, origin = yAxis ? region.y() : region.x();
        float[] p = new float[res];
        for (Item<T> it : items) {
            double c0 = yAxis ? it.b.y()      : it.b.x();
            double c1 = yAxis ? it.b.bottom() : it.b.right();
            int s = clampIdx((int) ((c0 - origin) * f), res);
            int e = clampIdx((int) ((c1 - origin) * f), res);
            for (int i = s; i <= e; i++) p[i]++;
        }
        return new Axis(p, origin, f);
    }

    /** Deepest relative valley in a profile, returned with its cut coordinate. {@code null} if none. */
    private static Valley deepestValley(Axis ax, double minGapCoord) {
        float[] p = ax.hist();
        if (p.length == 0) return null;
        float peak = 0;
        for (float v : p) peak = Math.max(peak, v);
        if (peak == 0) return null;

        float floor = peak * (float) NOISE;
        double minGap = minGapCoord * ax.f();
        Valley best = null;
        int i = 0;
        while (i < p.length) {
            if (p[i] <= floor) {
                int start = i;
                while (i < p.length && p[i] <= floor) i++;
                int end = i;                       // exclusive
                double w = end - start;
                if (w < minGap) continue;
                float lp = peakInRange(p, start - (int) (w * 2), start);
                float rp = peakInRange(p, end, end + (int) (w * 2));
                double depth = Math.min(lp, rp) / peak;
                if (best == null || depth > best.depth
                        || (Math.abs(depth - best.depth) < 0.01 && w > best.width)) {
                    best = new Valley();
                    best.cut   = ax.origin() + ((start + end) / 2.0) / ax.f();
                    best.depth = depth;
                    best.width = w;
                }
            } else i++;
        }
        return best;
    }

    private static float peakInRange(float[] p, int from, int to) {
        float peak = 0;
        for (int i = Math.max(0, from); i < Math.min(p.length, to); i++) peak = Math.max(peak, p[i]);
        return peak;
    }

    private static int clampIdx(int i, int res) { return i < 0 ? 0 : i >= res ? res - 1 : i; }

    private static double hOverlap(JxRect a, JxRect b) {
        return Math.max(0, Math.min(a.right(), b.right()) - Math.max(a.x(), b.x()));
    }

    private static double vOverlap(JxRect a, JxRect b) {
        return Math.min(a.bottom(), b.bottom()) - Math.max(a.y(), b.y());
    }

    private static <T> JxRect bbox(List<Item<T>> items) {
        JxRect box = null;
        for (Item<T> it : items) box = box == null ? it.b : box.union(it.b);
        return box != null ? box : JxRect.EMPTY;
    }

    // ── inner types ──────────────────────────────────────────────────────────
    private static final class Item<T> {
        final T v; final JxRect b; final double cx, cy;
        Item(T v, JxRect b) { this.v = v; this.b = b; this.cx = b.cx(); this.cy = b.cy(); }
    }
    private static final class Metrics { double medianWidth, medianHeight, minHGap, minVGap; }
    private record Axis(float[] hist, double origin, double f) {}
    private static final class Valley  { double cut, depth, width; }
}
