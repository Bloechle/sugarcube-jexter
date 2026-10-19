package sugarcube.jexter.ocd.model;

import sugarcube.jexter.core.JxRect;
import sugarcube.jexter.core.JxText;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A flat lookup from a content reference {@code (page, node id)} to its {@link OCDNode},
 * built once by walking every page's node tree (groups recursed). The logical
 * {@link sugarcube.jexter.ocd.model.OCDStructNode} tree references content this way, so every
 * structure-driven projection (HTML, Markdown, DocTags, EPUB navigation) resolves refs the
 * same way — this centralizes the index and the page-scoped key scheme they all repeated.
 *
 * <p>Node ids are page-scoped, so the key folds in the page ({@code page '\0' id}).
 * {@link #appendText} concatenates the text under a node (groups recursed); {@link #text(OCDStructNode)}
 * is the single canonical resolver every structure-driven projection uses — referenced content,
 * or the node's denormalized {@link OCDStructNode#text()} when it has no resolvable refs.
 */
public final class OCDIndex {

    private final Map<String, OCDNode> byKey = new HashMap<>();
    private final Map<OCDNode, Integer> position = new java.util.IdentityHashMap<>();   // place in the page's content
    private final Vocabulary vocabulary = new Vocabulary();

    private OCDIndex() {}

    /** Build the index for an entire document. */
    public static OCDIndex of(OCDDocument doc) {
        OCDIndex ix = new OCDIndex();
        for (int p = 0; p < doc.pageCount(); p++) {
            int page = p;
            doc.page(p).nodes().forEach(n -> {
                ix.position.put(n, ix.position.size());
                if (n.id() != null) ix.byKey.put(key(page, n.id()), n);
                if (n instanceof OCDText t) ix.vocabulary.add(t.text());
            });
        }
        return ix;
    }

    /** The node referenced on {@code page} by {@code id}, or {@code null}. */
    public OCDNode node(int page, String id) { return byKey.get(key(page, id)); }

    private static String key(int page, String id) { return page + "\u0000" + id; }

    /** Append the text under {@code n}: OCDText runs joined by single spaces, groups recursed — but
     *  two runs that sit flush together are joined without a space, and a soft hyphen at a line break
     *  is removed so the word is rejoined. */
    public static void appendText(StringBuilder b, OCDNode n) {
        OCDText[] prev = { null };
        n.stream().filter(OCDText.class::isInstance).map(OCDText.class::cast).forEach(t -> {
            if (t.text() != null && !t.text().isEmpty()) { join(b, t, prev[0], null); prev[0] = t; }
        });
    }

    /** Append one run's text to {@code b}. Every word space — intra-glyph, inter-run and inter-line — already
     *  lives in the runs as a sentinel (see {@link sugarcube.jexter.ocd.analysis.Spacer}), so the index is a
     *  plain concatenation. The only readout-time transform is the soft hyphen: a trailing {@code '-'} that a
     *  line break split is dropped to rejoin the word — the hyphen stays <i>painted</i> in the model, so it can
     *  only be resolved here, not materialised. (OCDBreak is a layout flag, never a spacing mechanism.) */
    private static void join(StringBuilder b, OCDText cur, OCDText prev, Vocabulary words) {
        String x = cur.text();
        if (prev != null && b.length() > 0 && b.charAt(b.length() - 1) == '-' && softHyphen(prev, cur, x)) {
            if (words == null || !words.compound(b, x)) b.deleteCharAt(b.length() - 1);   // line-break hyphen → rejoin the word
            b.append(x);
            return;
        }
        // Two runs cannot be adjacent on one line if the second STARTS at or left of where the first did:
        // on one line a run always advances strictly, so reading has gone back to a margin — a line boundary,
        // whatever the physical layer recorded. Normally an OCDBreak marks it and Spacer leaves a trailing
        // space; when a taller neighbouring column chains two bands into one line (a solution grid beside its
        // caption, measured), neither happens and the words weld — "carré contient" + "tous les chiffres"
        // reads as "contienttous". The read-out is the last place that can refuse the weld, and geometry says
        // so without re-deciding the lines.
        //
        // Left-to-right, space-parted scripts only: "a run always advances" is a statement about them,
        // and in Hebrew the second run of a single word legitimately starts left of the first — the rule
        // would split every RTL word it met (JxText.isLtrWordScript).
        //
        // The comparison carries a TOLERANCE, and it is not decoration: a stored page rounds coordinates to
        // export precision, so a strict test flips across a round-trip and the same document reads two ways
        // (measured: pdf→doctags stopped matching pdf→ocd→doctags). The margin is far below any real advance
        // and far above the rounding.
        if (prev != null && b.length() > 0 && x != null && !x.isEmpty()
                && !Character.isWhitespace(b.charAt(b.length() - 1)) && !Character.isWhitespace(x.charAt(0))
                && JxText.isLtrWordScript(x) && JxText.isLtrWordScript(prev.text())
                && cur.bounds().minX() <= prev.bounds().minX() + LINE_BACK_EPS) {
            b.append(' ');
        }
        b.append(x);
    }

    /** The document's own words, as it writes them on one line — the self-reference that tells a compound
     *  broken at its hyphen ({@code allers-} / {@code retours}) from a word hyphenated at a syllable
     *  ({@code préven-} / {@code tion}). No lexicon: the hyphen is kept when the document writes
     *  {@code allers-retours} somewhere and never {@code allersretours}; when it says neither, the word is
     *  rejoined, as poppler does — a syllable break is by far the common case. */
    private static final class Vocabulary {
        private final java.util.Set<String> whole = new java.util.HashSet<>(), compounds = new java.util.HashSet<>();

        /** Every word of one run: letters and digits, with the hyphens inside it — a word the run ENDS on with a
         *  hyphen is a break in progress and says nothing. */
        void add(String text) {
            if (text == null) return;
            int n = text.length();
            for (int i = 0; i < n; ) {
                if (!Character.isLetterOrDigit(text.charAt(i))) { i++; continue; }
                int j = i;
                while (j < n && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '-')) j++;
                int end = j;
                while (end > i && text.charAt(end - 1) == '-') end--;
                if (end == j) {
                    String w = text.substring(i, end).toLowerCase(java.util.Locale.ROOT);
                    (w.indexOf('-') >= 0 ? compounds : whole).add(w);
                }
                i = j;
            }
        }

        /** Whether the hyphen ending {@code b} joins two halves of a compound the document writes whole. */
        boolean compound(StringBuilder b, String next) {
            int i = b.length() - 1;                                    // the hyphen
            while (i > 0 && Character.isLetterOrDigit(b.charAt(i - 1))) i--;
            String left = b.substring(i, b.length() - 1);
            String s = next.stripLeading();
            int j = 0;
            while (j < s.length() && Character.isLetterOrDigit(s.charAt(j))) j++;
            String right = s.substring(0, j);
            if (left.isEmpty() || right.isEmpty()) return false;
            String l = left.toLowerCase(java.util.Locale.ROOT), r = right.toLowerCase(java.util.Locale.ROOT);
            return compounds.contains(l + "-" + r) && !whole.contains(l + r);
        }
    }

    /** Slack on the "reading went back to a margin" test — above the export rounding, below any advance. */
    private static final double LINE_BACK_EPS = 0.01;

    /** A trailing {@code '-'} is a line-break (soft) hyphen, not a compound hyphen, when the next run
     *  begins a lower line with a lowercase letter — i.e. the word continues on the following line. The
     *  rare compound that breaks exactly at its hyphen ({@code assurance-\naccidents}) is merged, as in
     *  poppler; the lowercase-continuation guard keeps proper nouns and new clauses intact. */
    private static boolean softHyphen(OCDText prev, OCDText cur, String x) {
        String s = x.stripLeading();
        if (s.isEmpty() || !Character.isLetter(s.charAt(0)) || !Character.isLowerCase(s.charAt(0))) return false;
        JxRect a = prev.bounds(), c = cur.bounds();
        if (a.isEmpty() || c.isEmpty()) return false;
        double size = Math.max(prev.fontSize(), cur.fontSize());
        if (size <= 0) return false;
        double drop = (a.y() + a.height() / 2) - (c.y() + c.height() / 2);   // > 0 → cur sits on a lower line
        return drop > 0.5 * size;
    }

    /** The concatenated text under {@code n} (see {@link #appendText}), normalized for export. */
    public static String text(OCDNode n) {
        StringBuilder b = new StringBuilder();
        appendText(b, n);
        return nfkc(b.toString());
    }

    /** NFKC at export: fold ligatures (U+FB01 &#xFB01; &rarr; "fi"), full-width and other compatibility forms
     *  to their canonical characters, so reflow projections (Markdown / DocTags / HTML / EPUB-reflow) and the
     *  search index stay searchable. The model and the OCD-EPUB keep the source codepoints (fidelity);
     *  SVG/PDF paint by glyph id and are unaffected. This is the export-time successor to the old Canonicalizer. */
    private static String nfkc(String s) {
        return s == null || s.isEmpty() ? "" : Normalizer.normalize(s, Normalizer.Form.NFKC);
    }

    /** Canonical text of a structure node across all pages. */
    public String text(OCDStructNode s) { return text(s, -1); }

    /** A run tagged with the page it was referenced from — node ids are page-scoped, so reading order
     *  is restored by sorting on {@code (page, position in the content)} across own + inline-descendant refs. */
    private record Run(int page, OCDText text) {}

    /** Canonical text of a structure node: its referenced content (restricted to {@code page}
     *  when {@code page >= 0}), or — for a node with no resolvable refs (e.g. a cell built from a
     *  straddling run) — its denormalized {@link OCDStructNode#text()}. The one resolver all writers share. */
    public String text(OCDStructNode s, int page) {
        List<Run> runs = new ArrayList<>();
        collect(s, page, runs);                                   // own refs + inline-descendant refs (SPAN/CODE/OTHER)
        // Reading order is the content's order, which the analysis set (XYCut) — never the ids, which a
        // restructure leaves behind and which made a second, hidden reading-order authority.
        runs.sort(java.util.Comparator.comparingInt(Run::page).thenComparingInt(r -> position.getOrDefault(r.text(), Integer.MAX_VALUE)));
        StringBuilder b = new StringBuilder();
        OCDText prev = null;
        for (Run rr : runs) {                                     // assemble across refs so a word split over runs stays whole
            OCDText t = rr.text();
            if (t.text() != null && !t.text().isEmpty()) { join(b, t, prev, vocabulary); prev = t; }
        }
        String tt = b.toString().strip();
        return nfkc(!tt.isEmpty() ? tt : (s.text() == null ? "" : s.text().strip()));
    }

    /** Gather the text runs of {@code s}: its own refs plus those of its inline descendants
     *  ({@link OCDStructNode#isInline}). Order is not preserved here — {@link #text} re-sorts into reading order. */
    private void collect(OCDStructNode s, int page, List<Run> out) {
        for (OCDStructNode.Ref r : s.refs())
            if (page < 0 || r.page() == page) {
                OCDNode n = node(r.page(), r.nodeId());
                if (n != null) n.stream().filter(OCDText.class::isInstance).map(OCDText.class::cast)
                                .forEach(t -> out.add(new Run(r.page(), t)));
            }
        for (OCDStructNode c : s.children())
            if (c.isInline()) collect(c, page, out);              // recurse inline emphasis; stop at block children
    }
}
