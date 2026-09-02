package sugarcube.jexter.ocd.analysis;

import sugarcube.jexter.core.JxRect;
import sugarcube.jexter.core.JxNum;
import sugarcube.jexter.core.JxText;
import sugarcube.jexter.ocd.model.OCDBreak;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDFont;
import sugarcube.jexter.ocd.model.OCDGlyph;
import sugarcube.jexter.ocd.model.OCDGroup;
import sugarcube.jexter.ocd.model.OCDNode;
import sugarcube.jexter.ocd.model.OCDParagraph;
import sugarcube.jexter.ocd.model.OCDText;

import java.util.ArrayList;
import java.util.List;

/**
 * The <b>single</b> text segmenter and the one block/line model the whole analysis layer shares.
 *
 * <p>It turns a page's flat text runs into a flat list of {@link Block}s — the unit both the
 * physical projection ({@link Paragrapher} → {@link OCDParagraph}) and the logical labeller
 * ({@link StructureBuilder} → {@code OCDStruct}) consume — so line clustering and block grouping
 * live in exactly one place (line clustering itself is the shared {@link Liner}).
 *
 * <p>Two entry points over the same model:
 * <ul>
 *   <li>{@link #segment} — segment raw content (runs → blocks): regionize at full-region whitespace
 *       valleys (columns / bands), cluster each region into baseline lines via {@link Liner},
 *       then group lines into blocks with a deliberately <b>over-segmenting</b> break rule (a line
 *       that opens a new structural unit — a different size, a blank-line gap, or an enumerator
 *       lead — always starts a new block). Over-segmentation is the safe bias: a split is cheap to
 *       merge downstream, a missed split glues two headings.</li>
 *   <li>{@link #fromParagraph} — read an existing {@link OCDParagraph} back as a {@code Block} (its
 *       lines are the spans between {@link OCDBreak} tokens), so the labeller trusts the segmenter's
 *       decisions instead of re-segmenting.</li>
 * </ul>
 *
 * <p>Pure analysis: it never mutates painted content. The {@code Block} type is a transient,
 * mutable analysis record.
 */
public final class Segmenter {

    private Segmenter() {}

    /** Size ratio (larger / smaller) above which a size change starts a new block. */
    private static final double SIZE_JUMP  = 1.20;
    /** A line whose char-weighted bold fraction reaches this counts as a bold (heading-candidate) line. */
    private static final double BOLD_LINE   = 0.60;
    /** Block grain: a block counts as bold / monospace when over half its characters are. */
    private static final double MAJORITY    = 0.50;
    /** × line height: a baseline gap wider than the region's body leading by this much is a paragraph
     *  break — the "interline" cut (a kink in otherwise-even leading) that holds same-style paragraphs apart. */
    private static final double INTERLINE_JUMP = 0.35;
    /** A lead marker's gap must be at least this × the line's own median word space to read as an enumerator. */
    private static final double ENUM_GAP_RATIO = 2.5;
    /** A lead glyph raised at least this × the body size above the baseline is a superscript (¹²³), not inline code. */
    private static final double RAISED_FRAC = 0.20;

    // ── dust absorption (phagocyte) ──────────────────────────────────────────
    /** A block this small (single line, ≤ this many glyphs) is "dust" — a stray accent, mark or fragment. */
    private static final int    DUST_GLYPHS = 2;

