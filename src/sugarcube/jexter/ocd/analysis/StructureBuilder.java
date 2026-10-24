package sugarcube.jexter.ocd.analysis;

import sugarcube.jexter.core.JxClock;
import sugarcube.jexter.ocd.analysis.Segmenter.Block;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDGroup;
import sugarcube.jexter.ocd.model.OCDNode;
import sugarcube.jexter.ocd.model.OCDParagraph;
import sugarcube.jexter.ocd.model.OCDStructNode;
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
 * Derives the logical {@link OCDStructNode} tree from the {@link Segmenter.Block}s of the document — the
 * same blocks the physical layer ({@link Paragrapher} &rarr; {@link OCDParagraph}) was built from, read
 * back by {@link Segmenter#fromParagraph} (no re-segmentation). Blocks are read per page in the content's
 * order, the reading order {@link XYCut} gave it, then each text block is labelled HEADING or PARAGRAPH.
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
        OCDStructNode root = new OCDStructNode(OCDStructNode.Type.DOCUMENT);
        Deque<OCDStructNode> headings = new ArrayDeque<>();
        Paragraphs paragraphs = new Paragraphs();
        for (Block b : blocks) {
            OCDStructNode container = headings.isEmpty() ? root : headings.peek();
            if (isFurniture(b))   continue;                       // running heads and folios are no part of the text
            if (isHeading(b, fp, doc)) { addHeading(b.page, level(b, fp, doc), headingIds(b), headings, root); paragraphs.interrupt(b); }
            else                  paragraphs.add(container, b);
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


    /** The document's text blocks per page, keyed by page index, in the content's order — the reading order
     *  {@link Paragrapher} set with the whole page in view (its photos and boxes included). The one block source
     *  shared by the heuristic outline and {@link BookmarkStructureBuilder}, so both read the same segmentation;
     *  a second cut over the text alone, blind to what screens it, read a column past a photo out of order. */
    static Map<Integer, List<Block>> orderedBlocksByPage(OCDDocument doc) {
        Map<Integer, List<Block>> byPage = new LinkedHashMap<>();
        for (int pi = 0; pi < doc.pageCount(); pi++) {
            List<Block> pageBlocks = new ArrayList<>();
            collect(doc, doc.page(pi).content(), pi, pageBlocks);
            byPage.put(pi, pageBlocks);
        }
        return byPage;
    }

    /**
     * THE wrapping authority, shared with {@link BookmarkStructureBuilder} so both trees reference blocks
     * identically: each text block becomes a PARAGRAPH — except a block that carries on, over a page or a column
     * break, the paragraph before it, which joins it. Those breaks are the press's, not the author's: a paragraph
     * they cut came out of every reflowable book, Markdown and DocTags as two, the second starting mid-sentence.
     *
     * <p>Geometry decides: the paragraph wrapped and its last line is full by its own measure (a paragraph's
     * own end leaves its last line short), and the block's first line is not indented; the block is the first
     * text of the next page, in the same column or one as wide ({@link #continues}), or opens the next column of
     * the same page ({@link #continuesBeside}). One instance per tree walk; a heading or anything else between
     * the two ({@link #interrupt}) keeps them apart.
     */
    static final class Paragraphs {
        private Block last;
        private OCDStructNode lastP;
        private int page = -1;

        void add(OCDStructNode container, Block b) {
            boolean first = b.page != page;
            page = b.page;
            List<OCDStructNode> kids = container.children();
            boolean open = last != null && !kids.isEmpty() && kids.get(kids.size() - 1) == lastP;
            if (open && (first ? b.page == last.page + 1 && continues(last, b) : b.page == last.page && continuesBeside(last, b))) {
                for (String nid : b.nodeIds) lastP.addRef(b.page, nid);
            } else {
                lastP = new OCDStructNode(OCDStructNode.Type.PARAGRAPH);
                for (String nid : b.nodeIds) lastP.addRef(b.page, nid);
                container.add(lastP);
            }
            last = b;
        }

        /** A block that is not a paragraph (a heading): what follows it never joins what came before it. */
        void interrupt(Block b) { page = b.page; last = null; lastP = null; }
    }

    /** Whether {@code b}, the first text of its page, carries on {@code a}, the last text of the page before: in
     *  the same column, or in one as wide — on a page set in columns, the last flows into the first of the next. */
    static boolean continues(Block a, Block b) {
        double col = column(a, b);
        return col > 0 && runsOn(a, b, col) && (Math.abs(a.bounds.x() - b.bounds.x()) <= COLUMN_SLACK * col
                                              || Math.abs(a.bounds.width() - b.bounds.width()) <= COLUMN_SLACK * col);
    }

    /** Whether {@code b}, opening the next column of the same page, carries on {@code a}, the last text of the
     *  column before: the next column to the right (less than a column away), starting higher, in the same
     *  size. The press and the magazine break a paragraph at the foot of a column as at the foot of a page. */
    static boolean continuesBeside(Block a, Block b) {
        double col = column(a, b);
        return col > 0 && b.bounds.x() >= a.bounds.right() - COLUMN_SLACK * col                 // to the right…
            && b.bounds.x() - a.bounds.right() < col                                            // …the next column
            && b.bounds.maxY() > a.bounds.y()                                                   // its top above a's foot
            && Math.abs(a.size - b.size) <= SIZE_TOL && runsOn(a, b, col);
    }

    /** The column the two blocks are set in: their widest line, or 0 when either is empty. */
    private static double column(Block a, Block b) {
        if (a.lines.isEmpty() || b.lines.isEmpty()) return 0;
        double col = 0;
        for (Segmenter.Line l : a.lines) col = Math.max(col, l.bounds.width());
        for (Segmenter.Line l : b.lines) col = Math.max(col, l.bounds.width());
        return col;
    }

    /** {@code a} ran out of room (its last line is full) and {@code b} does not open a paragraph (no indent).
     *  Full is the block's own measure: text set ragged wraps short of the column, so a last line as long as
     *  the shortest line the block wrapped ran out of room too — never under {@link #RAGGED} of the column.
     *  A block of one line has wrapped nothing: a label, a number, a table-of-contents entry — it runs on into
     *  nothing (two such side by side are each "full" of a column only as wide as themselves). */
    private static boolean runsOn(Block a, Block b, double col) {
        if (a.lines.size() < 2) return false;
        Segmenter.Line end = a.lines.get(a.lines.size() - 1), start = b.lines.get(0);
        double full = FULL_LINE * col;
        for (int i = 0; i < a.lines.size() - 1; i++) full = Math.min(full, a.lines.get(i).bounds.width());
        if (end.bounds.width() < Math.max(full, RAGGED * col)) return false;            // a short last line ends a paragraph
        return b.lines.size() < 2 || start.bounds.x() - b.lines.get(1).bounds.x() <= INDENT * Math.max(1, b.size);   // indented: a new paragraph
    }

    private static final double FULL_LINE    = 0.90;   // × the column: a line this long ran out of room, it did not end
    private static final double RAGGED       = 0.75;   // × the column: no line shorter than this ran out of room
    private static final double COLUMN_SLACK = 0.10;   // × the column: two columns this close in width or place are alike
    private static final double INDENT       = 0.5;    // × the text size: a first line further in is an indented new paragraph
    private static final double SIZE_TOL     = 0.5;    // points: two blocks this close in size are set in one text size

    /** A block is a heading when it is not running furniture / pure numbering and its document-wide
     *  typographic style ({@link FontProfile}) ranks as a recurring title. Always a short label. */
    static boolean isHeading(Block b, FontProfile fp, OCDDocument doc) {
        if (isRunning(b)) return false;                            // running head/foot (Furniture authority)
        if (!hasAlphaWord(b.text())) return false;                 // a heading needs a real word — rejects "832.20", "1 / 60"
        if (b.lines.size() > HEADING_MAX_LINES) return false;      // a heading is a short label, never a flowing paragraph
        if (b.text().length() > HEADING_MAX_CHARS) return false;
        FontProfile.Style s = styleOf(b, doc);                     // document-wide recurrence decides — no local size ratio
        return fp.isTitle(s) || fp.headingLevel(s) > 0;
    }

    /** A block's style for the {@link FontProfile} lookup — the profile's OWN reading of a line
     *  ({@link FontProfile#styleOfLine}), over the block's runs: a lookup by a style the profile never
     *  counted (another bold share, another size) finds no level. */
    private static FontProfile.Style styleOf(Block b, OCDDocument doc) {
        List<OCDText> runs = new ArrayList<>();
        for (Segmenter.Line ln : b.lines) runs.addAll(ln.runs);
        return FontProfile.styleOfLine(runs, doc);
    }

    /** Heading level: the document-wide size rank (largest = 1) from the {@link FontProfile}. */
    private static int level(Block b, FontProfile fp, OCDDocument doc) {
        FontProfile.Style s = styleOf(b, doc);
        int r = fp.isTitle(s) ? 0 : Math.max(0, fp.headingLevel(s) - 1);   // 0-based rank from the document-wide profile
        return OCDStructNode.headingLevel(r + 1);
    }

    /** True iff the block is running furniture, per the single authority {@link Furniture#isRunning}. */
    private static boolean isRunning(Block b) { return b.source != null && Furniture.isRunning(b.source); }

    /** A block made of running furniture and nothing else: it stays on the page, never in the logical tree —
     *  a running head or a folio read into Markdown, DocTags, HTML or a reflowable EPUB repeats on every page.
     *  ALL its runs, not any: a block that also carries body text keeps it. */
    static boolean isFurniture(Block b) {
        boolean any = false;
        for (Segmenter.Line ln : b.lines)
            for (OCDText t : ln.runs) {
                if (t.text() == null || t.text().isBlank()) continue;
                if (!Furniture.isRunning(t)) return false;
                any = true;
            }
        return any;
    }

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
    private static OCDStructNode addHeading(int page, int level, List<String> nodeIds, Deque<OCDStructNode> headings, OCDStructNode root) {
        OCDStructNode el = new OCDStructNode(OCDStructNode.Type.HEADING).level(level);
        while (!headings.isEmpty() && headings.peek().level() >= level) headings.pop();
        (headings.isEmpty() ? root : headings.peek()).add(el);
        headings.push(el);
        for (String nid : nodeIds) el.addRef(page, nid);
        return el;
    }

    private static boolean hasAlphaWord(String s) { return s != null && s.matches(".*\\p{L}{2,}.*"); }
}
