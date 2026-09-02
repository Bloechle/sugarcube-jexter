package sugarcube.jexter.ocd.analysis;

import sugarcube.jexter.core.JxText;
import sugarcube.jexter.ocd.model.OCDBreak;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDGroup;
import sugarcube.jexter.ocd.model.OCDNode;
import sugarcube.jexter.ocd.model.OCDOutline;
import sugarcube.jexter.ocd.model.OCDPage;
import sugarcube.jexter.ocd.model.OCDParagraph;
import sugarcube.jexter.ocd.model.OCDText;

import java.util.ArrayList;
import java.util.List;

/**
 * Aligns the text segmentation on the document's own <b>navigation</b> (PDF outline / bookmarks /
 * nav): a bookmark title is ground truth quoted from the page, so wherever the geometric
 * segmentation disagrees with it, the navigation wins — a title split across blocks is
 * <b>merged</b> back into one, a title fused with its following body is <b>split</b> out, and the
 * resulting title paragraph is tagged {@code heading-N} (N = the bookmark's tree depth). This is
 * not a lexicon: the matched strings come from the document itself.
 *
 * <p>Mechanics are line-grained and render-neutral by construction: a paragraph's visual lines are
 * the spans between its {@link OCDBreak}s, and every operation only regroups CONTIGUOUS siblings —
 * split cuts a paragraph's children at a break, merge concatenates adjacent sibling paragraphs
 * with a break in between. Child order is preserved globally, so paint (z) order — and the pixels —
 * cannot change. Idempotent: on a re-run each title matches one whole paragraph and is only
 * re-tagged. Furniture is never matched. Runs before {@link sugarcube.jexter.ocd.model.IdStamper} so
 * the wrappers a split creates get their ids minted by the existing pass.
 *
 * <p>Matching is strict — the normalized (whitespace-collapsed, case-folded) concatenation of
 * consecutive lines must EQUAL the normalized title; among several matches on the page the one
 * nearest the bookmark's destination {@code y} wins. An unmatched entry changes nothing (the
 * structure chain still anchors it best-effort downstream).
 */
public final class OutlineAligner {

    private OutlineAligner() {}

    private static final int MAX_LEVEL = 6;

    /** Align segmentation and heading roles on the outline. No-op without one. */
    public static void align(OCDDocument doc) {
        if (doc.outline().isEmpty()) return;
        List<Entry> entries = new ArrayList<>();
        for (OCDOutline o : doc.outline()) flatten(o, 1, entries);
        int tagged = 0, merged = 0, split = 0;
        for (Entry e : entries) {
            if (e.page < 0 || e.page >= doc.pageCount()) continue;
            String want = norm(e.title);
            if (want.isEmpty()) continue;
            Window w = find(doc.page(e.page), want, e.y);
            if (w == null) continue;
            int[] ops = carve(w);
            split += ops[0]; merged += ops[1];
            w.paras.get(0).p.role("heading-" + Math.min(MAX_LEVEL, e.level));
            tagged++;
        }
        if (tagged > 0)
            sugarcube.jexter.core.JxLog.info(OutlineAligner.class, tagged + " headings aligned (" + split + " splits, " + merged + " merges)");
    }

    private static void flatten(OCDOutline o, int level, List<Entry> out) {
        if (o.hasDestination() && o.title() != null && !o.title().isBlank())
            out.add(new Entry(o.title(), o.pageIndex(), o.hasY() ? o.y() : Double.NaN, level));
        for (OCDOutline c : o.children()) flatten(c, level + 1, out);
    }

    private record Entry(String title, int page, double y, int level) {}

    // ── the page as paragraphs of lines (model-level, fresh after every mutation) ──

    /** One paragraph and its line partition: {@code cuts[k]} = child index of the k-th line's first
     *  node; a line spans children {@code [cuts[k], cuts[k+1])} minus its trailing break. */
    private static final class Para {
        final OCDParagraph p; final List<OCDNode> siblings; final int index;   // index among siblings
        final List<Integer> cuts = new ArrayList<>();                           // line starts (child indices)
        final List<String> lineText = new ArrayList<>();
        Para(OCDParagraph p, List<OCDNode> siblings, int index) { this.p = p; this.siblings = siblings; this.index = index; }
        int lineCount() { return lineText.size(); }
    }

    private static List<Para> collect(OCDPage page) {
        List<Para> out = new ArrayList<>();
        collect(page.content(), out);
        return out;
    }

    private static void collect(List<OCDNode> kids, List<Para> out) {
        for (int i = 0; i < kids.size(); i++) {
            OCDNode n = kids.get(i);
            if (n instanceof OCDParagraph p) {
                Para para = new Para(p, kids, i);
                lines(p, para);
                if (!para.lineText.isEmpty() && !isFurniture(p)) out.add(para);
            } else if (n instanceof OCDGroup g) collect(g.children(), out);
        }
    }

    /** A run whose size clearly drops below its line's dominant size is a footnote
     *  reference mark (superscript digits in the stream): it stays in the block but is
     *  excluded from the MATCHING text — bookmark titles never quote the marks. */
    private static final double MARK_RATIO = 0.72;