    // ── one visual line ──────────────────────────────────────────────────────
    /** A baseline line: its runs (left→right), collapsed text, baseline y, bounds, dominant size,
     *  left indent, and content-node ids. */
    public static final class Line {
        public final List<OCDText> runs;
        public final String text;
        public final double yc;       // baseline (run-box centre y, page Y-up)
        public final double size;     // dominant-run size
        public final double boldFrac; // char-weighted bold fraction (heading-vs-body weight signal)
        public final JxRect bounds;
        public final List<String> nodeIds;
        public final boolean enumLead;   // a short lead token followed by a gap ≫ the line's median space (a., Art. 5)
        public final boolean raisedLead; // the first glyph is a superscript above the baseline (¹²³ alinéa/footnote nº)
        Line(List<OCDText> runs, String text, double yc, double size, double boldFrac, JxRect bounds, List<String> ids,
             boolean enumLead, boolean raisedLead) {
            this.runs = runs; this.text = text; this.yc = yc;
            this.size = size; this.boldFrac = boldFrac; this.bounds = bounds; this.nodeIds = ids;
            this.enumLead = enumLead; this.raisedLead = raisedLead;
        }
        boolean bold() { return boldFrac >= BOLD_LINE; }
    }

    // ── one block (paragraph-grain unit) ───────────────────────────────────────
    /** A block: a stack of {@link Line}s plus the signals every consumer needs. Mutable + transient. */
    public static final class Block {
        public int page;
        public final List<Line> lines = new ArrayList<>();
        public JxRect bounds = JxRect.EMPTY;
        public double size;                 // dominant-run size over the block
        public double boldFrac;             // char-weighted bold fraction
        public final List<String> nodeIds = new ArrayList<>();
        public OCDParagraph source;         // the paragraph this block was read from (null for synthetic)

        public boolean bold() { return boldFrac >= MAJORITY; }
        public String text() {
            StringBuilder sb = new StringBuilder();
            for (Line l : lines) { if (l.text.isEmpty()) continue; if (sb.length() > 0) sb.append(' '); sb.append(l.text); }
            return sb.toString();
        }
    }

    // ── segment a contiguous run span into blocks (for Paragrapher) ─────────────
    /** Segment a contiguous span of sibling text {@code runs} into blocks. The caller ({@link Paragrapher})
     *  drives the content walk — recursing into groups and isolating sibling text spans — so a block never
     *  crosses a graphical-group boundary. Columns, bands and the L-shape case are handled by the single
     *  {@link XYCut} engine. */
    public static List<Block> segment(OCDDocument doc, List<OCDText> runs, int page) {
        if (runs.isEmpty()) return List.of();
        // ① proto-lines: baseline bands, each cut at any >1 em horizontal gap so a margin number / second
        //    column stands as its own unit for XY-Cut. Ink-only — spaces are not derived yet.
        List<Line> protos = lines(doc, runs);
        if (protos.isEmpty()) return List.of();
        double bodyLeading = bodyLeading(protos);                      // the region's settled leading
        java.util.IdentityHashMap<Line, JxRect> bnd = new java.util.IdentityHashMap<>();
        for (Line l : protos) bnd.put(l, l.bounds);
        List<Block> out = new ArrayList<>();
        // ② one SOTA XY-Cut+ over the proto-lines (columns, L-shape, valleys — same engine as reading order)
        for (List<Line> leaf : XYCut.segment(protos, bnd::get)) {
            // ③ rebuild the leaf's clean lines from its runs — band only, no column split (there are no
            //    columns inside a confirmed leaf, so an enumerator parted by an indent rejoins its text).
            //    Pure geometry — spaces are materialised later, by Spacer, after Paragrapher.
            List<OCDText> leafRuns = new ArrayList<>();
            for (Line l : leaf) leafRuns.addAll(l.runs);
            List<Line> clean = new ArrayList<>();
            for (Liner.Line cl : Liner.lines(leafRuns, false)) {  // clean lines, band only — reading order
                Spacer.freeze(doc, cl.runs());                    // freeze the line: order + word spaces, sealed once
                clean.add(line(doc, cl.runs()));                  // Line.text is now built from spaced runs
            }
            // ④ split the leaf's lines into blocks by typography (size / weight) and leading
            splitLeaf(doc, clean, page, bodyLeading, out);
        }
        absorbDust(doc, out);                                        // absorb stray dust blocks into their container
        return out;
    }

