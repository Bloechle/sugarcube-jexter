package sugarcube.jexter.ocd.model;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A normalized document font: vector glyph outlines plus metrics, fully
 * self-contained (no binary font program). Compiled to a real {@code .otf} for
 * delivery; this is the editable in-memory pivot.
 *
 * <p><b>Keyed by glyph id, with a separate cmap.</b> Glyphs live in a {@code gid →
 * OCDGlyph} map; a separate {@code codepoint → gid} cmap maps text to glyphs.
 * This is deliberately the inverse of the old codepoint-keyed map: it lets
 * distinct glyphs that share a Unicode value (ligatures, small caps, alternates)
 * or have none (.notdef) coexist without overwriting each other.
 *
 * <p>Outlines and metrics are in normalized em units (1 em = 1). Metrics can be
 * recomputed from the real outlines via {@link #computeHeights()} — and since
 * {@link OCDGlyph} derives its bounds from the {@link sugarcube.jexter.core.JxPath},
 * that works at any time, including after a load.
 *
 * <p>Serialization (OCD-EPUB members / OTF compilation) lives in the writer layer, not here.
 */
public final class OCDFont {

    private String  id;                   // document-scoped resource id = slug, e.g. "CambriaMath" (run ref + file base)
    private String  name;                 // descriptive source name, e.g. "BCDEFG+TimesNewRoman"
    private String  family;
    private String  weight = "normal";    // "normal" | "bold"
    private String  style  = "normal";    // "normal" | "italic" | "oblique"
    private double  ascent     = 0.75;
    private double  descent    = 0.25;
    private double  capHeight  = 0.66;
    private double  xHeight    = 0.45;
    private double  spaceWidth = 0.25;
    private boolean embedded;

    private final Map<Integer, OCDGlyph> glyphs = new LinkedHashMap<>(); // gid → glyph
    private final Map<Integer, Integer>  cmap   = new LinkedHashMap<>(); // codepoint → gid
    private       Map<Integer, Integer>  reverseCmap;                    // gid → codepoint, built once on demand

    public OCDFont() {}
    public OCDFont(String name) { this.name = name; this.family = name; }

    // ── identity / style ─────────────────────────────────────────────────────
    public String  id()              { return id; }
    public OCDFont id(String v)      { this.id = v; return this; }
    public String  name()            { return name; }
    public OCDFont name(String n)    { this.name = n; return this; }
    public String  family()          { return family; }
    public OCDFont family(String f)  { this.family = f; return this; }
    public String  weight()          { return weight; }
    public OCDFont weight(String w)  { this.weight = w; return this; }
    public String  style()           { return style; }
    public OCDFont style(String s)   { this.style = s; return this; }
    public boolean embedded()        { return embedded; }
    public OCDFont embedded(boolean e){ this.embedded = e; return this; }

    // ── metrics (em) ──────────────────────────────────────────────────────────
    public double ascent()     { return ascent; }
    public double descent()    { return descent; }
    public double capHeight()  { return capHeight; }
    public double xHeight()    { return xHeight; }
    public double spaceWidth() { return spaceWidth; }
    public OCDFont ascent(double v)     { this.ascent = v; return this; }
    public OCDFont descent(double v)    { this.descent = v; return this; }
    public OCDFont capHeight(double v)  { this.capHeight = v; return this; }
    public OCDFont xHeight(double v)    { this.xHeight = v; return this; }
    public OCDFont spaceWidth(double v) { this.spaceWidth = v; return this; }

    // ── glyphs (keyed by gid) ──────────────────────────────────────────────────
    public OCDFont add(OCDGlyph g) {
        if (g != null) glyphs.put(g.gid(), g);
        return this;
    }
    public OCDGlyph glyph(int gid)             { return glyphs.get(gid); }
    public boolean  hasGlyph(int gid)          { return glyphs.containsKey(gid); }
    public Map<Integer, OCDGlyph> glyphs()     { return glyphs; }
    public int      glyphCount()               { return glyphs.size(); }

    // ── cmap (codepoint → gid) ──────────────────────────────────────────────────
    public OCDFont map(int codepoint, int gid) { cmap.put(codepoint, gid); return this; }
    public Integer gidOf(int codepoint)        { return cmap.get(codepoint); }
    public Map<Integer, Integer> cmap()        { return cmap; }

    /**
     * Reverse of the cmap: gid → a codepoint the embedded OTF maps to that gid.
     * Encoding a glyph as text via this codepoint makes every surface that renders
     * through the OTF cmap (PDF {@code showText}, EPUB/SVG {@code <text>}) select the
     * correct glyph. Codepoints the format-4 cmap cannot carry (control chars ≤ 32, or
     * beyond the BMP) are excluded so callers fall back. Built once per font instance.
     */
    public Map<Integer, Integer> reverseCmap() {
        if (reverseCmap == null) {
            Map<Integer, Integer> m = new HashMap<>();
            for (Map.Entry<Integer, Integer> e : cmap.entrySet()) {
                int cp = e.getKey();
                if (cp > 32 && cp <= 0xFFFF) m.putIfAbsent(e.getValue(), cp);   // gid → codepoint
            }
            reverseCmap = m;
        }
        return reverseCmap;
    }

    /** Resolve a character to its glyph via the cmap, or null. */
    public OCDGlyph glyphForChar(int codepoint) {
        Integer gid = cmap.get(codepoint);
        return gid == null ? null : glyphs.get(gid);
    }
    public OCDGlyph glyphForChar(char ch) { return glyphForChar((int) ch); }

    // ── metric recomputation from real outlines ────────────────────────────────
    /** Recompute cap/x-height from the actual glyph outlines. Safe to call any time. */
    public OCDFont computeHeights() {
        OCDGlyph cap = firstOf('H', 'I', 'T');
        if (cap != null && cap.yMax() > 0) capHeight = round4(cap.yMax());
        OCDGlyph ex = firstOf('x', 'o', 'e');
        if (ex != null && ex.yMax() > 0) xHeight = round4(ex.yMax());
        return this;
    }

    private OCDGlyph firstOf(char... chars) {
        for (char c : chars) {
            OCDGlyph g = glyphForChar(c);
            if (g != null) return g;
        }
        return null;
    }

    // ── style helpers ───────────────────────────────────────────────────────────
    public boolean isBold() {
        return "bold".equalsIgnoreCase(weight)
                || (name != null && name.toLowerCase().contains("bold"));
    }
    private Boolean serif;   // null = the document did not say

    public boolean isItalic() {
        return "italic".equalsIgnoreCase(style) || "oblique".equalsIgnoreCase(style)
                || (name != null && name.toLowerCase().matches(".*(italic|oblique).*"));
    }
    /** Serif, as the PRODUCER declared it: the {@code /Flags} serif bit of the PDF font descriptor,
     *  captured at import. Falls back to the name only when the document said nothing — a substituted
     *  standard-14 face, a descriptor-less Type3.
     *
     *  <p>The single use is the generic that closes a CSS font stack. It matters because a face can always
     *  fail to load — a compile failure, a container written without its fonts — and the browser then falls
     *  back to ITS default, which is a serif whatever the document was. Getting the generic from the font
     *  itself turns a silent disfigurement into a near miss. */
    public boolean isSerif() {
        if (serif != null) return serif;
        String s = (name == null ? "" : name.toLowerCase()) + ' ' + (family == null ? "" : family.toLowerCase());
        if (s.matches(".*(serif|times|georgia|garamond|minion|utopia|palatino|baskerville|caslon|cambria|book).*")
                && !s.contains("sans")) return true;
        return false;
    }
    public OCDFont serif(Boolean v) { this.serif = v; return this; }

    /** Fixed-pitch (monospace): every rendered glyph shares one advance, or the name says so. */
    public boolean isMono() {
        if (name != null) {
            String s = name.toLowerCase();
            if (s.contains("mono") || s.contains("courier") || s.contains("consol")
                    || s.contains("typewriter") || s.contains("menlo") || s.contains("inconsol")
                    || s.contains("cmtt")) return true;
        }
        double w = -1; int n = 0;
        for (OCDGlyph g : glyphs.values()) {
            if (g.isSpace() || g.advance() <= 0) continue;
            if (w < 0) w = g.advance();
            else if (Math.abs(g.advance() - w) > 0.02 * w) return false;   // a differing advance → proportional
            n++;
        }
        return n >= 4;   // several equal-width glyphs → monospace
    }

    private static double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }

    @Override public String toString() {
        return "OCDFont[" + name + " " + weight + " " + style + ", glyphs=" + glyphs.size()
                + (embedded ? ", embedded" : "") + ", cap=" + capHeight + ", x=" + xHeight + "]";
    }
}
