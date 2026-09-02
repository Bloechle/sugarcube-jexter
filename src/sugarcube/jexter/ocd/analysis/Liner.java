package sugarcube.jexter.ocd.analysis;

import sugarcube.jexter.core.JxRect;
import sugarcube.jexter.core.JxNum;
import sugarcube.jexter.ocd.model.OCDText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shared text-geometry helpers for the analysis passes — median run height and line clustering.
 *
 * <p>Lines are clustered by <b>vertical band intersection</b>: two runs share a line iff their bounding
 * bands overlap by at least {@link #OVERLAP_MIN} of the smaller band. This reads the line purely off box
 * geometry — no baseline metadata, no font-size heuristic, no super/subscript special case — so it works
 * identically on clean native PDF and on OCR output, where baselines and run structure are unreliable.
 * Two guard rails:
 * <ul>
 *   <li><b>Overlap threshold</b> — same-line runs overlap strongly (≈1.0 of the smaller band); tight-leading
 *       neighbours only ≈0.1–0.3, so ~1/3 separates them cleanly. A super/subscript is small and sits inside
 *       the line band, so it overlaps strongly and joins with no special case — overlap alone is the rule.</li>
 * </ul>
 */
final class Liner {

    private Liner() {}

    /** A run joins a line iff their vertical bands overlap by at least this fraction of the smaller band.
     *  Overlap alone is the line band: same-line runs (a raised/shrunk sub/superscript included) overlap
     *  strongly, tight-leading neighbours only ≈0.1–0.3, so ~1/3 separates them cleanly. */
    private static final double OVERLAP_MIN = 1.0 / 3.0;
    /** × em: a horizontal gap wider than this between two runs on the same band is a <b>column gutter</b> (or a
     *  margin line-number standing off the text), not a word space — so the band is cut there into separate
     *  lines. A word space is ≤ ~0.3 em and even heavily justified spacing stays well under 1 em, while a
     *  two-column gutter or a margin offset is several em; 1 em is the clean divide. Without this, a left- and
     *  a right-column line at the same height (they vertically overlap) would merge into one full-width line,
     *  and XY-Cut downstream could never recover the columns. */
    private static final double COLUMN_GAP = 1.0;

    /** Median run height (0 when empty). */
    static double medianHeight(List<OCDText> runs) {
        return JxNum.median(runs.stream().mapToDouble(t -> t.bounds().height()).toArray());
    }

    /** A clustered line: mean y and member runs (left→right). Geometry only — no text, no spacing; word
     *  spaces live in the runs as sentinels (the {@link Spacer} authority), read by whoever needs the text. */
    record Line(double yc, List<OCDText> runs) {}

    /** Cluster runs into lines top→down (PDF Y-up) by vertical band intersection: a run joins the open
     *  line iff their bands overlap by ≥{@link #OVERLAP_MIN} of the smaller band and the run is not far
     *  larger than the line. Pure box geometry — no baseline, no script special case — so it behaves the
     *  same on native PDF and on OCR. */
    static List<Line> lines(List<OCDText> runs, boolean splitColumns) {
        if (runs.isEmpty()) return new ArrayList<>();         // mutable: callers sort/mutate the result (e.g. Furniture)
        List<OCDText> ls = new ArrayList<>(runs);
        ls.sort((a, b) -> Double.compare(b.bounds().cy(), a.bounds().cy()));     // top→down
        // A run can only extend the open line if it writes along the same axis, so the scan keeps one open
        // accumulator PER axis: a rotated caption running down the margin no longer breaks the column's
        // lines apart as it is passed, and no longer absorbs them either.
        List<Acc> accs = new ArrayList<>();
        java.util.Map<Integer, Acc> open = new java.util.HashMap<>();
        for (OCDText t : ls) {
            Acc cur = open.get(axis(t));
            if (cur != null && cur.accepts(t)) cur.add(t);
            else { cur = new Acc(t); accs.add(cur); open.put(axis(t), cur); }
        }
        List<Line> out = new ArrayList<>(accs.size());
        for (Acc a : accs) out.addAll(a.toLines(splitColumns));
        return out;
    }

    /** Fraction of the smaller vertical band covered by the overlap of two boxes (0 = disjoint). */
    private static double vOverlap(JxRect a, JxRect b) {
        double ov = Math.min(a.bottom(), b.bottom()) - Math.max(a.y(), b.y());
        if (ov <= 0) return 0;
        double min = Math.min(a.height(), b.height());
        return min <= 0 ? 0 : ov / min;
    }

    /** The run's writing direction, quantised to a quarter turn. Two runs written along different axes
     *  cannot share a line — that is geometry, not a heuristic: a line IS a baseline, and a baseline has a
     *  direction. Without it a rotated caption, whose box is tall and narrow, overlaps every band of the
     *  column beside it and welds heading, body and caption into one paragraph (measured). */
    private static int axis(OCDText t) {
        var m = t.transform();
        return Math.floorMod((int) Math.round(Math.atan2(m.b(), m.a()) / (Math.PI / 2)), 4);
    }

    /** Mutable line accumulator: the member runs, the line's writing axis, and the union band. */
    private static final class Acc {
        final List<OCDText> runs = new ArrayList<>();
        final int axis;
        JxRect bounds;

        Acc(OCDText t) { this.axis = axis(t); add(t); }

        void add(OCDText t) {
            runs.add(t);
            bounds = bounds == null ? t.bounds() : bounds.union(t.bounds());
        }

        /** Join iff the run shares the line's band (≥{@link #OVERLAP_MIN} of the smaller) and is not far
         *  larger than the line. The upper size bound rejects a drop-cap (its tall band overlaps a short
         *  line band fully yet belongs to none); a smaller script passes both and attaches, no special case. */
        boolean accepts(OCDText t) {
            return axis(t) == axis                                  // a different baseline direction is a different line
                && vOverlap(t.bounds(), bounds) >= OVERLAP_MIN;     // then overlap alone is the band — a raised/shrunk sub/superscript belongs to its line
        }

        /** Cut this band into lines at every gap wider than {@link #COLUMN_GAP} (a column gutter or a margin
         *  offset) when {@code splitColumns} — so two columns sharing a height become two proto-lines. Inside a
         *  confirmed XY-Cut leaf there are no columns, so {@code splitColumns=false} keeps the whole band as one
         *  clean line (an enumerator parted from its text by an indent rejoins). */
        List<Line> toLines(boolean splitColumns) {
            runs.sort(Comparator.comparingDouble(t -> t.bounds().minX()));
            List<Line> out = new ArrayList<>();
            List<OCDText> seg = new ArrayList<>();
            OCDText prev = null;
            for (OCDText t : runs) {
                if (splitColumns && prev != null) {
                    double em = Math.max(prev.fontSize(), t.fontSize());
                    double gap = em > 0 ? (t.bounds().minX() - prev.bounds().maxX()) / em : 0;
                    if (gap > COLUMN_GAP) { out.add(lineOf(seg)); seg = new ArrayList<>(); }   // gutter / margin → new proto-line
                }
                seg.add(t);
                prev = t;
            }
            if (!seg.isEmpty()) out.add(lineOf(seg));
            return out;
        }

        static Line lineOf(List<OCDText> runs) {
            runs = new ArrayList<>(runs);
            runs.sort(Comparator.comparingDouble(t -> t.bounds().minX()));   // left→right
            double yc = 0;
            for (OCDText t : runs) yc += t.bounds().cy();
            return new Line(runs.isEmpty() ? 0 : yc / runs.size(), runs);
        }
    }
}
