package sugarcube.jexter.ocd.analysis;

import sugarcube.jexter.core.JxLog;
import sugarcube.jexter.core.JxText;
import sugarcube.jexter.core.JxRect;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDNode;
import sugarcube.jexter.ocd.model.OCDPage;
import sugarcube.jexter.ocd.model.OCDText;

import java.util.ArrayList;
import java.util.List;

/**
 * Running header / footer detection by <b>recto/verso stack stability at the page edge</b> — one idea, no
 * lexicon, no global threshold to tune beyond the page edge itself.
 *
 * <p>A running header or footer <i>is</i> a line that stays the same from one page to the <b>next page of the
 * same side</b>. Odd and even pages are two separate stacks (recto / verso): a recto header reappears on the
 * next recto, a verso header on the next verso, whether or not the two sides carry the same text. So we read
 * exactly that. For a candidate edge line on page <i>p</i>, we compare its <b>whole raw string</b> (upper-cased,
 * whitespace collapsed — digits kept) to the line at the same edge position on the same-side neighbours
 * <i>p−2</i> and <i>p+2</i> with a normalised Levenshtein similarity (<code>1 − dist/maxLen</code>: 1 = identical,
 * 0 = different; a missing line counts as 0). A line is furniture iff the mean similarity to its same-side
 * neighbours clears {@link #SIM_MIN}; if the same-side stack is inconclusive — a chapter whose single recto or
 * verso page is flanked by two other chapters — it falls back to the immediate {@code p±1} neighbours.
 *
 * <p>The two stacks are what makes this clean: recto/verso never cancel, and a title page falls out for free —
 * the page-1 title or bare law number differs from the running header on page 3, so its single neighbour pair is
 * unstable and it is not tagged. Stability is <b>local</b> (per page, per its own neighbours), so a header that
 * changes per chapter is tagged on every page whose same-side neighbour shares it.
 *
 * <p><b>Page-extremity stays the orthogonal filter.</b> Recurring body — a boilerplate legal footnote repeated
 * verbatim — is just as stable as the folio, so stability alone cannot reject it; but it is not at the very edge.
 * The header is the contiguous run of lines descending from the top, the footer the run rising from the bottom:
 * we accumulate inward while the next line is block-contiguous (gap ≤ {@value #CONTIG}× the line height) and
 * stable, stopping at the first break. On a dense page this stops at the folio, before the footnote block above
 * it.
 *
 * <p><b>Non-destructive:</b> it only sets {@link OCDNode#role} on the running text runs, never geometry or paint
 * order, so it is render-neutral. {@link #isRunning} stays the single authority consumed by font-profiling,
 * reading-order and the LLM structurer. Scope is text-only: text-less furniture (a rule, a logo) is graphics,
 * handled separately.
 */
public final class Furniture {

    private Furniture() {}

    private static final int    MIN_PAGES = 3;     // a stack needs a few pages for "next same-side page" to mean anything
    private static final int    STACK     = 2;     // same-side neighbour offset: previous/next page of the same parity
    private static final double SIM_MIN   = 0.60;  // furniture iff mean same-side-neighbour similarity ≥ this
    private static final double CONTIG    = 2.0;   // accumulate inward only while the next line is within this many line-heights
    private static final int    VIZ_BINS  = 200;   // Y resolution of the diagnostic Profile (inspector only)

    /** One page's lines (top-first) with their baseline y, line height, and raw comparison string. */
    private record Page(List<Liner.Line> lines, double[] yc, double[] lh, String[] raw) {}

    public static void detect(OCDDocument doc) {
        int pages = doc.pageCount();
        if (pages < MIN_PAGES) { JxLog.debug(Furniture.class, "header/footer skipped \u2014 " + pages + " pages (< " + MIN_PAGES + ")", null); return; }

        List<Page> pg = scan(doc);
        int tagged = 0, headers = 0, footers = 0;
        for (int p = 0; p < pages; p++) {
            Page pa = pg.get(p); if (pa.lines.isEmpty()) continue;
            List<Integer> head = edgeRun(pg, p, true, -1);                          // header run from the top
            for (int i : head) tagged += tag(pa.lines.get(i), "page-header");
            headers += head.size();
            int hi = head.isEmpty() ? -1 : head.get(head.size() - 1);              // footer never crosses it
            List<Integer> foot = edgeRun(pg, p, false, hi);                         // footer run from the bottom
            for (int i : foot) tagged += tag(pa.lines.get(i), "page-footer");
            footers += foot.size();
        }
        JxLog.info(Furniture.class, "header/footer \u2014 stack-stability+edge, tagged " + tagged
                + " run(s) (" + headers + " header line(s) / " + footers + " footer line(s)) over " + pages + " pages");
    }

