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
 *   <li>A style that sets running text is no heading style, however large: two paragraphs of copy or more
 *       ({@link Figures#isCopy}: four lines and more) set in it make it a text style — an article set larger
 *       than the rest of the report read its two-line paragraphs as headings.</li>
 *   <li>The single largest elevated style standing above every heading level is the <b>title</b>; it
 *       alone is exempt from recurrence, since a document title may appear only once.</li>
 *   <li>Unless several stand there: a magazine sets each article's title in a size of its own, fitted to
 *       its page (55 to 103 pt in one report), so none recurs. The styles above every heading level are then
 *       ONE level, the first, when together they cover {@link #MIN_RUNS} pages or more (sizes that differ ask
 *       more evidence than one that recurs) — on fewer, they are a cover, and the largest is the title. A
 *       display title is the largest text of every page it is on; the top recurring level shares the first
 *       level with them when it heads its pages too (one of those sizes happened to be used twice).</li>
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
    /** A style sets running text once this many paragraphs of copy are set in it. */
    private static final int    COPY_BLOCKS = 2;
    /** A block/line counts as bold when at least this fraction of its ink is bold (whole-line-bold rule) —
     *  the one threshold: {@link Segmenter} cuts on the same. */
    static final double BOLD_LINE  = 0.60;

    /** A typographic signature: point size (rounded to ½ pt) and weight. The unit of the style alphabet. */
    public record Style(double size, boolean bold) {}

    private final Map<Style, Integer> level;   // heading signature → 1-based level (1 = largest)
    private final Style title;                  // the document title's style, or null
    private final Style body;                   // the style carrying the most ink, or null for a textless document

    private FontProfile(Map<Style, Integer> level, Style title, Style body) {
        this.level = level;
        this.title = title;
        this.body = body;
    }

    /** Whether {@code s} is set larger than the body — a display size, whether or not it recurs as a heading. */
    public boolean isLarger(Style s) { return body != null && s.size() >= body.size() * LARGER; }

    /** Whether {@code s} is the document title's style (the one elevated style exempt from recurrence). */
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
                // Invisible text votes: a scan's words ARE invisible (its OCR layer), and skipped they left the
                // profile empty and the scan without a single heading.
                if (t.count() == 0) return;
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
        if (ink.isEmpty()) return new FontProfile(Map.of(), null, null);

        // the styles running text is set in: paragraphs of copy (read from the analysed paragraphs; none before)
        Map<Style, Integer> copy = new HashMap<>();
        for (int p = 0; p < doc.pageCount(); p++)
            doc.page(p).nodes().filter(n -> n instanceof sugarcube.jexter.ocd.model.OCDParagraph && Figures.isCopy(n) && !Furniture.isRunning(n))
               .forEach(n -> {
                   List<OCDText> runs = new ArrayList<>();
                   n.stream().filter(OCDText.class::isInstance).forEach(t -> runs.add((OCDText) t));
                   copy.merge(styleOfLine(runs, doc), 1, Integer::sum);
               });

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
            if (!elevated || copy.getOrDefault(s, 0) >= COPY_BLOCKS) continue;   // a text style is no heading style
            if (title == null || s.size() > title.size()) title = s;   // track the biggest elevated style
            boolean recurs = pages.get(s).size() >= MIN_PAGES || occur.get(s) >= MIN_RUNS;
            if (recurs) heads.add(s);
        }
        // display titles: each the largest text of its page, in a size no other title shares, on several pages
        Map<Integer, Double> pageTop = new HashMap<>();
        for (Style s : ink.keySet()) for (int pg : pages.get(s)) pageTop.merge(pg, s.size(), Math::max);
        java.util.function.Predicate<Style> tops = s -> pages.get(s).stream().allMatch(pg -> s.size() >= pageTop.get(pg) - SIZE_TOL);
        List<Style> display = new ArrayList<>();
        Set<Integer> shown = new HashSet<>();
        for (Style s : ink.keySet()) {
            if (s.equals(body) || heads.contains(s) || s.size() < body.size() * LARGER || !tops.test(s)
                    || copy.getOrDefault(s, 0) >= COPY_BLOCKS) continue;
            display.add(s);
            shown.addAll(pages.get(s));
        }
        // sizes that differ need more evidence than one that recurs: as many pages as a style needs runs
        boolean magazine = display.size() > 1 && shown.size() >= MIN_RUNS;
        // rank by size desc, bold before regular at equal size → coalesce equal (size,bold) into one level
        heads.sort(Comparator.comparingDouble(Style::size).reversed()
                .thenComparing(Comparator.comparing(Style::bold).reversed()));
        Map<Style, Integer> level = new HashMap<>();
        int lvl = 0; double lastSize = Double.NaN; boolean lastBold = false;
        // the display titles are the first level — beside the top recurring one when it too heads its pages
        boolean beside = magazine && !heads.isEmpty() && tops.test(heads.get(0));
        if (magazine) { lvl = beside ? 0 : 1; for (Style s : display) level.put(s, 1); title = null; }
        for (Style s : heads) {
            if (Double.isNaN(lastSize) || Math.abs(s.size() - lastSize) > SIZE_TOL || s.bold() != lastBold) {
                lvl++; lastSize = s.size(); lastBold = s.bold();
            }
            level.put(s, lvl);
        }
        // the document title: the biggest elevated style, only when it stands ABOVE every heading level
        // (so a recurring heading is never mistaken for the title). Null when there is no such singular style.
        if (!magazine && title != null && (heads.isEmpty() || title.size() > heads.get(0).size() + SIZE_TOL) && !level.containsKey(title)) {
            // keep title
        } else title = null;
        return new FontProfile(level, title, body);
    }

    private static double round(double v) { return Math.round(v * 2.0) / 2.0; }   // ½-pt buckets
}
