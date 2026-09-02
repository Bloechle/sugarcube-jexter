package sugarcube.jexter.ocd.analysis;

import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDGroup;
import sugarcube.jexter.ocd.model.OCDNode;
import sugarcube.jexter.ocd.model.OCDPage;
import sugarcube.jexter.ocd.model.OCDParagraph;
import sugarcube.jexter.ocd.model.OCDStruct;
import sugarcube.jexter.ocd.model.OCDStructure;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Projects the document's best logical structure onto page-level <b>heading roles</b>:
 * every paragraph a {@code HEADING} struct references gets {@code data-role="heading-N"},
 * so the roles survive in the pages regardless of WHERE the headings came from — PDF/UA
 * tag tree ({@code pdf}), bookmarks ({@code outline}, largely pre-tagged by
 * {@link OutlineAligner}), or the heuristic. One projector, every source: a consumer that
 * only reads pages (overlays, TTS, exports) sees the same vocabulary in all three worlds.
 *
 * <p>Structure priority: the document's {@code defaultStructureId}, else {@code pdf} >
 * {@code outline} > {@code heuristic} > first. Additive and render-neutral (roles are
 * attributes); an already-tagged paragraph is never overwritten, so the
 * {@link OutlineAligner}'s nav-grounded tags win over a looser reference. Idempotent.
 */
public final class HeadingRoles {

    private HeadingRoles() {}

    private static final int MAX_LEVEL = 6;

    public static void project(OCDDocument doc) {
        OCDStructure st = pick(doc);
        if (st == null || st.root() == null) return;
        // run/paragraph id → owning paragraph, per page (refs may point at either)
        Map<Integer, Map<String, OCDParagraph>> owner = new HashMap<>();
        for (int pi = 0; pi < doc.pageCount(); pi++) {
            Map<String, OCDParagraph> m = new HashMap<>();
            index(doc.page(pi), m);
            owner.put(pi, m);
        }
        int[] tagged = { 0 };
        walk(st.root(), owner, tagged);
        if (tagged[0] > 0)
            sugarcube.jexter.core.JxLog.info(HeadingRoles.class,
                    tagged[0] + " heading roles projected from structure '" + st.id() + "'");
    }

    private static OCDStructure pick(OCDDocument doc) {
        List<OCDStructure> all = doc.structures();
        if (all.isEmpty()) return null;
        if (doc.defaultStructureId() != null) {
            OCDStructure d = doc.structureById(doc.defaultStructureId());
            if (d != null) return d;
        }
        for (String id : new String[]{ "pdf", "outline", "heuristic" }) {
            OCDStructure s = doc.structureById(id);
            if (s != null) return s;
        }
        return all.get(0);
    }

    /** Every id that can name a paragraph — its own and each of its children's — mapped to that paragraph. */
    private static void index(OCDPage page, Map<String, OCDParagraph> m) {
        page.nodes().filter(OCDParagraph.class::isInstance).map(OCDParagraph.class::cast).forEach(p -> {
            if (p.id() != null) m.put(p.id(), p);
            for (OCDNode c : p.children()) if (c.id() != null) m.put(c.id(), p);
        });
    }

    private static void walk(OCDStruct s, Map<Integer, Map<String, OCDParagraph>> owner, int[] tagged) {
        if (s.type() == OCDStruct.Type.HEADING && !s.refs().isEmpty()) {
            int level = Math.min(MAX_LEVEL, Math.max(1, s.level()));
            for (OCDStruct.Ref r : s.refs()) {
                Map<String, OCDParagraph> m = owner.get(r.page());
                OCDParagraph p = m == null ? null : m.get(r.nodeId());
                if (p != null && !p.hasRole()) { p.role("heading-" + level); tagged[0]++; }
            }
        }
        for (OCDStruct c : s.children()) walk(c, owner, tagged);
    }
}