    /** The line's writing direction, quantised to a quarter turn — {@code -1} for an empty line. A block is
     *  a paragraph and a paragraph runs along ONE baseline: a rotated caption sharing a column's leaf must
     *  not be absorbed into its body, however well their boxes and type sizes agree. */
    private static int axis(Line ln) {
        if (ln == null || ln.runs.isEmpty()) return -1;
        var m = ln.runs.get(0).transform();
        return Math.floorMod((int) Math.round(Math.atan2(m.b(), m.a()) / (Math.PI / 2)), 4);
    }

    /** Within one XY-Cut leaf (already a tight geometric unit, top→down), recompose blocks in two phases.
     *  First, <b>hard cuts</b> partition the leaf wherever typography or a marker changes — a size jump or
     *  weight change (heading ↔ body), <b>① head-space</b> (a short lead then a &gt;1&nbsp;em gap: an enumerator
     *  {@code a.}, {@code b.}, {@code Art. N}) or <b>② lead-num</b> (a raised smaller lead: an alinéa or footnote
     *  number {@code ¹²³}). Both markers are glyph-geometry, not a lexicon. Then each same-style segment is
     *  passed to {@link #interlineSplit}, which recursively cuts it at an abnormal vertical gap — the leading
     *  signal segmented the way XY-Cut segments a column gap, so two same-style paragraphs set off only by a
     *  wider leading are separated, and every sub-paragraph is judged against its own rhythm. */
    private static void splitLeaf(OCDDocument doc, List<Line> leaf, int page, double bodyLeading, List<Block> out) {
        // phase 1 — hard cuts (typography + leading markers) partition the leaf into same-style segments
        List<List<Line>> segments = new ArrayList<>();
        List<Line> seg = null; Line prev = null;
        for (Line ln : leaf) {
            double hi = Math.max(ln.size, prev == null ? 0 : prev.size), lo = Math.min(ln.size, prev == null ? 0 : prev.size);
            boolean hard = seg == null
                    || axis(ln) != axis(prev)                        // a different baseline direction is a different block
                    || (lo > 0 && hi / lo >= SIZE_JUMP)               // size change (heading/body boundary)
                    || (prev != null && prev.bold() != ln.bold())    // weight change (bold heading ↔ body)
                    || ln.enumLead                                   // ① short lead + gap ≫ the line's median space (a., Art. 5)
                    || ln.raisedLead;                                // ② superscript lead number (¹²³), NOT centred inline code
            if (hard) { seg = new ArrayList<>(); segments.add(seg); }
            seg.add(ln); prev = ln;
        }
        // phase 2 — recursive interline split inside each segment (1-D XY-cut on the leading signal)
        for (List<Line> s : segments)
            for (List<Line> para : interlineSplit(s, bodyLeading)) {
                Block b = new Block(); b.page = page; b.lines.addAll(para); finish(doc, b); out.add(b);
            }
    }

    /** Recursively split a same-style run of lines at its most abnormal vertical gap — the leading signal
     *  judged the way XY-Cut judges a column gap. Find the widest valley; measure it against the typical
     *  leading of the <i>rest</i> ({@link #medianExcept leave-one-out median}, so a 50/50 regime change is
     *  not masked by its own half pulling the reference up); cut if it stands out by more than
     *  {@link #INTERLINE_JUMP} of the line height, then recurse on each side so every sub-paragraph is
     *  judged against its own rhythm. A lone gap (a one-line sub-paragraph has no local rhythm yet) is
     *  judged against {@code leafLeading}, the leaf-wide median — this keeps the very first gap splittable. */
    private static List<List<Line>> interlineSplit(List<Line> lines, double leafLeading) {
        int n = lines.size();
        if (n < 2) return List.of(lines);
        double[] gaps = new double[n - 1]; int imax = 0;
        for (int i = 0; i < n - 1; i++) { gaps[i] = lines.get(i).yc - lines.get(i + 1).yc; if (gaps[i] > gaps[imax]) imax = i; }
        double ref = JxNum.medianExcept(gaps, imax, leafLeading);
        double sz  = Math.max(lines.get(imax).size, lines.get(imax + 1).size);
        if (gaps[imax] - ref <= INTERLINE_JUMP * sz) return List.of(lines);   // homogeneous rhythm → keep whole
        List<List<Line>> r = new ArrayList<>();
        r.addAll(interlineSplit(new ArrayList<>(lines.subList(0, imax + 1)), leafLeading));
        r.addAll(interlineSplit(new ArrayList<>(lines.subList(imax + 1, n)), leafLeading));
        return r;
    }


