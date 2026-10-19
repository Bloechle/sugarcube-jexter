package sugarcube.jexter.ocd.analysis;

import sugarcube.jexter.core.JxText;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDFont;
import sugarcube.jexter.ocd.model.OCDGlyph;
import sugarcube.jexter.ocd.model.OCDText;

import java.util.ArrayList;
import java.util.List;

/**
 * The single space authority. Runs carry pure ink (the importer trusts no source space, {@link Cleaner}
 * strips every blank); word spaces are derived from glyph geometry and materialised as <b>space sentinels</b>
 * — gid {@value #SENTINEL}, unicode {@code " "} — spliced into the runs. A sentinel is outline-less, so every
 * render path skips it ({@code font.glyph(gid) == null}); yet {@code text()} / extraction / structure read the
 * space. Because it lives in the run, the whole downstream (writers, OCD-EPUB, index) reads spaced text
 * with no inter-run logic at all.
 *
 * <p>{@link #freeze} is the one entry point. {@link Segmenter} calls it the moment a clean line is rebuilt
 * (from {@link Liner}), before the lines are grouped into blocks — so each line is sealed once, in reading
 * order, and never touched again. It splices spaces by two concerns:
 * <ul>
 *   <li><b>inner spaces</b> ({@code spaceLine}) — the line is read as one glyph stream and, at every seam
 *       (intra-run and inter-run alike), the advance slack {@code step/size − advance}, as a ratio of the font's
 *       {@linkplain OCDFont#spaceWidth() space width}, is a word space when it clears the line's own gate
 *       ({@code gate}): the valley above its letter seams. One rule, intra-run and inter-run alike.
 *   <li><b>line end</b> ({@code endLine}) — the line ends on a word boundary: a trailing sentinel, unless it
 *       ends in a hyphen (left painted for read-out to rejoin the split word or keep a compound). This seals
 *       the inter-line and inter-block same-line seams (a {@code Titre 2} marker to its rubric).
 * </ul>
 *
 * <p>Idempotent by pipeline: {@link Cleaner} strips these sentinels at the head of every analysis, so the pass
 * re-derives them from scratch.
 */
public final class Spacer {

    private Spacer() {}

    /** × the font space width: slack above this between two glyphs is a word break, not kerning/tight
     *  setting. Justified columns compress word spaces well below the face's nominal. The gate
     *  sits at 0.28×, read off the full-corpus slack/sw histogram of a 40-page newspaper
     *  (244k seams): the kerning cluster dies at +0.05, the valley lies in [0.075, 0.10], and
     *  ~2100 true word gaps live in [0.10, 0.40]. Measured against BOTH failure directions
     *  (glued words and over-splits) before committing. */
    private static final double SPACE_FRACTION = 0.28;

    /** Guard rails on the per-line gate. {@link #MIN_SEAMS} — under this a line is not a distribution and the
     *  style decides. {@link #SEPARATION} / {@link #TIGHT} — the two clusters must stand apart by this factor
     *  (the tight one floored, so a charspace at 0 does not make every ratio infinite) before a valley is
     *  believed; measured separations are 5x on a tracked line and unbounded on body text, so this is a floor,
     *  not a tuning. */
    private static final int    MIN_SEAMS  = 6;
    /** × the font space width: no seam under this is a word space, whatever the line says — the top of the
     *  kerning cluster on the same histogram (it dies at +0.05, the valley lies in [0.075, 0.10]). A line that
     *  shows its own valley is gated there, down to this floor: its word spaces may sit well under the
     *  style (0.16 of the face's space on a justified report, measured), and the style alone glued them. */
    private static final double MIN_GAP    = 0.10;
    private static final double SEPARATION = 2.0;
    private static final double TIGHT      = 0.05;

    /** Gid of a spliced space: absent from every font, so all render paths skip it; carries only the {@code " "}. */
    public static final int SENTINEL = -1;

