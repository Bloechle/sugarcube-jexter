package sugarcube.jexter.write;

import sugarcube.jexter.convert.ConvertOptions;
import sugarcube.jexter.core.JxRect;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDNode;
import sugarcube.jexter.ocd.model.OCDPage;
import sugarcube.jexter.ocd.model.OCDStruct;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializes an {@link OCDDocument} to <b>DocTags</b> — IBM/Docling's LLM-oriented,
 * XML-inspired markup (the SmolDocling / Granite-Docling vocabulary). The whole
 * document is wrapped in {@code <doctag>…</doctag>}; pages are separated by
 * {@code <page_break>}. Each block carries its type tag plus nested location tokens
 * {@code <loc_x1><loc_y1><loc_x2><loc_y2>} on a fixed integer grid (default 500),
 * proportionally mapped to the page's display box. Tables use the OTSL vocabulary
 * ({@code <fcel> <ecel> <lcel> <ucel> <xcel> <nl>}) inside {@code <otsl>}.
 *
 * <p>Unlike Docling — whose boxes come from ML layout inference — these coordinates
 * come straight from the OCD geometry (the real PDF geometry), so the grounding is
 * exact and the very same region can be re-rendered faithfully.
 *
 * <p>The source is the logical {@link OCDStruct} tree
 * ({@code DOCUMENT → HEADING* → {PARAGRAPH, LIST/ITEM, TABLE, FIGURE, …}}), walked in
 * reading order. Block types Docling only obtains via ML (code, formula) are emitted
 * only when the importer already labelled them; nothing is invented.
 */
public final class DocTagsWriter {

    private DocTagsWriter() {}

    public static final int DEFAULT_GRID = 500;

    /** Uniform projection: DocTags (UTF-8), quantized onto the {@link ConvertOptions#DOCTAGS_GRID} grid. */
    public static void write(OCDDocument doc, OutputStream out, ConvertOptions opt) throws IOException {
        out.write(toDocTags(doc, opt.get(ConvertOptions.DOCTAGS_GRID)).getBytes(StandardCharsets.UTF_8));
    }

    public static String toDocTags(OCDDocument doc) { return toDocTags(doc, DEFAULT_GRID); }

    public static String toDocTags(OCDDocument doc, int grid) {
        Emit e = new Emit(doc, doc.pages(), OCDIndex.of(doc), grid);
        e.sb.append("<doctag>\n");
        e.walk(doc.structure());
        e.sb.append("</doctag>\n");
        return e.sb.toString();
    }

    // ── emission state (carries the page cursor for <page_break>) ────────────────
    private static final class Emit {
        final OCDDocument doc;
        final List<OCDPage> pages;
        final OCDIndex ix;
        final int grid;
        final StringBuilder sb = new StringBuilder();
        int lastPage = -1;

        Emit(OCDDocument doc, List<OCDPage> pages, OCDIndex ix, int grid) {
            this.doc = doc; this.pages = pages; this.ix = ix; this.grid = grid;
        }

        void walk(OCDStruct s) {
            if (s == null) return;
            switch (s.type()) {
                case DOCUMENT, SECTION -> { for (OCDStruct c : s.children()) walk(c); }  // containers
                case SPAN, OTHER -> {                           // inline under a text block is folded into its text(); at block position, emit it
                    if (hasBlockChild(s)) { for (OCDStruct c : s.children()) walk(c); }
                    else block(s, "text");
                }
                case TOC      -> block(s, "document_index");
                case HEADING  -> { block(s, "section_header_level_" + clamp(s.level(), 1, 6));
                                   for (OCDStruct c : s.children()) walk(c); }   // heading = label + sub-blocks
                case PARAGRAPH, QUOTE -> block(s, "text");
                case CAPTION  -> block(s, "caption");
                case CODE     -> block(s, "code");
                case NOTE     -> block(s, "footnote");
                case FIGURE   -> {                              // <picture> nests its caption (canonical)
                    int page = pageOf(s);
                    pageBreak(page);
                    sb.append("<picture>").append(loc(selfBox(s, page), page)).append('\n');
                    for (OCDStruct c : s.children()) walk(c);
                    sb.append("</picture>\n");
                }
                case LIST     -> { String lt = s.ordered() ? "ordered_list" : "unordered_list";
                                   sb.append('<').append(lt).append(">\n");
                                   for (OCDStruct c : s.children()) walk(c);
                                   sb.append("</").append(lt).append(">\n"); }
                case ITEM     -> block(s, "list_item");
                case TABLE    -> table(s);
                case ROW, CELL -> { /* handled inside table() */ }
            }
        }

