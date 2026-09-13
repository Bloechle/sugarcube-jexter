package sugarcube.jexter.ocd.analysis;

import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDFont;
import sugarcube.jexter.ocd.model.OCDText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Document-wide typographic profile — the single source of truth for "what is a heading".
 *
 * <p>It reads the visual style of every text run across <b>all</b> pages and accumulates, per distinct
 * {@link Style} signature {@code (rounded point size, bold)}, the total glyph count (its "ink mass") and
 * the set of pages it appears on. Headings are then defined by what the <i>document</i> does, never by a
 * per-block guess:
 * <ul>
 *   <li><b>Body</b> = the signature carrying the most ink — the prose the document is set in.</li>
 *   <li>A style is <b>elevated</b> when it is <b>larger</b> than the body, or <b>bold at body size</b>
 *       (body itself not bold). An elevated style becomes a heading level only if it also <b>recurs</b>
 *       — appears on at least {@link #MIN_PAGES} pages or {@link #MIN_RUNS} times. Recurrence is what
 *       separates a real heading style from a one-off emphasised phrase or a lone decorative size.</li>
 *   <li>The single largest elevated style standing above every heading level is the <b>title</b>; it
 *       alone is exempt from recurrence, since a document title may appear only once.</li>
 *   <li>Levels are the distinct heading signatures ranked by <b>size descending</b> (bold before regular
 *       at equal size) → level 1 (largest) … N. Size order <i>is</i> the hierarchy, document-wide and
 *       unambiguous; no per-block ratio threshold is ever consulted again.</li>
 * </ul>
 *
 * <p>Running heads/feet are excluded from the accumulation (via {@link Furniture#isRunning}) so page
 * furniture never pollutes the body or heading statistics. The profile is built once per document and the
 * labeller maps each block's {@linkplain #styleOfLine line style} to a level by pure lookup.
 */
public final class FontProfile {

    /** A style is "larger than body" once its size exceeds the body's by this ratio (robust to jitter). */
    private static final double LARGER     = 1.08;
    /** Two sizes count as the same level when within this many points (sub-point rendering jitter). */
    private static final double SIZE_TOL   = 0.6;
    /** A bold-at-body-size heading must recur on at least this many pages… */
    private static final int    MIN_PAGES  = 2;
    /** …or at least this many runs across the document. */
    private static final int    MIN_RUNS   = 3;
    /** A block/line counts as bold when at least this fraction of its ink is bold (whole-line-bold rule). */
    private static final double BOLD_LINE  = 0.60;

    /** A typographic signature: point size (rounded to ½ pt) and weight. The unit of the style alphabet. */
    public record Style(double size, boolean bold) {}

    private final Style body;
    private final Map<Style, Integer> level;   // heading signature → 1-based level (1 = largest)
    private final int levels;
    private final Style title;                  // the document title's style, or null

    private FontProfile(Style body, Map<Style, Integer> level, Style title) {
        this.body = body;
        this.level = level;
        this.title = title;
        this.levels = level.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    public Style body()   { return body; }
    public int   levels() { return levels; }
    /** The document title's style, or {@code null} if the document has no singular title-sized style. */
    public Style title()  { return title; }
    public boolean isTitle(Style s) { return title != null && title.equals(s); }

    /** The heading level of a style: 0 = body / not a heading, 1..N = heading rank (1 largest). */
    public int headingLevel(Style s) {
        Integer l = level.get(s);
        if (l != null) return l;
        // tolerant match: snap to a known heading signature within rounding of size and same weight
        for (Map.Entry<Style, Integer> e : level.entrySet())
            if (e.getKey().bold() == s.bold() && Math.abs(e.getKey().size() - s.size()) <= SIZE_TOL) return e.getValue();
        return 0;
    }

    /** A style from an explicit size + weight, size snapped to the ½-pt alphabet — the one place a {@link Style}
     *  is minted from a measured size, so callers never re-implement the bucket. */
    public static Style styleOf(double size, boolean bold) { return new Style(round(size), bold); }

    /** The style of one run: its (rounded) size and the weight of its font. */
    public static Style styleOf(OCDText t, OCDDocument doc) {
        OCDFont f = t.fontId() == null ? null : doc.findFont(t.fontId());
        return styleOf(t.fontSize(), f != null && f.isBold());
    }

    /** The style of a whole line: its dominant (most-ink) size, and bold iff the line is bold as a whole
     *  ({@link #BOLD_LINE} of the ink) — never promotes an inline-bold word to a heading weight. */
    public static Style styleOfLine(List<OCDText> runs, OCDDocument doc) {
        Map<Double, Long> ink = new HashMap<>();
        long bold = 0, total = 0;
        for (OCDText t : runs) {
            int n = t.count();
            if (n == 0) continue;
            double sz = round(t.fontSize());
            ink.merge(sz, (long) n, Long::sum);
            OCDFont f = t.fontId() == null ? null : doc.findFont(t.fontId());
            if (f != null && f.isBold()) bold += n;
            total += n;
        }
        if (total == 0) return new Style(0, false);
        double domSize = ink.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
        return new Style(domSize, bold >= BOLD_LINE * total);
    }

    /** Build the profile from the whole document, excluding running heads/feet from the statistics. The
     *  unit is the <b>line</b> (the whole-line-bold rule), so an inline-bold word never votes as a heading. */
    public static FontProfile of(OCDDocument doc) {
        Map<Style, Long> ink = new HashMap<>();          // total glyphs per style — the body-mass vote
        Map<Style, Integer> occur = new HashMap<>();      // number of lines per style — the recurrence count
        Map<Style, Set<Integer>> pages = new HashMap<>();
        for (int p = 0; p < doc.pageCount(); p++) {
            final int pg = p;
            List<OCDText> runs = new ArrayList<>();
            doc.page(p).texts().forEach(t -> {
                if (t.isInvisible() || t.count() == 0) return;
                if (Furniture.isRunning(t)) return;                 // page furniture never votes
                runs.add(t);
            });
            for (Liner.Line line : Liner.lines(runs, true)) {
                Style s = styleOfLine(line.runs(), doc);
                if (s.size() <= 0) continue;
                long glyphs = 0; for (OCDText t : line.runs()) glyphs += t.count();
                ink.merge(s, glyphs, Long::sum);
                occur.merge(s, 1, Integer::sum);
                pages.computeIfAbsent(s, k -> new HashSet<>()).add(pg);
            }
        }
        if (ink.isEmpty()) return new FontProfile(new Style(round(12), false), Map.of(), null);

        Style body = ink.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();

        // a heading style is ELEVATED above body (larger, or bold at body size) AND RECURS — recurrence is
        // what tells a real heading style (sz12 bold: 4.1, 4.2, 4.3…) from a one-off figure label or a
        // decorative title-page size. No size threshold is trusted on its own.
        List<Style> heads = new ArrayList<>();
        Style title = null;                                            // largest elevated style overall (may not recur)
        for (Style s : ink.keySet()) {
            if (s.equals(body)) continue;
            boolean larger   = s.size() >= body.size() * LARGER;
            boolean boldBody = !larger && s.bold() && !body.bold() && Math.abs(s.size() - body.size()) <= SIZE_TOL;
            boolean elevated = larger || boldBody;
            if (!elevated) continue;
            if (title == null || s.size() > title.size()) title = s;   // track the biggest elevated style
            boolean recurs = pages.get(s).size() >= MIN_PAGES || occur.get(s) >= MIN_RUNS;
            if (recurs) heads.add(s);
        }
        // rank by size desc, bold before regular at equal size → coalesce equal (size,bold) into one level
        heads.sort(Comparator.comparingDouble(Style::size).reversed()
                .thenComparing(Comparator.comparing(Style::bold).reversed()));
        Map<Style, Integer> level = new HashMap<>();
        int lvl = 0; double lastSize = Double.NaN; boolean lastBold = false;
        for (Style s : heads) {
            if (Double.isNaN(lastSize) || Math.abs(s.size() - lastSize) > SIZE_TOL || s.bold() != lastBold) {
                lvl++; lastSize = s.size(); lastBold = s.bold();
            }
            level.put(s, lvl);
        }
        // the document title: the biggest elevated style, only when it stands ABOVE every heading level
        // (so a recurring heading is never mistaken for the title). Null when there is no such singular style.
        if (title != null && (heads.isEmpty() || title.size() > heads.get(0).size() + SIZE_TOL) && !level.containsKey(title)) {
            // keep title
        } else title = null;
        return new FontProfile(body, level, title);
    }

    private static double round(double v) { return Math.round(v * 2.0) / 2.0; }   // ½-pt buckets
}
