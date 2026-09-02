package sugarcube.jexter.ocd.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <h2>The id authority for content nodes — the single place node identifiers are minted.</h2>
 *
 * Before this class, ids were stamped by five different sites with three clashing prefixes
 * ({@code n…}, {@code G…}, {@code g…}) and tied to paint order. They are now minted in one place,
 * under one rock-solid contract:
 *
 * <ul>
 *   <li><b>Page-local scope.</b> Ids are unique <i>within a page</i>. An {@link OCDStruct.Ref} is
 *       (page index, node id), so the same id string may legitimately recur on other pages — that is
 *       by design: it keeps ids short and unaffected by page reordering.</li>
 *   <li><b>Deterministic.</b> Ids derive from a depth-first walk of the page content in <i>content
 *       order</i> (not paint / {@code z} order). Identical content yields identical ids — reproducible
 *       across runs, which keeps re-analysis and any downstream model evaluation stable.</li>
 *   <li><b>Type-tagged.</b> A one-letter {@linkplain #prefix prefix} encodes the node kind; the
 *       numeric suffix is a per-(page, kind) sequence starting at 1. An id is therefore
 *       self-describing — {@code t4} is the 4th text run on its page, {@code i1} the first image —
 *       and cross-kind collisions are impossible by construction.</li>
 *   <li><b>Total.</b> After {@link #fill}, every node on every page carries a non-empty id.
 *       Structures may safely reference any node.</li>
 * </ul>
 *
 * <h3>Prefix legend</h3>
 * <pre>
 *   t text run     v vector path   i image      b line break   m media (audio/video)
 *   p paragraph    l OCG layer     x graphic    g group        n node (fallback)
 * </pre>
 *
 * One entry point: {@link #fill} mints ids only for nodes still lacking one — continuing each
 * per-(page, kind) sequence past the ids already on the page. On a fresh import every node is id-less,
 * so it mints the whole page canonically (content order, from 1); on re-analysis it preserves every
 * existing id, so an id a structure already references is never changed. There is deliberately no
 * clean-slate re-mint: re-numbering a referenced node would break the very structures this protects.
 */
public final class IdStamper {

    private IdStamper() {}

    /** Mint ids for nodes lacking one, continuing each per-(page, kind) sequence past the ids already
     *  present — so a node created by a later analysis pass gets a deterministic id while an existing
     *  (possibly structure-referenced) id is never disturbed. Idempotent: a fully-id'd page is a no-op.
     *  This is the <b>single</b> entry point; on a fresh import every node is id-less, so the first call
     *  mints the whole page canonically (content order, sequences from 1), exactly as a clean-slate stamp. */
    public static void fill(OCDDocument doc) {
        for (OCDPage page : doc.pages()) {
            Map<Character, Integer> seq = new HashMap<>();
            seed(page.content(), seq);
            walk(page.content(), seq);
        }
    }

    private static void walk(List<OCDNode> nodes, Map<Character, Integer> seq) {
        for (OCDNode n : nodes.stream().flatMap(OCDNode::stream).toList()) {    // OCDNode.stream = the traversal
            if (n instanceof OCDBreak) continue;                  // a break is a token, not an addressable node — no id
            if (n.id() == null || n.id().isEmpty()) {
                char k = prefix(n);
                n.id(k + Integer.toString(seq.merge(k, 1, Integer::sum)));
            }
        }
    }

    /** Advance each per-kind counter past the highest suffix already in use, so {@link #fill}
     *  cannot collide with an id that is already present (and possibly referenced). */
    private static void seed(List<OCDNode> nodes, Map<Character, Integer> seq) {
        for (OCDNode n : nodes.stream().flatMap(OCDNode::stream).toList()) {
            if (n instanceof OCDBreak) continue;
            String id = n.id();
            if (id != null && id.length() >= 2) {
                try { seq.merge(id.charAt(0), Integer.parseInt(id.substring(1)), Math::max); }
                catch (NumberFormatException ignore) { /* foreign id format → leave this counter */ }
            }
        }
    }

    /** THE source of truth for id prefixes — one letter per node kind. Group subtypes are tested
     *  before the generic {@link OCDGroup} so they win. */
    public static char prefix(OCDNode n) {
        if (n instanceof OCDText)         return 't';   // text run
        if (n instanceof OCDPath)         return 'v';   // vector path
        if (n instanceof OCDImage)        return 'i';   // image
        if (n instanceof OCDMedia)        return 'm';   // audio / video
        if (n instanceof OCDParagraph)    return 'p';   // text block
        if (n instanceof OCDLayerContent) return 'l';   // OCG layer content
        if (n instanceof OCDGraphic)      return 'x';   // clustered vector graphic
        if (n instanceof OCDGroup)        return 'g';   // generic container
        return 'n';                                      // fallback (no concrete kind matched)
    }
}
