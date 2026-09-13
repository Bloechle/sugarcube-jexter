package sugarcube.jexter.ocd.model;

import sugarcube.jexter.core.JxLog;
import sugarcube.jexter.core.JxRect;

import java.util.ArrayList;
import java.util.List;

/**
 * The page-set authority: reorder, keep, drop, repeat and insert pages — and, in the same breath, fix
 * up everything that pointed at a page by its POSITION.
 *
 * <p>Three reference kinds are stored as page indices, and a page operation invalidates all three at
 * once: {@link OCDStructNode.Ref#page()}, {@link OCDOutline#pageIndex()} and {@link OCDLink#pageIndex()}.
 * Doing the surgery without them is silent: measured on a 6-page document, deleting page 3 and moving
 * the last page to the front left a bookmark and an internal link pointing one past the end and three
 * structure references on the wrong page — and both writers emitted that document without a word
 * ({@code "page": "p6"} and {@code <a href="page-006.xhtml">} in a five-page container). Hence ONE
 * class: four features each rebuilding the table is four chances to forget a reference kind.
 *
 * <p>What the model already gives for free, and what it does not:
 * <ul>
 *   <li>node ids are <b>page-local by contract</b> ({@link IdStamper}), so nothing is re-stamped:
 *       {@code (page, nodeId)} stays unique as long as the pages are distinct;</li>
 *   <li>fonts, images and layers are <b>document-scoped</b>, so any page of THIS document may be
 *       reordered or repeated freely — merging two documents is the case that needs those three maps
 *       prefixed, and it is deliberately not here;</li>
 *   <li>a reference to a page that is no longer in the document is <b>dropped and reported</b>, the
 *       way the reader already treats an unresolvable reference. A bookmark keeps its title and loses
 *       its destination rather than vanishing: a title is content, and losing one silently is worse
 *       than a dead entry.</li>
 * </ul>
 */
public final class Pages {

    private Pages() {}

    /** A page taken from the source document (0-based), or a blank one to insert. */
    public record Slot(int from, JxRect blank) {
        public static Slot of(int index)      { return new Slot(index, null); }
        public static Slot blank(JxRect box)  { return new Slot(-1, box); }
        public boolean isBlank()              { return from < 0; }
    }

    /**
     * Rebuild the document's page list from {@code slots} — the RESULT, stated: omission deletes,
     * order reorders, repetition duplicates, a blank slot inserts. Every page reference is remapped
     * afterwards; references to a dropped page are removed and counted.
     *
     * <p>A repeated page is a real {@link OCDPage#copy(String) copy} from its second occurrence on —
     * an alias would be one object in two places, so editing page 4 afterwards would silently change
     * page 9 too. The copy shares the document's resources (fonts, image bytes, media, layers), which
     * is what the resource tables are for, and keeps its node ids: they are page-local by contract, so
     * every structure reference into the page stays valid. Those references name a POSITION, so they
     * resolve to the FIRST occurrence — the only answer a positional reference can give when one page
     * is now two.
     */
    public static void apply(OCDDocument doc, List<Slot> slots) {
        if (doc == null || slots == null || slots.isEmpty()) return;
        List<OCDPage> src = new ArrayList<>(doc.pages());
        List<OCDPage> out = new ArrayList<>(slots.size());
        int[] first = new int[src.size()];                 // old index → new index (first occurrence)
        java.util.Arrays.fill(first, -1);

        for (Slot s : slots) {
            if (s.isBlank()) {
                JxRect b = s.blank() != null ? s.blank()
                         : (src.isEmpty() ? new JxRect(0, 0, 595.276, 841.89) : src.get(0).mediaBox());
                out.add(new OCDPage("p" + (out.size() + 1), b));
                continue;
            }
            if (s.from() < 0 || s.from() >= src.size())
                throw new IllegalArgumentException("no page " + (s.from() + 1) + " (document has " + src.size() + ")");
            OCDPage page = src.get(s.from());
            if (first[s.from()] < 0) { first[s.from()] = out.size(); out.add(page); }
            else out.add(page.copy("p" + (out.size() + 1)));      // a repeat is a COPY, never an alias
        }

        doc.pages().clear();
        doc.pages().addAll(out);
        int dropped = remap(doc, first);
        for (OCDStructure st : doc.structures()) resort(st.root());
        if (dropped > 0)
            JxLog.warn(Pages.class, dropped + " reference(s) pointed at a page the page set no longer holds — dropped");
    }