    /** Freeze one reconstructed clean line: order its runs left→right (reading order is intrinsic to a frozen
     *  line), infer the inner word spaces, and end the line on a word boundary. Called by {@link Segmenter} the
     *  moment a clean line is rebuilt — before the lines are grouped into blocks — so the line is sealed once
     *  and never touched again. Every line ends on a trailing sentinel (unless a hyphen): it either joins the
     *  line to the block that continues it (a {@code Titre 2} marker to its rubric, a wrapped line to the next)
     *  or is stripped at the element edge, so no inter-line or inter-block same-line seam is ever lost. */
    public static void freeze(OCDDocument doc, List<OCDText> lineRuns) {
        if (lineRuns.isEmpty()) return;
        // The rules below read a horizontal gap as meaning — order, word space, end of line — and that is a
        // statement about left-to-right text whose words are parted by spaces. On a Hebrew, Arabic or CJK
        // line they do not mis-order, they CORRUPT: the line-ending space lands on the visually rightmost
        // run, which in Hebrew is the line's beginning, and a word is split down the middle. So the line is
        // left exactly as the producer wrote it — same runs, same order, same text. Say no rather than
        // corrupt; proper bidi and CJK handling are their own work, not a guess made here.
        if (!isLtrWordScript(lineRuns)) return;
        List<OCDText> runs = new ArrayList<>(lineRuns);
        runs.sort(java.util.Comparator.comparingDouble(t -> t.bounds().minX()));   // reading order
        spaceLine(doc, runs);                                             // inner word spaces
        endLine(runs.get(runs.size() - 1));                              // end the line on a word boundary
    }

    /** A line is the geometry's business only when every run on it is. One non-Latin run is enough to
     *  stand the whole line down: a mixed line's seams are exactly where a gap rule would go wrong. */
    private static boolean isLtrWordScript(List<OCDText> runs) {
        for (OCDText t : runs) if (!JxText.isLtrWordScript(t.text())) return false;
        return true;
    }

    /** Infer and splice the word spaces of a frozen line, runs already left→right. The line is read as a single
     *  glyph stream (prior sentinels skipped, so it is idempotent); at each seam the slack {@code step/size −
     *  advance} is the gap the layout left beyond the left glyph's own width — past the line's gate it is a
     *  word space. <b>One uniform rule, intra-run and inter-run alike</b>, so it needs no
     *  per-line tuning and is robust whatever the line holds: one glyph (no seam), one word (every slack tight),
     *  two words or a tab (the space/tab clears the style, kerning never does). */
    private static void spaceLine(OCDDocument doc, List<OCDText> runs) {
        record G(OCDText run, int idx, double x, double size, double adv, double sw) {}
        List<G> gl = new ArrayList<>();
        for (OCDText t : runs) {
            OCDFont f = doc.findFont(t.fontId());
            // The slack scale must be the run's HORIZONTAL em on the page — the same basis as the
            // glyph x positions. PDF Tz (horizontal scaling, ubiquitous in press headlines at
            // ~93-97%) makes it differ from the nominal size: dividing horizontal deltas by the
            // vertical em under-reads every gap by exactly Tz (measured: 0.045 vs 0.062 em on a
            // 65 pt Tz-93 headline — just under the gate, whole titles glued). The run transform
            // carries the anisotropy whatever its normalisation (unit-em at import, full matrix
            // after read), so the scale-invariant ASPECT |T·ex|/|T·ey| corrects the size on both.
            double sz = t.fontSize();
            var T = t.transform();
            if (T != null) {
                double hx = Math.hypot(T.a(), T.b()), hy = Math.hypot(T.c(), T.d());
                if (hx > 1e-9 && hy > 1e-9) sz *= hx / hy;
            }
            if (f == null || sz <= 0) continue;
            double sw = f.spaceWidth();
            if (sw <= 0) continue;
            List<OCDText.Glyph> gs = t.glyphs();
            for (int i = 0; i < gs.size(); i++) {
                OCDText.Glyph g = gs.get(i);
                if (g.isBlank()) continue;                          // skip a prior sentinel — idempotent
                OCDGlyph og = f.glyph(g.gid());
                gl.add(new G(t, i, t.glyphPageX(g), sz, og == null ? 0 : og.advance(), sw));
            }
        }
        if (gl.size() < 2) return;                                 // one glyph (or none): no seam, no space

        // The line names its own gate. Each seam is read as a RATIO of the face's space width, so a line that
        // mixes sizes still compares; then the sorted ratios are split where the tight cluster ends. Body text separates
        // a charspace cluster at ~0 from word spaces at ~1; a tracked line separates 0.28 from 1.4. One absolute
        // number cannot serve both — the tracked page measured 0.078 em of tracking against a 0.0778 em gate,
        // two ten-thousandths under, and every letter seam became a word space.
        double[] ratio = new double[gl.size() - 1];
        for (int k = 0; k + 1 < gl.size(); k++) {
            G a = gl.get(k), b = gl.get(k + 1);
            ratio[k] = ((b.x() - a.x()) / a.size() - a.adv()) / a.sw();   // em gap beyond the glyph's own advance
        }
        double gate = gate(ratio);

        // mark every seam that clears the gate, grouped by the left glyph's run
        java.util.Map<OCDText, java.util.TreeSet<Integer>> marks = new java.util.IdentityHashMap<>();
        for (int k = 0; k < ratio.length; k++)
            if (ratio[k] > gate)
                marks.computeIfAbsent(gl.get(k).run(), r -> new java.util.TreeSet<>()).add(gl.get(k).idx());

        // splice a space sentinel after each marked glyph (mid-run = intra, last glyph = the run's trailing space)
        marks.forEach((run, idxs) -> {
            List<OCDText.Glyph> gs = run.glyphs();
            List<OCDText.Glyph> out = new ArrayList<>(gs.size() + idxs.size());
            for (int i = 0; i < gs.size(); i++) {
                out.add(gs.get(i));
                boolean spaced = i + 1 < gs.size() && gs.get(i + 1).isBlank();     // frozen before: idempotent
                if (idxs.contains(i) && !spaced) out.add(new OCDText.Glyph(SENTINEL, gs.get(i).x(), " "));
            }
            gs.clear();
            gs.addAll(out);
        });
    }