    private static void lines(OCDParagraph p, Para para) {
        List<OCDNode> kids = p.children();
        int start = 0;
        List<OCDText> runs = new ArrayList<>();
        for (int i = 0; i <= kids.size(); i++) {
            boolean atBreak = i == kids.size() || kids.get(i) instanceof OCDBreak;
            if (!atBreak) {
                if (kids.get(i) instanceof OCDText t) runs.add(t);
                continue;
            }
            double max = 0;
            for (OCDText t : runs) max = Math.max(max, t.fontSize());
            StringBuilder sb = new StringBuilder();
            for (OCDText t : runs)
                if (runs.size() == 1 || t.fontSize() > MARK_RATIO * max) sb.append(t.text()).append(' ');
            String txt = norm(sb.toString());
            if (!txt.isEmpty()) { para.cuts.add(start); para.lineText.add(txt); }
            runs.clear();
            start = i + 1;
        }
    }

    private static boolean isFurniture(OCDParagraph p) {
        for (OCDNode n : p.children())
            if (n instanceof OCDText t && t.hasRole()
                    && (t.role().equals("page-header") || t.role().equals("page-footer"))) return true;
        return false;
    }

    // ── window search: consecutive lines (crossing only ADJACENT sibling paragraphs) == title ──

    private static final class Window {
        final List<Para> paras = new ArrayList<>();   // the paragraphs touched, in order
        int startLine, endLine;                       // line indices in first / last para
        double y = Double.NaN;                        // first line's paragraph y (tie-break)
    }

    private static Window find(OCDPage page, String want, double y) {
        List<Para> paras = collect(page);
        List<Window> hits = new ArrayList<>();
        for (int pi = 0; pi < paras.size(); pi++) {
            Para p0 = paras.get(pi);
            for (int li = 0; li < p0.lineCount(); li++) {
                Window w = tryFrom(paras, pi, li, want);
                if (w != null) hits.add(w);
            }
        }
        if (hits.isEmpty()) return null;
        if (hits.size() == 1 || Double.isNaN(y)) return hits.get(0);
        Window best = hits.get(0); double bd = dist(best, y);
        for (Window w : hits) { double d = dist(w, y); if (d < bd) { best = w; bd = d; } }
        return best;
    }

    private static double dist(Window w, double y) {
        return Double.isNaN(w.y) ? Double.MAX_VALUE : Math.abs(w.y - y);
    }

    private static Window tryFrom(List<Para> paras, int pi, int li, String want) {
        Window w = new Window();
        StringBuilder acc = new StringBuilder();
        Para p = paras.get(pi);
        w.paras.add(p); w.startLine = li;
        w.y = topY(p);
        int line = li;
        while (true) {
            if (acc.length() > 0) acc.append(' ');
            acc.append(w.paras.get(w.paras.size() - 1).lineText.get(line));
            if (acc.length() > want.length()) return null;
            if (acc.length() == want.length())
                { w.endLine = line; return acc.toString().contentEquals(want) ? w : null; }
            // advance: next line, else the ADJACENT next sibling paragraph
            Para cur = w.paras.get(w.paras.size() - 1);
            if (line + 1 < cur.lineCount()) { line++; continue; }
            int next = paras.indexOf(cur) + 1;
            if (next >= paras.size()) return null;
            Para np = paras.get(next);
            if (np.siblings != cur.siblings || np.index != cur.index + 1) return null;   // not adjacent siblings
            w.paras.add(np); line = 0;
        }
    }

    private static double topY(Para p) {
        for (OCDNode n : p.p.children()) if (n instanceof OCDText t) return t.bounds().y();
        return Double.NaN;
    }

    // ── carve: split the edges, merge the middle — contiguous regrouping only ──

    /** Make the window exactly one paragraph. Returns {splits, merges}. */
    private static int[] carve(Window w) {
        int splits = 0, merges = 0;
        Para first = w.paras.get(0), last = w.paras.get(w.paras.size() - 1);
        if (last.lineCount() > w.endLine + 1) { splitAt(last, w.endLine + 1); splits++; }     // tail off
        if (w.startLine > 0) {                                                                 // head off
            OCDParagraph title = splitAt(first, w.startLine); splits++;
            // the window's paragraph is now the NEW one, one slot to the right
            w.paras.set(0, reindex(first, title));
        }
        // merge everything after the first into it
        OCDParagraph home = w.paras.get(0).p;
        for (int k = 1; k < w.paras.size(); k++) {
            OCDParagraph b = w.paras.get(k).p;
            List<OCDNode> pk = w.paras.get(k).siblings;
            home.add(new OCDBreak());
            for (OCDNode c : new ArrayList<>(b.children())) home.add(c);
            b.children().clear();
            pk.remove(b);
            merges++;
        }
        return new int[]{ splits, merges };
    }

    /** Cut {@code para} before its {@code line}-th line: the tail becomes a NEW paragraph inserted
     *  right after it (child order preserved — paint untouched); the boundary break is dropped. */
    private static OCDParagraph splitAt(Para para, int line) {
        List<OCDNode> kids = para.p.children();
        int cut = para.cuts.get(line);
        OCDParagraph tail = new OCDParagraph();
        for (int i = cut; i < kids.size(); i++) tail.add(kids.get(i));
        kids.subList(cut, kids.size()).clear();
        while (!kids.isEmpty() && kids.get(kids.size() - 1) instanceof OCDBreak) kids.remove(kids.size() - 1);
        para.siblings.add(para.index + 1, tail);
        return tail;
    }

    private static Para reindex(Para old, OCDParagraph title) {
        Para np = new Para(title, old.siblings, old.index + 1);
        lines(title, np);
        return np;
    }

    private static String norm(String s) { return JxText.collapse(s).toUpperCase(); }
}