    /** The region's settled leading: the median consecutive baseline gap over its lines (top→down).
     *  This is the dominant body leading; judging every gap against it — rather than against a running
     *  in-block spacing that only exists from the second gap on — lets an inter-paragraph gap split even
     *  when it opens a leaf (the common case for short legal sub-paragraphs). 0 for a single line. */
    private static double bodyLeading(List<Line> lines) {
        if (lines.size() < 2) return 0;
        List<Line> s = new ArrayList<>(lines);
        s.sort((a, b) -> Double.compare(b.yc, a.yc));                 // top→down (Y-up: higher baseline first)
        double[] g = new double[s.size() - 1];
        for (int i = 1; i < s.size(); i++) g[i - 1] = s.get(i - 1).yc - s.get(i).yc;
        return JxNum.median(g);
    }
    /** Absorb "dust" blocks — a stray accent, mark or fragment isolated as its own tiny block — into the
     *  smallest block whose bounds contain them. Guarded: never folds an enumerator lead (list markers
     *  stay their own block), a figure/table, or a block into a smaller one (a drop-cap is bigger than its
     *  body, so it stays). */
    private static void absorbDust(OCDDocument doc, List<Block> blocks) {
        for (int i = blocks.size() - 1; i >= 0; i--) {
            Block d = blocks.get(i);
            if (!isDust(d)) continue;
            Block host = null; double bestArea = Double.MAX_VALUE;
            for (Block h : blocks) {
                if (h == d || isDust(h)) continue;
                if (d.size > h.size * 1.05) continue;                        // only absorb dust no larger than its host (a drop-cap is bigger → it stays)
                if (!h.bounds.inflate(0.5 * h.size, 0.5 * h.size).contains(d.bounds)) continue;
                double area = h.bounds.width() * h.bounds.height();
                if (area < bestArea) { bestArea = area; host = h; }
            }
            if (host == null) continue;
            int li = 0; double bestDy = Double.MAX_VALUE;                     // merge into the nearest baseline line
            for (int k = 0; k < host.lines.size(); k++) {
                double dy = Math.abs(host.lines.get(k).yc - d.lines.get(0).yc);
                if (dy < bestDy) { bestDy = dy; li = k; }
            }
            List<OCDText> merged = new ArrayList<>(host.lines.get(li).runs);
            for (Line dl : d.lines) merged.addAll(dl.runs);
            host.lines.set(li, line(doc, merged));
            host.bounds = JxRect.EMPTY; host.nodeIds.clear();
            finish(doc, host);
            blocks.remove(i);
        }
    }

    private static boolean isDust(Block b) {
        if (b.lines.size() != 1) return false;
        return glyphCount(b) <= DUST_GLYPHS;
    }

    private static int glyphCount(Block b) {
        int n = 0;
        for (Line l : b.lines) for (OCDText t : l.runs) n += t.count();
        return n;
    }


    /** Cluster a region's runs into proto-lines (baseline bands, column-split) via the shared clusterer. */
    private static List<Line> lines(OCDDocument doc, List<OCDText> region) {
        List<Line> out = new ArrayList<>();
        for (Liner.Line cl : Liner.lines(region, true)) out.add(line(doc, cl.runs()));
        return out;
    }

