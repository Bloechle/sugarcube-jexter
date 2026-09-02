package sugarcube.jexter.write;

import sugarcube.jexter.convert.ConvertOptions;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDStruct;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Projects an {@link OCDDocument} to clean, token-efficient <b>Markdown</b> for LLM /
 * RAG ingestion — the content view, presentation geometry discarded. The logical
 * {@link OCDStruct} tree is walked in reading order:
 * HEADING→ATX {@code #…}, PARAGRAPH→prose, QUOTE→{@code >}, LIST/ITEM→{@code -},
 * TABLE→GitHub-flavored pipe table, CODE→fenced block, CAPTION→italic, NOTE→quote.
 *
 * <p>No bounding boxes or ids are emitted: this is the readable view a model consumes
 * directly. When grounding is needed (cite a region, highlight on the page), use the
 * {@link DocTagsWriter} projection instead — it carries exact OCD geometry as
 * {@code <loc_>} boxes.
 */
public final class MarkdownWriter {

    private MarkdownWriter() {}

    /** Uniform projection: token-efficient Markdown (UTF-8). Whole-document content view —
     *  export options are accepted for contract uniformity but not consumed. */
    public static void write(OCDDocument doc, OutputStream out, ConvertOptions opt) throws IOException {
        out.write(toMarkdown(doc).getBytes(StandardCharsets.UTF_8));
    }

    public static String toMarkdown(OCDDocument doc) {
        Emit e = new Emit(OCDIndex.of(doc));
        e.walk(doc.structure());
        return e.sb.toString().strip() + "\n";
    }

    private static final class Emit {
        final OCDIndex ix;
        final StringBuilder sb = new StringBuilder();
        Emit(OCDIndex ix) { this.ix = ix; }

        void walk(OCDStruct s) {
            if (s == null) return;
            switch (s.type()) {
                case DOCUMENT, SECTION, TOC -> { for (OCDStruct c : s.children()) walk(c); }
                case SPAN, OTHER -> {                       // inline under a text block is folded into its text(); at block position, emit it
                    if (hasBlockChild(s)) { for (OCDStruct c : s.children()) walk(c); }
                    else { String t = text(s); if (!t.isEmpty()) block(t); }
                }
                case HEADING -> {
                    String t = text(s);
                    if (!t.isEmpty()) block("#".repeat(clamp(s.level(), 1, 6)) + " " + t);
                    for (OCDStruct c : s.children()) walk(c);
                }
                case PARAGRAPH -> { String t = text(s); if (!t.isEmpty()) block(t); }
                case QUOTE -> { String t = text(s); if (!t.isEmpty()) block("> " + t.replace("\n", "\n> ")); }
                case NOTE  -> { String t = text(s); if (!t.isEmpty()) block("> " + t.replace("\n", "\n> ")); }
                case CAPTION -> { String t = text(s); if (!t.isEmpty()) block("*" + t + "*"); }
                case CODE -> { String t = text(s); if (!t.isEmpty()) block("```\n" + t + "\n```"); }
                case LIST -> { list(s); }
                case ITEM -> { String t = stripMarker(text(s)); if (!t.isEmpty()) block("- " + t); }   // stray item
                case FIGURE -> {
                    String alt = s.alt();
                    block("![" + (alt == null || alt.isBlank() ? "figure" : alt.strip()) + "](#)");
                    for (OCDStruct c : s.children()) walk(c);    // caption (if any) follows the placeholder
                }
                case TABLE -> { table(s); }
                case ROW, CELL -> { /* handled inside table() */ }
            }
        }

        void list(OCDStruct s) {
            StringBuilder b = new StringBuilder();
            int n = 0;
            for (OCDStruct c : s.children()) {
                if (c.type() == OCDStruct.Type.ITEM) {
                    String t = stripMarker(text(c));
                    if (!t.isEmpty()) b.append(s.ordered() ? (++n + ". ") : "- ").append(t.replace("\n", " ")).append('\n');
                } else if (c.type() == OCDStruct.Type.LIST) {     // nested list → indent
                    int m = 0;
                    for (OCDStruct gc : c.children())
                        if (gc.type() == OCDStruct.Type.ITEM) {
                            String t = stripMarker(text(gc));
                            if (!t.isEmpty()) b.append("  ").append(c.ordered() ? (++m + ". ") : "- ").append(t.replace("\n", " ")).append('\n');
                        }
                }
            }
            if (b.length() > 0) { sb.append(b); sb.append('\n'); }
        }

        void table(OCDStruct s) {
            List<OCDStruct> rows = new ArrayList<>();
            for (OCDStruct c : s.children()) if (c.type() == OCDStruct.Type.ROW) rows.add(c);
            if (rows.isEmpty()) return;
            int ncols = 0;
            List<List<String>> grid = new ArrayList<>();
            for (OCDStruct r : rows) {
                List<String> row = new ArrayList<>();
                for (OCDStruct c : r.children())
                    if (c.type() == OCDStruct.Type.CELL) {
                        String t = text(c).replace("\n", " ").replace("|", "\\|").strip();
                        int cs = Math.max(1, c.colSpan());
                        row.add(t);
                        for (int k = 1; k < cs; k++) row.add("");      // GFM has no colspan → pad
                    }
                grid.add(row);
                ncols = Math.max(ncols, row.size());
            }
            StringBuilder b = new StringBuilder();
            for (int ri = 0; ri < grid.size(); ri++) {
                List<String> row = grid.get(ri);
                b.append("| ");
                for (int ci = 0; ci < ncols; ci++) b.append(ci < row.size() ? row.get(ci) : "").append(" | ");
                b.append('\n');
                if (ri == 0) {                                       // header separator after first row
                    b.append("|");
                    for (int ci = 0; ci < ncols; ci++) b.append(" --- |");
                    b.append('\n');
                }
            }
            sb.append(b).append('\n');
            for (OCDStruct c : s.children())                 // table caption → italic line under the table
                if (c.type() == OCDStruct.Type.CAPTION) { String t = text(c); if (!t.isEmpty()) block("*" + t + "*"); }
        }

        /** Append a block followed by a blank line. */
        void block(String s) { sb.append(s).append("\n\n"); }

        // text from a struct node's own refs (resolved per page), title fallback
        String text(OCDStruct s) { return ix.text(s); }
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

    /** Drop a leading list marker (bullet, "1", "1.", "a)" …); the {@code -} prefix conveys list-ness. */
    private static String stripMarker(String s) {
        return sugarcube.jexter.core.ListMarker.strip(s);
    }
}
