package sugarcube.jexter.ocd.analysis;

import sugarcube.jexter.ocd.model.OCDBreak;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDGroup;
import sugarcube.jexter.ocd.model.OCDNode;
import sugarcube.jexter.ocd.model.OCDParagraph;
import sugarcube.jexter.ocd.model.OCDText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Physical text recomposition: groups a page's flat text runs into {@link OCDParagraph}s — visual
 * lines separated by {@link OCDBreak} tokens — the first, geometric layer of the structured document.
 *
 * <p>It is a thin projection over the shared {@link Segmenter} segmenter: it walks the content tree
 * (recursing into graphical groups so a paragraph never crosses a group boundary), isolates each
 * maximal span of sibling text runs, asks {@link Segmenter#segment} for that span's blocks, and emits
 * one {@code OCDParagraph} per <b>paint-contiguous fragment</b> of a block. The line clustering, column
 * split and block grouping all live in {@code Segmenter} (the single segmenter), not here.
 *
 * <p><b>Every decision is taken in {@code z}, never in flow order.</b> The flow carries reading order
 * as soon as one analysis has run ({@link Cleaner} preserves the flow it is given, and this pass ends
 * on {@link XYCut#order}), so flow adjacency is not paint adjacency on a re-analysis. {@code z} is the
 * only order the pipeline never rewrites, so it is the frame for both the decoration bracket test and
 * the fragment cut — which is what makes the pass a fixed point under re-analysis.
 *
 * <p><b>A paragraph wraps a CONTIGUOUS paint span — invariant, not an aspiration.</b> A wrapper is
 * spliced away by {@link OCDNode#inPaintOrder} (renderer, {@code PdfWriter}, {@code SvgWriter}) but is
 * stored <i>atomically</i> by {@code SvgOcdWriter}, because the OCD-EPUB page must carry the
 * {@code <g data-ocd="p">} grouping. A paragraph straddling a foreign node's {@code z} would therefore
 * serialize that node <i>after</i> the whole paragraph and silently invert the paint interleave in the
 * stored page (measured: a knockout label vanishing under its own tile). So a block interrupted in
 * paint order emits several fragments rather than one straddling wrapper.
 *
 * <p><b>The flow carries reading order.</b> It wraps existing runs in transparent structural
 * {@link OCDParagraph}s (identity transform, no clip) and orders each level — paragraphs and standalone
 * nodes — in reading order via {@link XYCut}. The painted result stays byte-identical because paint
 * follows {@code z}, not flow order. The segmenter deliberately <b>over-segments</b> — an
 * enumerator/marker line always opens its own block — so a heading is never glued to the next heading
 * or to its body.
 */
public final class Paragrapher {

    private Paragrapher() {}

    public static void recompose(OCDDocument doc) {
        for (int pi = 0; pi < doc.pageCount(); pi++) {
            // One flow mint per PAGE: recompose runs per LEVEL (it recurses into groups), so a per-level
            // counter would hand the same id to unrelated blocks in different groups and the consumers that
            // rejoin a flow would glue them together. The mint is threaded through the recursion instead.
            recompose(doc, doc.page(pi).content(), pi, new int[]{ 0 });   // blank runs pruned by Cleaner
        }
    }

    /** Wrap this node list's text runs into {@link OCDParagraph}s and order the level in <b>reading order</b>,
     *  in place. The painted result stays byte-identical because paint follows {@code z}
     *  ({@link OCDNode#inPaintOrder}), not flow order. */
    private static void recompose(OCDDocument doc, List<OCDNode> nodes, int page, int[] flowMint) {
        // recurse first: a graphical group's children are their own structural context
        for (OCDNode n : nodes)
            if (n instanceof OCDGroup g && !(n instanceof OCDParagraph)) recompose(doc, g.children(), page, flowMint);

        // PAINT ORDER IS THE FRAME. The incoming flow is reading order once an analysis has run, so it
        // is re-derived here from z — stable, so equal-z ties keep their flow position — and every
        // decision below (bracket test, fragment cut, child order) reads this list, not `nodes`.
        List<OCDNode> byZ = new ArrayList<>(nodes);
        byZ.sort(Comparator.comparingDouble(OCDNode::z));

        List<OCDText> text = new ArrayList<>();
        for (OCDNode n : byZ) if (n instanceof OCDText t) text.add(t);
        if (text.isEmpty()) return;

        // map each run to its block index + visual-line index
        List<Segmenter.Block> blocks = Segmenter.segment(doc, text, page);
        IdentityHashMap<OCDText, int[]> where = new IdentityHashMap<>();
        for (int bi = 0; bi < blocks.size(); bi++) {
            List<Segmenter.Line> lines = blocks.get(bi).lines;
            for (int li = 0; li < lines.size(); li++)
                for (OCDText t : lines.get(li).runs) where.put(t, new int[]{ bi, li });
        }

        // Block index per node: a text run takes its own; a non-text node (an inline decoration — a code-chip
        // background, an underline, a knockout tile) is *interior* to a block only when the runs bracketing it
        // IN PAINT ORDER belong to that same block. So a chip or a tile joins its paragraph, while a figure
        // path or a rule between two paragraphs stays a standalone sibling. (Blank runs are already gone.)
        int N = byZ.size();
        int[] blk = new int[N];
        for (int i = 0; i < N; i++) {
            OCDNode n = byZ.get(i);
            blk[i] = (n instanceof OCDText t && where.containsKey(t)) ? where.get(t)[0] : -1;
        }
        for (int i = 0; i < N; i++) {
            if (blk[i] >= 0) continue;                                              // already placed
            if (byZ.get(i) instanceof OCDText) continue;                            // every (inked) run places itself; only non-text decorations ride
            int before = -1; for (int k = i - 1; k >= 0; k--) if (blk[k] >= 0) { before = blk[k]; break; }
            int after  = -1; for (int k = i + 1; k <  N; k++) if (blk[k] >= 0) { after  = blk[k]; break; }
            if (before >= 0 && before == after) blk[i] = before;                   // bracketed by one block → rides into it
        }

        // Cut the paint sequence into MAXIMAL z-CONTIGUOUS fragments and wrap one paragraph per fragment.
        // A block whose runs genuinely interleave with another block's, or with a node no bracket could
        // absorb, therefore yields several paragraphs instead of one straddling wrapper — the interleave is
        // real, and the wrapper contract (contiguous paint span) is now true by construction, so the stored
        // page paints exactly as the model does. Deterministic and idempotent: the cut reads z, which no pass
        // rewrites, so re-analysis re-derives the same fragments (a flow-index cut would not — the flow is
        // re-ordered every pass, which is what made the earlier gather-everything form look necessary).
        List<OCDNode> out = new ArrayList<>(N);
        List<List<OCDParagraph>> byBlock = new ArrayList<>(blocks.size());
        for (int b = 0; b < blocks.size(); b++) byBlock.add(new ArrayList<>());
        for (int i = 0; i < N; ) {
            int b = blk[i];
            if (b < 0) { out.add(byZ.get(i++)); continue; }                          // standalone node → loose, at its z
            int j = i; while (j < N && blk[j] == b) j++;                             // maximal run of one block
            OCDParagraph para = paragraph(new ArrayList<>(byZ.subList(i, j)), where);
            byBlock.get(b).add(para);
            out.add(para);
            i = j;
        }
        // A block that emitted MORE THAN ONE fragment stays one text flow: every fragment takes the same id
        // from the PAGE's mint, so the consumers that rejoin a flow — the logical layer (StructureBuilder)
        // and the LLM signal harvest (BlockSignals) — see the paragraph, not the pieces paint order forced.
        // The projections read the structure tree, so Markdown / DocTags / HTML / reflowable EPUB never see
        // the split. A whole block keeps flow = -1 and serializes exactly as before. The id is minted ONLY
        // on a real split, so it stays dense, and the walk is deterministic, so it stays byte-stable.
        for (List<OCDParagraph> frags : byBlock) {
            if (frags.size() < 2) continue;
            int id = flowMint[0]++;
            for (OCDParagraph p : frags) p.flow(id);
        }
        // The flow carries READING ORDER (paint order lives in z, re-read by every rasteriser). Order this
        // level's items — paragraphs and standalone nodes (figures, rules) — by the shared XY-Cut+ engine, so
        // the document is stored as it reads. Reordering is render-safe by construction now that paint follows
        // z, not flow; re-analysis is geometry-based, so it is unaffected.
        nodes.clear();
        nodes.addAll(XYCut.order(out, OCDNode::bounds));
    }

    /** Wrap one paint-contiguous fragment — its runs plus the decorations interleaved among them — into an
     *  {@link OCDParagraph}, preserving paint order and inserting an {@link OCDBreak} at each visual-line
     *  change. Decorations ride along as children; text consumers skip non-text via
     *  {@code Segmenter.flatten}. */
    private static OCDParagraph paragraph(List<OCDNode> span, IdentityHashMap<OCDText, int[]> where) {
        OCDParagraph para = new OCDParagraph();
        int curLine = -1;
        for (OCDNode n : span) {
            if (n instanceof OCDText t && where.containsKey(t)) {
                int li = where.get(t)[1];
                if (curLine != -1 && li != curLine) para.add(new OCDBreak());      // new visual line — the inter-line boundary
                curLine = li;
            }
            para.add(n);                                                           // run or interior decoration
        }
        // The fragment IS a contiguous paint span, so the wrapper's paint slot is its earliest painting
        // child: it sorts into place under inPaintOrder (children keep their own z) and, stored atomically
        // by SvgOcdWriter, occupies exactly the span it came from. OCDBreaks paint nothing and carry no z
        // (default 0) — they must be excluded, or they would pin every multi-line paragraph to z=0 and sink
        // it under every fill.
        float zmin = Float.MAX_VALUE;
        for (OCDNode n : para.children()) if (!(n instanceof OCDBreak)) zmin = Math.min(zmin, n.z());
        if (zmin != Float.MAX_VALUE) para.z(zmin);
        return para;
    }
}