    /** Build a {@link Line} from runs (left→right): its text is the plain concatenation of the runs' text —
     *  spaces already live in the runs as sentinels once {@link Spacer} has frozen the line — plus the
     *  baseline, dominant size, char-weighted bold fraction, bounds, ids and the lead-token signals. */
    private static Line line(OCDDocument doc, List<OCDText> runs) {
        StringBuilder sb = new StringBuilder();
        double yc = 0;
        JxRect bounds = JxRect.EMPTY; List<String> ids = new ArrayList<>();
        for (OCDText t : runs) {
            sb.append(t.text() == null ? "" : t.text());
            JxRect b = t.bounds();
            yc += b.cy();
            bounds = bounds.isEmpty() ? b : bounds.union(b);
            if (t.id() != null) ids.add(t.id());
        }
        double[] st = runStyle(doc, runs);                    // [dominant size, bold fraction]
        double[] hs = headSignal(doc, runs);                  // [enumLead, raisedLead]
        return new Line(new ArrayList<>(runs), JxText.collapse(sb.toString()),
                yc / Math.max(1, runs.size()),
                st[0], st[1], bounds, ids,
                hs[0] > 0, hs[1] > 0);
    }

    /** Two geometric lead signals for {@link #splitLeaf}, read off the frozen line's glyphs:
     *  <ul><li><b>enumLead</b> — the <i>lead token</i> (everything up to the first word space) is followed by a
     *      space clearly wider than the line's own median word space: a real marker indent ({@code a.²⁹},
     *      {@code 1.}, {@code i)}). Both widths are measured for real and the test is relative to the line, so
     *      ordinary justified prose — where the first space is a normal space — never trips it.</li>
     *  <li><b>raisedLead</b> — the leftmost glyph sits ABOVE the line baseline: a superscript alinéa / footnote
     *      number ({@code ¹²³}). Inline monospace code is vertically centred, not raised, so it is excluded.</li></ul>
     *  Returns {@code [enumLead, raisedLead]} as 0/1. */
    private static double[] headSignal(OCDDocument doc, List<OCDText> runs) {
        // walk the line in reading order: ink-glyph metrics, plus the real pixel width of every word space
        // (a sentinel carries no width, so a space = nextInkX − (prevInkX + prevAdvance·size)).
        List<double[]> ink = new ArrayList<>();          // [x, baselineY, size]
        List<Double> spaces = new ArrayList<>();         // pixel width of each word space, in order
        double headSpace = -1, prevEnd = Double.NaN;
        boolean afterSpace = false;
        for (OCDText t : runs) {
            OCDFont f = doc.findFont(t.fontId());
            double sz = t.fontSize();
            for (OCDText.Glyph g : t.glyphs()) {
                if (g.unicode() != null && g.unicode().isBlank()) { afterSpace = true; continue; }   // word-space sentinel
                double x = t.glyphPageX(g);
                if (afterSpace && !Double.isNaN(prevEnd)) {
                    double w = x - prevEnd;                                                          // real space width
                    if (w > 0) { spaces.add(w); if (headSpace < 0) headSpace = w; }
                }
                OCDGlyph og = f == null ? null : f.glyph(g.gid());
                ink.add(new double[]{ x, t.transform().apply(g.x(), 0).y(), sz });
                prevEnd = x + (og == null ? 0.5 : og.advance()) * sz;
                afterSpace = false;
            }
        }
        int n = ink.size();
        if (n < 2) return new double[]{0, 0};
        double[] ys = new double[n], szs = new double[n];
        for (int i = 0; i < n; i++) { ys[i] = ink.get(i)[1]; szs[i] = ink.get(i)[2]; }
        double baseY = JxNum.median(ys), bodySize = JxNum.median(szs);
        double medSpace = JxNum.median(spaces.stream().mapToDouble(Double::doubleValue).toArray());

        boolean enumLead   = medSpace > 0 && headSpace >= ENUM_GAP_RATIO * medSpace;                  // wide space after the lead token
        boolean raisedLead = (ink.get(0)[1] - baseY) > RAISED_FRAC * bodySize && ink.get(0)[2] < bodySize - 0.5;  // superscript lead
        return new double[]{ enumLead ? 1 : 0, raisedLead ? 1 : 0 };
    }