        /** Emit one leaf block: {@code <tag><loc…>text</tag>} on its page (self refs only). */
        void block(OCDStruct s, String tag) {
            int page = pageOf(s);
            JxRect box = selfBox(s, page);
            String text = clean(text(s, page));
            if (tag.equals("list_item")) text = stripMarker(text);   // tag conveys list-ness; content carries no bullet
            if (box == null && text.isEmpty()) return;     // nothing to say
            pageBreak(page);
            sb.append('<').append(tag).append('>').append(loc(box, page)).append(text)
              .append("</").append(tag).append(">\n");
        }

        /** Emit a table as an {@code <otsl>} block carrying the OTSL token stream (+ nested caption). */
        void table(OCDStruct s) {
            int page = pageOf(s);
            JxRect box = subtreeBox(s, page);
            pageBreak(page);
            sb.append("<otsl>").append(loc(box, page)).append('\n');
            sb.append(otsl(s, page));
            for (OCDStruct c : s.children())                 // caption encapsulated in the table block
                if (c.type() == OCDStruct.Type.CAPTION) block(c, "caption");
            sb.append("</otsl>\n");
        }

        void pageBreak(int page) {
            if (page < 0) page = Math.max(0, lastPage);
            if (lastPage >= 0 && page != lastPage) sb.append("<page_break>\n");
            lastPage = page;
        }

        // ── OTSL grid (handles colSpan / rowSpan) ────────────────────────────────
        String otsl(OCDStruct table, int page) {
            List<OCDStruct> rows = new ArrayList<>();
            for (OCDStruct c : table.children()) if (c.type() == OCDStruct.Type.ROW) rows.add(c);
            int nrows = rows.size();
            if (nrows == 0) return "";
            int ncols = 0;
            for (OCDStruct r : rows) { int w = 0; for (OCDStruct c : r.children()) if (c.type() == OCDStruct.Type.CELL) w += Math.max(1, c.colSpan()); ncols = Math.max(ncols, w); }
            if (ncols == 0) return "";

            String[][] tok = new String[nrows][ncols];
            String[][] txt = new String[nrows][ncols];
            for (int ri = 0; ri < nrows; ri++) {
                int ci = 0;
                for (OCDStruct cell : rows.get(ri).children()) {
                    if (cell.type() != OCDStruct.Type.CELL) continue;
                    while (ci < ncols && tok[ri][ci] != null) ci++;          // skip rowspan carry
                    if (ci >= ncols) break;
                    int cs = Math.max(1, cell.colSpan()), rs = Math.max(1, cell.rowSpan());
                    String t = clean(text(cell, page));
                    OCDStruct.HeaderKind hk = cell.header();
                    String head = (hk == OCDStruct.HeaderKind.COLUMN || hk == OCDStruct.HeaderKind.BOTH) ? "ched"
                                : hk == OCDStruct.HeaderKind.ROW ? "rhed" : "fcel";
                    tok[ri][ci] = t.isEmpty() ? "ecel" : head;
                    txt[ri][ci] = t;
                    for (int k = 1; k < cs && ci + k < ncols; k++) tok[ri][ci + k] = "lcel";       // span right
                    for (int j = 1; j < rs && ri + j < nrows; j++) tok[ri + j][ci] = "ucel";       // span down
                    for (int j = 1; j < rs && ri + j < nrows; j++)
                        for (int k = 1; k < cs && ci + k < ncols; k++) tok[ri + j][ci + k] = "xcel"; // both
                    ci += cs;
                }
            }
            StringBuilder o = new StringBuilder();
            for (int ri = 0; ri < nrows; ri++) {
                for (int ci = 0; ci < ncols; ci++) {
                    String tk = tok[ri][ci] == null ? "ecel" : tok[ri][ci];
                    o.append('<').append(tk).append('>');
                    if (!"ecel".equals(tk) && !"lcel".equals(tk) && !"ucel".equals(tk) && !"xcel".equals(tk)
                            && txt[ri][ci] != null) o.append(txt[ri][ci]);
                }
                o.append("<nl>\n");
            }
            return o.toString();
        }

