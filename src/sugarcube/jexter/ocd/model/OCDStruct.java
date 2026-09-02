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
public final class OCDStruct {

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
    private final List<OCDStruct> children = new ArrayList<>();

    public OCDStruct() {}
    public OCDStruct(Type type) { this.type = type == null ? Type.OTHER : type; }

    public Type       type()              { return type; }
    public OCDStruct  type(Type t)        { this.type = t == null ? Type.OTHER : t; return this; }
    public int        level()             { return level; }
    public OCDStruct  level(int v)        { this.level = v; return this; }
    public int        colSpan()           { return colSpan; }
    public OCDStruct  colSpan(int v)      { this.colSpan = Math.max(1, v); return this; }
    public int        rowSpan()           { return rowSpan; }
    public OCDStruct  rowSpan(int v)      { this.rowSpan = Math.max(1, v); return this; }
    public boolean    ordered()           { return ordered; }
    public OCDStruct  ordered(boolean v)  { this.ordered = v; return this; }
    public HeaderKind header()            { return header; }
    public OCDStruct  header(HeaderKind h){ this.header = h == null ? HeaderKind.NONE : h; return this; }
    public String     text()              { return text; }
    public OCDStruct  text(String v)      { this.text = v == null ? "" : v; return this; }
    public String     lang()              { return lang; }
    public OCDStruct  lang(String v)      { this.lang = v == null ? "" : v; return this; }
    public String     alt()               { return alt; }
    public OCDStruct  alt(String v)       { this.alt = v == null ? "" : v; return this; }

    public List<Ref>       refs()                       { return refs; }
    public OCDStruct       addRef(int page, String node){ if (node != null) refs.add(new Ref(page, node)); return this; }
    public List<OCDStruct> children()                   { return children; }
    public OCDStruct       add(OCDStruct c)             { if (c != null) children.add(c); return this; }
    public boolean         isEmpty()                    { return refs.isEmpty() && children.isEmpty(); }

    @Override public String toString() {
        return "OCDStruct[" + type + (level > 0 ? " L" + level : "")
                + (header != HeaderKind.NONE ? " " + header : "")
                + (text.isEmpty() ? "" : " \"" + text + "\"") + " refs=" + refs.size()
                + (children.isEmpty() ? "" : " (" + children.size() + ")") + "]";
    }
}