    // ── read an existing paragraph back as a block (for StructureBuilder) ──────
    /** Adapt an {@link OCDParagraph} into a {@link Block}: lines are the spans between
     *  {@link OCDBreak} tokens, signals recomputed from the same runs (so it matches what
     *  {@link #segment} produced — no second segmentation). */
    public static Block fromParagraph(OCDDocument doc, OCDParagraph para, int page) {
        return fromParagraphs(doc, List.of(para), page);
    }

    /** Adapt the fragments of ONE text flow back into a single {@link Block} — the inverse of the paint-order
     *  split {@link Paragrapher} had to make (a paragraph wraps a contiguous paint span, so an interleaved
     *  block emits several wrappers sharing an {@link OCDParagraph#flow()}).
     *
     *  <p>The visual lines are rebuilt over the union of the runs by {@link Liner} — the single line
     *  authority — rather than read off the {@link OCDBreak} markers: a fragment boundary can fall MID-LINE
     *  (two runs of one baseline with a foreign run painted between them), and only the line authority can
     *  say so. Measured to reproduce the markers exactly on a whole document, so this is one path, not a
     *  special case: the markers are a projection of what {@code Liner} already decided. */
    public static Block fromParagraphs(OCDDocument doc, List<OCDParagraph> paras, int page) {
        Block b = new Block();
        b.page = page; b.source = paras.isEmpty() ? null : paras.get(0);
        List<OCDText> runs = new ArrayList<>();
        for (OCDParagraph para : paras)
            for (OCDNode c : flatten(para)) if (c instanceof OCDText t && t.count() > 0) runs.add(t);
        for (Liner.Line cl : Liner.lines(runs, false)) b.lines.add(line(doc, cl.runs()));
        finish(doc, b);
        return b;
    }

    /** A paragraph's content in order, descending through any nested non-paragraph groups. */
    private static List<OCDNode> flatten(OCDGroup g) {
        List<OCDNode> out = new ArrayList<>();
        for (OCDNode c : g.children()) {
            if (c instanceof OCDBreak || c instanceof OCDText) out.add(c);
            else if (c instanceof OCDGroup gg) out.addAll(flatten(gg));
        }
        return out;
    }

    /** Compute the block-level signals (bounds, dominant size, bold/mono fractions, ids) from its lines. */
    private static void finish(OCDDocument doc, Block b) {
        List<OCDText> runs = new ArrayList<>();
        for (Line ln : b.lines) {
            b.bounds = b.bounds.isEmpty() ? ln.bounds : b.bounds.union(ln.bounds);
            b.nodeIds.addAll(ln.nodeIds);
            runs.addAll(ln.runs);
        }
        double[] st = runStyle(doc, runs);
        b.size = st[0];
        b.boldFrac = st[1];
    }

    /** The dominant run size of a run set — the size of the run carrying the most ink (stripped chars).
     *  Single size authority shared by line build, block finish and heading-id extraction. */
    static double dominantSize(List<OCDText> runs) {
        double size = 0; int best = -1;
        for (OCDText t : runs) {
            int ch = (t.text() == null ? "" : t.text()).strip().length();
            if (ch > best) { best = ch; size = t.fontSize(); }
        }
        return size;
    }

    /** Dominant size + char-weighted bold fraction of a run set. */
    private static double[] runStyle(OCDDocument doc, List<OCDText> runs) {
        double bold = 0, total = 0;
        for (OCDText t : runs) {
            int ch = (t.text() == null ? "" : t.text()).strip().length();
            total += ch;
            OCDFont fnt = doc.findFont(t.fontId());
            if (fnt != null && fnt.isBold()) bold += ch;
        }
        return new double[]{ dominantSize(runs), total > 0 ? bold / total : 0 };
    }
}