    /** A line is furniture iff it is stable across the same-side stack: the mean normalised-Levenshtein
     *  similarity of its raw string to the same edge-position line on pages {@code p−2} and {@code p+2} clears
     *  {@link #SIM_MIN}. {@code top} selects the edge, {@code depth} the inward position from it. */
    private static boolean furniture(List<Page> pg, int p, boolean top, int depth) {
        return strength(pg, p, top, depth) >= SIM_MIN;
    }

    /** Furniture strength: stability across the same-side stack ({@code p±STACK}); if that stack does not
     *  confirm it — an edge case such as a chapter whose single recto (or verso) page is flanked by two other
     *  chapters — fall back to the immediate neighbours ({@code p±1}, either side). The {@code max} means either
     *  signal can carry the line and the fallback never weakens the same-side result. */
    private static double strength(List<Page> pg, int p, boolean top, int depth) {
        return Math.max(stability(pg, p, top, depth, STACK), stability(pg, p, top, depth, 1));
    }

    /** The contiguous, stable furniture run inward from one edge: the line indices from the edge inward, in
     *  order, stopping at the first line that is non-contiguous (gap &gt; {@value #CONTIG}× the previous line
     *  height) or not stable furniture. {@code hi} is the header's deepest index so a footer never crosses into
     *  it (−1 for the header pass). One walk, shared by {@link #detect} (tagging) and {@link #profile} (bins). */
    private static List<Integer> edgeRun(List<Page> pg, int p, boolean top, int hi) {
        Page pa = pg.get(p); int n = pa.lines.size();
        List<Integer> run = new ArrayList<>();
        for (int d = 0; d < n; d++) {
            int i = top ? d : n - 1 - d;
            if (!top && i <= hi) break;                                  // footer must not overlap the header
            if (d > 0) {
                int prev = top ? i - 1 : i + 1;                          // the line one step closer to the edge
                if (Math.abs(pa.yc[prev] - pa.yc[i]) > CONTIG * pa.lh[prev]) break;   // block ended
            }
            if (!furniture(pg, p, top, d)) break;                        // first unstable line ends the run
            run.add(i);
        }
        return run;
    }

    private static double stability(List<Page> pg, int p, boolean top, int depth, int span) {
        String me = lineAt(pg.get(p), top, depth);
        if (me == null || me.isEmpty()) return 0.0;
        double sum = 0; int cnt = 0;
        for (int off : new int[]{-span, span}) {
            int q = p + off; if (q < 0 || q >= pg.size()) continue;
            String o = lineAt(pg.get(q), top, depth);
            sum += (o == null || o.isEmpty()) ? 0.0 : sim(me, o);   // missing line on the neighbour → similarity 0
            cnt++;
        }
        return cnt == 0 ? 0.0 : sum / cnt;
    }

    /** Raw string of the edge line at {@code depth} from the top (or bottom) of a page, or {@code null} if the
     *  page has no line at that position. */
    private static String lineAt(Page pa, boolean top, int depth) {
        int n = pa.raw.length, i = top ? depth : n - 1 - depth;
        return (i < 0 || i >= n) ? null : pa.raw[i];
    }

    /** Normalised Levenshtein similarity in {@code [0,1]}: {@code 1 − dist/max(len)}. 1 = identical strings. */
    private static double sim(String a, String b) {
        int m = Math.max(a.length(), b.length());
        return m == 0 ? 1.0 : 1.0 - (double) lev(a, b) / m;
    }

    /** Levenshtein edit distance, two-row DP (O(min·max) time, O(min) space). */
    private static int lev(String a, String b) {
        if (a.length() < b.length()) { String t = a; a = b; b = t; }
        int[] d = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) d[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int prev = d[0]; d[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int tmp = d[j];
                d[j] = Math.min(Math.min(d[j] + 1, d[j - 1] + 1), prev + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
                prev = tmp;
            }
        }
        return d[b.length()];
    }

    /** The detector's diagnostic view for tooling (PDF Inspector): per Y bin (top→bottom), the max line
     *  <b>stability</b> (same-side-neighbour similarity) of any line centred in that bin, plus the Y bins of the
     *  detected header / footer edge lines (−1 = none). Built from the same pass as {@link #detect}. */
    public record Profile(int pages, int bins, double[] rec, int headerBin, int footerBin) {}

