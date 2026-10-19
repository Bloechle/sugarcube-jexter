package sugarcube.jexter.ocd.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One node of the document's logical structure tree — the layer that makes OCD a
 * <i>structured</i> format. It is independent of paint order and of page boundaries:
 * it imposes reading order and hierarchy, and points <b>into</b> the presentation
 * layer by {@link Ref}erence (page index + content node id) rather than owning content.
 * A heading nests the content beneath it (HTML5-outline style), so the tree is
 * {@code DOCUMENT → HEADING* → {PARAGRAPH, HEADING, …}}.
 *
 * <p><b>Single source of truth.</b> Content (glyphs, paths, images) lives once, in the
 * presentation layer. This tree never duplicates it: a node's text is obtained by
 * resolving its {@link #refs}. The structure is purely additive metadata — building or
 * dropping it never changes a pixel.
 *
 * <p><b>The one denormalized field, {@link #text}.</b> A few nodes have textual content
 * that maps to no single presentation node to point at — chiefly a table {@code CELL}
 * synthesized from a run that straddles several columns, or a borderless-table cell read
 * out of a text band. Such a node carries its content inline in {@link #text}. The contract
 * is strict and one-way: {@code text} is populated <i>only</i> when {@link #refs} cannot
 * carry the content, and is empty otherwise. Consumers therefore resolve a node's text
 * uniformly as <i>refs-if-present, else {@code text}</i> — never both.
 *
 * <p><b>Type-specific facets.</b> A node is a flat tagged record (mirroring a PDF
 * structure element): most fields apply only to one {@link Type}. {@link #level} → HEADING;
 * {@link #colSpan}/{@link #rowSpan}/{@link #header} → CELL; {@link #ordered} → LIST.
 * {@link #lang}/{@link #alt} are the optional accessibility facet, valid anywhere.
 */
public final class OCDStructNode {

    public enum Type {
        DOCUMENT, SECTION, HEADING, PARAGRAPH,
        LIST, ITEM, TABLE, ROW, CELL,
        FIGURE, CAPTION, QUOTE, CODE, NOTE, TOC, SPAN, OTHER
    }

    /** Header role of a table {@code CELL} — projects to OTSL {@code ched} (column) / {@code rhed} (row). */
    public enum HeaderKind { NONE, COLUMN, ROW, BOTH }

    /** A pointer into the presentation layer: a content node on a page. */
    public record Ref(int page, String nodeId) {}

    private Type       type   = Type.OTHER;
    private int        level;                       // HEADING depth 1..6; 0 otherwise
    private int        colSpan = 1;                 // CELL: columns spanned (merged cells)
    private int        rowSpan = 1;                 // CELL: rows spanned
    private boolean    ordered;                     // LIST: enumerated (1. 2. …) vs bulleted
    private HeaderKind header = HeaderKind.NONE;    // CELL: column / row header role
    private String     text = "";                   // denormalized content — ONLY when refs cannot carry it (see class doc)
    private String     lang = "";                   // optional BCP-47 override
    private String     alt  = "";                   // optional alternate / actual text (accessibility)
    private final List<Ref>       refs     = new ArrayList<>();
    private final List<OCDStructNode> children = new ArrayList<>();

    /** The deepest HEADING level — HTML's six, the cap of every pass that assigns one. */
    public static final int MAX_LEVEL = 6;

    /** {@code v} as a heading level: 1..{@link #MAX_LEVEL} (0 or less reads as 1). */
    public static int headingLevel(int v) { return Math.max(1, Math.min(MAX_LEVEL, v)); }

    public OCDStructNode() {}
    public OCDStructNode(Type type) { this.type = type == null ? Type.OTHER : type; }

    public Type       type()              { return type; }
    public OCDStructNode  type(Type t)        { this.type = t == null ? Type.OTHER : t; return this; }
    public int        level()             { return level; }
    /** {@link #level} as a heading level, 1..{@link #MAX_LEVEL}. */
    public int        headingLevel()      { return headingLevel(level); }

    /** Inline emphasis — PDF/UA wraps a styled term or a mono identifier as a SPAN/CODE/OTHER child of its
     *  text block: its text is part of the parent's reading flow. */
    public boolean    isInline()          { return type == Type.SPAN || type == Type.CODE || type == Type.OTHER; }

    /** Whether any child is a block — a container to walk, where a node with only inline children (or
     *  none) carries its own text. */
    public boolean    hasBlockChild()     { return children.stream().anyMatch(c -> !c.isInline()); }

    /**
     * A container's children as a reader meets them: each run of consecutive loose inline nodes (a SPAN or
     * OTHER at block position, the words a producer wraps piece by piece — Typst writes a callout as « Two
     * layers, kept apart A document carries a » · « presentation » · « layer (what paints)… ») becomes ONE
     * transient PARAGRAPH holding all their refs, so the structure writers read one paragraph, not a block
     * per styled word. A lone CODE stays itself (a code block); block children pass through. Nothing in the
     * tree changes: the paragraphs are views, built for one walk.
     */
    public static List<OCDStructNode> flow(List<OCDStructNode> kids) {
        List<OCDStructNode> out = new ArrayList<>(), run = new ArrayList<>();
        for (OCDStructNode c : kids) {
            if (c.isInline() && !c.hasBlockChild()) { run.add(c); continue; }
            flush(run, out);
            out.add(c);
        }
        flush(run, out);
        return out;
    }

    private static void flush(List<OCDStructNode> run, List<OCDStructNode> out) {
        if (run.isEmpty()) return;
        if (run.size() == 1 && run.get(0).type() == Type.CODE) out.add(run.get(0));
        else {
            OCDStructNode p = new OCDStructNode(Type.PARAGRAPH);
            for (OCDStructNode c : run) refsOf(c, p);
            if (p.refs().isEmpty()) out.addAll(run); else out.add(p);   // no content to read: as they were
        }
        run.clear();
    }

    private static void refsOf(OCDStructNode n, OCDStructNode into) {
        into.refs().addAll(n.refs());
        for (OCDStructNode c : n.children()) refsOf(c, into);
    }
    public OCDStructNode  level(int v)        { this.level = v; return this; }
    public int        colSpan()           { return colSpan; }
    public OCDStructNode  colSpan(int v)      { this.colSpan = Math.max(1, v); return this; }
    public int        rowSpan()           { return rowSpan; }
    public OCDStructNode  rowSpan(int v)      { this.rowSpan = Math.max(1, v); return this; }
    public boolean    ordered()           { return ordered; }
    public OCDStructNode  ordered(boolean v)  { this.ordered = v; return this; }
    public HeaderKind header()            { return header; }
    public OCDStructNode  header(HeaderKind h){ this.header = h == null ? HeaderKind.NONE : h; return this; }
    public String     text()              { return text; }
    public OCDStructNode  text(String v)      { this.text = v == null ? "" : v; return this; }
    public String     lang()              { return lang; }
    public OCDStructNode  lang(String v)      { this.lang = v == null ? "" : v; return this; }
    public String     alt()               { return alt; }
    public OCDStructNode  alt(String v)       { this.alt = v == null ? "" : v; return this; }

    public List<Ref>       refs()                       { return refs; }
    public OCDStructNode       addRef(int page, String node){ if (node != null) refs.add(new Ref(page, node)); return this; }
    public List<OCDStructNode> children()                   { return children; }
    public OCDStructNode       add(OCDStructNode c)             { if (c != null) children.add(c); return this; }
    public boolean         isEmpty()                    { return refs.isEmpty() && children.isEmpty(); }

    @Override public String toString() {
        return "OCDStructNode[" + type + (level > 0 ? " L" + level : "")
                + (header != HeaderKind.NONE ? " " + header : "")
                + (text.isEmpty() ? "" : " \"" + text + "\"") + " refs=" + refs.size()
                + (children.isEmpty() ? "" : " (" + children.size() + ")") + "]";
    }
}
