package sugarcube.jexter.ocd.analysis;

import sugarcube.jexter.ocd.model.OCDStruct;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * <h2>Deterministic reconciliation of a page-windowed logical tree.</h2>
 *
 * When {@link Refiner} runs page-by-page (the scale-invariant path), each page is structured
 * in isolation, so the model can only guess heading depth <i>locally</i> — it never sees the whole
 * document, and a level&nbsp;1 on page&nbsp;3 need not mean the same thing as a level&nbsp;1 on
 * page&nbsp;47. This pass owns the one decision a per-page model cannot make: it re-derives a
 * <b>consistent global heading hierarchy</b> by clustering the headings' actual type sizes across
 * the whole document into a single level map (largest size → level&nbsp;1, capped at a sane depth),
 * exactly the typographic signal heading detection rests on.
 *
 * <p>It is purely structural and deterministic — it reassigns {@code OCDStruct.level} only, never
 * touches geometry, paint order, or refs, so fidelity is untouched and the same input yields the
 * same hierarchy. Tie-breaking on weight/whitespace is left for a future pass; size is primary and
 * carries the vast majority of cases.
 */
public final class StructureReconciler {

    private StructureReconciler() {}

    private static final int MAX_LEVELS  = 6;   // 2–3 are typical; cap so deep noise collapses
    private static final int SIZE_BUCKET = 5;   // sizePct rounding — folds near-equal sizes (15.9 vs 16.1) together

    /**
     * Reassign every {@code HEADING}'s level from its representative size, so a document assembled
     * from independent page windows gets one coherent depth scale.
     *
     * @param root      the assembled document tree (mutated in place)
     * @param sizeByRef map from {@code page + "#" + nodeId} to the block's font size as a percentage
     *                  of body (100 = body, 160 = 1.6× body); the per-leaf size captured at harvest
     */
    public static void normalizeHeadingLevels(OCDStruct root, Map<String, Integer> sizeByRef) {
        if (root == null || sizeByRef == null || sizeByRef.isEmpty()) return;

        List<OCDStruct> headings = new ArrayList<>();
        collectHeadings(root, headings);
        if (headings.isEmpty()) return;

        // Representative size bucket per heading = the largest size among its referenced leaves.
        Map<OCDStruct, Integer> bucketOf = new LinkedHashMap<>();
        TreeSet<Integer> buckets = new TreeSet<>(Comparator.reverseOrder());   // largest first
        for (OCDStruct h : headings) {
            int rep = 0;
            for (OCDStruct.Ref r : h.refs()) {
                Integer s = sizeByRef.get(r.page() + "#" + r.nodeId());
                if (s != null) rep = Math.max(rep, s);
            }
            if (rep <= 0) continue;                                            // no size info → keep model level
            int b = Math.round(rep / (float) SIZE_BUCKET) * SIZE_BUCKET;
            bucketOf.put(h, b);
            buckets.add(b);
        }
        if (buckets.isEmpty()) return;

        // Largest distinct size bucket → level 1, next → 2, … capped.
        Map<Integer, Integer> levelOf = new HashMap<>();
        int lv = 1;
        for (int b : buckets) { levelOf.put(b, Math.min(lv, MAX_LEVELS)); lv++; }

        for (Map.Entry<OCDStruct, Integer> e : bucketOf.entrySet())
            e.getKey().level(levelOf.get(e.getValue()));
    }

    private static void collectHeadings(OCDStruct n, List<OCDStruct> out) {
        if (n == null) return;
        if (n.type() == OCDStruct.Type.HEADING) out.add(n);
        for (OCDStruct c : n.children()) collectHeadings(c, out);
    }
}
