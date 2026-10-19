package sugarcube.jexter.ocd.analysis;

import sugarcube.jexter.core.JxRect;
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
    /** × em of the SMALLER run: a horizontal gap wider than this between two runs on the same band may be a
     *  <b>column gutter</b> (or a margin number standing off the text), so the band is cut there into
     *  proto-lines. A newspaper sets its columns about one em apart (9 pt for 8.5 pt type) and poppler takes
     *  0.7 em; at 1 em of the LARGER run, two columns of a press page and a big figure beside a caption
     *  stayed one line, which bridged the gutter and left XY-Cut no column to find. A wide word space cut
     *  here costs nothing: XY-Cut only cuts where every line leaves the gap, and inside its leaf the band is
     *  read whole again ({@code splitColumns=false}). */
    private static final double COLUMN_GAP = 0.5;

    /** A clustered line: mean y and member runs (left→right). Geometry only — no text, no spacing; word
     *  spaces live in the runs as sentinels (the {@link Spacer} authority), read by whoever needs the text. */
    record Line(double yc, List<OCDText> runs) {}

    /** Cluster runs into lines top→down (PDF Y-up) by vertical band intersection: a run joins the open
     *  line iff their bands overlap by ≥{@link #OVERLAP_MIN} of the smaller band and the run is not far
     *  larger than the line. Pure box geometry — no baseline, no script special case — so it behaves the
     *  same on native PDF and on OCR. */
    static List<Line> lines(List<OCDText> runs, boolean splitColumns) {
        if (runs.isEmpty()) return new ArrayList<>();         // mutable: callers sort/mutate the result (e.g. Furniture)
        // OCDText.bounds() is DERIVED — it walks every glyph and transforms four corners — and axis() is an
        // atan2. This pass asks for both inside comparators, so O(n log n) times and twice per comparison,
        // then again per candidate and per member. Nothing here moves a run, so each box and each axis is
        // taken ONCE and the pass reads that: same values, same order (List.sort is stable and the keys are
        // unchanged), one derivation per run instead of a dozen.
        Box box = new Box(runs);
        List<OCDText> ls = new ArrayList<>(runs);
        ls.sort((a, b) -> Double.compare(box.of(b).cy(), box.of(a).cy()));       // top→down
        // A run can only extend the open line if it writes along the same axis, so the scan keeps one open
        // accumulator PER axis: a rotated caption running down the margin no longer breaks the column's
        // lines apart as it is passed, and no longer absorbs them either.
        List<Acc> accs = new ArrayList<>();
        java.util.Map<Integer, Acc> open = new java.util.HashMap<>();
        for (OCDText t : ls) {
            int a = box.axis(t);
            Acc cur = open.get(a);
            if (cur != null && cur.accepts(t)) cur.add(t);
            else { cur = new Acc(t, box); accs.add(cur); open.put(a, cur); }
        }
        List<Line> out = new ArrayList<>(accs.size());
        for (Acc a : accs) out.addAll(a.toLines(splitColumns));
        return out;
    }

    /** The derived geometry of one clustering pass, taken once per run. Scoped to the call: the runs are
     *  not touched while they are clustered, so there is nothing to invalidate. */
    private static final class Box {
        private final java.util.Map<OCDText, JxRect> rects;
        private final java.util.Map<OCDText, Integer> axes;

        Box(List<OCDText> runs) {
            rects = new java.util.IdentityHashMap<>(Math.max(4, runs.size() * 2));
            axes  = new java.util.IdentityHashMap<>(Math.max(4, runs.size() * 2));
            for (OCDText t : runs) { rects.put(t, t.bounds()); axes.put(t, Liner.axis(t)); }
        }

        JxRect of(OCDText t)  { return rects.computeIfAbsent(t, OCDText::bounds); }
        int    axis(OCDText t) { return axes.computeIfAbsent(t, Liner::axis); }
    }

    /** Fraction of the smaller vertical band covered by the overlap of two boxes (0 = disjoint). */
    private static double vOverlap(JxRect a, JxRect b) {
        double ov = Math.min(a.maxY(), b.maxY()) - Math.max(a.y(), b.y());
        if (ov <= 0) return 0;
        double min = Math.min(a.height(), b.height());
        return min <= 0 ? 0 : ov / min;
    }

    /** The run's writing direction, quantised to a quarter turn. Two runs written along different axes
     *  cannot share a line — that is geometry, not a heuristic: a line IS a baseline, and a baseline has a
     *  direction. Without it a rotated caption, whose box is tall and narrow, overlaps every band of the
     *  column beside it and welds heading, body and caption into one paragraph (measured). */
    private static int axis(OCDText t) {
        return t.transform().quarterTurns();
    }

    /** Mutable line accumulator: the member runs, the line's writing axis, and its band — the band of its
     *  TALLEST run, never the union of all of them. A union grows with every run it takes, so two columns
     *  set on offset baselines chain: each line overlaps the half-line beside it, the band slides down one
     *  step at a time, and a whole paragraph of one column lands in one "line" with the other column's
     *  lines (measured on a two-column paper: the abstract read as one line, no word space left in it). */
    private static final class Acc {
        final List<OCDText> runs = new ArrayList<>();
        final Box box;
        final int axis;
        JxRect band;

        Acc(OCDText t, Box box) { this.box = box; this.axis = box.axis(t); add(t); }

        void add(OCDText t) {
            runs.add(t);
            JxRect b = box.of(t);
            if (band == null || b.height() > band.height()) band = b;
        }

        /** Join iff the run writes along the line's axis and shares its band (≥{@link #OVERLAP_MIN} of the
         *  smaller): a raised or shrunk sub/superscript sits inside the band and joins, no special case. */
        boolean accepts(OCDText t) {
            return box.axis(t) == axis                              // a different baseline direction is a different line
                && vOverlap(box.of(t), band) >= OVERLAP_MIN;
        }

        /** Cut this band into lines at every gap wider than {@link #COLUMN_GAP} (a column gutter or a margin
         *  offset) when {@code splitColumns} — so two columns sharing a height become two proto-lines. Inside a
         *  confirmed XY-Cut leaf there are no columns, so {@code splitColumns=false} keeps the whole band as one
         *  clean line (an enumerator parted from its text by an indent rejoins). */
        List<Line> toLines(boolean splitColumns) {
            runs.sort(Comparator.comparingDouble(t -> box.of(t).minX()));
            List<Line> out = new ArrayList<>();
            List<OCDText> seg = new ArrayList<>();
            OCDText prev = null;
            for (OCDText t : runs) {
                if (splitColumns && prev != null) {
                    double em = Math.min(prev.fontSize(), t.fontSize());
                    double gap = em > 0 ? (box.of(t).minX() - box.of(prev).maxX()) / em : 0;
                    if (gap > COLUMN_GAP) { out.add(lineOf(seg, box)); seg = new ArrayList<>(); }   // gutter / margin → new proto-line
                }
                seg.add(t);
                prev = t;
            }
            if (!seg.isEmpty()) out.add(lineOf(seg, box));
            return out;
        }

        static Line lineOf(List<OCDText> runs, Box box) {
            runs = new ArrayList<>(runs);
            runs.sort(Comparator.comparingDouble(t -> box.of(t).minX()));   // left→right
            double yc = 0;
            for (OCDText t : runs) yc += box.of(t).cy();
            return new Line(runs.isEmpty() ? 0 : yc / runs.size(), runs);
        }
    }
}
