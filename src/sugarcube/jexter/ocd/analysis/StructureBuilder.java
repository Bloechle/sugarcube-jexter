package sugarcube.jexter.ocd.analysis;

import sugarcube.jexter.core.JxClock;
import sugarcube.jexter.ocd.analysis.Segmenter.Block;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDGroup;
import sugarcube.jexter.ocd.model.OCDNode;
import sugarcube.jexter.ocd.model.OCDParagraph;
import sugarcube.jexter.ocd.model.OCDStruct;
import sugarcube.jexter.ocd.model.OCDStructure;
import sugarcube.jexter.ocd.model.OCDText;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives the logical {@link OCDStruct} tree from the {@link Segmenter.Block}s of the document — the
 * same blocks the physical layer ({@link Paragrapher} &rarr; {@link OCDParagraph}) was built from, read
 * back by {@link Segmenter#fromParagraph} (no re-segmentation). Blocks are ordered per page by the
 * shared {@link XYCut} engine, then each text block is labelled HEADING or PARAGRAPH.
 *
 * <p><b>Scope: text + titles only.</b> This pass emits exactly two element types — HEADING and
 * PARAGRAPH. Figures, tables, captions, lists and code are deferred to the downstream LLM-refine layer;
 * images and vector graphics stay in the presentation layer and never enter the logical tree here.
 *
 * <p><b>Headings are typographic, not lexical.</b> A block is a heading when {@link FontProfile} — the
 * document-wide style alphabet — ranks its size/weight as a recurring title style (size above body, or
 * bold at body size in a non-bold-body document), never from regex enumerator patterns. Running
 * heads/feet are excluded via {@link Furniture#isRunning} (the one authority). Additive only:
 * it references content by id and never alters a pixel.
 */
public final class StructureBuilder {

    private StructureBuilder() {}

    private static final int    HEADING_MAX_LINES = 2;      // a heading is a short label, never a flowing paragraph
    private static final int    HEADING_MAX_CHARS = 160;    // brevity cap for the size/weight signals
    private static final double SUPER_RATIO       = 0.8;    // a pure-digit run <= this x the line size is a footnote-ref superscript

    /** Build the heuristic HEADING/PARAGRAPH tree. Headings come from the {@link FontProfile} recurrence —
     *  no size-ratio or table/list/figure parameters: those concerns are deferred to the LLM-refine layer. */
    public static void build(OCDDocument doc) {
        List<Block> blocks = new ArrayList<>();
        for (List<Block> pageBlocks : orderedBlocksByPage(doc).values()) blocks.addAll(pageBlocks);
        if (blocks.isEmpty()) return;

        FontProfile fp = FontProfile.of(doc);            // document-wide style alphabet — defines headings unambiguously
        OCDStruct root = new OCDStruct(OCDStruct.Type.DOCUMENT);
        Deque<OCDStruct> headings = new ArrayDeque<>();
        for (Block b : blocks) {
            OCDStruct container = headings.isEmpty() ? root : headings.peek();
            if (isHeading(b, fp)) addHeading(b.page, level(b, fp), headingIds(b), headings, root);
            else                  addParagraph(container, b);
        }
        doc.structures().removeIf(st -> "heuristic".equals(st.id()));     // idempotent re-run / generate-outline
        doc.addStructure(new OCDStructure("heuristic", "Heuristic", OCDStructure.Source.HEURISTIC)
                .by("StructureBuilder").at(JxClock.millis())
                .how("XY-Cut+ geometry \u00b7 FontProfile recurrence").root(root));
    }

    /** Collect the page's <b>text</b> blocks (read back from the physical paragraphs — no re-segmentation).
     *  Images and vector graphics are presentation, not structure, so they never enter the logical tree. */
    private static void collect(OCDDocument doc, List<OCDNode> nodes, int page, List<Block> out) {
        // Fragments of one text flow rejoin here: Paragrapher must split a block whose runs are interleaved in
        // paint order (a paragraph wraps a CONTIGUOUS paint span, or the stored page inverts), but that is a
        // presentation fact and the logical layer refuses to inherit it — one flow is one Block, hence one
        // PARAGRAPH, so Markdown / DocTags / HTML / reflowable EPUB never see the split.
        List<List<OCDParagraph>> flows = new ArrayList<>();
        gather(nodes, flows, new HashMap<>());
        for (List<OCDParagraph> frags : flows) out.add(Segmenter.fromParagraphs(doc, frags, page));
    }

    /** Walk the level in reading order and bucket the paragraphs by flow: a whole paragraph opens its own
     *  bucket, a fragment joins the one its flow already opened. {@code flows} keeps the reading order,
     *  {@code byFlow} is only the index into it.
     *
     *  <p>Deliberately NOT {@code page.nodes()}: this walk must stop at a paragraph and never look inside
     *  it, and it must see the levels as the flow left them. Flattening it moves the reading indices —
     *  measured, and it breaks {@code write(read(x)) == x}. */
    private static void gather(List<OCDNode> nodes, List<List<OCDParagraph>> flows,
                               Map<Integer, List<OCDParagraph>> byFlow) {
        for (OCDNode n : nodes) {
            if (n instanceof OCDParagraph p) {
                List<OCDParagraph> bucket = p.isFragment() ? byFlow.get(p.flow()) : null;
                if (bucket == null) {
                    bucket = new ArrayList<>();
                    flows.add(bucket);
                    if (p.isFragment()) byFlow.put(p.flow(), bucket);
                }
                bucket.add(p);
            } else if (n instanceof OCDGroup g) gather(g.children(), flows, byFlow);
        }
    }


    /** The document's text blocks, ordered per page by the shared {@link XYCut}, keyed by page index. The one
     *  block source shared by the heuristic outline and {@link BookmarkStructureBuilder}, so both read the same
     *  segmentation. */
    static Map<Integer, List<Block>> orderedBlocksByPage(OCDDocument doc) {
        Map<Integer, List<Block>> byPage = new LinkedHashMap<>();
        for (int pi = 0; pi < doc.pageCount(); pi++) {
            List<Block> pageBlocks = new ArrayList<>();
            collect(doc, doc.page(pi).content(), pi, pageBlocks);
            byPage.put(pi, XYCut.order(pageBlocks, b -> b.bounds));
        }
        return byPage;
    }

    /** Wrap one text block as a PARAGRAPH under {@code container} — THE wrapping authority,
     *  shared with {@link BookmarkStructureBuilder} so both trees reference blocks identically. */
    static void addParagraph(OCDStruct container, Block b) {
        OCDStruct p = new OCDStruct(OCDStruct.Type.PARAGRAPH);
        for (String nid : b.nodeIds) p.addRef(b.page, nid);
        container.add(p);
    }

    /** A block is a heading when it is not running furniture / pure numbering and its document-wide
     *  typographic style ({@link FontProfile}) ranks as a recurring title. Always a short label. */
    private static boolean isHeading(Block b, FontProfile fp) {
        if (isRunning(b)) return false;                            // running head/foot (Furniture authority)
        if (!hasAlphaWord(b.text())) return false;                 // a heading needs a real word — rejects "832.20", "1 / 60"
        if (b.lines.size() > HEADING_MAX_LINES) return false;      // a heading is a short label, never a flowing paragraph
        if (b.text().length() > HEADING_MAX_CHARS) return false;
        FontProfile.Style s = styleOf(b);                          // document-wide recurrence decides — no local size ratio
        return fp.isTitle(s) || fp.headingLevel(s) > 0;
    }

    /** A block's typographic signature for the {@link FontProfile} lookup: half-pt size and whole-block weight. */
    private static FontProfile.Style styleOf(Block b) {
        return FontProfile.styleOf(b.size, b.bold());
    }

    /** Heading level: the document-wide size rank (largest = 1) from the {@link FontProfile}. */
    private static int level(Block b, FontProfile fp) {
        FontProfile.Style s = styleOf(b);
        int r = fp.isTitle(s) ? 0 : Math.max(0, fp.headingLevel(s) - 1);   // 0-based rank from the document-wide profile
        return Math.min(6, r + 1);
    }

    /** True iff the block is running furniture, per the single authority {@link Furniture#isRunning}. */
    private static boolean isRunning(Block b) { return b.source != null && Furniture.isRunning(b.source); }

    /** A heading's content ids with footnote-reference superscripts dropped: a pure-digit run distinctly
     *  smaller than its line's dominant size is a footnote marker, so {@code "Titre 10 superscript"} keeps
     *  only {@code "Titre 10"}. The marker still renders; it is only removed from the heading's logical text. */
    private static List<String> headingIds(Block b) {
        List<String> ids = new ArrayList<>();
        for (Segmenter.Line ln : b.lines) {
            double dom = Segmenter.dominantSize(ln.runs);
            for (OCDText t : ln.runs) {
                if (t.id() == null) continue;
                String s = t.text() == null ? "" : t.text().strip();
                boolean digits = s.matches("\\d+");
                if (digits && dom > 0 && t.fontSize() <= SUPER_RATIO * dom) continue;   // footnote-ref superscript
                ids.add(t.id());
            }
        }
        return ids;
    }

    /** Add one HEADING at {@code level}, nesting it under the open-heading stack (HTML5-outline style). */
    private static OCDStruct addHeading(int page, int level, List<String> nodeIds, Deque<OCDStruct> headings, OCDStruct root) {
        OCDStruct el = new OCDStruct(OCDStruct.Type.HEADING).level(level);
        while (!headings.isEmpty() && headings.peek().level() >= level) headings.pop();
        (headings.isEmpty() ? root : headings.peek()).add(el);
        headings.push(el);
        for (String nid : nodeIds) el.addRef(page, nid);
        return el;
    }

    private static boolean hasAlphaWord(String s) { return s != null && s.matches(".*\\p{L}{2,}.*"); }
}
