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
 *       (intra-run and inter-run alike), the advance slack {@code step/size − advance} past half the font
 *       {@linkplain OCDFont#spaceWidth() space width} (the style) is a word space. One uniform rule, no
 *       per-line tuning, robust whatever the line holds (one glyph, one word, two words, a tab).
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
     *  advance} is the gap the layout left beyond the left glyph's own width — past half the font space width
     *  (the style) it is a word space. <b>One uniform rule, intra-run and inter-run alike</b>, so it needs no
     *  per-line tuning and is robust whatever the line holds: one glyph (no seam), one word (every slack tight),
     *  two words or a tab (the space/tab clears the style, kerning never does). */
    private static void spaceLine(OCDDocument doc, List<OCDText> runs) {
        record G(OCDText run, int idx, double x, double size, double adv, double sw) {}
        List<G> gl = new ArrayList<>();
        for (OCDText t : runs) {
            OCDFont f = doc.findFont(t.fontId());
            // The slack scale must be the run's HORIZONTAL em on the page — the same basis as the
            // glyph x positions — not the nominal font size: PDF Tz (horizontal scaling, ubiquitous
            // in press headlines at ~96-97%) makes them differ, and dividing horizontal deltas by
            // the vertical size under-reads every gap by exactly Tz (measured: 0.045 vs 0.062 em on
            // a 65 pt headline at Tz 96.5 — just under the gate, whole titles glued).
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

        // mark every seam whose slack clears the style threshold, grouped by the left glyph's run
        java.util.Map<OCDText, java.util.TreeSet<Integer>> marks = new java.util.IdentityHashMap<>();
        for (int k = 0; k + 1 < gl.size(); k++) {
            G a = gl.get(k), b = gl.get(k + 1);
            double slack = (b.x() - a.x()) / a.size() - a.adv();   // em gap beyond the glyph's own advance
            if (slack > a.sw() * SPACE_FRACTION)
                marks.computeIfAbsent(a.run(), r -> new java.util.TreeSet<>()).add(a.idx());
        }

        // splice a space sentinel after each marked glyph (mid-run = intra, last glyph = the run's trailing space)
        marks.forEach((run, idxs) -> {
            List<OCDText.Glyph> gs = run.glyphs();
            List<OCDText.Glyph> out = new ArrayList<>(gs.size() + idxs.size());
            for (int i = 0; i < gs.size(); i++) {
                out.add(gs.get(i));
                if (idxs.contains(i)) out.add(new OCDText.Glyph(SENTINEL, gs.get(i).x(), " "));
            }
            gs.clear();
            gs.addAll(out);
        });
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