    /** Parse a 1-based page spec — {@code "1,4-6,2,+"}: ranges, repetition, {@code +} for a blank
     *  page. Open ranges are accepted ({@code "3-"} = to the end), and a descending one reverses
     *  ({@code "6-1"}). The spec states the RESULT, so it needs no order of operations. */
    public static List<Slot> parse(String spec, int pageCount) {
        List<Slot> out = new ArrayList<>();
        if (spec == null || spec.isBlank()) return out;
        for (String raw : spec.split(",")) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            if (t.equals("+")) { out.add(Slot.blank(null)); continue; }
            int dash = t.indexOf('-', 1);
            if (dash < 0) { out.add(Slot.of(one(t, pageCount) - 1)); continue; }
            String a = t.substring(0, dash).trim(), b = t.substring(dash + 1).trim();
            int lo = one(a, pageCount), hi = b.isEmpty() ? pageCount : one(b, pageCount);
            if (lo <= hi) for (int i = lo; i <= hi; i++) out.add(Slot.of(i - 1));
            else          for (int i = lo; i >= hi; i--) out.add(Slot.of(i - 1));
        }
        return out;
    }

    private static int one(String s, int pageCount) {
        int n;
        try { n = Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("page spec: '" + s + "' is not a page number"); }
        if (n < 1 || n > pageCount)
            throw new IllegalArgumentException("page spec: no page " + n + " (document has " + pageCount + ")");
        return n;
    }

    /** After a reorder the logical tree still holds the OLD order, and the projections that read it —
     *  Markdown, DocTags, HTML, the reflowable EPUB — would export a document in an order the pages no
     *  longer have. So every level is re-sorted by the page its subtree starts on, STABLY: same page,
     *  same relative order as before, which is what keeps a page's own blocks in reading order. A page
     *  that is now in the document twice keeps ONE logical subtree — a structure node points at a node
     *  id on a page, and two occurrences of one page cannot be told apart by such a reference. */
    private static void resort(OCDStructNode s) {
        if (s == null || s.children().isEmpty()) return;
        for (OCDStructNode k : s.children()) resort(k);
        List<OCDStructNode> kids = new ArrayList<>(s.children());
        kids.sort(java.util.Comparator.comparingInt(Pages::firstPage));
        s.children().clear();
        s.children().addAll(kids);
    }

    /** The page a subtree starts on — {@link Integer#MAX_VALUE} when it references none, so an
     *  unanchored node keeps its place at the end rather than jumping to the front. */
    private static int firstPage(OCDStructNode s) {
        int min = Integer.MAX_VALUE;
        for (OCDStructNode.Ref r : s.refs()) min = Math.min(min, r.page());
        for (OCDStructNode k : s.children()) min = Math.min(min, firstPage(k));
        return min;
    }

    // ── the fix-up, in ONE place ────────────────────────────────────────────────

    /** Apply an old→new page table to every reference the model holds by position. Returns how many
     *  were dropped for naming a page that is gone. */
    private static int remap(OCDDocument doc, int[] oldToNew) {
        int[] dropped = { 0 };
        for (OCDStructure st : doc.structures()) remapStruct(st.root(), oldToNew, dropped);
        for (OCDOutline o : doc.outline()) remapOutline(o, oldToNew, dropped);
        for (OCDPage p : doc.pages())
            p.links().removeIf(l -> {
                if (l.isExternal() || !l.hasDestination()) return false;
                int to = at(oldToNew, l.pageIndex());
                if (to < 0) { dropped[0]++; return true; }
                l.pageIndex(to);
                return false;
            });
        return dropped[0];
    }

    private static void remapStruct(OCDStructNode s, int[] table, int[] dropped) {
        if (s == null) return;
        List<OCDStructNode.Ref> refs = new ArrayList<>(s.refs());
        s.refs().clear();
        for (OCDStructNode.Ref r : refs) {
            int to = at(table, r.page());
            if (to < 0) dropped[0]++; else s.addRef(to, r.nodeId());
        }
        for (OCDStructNode k : s.children()) remapStruct(k, table, dropped);
    }

    /** A bookmark whose page is gone keeps its TITLE and loses its destination; its children are
     *  remapped as usual, so a chapter does not disappear because one of its pages did. */
    private static void remapOutline(OCDOutline o, int[] table, int[] dropped) {
        if (o.hasDestination()) {
            int to = at(table, o.pageIndex());
            if (to < 0) { dropped[0]++; o.pageIndex(-1); o.y(Double.NaN); } else o.pageIndex(to);
        }
        for (OCDOutline k : o.children()) remapOutline(k, table, dropped);
    }

    /** A link's own page, in a repeated page, resolves to the FIRST occurrence — the only answer a
     *  positional reference can give when one page is now two. */
    private static int at(int[] table, int oldIndex) {
        return (oldIndex >= 0 && oldIndex < table.length) ? table[oldIndex] : -1;
    }
}