    public static Profile profile(OCDDocument doc) {
        int pages = doc.pageCount();
        List<Page> pg = scan(doc);
        double[] rec = new double[VIZ_BINS];
        int headerBin = -1, footerBin = -1;
        double headerYf = Double.MAX_VALUE, footerYf = -1.0;
        for (int p = 0; p < pages; p++) {
            Page pa = pg.get(p); int n = pa.lines.size(); if (n == 0) continue;
            JxRect box = doc.page(p).effectiveBox(); double top = box.maxY(), h = Math.max(1.0, box.height());
            for (int j = 0; j < n; j++) {
                if (pa.raw[j].isEmpty()) continue;
                int b = bin((top - pa.yc[j]) / h);
                rec[b] = Math.max(rec[b], Math.max(strength(pg, p, true, j), strength(pg, p, false, n - 1 - j)));
            }
            List<Integer> head = edgeRun(pg, p, true, -1);
            for (int i : head) { double f = (top - pa.yc[i]) / h; if (f < headerYf) { headerYf = f; headerBin = bin(f); } }
            int hi = head.isEmpty() ? -1 : head.get(head.size() - 1);
            List<Integer> foot = edgeRun(pg, p, false, hi);
            for (int i : foot) { double f = (top - pa.yc[i]) / h; if (f > footerYf) { footerYf = f; footerBin = bin(f); } }
        }
        return new Profile(pages, VIZ_BINS, rec, headerBin, footerBin);
    }

    // ── scan: cluster each page's lines (top-first) and pre-compute baseline y, line height, raw string ──
    private static List<Page> scan(OCDDocument doc) {
        List<Page> pg = new ArrayList<>(doc.pageCount());
        for (int i = 0; i < doc.pageCount(); i++) {
            List<Liner.Line> ls = lines(doc.page(i));
            double[] yc = new double[ls.size()], lh = new double[ls.size()]; String[] raw = new String[ls.size()];
            for (int j = 0; j < ls.size(); j++) { yc[j] = ls.get(j).yc(); lh[j] = lineHeight(ls.get(j)); raw[j] = raw(lineText(ls.get(j))); }
            pg.add(new Page(ls, yc, lh, raw));
        }
        return pg;
    }

    /** The comparison string: upper-cased, whitespace collapsed, digits kept (so a folio's page number is a
     *  one-character difference between neighbours, not a fold to identity). */
    static String raw(String text) {
        return text == null ? "" : JxText.collapse(text.toUpperCase());
    }

    private static int tag(Liner.Line l, String role) {
        int n = 0; for (OCDText t : l.runs()) { t.role(role); n++; } return n;
    }

    /** Max run height on the line — the local scale for the contiguity test. */
    private static double lineHeight(Liner.Line l) {
        double h = 0; for (OCDText t : l.runs()) h = Math.max(h, t.bounds().height());
        return h > 0 ? h : 1.0;
    }

    /** The line's text, read straight off its runs — word spaces already live in them as sentinels (the
     *  {@link Spacer} authority). Liner is pure geometry and carries no text of its own. */
    private static String lineText(Liner.Line l) {
        StringBuilder b = new StringBuilder();
        for (OCDText t : l.runs()) if (t.text() != null) b.append(t.text());
        return b.toString();
    }

    private static int bin(double frac) {
        return Math.max(0, Math.min(VIZ_BINS - 1, (int) Math.round(frac * (VIZ_BINS - 1))));
    }

    /** Text lines of a page, clustered by the shared clusterer and ordered <b>top-most first</b>
     *  (descending baseline y; larger y is higher on the page). */
    private static List<Liner.Line> lines(OCDPage page) {
        List<Liner.Line> ls = Liner.lines(page.texts().filter(t -> t.text() != null && !t.text().isBlank()).toList(), true);
        ls.sort((a, c) -> Double.compare(c.yc(), a.yc()));
        return ls;
    }

    /** True if {@code n} — or any leaf in its subtree — was tagged running furniture by {@link #detect}.
     *  The single authority for the running-role test, shared by reading-order and the LLM structurer. */
    public static boolean isRunning(OCDNode n) {
        return n != null && n.stream().map(OCDNode::role)
                .anyMatch(r -> "page-header".equals(r) || "page-footer".equals(r));
    }
}
