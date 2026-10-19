package sugarcube.jexter.ocd.model;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

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
 * <p>Outlines and metrics are in normalized em units (1 em = 1); {@link OCDGlyph} derives its bounds
 * from its {@link sugarcube.jexter.core.JxPath}, at any time, including after a load.
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
    private double  descent    = -0.25;   // below the baseline, so NEGATIVE: Y-up, the PDF convention
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
    /** In em, below the baseline, so negative (Y-up, the PDF convention). A positive value is taken as
     *  its magnitude: producers disagreed, and every compiled font with a positive one had its descender
     *  inverted (hhea positive, winDescent wrapped to ~65000). */
    public OCDFont descent(double v)    { this.descent = -Math.abs(v); return this; }
    public OCDFont capHeight(double v)  { this.capHeight = v; return this; }
    public OCDFont xHeight(double v)    { this.xHeight = v; return this; }
    public OCDFont spaceWidth(double v) { this.spaceWidth = v; return this; }

    // ── glyphs (keyed by gid) ──────────────────────────────────────────────────
    public OCDFont add(OCDGlyph g) {
        if (g != null) glyphs.put(g.gid(), g);
        return this;
    }
    /** A font BUILT character by character (a loaded TTF, a drawn hand): the next glyph id, its outline, and
     *  the cmap entry naming it — the one way the font builders add a glyph. */
    public OCDFont addChar(int codepoint, sugarcube.jexter.core.JxPath outline, double advance) {
        int gid = glyphs.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        add(new OCDGlyph(gid, new String(Character.toChars(codepoint)), outline, advance));
        return map(codepoint, gid);
    }
    public OCDGlyph glyph(int gid)             { return glyphs.get(gid); }
    public boolean  hasGlyph(int gid)          { return glyphs.containsKey(gid); }
    public Map<Integer, OCDGlyph> glyphs()     { return glyphs; }
    public int      glyphCount()               { return glyphs.size(); }

    // ── cmap (codepoint → gid) ──────────────────────────────────────────────────
    /** Map a codepoint; the reverse table is rebuilt on its next use (a font gains entries after its first
     *  encode when text is written into it). */
    public OCDFont map(int codepoint, int gid) { cmap.put(codepoint, gid); reverseCmap = null; return this; }
    public Integer gidOf(int codepoint)        { return cmap.get(codepoint); }
    /** Read-only: every change goes through {@link #map}, which keeps {@link #reverseCmap} true. */
    public Map<Integer, Integer> cmap()        { return java.util.Collections.unmodifiableMap(cmap); }

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

    // ── style helpers ───────────────────────────────────────────────────────────
    public boolean isBold() {
        return "bold".equalsIgnoreCase(weight)
                || (name != null && name.toLowerCase(Locale.ROOT).contains("bold"));
    }
    private Boolean serif;   // null = the document did not say

    public boolean isItalic() {
        return "italic".equalsIgnoreCase(style) || "oblique".equalsIgnoreCase(style)
                || (name != null && name.toLowerCase(Locale.ROOT).matches(".*(italic|oblique).*"));
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
        String s = (name == null ? "" : name.toLowerCase(Locale.ROOT)) + ' ' + (family == null ? "" : family.toLowerCase(Locale.ROOT));
        if (s.matches(".*(serif|times|georgia|garamond|minion|utopia|palatino|baskerville|caslon|cambria|book).*")
                && !s.contains("sans")) return true;
        return false;
    }
    public OCDFont serif(Boolean v) { this.serif = v; return this; }

    /** Fixed-pitch (monospace): every rendered glyph shares one advance, or the name says so. */
    public boolean isMono() {
        if (name != null) {
            String s = name.toLowerCase(Locale.ROOT);
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

    @Override public String toString() {
        return "OCDFont[" + name + " " + weight + " " + style + ", glyphs=" + glyphs.size()
                + (embedded ? ", embedded" : "") + ", cap=" + capHeight + ", x=" + xHeight + "]";
    }
}
