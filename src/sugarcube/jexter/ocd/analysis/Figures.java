package sugarcube.jexter.ocd.analysis;

import sugarcube.jexter.core.JxClock;
import sugarcube.jexter.core.JxRect;
import sugarcube.jexter.ocd.model.OCDBreak;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDGraphic;
import sugarcube.jexter.ocd.model.OCDGroup;
import sugarcube.jexter.ocd.model.OCDImage;
import sugarcube.jexter.ocd.model.OCDNode;
import sugarcube.jexter.ocd.model.OCDPage;
import sugarcube.jexter.ocd.model.OCDParagraph;
import sugarcube.jexter.ocd.model.OCDStructNode;
import sugarcube.jexter.ocd.model.OCDStructure;
import sugarcube.jexter.ocd.model.OCDText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pictures of the page body, placed in the logical tree: every image and every vector graphic
 * ({@link OCDGraphic}) a reader sees in the text becomes a {@link OCDStructNode.Type#FIGURE}, at its
 * place in the reading order — so a reflowable book, HTML, Markdown and DocTags show what the page shows.
 *
 * <p><b>Detect the obvious.</b> A picture on the page is a fact, not a guess: this pass decides nothing
 * about captions, tables or what a figure means (still {@link Refiner}'s). It only keeps out what a reader
 * of a book must not see: a picture under the text (a scan behind its OCR words, a full-bleed background),
 * one repeated on most pages (a logo, a running decoration), page furniture, a layer switched off, and
 * specks too small to be a picture. A picture painted {@value #REPEATS} times or more on ONE page — the same
 * bytes, a wallpaper tiled over the page — is shown once, where it is first read. Headings and paragraphs are never re-decided: the builders own them.
 *
 * <p><b>One figure is one zone free of body copy</b> — the XY-Cut idea run bottom-up. A drawing reaches
 * this pass in pieces: a chart's bars, axes and legend, a diagram's boxes and the arrows between them, its
 * labels as text in between (33 pieces for one chart, measured). Pieces grow together while their ink is
 * close and the box they would make holds no BODY text; the lines and specks that bind a drawing (an
 * arrow, an axis, a small icon) join a figure but never make one; a label touching two pieces binds them
 * (touching one, it may be a caption: it stays text); last, pieces whose joint box holds no body text are
 * one drawing. Body text is long text set on paper — code set on coloured chips is a drawing's label —
 * or a column of copy, which stays text whatever lies under it (a magazine sets its articles over
 * photographs and decorations; read as labels, whole columns left the text). Text set larger than the
 * body is a title, never a label. A picture holding body copy, or a heading and nothing else, is that
 * text's frame, not a figure. All of it is INK ({@link OCDNode#ink}): a white canvas wider than the
 * drawing once carried a figure across the next column. The short text over the region is its labels —
 * drawn with it, out of the text flow, where it was noise between the paragraphs. Body text never leaves
 * the flow.
 *
 * <p><b>Where it goes.</b> The content's order is the reading order ({@link XYCut}); a figure takes the
 * place of the first thing it holds, or follows the text block read just before it.
 *
 * <p>The {@code outline} and {@code heuristic} structures receive figures; a tagged PDF's own structure
 * ({@code pdf}) is left as its author tagged it — an untagged picture there is an artifact by PDF/UA.
 * A document with no structure at all but pictures gets a {@code heuristic} one holding them. Idempotent:
 * a picture a FIGURE already references is never placed twice.
 */
public final class Figures {

    private Figures() {}

    static final double MIN_SIDE   = 24;     // page units: a smaller picture is a speck, a bullet or an icon
    static final double BACKGROUND = 0.5;    // × the page area: a picture this large with text over it is a background
    static final int    REPEATS    = 3;      // pages: a picture repeated on at least this many (and half of them) is decoration
    static final double GAP        = 12;     // page units: ink this close belongs to one drawing
    static final double HUG        = 3;      // page units: a label overlaps its drawing's ink by more, a caption sits beside it
    static final int    LABEL      = 100;    // characters: shorter text inside a drawing is a label, longer is body text
    static final int    COLUMN     = 4;      // lines: a block this tall is a column of copy, body text even over a picture
    static final int    GLUE_MAX   = 3000;   // binding pieces per page past which only the pictures themselves count

    /** A thing on the page in reading order: a block of text, or a picture. */
    private record Token(int page, OCDNode node, boolean picture) {}

    /** Place the body's pictures as FIGUREs in the document's non-tagged structures. */
    public static void place(OCDDocument doc) {
        List<Token> tokens = new ArrayList<>();
        Map<String, Integer> where = new HashMap<>();          // page + node id → token index
        Map<String, Integer> repeats = repeats(doc);
        for (int pi = 0; pi < doc.pageCount(); pi++)
            walk(doc, pi, doc.page(pi).content(), tokens, where, repeats, copies(doc, doc.page(pi)), new java.util.HashSet<>());
        if (tokens.stream().noneMatch(Token::picture)) return;

        boolean placed = false;
        for (OCDStructure st : doc.structures())
            if (("outline".equals(st.id()) || "heuristic".equals(st.id())) && st.root() != null) { place(doc, st.root(), tokens, where); placed = true; }
        if (!placed && doc.structureById("pdf") == null) {        // pictures and no text: a structure of pictures
            OCDStructNode root = new OCDStructNode(OCDStructNode.Type.DOCUMENT);
            place(doc, root, tokens, where);
            doc.addStructure(new OCDStructure("heuristic", "Heuristic", OCDStructure.Source.HEURISTIC)
                    .by("Figures").at(JxClock.millis()).how("the page's pictures, in reading order").root(root));
        }
    }

    // ── what the page shows ──────────────────────────────────────────────────

    /** The page's text blocks and pictures in reading order. A paragraph or a graphic is one token, and
     *  every id inside it (runs, nested paragraphs) points at that token. */
    private static void walk(OCDDocument doc, int page, List<OCDNode> nodes, List<Token> tokens,
                             Map<String, Integer> where, Map<String, Integer> repeats, Map<String, Integer> copies, java.util.Set<String> once) {
        OCDPage p = doc.page(page);
        for (OCDNode n : nodes) {
            if (doc.hides(n)) continue;
            if (n instanceof OCDParagraph || n instanceof OCDText) add(page, n, false, tokens, where);
            else if (n instanceof OCDGraphic g) { if (shows(p, g, repeats)) add(page, g, true, tokens, where); }
            else if (n instanceof OCDImage im) {
                String c = content(doc, im);
                boolean tiled = copies.getOrDefault(c, 0) >= REPEATS;
                if (shows(p, im, repeats) && (!tiled || once.add(c))) add(page, im, true, tokens, where);
            }
            else if (n instanceof OCDGroup g) walk(doc, page, g.children(), tokens, where, repeats, copies, once);   // a wrapper: what it holds
        }
    }

    /** How many times the page paints each picture, by its bytes: a designer's file placed again and again
     *  is embedded again and again under fresh names. */
    private static Map<String, Integer> copies(OCDDocument doc, OCDPage page) {
        Map<String, Integer> n = new HashMap<>();
        page.nodes().filter(OCDImage.class::isInstance).forEach(im -> n.merge(content(doc, (OCDImage) im), 1, Integer::sum));
        return n;
    }

    /** A picture's identity by its bytes (their length and hash), or its resource when it has none. */
    private static String content(OCDDocument doc, OCDImage im) {
        byte[] b = im.resourceRef() == null ? null : doc.image(im.resourceRef());
        return b == null ? "ref:" + im.resourceRef() : b.length + ":" + java.util.Arrays.hashCode(b);
    }

    private static void add(int page, OCDNode n, boolean picture, List<Token> tokens, Map<String, Integer> where) {
        int at = tokens.size();
        tokens.add(new Token(page, n, picture));
        n.stream().forEach(d -> { if (d.id() != null) where.put(key(page, d.id()), at); });
    }

    /** A picture a reader of the book should see: big enough, not repeated page after page, not under text. */
    private static boolean shows(OCDPage page, OCDNode pic, Map<String, Integer> repeats) {
        JxRect b = pic.ink();
        if (b.isEmpty() || Math.max(b.width(), b.height()) < MIN_SIDE || Furniture.isRunning(pic)) return false;
        if (repeats.getOrDefault(signature(pic), 0) > 0) return false;
        JxRect pageBox = page.effectiveBox();
        if (pic instanceof OCDImage && b.width() * b.height() >= BACKGROUND * pageBox.width() * pageBox.height())
            return page.texts().noneMatch(t -> t.z() > pic.z() && b.contains(t.bounds()));   // the scan under its words
        return true;
    }

    /** Pictures repeated on most pages (same kind, same place, same size): signature → 1 when decoration. */
    private static Map<String, Integer> repeats(OCDDocument doc) {
        Map<String, Integer> pages = new HashMap<>();
        for (OCDPage p : doc.pages()) {
            Map<String, Boolean> seen = new HashMap<>();
            p.nodes().filter(n -> n instanceof OCDImage || n instanceof OCDGraphic)
             .forEach(n -> seen.putIfAbsent(signature(n), true));
            seen.keySet().forEach(s -> pages.merge(s, 1, Integer::sum));
        }
        int floor = Math.max(REPEATS, (doc.pageCount() + 1) / 2);
        Map<String, Integer> decoration = new HashMap<>();
        pages.forEach((s, n) -> { if (n >= floor) decoration.put(s, 1); });
        return decoration;
    }

    private static String signature(OCDNode n) {
        JxRect b = n.bounds();
        String what = n instanceof OCDImage im ? "i" + im.resourceRef() : "g";
        return what + "@" + Math.round(b.x() / 2) + "," + Math.round(b.y() / 2) + "," + Math.round(b.width() / 2) + "," + Math.round(b.height() / 2);
    }

    // ── into the tree ────────────────────────────────────────────────────────

    /** A struct node, where it hangs, the first token it reads and every token it reads. */
    private static final class Entry {
        final OCDStructNode node; final OCDStructNode parent; final int at; final java.util.Set<Integer> reads;
        Entry(OCDStructNode node, OCDStructNode parent, int at, java.util.Set<Integer> reads) { this.node = node; this.parent = parent; this.at = at; this.reads = reads; }
    }

    /** Everything the node reads lies in the region: a paragraph only half inside a figure stays text. */
    private static boolean within(Entry e, java.util.Set<Integer> region) { return region.containsAll(e.reads); }

    private static void place(OCDDocument doc, OCDStructNode root, List<Token> tokens, Map<String, Integer> where) {
        List<Entry> entries = new ArrayList<>();
        Map<OCDNode, Boolean> present = new IdentityHashMap<>();
        index(root, null, entries, tokens, where, present);
        Map<OCDStructNode, Integer> leading = new IdentityHashMap<>();   // figures already put first in a container
        java.util.Set<Integer> headings = new java.util.HashSet<>();     // what the structure reads as a heading
        for (Entry e : entries) if (e.node.type() == OCDStructNode.Type.HEADING) headings.addAll(e.reads);
        for (Region r : regions(doc, tokens, present, headings, FontProfile.of(doc))) {
            java.util.SortedSet<Integer> region = r.tokens();
            int at = region.first();                                     // the first thing it holds
            int page = tokens.get(at).page();
            OCDStructNode fig = new OCDStructNode(OCDStructNode.Type.FIGURE);
            for (int i : region) fig.addRef(page, tokens.get(i).node().id());
            for (OCDNode g : r.reach()) fig.addRef(page, g.id());
            Entry made = absorb(fig, region, entries);
            if (made == null) made = after(fig, at, entries, root, leading);
            entries.add(made);
        }
    }

    /** One figure: its pictures and labels (token indices, sorted), and the binding pieces that reach past
     *  them (an axis, a frame) — named too, so a writer draws the region the figure really covers. */
    private record Region(java.util.SortedSet<Integer> tokens, List<OCDNode> reach) {}

    /** The figures, page by page, in reading order of their first token. */
    private static List<Region> regions(OCDDocument doc, List<Token> tokens, Map<OCDNode, Boolean> present, java.util.Set<Integer> headings,
                                        FontProfile profile) {
        List<Region> out = new ArrayList<>();
        Map<Integer, List<Integer>> seeds = new java.util.TreeMap<>();
        for (int i = 0; i < tokens.size(); i++)
            if (tokens.get(i).picture() && !present.containsKey(tokens.get(i).node()))
                seeds.computeIfAbsent(tokens.get(i).page(), k -> new ArrayList<>()).add(i);
        for (var e : seeds.entrySet()) {
            int page = e.getKey();
            List<OCDNode> glue = glue(doc, page, tokens);
            List<JxRect> drawn = new ArrayList<>();                       // all the page draws: pictures and loose pieces
            for (int i : e.getValue()) drawn.add(tokens.get(i).node().ink());
            for (OCDNode g : glue) drawn.add(g.ink());
            List<JxRect> body = new ArrayList<>(), heads = new ArrayList<>();
            List<Integer> labels = new ArrayList<>();
            for (int i = 0; i < tokens.size(); i++) {
                Token t = tokens.get(i);
                if (t.picture() || t.page() != page) continue;
                JxRect r = t.node().ink();
                if (isCopy(t.node()) || isBody(t.node()) && !onDrawing(t.node(), drawn)) body.add(r);
                else if (headings.contains(i) || t.node().hasRole() && t.node().role().startsWith("heading-") || display(t.node(), profile, doc)) heads.add(r);
                else labels.add(i);
            }
            List<java.util.SortedSet<Integer>> groups = new ArrayList<>();
            List<List<OCDNode>> bound = new ArrayList<>();
            List<JxRect> boxes = new ArrayList<>();
            for (int i : e.getValue()) {
                JxRect ink = tokens.get(i).node().ink();                    // behind body copy, or a heading alone: a frame
                if (frames(ink, body) || frames(ink, heads) && !holdsLabel(ink, labels, tokens)) continue;
                groups.add(new java.util.TreeSet<>(List.of(i))); bound.add(new ArrayList<>()); boxes.add(ink);
            }
            for (boolean grew = true; grew; ) {                            // grow until nothing near can join
                grew = false;
                for (int a = 0; a < groups.size(); a++) {
                    for (int b = groups.size() - 1; b > a; b--)
                        if (joins(boxes.get(a), boxes.get(b), body)) { merge(groups, bound, boxes, a, b); grew = true; }
                    for (var it = glue.iterator(); it.hasNext(); ) {
                        OCDNode g = it.next();
                        if (joins(boxes.get(a), g.ink(), body)) { boxes.set(a, boxes.get(a).union(g.ink())); bound.get(a).add(g); it.remove(); grew = true; }
                    }
                }
                for (var it = labels.iterator(); it.hasNext(); ) {         // a label touching two pieces binds them;
                    int i = it.next();                                      // touching one, it may be its caption: it stays
                    JxRect r = tokens.get(i).node().ink();
                    List<Integer> near = new ArrayList<>();
                    for (int k = 0; k < groups.size(); k++) if (near(boxes.get(k), r)) near.add(k);
                    if (near.size() < 2) continue;
                    JxRect u = r;
                    for (int k : near) u = u.union(boxes.get(k));
                    if (!clear(u, body)) continue;
                    for (int x = near.size() - 1; x > 0; x--) merge(groups, bound, boxes, near.get(0), near.get(x));
                    groups.get(near.get(0)).add(i); boxes.set(near.get(0), boxes.get(near.get(0)).union(r));
                    it.remove(); grew = true;
                }
            }
            for (boolean grew = true; grew; ) {                            // then the zones free of body copy: pieces
                grew = false;                                               // with no body text between them are one drawing
                for (int a = 0; a < groups.size() && !grew; a++)
                    for (int b = groups.size() - 1; b > a; b--)
                        if (clear(boxes.get(a).union(boxes.get(b)), body)) { merge(groups, bound, boxes, a, b); grew = true; break; }
            }
            for (int k = 0; k < groups.size(); k++)                       // the short text over a region is its labels
                for (int i : labels)
                    if (!boxes.get(k).intersection(tokens.get(i).node().ink().inflate(-HUG, -HUG)).isEmpty()) groups.get(k).add(i);
            for (int k = 0; k < groups.size(); k++) {
                JxRect own = JxRect.EMPTY;
                for (int i : groups.get(k)) own = own.union(tokens.get(i).node().ink());
                List<OCDNode> reach = new ArrayList<>();
                for (OCDNode g : bound.get(k)) if (!within(own, g.ink(), 0)) { reach.add(g); own = own.union(g.ink()); }
                out.add(new Region(groups.get(k), reach));
            }
        }
        out.sort(java.util.Comparator.comparingInt(r -> r.tokens().first()));
        return out;
    }

    /** Two pieces of one drawing: their ink is close, and no body text lies in the box they would make. */
    private static boolean joins(JxRect a, JxRect b, List<JxRect> body) { return near(a, b) && clear(a.union(b), body); }

    private static boolean clear(JxRect box, List<JxRect> body) {
        for (JxRect t : body) if (!box.intersection(t).isEmpty()) return false;
        return true;
    }

    /** Group {@code b} into group {@code a} (a < b). */
    private static void merge(List<java.util.SortedSet<Integer>> groups, List<List<OCDNode>> bound, List<JxRect> boxes, int a, int b) {
        groups.get(a).addAll(groups.remove(b)); bound.get(a).addAll(bound.remove(b)); boxes.set(a, boxes.get(a).union(boxes.remove(b)));
    }

    /** What binds a drawing without being a picture of its own: a loose path, a picture too small to stand. */
    private static List<OCDNode> glue(OCDDocument doc, int page, List<Token> tokens) {
        java.util.Set<OCDNode> pictures = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Token t : tokens) if (t.picture() && t.page() == page) t.node().stream().forEach(pictures::add);
        List<OCDNode> out = new ArrayList<>();
        doc.page(page).nodes().filter(n -> (n instanceof sugarcube.jexter.ocd.model.OCDPath || n instanceof OCDImage) && !pictures.contains(n))
           .filter(n -> n.id() != null && !Furniture.isRunning(n) && !n.ink().isEmpty()).limit(GLUE_MAX).forEach(out::add);
        return out;
    }

    private static boolean near(JxRect a, JxRect b) {
        return a.x() - GAP <= b.right() && b.x() - GAP <= a.right() && a.y() - GAP <= b.maxY() && b.y() - GAP <= a.maxY();
    }

    private static boolean within(JxRect region, JxRect r, double tolerance) {
        return !r.isEmpty() && r.x() >= region.x() - tolerance && r.y() >= region.y() - tolerance
            && r.right() <= region.right() + tolerance && r.maxY() <= region.maxY() + tolerance;
    }

    private static boolean holdsLabel(JxRect picture, List<Integer> labels, List<Token> tokens) {
        for (int i : labels) if (within(picture, tokens.get(i).node().ink(), 0)) return true;
        return false;
    }

    /** A picture holding one of {@code texts} whole is no figure: it is that text's frame or background. */
    private static boolean frames(JxRect picture, List<JxRect> texts) {
        for (JxRect t : texts) if (within(picture, t, 0)) return true;
        return false;
    }

    /** Text set on the drawing rather than on paper: at least half of what its lines span lies over ink that
     *  does not hold the whole text (a panel behind a paragraph holds it whole — that text is on paper, framed). */
    private static boolean onDrawing(OCDNode text, List<JxRect> drawn) {
        JxRect whole = text.ink();
        List<JxRect> under = new ArrayList<>();
        for (JxRect d : drawn) if (!within(d, whole, 0) && !d.intersection(whole).isEmpty()) under.add(d);
        if (under.isEmpty()) return false;
        int[] on = new int[2];                                    // samples on ink, samples
        text.stream().filter(n -> n instanceof sugarcube.jexter.ocd.model.OCDText).map(OCDNode::ink).forEach(r -> {
            for (int x = 0; x < 8; x++, on[1]++) {
                double px = r.x() + (x + 0.5) * r.width() / 8, py = r.y() + r.height() / 2;
                for (JxRect d : under) if (px >= d.x() && px <= d.right() && py >= d.y() && py <= d.maxY()) { on[0]++; break; }
            }
        });
        return on[1] > 0 && 2 * on[0] >= on[1];
    }

    /** Text that reads as text: a paragraph of body copy, never a drawing's label. */
    private static boolean isBody(OCDNode text) { return sugarcube.jexter.ocd.model.OCDIndex.text(text).length() >= LABEL; }

    /** Text set larger than the body: a title over a picture, never its label — a drawing labels itself at
     *  most at the size of the text around it. Unrecognised as a heading, an article's title set over its
     *  opening illustration left every text target as one of its labels. */
    private static boolean display(OCDNode text, FontProfile profile, OCDDocument doc) {
        List<OCDText> runs = text.stream().filter(n -> n instanceof OCDText).map(n -> (OCDText) n).toList();
        return !runs.isEmpty() && profile.isLarger(FontProfile.styleOfLine(runs, doc));
    }

    /** A column of copy: body text of {@value #COLUMN} lines or more — what no drawing labels itself with, so it
     *  stays text whatever lies under it. {@link GraphicClusterer} asks the same: a drawing never engulfs one. */
    static boolean isCopy(OCDNode text) {
        return isBody(text) && text.stream().filter(n -> n instanceof OCDBreak).count() + 1 >= COLUMN;
    }

    /** The paragraphs a figure holds are its own: the first one's place becomes the figure, the others go. */
    private static Entry absorb(OCDStructNode fig, java.util.Set<Integer> region, List<Entry> entries) {
        Entry first = null;
        for (Entry e : List.copyOf(entries)) {
            if (!region.contains(e.at) || !within(e, region) || e.node.type() != OCDStructNode.Type.PARAGRAPH || !e.node.children().isEmpty()) continue;
            List<OCDStructNode> kids = e.parent.children();
            if (first == null) { kids.set(kids.indexOf(e.node), fig); first = new Entry(fig, e.parent, region.iterator().next(), region); }
            else kids.remove(e.node);
            entries.remove(e);
        }
        return first;
    }

    /** After the block read just before the picture: inside a heading's section, first; else its next sibling. */
    private static Entry after(OCDStructNode fig, int at, List<Entry> entries, OCDStructNode root,
                               Map<OCDStructNode, Integer> leading) {
        Entry anchor = null;
        for (Entry e : entries) if (e.at < at && (anchor == null || e.at >= anchor.at)) anchor = e;
        if (anchor == null || anchor.node.type() == OCDStructNode.Type.HEADING) {
            OCDStructNode box = anchor == null ? root : anchor.node;
            int i = leading.merge(box, 1, Integer::sum) - 1;
            box.children().add(Math.min(i, box.children().size()), fig);
            return new Entry(fig, box, at, java.util.Set.of(at));
        }
        List<OCDStructNode> kids = anchor.parent.children();
        kids.add(kids.indexOf(anchor.node) + 1, fig);
        return new Entry(fig, anchor.parent, at, java.util.Set.of(at));
    }

    /** Every struct node with content, at the first token it reads; the pictures already referenced. */
    private static void index(OCDStructNode n, OCDStructNode parent, List<Entry> out, List<Token> tokens,
                              Map<String, Integer> where, Map<OCDNode, Boolean> present) {
        int first = Integer.MAX_VALUE;
        java.util.Set<Integer> reads = new java.util.HashSet<>();
        for (OCDStructNode.Ref r : n.refs()) {
            Integer t = where.get(key(r.page(), r.nodeId()));
            if (t == null) continue;
            first = Math.min(first, t);
            reads.add(t);
            if (n.type() == OCDStructNode.Type.FIGURE && tokens.get(t).picture()) present.put(tokens.get(t).node(), true);
        }
        if (first != Integer.MAX_VALUE && parent != null) out.add(new Entry(n, parent, first, reads));
        for (OCDStructNode c : List.copyOf(n.children())) index(c, n, out, tokens, where, present);
    }

    private static String key(int page, String id) { return page + "\u0000" + id; }
}