        // ── geometry → loc tokens ────────────────────────────────────────────────
        String loc(JxRect box, int page) {
            if (box == null || box.isEmpty() || page < 0 || page >= pages.size()) return "";
            double[] n = normBox(box, pages.get(page));
            int x1 = g(n[0]), y1 = g(n[1]), x2 = g(n[0] + n[2]), y2 = g(n[1] + n[3]);
            return "<loc_" + x1 + "><loc_" + y1 + "><loc_" + x2 + "><loc_" + y2 + ">";
        }
        int g(double v) { return Math.max(0, Math.min(grid, (int) Math.round(v * grid))); }

        // ── text & boxes from refs (resolved against the shared index) ────────────
        String text(OCDStruct s, int page) { return ix.text(s, page); }

        /** Box of this node's own refs only (excludes child blocks) — for text/heading loc. */
        JxRect selfBox(OCDStruct s, int page) {
            if (page < 0) return null;
            JxRect box = null;
            for (OCDStruct.Ref r : s.refs()) {
                if (r.page() != page) continue;
                OCDNode n = ix.node(page, r.nodeId());
                if (n != null) box = (box == null) ? n.bounds() : box.union(n.bounds());
            }
            return box;
        }
        /** Union of every page-{@code page} ref anywhere under {@code s} — for table loc.
         *  CAPTION children are skipped: they carry their own loc and must not inflate the table box. */
        JxRect subtreeBox(OCDStruct s, int page) {
            JxRect box = selfBox(s, page);
            for (OCDStruct c : s.children()) {
                if (c.type() == OCDStruct.Type.CAPTION) continue;
                JxRect cb = subtreeBox(c, page);
                if (cb != null) box = (box == null) ? cb : box.union(cb);
            }
            return box;
        }
        int pageOf(OCDStruct s) {
            for (OCDStruct.Ref r : s.refs()) return r.page();
            for (OCDStruct c : s.children()) { int p = pageOf(c); if (p >= 0) return p; }
            return -1;
        }
    }

    // ── helpers (shared, stateless) ──────────────────────────────────────────────
    /** PDF-space box → normalized [0..1] top-left box in display space (rotation applied). */
    private static double[] normBox(JxRect b, OCDPage p) {
        JxRect box = p.effectiveBox();
        double bw = box.maxX() - box.minX(), bh = box.maxY() - box.minY();
        if (bw <= 0 || bh <= 0) return new double[]{0, 0, 0, 0};
        double x = (b.minX() - box.minX()) / bw;
        double y = (box.maxY() - b.maxY()) / bh;            // flip Y → top-left
        double w = (b.maxX() - b.minX()) / bw;
        double h = (b.maxY() - b.minY()) / bh;
        return switch (p.rotation()) {
            case 90  -> new double[]{ 1 - (y + h), x, h, w };
            case 180 -> new double[]{ 1 - (x + w), 1 - (y + h), w, h };
            case 270 -> new double[]{ y, 1 - (x + w), h, w };
            default  -> new double[]{ x, y, w, h };
        };
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v <= 0 ? lo : v)); }

    /** A SPAN/OTHER with any non-inline child is a real container (walk it); one with only inline
     *  children (or none) carries its own text and is emitted as a block. */
    private static boolean hasBlockChild(OCDStruct s) {
        for (OCDStruct c : s.children()) {
            OCDStruct.Type t = c.type();
            if (t != OCDStruct.Type.SPAN && t != OCDStruct.Type.CODE && t != OCDStruct.Type.OTHER) return true;
        }
        return false;
    }

    /** Keep tag stream intact: drop raw angle brackets from text content. */
    private static String clean(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace('<', '\u2039').replace('>', '\u203A').strip();   // ‹ › lookalikes
    }

    /** Drop a leading list marker (bullet, "1", "1.", "a)" …) — list-ness is in the tag, not the text. */
    static String stripMarker(String s) {
        return sugarcube.jexter.core.ListMarker.strip(s);
    }
}
