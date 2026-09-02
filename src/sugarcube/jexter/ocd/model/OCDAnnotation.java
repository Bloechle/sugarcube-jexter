package sugarcube.jexter.ocd.model;

import sugarcube.jexter.core.JxColor;
import sugarcube.jexter.core.JxRect;

import java.util.ArrayList;
import java.util.List;

/**
 * A review/markup annotation on a page — the layer above the content, exactly as in PDF
 * (annotations live in a per-page array, not the content stream). Covers the comment and
 * text-markup family: sticky {@link Markup#NOTE notes}, {@link Markup#FREETEXT free text},
 * {@link Markup#HIGHLIGHT highlights}, underlines, strike-outs, squiggly, stamps, ink and
 * simple shapes. Interactive form widgets are a separate concern — see {@link OCDFormField}.
 *
 * <p>Self-contained value object, stored in {@link OCDPage#annotations()} alongside
 * {@link OCDLink}. Geometry is page content space (origin bottom-left): {@link #rect} is the
 * annotation rectangle; {@link #quads} are the marked text regions for the text-markup kinds
 * (one rect per marked run). Appearance painting is deliberately not modelled here — the
 * semantics (kind, author, contents, colour, geometry) are what make this a portable standard.
 */
public final class OCDAnnotation {

    /** The annotation kind — pure classification (no behaviour), like {@link OCDStruct.Type}. */
    public enum Markup { HIGHLIGHT, UNDERLINE, STRIKEOUT, SQUIGGLY, NOTE, FREETEXT, STAMP, INK, LINE, SHAPE, OTHER }

    private Markup  type = Markup.OTHER;
    private JxRect  rect;
    private String  contents = "";         // the comment / note body
    private String  author   = "";         // /T — the annotator
    private String  modified = "";         // /M — ISO-ish date string, as authored
    private JxColor color;                 // annotation colour (null = unset)
    private final List<JxRect> quads = new ArrayList<>();   // text-markup regions (Highlight/Underline/…)

    public OCDAnnotation() {}
    public OCDAnnotation(Markup type) { this.type = type; }

    public Markup       type()             { return type; }
    public OCDAnnotation type(Markup t)    { this.type = t == null ? Markup.OTHER : t; return this; }
    public JxRect       rect()             { return rect; }
    public OCDAnnotation rect(JxRect r)    { this.rect = r; return this; }
    public String       contents()         { return contents; }
    public OCDAnnotation contents(String v){ this.contents = nz(v); return this; }
    public String       author()           { return author; }
    public OCDAnnotation author(String v)  { this.author = nz(v); return this; }
    public String       modified()         { return modified; }
    public OCDAnnotation modified(String v){ this.modified = nz(v); return this; }
    public JxColor      color()            { return color; }
    public OCDAnnotation color(JxColor c)  { this.color = c; return this; }
    public List<JxRect> quads()            { return quads; }
    public OCDAnnotation addQuad(JxRect q) { if (q != null) quads.add(q); return this; }

    public boolean isTextMarkup() {
        return type == Markup.HIGHLIGHT || type == Markup.UNDERLINE
                || type == Markup.STRIKEOUT || type == Markup.SQUIGGLY;
    }

    private static String nz(String v) { return v == null ? "" : v; }

    @Override public String toString() {
        return "OCDAnnotation[" + type + " " + rect + (contents.isEmpty() ? "" : " \"" + contents + "\"") + "]";
    }
}