    /** The seam gate for one line, as a ratio of the face's space width. A line with enough seams is gated at
     *  the valley that closes its TIGHT cluster — the letter seams, at ~0 in body text, at the tracking on a
     *  tracked line: walking the sorted ratios up from the bottom, the first value that stands
     *  {@link #SEPARATION}× above the cluster under it, across a jump at least as wide as that cluster is
     *  spread, opens the word spaces. The gate never falls under the style ({@link #SPACE_FRACTION}) on a line
     *  that has a gap past it, nor under the kerning floor ({@link #MIN_GAP}) on a line whose gaps all sit
     *  under the style.
     *
     *  <p>The FIRST such valley, not the widest: a justified column mixes a tight word space (0.36 of the
     *  face's space) with a loose one (1.2), and a results table mixes word spaces with column gaps (1.0 against
     *  7.2). The widest jump lies BETWEEN the spaces there, and every tighter space under it was glued
     *  ("Leconseil", "YoungBoys") — the press defect. A line with no such valley cannot point at a gap:
     *  geometry alone cannot tell one tracked word from a line of isolated letters, so it says nothing rather
     *  than guess. Too few seams is not a distribution: the style decides. */
    private static double gate(double[] ratio) {
        if (ratio.length < MIN_SEAMS) return SPACE_FRACTION;
        double[] s = ratio.clone();
        java.util.Arrays.sort(s);
        // A line with a seam past the style has word spaces of the style's size, and a looser letter pair under
        // it is letter fitting ("des tination" in a display face). Only a line whose every gap sits under the
        // style — the face's space width says more than its setting — is trusted down to the kerning floor.
        double floor = s[s.length - 1] > SPACE_FRACTION ? SPACE_FRACTION : MIN_GAP;
        for (int i = 0; i + 1 < s.length; i++) {
            double lo = median(s, 0, i + 1), next = s[i + 1];
            if (next >= SEPARATION * Math.max(lo, TIGHT) && next - s[i] >= s[i] - lo
                    && median(s, i + 1, s.length) > floor)
                return Math.max((s[i] + next) / 2, floor);
        }
        return Double.POSITIVE_INFINITY;
    }

    private static double median(double[] sorted, int from, int to) {
        return from >= to ? 0 : sorted[from + (to - from) / 2];
    }


    /** Materialise the inter-line word space: a visual line that continues into the next is a word boundary, so
     *  the run that ends it takes a trailing space sentinel — <b>unless</b> it ends in a hyphen, which is left
     *  painted (it is visible in the page) for text read-out to rejoin the split word or keep a compound. Called
     *  at the line authority over a leaf's consecutive clean lines, so the boundary lives in the run stream and
     *  both the physical and the structure-ref read-outs are a plain concatenation. */
    public static void endLine(OCDText lineEnd) {
        if (lineEnd == null || lineEnd.glyphs().isEmpty()) return;
        OCDText.Glyph last = lineEnd.glyphs().get(lineEnd.glyphs().size() - 1);
        if (last.isBlank() || "-".equals(last.unicode())) return;     // already spaced, or hyphen → defer to read-out
        lineEnd.add(new OCDText.Glyph(SENTINEL, last.x(), " "));
    }

}
