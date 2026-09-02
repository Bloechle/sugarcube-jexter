package sugarcube.jexter.ocd.analysis;

import sugarcube.jexter.core.JxClock;
import sugarcube.jexter.ocd.analysis.Segmenter.Block;
import sugarcube.jexter.core.JxText;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDOutline;
import sugarcube.jexter.ocd.model.OCDStruct;
import sugarcube.jexter.ocd.model.OCDStructure;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the PDF <b>bookmark tree</b> ({@link OCDDocument#outline()}) into a projectable logical structure.
 *
 * <p>A bookmark already carries everything a heading hierarchy needs: a title, a tree depth (= the heading
 * level, h1 at the top, h2 one level in, …), and a destination (page + page-space {@code y}). What it lacks
 * is the link to the <i>content</i> — which runs on the page <i>are</i> that heading — so the result is not
 * directly projectable to HTML/EPUB. This pass supplies exactly that link: each bookmark becomes a
 * {@link OCDStruct.Type#HEADING} at its tree depth, <b>anchored</b> to the page {@link Segmenter.Block} that
 * best matches it — same title text when the bookmark quotes the heading, else the block nearest its
 * destination {@code y}. The bookmarks' own nesting <i>is</i> the hierarchy, so no {@link FontProfile} size
 * ranking is consulted.
 *
 * <p>{@link OutlineAligner} runs upstream and re-cuts the segmentation on these very titles, so on an
 * aligned document a bookmark's block matches its title <b>exactly, one block</b>: the anchor tries strict
 * equality first (tie-broken by destination {@code y}), and only falls back to the fuzzy prefix/contains
 * match — then nearest-{@code y} — for the entries the aligner left untouched.
 *
 * <p>The bookmarks give the <b>skeleton</b> only — the tree would carry headings and nothing else, and every
 * structure-projecting writer (Markdown, DocTags, reflowable HTML/EPUB) walks this tree as its sole content
 * source. So after the skeleton is built, the remaining text blocks are attached in reading order as
 * {@link OCDStruct.Type#PARAGRAPH} under the heading that precedes them (the document root before the first
 * one) — same wrapping authority as the heuristic pass ({@link StructureBuilder#addParagraph}).
 *
 * <p>It is the second rung of the structure-priority chain: a tagged PDF/UA tree ({@code pdf}) wins; with no
 * tags but bookmarks present, this resolves them <b>immediately</b> at import so the document still has a real
 * block-referenced structure, not just a navigation tree. Additive and render-neutral: it references content
 * by id and never alters a pixel. Re-runnable — it replaces any prior {@code outline} structure.
 */
public final class BookmarkStructureBuilder {

    private BookmarkStructureBuilder() {}

    private static final int    MAX_LEVEL = 6;     // HEADING depth cap, like the rest of the pipeline

    /** Resolve the bookmark tree into the {@code outline} structure. No-op when the document has no bookmarks. */
    public static void build(OCDDocument doc) {
        if (doc.outline().isEmpty()) return;
        Map<Integer, List<Block>> byPage = StructureBuilder.orderedBlocksByPage(doc);   // shared block source

        OCDStruct root = new OCDStruct(OCDStruct.Type.DOCUMENT);
        int[] stats = new int[3];                                                        // exact · fuzzy · y-only
        Map<Block, OCDStruct> anchored = new IdentityHashMap<>();                        // anchor block → its HEADING
        for (OCDOutline o : doc.outline()) walk(o, 1, root, byPage, stats, anchored);
        if (root.children().isEmpty()) return;

        // The skeleton alone would lose the body: writers project THIS tree and nothing else.
        // Walk the blocks in reading order; an anchor block IS its heading (switch container),
        // every other block becomes a PARAGRAPH under the heading that precedes it.
        OCDStruct current = root;
        for (List<Block> pageBlocks : byPage.values())
            for (Block b : pageBlocks) {
                OCDStruct h = anchored.get(b);
                if (h != null) current = h;
                else StructureBuilder.addParagraph(current, b);
            }
        sugarcube.jexter.core.JxLog.info(BookmarkStructureBuilder.class,
                "anchors: " + stats[0] + " exact, " + stats[1] + " fuzzy, " + stats[2] + " nearest-y");

        doc.structures().removeIf(st -> "outline".equals(st.id()));   // idempotent re-run
        doc.addStructure(new OCDStructure("outline", "PDF outline (bookmarks)", OCDStructure.Source.PDF)
                .by("BookmarkStructureBuilder").at(JxClock.millis())
                .how("PDF bookmark tree \u00b7 anchored to nearest block")
                .purpose("Bookmark hierarchy resolved to block-referenced headings").root(root));
    }

    /** Emit a HEADING for {@code o} at {@code level}, anchored to its best-matching block, then recurse into
     *  its children one level deeper — so the bookmark tree's nesting becomes the heading nesting. */
    private static void walk(OCDOutline o, int level, OCDStruct parent, Map<Integer, List<Block>> byPage,
                             int[] stats, Map<Block, OCDStruct> anchored) {
        OCDStruct h = new OCDStruct(OCDStruct.Type.HEADING).level(Math.min(MAX_LEVEL, level));
        Block anchor = anchor(o, byPage.get(o.pageIndex()), stats);
        if (anchor != null) {
            for (String nid : anchor.nodeIds) h.addRef(o.pageIndex(), nid);
            anchored.put(anchor, h);                       // last wins: the deepest heading on a shared anchor
        }
        parent.add(h);
        for (OCDOutline c : o.children()) walk(c, level + 1, h, byPage, stats, anchored);
    }

    /** The block on the bookmark's destination page that best matches it: a title-text match (the bookmark
     *  quotes the heading) tie-broken by proximity to the destination {@code y}; failing any text match, the
     *  block whose top is nearest that {@code y}. {@code null} when the destination is unresolved or the page
     *  has no blocks (the heading is still emitted, unanchored, to keep the tree shape). */
    private static Block anchor(OCDOutline o, List<Block> blocks, int[] stats) {
        if (blocks == null || blocks.isEmpty() || !o.hasDestination()) return null;
        String want = norm(o.title());
        Block best = null; double bestDist = Double.MAX_VALUE;
        for (Block b : blocks) {                                  // 1) EXACT — the aligner made these one-block
            if (want.isEmpty() || !norm(b.text()).equals(want)) continue;
            double dist = o.hasY() ? Math.abs(b.bounds.maxY() - o.y()) : 0;
            if (best == null || dist < bestDist) { best = b; bestDist = dist; }
        }
        if (best != null) { stats[0]++; return best; }
        for (Block b : blocks) {                                  // 2) fuzzy prefix/contains, nearest-y among matches
            String bt = norm(b.text());
            boolean match = !want.isEmpty() && (bt.startsWith(want) || want.startsWith(bt) || bt.contains(want));
            if (!match) continue;
            double dist = o.hasY() ? Math.abs(b.bounds.maxY() - o.y()) : 0;
            if (best == null || dist < bestDist) { best = b; bestDist = dist; }
        }
        if (best != null) { stats[1]++; return best; }
        if (!o.hasY()) return null;                               // 3) nearest-y fallback
        for (Block b : blocks) {
            double d = Math.abs(b.bounds.maxY() - o.y());
            if (d < bestDist) { best = b; bestDist = d; }
        }
        if (best != null) stats[2]++;
        return best;
    }

    private static String norm(String s) { return JxText.collapse(s).toUpperCase(); }
}
